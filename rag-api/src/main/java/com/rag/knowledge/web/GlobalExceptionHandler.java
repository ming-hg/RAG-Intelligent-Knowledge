package com.rag.knowledge.web;

import com.rag.knowledge.common.ApiResponse;
import com.rag.knowledge.common.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 错误信封的 Content-Type 必须写死，不能交给协商：/chat/ask 这类 SSE 端点带着
     * {@code Accept: text/event-stream}，协商结果是「只能吐事件流」，于是起流前的参数错会变成
     * 一个没有 body 的 500——前端按 content-type 分支的整段逻辑（§7.4）根本没机会跑，
     * 用户看到的和「服务炸了」一模一样。错误就是 JSON，客户端要流也得先读到这句话。
     */
    private ResponseEntity<ApiResponse<Object>> json(int code, String message) {
        return ResponseEntity.status(resolveStatus(code))
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(code, message));
    }

    @ExceptionHandler(RagException.class)
    public ResponseEntity<ApiResponse<Object>> handleRag(RagException ex) {
        log.warn("业务异常 code={} msg={} hints={}", ex.code(), ex.getMessage(), ex.hints());
        String message = ex.hints().isEmpty()
                ? ex.getMessage()
                : ex.getMessage() + " ｜ 处理建议: " + String.join(" / ", ex.hints());
        return json(ex.code(), message);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return json(400, detail);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return json(400, "文件超出大小限制，请调大 spring.servlet.multipart.max-file-size");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleOther(Exception ex) {
        log.error("未处理异常", ex);
        return json(500, ex.getMessage() == null ? "服务内部异常" : ex.getMessage());
    }

    private HttpStatus resolveStatus(int code) {
        HttpStatus resolved = HttpStatus.resolve(code);
        return resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
    }
}
