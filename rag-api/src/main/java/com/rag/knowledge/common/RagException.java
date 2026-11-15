package com.rag.knowledge.common;

import java.util.List;

/**
 * 业务异常：携带用户可读消息与可选的修复建议。
 * 本地部署场景下，模型/数据库类故障必须告诉用户「去哪儿改什么」，而不是抛栈。
 */
public class RagException extends RuntimeException {

    private final int code;
    private final List<String> hints;

    public RagException(int code, String message) {
        this(code, message, new String[0]);
    }

    public RagException(int code, String message, String... hints) {
        super(message);
        this.code = code;
        this.hints = List.of(hints);
    }

    public int code() {
        return code;
    }

    public List<String> hints() {
        return hints;
    }

    public static RagException badRequest(String message) {
        return new RagException(400, message);
    }

    public static RagException notFound(String message) {
        return new RagException(404, message);
    }

    public static RagException conflict(String message, String... hints) {
        return new RagException(409, message, hints);
    }

    public static RagException modelUnavailable(String message, String... hints) {
        return new RagException(503, message, hints);
    }
}
