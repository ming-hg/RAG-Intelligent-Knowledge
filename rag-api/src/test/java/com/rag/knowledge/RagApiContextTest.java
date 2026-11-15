package com.rag.knowledge;

import com.rag.knowledge.app.model.ModelService;
import com.rag.knowledge.config.RagProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不依赖数据库和模型服务的装配门：Boot 4 把 autoconfigure 拆模块后，
 * 「少引一个 starter」只会表现为 bean 缺失或迁移不执行，这类问题必须在起服务前被抓到。
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
class RagApiContextTest {

    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoadsFromDevDefaults() {
        assertThat(context.getBeanNamesForType(ModelService.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ChatModel.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(StreamingChatModel.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(EmbeddingModel.class)).hasSize(1);
    }

    @Test
    void restClientBuilderIsAutoConfigured() {
        assertThat(context.getBeanNamesForType(RestClient.Builder.class)).isNotEmpty();
        // 我们自己注册的工厂必须顶掉 Boot 默认的（HTTP/1.1 + 15s），否则 /api/health 会被模型服务拖到 90s
        assertThat(context.getBeanNamesForType(ClientHttpRequestFactory.class)).hasSize(1);
    }

    @Test
    void configuredBaseUrlAlwaysCarriesV1() {
        RagProperties props = context.getBean(RagProperties.class);
        assertThat(props.lmStudio().baseUrlV1()).endsWith("/v1");
        assertThat(props.embedding().dim()).isPositive();
    }
}
