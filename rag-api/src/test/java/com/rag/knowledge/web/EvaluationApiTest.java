package com.rag.knowledge.web;

import com.rag.knowledge.app.eval.EvalScorer.Metrics;
import com.rag.knowledge.app.eval.EvaluationService;
import com.rag.knowledge.app.eval.EvaluationService.CaseInput;
import com.rag.knowledge.app.eval.EvaluationService.RunDetail;
import com.rag.knowledge.app.eval.EvaluationService.RunView;
import com.rag.knowledge.common.ApiResponse;
import com.rag.knowledge.db.EvaluationRepository.Case;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评测接口的对外口径：默认值、话术，以及「分母为零时不许写成 0」。
 * <p>
 * 这里只钉契约，不重复服务层的判分逻辑（那在 EvaluationServiceTest）：接口话术是调用方唯一能读到的解释，
 * 一句「拒答正确率=0」会让人以为负例全错，而实际是这批用例根本没有负例。
 */
class EvaluationApiTest {

    private final EvaluationService evaluation = mock(EvaluationService.class);
    private final EvaluationController controller = new EvaluationController(evaluation);

    @Test
    void aCaseRequestThatOmitsShouldAnswerMeansAPositiveCase() {
        when(evaluation.addCase(any())).thenReturn(aCase(1L, true, true));

        controller.addCase(new EvaluationController.CaseRequest(1L, "怎么分块", "RRF-2-7", null, null, null));

        ArgumentCaptor<CaseInput> input = ArgumentCaptor.forClass(CaseInput.class);
        verify(evaluation).addCase(input.capture());
        assertThat(input.getValue().shouldAnswer()).isTrue();
    }

