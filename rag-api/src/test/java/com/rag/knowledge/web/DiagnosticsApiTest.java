package com.rag.knowledge.web;

import com.rag.knowledge.app.diag.RunDiagnostics;
import com.rag.knowledge.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /api/diagnostics 的两条职责：把计数原样搬出来，以及对「配了但一定是错的」参数当场报警。
 * 阈值量纲那条是本项目的头号自伤方式——0.45 放进 min-rrf-score 不会报错，只会让检索永远空手，
 * 然后被人当成「库里没资料」去重新上传文档。
 */
class DiagnosticsApiTest {

    private final RunDiagnostics diagnostics = new RunDiagnostics();

    @Test
    void countersAndLastReasonAreEchoedAsIs() {
        diagnostics.vectorDegraded("Connection refused");
        diagnostics.refused();
        DiagnosticsController.View view = controller(props(RagProperties.Retrieval.defaults())).diagnostics().data();

        assertThat(view.run().counters().vectorDegraded()).isEqualTo(1);
        assertThat(view.run().counters().refused()).isEqualTo(1);
        assertThat(view.run().counters().contextTruncated()).isZero();
        assertThat(view.run().lastVectorFailure().reason()).contains("Connection refused");
        assertThat(view.warnings()).isEmpty();
        assertThat(view.effective().mode()).isEqualTo("hybrid");
        assertThat(view.effective().embeddingDim()).isEqualTo(768);
        // 预算回显的是「不带历史与问题时最多能装多少字」：16384 - 700 - 900 = 14784（窗口取自模型实测）
        assertThat(view.effective().contextBudgetChars()).isEqualTo(14784);
        assertThat(view.effective().nCtx()).isEqualTo(16384);
    }

    /**
     * n-ctx 配得比模型真实窗口小，不会有任何报错，只会每轮白白少装资料——这件事只能从模型回显里反出来。
     * 所以撞过一次 400 之后，diagnostics 必须把「配置小于实测」这句话讲出来。
     */
    @Test
    void anUnderconfiguredWindowIsCalledOutAfterTheModelRefusesOnce() {
        RagProperties.Budget small = new RagProperties.Budget(4096, 700, 900, 1.0, 4, 800);
        RagProperties.Retrieval retrieval = new RagProperties.Retrieval("hybrid", 6, 40, 60, 0.45, 0.012, true, small);
        diagnostics.contextOverflow(4096, 16384, "模型侧回传 16384");

        DiagnosticsController.View view = controller(props(retrieval)).diagnostics().data();

        assertThat(view.run().counters().contextOverflow()).isEqualTo(1);
        assertThat(view.warnings()).anySatisfy(w -> assertThat(w)
                .contains("真实窗口是 16384").contains("RAG_LLM_N_CTX").contains("2496"));
    }

    /** 反方向更糟：配置比模型给的大，请求会被 400 拒掉，报错要指出去 LM Studio 调 context 这条路。 */
    @Test
    void anOverconfiguredWindowPointsAtLmStudio() {
        RagProperties.Budget big = new RagProperties.Budget(32768, 700, 900, 1.0, 4, 800);
        diagnostics.contextOverflow(32768, 16384, "模型侧回传 16384");

        DiagnosticsController.View view = controller(props(new RagProperties.Retrieval("hybrid", 6, 40, 60,
                0.45, 0.012, true, big))).diagnostics().data();

        assertThat(view.warnings()).anySatisfy(w -> assertThat(w)
                .contains("n-ctx=32768").contains("LM Studio 里调大 context"));
    }

    @Test
    void aCosineSizedThresholdOnRrfScoresGetsCalledOut() {
        RagProperties.Retrieval broken = new RagProperties.Retrieval("hybrid", 6, 40, 60, 0.45, 0.45, true,
                RagProperties.Budget.defaults());

        DiagnosticsController.View view = controller(props(broken)).diagnostics().data();

        assertThat(view.warnings()).hasSize(1);
        assertThat(view.warnings().get(0)).contains("min-rrf-score=0.45").contains("0.01x 量纲");
    }

    @Test
    void childBlocksWiderThanTheEmbeddingWindowGetsCalledOut() {
        RagProperties.Chunk chunk = new RagProperties.Chunk(600, 120, 60, 1200, true);
        RagProperties base = props(RagProperties.Retrieval.defaults());

        DiagnosticsController.View view = new DiagnosticsController(diagnostics,
                new RagProperties(base.lmStudio(), base.storage(), base.embedding(),
                        new RagProperties.Ingest(true, Duration.ofSeconds(2), Duration.ofMinutes(10), 3, 1, 100,
                                base.ingest().allowedExtensions(), chunk),
                        base.retrieval(), base.archive()))
                .diagnostics().data();

        assertThat(view.warnings()).anySatisfy(w -> assertThat(w).contains("child-chars=600").contains("静默截断"));
    }

    private DiagnosticsController controller(RagProperties props) {
        return new DiagnosticsController(diagnostics, props);
    }

    private static RagProperties props(RagProperties.Retrieval retrieval) {
        return new RagProperties(
                new RagProperties.LmStudio("http://localhost:1234", "lm-studio", "qwen2.5-vl-7b-instruct",
                        "bge-base-zh-v1.5", 0.3, 0.8, null, Duration.ofSeconds(90), 1),
                new RagProperties.Storage("./storage"),
                new RagProperties.Embedding(768, 16),
                RagProperties.Ingest.defaults(), retrieval, RagProperties.Archive.defaults());
    }
}
