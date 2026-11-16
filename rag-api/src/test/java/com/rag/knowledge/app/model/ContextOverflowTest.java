package com.rag.knowledge.app.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 识别与解析都建立在实测响应体上（192.168.1.61:1234，2026-11-16 抓的两条 400），
 * 所以这里的字符串不是编出来的样例，而是真机原样：换一家服务时这些断言会立刻过期，那是想要的效果。
 */
class ContextOverflowTest {

    private static final String LM_STUDIO_BODY = "{\"error\":\"Engine protocol predict request returned 400: "
            + "{\\\"error\\\":{\\\"code\\\":400,\\\"message\\\":\\\"request (36005 tokens) "
            + "exceeds the available context size (16384 tokens), try increasing it\\\","
            + "\\\"type\\\":\\\"exceed_context_size_error\\\",\\\"n_prompt_tokens\\\":36005,\\\"n_ctx\\\":16384}}\"}";

    @Test
    void readsTheRealWindowOutOfTheMeasuredRefusal() {
        RuntimeException wrapped = new RuntimeException("Chat completion failed",
                new IllegalStateException(LM_STUDIO_BODY));

        assertThat(ContextOverflow.from(wrapped)).isTrue();
        assertThat(ContextOverflow.reportedCtx(wrapped)).isEqualTo(16384);
        assertThat(ContextOverflow.reportedPromptTokens(wrapped)).isEqualTo(36005);
    }

    /** OpenAI 风格的措辞没有 n_ctx 字段：认得出是超限就够了，窗口数留空，别瞎猜。 */
    @Test
    void recognizesOtherWordingsWithoutInventingAWindow() {
        String other = "This model's maximum context length is 8192 tokens, however you requested 9210 tokens. "
                + "Please reduce the length of the prompt or completion.";

        assertThat(ContextOverflow.is(other)).isTrue();
        assertThat(ContextOverflow.reportedCtx(other)).isNull();
    }

    @Test
    void ordinaryOutagesAreNotOverflow() {
        assertThat(ContextOverflow.from(new IllegalStateException("Connection refused"))).isFalse();
        assertThat(ContextOverflow.from(new RuntimeException("429 too many requests"))).isFalse();
        assertThat(ContextOverflow.from(null)).isFalse();
        assertThat(ContextOverflow.is(null)).isFalse();
        assertThat(ContextOverflow.reportedCtx((String) null)).isNull();
    }

    /** 只搜最外层消息永远读不到窗口数：响应体藏在第三层 cause 里也得捞出来。 */
    @Test
    void digsThroughSeveralCauseLevels() {
        Throwable top = new RuntimeException("外层", new RuntimeException("中层",
                new IllegalStateException(LM_STUDIO_BODY)));

        assertThat(ContextOverflow.from(top)).isTrue();
        assertThat(ContextOverflow.text(top)).contains("外层").contains("中层").contains("n_ctx");
        assertThat(ContextOverflow.reportedCtx(top)).isEqualTo(16384);
    }

    @Test
    void aThrowableWithoutMessagesStillReadsAsNoOverflow() {
        assertThat(ContextOverflow.from(new IllegalStateException())).isFalse();
        assertThat(ContextOverflow.text(new IllegalStateException())).isEmpty();
    }
}
