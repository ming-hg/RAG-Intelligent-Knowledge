package com.rag.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 全本地部署的运行配置。所有字段都有可用默认值，开箱即可启动（模型服务未启动时健康检查会明确报告）。
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(LmStudio lmStudio, Storage storage, Embedding embedding, Ingest ingest,
                            Retrieval retrieval, Archive archive) {

    /** 配置里整段漏写 rag.ingest 时退回默认值，而不是让上传接口 NPE。 */
    public Ingest ingest() {
        return ingest == null ? Ingest.defaults() : ingest;
    }

    public Retrieval retrieval() {
        return retrieval == null ? Retrieval.defaults() : retrieval;
    }

    /** 归档段整体漏写、或写了段落却没给限值时都要能导出导入，本地部署不该因为少两行配置而少功能。 */
    public Archive archive() {
        return archive == null || archive.maxImportMb() <= 0 ? Archive.defaults() : archive;
    }

    public record LmStudio(
            String baseUrl,
            String apiKey,
            String chatModel,
            String embeddingModel,
            Double temperature,
            Double topP,
            Integer maxTokens,
            Duration timeout,
            Integer maxRetries) {

        /**
         * LM Studio 的 OpenAI 兼容接口只挂在 /v1 下，漏写 /v1 是本地部署最高频的接入错误，这里直接补齐。
         */
        public String baseUrlV1() {
            String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return trimmed.endsWith("/v1") ? trimmed : trimmed + "/v1";
        }
    }

    public record Storage(String dir) {
    }

    /**
     * dim 决定 kb_segment.embedding 列的 vector(dim)，由 Flyway 占位符注入 migration。
     * 变更 dim 需要全量重算向量，启动时会校验库内已存的 embedding_model 是否一致。
     */
    public record Embedding(int dim, int batchSize) {
    }

    /**
     * 入库流水线运行参数。
     * concurrency 默认 1：模型服务是单机单卡，并发入库只会让 embedding 排队并撞超时，不会更快。
     */
    public record Ingest(
            boolean workerEnabled,
            Duration pollInterval,
            Duration leaseDuration,
            int maxAttempts,
            int concurrency,
            int maxFileSizeMb,
            List<String> allowedExtensions,
            Chunk chunk) {

        public static Ingest defaults() {
            return new Ingest(true, Duration.ofSeconds(2), Duration.ofMinutes(10), 3, 1, 100,
                    List.of("pdf", "docx", "doc", "md", "markdown", "txt", "csv", "json", "html", "htm"),
                    Chunk.defaults());
        }

        /** 外部配置只写了一两个 key 时，其余字段仍要可用。 */
        public Chunk chunk() {
            return chunk == null ? Chunk.defaults() : chunk;
        }
    }

    /**
     * 分块尺寸。childChars 的上限由 embedding 模型的窗口决定（512 token ≈ 400 汉字留余量），
     * 不是审美参数：调大不会报错，只会让超出部分被静默截断，表现为「文档里有但搜不到」。
     */
    public record Chunk(int childChars, int childMinChars, int overlapChars, int parentMaxChars,
                        boolean parentChild) {

        public static Chunk defaults() {
            return new Chunk(400, 120, 60, 1200, true);
        }
    }

    /**
     * 检索参数。mode 与阈值必须成对理解：HYBRID 排的是 RRF 分（量级 0.016–0.033），
     * VECTOR 排的是余弦相似度（0–1），把其中一个的阈值搬到另一个上会要么全不过、要么全过。
     */
    public record Retrieval(String mode, int topK, int candidateK, int rrfK, double minScore,
                           double minRrfScore, boolean parentContext, Budget budget) {

        public static Retrieval defaults() {
            return new Retrieval("hybrid", 6, 40, 60, 0.45, 0.012, true, Budget.defaults());
        }

        public Budget budget() {
            return budget == null ? Budget.defaults() : budget;
        }
    }

    /**
     * 上下文装配预算。nCtx 必须是**当前已加载模型的真实窗口**：同一台 LM Studio 上，
     * qwen2.5-vl-7b 与 glm-4-9b-chat-1m 实测都是 16384（2026-11-16，靠超限的 400 回显 n_ctx 量的），
     * 早先测到的 4096 是那次加载时的小配置。超限不会被静默截断，而是 HTTP 400 直接拒。
     * charsPerToken 取 1.0：实测 60000 汉字 = 36005~40019 token，即 1 token ≈ 1.5 字，
     * 所以按 1 字 1 token 算预算站在「少装」那一侧，宁可少带几块也不要撑爆窗口。
     */
    public record Budget(int nCtx, int outputReserveTokens, int templateChars, double charsPerToken,
                         int historyMaxTurns, int historyMaxChars) {

        public static Budget defaults() {
            return new Budget(16384, 700, 900, 1.0, 4, 800);
        }
    }

    /**
     * 归档包（导出/导入）参数。
     * <p>
     * maxImportMb 与 spring.servlet.multipart.max-file-size 是两道不同的闸：后者管容器收多少字节，
     * 这条管「这个包我们真的打算解到多大」。带向量的包比纯文本大一个数量级，所以它的默认值远高于
     * rag.ingest.max-file-size-mb——那一条限制的是单个文档，不是整库归档。
     */
    public record Archive(int maxImportMb) {

        public static Archive defaults() {
            return new Archive(512);
        }
    }
}
