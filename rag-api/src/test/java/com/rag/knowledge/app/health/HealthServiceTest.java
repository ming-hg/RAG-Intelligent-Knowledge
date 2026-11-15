package com.rag.knowledge.app.health;

import com.rag.knowledge.app.model.ModelService;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class HealthServiceTest {

    @TempDir
    Path storageDir;

    private JdbcTemplate jdbc;
    private EmbeddingModel embeddingModel;
    private ChatModel chatModel;
    private ModelService modelService;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        embeddingModel = mock(EmbeddingModel.class);
        chatModel = mock(ChatModel.class);
        modelService = mock(ModelService.class);
        when(modelService.catalog()).thenReturn(new ModelService.Catalog(
                "http://localhost:1234/v1", List.of("qwen-7b", "bge-base-zh-v1.5"), "qwen-7b", "bge-base-zh-v1.5"));
    }

    private RagProperties props(String embeddingModelName, int dim) {
        return new RagProperties(
                new RagProperties.LmStudio("http://localhost:1234/v1", "lm-studio", "qwen-7b",
                        embeddingModelName, 0.3, 0.8, null, Duration.ofSeconds(5), 1),
                new RagProperties.Storage(storageDir.toString()),
                new RagProperties.Embedding(dim, 16),
                RagProperties.Ingest.defaults());
    }

    private HealthService service(RagProperties props) {
        return new HealthService(jdbc, props, embeddingModel, chatModel, modelService);
    }

    private void stubHealthyPostgres(boolean zhparser) {
        when(jdbc.queryForObject(contains("extversion"), eq(String.class))).thenReturn("0.8.7");
        when(jdbc.queryForObject(contains("idx_segment_embedding_hnsw"), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("pg_ts_config"), eq(Integer.class))).thenReturn(zhparser ? 1 : 0);
    }

    @Test
    void postgresDownMakesReportNotReady() {
        when(jdbc.queryForObject(anyString(), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        HealthService.Report report = service(props("bge-base-zh-v1.5", 768)).report(false);

        assertThat(report.ready()).isFalse();
        assertThat(report.probes()).anySatisfy(p -> {
            assertThat(p.name()).isEqualTo("postgres");
            assertThat(p.status()).isEqualTo(HealthService.DOWN);
            assertThat(p.detail()).contains("Connection refused");
        });
    }

    @Test
    void missingZhparserDegradesButStaysReady() {
        stubHealthyPostgres(false);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        HealthService.Report report = service(props("bge-base-zh-v1.5", 768)).report(false);

        assertThat(report.ready()).isTrue();
        assertThat(report.probes()).anySatisfy(p -> {
            assertThat(p.name()).isEqualTo("chineseFullText");
            assertThat(p.status()).isEqualTo(HealthService.DEGRADED);
            assertThat(p.detail()).contains("pg_trgm");
        });
    }

    @Test
    void mixedEmbeddingModelsInStoreAreReported() {
        stubHealthyPostgres(true);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("bge-base-zh-v1.5", "m3e-base"));

        HealthService.Report report = service(props("bge-base-zh-v1.5", 768)).report(false);

        assertThat(report.probes()).anySatisfy(p -> {
            assertThat(p.name()).isEqualTo("embeddingConsistency");
            assertThat(p.status()).isEqualTo(HealthService.DEGRADED);
            assertThat(p.detail()).contains("全量重算");
        });
    }

    @Test
    void modelProbesAreSkippedUnlessRequested() {
        stubHealthyPostgres(true);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        when(modelService.echoCheck()).thenReturn(new ModelService.EchoCheck(
                "bge-base-zh-v1.5", "bge-base-zh-v1.5", "qwen-7b", "qwen-7b", List.of()));

        List<String> fast = service(props("bge-base-zh-v1.5", 768)).report(false).probes().stream()
                .map(HealthService.Probe::name).toList();
        List<String> deep = service(props("bge-base-zh-v1.5", 768)).report(true).probes().stream()
                .map(HealthService.Probe::name).toList();

        assertThat(fast).doesNotContain("embedding", "chat", "modelEcho");
        assertThat(deep).contains("embedding", "chat", "modelEcho");
    }

    /**
     * 这是最危险的静默故障：LM Studio 认不出的模型名照样返回 200，向量来自另一个模型；
     * bge 与 nomic 又同为 768 维，维度校验挡不住，所以必须挡住就绪。
     */
    @Test
    void configuredModelMissingFromCatalogBlocksReadiness() {
        stubHealthyPostgres(true);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        when(modelService.catalog()).thenReturn(new ModelService.Catalog(
                "http://localhost:1234/v1", List.of("glm-4-9b-chat-1m"), "qwen-7b", "bge-base-zh-v1.5"));

        HealthService.Report report = service(props("bge-base-zh-v1.5", 768)).report(false);

        assertThat(report.ready()).isFalse();
        assertThat(report.probes()).anySatisfy(p -> {
            assertThat(p.name()).isEqualTo("modelIds");
            assertThat(p.status()).isEqualTo(HealthService.DOWN);
            assertThat(p.detail()).contains("不在 /v1/models 中").contains("串味");
        });
    }

    @Test
    void unreachableModelServiceDegradesWithoutBlockingReadiness() {
        stubHealthyPostgres(true);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        when(modelService.catalog()).thenThrow(RagException.modelUnavailable("无法读取模型列表"));

        HealthService.Report report = service(props("bge-base-zh-v1.5", 768)).report(false);

        assertThat(report.ready()).isTrue();
        assertThat(report.probes()).anySatisfy(p -> {
            assertThat(p.name()).isEqualTo("modelIds");
            assertThat(p.status()).isEqualTo(HealthService.DEGRADED);
        });
    }

    @Test
    void echoedModelSwapIsReportedInDeepProbe() {
        stubHealthyPostgres(true);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        when(modelService.echoCheck()).thenReturn(new ModelService.EchoCheck(
                "bge-base-zh-v1.5", "text-embedding-nomic-embed-text-v1.5", "qwen-7b", "qwen-7b",
                List.of("embedding 配置是 bge-base-zh-v1.5，实际应答的是 text-embedding-nomic-embed-text-v1.5")));

        HealthService.Report report = service(props("bge-base-zh-v1.5", 768)).report(true);

        assertThat(report.ready()).isFalse();
        assertThat(report.probes()).anySatisfy(p -> {
            assertThat(p.name()).isEqualTo("modelEcho");
            assertThat(p.status()).isEqualTo(HealthService.DOWN);
            assertThat(p.detail()).contains("nomic");
        });
    }
}
