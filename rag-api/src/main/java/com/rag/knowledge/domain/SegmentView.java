package com.rag.knowledge.domain;

/**
 * 分块的读视图（前端展示/调参预览）。
 * parentChunkNo 为 null 表示这条本身就是检索命中块，没有父上下文块。
 */
public record SegmentView(
        long id,
        int chunkNo,
        Integer parentChunkNo,
        String content,
        String headingPath,
        Integer pageStart,
        Integer pageEnd,
        Integer charStart,
        Integer charEnd,
        boolean available,
        String embStatus,
        int hitCount) {
}
