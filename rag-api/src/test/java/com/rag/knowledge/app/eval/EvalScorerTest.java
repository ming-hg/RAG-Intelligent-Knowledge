package com.rag.knowledge.app.eval;

import com.rag.knowledge.app.eval.EvalScorer.Metrics;
import com.rag.knowledge.app.eval.EvalScorer.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑分判据与指标（纯计算）。
 * <p>
 * 这里最要紧的是三件事：未标注的用例不许进分母，分母为 0 时指标必须是 null 而不是 0，
 * 以及「拒答」只指装配一块都没装下——召回 6 块装下 3 块是截断，模型照样被问到。
 * 前两条写错，报表会把「没标注」读成「检索退化了」；第三条写错，拒答正确率会永远 0，
 * 而真实首轮跑分就是这样误报的。
 */
class EvalScorerTest {

    @Test
    void firstPlaceHitLiftsEveryTopMetric() {
        Verdict verdict = EvalScorer.judge(true, "RRF-2-7", "运维手册.md",
                List.of("运维手册.md"), List.of("…编号 RRF-2-7 只有关键词路才能命中"), false, 0, 12);

        assertThat(verdict.correct()).isTrue();
        assertThat(verdict.firstRank()).isEqualTo(1);
        assertThat(verdict.reason()).isNull();

        Metrics metrics = EvalScorer.aggregate(List.of(verdict), 6);
        assertThat(metrics.hitAt1()).isEqualTo(1.0);
        assertThat(metrics.hitAt3()).isEqualTo(1.0);
        assertThat(metrics.hitAtK()).isEqualTo(1.0);
        assertThat(metrics.mrr()).isEqualTo(1.0);
        assertThat(metrics.emptyRecallRate()).isZero();
        assertThat(metrics.refusalAccuracy()).as("这批没有负例，拒答正确率不该出现").isNull();
    }

    @Test
    void relevantBlockOutsideTheShortWindowIsStillACarriedHitButNotAGoodOne() {
        Verdict verdict = EvalScorer.judge(true, "LEDGER-7731", null,
                List.of("a.md", "a.md", "a.md", "a.md"),
                List.of("一", "二", "三", "里面有 LEDGER-7731"), false, 0, 30);

        assertThat(verdict.firstRank()).isEqualTo(4);
        assertThat(verdict.reason()).contains("第 4 名").contains("前 3");
        Metrics metrics = EvalScorer.aggregate(List.of(verdict), 6);
        assertThat(metrics.hitAt1()).isZero();
        assertThat(metrics.hitAt3()).isZero();
        assertThat(metrics.hitAtK()).isEqualTo(1.0);
        assertThat(metrics.mrr()).isEqualTo(0.25);
    }

    @Test
    void matchingIgnoresWhitespaceAndCaseBecauseChunkingRewritesBoth() {
        // 分块会把换行吞成空格、也可能留下多余空白，判据必须扛得住这两种写法
        assertThat(EvalScorer.matches("编号\n  rrf-2-7 只有关键词", "RRF-2-7")).isTrue();
        assertThat(EvalScorer.matches("编号 RRF-2-8", "RRF-2-7")).isFalse();
    }

    @Test
    void documentScopeAcceptsTheStemWithoutTheExtension() {
        assertThat(EvalScorer.docMatches("运维手册.md", "运维手册")).isTrue();
        assertThat(EvalScorer.docMatches("评测台账.csv", "运维手册")).isFalse();
        assertThat(EvalScorer.docMatches(null, "运维手册")).as("没有文档名时不能算对上").isFalse();
        assertThat(EvalScorer.docMatches("随便什么.md", null)).as("没标文档范围 = 全库都算").isTrue();
    }

    @Test
    void wrongDocumentDoesNotCountEvenWhenTheMarkerIsThere() {
        Verdict verdict = EvalScorer.judge(true, "ARCHIVE-7731", "运维手册",
                List.of("另一份.md"), List.of("里面有 ARCHIVE-7731"), false, 0, 5);

        assertThat(verdict.correct()).isFalse();
        assertThat(verdict.firstRank()).isNull();
    }

