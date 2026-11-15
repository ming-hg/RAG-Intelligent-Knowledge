package com.rag.knowledge.app.rag;

import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;

/**
 * 全局配置与单次请求覆盖合并后的检索参数。
 * <p>
 * 覆盖只开放 mode 与 topK：candidateK / rrfK / 阈值属于「调一次就固定下来」的标定项，
 * 让前端随手改会让同一个问题在不同页面拿到不同结果，排查时无法复现。
 */
public record RetrievalSettings(
        RetrievalMode mode,
        int topK,
        int candidateK,
        int rrfK,
        double minScore,
        double minRrfScore,
        boolean parentContext) {

    private static final int MAX_TOP_K = 50;

    public static RetrievalSettings of(RagProperties.Retrieval cfg, String modeOverride, Integer topKOverride) {
        RetrievalMode mode = RetrievalMode.of(modeOverride == null || modeOverride.isBlank()
                ? cfg.mode() : modeOverride);
        int topK = topKOverride == null || topKOverride <= 0 ? cfg.topK() : topKOverride;
        if (topK > MAX_TOP_K) {
            throw RagException.badRequest("topK 最大 " + MAX_TOP_K + "，收到 " + topK);
        }
        // 候选池必须大于最终条数，否则 RRF 没有可融合的空间，混合检索会退化成单路排序
        int candidateK = Math.max(cfg.candidateK(), topK * 2);
        return new RetrievalSettings(mode, topK, candidateK, Math.max(1, cfg.rrfK()),
                cfg.minScore(), cfg.minRrfScore(), cfg.parentContext());
    }

    public double threshold() {
        return mode.effectiveThreshold(minScore, minRrfScore);
    }
}
