package com.rag.knowledge.web;

import com.rag.knowledge.app.health.HealthService;
import com.rag.knowledge.common.ApiResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * GET /api/health              快速探测（PG / 全文检索 / 存储 / 向量模型一致性）
     * GET /api/health?models=true  额外实调 LM Studio 的 embedding 与 chat（慢，用于排障）
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<HealthService.Report>> health(
            @RequestParam(name = "models", defaultValue = "false") boolean withModels) {
        HealthService.Report report = healthService.report(withModels);
        int code = report.ready() ? 200 : 503;
        String message = report.ready() ? "关键依赖就绪" : "关键依赖不可用，见 probes 中 DOWN 项";
        ApiResponse<HealthService.Report> body =
                new ApiResponse<>(code, message, report, MDC.get("traceId"));
        return ResponseEntity.status(HttpStatus.valueOf(code)).body(body);
    }
}