    /** refused 必须照实写：它等于 correct，写成常量 true 就会产出「拒答了却判错」这种自相矛盾的行。 */
    @Test
    void negativeCaseIsCorrectOnlyWhenNothingWasPlaced() {
        Verdict refused = EvalScorer.judge(false, null, null, List.of(), List.of(), true, 0, 4);
        assertThat(refused.refused()).isTrue();
        assertThat(refused.correct()).isTrue();
        assertThat(refused.reason()).isNull();

        Verdict hallucinated = EvalScorer.judge(false, null, null, List.of("变更台账.csv"), List.of("别的编号"),
                false, 0, 9);
        assertThat(hallucinated.refused()).as("端出了证据就是没拒答，报表得说实话").isFalse();
        assertThat(hallucinated.correct()).as("库里没有的东西被召回了就是错").isFalse();
        assertThat(hallucinated.reason()).contains("变更台账.csv");

        Metrics metrics = EvalScorer.aggregate(List.of(refused, hallucinated), 6);
        assertThat(metrics.negative()).isEqualTo(2);
        assertThat(metrics.refusalAccuracy()).isEqualTo(0.5);
        assertThat(metrics.hitAtK()).as("负例不进检索指标的分子").isNull();
    }

    @Test
    void unlabelledPositiveStaysOutOfEveryDenominator() {
        Verdict unlabelled = EvalScorer.judge(true, null, null, List.of(), List.of(), true, 0, 3);
        assertThat(unlabelled.invalid()).isTrue();
        assertThat(unlabelled.correct()).isFalse();

        Verdict good = EvalScorer.judge(true, "M-1", null, List.of("a.md"), List.of("M-1"), false, 0, 3);
        Metrics metrics = EvalScorer.aggregate(List.of(unlabelled, good), 6);

        assertThat(metrics.cases()).isEqualTo(2);
        assertThat(metrics.invalid()).isEqualTo(1);
        assertThat(metrics.positive()).isEqualTo(1);
        assertThat(metrics.hitAtK()).as("分数只由能判的那条算出来").isEqualTo(1.0);
    }

    @Test
    void emptyRecallAndRankedBeyondKAreDifferentFailuresNeedingDifferentFixes() {
        Verdict empty = EvalScorer.judge(true, "M-9", null, List.of(), List.of(), true, 0, 10);
        Verdict buried = EvalScorer.judge(true, "M-8", null, List.of("a.md", "a.md", "a.md"),
                List.of("一", "二", "三"), false, 0, 10);

        assertThat(buried.reason()).contains("没进前 3 名");
        Metrics metrics = EvalScorer.aggregate(List.of(empty, buried), 3);

        assertThat(metrics.emptyRecallRate()).as("一条都没召回：先怀疑阈值或库里真没有").isEqualTo(0.5);
        assertThat(metrics.rankedBeyondK()).as("召回了但相关块排在 k 之外：该改排序").isEqualTo(0.5);
        assertThat(metrics.hitAtK()).isZero();
    }

    @Test
    void refusalThatStillRecalledSomethingIsCountedAsBuriedNotEmpty() {
        // 命中非空却一块都没装下（正文全空）：这不该记成空召回，否则调阈值的人会白调
        Verdict verdict = EvalScorer.judge(true, "M-1", null, List.of("a.md"), List.of("太短的片段"), true, 1, 7);

        assertThat(verdict.reason()).contains("装配一条都没装下");
        Metrics metrics = EvalScorer.aggregate(List.of(verdict), 6);
        assertThat(metrics.emptyRecallRate()).isZero();
        assertThat(metrics.rankedBeyondK()).isEqualTo(1.0);
    }

