package com.rag.knowledge.app.rag;

import com.rag.knowledge.common.RagException;

import java.util.Arrays;
import java.util.Locale;

/**
 * 检索模式。三种模式排的分不是同一个量纲，所以阈值字段也分开（见硬性约束 1）：
 * VECTOR 用余弦相似度，HYBRID/KEYWORD 用 RRF 融合分。
 */
public enum RetrievalMode {

    VECTOR,
    KEYWORD,
    HYBRID;

    public static RetrievalMode of(String value) {
        if (value == null || value.isBlank()) {
            return HYBRID;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(mode -> mode.name().equals(normalized)).findFirst()
                .orElseThrow(() -> RagException.badRequest("检索模式只能是 vector/keyword/hybrid，收到：" + value));
    }

    public boolean usesVector() {
        return this != KEYWORD;
    }

    public boolean usesKeyword() {
        return this != VECTOR;
    }

    /** 纯相似度阈值只对向量模式有意义，混合作用 RRF 分会把所有结果滤空。 */
    public double effectiveThreshold(double minScore, double minRrfScore) {
        return this == VECTOR ? minScore : minRrfScore;
    }
}
