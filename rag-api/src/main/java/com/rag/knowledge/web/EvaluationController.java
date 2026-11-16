package com.rag.knowledge.web;

import com.rag.knowledge.app.eval.EvalScorer.Metrics;
import com.rag.knowledge.app.eval.EvaluationService;
import com.rag.knowledge.app.eval.EvaluationService.CaseInput;
import com.rag.knowledge.app.eval.EvaluationService.RunDetail;
import com.rag.knowledge.app.eval.EvaluationService.RunView;
import com.rag.knowledge.common.ApiResponse;
import com.rag.knowledge.db.EvaluationRepository.Case;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评测集与跑分（M5e）：用例的增删改、从反馈里捞候选、跑一轮分、回看历史轮次的参数与分数。
 * <p>
 * 只有检索层，不生成答案，所以这套接口在没有模型服务答应的机器上也能跑——「先有分数再调参」这件事
 * 不该被模型是否在线卡住。
 */
@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    private final EvaluationService evaluation;

    public EvaluationController(EvaluationService evaluation) {
        this.evaluation = evaluation;
    }

    /** shouldAnswer=true 时 expectMarker 可以留空，但那条用例会停在未启用状态等标注。 */
    public record CaseRequest(long kbId, String question, String expectMarker, String expectDoc,
                              Boolean shouldAnswer, String note) {

        CaseInput toInput() {
            return new CaseInput(kbId, question, expectMarker, expectDoc,
                    shouldAnswer == null || shouldAnswer, note);
        }
    }

    /** 局部更新：字段缺席 = 不改，空串 = 清空（与问答反馈那条 PATCH 同一套语义）。 */
    public record CasePatch(String expectMarker, String expectDoc, String note, Boolean enabled) {
    }

    @GetMapping("/cases")
    public ApiResponse<List<Case>> cases(@RequestParam(value = "kbId", required = false) Long kbId,
                                        @RequestParam(value = "enabledOnly", defaultValue = "false")
                                        boolean enabledOnly) {
        List<Case> rows = evaluation.listCases(kbId, enabledOnly);
        if (!enabledOnly) {
            return ApiResponse.ok(rows, "用例共 " + rows.size() + " 条");
        }
        return ApiResponse.ok(rows, "启用的用例 " + rows.size() + " / 共 "
                + evaluation.countCases(kbId, false) + " 条");
    }

    @PostMapping("/cases")
    public ApiResponse<Case> addCase(@RequestBody CaseRequest request) {
        Case created = evaluation.addCase(request.toInput());
        return ApiResponse.ok(created, created.enabled() ? "用例已启用"
                : "用例已建，但正例没有 expect_marker，先停在未启用状态（补标注后 PATCH enabled=true）");
    }

    @PatchMapping("/cases/{id}")
    public ApiResponse<Case> patchCase(@PathVariable long id, @RequestBody CasePatch patch) {
        return ApiResponse.ok(evaluation.updateCase(id, patch.expectMarker(), patch.expectDoc(), patch.note(),
                patch.enabled()), "用例 " + id + " 已更新");
    }

    @DeleteMapping("/cases/{id}")
    public ApiResponse<Void> deleteCase(@PathVariable long id) {
        evaluation.deleteCase(id);
        return ApiResponse.ok(null, "用例 " + id + " 已删除（历史跑分结果保留）");
    }

    /**
     * 从「差评 + 已人工订正」的反馈里生成待标注用例。
     * 生成出来一律未启用：判据（哪段文字算找到）得人给，机器从订正答案里截一段等于让评测自己考自己。
     */
    @PostMapping("/cases/from-feedback")
    public ApiResponse<List<Case>> fromFeedback(@RequestParam("kbId") long kbId) {
        List<Case> created = evaluation.importFromFeedback(kbId);
        return ApiResponse.ok(created, created.isEmpty()
                ? "没有可导入的反馈：需要 rating=-1 且已填 annotatedAnswer 的回答"
                : "已生成 " + created.size() + " 条待标注用例（enabled=false），补上 expect_marker 再启用");
    }

    /**
     * 跑一轮分。kbId 不给 = 全部启用用例；mode/topK 是唯一开放的覆盖项，
     * 其余参数（阈值、候选池、rrf-k）要比较就重启服务改配置，这样参数快照必然等于真实生效值。
     */
    @PostMapping("/run")
    public ApiResponse<RunDetail> run(@RequestParam(value = "kbId", required = false) Long kbId,
                                      @RequestParam(value = "mode", required = false) String mode,
                                      @RequestParam(value = "topK", required = false) Integer topK,
                                      @RequestParam(value = "note", required = false) String note) {
        RunDetail detail = evaluation.run(kbId, mode, topK, note);
        return ApiResponse.ok(detail, summary(detail.run()));
    }

    @GetMapping("/runs")
    public ApiResponse<List<RunView>> runs(@RequestParam(value = "limit", defaultValue = "0") int limit) {
        List<RunView> rows = evaluation.listRuns(limit);
        return ApiResponse.ok(rows, "最近 " + rows.size() + " 轮跑分（delta 是相对上一轮）");
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<RunDetail> run(@PathVariable long id) {
        RunDetail detail = evaluation.detail(id);
        return ApiResponse.ok(detail, summary(detail.run()));
    }

    private static String summary(RunView run) {
        Metrics m = run.metrics();
        StringBuilder text = new StringBuilder("跑分 #").append(run.id()).append("：")
                .append(m.cases()).append(" 条（正例 ").append(m.positive()).append("，负例 ").append(m.negative())
                .append("，待标注 ").append(m.invalid()).append("）");
        append(text, "hit@1", m.hitAt1());
        append(text, "hit@3", m.hitAt3());
        append(text, "hit@K", m.hitAtK());
        append(text, "MRR", m.mrr());
        append(text, "空召回率", m.emptyRecallRate());
        append(text, "拒答正确率", m.refusalAccuracy());
        append(text, "上下文截断率", m.truncatedCaseRate());
        if (m.truncatedCaseRate() != null && m.truncatedCaseRate() > 0) {
            text.append(" ｜ 有正例召回了却没装进上下文，先查 n_ctx / parent-max-chars，别去动阈值");
        }
        if (!run.delta().isEmpty()) {
            text.append(" ｜ 与上一轮差值 ").append(run.delta());
        }
        return text.toString();
    }

    /** null 指标写成「不适用」而不是 0：负例为零时拒答正确率不存在，报 0 会被读成全错。 */
    private static void append(StringBuilder text, String label, Double value) {
        text.append("，").append(label).append('=').append(value == null ? "不适用" : value);
    }
}
