package com.rag.knowledge.web;

import com.rag.knowledge.app.model.ModelService;
import com.rag.knowledge.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型接入层的自测接口：换模型、改 base-url、跨机接入后先跑这三个确认链路。
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelService modelService;

    public ModelController(ModelService modelService) {
        this.modelService = modelService;
    }

    public record ChatRequest(String question) {
    }

    public record EmbeddingRequest(String text) {
    }

    @GetMapping
    public ApiResponse<ModelService.Catalog> models() {
        return ApiResponse.ok(modelService.catalog(), "模型列表获取成功");
    }

    @PostMapping("/chat-test")
    public ApiResponse<ModelService.ChatResult> chatTest(@RequestBody ChatRequest request) {
        ModelService.ChatResult result = modelService.testChat(request.question());
        return ApiResponse.ok(result, "聊天模型连通正常，耗时 " + result.tookMs() + "ms");
    }

    @PostMapping("/embedding-test")
    public ApiResponse<ModelService.EmbeddingResult> embeddingTest(@RequestBody EmbeddingRequest request) {
        ModelService.EmbeddingResult result = modelService.testEmbedding(request.text());
        String message = result.dimMatches()
                ? "Embedding 连通正常，维度 " + result.dim() + "，耗时 " + result.tookMs() + "ms"
                : "Embedding 返回 " + result.dim() + " 维，与配置的 " + result.expectedDim()
                        + " 维不一致，需同步 rag.embedding.dim 并重建 schema";
        return ApiResponse.ok(result, message);
    }
}
