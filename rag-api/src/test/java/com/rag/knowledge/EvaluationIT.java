package com.rag.knowledge;

import com.rag.knowledge.app.eval.EvalScorer.Metrics;
import com.rag.knowledge.app.eval.EvaluationService;
import com.rag.knowledge.app.eval.EvaluationService.PerCase;
import com.rag.knowledge.app.eval.EvaluationService.RunDetail;
import com.rag.knowledge.app.eval.EvaluationService.RunView;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.db.EvaluationRepository;
import com.rag.knowledge.db.EvaluationRepository.Case;
import com.rag.knowledge.db.SegmentRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * M5e 的验收面：跑分对着真实 pgvector 的名次算指标，并且把每轮的参数、指标、逐条判定真的落进库。
 * <p>
 * 三件只有连库才成立的事：名次由真实余弦决定；{@code eval_result} 在用例删掉后仍读得回当时的问题与原因；
 * from-feedback 那条 SQL 真能在 {@code chat_message} 上捞出「人已订正过的差评」——role 的大小写、
 * 取 {@code query_final} 还是 {@code parent_msg_id}、{@code kb_ids} 数组过滤，写错任何一处都只是查出空集，
 * 而纯单测会一路绿。
 * <p>
 * 统一用 {@code mode=vector}：官方 pgvector 镜像的关键词路是 pg_trgm 兜底，字面巧合会带来读不懂的名次，
 * 而这里要钉的是「名次 → 指标 → 落库」这条链，不是分词。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rag.embedding.dim=768",
        "rag.ingest.worker-enabled=false"
})
class EvaluationIT {

    private static final long KB_ID = 1L;
    private static final long DOC_ID = 11L;
    private static final String FIRST = "网关超时怎么排查";
    private static final String FIRST_MARKER = "ERR_GATEWAY_TIMEOUT";
    private static final String SECOND = "熔断阈值是多少";
    private static final String SECOND_MARKER = "熔断阈值";
    private static final String NEGATIVE = "食堂今天吃什么";
    /** 与 NEGATIVE 的区别只在向量：这条问的是库里没有的话题，但问法与查询轴对齐，所以照样召回一整轮。 */
    private static final String UNSUPPRESSED = "食堂今天几点关门";

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("rag_knowledge")
            .withUsername("rag")
            .withPassword("rag_local_dev");

    @MockitoBean
    EmbeddingModel embeddingModel;

    @Autowired
    EvaluationService evaluation;

