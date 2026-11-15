package com.rag.knowledge.app.rag;

import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检索参数合并与阈值语义。阈值这块是「看起来能调、实际一改就全空」的重灾区：
 * 余弦分和 RRF 分量纲差两个数量级，用错一把尺子就等于把所有命中都滤掉。
 */
class RetrievalSettingsTest {

    private final RagProperties.Retrieval cfg = RagProperties.Retrieval.defaults();

    @Test
    void defaultsComeStraightFromConfigWhenNothingIsOverridden() {
        RetrievalSettings settings = RetrievalSettings.of(cfg, null, null);

        assertThat(settings.mode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(settings.topK()).isEqualTo(6);
        assertThat(settings.candidateK()).isEqualTo(40);
        assertThat(settings.threshold()).isEqualTo(cfg.minRrfScore());
    }

    @Test
    void vectorModeIsTheOnlyOneMeasuredByCosineSimilarity() {
        assertThat(RetrievalSettings.of(cfg, "vector", null).threshold()).isEqualTo(cfg.minScore());
        assertThat(RetrievalSettings.of(cfg, "hybrid", null).threshold()).isEqualTo(cfg.minRrfScore());
        assertThat(RetrievalSettings.of(cfg, "keyword", null).threshold()).isEqualTo(cfg.minRrfScore());
    }

    @Test
    void candidatePoolGrowsWithTopKSoFusionAlwaysHasRoom() {
        assertThat(RetrievalSettings.of(cfg, "hybrid", 30).candidateK()).isGreaterThanOrEqualTo(60);
        assertThat(RetrievalSettings.of(cfg, "hybrid", 1).candidateK()).isEqualTo(cfg.candidateK());
    }

    @Test
    void blankOverrideFallsBackToConfiguredMode() {
        assertThat(RetrievalSettings.of(cfg, "  ", 0).mode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(RetrievalSettings.of(cfg, null, -5).topK()).isEqualTo(cfg.topK());
    }

    @Test
    void rejectsUnknownModeAndAbsurdTopK() {
        assertThatThrownBy(() -> RetrievalSettings.of(cfg, "semantic", null))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("vector/keyword/hybrid");
        assertThatThrownBy(() -> RetrievalSettings.of(cfg, "hybrid", 500))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("topK 最大 50");
    }

    @Test
    void modeNamesAreCaseAndSeparatorTolerant() {
        assertThat(RetrievalMode.of("Hybrid")).isEqualTo(RetrievalMode.HYBRID);
        assertThat(RetrievalMode.of(" KEYWORD ")).isEqualTo(RetrievalMode.KEYWORD);
        assertThat(RetrievalMode.VECTOR.usesKeyword()).isFalse();
        assertThat(RetrievalMode.KEYWORD.usesVector()).isFalse();
    }
}
