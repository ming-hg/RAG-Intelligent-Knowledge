package com.rag.knowledge.app.rag;

import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.RetrievalRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索编排的降级矩阵：哪一路可用就走哪一路，并且必须把「这次实际怎么搜的」说出来。
 * SQL 本身由 RetrievalIT 覆盖，这里只管分支与阈值量纲。
 */
class HybridSearchServiceTest {

    private static final int DIM = 4;

    private final RetrievalRepository repository = mock(RetrievalRepository.class);

    @Test
    void hybridIsTheDefaultAndCarriesBothPathsIntoTheQuery() {
        when(repository.probeEngine()).thenReturn(KeywordEngine.CHINESE_TSVECTOR);
        HybridSearchService service = service(vecOk(), RagProperties.Retrieval.defaults());

        HybridSearchService.Outcome outcome = service.search("网关超时怎么排查", List.of(1L), null, null, null);

        assertThat(outcome.requested()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(outcome.effective()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(outcome.degraded()).isFalse();
        RetrievalQuery query = capture();
        assertThat(query.useVector()).isTrue();
        assertThat(query.useKeyword()).isTrue();
        assertThat(query.vectorLiteral()).startsWith("[").endsWith("]");
        assertThat(query.threshold()).isEqualTo(RagProperties.Retrieval.defaults().minRrfScore());
        assertThat(query.docIds()).isEmpty();
    }

    @Test
    void embeddingFailureDegradesToKeywordAndSwitchesToTheRrfThreshold() {
        when(repository.probeEngine()).thenReturn(KeywordEngine.CHINESE_TSVECTOR);
        HybridSearchService service = service(throwing("Connection refused"), RagProperties.Retrieval.defaults());

        HybridSearchService.Outcome outcome = service.search("问题", List.of(1L), List.of(3L), "hybrid", null);

        assertThat(outcome.effective()).isEqualTo(RetrievalMode.KEYWORD);
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.vectorFailure()).contains("Connection refused");
        RetrievalQuery query = capture();
        assertThat(query.useVector()).isFalse();
        assertThat(query.mode()).isEqualTo(RetrievalMode.KEYWORD);
        assertThat(query.threshold()).isEqualTo(RagProperties.Retrieval.defaults().minRrfScore());
        assertThat(query.docIds()).containsExactly(3L);
    }

    @Test
    void keywordRequestWithoutAnyFullTextCapabilityGoesBackToVector() {
        when(repository.probeEngine()).thenReturn(KeywordEngine.NONE);
        HybridSearchService service = service(vecOk(), RagProperties.Retrieval.defaults());

        HybridSearchService.Outcome outcome = service.search("问题", List.of(1L), null, "keyword", null);

        assertThat(outcome.effective()).isEqualTo(RetrievalMode.VECTOR);
        // 走向量路就得用余弦阈值，否则 0.45 的尺子量 RRF 分会把所有命中筛光
        assertThat(capture().threshold()).isEqualTo(RagProperties.Retrieval.defaults().minScore());
    }

    @Test
    void vectorOnlyModeNeverTouchesTheKeywordPath() {
        when(repository.probeEngine()).thenReturn(KeywordEngine.CHINESE_TSVECTOR);
        HybridSearchService service = service(vecOk(), RagProperties.Retrieval.defaults());

        service.search("问题", List.of(1L), null, "vector", 3);

        RetrievalQuery query = capture();
        assertThat(query.mode()).isEqualTo(RetrievalMode.VECTOR);
        assertThat(query.useKeyword()).isFalse();
        assertThat(query.topK()).isEqualTo(3);
    }

    @Test
    void neitherPathAvailableReturnsEmptyWithoutTouchingSql() {
        when(repository.probeEngine()).thenReturn(KeywordEngine.NONE);
        HybridSearchService service = service(throwing("LM Studio 未启动"), RagProperties.Retrieval.defaults());

        HybridSearchService.Outcome outcome = service.search("问题", List.of(1L), null, null, null);

        assertThat(outcome.hits()).isEmpty();
        assertThat(outcome.vectorFailure()).contains("LM Studio 未启动");
        verify(repository, never()).search(any());
    }

    @Test
    void dimensionMismatchIsReportedInsteadOfSendingAnUnusableVector() {
        when(repository.probeEngine()).thenReturn(KeywordEngine.TRIGRAM);
        HybridSearchService service = service(new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                return Response.from(List.of(new Embedding(new float[] { 1f, 2f })));
            }
        }, RagProperties.Retrieval.defaults());

