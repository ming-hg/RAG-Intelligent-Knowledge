package com.rag.knowledge;

import com.rag.knowledge.app.model.ContextOverflow;
import com.rag.knowledge.app.model.ModelService;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.AiConfig;
import com.rag.knowledge.config.LmStudioHttpClient;
import com.rag.knowledge.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.ThrowableAssert.catchThrowable;

/**
 * 真机门：默认跳过，`LM_STUDIO_IT=true mvn verify` 显式打开。
 * 只在真实 LM Studio 上验证三件假不了的事——模型名是否真被加载、批量向量维度与顺序、流式增量能否拼回完整回答。
 */
@EnabledIfEnvironmentVariable(named = "LM_STUDIO_IT", matches = "true")
class LmStudioConnectivityIT {

    private static final String BASE_URL = env("LM_STUDIO_BASE_URL", "http://192.168.1.61:1234/v1");
    private static final String CHAT_MODEL = env("LM_STUDIO_CHAT_MODEL", "glm-4-9b-chat-1m");
    private static final String EMBEDDING_MODEL = env("LM_STUDIO_EMBEDDING_MODEL", "text-embedding-bge-base-zh-v1.5");
    private static final int DIM = Integer.parseInt(env("RAG_EMBEDDING_DIM", "768"));
    private static final int N_CTX = Integer.parseInt(env("RAG_LLM_N_CTX", "16384"));

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private RagProperties props() {
        return new RagProperties(
                new RagProperties.LmStudio(BASE_URL, env("LM_STUDIO_API_KEY", "lm-studio"), CHAT_MODEL,
                        EMBEDDING_MODEL, 0.3, 0.8, null, Duration.ofSeconds(90), 1),
                new RagProperties.Storage("./storage"),
                new RagProperties.Embedding(DIM, 16),
                RagProperties.Ingest.defaults(), RagProperties.Retrieval.defaults(),
                RagProperties.Archive.defaults());
    }

    private ModelService modelService(RagProperties props) {
        AiConfig config = new AiConfig();
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(LmStudioHttpClient.requestFactory());
        return new ModelService(builder, props, config.chatModel(props), config.embeddingModel(props));
    }

    @Test
    void configuredModelsAreTheOnesActuallyServing() {
        RagProperties props = props();
        ModelService service = modelService(props);

        ModelService.Catalog catalog = service.catalog();
        assertThat(catalog.models()).contains(CHAT_MODEL, EMBEDDING_MODEL);

        ModelService.EchoCheck echo = service.echoCheck();
        assertThat(echo.mismatched()).isEmpty();
        assertThat(echo.echoedEmbedding()).isEqualTo(EMBEDDING_MODEL);
        assertThat(echo.echoedChat()).isEqualTo(CHAT_MODEL);
    }

    /**
     * 模型加载后的真实窗口没有查询端点，只有把它撑爆才肯说（实测响应体里带 {@code n_ctx}）。
     * 这条把「RAG_LLM_N_CTX 是不是配小了」变成一次断言：配小了不会有任何报错，只会每轮白白少装几块资料。
     */
    @Test
    void overflowsTheWindowToReadTheRealContextLength() {
        ModelService service = modelService(props());
        String oversized = "混合检索与倒数排名融合的说明，两路结果融合后再一起截断。".repeat(1200);

        Throwable thrown = catchThrowable(() -> service.testChat(oversized));

        assertThat(thrown).isInstanceOf(RagException.class);
        assertThat(((RagException) thrown).code()).as("超限必须是独立的 413，不是笼统的模型不可用").isEqualTo(413);
        Integer realCtx = ContextOverflow.reportedCtx(thrown.getMessage());
        assertThat(realCtx).as("超限响应里必须带着模型真实窗口：%s", thrown.getMessage()).isNotNull();
        assertThat(N_CTX).as("配置 n-ctx=%d 比模型真实窗口 %d 还大：要么在 LM Studio 里调大 context，要么降低配置",
                N_CTX, realCtx).isLessThanOrEqualTo(realCtx);
    }

    @Test
    void batchEmbeddingKeepsDimensionAndOrder() {
        EmbeddingModel embeddingModel = new AiConfig().embeddingModel(props());
        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            segments.add(TextSegment.from("第 " + i + " 段：本地私有化知识库的语义分块样本，用于校验批量向量化。"));
        }

        Response<List<Embedding>> response = embeddingModel.embedAll(segments);

        assertThat(response.content()).hasSize(16);
        assertThat(response.content()).allSatisfy(embedding -> assertThat(embedding.dimension()).isEqualTo(DIM));
        // 顺序错位会把 A 段的向量挂到 B 段上，检索时表现为「答案来自不相干的段落」且不报错
        assertThat(response.content().get(0).vector()).isNotEqualTo(response.content().get(9).vector());
    }

    @Test
    void streamingPartialsRebuildTheCompleteAnswer() throws InterruptedException {

        StreamingChatModel streaming = new AiConfig().streamingChatModel(props());
        List<String> partials = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> finalAnswer = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        streaming.chat("用一句话说明什么是RAG，不要分点。", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                partials.add(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                finalAnswer.set(response.aiMessage().text());
                done.countDown();
            }

            @Override
            public void onError(Throwable error) {
                failure.set(error);
                done.countDown();
            }
        });

        assertThat(done.await(120, TimeUnit.SECONDS)).as("流式响应超时").isTrue();
        assertThat(failure.get()).isNull();
        assertThat(partials).hasSizeGreaterThan(1);
        assertThat(finalAnswer.get()).isNotBlank();
        assertThat(String.join("", partials)).isEqualTo(finalAnswer.get());
    }
}
