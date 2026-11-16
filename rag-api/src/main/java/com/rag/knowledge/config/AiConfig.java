package com.rag.knowledge.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 手工装配 LangChain4j 模型客户端（不引 spring starter）：
 * 本地部署只需要 OpenAI 兼容协议，starter 反而带来 Jackson 版本耦合。
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatModel chatModel(RagProperties props) {
        RagProperties.LmStudio lm = props.lmStudio();
        var builder = OpenAiChatModel.builder()
                .baseUrl(lm.baseUrlV1())
                .apiKey(lm.apiKey())
                .modelName(lm.chatModel())
                .temperature(lm.temperature())
                .topP(lm.topP())
                .timeout(lm.timeout())
                .maxRetries(lm.maxRetries())
                .httpClientBuilder(LmStudioHttpClient.langchain4j(props));
        if (lm.maxTokens() != null) {
            builder.maxTokens(lm.maxTokens());
        }
        return builder.build();
    }

    @Bean
    public StreamingChatModel streamingChatModel(RagProperties props) {
        RagProperties.LmStudio lm = props.lmStudio();
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(lm.baseUrlV1())
                .apiKey(lm.apiKey())
                .modelName(lm.chatModel())
                .temperature(lm.temperature())
                .topP(lm.topP())
                .timeout(lm.timeout())
                .httpClientBuilder(LmStudioHttpClient.langchain4j(props));
        if (lm.maxTokens() != null) {
            builder.maxTokens(lm.maxTokens());
        }
        return builder.build();
    }

    @Bean
    public EmbeddingModel embeddingModel(RagProperties props) {
        RagProperties.LmStudio lm = props.lmStudio();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(lm.baseUrlV1())
                .apiKey(lm.apiKey())
                .modelName(lm.embeddingModel())
                // 本地模型的单批上限官方未公布，默认 2048 必然超时/OOM，这里按配置收窄
                .maxSegmentsPerBatch(props.embedding().batchSize())
                .timeout(lm.timeout())
                .maxRetries(lm.maxRetries())
                .httpClientBuilder(LmStudioHttpClient.langchain4j(props))
                .build();
    }
}
