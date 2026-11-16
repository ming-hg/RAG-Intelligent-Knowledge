package com.rag.knowledge.app.health;

import com.rag.knowledge.app.model.ModelService;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 分层自检：把「PG 没起来 / 模型没加载 / 维度对不上」这三类最常见的本地部署故障分开报告，
 * 而不是笼统返回 500。模型探测较慢，默认跳过，用 withModels=true 显式触发。
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
    public static final String DEGRADED = "DEGRADED";

    private final JdbcTemplate jdbc;
    private final RagProperties props;
    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final ModelService modelService;

    public HealthService(JdbcTemplate jdbc, RagProperties props, EmbeddingModel embeddingModel, ChatModel chatModel,
                         ModelService modelService) {
        this.jdbc = jdbc;
        this.props = props;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.modelService = modelService;
    }

    public record Probe(String name, String status, String detail, long tookMs) {
    }

    public record Report(boolean ready, List<Probe> probes) {
    }

    /**
     * postgres/storage 决定 ready；modelIds 只有在「配置里的模型名 LM Studio 根本不认识」时才拦，
     * 因为那种情况下向量会静默来自另一个模型，入库越多越难收拾。模型服务没启动只报 DEGRADED。
     */
    public Report report(boolean withModels) {
        List<Probe> probes = new ArrayList<>();
        probes.add(probePostgres());
        probes.add(probeChineseFullText());
        probes.add(probeStorage());
        probes.add(probeEmbeddingConsistency());
        probes.add(probeModelIds());
        if (withModels) {
            probes.add(probeEmbeddingCall());
            probes.add(probeChatCall());
            probes.add(probeModelEcho());
        }
        boolean ready = probes.stream()
                .filter(p -> List.of("postgres", "storage", "modelIds", "modelEcho").contains(p.name()))
                .noneMatch(p -> DOWN.equals(p.status()));
        return new Report(ready, probes);
    }

    private Probe probePostgres() {
        return run("postgres", () -> {
            String version = jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                    String.class);
            Integer hnsw = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_indexes
                    WHERE tablename = 'kb_segment' AND indexname = 'idx_segment_embedding_hnsw'
                    """, Integer.class);
            if (hnsw == null || hnsw == 0) {
                return new ProbeResult(DEGRADED, "pgvector " + version + " 已装，但缺少 HNSW 索引（迁移未完整执行）");
            }
            return new ProbeResult(UP, "pgvector " + version + "，HNSW 索引就绪");
        });
    }

    /**
     * zhparser 编译失败时不算故障：检索会自动退化为 pg_trgm 子串匹配，但召回质量下降，必须让用户看见。
     */
    private Probe probeChineseFullText() {
        return run("chineseFullText", () -> {
            Integer cfg = jdbc.queryForObject("SELECT count(*) FROM pg_ts_config WHERE cfgname = 'chinese'", Integer.class);
            if (cfg != null && cfg > 0) {
                return new ProbeResult(UP, "使用 chinese(zhparser) 分词做关键词检索");
            }
            return new ProbeResult(DEGRADED, "无 chinese 全文配置，关键词检索退化为 pg_trgm 子串匹配，中文召回会变差");
        });
    }

    private Probe probeStorage() {
        return run("storage", () -> {
            Path dir = Path.of(props.storage().dir());
            Files.createDirectories(dir);
            Path probe = dir.resolve(".write-probe");
            Files.writeString(probe, "ok", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(probe);
            return new ProbeResult(UP, dir.toAbsolutePath() + " 可写");
        });
    }

    private Probe probeEmbeddingConsistency() {
        return run("embeddingConsistency", () -> {
            String configured = props.lmStudio().embeddingModel();
            List<String> stored = jdbc.queryForList(
                    "SELECT DISTINCT embedding_model FROM kb_segment WHERE embedding_model IS NOT NULL", String.class);
            if (stored.isEmpty()) {
                return new ProbeResult(UP, "库内暂无向量，当前模型 " + configured);
            }
            if (stored.size() == 1 && configured.equals(stored.get(0))) {
                return new ProbeResult(UP, "库内 " + configured + " 与配置一致");
            }
            return new ProbeResult(DEGRADED,
                    "配置模型是 " + configured + "，但库内已存在 " + stored + " 的向量；混用维度会污染检索，请改回原模型或全量重算");
        });
    }

    /**
     * LM Studio 对认不出的模型名不报错，会拿当前已加载的模型应答；bge 与 nomic 又同为 768 维，
     * 维度比对不出来。所以模型名必须先跟 /v1/models 的 id 对一遍。
     */
    private Probe probeModelIds() {
        return run("modelIds", () -> {
            ModelService.Catalog catalog;
            try {
                catalog = modelService.catalog();
            } catch (RagException ex) {
                return new ProbeResult(DEGRADED, "模型服务暂不可达，启动后需复查：" + ex.getMessage());
            }
            if (catalog.models().isEmpty()) {
                return new ProbeResult(DEGRADED, "LM Studio 未返回任何模型，先在 Local Server 里加载");
            }
            List<String> missing = new ArrayList<>();
            if (!catalog.models().contains(catalog.chatModel())) {
                missing.add("chat=" + catalog.chatModel());
            }
            if (!catalog.models().contains(catalog.embeddingModel())) {
                missing.add("embedding=" + catalog.embeddingModel());
            }
            if (!missing.isEmpty()) {
                return new ProbeResult(DOWN, "配置里的模型名不在 /v1/models 中：" + missing
                        + "；LM Studio 会忽略名字改用已加载的模型应答，向量空间会串味，请改成列表里的 id（当前为 "
                        + catalog.models() + "）");
            }
            return new ProbeResult(UP, catalog.models().size() + " 个模型可选，chat/embedding 均在列表内");
        });
    }

    private Probe probeEmbeddingCall() {
        return run("embedding", () -> {
            Response<Embedding> resp = embeddingModel.embed("本地知识库健康检查");
            int dim = resp.content().dimension();
            if (dim != props.embedding().dim()) {
                return new ProbeResult(DEGRADED, "实际维度 " + dim + " 与 rag.embedding.dim=" + props.embedding().dim()
                        + " 不一致，请同步修改后重建 schema");
            }
            return new ProbeResult(UP, dim + " 维");
        });
    }

    private Probe probeChatCall() {
        return run("chat", () -> {
            String answer = chatModel.chat("只回复两个字：正常");
            return new ProbeResult(UP, "模型 " + props.lmStudio().chatModel() + " 响应：" + trim(answer));
        });
    }

    /**
     * 名字在 /v1/models 里但没加载，LM Studio 依然会用别的模型应答；只有回显的 model 字段能证伪。
     */
    private Probe probeModelEcho() {
        return run("modelEcho", () -> {
            ModelService.EchoCheck echo = modelService.echoCheck();
            if (!echo.mismatched().isEmpty()) {
                return new ProbeResult(DOWN, "LM Studio 实际应答的模型与配置不符：" + echo.mismatched()
                        + "；请在 Local Server 里加载对应模型，或把配置改成它实际加载的那个");
            }
            return new ProbeResult(UP, "回显一致：embedding=" + echo.echoedEmbedding()
                    + "，chat=" + echo.echoedChat());
        });
    }

    private interface ProbeCall {
        ProbeResult get() throws Exception;
    }

    private record ProbeResult(String status, String detail) {
    }

    private Probe run(String name, ProbeCall call) {
        long start = System.currentTimeMillis();
        try {
            ProbeResult result = call.get();
            return new Probe(name, result.status(), result.detail(), System.currentTimeMillis() - start);
        } catch (Exception ex) {
            log.warn("健康检查项 {} 失败", name, ex);
            return new Probe(name, DOWN, rootMessage(ex), System.currentTimeMillis() - start);
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String prefix = ex instanceof IOException ? "文件读写失败：" : "调用失败：";
        return prefix + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }
}
