package com.rag.knowledge.common;

import org.slf4j.MDC;

public record ApiResponse<T>(int code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data, MDC.get("traceId"));
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(200, message, data, MDC.get("traceId"));
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, MDC.get("traceId"));
    }
}
