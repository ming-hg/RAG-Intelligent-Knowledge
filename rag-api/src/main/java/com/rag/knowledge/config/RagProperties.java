package com.rag.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 全本地部署的运行配置。所有字段都有可用默认值，开箱即可启动（模型服务未启动时健康检查会明确报告）。
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(LmStudio lmStudio, Storage storage, Embedding embedding, Ingest ingest) {

    /** 配置里整段漏写 rag.ingest 时退回默认值，而不是让上传接口 NPE。 */
    public Ingest ingest() {
        return ingest == null ? Ingest.defaults() : ingest;
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
}
