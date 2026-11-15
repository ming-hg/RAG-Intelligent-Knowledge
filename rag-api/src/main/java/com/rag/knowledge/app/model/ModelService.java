package com.rag.knowledge.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模型服务侧的元信息与非问答类调用：列出 LM Studio 已加载的模型、单独试调 chat/embedding。
 * /v1/models 是 OpenAI 兼容协议里 LangChain4j 没有封装的部分，这里直连 REST。
 */
@Service
public class ModelService {

    private static final int SAMPLE_SIZE = 8;

    private final RestClient restClient;
    private final RagProperties props;
    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;

    public ModelService(RestClient.Builder restClientBuilder, RagProperties props,
                        ChatModel chatModel, EmbeddingModel embeddingModel) {
        this.props = props;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.restClient = restClientBuilder
                .baseUrl(props.lmStudio().baseUrlV1())
                .defaultHeader("Authorization", "Bearer " + props.lmStudio().apiKey())
                .build();
    }

    public record Catalog(String baseUrl, List<String> models, String chatModel, String embeddingModel) {
    }

    public record ChatResult(String model, String answer, long tookMs) {
    }

    /**
     * dimMatches=false 时向量列宽度与库表不一致，此时任何入库都会失败，必须先改配置再重建 schema。
     */
    public record EmbeddingResult(String model, String text, int dim, int expectedDim, boolean dimMatches,
                                  List<Float> sample, long tookMs) {
    }

    public Catalog catalog() {
        ModelsResponse response;
        try {
            response = restClient.get().uri("/models").retrieve().body(ModelsResponse.class);
        } catch (RestClientException ex) {
            throw RagException.modelUnavailable(
                    "无法读取模型列表：" + props.lmStudio().baseUrlV1() + "/models",
                    "确认 Local Server 已启动且端口可达（跨机访问需打开「Serve on Local Network」并放行防火墙）",
                    "base-url 必须以 /v1 结尾");
        }
        List<String> ids = response == null || response.data() == null
                ? List.of()
                : response.data().stream().map(ModelDto::id).filter(id -> id != null && !id.isBlank()).toList();
        return new Catalog(props.lmStudio().baseUrlV1(), ids,
                props.lmStudio().chatModel(), props.lmStudio().embeddingModel());
    }

    public ChatResult testChat(String question) {
        String prompt = requireText(question, "question");
        long start = System.currentTimeMillis();
        String answer;
        try {
            answer = chatModel.chat(prompt);
        } catch (RuntimeException ex) {
            throw modelUnavailable("聊天模型调用失败：" + props.lmStudio().chatModel(), ex);
        }
        return new ChatResult(props.lmStudio().chatModel(), answer, System.currentTimeMillis() - start);
    }

    public EmbeddingResult testEmbedding(String text) {
        String input = requireText(text, "text");
        long start = System.currentTimeMillis();
        Embedding embedding;
        try {
            embedding = embeddingModel.embed(input).content();
        } catch (RuntimeException ex) {
            throw modelUnavailable("Embedding 模型调用失败：" + props.lmStudio().embeddingModel(), ex);
        }
        int expected = props.embedding().dim();
        List<Float> vector = embedding.vectorAsList();
        return new EmbeddingResult(props.lmStudio().embeddingModel(), input, embedding.dimension(), expected,
                embedding.dimension() == expected, vector.subList(0, Math.min(SAMPLE_SIZE, vector.size())),
                System.currentTimeMillis() - start);
    }

    /**
     * LM Studio 对不认识或已加载的模型名并不报错：实测传错误 id 仍返回 200，并用当前已加载的模型应答。
     * bge-base-zh 与 nomic-embed 又都是 768 维，单靠维度比对不出串味，所以只有回显的 model 字段可信。
     */
    public record EchoCheck(String configuredEmbedding, String echoedEmbedding,
                            String configuredChat, String echoedChat, List<String> mismatched) {
    }

    public EchoCheck echoCheck() {
        String embeddingConfigured = props.lmStudio().embeddingModel();
        String chatConfigured = props.lmStudio().chatModel();
        String embeddingEchoed = postForEcho("/embeddings", Map.of(
                "model", embeddingConfigured, "input", "自检"));
        String chatEchoed = postForEcho("/chat/completions", Map.of(
                "model", chatConfigured,
                "messages", List.of(Map.of("role", "user", "content", "只回复一个字：好")),
                "max_tokens", 4));
        List<String> mismatched = new ArrayList<>();
        if (!embeddingConfigured.equals(embeddingEchoed)) {
            mismatched.add("embedding 配置是 " + embeddingConfigured + "，实际应答的是 " + embeddingEchoed);
        }
        if (!chatConfigured.equals(chatEchoed)) {
            mismatched.add("chat 配置是 " + chatConfigured + "，实际应答的是 " + chatEchoed);
        }
        return new EchoCheck(embeddingConfigured, embeddingEchoed, chatConfigured, chatEchoed, mismatched);
    }

    private String postForEcho(String path, Map<String, Object> payload) {
        Map<String, Object> body;
        try {
            body = restClient.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException ex) {
            throw RagException.modelUnavailable("模型服务调用失败：" + props.lmStudio().baseUrlV1() + path
                    + "：" + rootOf(ex), "确认 LM Studio 已启动且该端口可从本机访问");
        }
        if (body == null) {
            throw RagException.modelUnavailable("模型服务返回空响应：" + path);
        }
        // LM Studio 连未知路径都用 HTTP 200 + {"error": "..."} 返回，不能把 200 当成成功
        Object error = body.get("error");
        if (error != null) {
            throw RagException.modelUnavailable("模型服务返回错误：" + error);
        }
        Object echoed = body.get("model");
        return echoed == null ? null : echoed.toString();
    }

    private static String rootOf(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw RagException.badRequest(field + " 不能为空");
        }
        return value.trim();
    }

    private RagException modelUnavailable(String message, RuntimeException ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return RagException.modelUnavailable(message + "：" + root.getMessage(),
                "确认 LM Studio 已加载该模型（模型名需与 /v1/models 返回的 id 完全一致）",
                "跨机部署时检查 " + props.lmStudio().baseUrlV1() + " 是否可从本机访问");
    }

    // LM Studio 在各版本里往 /v1/models 里塞了不同字段，只认 id，其余忽略
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelsResponse(List<ModelDto> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelDto(String id) {
    }
}
