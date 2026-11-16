package com.rag.knowledge.app.rag;

/**
 * 关键词检索当前走哪一路（ADR-003 三级降级）。
 * 判定依据是 kb_segment.content_ts 这一列在不在——它是 migration 里按 zhparser 可用性建的，
 * 比再去查一遍扩展名更可靠：列在就说明分词配置真的能用。
 */
public enum KeywordEngine {

    CHINESE_TSVECTOR("中文分词 tsvector（zhparser）"),
    TRIGRAM("pg_trgm 子串相似度（中文按字切，精度低于分词）"),
    NONE("无关键词路（只走向量检索）");

    private final String description;

    KeywordEngine(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    public boolean available() {
        return this != NONE;
    }
}