    @Autowired
    EvaluationRepository evals;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("TRUNCATE knowledge_base, kb_document, kb_segment, kb_task, chat_message, chat_session,"
                + " eval_case, eval_run, eval_result RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO knowledge_base (id, name, embedding_model, embedding_dim) OVERRIDING SYSTEM VALUE"
                + " VALUES (?, '主库', 'text-embedding-bge-base-zh-v1.5', 768)", KB_ID);
        jdbc.update("INSERT INTO kb_document (id, kb_id, name, sha256, content, status, version, available)"
                + " OVERRIDING SYSTEM VALUE VALUES (?, ?, '运维手册.pdf', 'sha-11', '正文', 'READY', 1, true)",
                DOC_ID, KB_ID);
        // 投影从 1.0 递减到 0.6：名次固定，最低一条仍高于 min-score=0.45，所以前五名一定是这五块
        segment(101, "网关超时 " + FIRST_MARKER + " 的排查步骤", 1.0);
        segment(102, "重启网关进程即可恢复", 0.9);
        segment(103, "查看 upstream 日志确认后端耗时", 0.8);
        segment(104, "限流阈值的配置文件位置", 0.7);
        segment(105, SECOND + "：见 " + SECOND_MARKER + " 一节", 0.6);
        // 负例往与查询轴垂直的方向问：真实余弦 0，闸门把它整个挡掉，拒答才是「库里真没有」而不是「服务挂了」
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> Response.from(new Embedding(
                NEGATIVE.equals(invocation.<String>getArgument(0)) ? orthogonalToQueryAxis() : onQueryAxis())));
    }

    /** 两个正例（期望块排在 1、5）加一个该拒答的负例。反馈那两条测试自己建用例，所以不放进来。 */
    private void stubLabelledCases() {
        addCase(FIRST, FIRST_MARKER, true);
        addCase(SECOND, SECOND_MARKER, true);
        addCase(NEGATIVE, null, false);
    }

    @Test
    void scoresComeFromRealVectorRanksAndEveryJudgementIsPersisted() {
        stubLabelledCases();
        RunDetail run = evaluation.run(KB_ID, "vector", null, "第一轮");

        Metrics metrics = run.run().metrics();
        assertThat(metrics.cases()).isEqualTo(3);
        assertThat(metrics.positive()).isEqualTo(2);
        assertThat(metrics.negative()).isEqualTo(1);
        assertThat(metrics.invalid()).isZero();
        // 两个正例的期望块分别排在第 1 与第 5：所以 hit@1、hit@3 只有一半，topK=6 的 hit@K 才满
        assertThat(metrics.hitAt1()).isEqualTo(0.5);
        assertThat(metrics.hitAt3()).isEqualTo(0.5);
        assertThat(metrics.hitAtK()).isEqualTo(1.0);
        assertThat(metrics.mrr()).isEqualTo(0.6);
        assertThat(metrics.refusalAccuracy()).isEqualTo(1.0);
        assertThat(metrics.emptyRecallRate()).isZero();
        assertThat(metrics.rankedBeyondK()).isZero();

        // 报表顺序是「错例在前」，全对时按名次升序，拒答那条没有名次所以排最后
        assertThat(run.cases()).extracting("question").containsExactly(FIRST, SECOND, NEGATIVE);
        assertThat(run.cases()).extracting("firstRank").containsExactly(1, 5, null);
        assertThat(row(run, SECOND).reason()).contains("排在第 5 名").contains("前 3 名是别的");
        assertThat(row(run, FIRST).reason()).isNull();
        assertThat(row(run, NEGATIVE).refused()).isTrue();
        assertThat(row(run, NEGATIVE).correct()).isTrue();

        long scoredRun = run.run().id();
        // detail 里逐条名次带相关性标记：光知道「没进前三」没用，得看见挤在前面的是什么
        assertThat(jdbc.queryForList("""
                SELECT e->>'rank' FROM eval_result r, jsonb_array_elements(r.detail) e
                WHERE r.run_id = ? AND r.first_rank = 1 ORDER BY (e->>'rank')::int
                """, String.class, scoredRun)).containsExactly("1", "2", "3", "4", "5");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM eval_result r, jsonb_array_elements(r.detail) e
                WHERE r.run_id = ? AND r.first_rank = 1 AND (e->>'relevant')::boolean
                """, Long.class, scoredRun)).isEqualTo(1L);
    }

    /**
     * 负例只要真的装下了证据就不算拒答。真机第一轮混合模式跑分读出来的正是 0（RRF 阈值 0.012 挡不住无关问题），
     * 这条把那个读数钉住：报表里 refused/correct/reason 三处必须说同一件事，早期版本 refused 写死 true，
     * 于是同一条记录既写着「已拒答」又判「错」，还把真正的 0 分藏了起来。
     */
    @Test
    void aNegativeThatRecallsEvidenceIsReportedAsUnsuppressedNotRefused() {
        addCase(UNSUPPRESSED, null, false);

        RunDetail run = evaluation.run(KB_ID, "vector", null, null);
        PerCase negative = row(run, UNSUPPRESSED);

        assertThat(negative.hits()).isEqualTo(5);
        assertThat(negative.refused()).as("装得下证据就是没拒答").isFalse();
        assertThat(negative.correct()).isFalse();
        assertThat(negative.reason()).contains("被召回了").contains("第 1 名");
        assertThat(run.run().metrics().refusalAccuracy()).as("幻觉没被压住，分数必须如实是 0").isZero();
    }

    @Test
    void settingsRecordWhatActuallyTookEffectSoTwoRunsAreComparable() {
        stubLabelledCases();
        long runId = evaluation.run(KB_ID, "vector", 3, "只给三个位置").run().id();

        Map<String, Object> settings = evaluation.detail(runId).run().settings();
        assertThat(settings)
                .containsEntry("mode", "VECTOR")
                .containsEntry("modeOverride", "vector")
                .containsEntry("topK", 3)
                .containsEntry("topKOverride", 3)
                // 向量路生效的是余弦闸门 0.45，不是 RRF 量纲的 0.012：抄错这一个数就能让检索常年空手
                .containsEntry("threshold", 0.45)
                .containsEntry("minScore", 0.45)
                .containsEntry("minRrfScore", 0.012)
                .containsEntry("candidateK", 40)
                .containsEntry("embeddingDim", 768)
                .containsEntry("embeddingModel", "text-embedding-bge-base-zh-v1.5")
                .containsEntry("nCtx", 16384)
                .containsEntry("contextBudgetChars", 14784)
                // 官方 pgvector 镜像没有 zhparser，快照必须把「这次实际用的是三元组」写下来，否则换个镜像分数变了找不到原因
                .containsEntry("keywordEngine", "TRIGRAM");
        assertThat(jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM eval_run WHERE id = ?", Boolean.class,
                runId)).isTrue();
    }

    /** 评测集存在的唯一理由：改一个参数，分数变化能归因到那个参数。这里动的真的是装几块。 */
    @Test
    void aSmallerTopKPushesTheFifthPlaceHitOutOfTheWindowAndTheDeltaSaysSo() {
        stubLabelledCases();
        long wide = evaluation.run(KB_ID, "vector", null, "topK=6").run().id();
        long narrow = evaluation.run(KB_ID, "vector", 3, "topK=3").run().id();

        RunView before = evaluation.detail(wide).run();
        RunView after = evaluation.detail(narrow).run();
        assertThat(before.metrics().hitAtK()).isEqualTo(1.0);
        assertThat(after.metrics().hitAtK()).isEqualTo(0.5);
        assertThat(after.metrics().rankedBeyondK()).as("不是空召回：前三名被别的占了").isEqualTo(0.5);
        assertThat(after.metrics().emptyRecallRate()).isZero();
        assertThat(after.metrics().refusalAccuracy()).isEqualTo(1.0);
        assertThat(row(evaluation.detail(narrow), SECOND).reason()).contains("没进前 3 名");

        // delta 只在相邻两轮之间算得出：detail() 那一轮是单条记录，只能靠 listRuns 找回上下文
        assertThat(after.delta()).isEmpty();
        List<RunView> runs = evaluation.listRuns(0);
        assertThat(runs).extracting(RunView::id).containsExactly(narrow, wide);
        assertThat(runs.get(0).delta())
                .containsEntry("hitAtK", -0.5)
                .containsEntry("hitAt1", 0.0)
                .containsEntry("mrr", -0.1);
    }

    /** 删用例不该删证据：结果行带着当时的问题、判据和原因，所以用例没了也还读得回「当初为什么是 1.0」。 */
    @Test
    void deletingACaseLeavesTheEarlierRunReadable() {
        stubLabelledCases();
        RunDetail run = evaluation.run(KB_ID, "vector", null, null);
        long caseId = row(run, SECOND).caseId();

        evals.deleteCase(caseId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM eval_case", Integer.class)).isEqualTo(2);
        RunDetail replay = evaluation.detail(run.run().id());
        assertThat(replay.cases()).hasSize(3);
        assertThat(replay.cases()).extracting("question").contains(FIRST, SECOND);
        assertThat(row(replay, SECOND).reason()).contains("排在第 5 名");
        assertThat(replay.run().metrics().hitAtK()).as("分数是当时算完存下的，不该随用例增删漂移").isEqualTo(1.0);
    }

    @Test
    void correctedBadAnswersBecomeDisabledCasesAndNothingEntersTheRunUntilTheyAreLabelled() {
        seedFeedback("上次说错了", "正确做法是先看 upstream 超时", KB_ID);
        seedFeedback("别的库的差评", "订正过的答案", 2L);
        seedFeedback("没人订正过的差评", null, KB_ID);

        List<Case> imported = evaluation.importFromFeedback(KB_ID);

        assertThat(imported).hasSize(1);
        assertThat(imported.get(0).question()).isEqualTo("上次说错了");
        assertThat(imported.get(0).enabled()).isFalse();
        assertThat(imported.get(0).origin()).isEqualTo("feedback");
        assertThat(imported.get(0).note()).contains("正确做法是先看 upstream 超时");
        assertThat(evals.countCases(KB_ID, true)).isZero();

        // 待标注的一条都不进跑分，否则「捞了 20 条反馈」会立刻被读成「检索崩了」
        assertThatThrownBy(() -> evaluation.run(KB_ID, "vector", null, null))
                .isInstanceOfSatisfying(RagException.class,
                        ex -> assertThat(ex.getMessage()).contains("没有启用的评测用例"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eval_run", Integer.class)).isZero();
    }

    @Test
    void labellingAnImportedCaseIsExactlyWhatMakesItCount() {
        seedFeedback(SECOND, "订正：见熔断阈值一节", KB_ID);
        Case pending = evaluation.importFromFeedback(KB_ID).get(0);

        Case labelled = evaluation.updateCase(pending.id(), SECOND_MARKER, null, null, true);

        assertThat(labelled.enabled()).isTrue();
        RunDetail run = evaluation.run(KB_ID, "vector", null, null);
        assertThat(run.run().metrics().cases()).isEqualTo(1);
        assertThat(run.run().metrics().hitAtK()).isEqualTo(1.0);
        assertThat(row(run, SECOND).firstRank()).isEqualTo(5);
    }

    private void addCase(String question, String marker, boolean shouldAnswer) {
        evaluation.addCase(new EvaluationService.CaseInput(KB_ID, question, marker, null, shouldAnswer, null));
    }

    private void seedFeedback(String question, String annotated, long kbId) {
        UUID sessionId = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_session (id, title, kb_ids) VALUES (?, 'IT 会话', ARRAY[?]::bigint[])",
                sessionId, kbId);
        jdbc.update("INSERT INTO chat_message (id, session_id, role, content, query_final, status, rating,"
                + " annotated_answer) VALUES (?, ?, 'assistant', '模型原来的错话', ?, 'OK', -1, ?)",
                UUID.randomUUID(), sessionId, question, annotated);
    }

    private static PerCase row(RunDetail run, String question) {
        return run.cases().stream().filter(c -> question.equals(c.question())).findFirst().orElseThrow();
    }

    private void segment(long id, String content, double projection) {
        jdbc.update("""
                INSERT INTO kb_segment (id, doc_id, kb_id, parent_id, chunk_no, doc_version, content,
                                        heading_path, page_start, page_end, char_start, char_end,
                                        embedding, embedding_model, emb_status, available)
                OVERRIDING SYSTEM VALUE
                VALUES (?, ?, ?, NULL, ?, 1, ?, '排障', 2, 2, 0, ?, CAST(? AS vector),
                        'text-embedding-bge-base-zh-v1.5', 'READY', true)
                """, id, DOC_ID, KB_ID, (int) (id - 100), content, content.length(), literal(projection));
    }

    private static String literal(double projection) {
        return SegmentRepository.toVectorLiteral(axisVectorArray(projection));
    }

    /** 查询轴固定在第 3 维：块投影多大，它与查询的余弦就是多大，名次完全可预测。 */
    private static float[] axisVectorArray(double projection) {
        float[] values = new float[768];
        values[1] = (float) Math.sqrt(Math.max(0.0, 1.0 - projection * projection));
        values[2] = (float) projection;
        return values;
    }

    private static float[] onQueryAxis() {
        return axisVectorArray(1.0);
    }

    private static float[] orthogonalToQueryAxis() {
        // 必须落在第 4 维：所有块都在 (2,3) 平面里，用第 2 维当「正交」其实还留着 sqrt(1-p²) 的余弦，
        // 0.6 投影那条块照样过闸门，于是负例永远测不到拒答。
        float[] values = new float[768];
        values[3] = 1.0f;
        return values;
    }
}