    @Test
    void anUnlabelledPositiveSaysItIsWaitingForLabellingInsteadOfPretendingToBeLive() {
        when(evaluation.addCase(any())).thenReturn(aCase(9L, true, false));

        ApiResponse<Case> response = controller.addCase(
                new EvaluationController.CaseRequest(1L, "怎么分块", null, null, true, null));

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.message()).contains("未启用").contains("PATCH enabled=true");
    }

    @Test
    void aCaseThatIsImmediatelyUsableSaysSoShortly() {
        when(evaluation.addCase(any())).thenReturn(aCase(9L, false, true));

        assertThat(controller.addCase(new EvaluationController.CaseRequest(1L, "食堂吃什么", null, null, false, null))
                .message()).isEqualTo("用例已启用");
    }

    /** 只看「启用 2 条」会读成「评测集就这 2 条」，而真实情况通常是另有一批等着标注。 */
    @Test
    void anEnabledOnlyListingCountsTheWholeSetToo() {
        when(evaluation.listCases(1L, true)).thenReturn(List.of(aCase(1L, true, true), aCase(2L, false, true)));
        when(evaluation.countCases(1L, false)).thenReturn(7);

        assertThat(controller.cases(1L, true).message()).contains("启用的用例 2").contains("共 7 条");
    }

    @Test
    void aFullListingDoesNotPayForASecondCountQuery() {
        when(evaluation.listCases(null, false)).thenReturn(List.of(aCase(1L, true, true)));

        assertThat(controller.cases(null, false).message()).isEqualTo("用例共 1 条");
        verify(evaluation, never()).countCases(any(), anyBoolean());
    }

    @Test
    void runForwardsEveryOverrideAndHandsBackTheWholeDetail() {
        RunDetail detail = new RunDetail(new RunView(7L, "起", "止", Map.of(), metrics(1, 1, 0), Map.of(), "备注"),
                List.of());
        when(evaluation.run(3L, "keyword", 3, "阈值改回 0.012")).thenReturn(detail);

        ApiResponse<RunDetail> response = controller.run(3L, "keyword", 3, "阈值改回 0.012");

        verify(evaluation).run(3L, "keyword", 3, "阈值改回 0.012");
        assertThat(response.data()).isSameAs(detail);
        assertThat(response.message()).contains("跑分 #7").contains("待标注 0");
    }

    /** hit@1 有值、但这批没有负例可算拒答：后者必须写成「不适用」，0 会被读成「负例全错」。 */
    @Test
    void anAbsentMetricIsSpelledOutAsNotApplicableNotZero() {
        Metrics partial = new Metrics(1, 1, 0, 0, 1.0, 1.0, 1.0, 1.0, null, 0.0, 0.0, 0.0, 12);
        when(evaluation.detail(7L)).thenReturn(new RunDetail(
                new RunView(7L, "起", "止", Map.of(), partial, Map.of(), null), List.of()));

        assertThat(controller.run(7L).message())
                .contains("hit@1=1.0")
                .contains("拒答正确率=不适用")
                .doesNotContain("拒答正确率=0");
    }

    /** 装不全必须单独成一项读数：hit@k 只说检索排第几，预算挤掉的块模型根本读不到。 */
    @Test
    void truncationIsSpelledOutSeparatelyFromHitRate() {
        Metrics truncating = new Metrics(2, 2, 0, 0, 1.0, 1.0, 1.0, 1.0, null, 0.0, 0.0, 0.5, 12);
        when(evaluation.detail(8L)).thenReturn(new RunDetail(
                new RunView(8L, "起", "止", Map.of(), truncating, Map.of(), null), List.of()));

        assertThat(controller.run(8L).message())
                .contains("hit@K=1.0")
                .contains("上下文截断率=0.5")
                .contains("n_ctx");
    }

    @Test
    void theChangeAgainstThePreviousRunIsPutInTheMessageNotBuriedInData() {
        when(evaluation.listRuns(5)).thenReturn(List.of(
                new RunView(8L, "起", "止", Map.of(), metrics(1, 1, 0), Map.of("hitAt1", -0.25), "第二次"),
                new RunView(7L, "起", "止", Map.of(), metrics(1, 1, 0), Map.of(), "第一次")));

        ApiResponse<List<RunView>> response = controller.runs(5);

        assertThat(response.message()).isEqualTo("最近 2 轮跑分（delta 是相对上一轮）");
        assertThat(response.data().get(0).delta()).containsEntry("hitAt1", -0.25);
    }

    @Test
    void anEmptyFeedbackImportSaysWhatItWasLookingFor() {
        when(evaluation.importFromFeedback(1L)).thenReturn(List.of());

        assertThat(controller.fromFeedback(1L).message())
                .contains("没有可导入的反馈").contains("rating=-1").contains("annotatedAnswer");
    }

    @Test
    void importedCasesAreAnnouncedAsUnlabelledRatherThanReady() {
        when(evaluation.importFromFeedback(1L)).thenReturn(List.of(aCase(21L, true, false)));

        assertThat(controller.fromFeedback(1L).message())
                .contains("已生成 1 条待标注用例").contains("enabled=false");
    }

    /** 删用例不删历史结果——否则两个月后那份「检索改好了」的证据会跟着一条用例一起消失。 */
    @Test
    void deletingACaseKeepsTheRunsThatAlreadyScoredIt() {
        assertThat(controller.deleteCase(9L).message()).contains("用例 9 已删除").contains("历史跑分结果保留");
        verify(evaluation).deleteCase(9L);
    }

    @Test
    void aPatchBodyWithoutAnyFieldStillMeansPartialUpdate() {
        when(evaluation.updateCase(9L, null, null, null, null)).thenReturn(aCase(9L, true, true));

        ApiResponse<Case> response = controller.patchCase(9L, new EvaluationController.CasePatch(null, null, null, null));

        verify(evaluation).updateCase(9L, null, null, null, null);
        assertThat(response.message()).isEqualTo("用例 9 已更新");
    }

    private static Metrics metrics(int cases, int positive, int negative) {
        return new Metrics(cases, positive, negative, 0, 1.0, 1.0, 1.0, 1.0,
                negative == 0 ? null : 1.0, 0.0, 0.0, 0.0, 12);
    }

    private static Case aCase(long id, boolean shouldAnswer, boolean enabled) {
        return new Case(id, 1L, "怎么分块", shouldAnswer ? "RRF-2-7" : null, null, shouldAnswer, "manual",
                UUID.randomUUID(), null, enabled);
    }
}
