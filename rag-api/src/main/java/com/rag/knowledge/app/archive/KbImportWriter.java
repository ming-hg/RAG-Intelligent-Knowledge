package com.rag.knowledge.app.archive;

import com.rag.knowledge.app.ingest.ChunkOverride;
import com.rag.knowledge.db.DocumentRepository;
import com.rag.knowledge.db.SegmentRepository;
import com.rag.knowledge.domain.ArchivedSegment;
import com.rag.knowledge.domain.DocStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 归档导入的单篇落库：文档行 + 整代块 + 父子关系，一个事务。
 * <p>
 * 逐篇成事务而不是一整库一个事务：几百篇的包里某一篇坏了（向量维度不对、jsonl 被截断），
 * 代价应该只是那一篇回滚，而不是前面几十分钟写好的全部作废。
 */
@Service
public class KbImportWriter {

    private final DocumentRepository documents;
    private final SegmentRepository segments;
    private final ObjectMapper json;

    public KbImportWriter(DocumentRepository documents, SegmentRepository segments, ObjectMapper json) {
        this.documents = documents;
        this.segments = segments;
        this.json = json;
    }

    /**
     * 带着可用向量导入：状态直接 READY，块按包里的原样落库，不重切也不重算。
     * <p>
     * 这里刻意不走 {@link com.rag.knowledge.app.ingest.SegmentIndexPublisher}：那条路径的前提是
     * 「块是刚算出来的、状态由算的人决定」，而归档导入恰恰相反——available=false 的块是用户在界面上
     * 手工关掉检索的，父块本来就没向量，用发布器的规则去推会把这层人工意图抹掉。
     */
    @Transactional
    public long writeReady(long kbId, KbArchive.DocEntry entry, String content, String filePath,
                           List<ArchivedSegment> rows, String embeddingModel, int indexVersion) {
        long docId = documents.insertArchived(kbId, entry.name(), entry.sha256(), entry.mime(), entry.fileSize(),
                content, entry.pageCount(), entry.version(), indexVersion, DocStatus.READY,
                overrideJson(entry), filePath);
        if (rows.isEmpty()) {
            return docId;
        }
        int written = segments.insertArchivedGeneration(docId, kbId, entry.version(), embeddingModel, rows);
        if (written != rows.size()) {
            throw new IllegalStateException("分块写入条数不符：" + written + " != " + rows.size());
        }
        segments.linkParents(docId, entry.version(), linksOf(rows));
        return docId;
    }

    /**
     * 向量空间对不上时的导入：只落正文、分块覆盖与原始文件，把版本压回 0，
     * 剩下的交给正常入库流水线（它会把 version+1 这一代算出来并原子切换）。
     */
    @Transactional
    public long writePending(long kbId, KbArchive.DocEntry entry, String content, String filePath) {
        return documents.insertArchived(kbId, entry.name(), entry.sha256(), entry.mime(), entry.fileSize(),
                content, entry.pageCount(), 0, 1, DocStatus.PENDING, overrideJson(entry), filePath);
    }

    private static List<int[]> linksOf(List<ArchivedSegment> rows) {
        return rows.stream()
                .filter(row -> row.parentChunkNo() != null)
                .map(row -> new int[] { row.chunkNo(), row.parentChunkNo() })
                .toList();
    }

    /** 清单里带回来的覆盖按原样写回；全空就写 NULL，让「没设过」和「等于默认」在库里仍然是两件事。 */
    private String overrideJson(KbArchive.DocEntry entry) {
        ChunkOverride override = entry.chunkOverride();
        return override == null ? null : override.write(json);
    }
}
