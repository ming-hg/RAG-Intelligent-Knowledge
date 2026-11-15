package com.rag.knowledge.domain;

/**
 * 待入库的分块：分块产物 + 向量。
 * embedding 为 null 表示这条只当上下文用（父块），不参与向量检索。
 */
public record SegmentDraft(
        String content,
        int chunkNo,
        Integer parentChunkNo,
        String headingPath,
        int pageStart,
        int pageEnd,
        int charStart,
        int charEnd,
        boolean searchable,
        float[] embedding) {
}
