package com.rag.knowledge.app.rag;

import java.util.List;

/**
 * 一次检索的完整入参。向量路与关键词路各自可以被单独关掉：
 * 模式选 keyword 时不算向量；embedding 模型不可用时 HybridSearchService 会把 vectorLiteral 置空自动降级。
 *
 * @param vectorLiteral pgvector 字面量，形如 "[0.1,0.2,...]"，不带外层引号
 * @param threshold     由 RetrievalMode.effectiveThreshold 选出来的分数下限，量纲与 mode 匹配
 */
public record RetrievalQuery(
        List<Long> kbIds,
        List<Long> docIds,
        String text,
        String vectorLiteral,
        RetrievalMode mode,
        KeywordEngine engine,
        int candidateK,
        int topK,
        int rrfK,
        double threshold) {

    public boolean useVector() {
        return mode.usesVector() && vectorLiteral != null && !vectorLiteral.isBlank();
    }

    public boolean useKeyword() {
        return mode.usesKeyword() && engine.available() && text != null && !text.isBlank();
    }

    /** 两路都不可用等于这次检索不可能有结果，让调用方提前给出人话而不是抛 SQL 语法错误。 */
    public boolean usable() {
        return useVector() || useKeyword();
    }
}
