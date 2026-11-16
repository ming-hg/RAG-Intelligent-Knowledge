package com.rag.knowledge.app.model;

import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModelServiceTest {

    private static final String MODELS_JSON = """
            {"object":"list","data":[
              {"id":"qwen2.5-vl-7b-instruct","object":"model","owned_by":"lmstudio"},
              {"id":"bge-base-zh-v1.5","object":"model","owned_by":"lmstudio"}
            ]}
            """;

    private RagProperties props(String baseUrl, int dim) {
        return new RagProperties(
                new RagProperties.LmStudio(baseUrl, "lm-studio", "qwen2.5-vl-7b-instruct",
                        "bge-base-zh-v1.5", 0.3, 0.8, null, Duration.ofSeconds(5), 1),
                new RagProperties.Storage("./storage"),
                new RagProperties.Embedding(dim, 16),
                RagProperties.Ingest.defaults(), RagProperties.Retrieval.defaults(),
                RagProperties.Archive.defaults());
    }

    private ModelService service(RagProperties props, ChatModel chat, EmbeddingModel embedding) {
        return new ModelService(RestClient.builder(), props, chat, embedding);
    }

    @Test
    void catalogAppendsV1AndReturnsModelIds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ModelService service = new ModelService(builder, props("http://localhost:1234", 768),
                mock(ChatModel.class), mock(EmbeddingModel.class));

        server.expect(requestTo("http://localhost:1234/v1/models"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer lm-studio"))
                .andRespond(withSuccess(MODELS_JSON, MediaType.APPLICATION_JSON));

        ModelService.Catalog catalog = service.catalog();

        server.verify();
        assertThat(catalog.baseUrl()).isEqualTo("http://localhost:1234/v1");
        assertThat(catalog.models()).containsExactly("qwen2.5-vl-7b-instruct", "bge-base-zh-v1.5");
    }

    @Test
    void unreachableModelServiceReportsFixHints() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ModelService service = new ModelService(builder, props("http://localhost:1234/v1", 768),
                mock(ChatModel.class), mock(EmbeddingModel.class));

        server.expect(requestTo("http://localhost:1234/v1/models")).andRespond(withServerError());

        assertThatThrownBy(service::catalog)
                .isInstanceOf(RagException.class)
                .hasMessageContaining("无法读取模型列表")
                .extracting(e -> ((RagException) e).hints())
                .asList()
                .isNotEmpty();
    }

    @Test
    void embeddingDimensionMismatchIsFlagged() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(new Embedding(new float[512])));

        ModelService.EmbeddingResult result = service(props("http://localhost:1234/v1", 768),
                mock(ChatModel.class), embeddingModel).testEmbedding("知识库问答");

        assertThat(result.dim()).isEqualTo(512);
        assertThat(result.expectedDim()).isEqualTo(768);
        assertThat(result.dimMatches()).isFalse();
    }

    @Test
    void blankQuestionIsRejectedBeforeCallingModel() {
        ChatModel chatModel = mock(ChatModel.class);
        ModelService service = service(props("http://localhost:1234/v1", 768), chatModel, mock(EmbeddingModel.class));

        assertThatThrownBy(() -> service.testChat("  "))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("question 不能为空");
    }

    @Test
    void chatFailureCarriesModelNameAndRootCause() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(anyString())).thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> service(props("http://localhost:1234/v1", 768), chatModel, mock(EmbeddingModel.class))
                .testChat("什么是RAG"))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("qwen2.5-vl-7b-instruct")
                .hasMessageContaining("Connection refused");
    }

    /** LM Studio 认不出的模型名照样 200，只是回显成实际加载的模型——只能靠回显证伪。 */
    @Test
    void echoCheckFlagsSilentModelSwap() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ModelService service = new ModelService(builder, props("http://localhost:1234/v1", 768),
                mock(ChatModel.class), mock(EmbeddingModel.class));

        server.expect(requestTo("http://localhost:1234/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"object":"list","model":"text-embedding-nomic-embed-text-v1.5",
                         "data":[{"index":0,"embedding":[0.1,0.2]}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:1234/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"model":"bge-base-zh-v1.5","choices":[{"message":{"content":"好"}}]}
                        """, MediaType.APPLICATION_JSON));

        ModelService.EchoCheck echo = service.echoCheck();

        server.verify();
        assertThat(echo.echoedEmbedding()).isEqualTo("text-embedding-nomic-embed-text-v1.5");
        assertThat(echo.mismatched()).hasSize(2);
        assertThat(echo.mismatched().get(0)).contains("bge-base-zh-v1.5");
    }

    /** LM Studio 用 HTTP 200 + {"error": "..."} 返回未知端点，不能把 200 当成功。 */
    @Test
    void http200WithBodyErrorIsTreatedAsFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ModelService service = new ModelService(builder, props("http://localhost:1234/v1", 768),
                mock(ChatModel.class), mock(EmbeddingModel.class));

        server.expect(requestTo("http://localhost:1234/v1/embeddings"))
                .andRespond(withSuccess("{\"error\":\"Failed to load model. Model is not embedding.\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::echoCheck)
                .isInstanceOf(RagException.class)
                .hasMessageContaining("Model is not embedding");
    }
}
