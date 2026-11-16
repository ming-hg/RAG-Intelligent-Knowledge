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

    /**
     * 参数错也要带「去哪儿改什么」：400 只说「不合法」时，调用方唯一能做的就是把请求体贴回来问人。
     * 单参版本保留给确实没什么可提示的场合（重载的 String... 会吞掉它，所以不能靠它兜底）。
     */
    public static RagException badRequest(String message) {
        return new RagException(400, message);
    }

    public static RagException badRequest(String message, String... hints) {
        return new RagException(400, message, hints);
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

    /** 装配出来的资料超过模型窗口，请求在生成开始之前就被拒：不是服务不可用，是配置对不上。 */
    public static RagException contextOverflow(String message, String... hints) {
        return new RagException(413, message, hints);
    }
}