        HybridSearchService.Outcome outcome = service.search("问题", List.of(1L), null, "vector", null);

        assertThat(outcome.vectorFailure()).contains("模型返回 2 维").contains("rag.embedding.dim=4");
        assertThat(capture().mode()).isEqualTo(RetrievalMode.KEYWORD);
    }

    @Test
    void engineIsProbedOnceAndCanBeRefreshedAfterMigration() {
        when(repository.probeEngine()).thenReturn(KeywordEngine.NONE, KeywordEngine.CHINESE_TSVECTOR);
        HybridSearchService service = service(vecOk(), RagProperties.Retrieval.defaults());

        service.search("问题", List.of(1L), null, null, null);
        service.search("问题", List.of(1L), null, null, null);
        verify(repository, times(1)).probeEngine();

        service.refreshEngine();
        assertThat(service.engine()).isEqualTo(KeywordEngine.CHINESE_TSVECTOR);
    }

    @Test
    void emptyKnowledgeScopeIsRejectedBeforeCallingTheModel() {
        HybridSearchService service = service(vecOk(), RagProperties.Retrieval.defaults());

        assertThatThrownBy(() -> service.search("问题", List.of(), null, null, null))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("至少一个知识库");
        assertThatThrownBy(() -> service.search("问题", null, null, null, null))
                .isInstanceOf(RagException.class);
    }

    @Test
    void effectiveModeMatrixCoversEveryCombination() {
        assertThat(HybridSearchService.effectiveMode(RetrievalMode.HYBRID, true, true))
                .isEqualTo(RetrievalMode.HYBRID);
        assertThat(HybridSearchService.effectiveMode(RetrievalMode.HYBRID, true, false))
                .isEqualTo(RetrievalMode.VECTOR);
        assertThat(HybridSearchService.effectiveMode(RetrievalMode.HYBRID, false, true))
                .isEqualTo(RetrievalMode.KEYWORD);
        assertThat(HybridSearchService.effectiveMode(RetrievalMode.HYBRID, false, false)).isNull();
        assertThat(HybridSearchService.effectiveMode(RetrievalMode.VECTOR, false, true))
                .isEqualTo(RetrievalMode.KEYWORD);
        assertThat(HybridSearchService.effectiveMode(RetrievalMode.KEYWORD, true, false))
                .isEqualTo(RetrievalMode.VECTOR);
    }

    private final ArgumentCaptor<RetrievalQuery> captor = ArgumentCaptor.forClass(RetrievalQuery.class);

    private RetrievalQuery capture() {
        verify(repository).search(captor.capture());
        return captor.getValue();
    }

    private HybridSearchService service(EmbeddingModel embeddingModel, RagProperties.Retrieval retrieval) {
        return new HybridSearchService(repository, embeddingModel, props(retrieval));
    }

    private static RagProperties props(RagProperties.Retrieval retrieval) {
        return new RagProperties(
                new RagProperties.LmStudio("http://localhost:1234/v1", "lm-studio", "chat", "bge",
                        0.3, 0.8, null, Duration.ofSeconds(5), 1),
                new RagProperties.Storage("target/rag-test"),
                new RagProperties.Embedding(DIM, 16),
                RagProperties.Ingest.defaults(),
                retrieval, RagProperties.Archive.defaults());
    }

    private static EmbeddingModel vecOk() {
        return new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                float[] vector = new float[DIM];
                vector[0] = 1f;
                return Response.from(segments.stream().map(s -> new Embedding(vector)).toList());
            }
        };
    }

    private static EmbeddingModel throwing(String message) {
        return new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                throw new IllegalStateException(message);
            }
        };
    }
}
