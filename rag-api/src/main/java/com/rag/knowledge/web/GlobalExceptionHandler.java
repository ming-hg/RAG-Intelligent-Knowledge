package com.rag.knowledge.web;

import com.rag.knowledge.common.ApiResponse;
import com.rag.knowledge.common.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RagException.class)
    public ResponseEntity<ApiResponse<Object>> handleRag(RagException ex) {
        log.warn("业务异常 code={} msg={} hints={}", ex.code(), ex.getMessage(), ex.hints());
        String message = ex.hints().isEmpty()
                ? ex.getMessage()
                : ex.getMessage() + " ｜ 处理建议: " + String.join(" / ", ex.hints());
        return ResponseEntity.status(resolveStatus(ex.code())).body(ApiResponse.error(ex.code(), message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(ApiResponse.error(400, detail));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "文件超出大小限制，请调大 spring.servlet.multipart.max-file-size"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleOther(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(500, ex.getMessage() == null ? "服务内部异常" : ex.getMessage()));
    }

    private HttpStatus resolveStatus(int code) {
        HttpStatus resolved = HttpStatus.resolve(code);
        return resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
    }
}
