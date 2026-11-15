package com.rag.knowledge.web;

import com.rag.knowledge.app.ingest.ChineseSemanticSplitter;
import com.rag.knowledge.app.ingest.ChunkOverride;
import com.rag.knowledge.app.ingest.IngestService;
import com.rag.knowledge.common.ApiResponse;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.db.DocumentRepository;
import com.rag.knowledge.db.SegmentRepository;
import com.rag.knowledge.domain.DocStatus;
import com.rag.knowledge.domain.Document;
import com.rag.knowledge.domain.SegmentView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文档管理接口：上传后立刻返回，入库进度靠轮询列表接口拿。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IngestService ingestService;
    private final DocumentRepository documents;
    private final SegmentRepository segments;

    public DocumentController(IngestService ingestService, DocumentRepository documents, SegmentRepository segments) {
        this.ingestService = ingestService;
        this.documents = documents;
        this.segments = segments;
    }

    public record DocumentView(
            long id,
            long kbId,
            String name,
            String status,
            int progress,
            String progressMsg,
            String errorCode,
            String errorMsg,
            int version,
            Integer pageCount,
            Integer charCount,
            Long fileSize,
            String mime,
            String sha256) {

        static DocumentView of(Document doc) {
            return new DocumentView(doc.id(), doc.kbId(), doc.name(), doc.status().name(), doc.progress(),
                    doc.progressMsg(), doc.errorCode(), doc.errorMsg(), doc.version(), doc.pageCount(),
                    doc.charCount(), doc.fileSize(), doc.mime(), doc.sha256());
        }
    }

    public record UploadView(long documentId, long taskId, boolean duplicated, String status) {
    }

    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<UploadView> upload(@RequestPart("file") MultipartFile file,
                                         @RequestParam(value = "kbId", required = false) Long kbId) {
        long targetKb = kbId == null ? ingestService.defaultKbId() : kbId;
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw RagException.badRequest("读取上传流失败：" + file.getOriginalFilename()
                    + " ｜ 原因: " + e.getMessage());
        }
        IngestService.UploadResult result = ingestService.upload(targetKb, file.getOriginalFilename(),
                file.getContentType(), content);
        String message = result.duplicated()
                ? "内容相同的文档已存在，未重复消耗 embedding"
                : "已入队，任务 id=" + result.taskId();
        return ApiResponse.ok(new UploadView(result.documentId(), result.taskId(), result.duplicated(),
                result.status().name()), message);
    }

    @GetMapping
    public ApiResponse<List<DocumentView>> list(@RequestParam(value = "kbId", required = false) Long kbId,
                                               @RequestParam(value = "status", required = false) String status,
                                               @RequestParam(value = "limit", defaultValue = "50") int limit,
                                               @RequestParam(value = "offset", defaultValue = "0") int offset) {
        long targetKb = kbId == null ? ingestService.defaultKbId() : kbId;
        return ApiResponse.ok(documents.list(targetKb, parseStatus(status), Math.min(limit, 200), Math.max(offset, 0))
                .stream().map(DocumentView::of).toList());
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(@RequestParam(value = "kbId", required = false) Long kbId) {
        long targetKb = kbId == null ? ingestService.defaultKbId() : kbId;
        return ApiResponse.ok(Map.of(
                "kbId", targetKb,
                "total", documents.count(targetKb),
                "ready", documents.countByStatus(targetKb, DocStatus.READY),
                "failed", documents.countByStatus(targetKb, DocStatus.FAILED)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentView> detail(@PathVariable long id) {
        return ApiResponse.ok(DocumentView.of(require(id)));
    }

    @GetMapping("/{id}/segments")
    public ApiResponse<List<SegmentView>> segmentList(@PathVariable long id,
                                                     @RequestParam(value = "onlySearchable", defaultValue = "true")
                                                     boolean onlySearchable,
                                                     @RequestParam(value = "limit", defaultValue = "50") int limit,
                                                     @RequestParam(value = "offset", defaultValue = "0") int offset) {
        Document doc = require(id);
        if (doc.version() == 0) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(segments.listForDocument(id, doc.version(), onlySearchable, Math.min(limit, 500),
                Math.max(offset, 0)));
    }

    @GetMapping("/{id}/content")
    public ApiResponse<String> content(@PathVariable long id) {
        require(id);
        // '\f' 与 '\n' 等宽，所以 char_start/char_end 在这份文本上照样能切准；换成 '\n' 只是不让前端渲染出控制字符
        return ApiResponse.ok(ChineseSemanticSplitter.offsetBase(documents.content(id)));
    }

    /** 分块预览：拿库里已存的正文按给定参数重切一遍，不写库、不调模型，前端调参就靠它。 */
    @GetMapping("/{id}/chunks/preview")
    public ApiResponse<IngestService.Preview> preview(@PathVariable long id,
                                                      @RequestParam(required = false) Integer childChars,
                                                      @RequestParam(required = false) Integer childMinChars,
                                                      @RequestParam(required = false) Integer overlapChars,
                                                      @RequestParam(required = false) Integer parentMaxChars,
                                                      @RequestParam(required = false) Boolean parentChild,
                                                      @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(ingestService.preview(id, childChars, childMinChars, overlapChars,
                parentMaxChars, parentChild, limit));
    }

    /**
     * 这篇文档的分块参数现状：覆盖、全局默认、真正生效的那组值。
     * 覆盖只影响将来怎么切，已入库的那一代不变——生效要等一次重新入库。
     */
    @GetMapping("/{id}/chunk-config")
    public ApiResponse<IngestService.ChunkConfigView> chunkConfig(@PathVariable long id) {
        return ApiResponse.ok(ingestService.chunkConfig(id));
    }

    /** 整份替换覆盖：没给的键即「不覆盖」，全部等于默认时清空。 */
    @PutMapping("/{id}/chunk-config")
    public ApiResponse<IngestService.ChunkConfigView> setChunkConfig(@PathVariable long id,
                                                                     @RequestBody(required = false)
                                                                     ChunkOverride body) {
        IngestService.ChunkConfigView view = ingestService.setChunkConfig(id, body);
        return ApiResponse.ok(view, view.override().isEmpty()
                ? "已恢复跟随全局默认，重新入库后生效" : "覆盖已保存，重新入库后生效");
    }

    /** 块级禁用/恢复：只动 available，检索谓词固定带这个条件，立刻生效且完全可逆。 */
    @PatchMapping("/{id}/segments/{segmentId}")
    public ApiResponse<Map<String, Object>> setSegmentAvailable(@PathVariable long id,
                                                                @PathVariable long segmentId,
                                                                @RequestParam boolean available) {
        require(id);
        if (segments.setAvailable(id, segmentId, available) == 0) {
            throw RagException.notFound("块 " + segmentId + " 不属于文档 " + id + "，或该块已被新版本替换");
        }
        return ApiResponse.ok(Map.of("documentId", id, "segmentId", segmentId, "available", available),
                available ? "块已恢复检索" : "块已从检索中排除");
    }

    @PostMapping("/{id}/reindex")
    public ApiResponse<Map<String, Object>> reindex(@PathVariable long id,
                                                    @RequestParam(defaultValue = "false") boolean reparse) {
        long taskId = ingestService.reindex(id, reparse);
        return ApiResponse.ok(Map.of("documentId", id, "taskId", taskId),
                reparse ? "已重新排队入库（重新解析原文件）" : "已重新排队入库（复用库内正文）");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable long id) {
        ingestService.delete(id);
        return ApiResponse.ok(Map.of("documentId", id), "文档已删除");
    }

    private Document require(long id) {
        return documents.findById(id).orElseThrow(() -> RagException.notFound("文档不存在：" + id));
    }

    private static DocStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DocStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw RagException.badRequest("未知状态：" + raw + " ｜ 可选: " + Arrays.toString(DocStatus.values()));
        }
    }
}
