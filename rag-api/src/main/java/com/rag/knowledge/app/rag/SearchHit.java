package com.rag.knowledge.app.rag;

/**
 * 一条检索命中。vectorRank / keywordRank 为 null 表示这一路没召回它（融合分只有一项）。
 * similarity 只在向量路有效；rrf 是混合与关键词路的排序依据。
 */
public record SearchHit(
        long segmentId,
        long docId,
        String docName,
        int chunkNo,
        int docVersion,
        String content,
        String headingPath,
        Integer pageStart,
        Integer pageEnd,
        Integer charStart,
        Integer charEnd,
        Long parentId,
        Integer vectorRank,
        Integer keywordRank,
        double rrf,
        Double similarity) {

    /** 引用面板与「命中片段」展示用：过长的一律截断，完整内容靠 segmentId 再取。 */
    public String snippet(int maxChars) {
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= maxChars ? flat : flat.substring(0, maxChars) + "…";
    }

    /** 引用条目的出处描述：文档名 → 页码 → 标题路径，缺什么就少一段。 */
    public String location() {
        StringBuilder where = new StringBuilder(docName == null || docName.isBlank()
                ? "文档 " + docId : docName);
        if (pageStart != null && pageStart > 0) {
            where.append(" 第").append(pageStart);
            if (pageEnd != null && pageEnd > pageStart) {
                where.append("-").append(pageEnd);
            }
            where.append("页");
        }
        if (headingPath != null && !headingPath.isBlank()) {
            where.append(" · ").append(headingPath);
        }
        return where.toString();
    }
}
