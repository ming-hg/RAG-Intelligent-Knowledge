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
        /** 这块被人工改过正文：`charStart/charEnd` 描述的还是编辑前那份正文里的位置。 */
        boolean edited,
        int hitCount) {

    /** 父块只当上下文、不参与向量召回，判据是入库时留下的 SKIPPED。 */
    public boolean parentBlock() {
        return "SKIPPED".equals(embStatus);
    }
}
