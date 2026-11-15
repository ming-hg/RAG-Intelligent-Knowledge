package com.rag.knowledge.domain;

/**
 * 文档入库状态机。
 * <p>
 * 状态写进 kb_document.status，前端进度条与「重试」按钮都依赖它，所以非法迁移必须在写库前就炸，
 * 而不是让界面出现「已就绪但没有块」这种无法自愈的中间态。
 */
public enum DocStatus {

    PENDING,
    PARSING,
    SPLITTING,
    EMBEDDING,
    INDEXING,
    READY,
    FAILED;

    /** 重试与重新入库都从 PARSING 重新走一遍流水线。 */
    public boolean isRerunnable() {
        return this == PENDING || this == FAILED || this == READY;
    }

    public boolean canFollowFrom(DocStatus previous) {
        if (this == FAILED) {
            return true;   // 任何阶段都可能失败
        }
        if (previous == null) {
            return this == PENDING;
        }
        return switch (previous) {
            case PENDING -> this == PARSING;
            case PARSING -> this == SPLITTING;
            case SPLITTING -> this == EMBEDDING;
            case EMBEDDING -> this == INDEXING;
            case INDEXING -> this == READY;
            case READY, FAILED -> this == PARSING;
        };
    }
}
