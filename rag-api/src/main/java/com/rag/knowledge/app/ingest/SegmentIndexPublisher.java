package com.rag.knowledge.app.ingest;

import com.rag.knowledge.db.DocumentRepository;
import com.rag.knowledge.db.SegmentRepository;
import com.rag.knowledge.domain.SegmentDraft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 索引版本切换：写新代 → 补父子 → 放开 available → 删旧代 → 文档置 READY，全在一个事务里。
 * <p>
 * 这几步必须原子。中途崩溃的结果是要么旧索引还在（事务回滚，可重试），要么新索引完整可用，
 * 不会出现「旧块删了、新块没放开」这种检索直接空的死态。
 */
@Service
public class SegmentIndexPublisher {

    private final SegmentRepository segments;
    private final DocumentRepository documents;

    public SegmentIndexPublisher(SegmentRepository segments, DocumentRepository documents) {
        this.segments = segments;
        this.documents = documents;
    }

    public record Result(int publishedRows, int droppedRows) {
    }

    @Transactional
    public Result publish(long docId, long kbId, int targetVersion, String embeddingModel, int indexVersion,
                          List<SegmentDraft> drafts, List<int[]> childToParentChunkNo) {
        segments.deleteGeneration(docId, targetVersion);
        int written = segments.insertGeneration(docId, kbId, targetVersion, embeddingModel, drafts);
        if (written != drafts.size()) {
            throw new IllegalStateException("分块写入条数不符：" + written + " != " + drafts.size());
        }
        segments.linkParents(docId, targetVersion, childToParentChunkNo);
        segments.publishGeneration(docId, targetVersion);
        int missing = segments.countMissingEmbedding(docId, targetVersion);
        if (missing > 0) {
            throw new IllegalStateException("有 " + missing + " 个可检索块没有向量，拒绝放开这一代索引");
        }
        int dropped = segments.deleteOtherGenerations(docId, targetVersion);
        documents.markReady(docId, targetVersion, indexVersion);
        return new Result(written, dropped);
    }
}
