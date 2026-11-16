package com.rag.knowledge.app.eval;

import com.rag.knowledge.app.eval.EvalScorer.Metrics;
import com.rag.knowledge.app.eval.EvalScorer.Verdict;
import com.rag.knowledge.app.rag.ContextPlanner;
import com.rag.knowledge.app.rag.EvidencePlanner;
import com.rag.knowledge.app.rag.HybridSearchService;
import com.rag.knowledge.app.rag.KeywordEngine;
import com.rag.knowledge.app.rag.RetrievalMode;
import com.rag.knowledge.app.rag.RetrievalSettings;
import com.rag.knowledge.app.rag.SearchHit;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.EvaluationRepository;
import com.rag.knowledge.db.EvaluationRepository.Case;
import com.rag.knowledge.db.EvaluationRepository.FeedbackCandidate;
import com.rag.knowledge.db.EvaluationRepository.Result;
import com.rag.knowledge.db.EvaluationRepository.Run;
import com.rag.knowledge.db.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测集跑分（M5e）：拿一批「问题 + 期望证据」的用例过一遍检索，算 hit@k / MRR / 空召回率 / 拒答正确率。
 * <p>
 * 只做检索层，不调问答模型。理由不是省事：评测的意义是「改一个参数，分数变化能归因到那个参数」，
 * 而模型采样做不到可重复；把答案质量混进来，同一套参数两次跑出不同分数，调参就没法比较。
 * <p>
 * 每轮跑分把当时的生效参数整套写进 {@code eval_run.settings}——两次分数能比较的前提，是知道两次的参数差在哪。
 * 阈值这类参数不提供按请求覆盖（沿用 {@link RetrievalSettings} 的口径）：要比较就重启服务改配置，
 * 这样 settings 记录的与真实线上路径必然一致。
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final int LIST_RUNS_DEFAULT = 30;
    /** 报表里每条用例只留前几条命中；全量在 eval_result.detail。 */
    private static final int DETAIL_KEEP = 5;

    public record CaseInput(long kbId, String question, String expectMarker, String expectDoc,
                           boolean shouldAnswer, String note) {
    }

    /** 一条用例的结果。reason 是判的那一刻写下的人话解释，不是事后从 hits 反推的。 */
    public record PerCase(long caseId, long kbId, String question, String expectMarker, boolean shouldAnswer,
                          Integer firstRank, int hits, boolean refused, boolean correct, long tookMs, String reason) {
    }

    public record RunView(long id, String startedAt, String finishedAt, Map<String, Object> settings, Metrics metrics,
                          Map<String, Double> delta, String note) {
    }

    public record RunDetail(RunView run, List<PerCase> cases) {
    }

    private final EvaluationRepository evals;
    private final KnowledgeBaseRepository knowledgeBases;
    private final HybridSearchService search;
    private final EvidencePlanner evidence;
    private final RagProperties props;
    private final ObjectMapper json;

    public EvaluationService(EvaluationRepository evals, KnowledgeBaseRepository knowledgeBases,
                             HybridSearchService search, EvidencePlanner evidence, RagProperties props,
                             ObjectMapper json) {
        this.evals = evals;
        this.knowledgeBases = knowledgeBases;
        this.search = search;
        this.evidence = evidence;
        this.props = props;
        this.json = json;
    }

    public List<Case> listCases(Long kbId, boolean enabledOnly) {
        return evals.listCases(kbId, enabledOnly);
    }

    public int countCases(Long kbId, boolean enabledOnly) {
        return evals.countCases(kbId, enabledOnly);
    }

    public Case addCase(CaseInput input) {
        requireKb(input.kbId());
        if (input.question() == null || input.question().isBlank()) {
            throw RagException.badRequest("用例的问题不能为空");
        }
        // 正例没 marker 就没法判相关性：先进「待标注」，人补上再启用，别让它带着空判据进跑分
        boolean enabled = !input.shouldAnswer() || notBlank(input.expectMarker());
        long id = evals.addCase(input.kbId(), input.question().trim(), input.expectMarker(), input.expectDoc(),
                input.shouldAnswer(), "manual", null, annotatedNote(input.note(), enabled), enabled);
        return require(id);
    }

    private static String annotatedNote(String note, boolean enabled) {
        if (enabled) {
            return note;
        }
        String prefix = note == null || note.isBlank() ? "" : note + " ｜ ";
        return prefix + "补上 expect_marker 后启用（正例没有判据不进分数，只算待标注）";
    }

    /**
     * 局部更新：null = 这次不改，空串 = 清空（与问答反馈那条 PATCH 同一套语义）。
     * 启用一条没标注的正例会当场挡住——否则「加了六条待标注」在报表里会读成「检索退化了」。
     */
    public Case updateCase(long id, String expectMarker, String expectDoc, String note, Boolean enabled) {
        Case current = require(id);
        if (Boolean.TRUE.equals(enabled) && current.shouldAnswer()
                && !notBlank(expectMarker == null ? current.expectMarker() : expectMarker)) {
            throw RagException.badRequest("用例 " + id + " 是正例但没有 expect_marker，判不了相关性",
                    "先补标注（命中块正文里必须出现的一段文字），或改成 shouldAnswer=false 当负例用");
        }
        evals.updateCase(id, expectMarker, expectDoc, note, enabled);
        return require(id);
    }

    public void deleteCase(long id) {
        if (evals.deleteCase(id) == 0) {
            throw RagException.notFound("用例不存在：" + id);
        }
    }

    /**
     * 从「差评 + 已人工订正」的反馈里捞候选问句，落成未启用用例（ADR-014 那条闭环的下游）。
     * 不猜 marker：从订正答案里截一段当判据，等于让评测自己考自己——分数会跟着订正的措辞涨，检索一点没变。
     */
    public List<Case> importFromFeedback(long kbId) {
        requireKb(kbId);
        List<Case> created = new ArrayList<>();
        for (FeedbackCandidate candidate : evals.findCorrectedBadAnswers(kbId)) {
            long id = evals.addCase(kbId, candidate.question(), null, null, true, "feedback", candidate.answerId(),
                    "来自反馈订正：" + brief(candidate.annotatedAnswer()), false);
            created.add(require(id));
        }
        log.info("评测集从反馈导入 {} 条待标注用例（kbId={}）", created.size(), kbId);
        return created;
    }

    /**
     * 跑一轮分。kbId 为空 = 全部启用用例（各条按自己标注的库检索），给出的是「整个系统现在什么样」。
     * 单条用例失败不毁掉整轮：模型服务中途挂掉时，剩下的分数仍然可比，失败那条在报表里带着原因。
     */
    public RunDetail run(Long kbId, String modeOverride, Integer topKOverride, String note) {
        List<Case> cases = evals.listCases(kbId, true);
        if (cases.isEmpty()) {
            throw RagException.badRequest(kbId == null ? "评测集是空的，没有启用的用例"
                            : "知识库 " + kbId + " 没有启用的评测用例",
                    "POST /api/evaluation/cases 手工建几条，或 POST /api/evaluation/cases/from-feedback?kbId=1"
                            + " 从「差评 + 已订正」的反馈里捞（捞出来要先补 marker 再启用）");
        }
        RetrievalSettings settings = RetrievalSettings.of(props.retrieval(), modeOverride, topKOverride);
        KeywordEngine engine = search.engine();
        long runId = evals.startRun(write(snapshot(settings, engine, modeOverride, topKOverride)), note);
        List<Verdict> verdicts = new ArrayList<>(cases.size());
        int degraded = 0;
        for (Case c : cases) {
            CaseRun one = runOne(runId, c, settings, modeOverride, topKOverride);
            verdicts.add(one.verdict());
            if (one.degraded()) {
                degraded++;
            }
        }
        Metrics metrics = EvalScorer.aggregate(verdicts, settings.topK());
        evals.finishRun(runId, write(metrics));
        log.info("评测跑分 #{}：{} 条（正例 {}，负例 {}，待标注 {}），topK={} 时 hit@1={}、hit@3={}、hit@K={}、"
                        + "MRR={}、空召回率={}、排在K外={}、拒答正确率={}、上下文截断率={}",
                runId, metrics.cases(), metrics.positive(), metrics.negative(), metrics.invalid(), settings.topK(),
                metrics.hitAt1(), metrics.hitAt3(), metrics.hitAtK(), metrics.mrr(), metrics.emptyRecallRate(),
                metrics.rankedBeyondK(), metrics.refusalAccuracy(), metrics.truncatedCaseRate());
        if (degraded > 0) {
            // 向量路算不出来时检索不报错，而是悄悄退成关键词路：分数照出，但那是一路的分数。
            // 参数快照记的是「请求的模式」（写快照时还没跑），所以这件事只能逐条落在 reason 里 + 这里一条 WARN
            log.warn("评测跑分 #{}：{} 条是降级跑出来的（请求 {}，实际只走了别的）——"
                            + "这一轮的分不能和正常轮次比，先修 embedding 再重跑",
                    runId, degraded, settings.mode());
        }
        if (metrics.truncatedCaseRate() != null && metrics.truncatedCaseRate() > 0) {
            // 分数能看但不能直接跟上一轮比：这一轮有几条是「检索找到了、模型没读到」，
            // 和预算充足的跑分不是同一个条件下测出来的
            log.warn("评测跑分 #{}：{} 条正例里有 {} 召回了却没装进上下文（预算 {} 字符、n_ctx={}）——"
                            + "hit@k 记的是命中，模型却读不到它，换预算之前两轮分数不可比",
                    runId, metrics.positive(), Math.round(metrics.truncatedCaseRate() * 100) + "%",
                    ContextPlanner.budgetChars(props.retrieval().budget(), 0), props.retrieval().budget().nCtx());
        }
        return detail(runId);
    }

    /** 一条用例的判定，外加「这条是不是降级跑出来的」——后者决定这轮的分数能不能和历史比。 */
    private record CaseRun(Verdict verdict, boolean degraded) {
    }

    private CaseRun runOne(long runId, Case c, RetrievalSettings settings, String modeOverride,
                           Integer topKOverride) {
        List<SearchHit> hits;
        boolean refused;
        int unplaced;
        long tookMs;
        boolean degraded;
        RetrievalMode effective;
        String vectorFailure;
        try {
            HybridSearchService.Outcome outcome = search.search(c.question(), List.of(c.kbId()), null,
                    modeOverride, topKOverride);
            // 拒答判据必须和问答链路同一个方法：plan.empty() = 一块都没装下 = 线上真的会回「没有资料」。
            // 装下一半不算拒答（那是截断），unplaced 只拿来把原因写清楚
            ContextPlanner.Plan plan = evidence.plan(outcome.hits(), c.question().length());
            hits = outcome.hits();
            refused = plan.empty();
            unplaced = plan.unplacedHits();
            tookMs = outcome.tookMs();
            degraded = outcome.degraded();
            effective = outcome.effective();
            vectorFailure = outcome.vectorFailure();
        } catch (RuntimeException e) {
            Verdict failed = new Verdict(c.shouldAnswer(), false, false, null, 0, false, 0, 0,
                    "这条没跑成：" + message(e));
            evals.addResult(runId, new Result(c.id(), null, 0, false, false, 0, c.question(), c.expectMarker(),
                    failed.reason(), write(List.of())));
            return new CaseRun(failed, false);
        }
        Verdict verdict = EvalScorer.judge(c.shouldAnswer(), c.expectMarker(), c.expectDoc(),
                hits.stream().map(SearchHit::docName).toList(), hits.stream().map(SearchHit::content).toList(),
                refused, unplaced, tookMs);
        if (degraded) {
            // 向量路没成时检索不报错，分数照出，但那是一路的分数：不写在行里，两轮就会被当成同一条件下测的
            verdict = new Verdict(verdict.positive(), verdict.invalid(), verdict.correct(), verdict.firstRank(),
                    verdict.hits(), verdict.refused(), verdict.unplaced(), verdict.tookMs(),
                    (verdict.reason() == null ? "" : verdict.reason() + " ｜ ") + "降级跑的：请求 " + settings.mode()
                            + "，实际只走 " + effective + (vectorFailure == null ? "" : "（" + vectorFailure + "）"));
        }
        evals.addResult(runId, new Result(c.id(), verdict.firstRank(), verdict.hits(), verdict.refused(),
                verdict.correct(), tookMs, c.question(), c.expectMarker(), verdict.reason(),
                write(detailRows(hits, c, verdict))));
        return new CaseRun(verdict, degraded);
    }

    /** 前几条命中带相关性标记落进 detail：只知道「没进前三」没用，得看见挤在前面的是什么。 */
    private static List<Map<String, Object>> detailRows(List<SearchHit> hits, Case c, Verdict verdict) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(DETAIL_KEEP, hits.size()); i++) {
            SearchHit hit = hits.get(i);
            boolean relevant = c.shouldAnswer() && notBlank(c.expectMarker())
                    && EvalScorer.matches(hit.content(), c.expectMarker())
                    && EvalScorer.docMatches(hit.docName(), c.expectDoc());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("docName", hit.docName());
            row.put("chunkNo", hit.chunkNo());
            row.put("rrf", hit.rrf());
            row.put("relevant", relevant);
            row.put("snippet", hit.snippet(120));
            rows.add(row);
        }
        return rows;
    }

    public List<RunView> listRuns(int limit) {
        List<Run> runs = evals.listRuns(limit <= 0 ? LIST_RUNS_DEFAULT : limit);
        List<RunView> views = new ArrayList<>(runs.size());
        for (int i = 0; i < runs.size(); i++) {
            Metrics now = metricsOf(runs.get(i));
            // listRuns 按 id 倒序，所以上一轮在 i+1：跨两轮取差值才有「这次比上次高在哪」
            Metrics before = i + 1 < runs.size() ? metricsOf(runs.get(i + 1)) : null;
            views.add(new RunView(runs.get(i).id(), runs.get(i).startedAt(), runs.get(i).finishedAt(),
                    readMap(runs.get(i).settingsJson()), now, diff(now, before), runs.get(i).note()));
        }
        return views;
    }

    public RunDetail detail(long runId) {
        Run run = evals.findRun(runId);
        if (run == null) {
            throw RagException.notFound("跑分记录不存在：" + runId);
        }
        List<PerCase> rows = new ArrayList<>();
        for (Result r : evals.listResults(runId)) {
            Case c = evals.findCase(r.caseId());
            rows.add(new PerCase(r.caseId(), c == null ? 0 : c.kbId(), r.question(), r.expectMarker(),
                    c == null || c.shouldAnswer(), r.firstRank(), r.hits(), r.refused(), r.correct(), r.tookMs(),
                    r.reason()));
        }
        return new RunDetail(new RunView(run.id(), run.startedAt(), run.finishedAt(), readMap(run.settingsJson()),
                metricsOf(run), Map.of(), run.note()), rows);
    }

    /** 参数快照：只回答「这一轮到底用的是什么」，字段全部取自真正生效的那份配置，不另立来源。 */
    private Map<String, Object> snapshot(RetrievalSettings settings, KeywordEngine engine, String modeOverride,
                                         Integer topKOverride) {
        RagProperties.Retrieval r = props.retrieval();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", settings.mode().name());
        out.put("modeOverride", modeOverride);
        out.put("topK", settings.topK());
        out.put("topKOverride", topKOverride);
        out.put("candidateK", settings.candidateK());
        out.put("rrfK", settings.rrfK());
        out.put("threshold", settings.threshold());
        out.put("minScore", settings.minScore());
        out.put("minRrfScore", settings.minRrfScore());
        out.put("parentContext", settings.parentContext());
        out.put("keywordEngine", engine.name());
        out.put("embeddingModel", props.lmStudio().embeddingModel());
        out.put("embeddingDim", props.embedding().dim());
        out.put("nCtx", r.budget().nCtx());
        out.put("contextBudgetChars", ContextPlanner.budgetChars(r.budget(), 0));
        return out;
    }

    /** 与上一轮的差值：调参要的不是绝对分数，是「哪一项动了」。 */
    private static Map<String, Double> diff(Metrics now, Metrics before) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (now == null || before == null) {
            return out;
        }
        putDelta(out, "hitAt1", now.hitAt1(), before.hitAt1());
        putDelta(out, "hitAt3", now.hitAt3(), before.hitAt3());
        putDelta(out, "hitAtK", now.hitAtK(), before.hitAtK());
        putDelta(out, "mrr", now.mrr(), before.mrr());
        putDelta(out, "refusalAccuracy", now.refusalAccuracy(), before.refusalAccuracy());
        putDelta(out, "emptyRecallRate", now.emptyRecallRate(), before.emptyRecallRate());
        putDelta(out, "truncatedCaseRate", now.truncatedCaseRate(), before.truncatedCaseRate());
        return out;
    }

    private static void putDelta(Map<String, Double> out, String key, Double now, Double before) {
        if (now != null && before != null) {
            out.put(key, Math.round((now - before) * 10000.0) / 10000.0);
        }
    }

    private Case require(long id) {
        Case found = evals.findCase(id);
        if (found == null) {
            throw RagException.notFound("用例不存在：" + id);
        }
        return found;
    }

    private void requireKb(long kbId) {
        try {
            knowledgeBases.require(kbId);
        } catch (RuntimeException e) {
            throw RagException.notFound("知识库不存在：" + kbId);
        }
    }

    private Metrics metricsOf(Run run) {
        return notBlank(run.metricsJson()) ? json.readValue(run.metricsJson(), Metrics.class) : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String raw) {
        return notBlank(raw) ? (Map<String, Object>) json.readValue(raw, Map.class) : Map.of();
    }

    private String write(Object value) {
        return json.writeValueAsString(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
