package com.rag.knowledge.web;

import com.rag.knowledge.app.archive.KbExportService;
import com.rag.knowledge.app.archive.KbImportService;
import com.rag.knowledge.app.ingest.IngestService;
import com.rag.knowledge.common.ApiResponse;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.KnowledgeBaseRepository;
import com.rag.knowledge.db.KnowledgeBaseRepository.KnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 知识库列表与整库归档：导出成 .zip、导入 .zip 回库。
 * <p>
 * 第一版只有一个库，但这个接口决定了前端要不要做库切换 UI，以及启动时默认库是否已经按当前 embedding 配置建好。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    private final KnowledgeBaseRepository knowledgeBases;
    private final IngestService ingestService;
    private final KbExportService exportService;
    private final KbImportService importService;
    private final RagProperties props;

    public KnowledgeBaseController(KnowledgeBaseRepository knowledgeBases, IngestService ingestService,
                                   KbExportService exportService, KbImportService importService,
                                   RagProperties props) {
        this.knowledgeBases = knowledgeBases;
        this.ingestService = ingestService;
        this.exportService = exportService;
        this.importService = importService;
        this.props = props;
    }

    public record KnowledgeBaseView(long id, String name, String embeddingModel, int embeddingDim, int indexVersion) {

        static KnowledgeBaseView of(KnowledgeBase kb) {
            return new KnowledgeBaseView(kb.id(), kb.name(), kb.embeddingModel(), kb.embeddingDim(), kb.indexVersion());
        }
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseView>> list() {
        long defaultId = ingestService.defaultKbId();
        return ApiResponse.ok(knowledgeBases.list().stream().map(KnowledgeBaseView::of).toList(),
                "默认知识库 id=" + defaultId);
    }

    /**
     * 导出整库为 zip 附件。
     * <p>
     * 先 prepare 再开流：响应头一发出去就是 200，之后再发现问题也只能把错误写进一半的压缩包里，
     * 浏览器会存下一个「解压失败」的文件而没人知道为什么。
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable long id,
                                                        @RequestParam(value = "vectors", defaultValue = "true")
                                                        boolean vectors,
                                                        @RequestParam(value = "files", defaultValue = "false")
                                                        boolean files) {
        KbExportService.Info info = exportService.prepare(id, vectors, files);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(info.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(out -> exportService.writeTo(id, vectors, files, out));
    }

    /**
     * 导入归档包。包先进临时文件再解：{@code ZipFile} 需要随机访问，
     * 而流式解 zip 只能按写入顺序读，缺一个条目就得整包重来。
     */
    @PostMapping(value = "/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KbImportService.Summary> importArchive(@PathVariable long id,
                                                              @RequestPart("file") MultipartFile file,
                                                              @RequestParam(value = "rebuild", defaultValue = "false")
                                                              boolean rebuild) {
        if (file == null || file.isEmpty()) {
            throw RagException.badRequest("归档包是空的");
        }
        long maxBytes = (long) props.archive().maxImportMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw RagException.badRequest("归档包 " + file.getSize() / 1024 / 1024 + "MB 超过 "
                    + props.archive().maxImportMb() + "MB 限制（rag.archive.max-import-mb）");
        }
        Path temp;
        try {
            temp = Files.createTempFile("kb-import-", ".zip");
            file.transferTo(temp.toFile());
        } catch (IOException e) {
            throw RagException.badRequest("归档包落临时文件失败：" + e.getMessage());
        }
        try {
            KbImportService.Summary summary = importService.importArchive(id, temp, rebuild);
            return ApiResponse.ok(summary, importMessage(summary));
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                log.warn("临时归档包没删掉，可手工清理：{}", temp, e);
            }
        }
    }

    private static String importMessage(KbImportService.Summary summary) {
        if ("CARRIED".equals(summary.mode())) {
            return "向量直接可用：导入 " + summary.imported() + " 篇 / " + summary.segmentsCarried()
                    + " 块，未花一次 embedding";
        }
        return "向量空间对不上，已按重新入库导入 " + summary.queued() + " 篇；等文档变 READY 后检索才完整";
    }
}
