package com.rag.knowledge.app.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「上下文超出模型窗口」的识别。实测（192.168.1.61:1234，2026-11-16）两个 chat 模型都用 HTTP 400 明确拒绝：
 * <pre>
 * {"error":"Engine protocol predict request returned 400: {"error":{"code":400,
 * "message":"request (36005 tokens) exceeds the available context size (16384 tokens), try increasing it",
 * "type":"exceed_context_size_error","n_prompt_tokens":36005,"n_ctx":16384}}"}
 * </pre>
 * 必须把它和「模型服务不可用」分开：超限是装配预算配错了，处理动作在 {@code rag.*} 配置里；不可用是服务没起来，
 * 动作在 LM Studio 那边。合成一条 503，用户就会去重启一个本来就好的服务。
 * <p>
 * 反方向的错更安静：配置窗口小于模型真实窗口不会报任何错，只是每轮白白少装资料，所以这里把模型回传的
 * {@code n_ctx} 抠出来，让 {@code /api/diagnostics} 能把「你配的比模型给的小」说出口。
 */
public final class ContextOverflow {

    private static final Pattern REPORTED_CTX = Pattern.compile("n_ctx\\D{0,4}(\\d{2,})");
    private static final Pattern REPORTED_CTX_FALLBACK = Pattern.compile("context size \\((\\d{2,}) tokens\\)");
    private static final Pattern REPORTED_PROMPT_TOKENS = Pattern.compile("n_prompt_tokens\\D{0,4}(\\d{2,})");

    private ContextOverflow() {
    }

    public static boolean from(Throwable ex) {
        return is(text(ex));
    }

    public static boolean is(String text) {
        if (text == null) {
            return false;
        }
        return text.contains("exceed_context_size_error")
                || text.contains("exceeds the available context size")
                || text.contains("maximum context length")
                || text.contains("reduce the length of the prompt");
    }

    /** 模型侧真实窗口；LM Studio 只在拒绝请求时把它说出来，没有查询端点。 */
    public static Integer reportedCtx(Throwable ex) {
        return reportedCtx(text(ex));
    }

    public static Integer reportedCtx(String text) {
        Integer value = first(REPORTED_CTX, text);
        return value != null ? value : first(REPORTED_CTX_FALLBACK, text);
    }

    public static Integer reportedPromptTokens(Throwable ex) {
        return first(REPORTED_PROMPT_TOKENS, text(ex));
    }

    /** 异常链上的消息拼一起：langchain4j 会把状态码和响应体包在 cause 里，只看最外层永远看不到 n_ctx。 */
    public static String text(Throwable ex) {
        StringBuilder text = new StringBuilder();
        Throwable current = ex;
        while (current != null && current != current.getCause()) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                text.append(text.isEmpty() ? "" : " | ").append(message);
            }
            current = current.getCause();
        }
        return text.toString();
    }

    private static Integer first(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }
}
