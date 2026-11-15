package com.rag.knowledge.app.archive;

import com.rag.knowledge.app.ingest.ChunkOverride;
import com.rag.knowledge.app.ingest.FileStorage;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.db.DocumentRepository;
import com.rag.knowledge.db.KnowledgeBaseRepository;
import com.rag.knowledge.db.KnowledgeBaseRepository.KnowledgeBase;
import com.rag.knowledge.db.SegmentRepository;
import com.rag.knowledge.domain.ArchivedSegment;
import com.rag.knowledge.domain.DocStatus;
import com.rag.knowledge.domain.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 知识库导出：把整库打成自描述的 .zip（清单 + 正文 + 分块与向量 + 可选原始文件）。
 * <p>
 * 逐篇流式写而不是先拼一个大对象：几百篇文档连正文带向量会有几百 MB，全塞内存里换来的是
 * 「导出一次、服务重启一次」。清单先行，导入端因此可以在碰到任何字节之前就知道维度与模型对不对。
 */
@Service
public class KbExportService {

    private static final Logger log = LoggerFactory.getLogger(KbExportService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * @param skippedNotReady 没被写进包的文档数：正在入库的文档没有完整的一代索引，
     *                        硬导出一半的块，导入方拿到的就是「检索结果少了但没人报错」的库。
     */
    public record Info(String fileName, long kbId, String kbName, int documents, int skippedNotReady,
                       boolean vectors, boolean files) {
    }

    private final KnowledgeBaseRepository knowledgeBases;
    private final DocumentRepository documents;
    private final SegmentRepository segments;
    private final FileStorage storage;
    private final ObjectMapper json;

    public KbExportService(KnowledgeBaseRepository knowledgeBases, DocumentRepository documents,
                           SegmentRepository segments, FileStorage storage, ObjectMapper json) {
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.segments = segments;
        this.storage = storage;
        this.json = json;
    }

    /** 校验与计数提前做：下载中途失败已经没法回成 4xx JSON，只能靠这里先把错误挡住。 */
    public Info prepare(long kbId, boolean vectors, boolean files) {
        KnowledgeBase kb = requireKb(kbId);
        List<Document> ready = readyDocuments(kbId);
        return new Info(fileName(kbId), kb.id(), kb.name(), ready.size(),
                documents.count(kbId) - ready.size(), vectors, files);
    }

    public void writeTo(long kbId, boolean vectors, boolean files, OutputStream out) throws IOException {
        long startedAt = System.currentTimeMillis();
        KnowledgeBase kb = requireKb(kbId);
        List<Document> docs = readyDocuments(kbId);
        List<KbArchive.DocEntry> manifest = new ArrayList<>(docs.size());
        for (int slot = 0; slot < docs.size(); slot++) {
            Document doc = docs.get(slot);
            int segmentCount = vectors ? segments.countForDocument(doc.id(), doc.version()) : 0;
            manifest.add(new KbArchive.DocEntry(slot, doc.name(), doc.sha256(), doc.mime(), doc.fileSize(),
                    doc.pageCount(), doc.charCount(), doc.version(), segmentCount,
                    ChunkOverride.read(json, doc.chunkOverride())));
        }

        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out), StandardCharsets.UTF_8);
        writeJson(zip, KbArchive.MANIFEST_ENTRY, new KbArchive.Manifest(KbArchive.FORMAT_VERSION,
                LocalDateTime.now().withNano(0).toString(),
                new KbArchive.Source(kb.name(), kb.embeddingModel(), kb.embeddingDim(), kb.indexVersion()),
                vectors, files, manifest));
        for (int slot = 0; slot < docs.size(); slot++) {
            Document doc = docs.get(slot);
            writeText(zip, KbArchive.contentEntry(slot), documents.content(doc.id()));
            if (vectors) {
                writeSegments(zip, KbArchive.segmentsEntry(slot),
                        segments.listArchived(doc.id(), doc.version()));
            }
            if (files) {
                writeOriginal(zip, KbArchive.fileEntry(slot, doc.name()), doc);
            }
        }
        zip.finish();
        zip.flush();
        log.info("知识库 {} 导出完成：{} 篇文档，向量={}，原始文件={}，用时 {}ms", kbId, docs.size(), vectors, files,
                System.currentTimeMillis() - startedAt);
    }

    private List<Document> readyDocuments(long kbId) {
        return documents.listLive(kbId).stream().filter(doc -> doc.status() == DocStatus.READY).toList();
    }

    private KnowledgeBase requireKb(long kbId) {
        try {
            return knowledgeBases.require(kbId);
        } catch (RuntimeException e) {
            throw RagException.notFound("知识库不存在：" + kbId);
        }
    }

    private static String fileName(long kbId) {
        return "kb-" + kbId + "-" + LocalDateTime.now().format(STAMP) + ".zip";
    }

    private void writeJson(ZipOutputStream zip, String entry, Object value) throws IOException {
        writeBytes(zip, entry, json.writeValueAsBytes(value));
    }

    /** 一行一块：jsonl 让导入端可以逐行解析，坏一行不至于让整篇文档的分块文件全部读不出来。 */
    private void writeSegments(ZipOutputStream zip, String entry, List<ArchivedSegment> rows) throws IOException {
        StringBuilder text = new StringBuilder(rows.size() * 512);
        for (ArchivedSegment row : rows) {
            text.append(json.writeValueAsString(toEntry(row))).append('\n');
        }
        writeBytes(zip, entry, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeOriginal(ZipOutputStream zip, String entry, Document doc) throws IOException {
        if (doc.filePath() == null || doc.filePath().isBlank()) {
            return;
        }
        try {
            writeBytes(zip, entry, storage.read(doc.filePath()));
        } catch (RagException e) {
            // 原始文件丢了不影响包的可用性：正文与分块齐全，只是以后没法 reparse=true 重跑一遍 Tika
            log.warn("文档 {} 的原始文件没进包：{}", doc.id(), e.getMessage());
        }
    }

    /** 空正文写成空条目而不是跳过：跳过会让导入端把「没导出」和「导出时正文为空」看成同一件事。 */
    private void writeText(ZipOutputStream zip, String entry, String text) throws IOException {
        writeBytes(zip, entry, (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytes(ZipOutputStream zip, String entry, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static KbArchive.SegmentEntry toEntry(ArchivedSegment row) {
        return new KbArchive.SegmentEntry(row.chunkNo(), row.parentChunkNo(), row.content(), row.headingPath(),
                row.pageStart(), row.pageEnd(), row.charStart(), row.charEnd(), row.available(), row.embStatus(),
                row.editedAt(), VectorCodec.encode(row.embedding()));
    }
}
