package com.rag.knowledge.config;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 本地模型服务的 HTTP 客户端底座。
 * <p>
 * JDK HttpClient 默认协商 HTTP/2，在明文 http 上会先发 h2c 升级请求；LM Studio 不响应这个升级，
 * 请求就干等到超时（实测 curl 47ms 返回，同机 Java 侧永久挂住）。所以这里强制 HTTP/1.1 并设死超时，
 * 让「模型服务没起来」表现为报错，而不是表现为线程卡住。
 */
@Configuration
public class LmStudioHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // RestClient 只走 /v1/models、回显自检这类小请求；真正的长生成走 LangChain4j 客户端的 90s 超时。
    // 不设上限的话一次模型服务卡顿会把 /api/health 拖住 90s。
    private static final Duration SMALL_REQUEST_READ_TIMEOUT = Duration.ofSeconds(15);

    public static HttpClient.Builder jdk() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT);
    }

    public static HttpClientBuilder langchain4j(RagProperties props) {
        return new JdkHttpClientBuilder()
                .httpClientBuilder(jdk())
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(props.lmStudio().timeout());
    }

    public static ClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk().build());
        factory.setReadTimeout(SMALL_REQUEST_READ_TIMEOUT);
        return factory;
    }

    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        return requestFactory();
    }
}