    /** 装下一半 ≠ 拒答：线上会照常生成答案，所以指标不许变红，但报表必须说清有几块没进模型。 */
    @Test
    void placingPartOfTheHitsIsTruncationNotRefusal() {
        Verdict found = EvalScorer.judge(true, "M-1", null, List.of("a.md", "a.md", "a.md"),
                List.of("M-1 那块", "别", "还有别"), false, 2, 8);
        assertThat(found.correct()).isTrue();
        assertThat(found.refused()).isFalse();
        assertThat(found.reason()).contains("找到了").contains("2 块没装进上下文");

        Verdict missed = EvalScorer.judge(true, "M-9", null, List.of("a.md", "a.md", "a.md"),
                List.of("x", "y", "z"), false, 2, 9);
        assertThat(missed.reason()).contains("没进前 1 名").contains("其余 2 条没装进上下文");

        Metrics metrics = EvalScorer.aggregate(List.of(found, missed), 6);
        assertThat(metrics.emptyRecallRate()).as("装得下一半不是空召回").isZero();
        assertThat(metrics.hitAtK()).isEqualTo(0.5);
        assertThat(metrics.truncatedCaseRate()).as("两条正例都有块没装下").isEqualTo(1.0);
    }

    /** 召回 6 块只装下 3 块、相关块排第 5：检索层算命中，线上模型却读不到它——两件事都得单独说清楚。 */
    @Test
    void aHitInTheUnplacedTailSaysTheModelNeverSawIt() {
        Verdict verdict = EvalScorer.judge(true, "M-5", null,
                List.of("a.md", "a.md", "a.md", "a.md", "a.md", "a.md"),
                List.of("一", "二", "三", "四", "M-5 那块", "六"), false, 3, 9);

        assertThat(verdict.firstRank()).isEqualTo(5);
        assertThat(verdict.correct()).as("检索把相关块召回进了前 6，这一项就是对的").isTrue();
        assertThat(verdict.reason()).contains("排在第 5 名").contains("只装下 3 块").contains("模型没读到它");

        Metrics metrics = EvalScorer.aggregate(List.of(verdict), 6);
        assertThat(metrics.hitAtK()).as("hit@k 量检索，不被预算污染").isEqualTo(1.0);
        assertThat(metrics.truncatedCaseRate()).as("装不下只在截断率这一项里体现").isEqualTo(1.0);
    }

    /** 没有正例就没有分母，截断率是 null；全装下是 0——这两个读数不能混。 */
    @Test
    void truncationRateSeparatesNothingTruncatedFromNothingToJudge() {
        Metrics noPositive = EvalScorer.aggregate(List.of(
                EvalScorer.judge(false, null, null, List.of("a.md"), List.of(), true, 0, 5)), 6);
        assertThat(noPositive.truncatedCaseRate()).isNull();

        Metrics allPlaced = EvalScorer.aggregate(List.of(
                EvalScorer.judge(true, "A", null, List.of("d"), List.of("A"), false, 0, 5)), 6);
        assertThat(allPlaced.truncatedCaseRate()).as("一条没截是 0，和「没法算」是两件事").isZero();
    }

    @Test
    void mrrAveragesReciprocalRanksAcrossThePositiveSet() {
        List<Verdict> verdicts = List.of(
                EvalScorer.judge(true, "A", null, List.of("d"), List.of("A"), false, 0, 1),
                EvalScorer.judge(true, "B", null, List.of("d", "d"), List.of("x", "B"), false, 0, 1),
                EvalScorer.judge(true, "C", null, List.of("d"), List.of("x"), false, 0, 1));

        Metrics metrics = EvalScorer.aggregate(verdicts, 6);

        assertThat(metrics.mrr()).isEqualTo(Math.round((1.0 + 0.5 + 0.0) / 3 * 10000.0) / 10000.0);
        assertThat(metrics.avgTookMs()).isEqualTo(1);
    }

    @Test
    void anEntirelyUnlabelledSetProducesNoRatesAtAll() {
        Metrics metrics = EvalScorer.aggregate(List.of(
                EvalScorer.judge(true, null, null, List.of(), List.of(), true, 0, 1)), 6);

        assertThat(metrics.positive()).isZero();
        assertThat(metrics.hitAt1()).isNull();
        assertThat(metrics.mrr()).isNull();
        assertThat(metrics.avgTookMs()).isZero();
    }
}
