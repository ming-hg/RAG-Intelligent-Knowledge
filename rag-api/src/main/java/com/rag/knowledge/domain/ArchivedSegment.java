package com.rag.knowledge.domain;

/**
 * 归档用的一行分块：比 {@link SegmentView} 多带向量与 edited_at，少带 id 与命中计数。
 * <p>
 * 不带 id 是因为块 id 属于源库，换一台机器重新落库必然拿到不同的 id，父子关系靠 parentChunkNo 重建；
 * 不带 hit_count 是因为那是本机使用统计，搬过去只会把一台机器的热度当成另一台的先验。
 *
 * @param embedding 向量本体，null 表示这块没有向量（父块，或编辑后待补算）
 * @param editedAt  人工编辑时刻的 ISO-8601 文本，null 表示没编辑过
 */
public record ArchivedSegment(
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
        String editedAt,
        float[] embedding) {
}
