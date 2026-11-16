package com.rag.knowledge.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 评测集与跑分的仓储（M5e）。
 * <p>
 * jsonb 一律在 Java 侧序列化后 {@code ?::jsonb} 写入、{@code ::text} 读出（与 {@link ChatRepository} 同一套）：
 * 让 PG 端拼 jsonb，等于把列的形状交给数据库版本决定，回放历史跑分时解不开。
 */
@Repository
public class EvaluationRepository {

    public record Case(long id, long kbId, String question, String expectMarker, String expectDoc,
                       boolean shouldAnswer, String origin, UUID originMsgId, String note, boolean enabled) {
    }

    /** 一次跑分。settings 与 metrics 是原始 json 文本，由服务层解码——仓储不参与指标口径。 */
    public record Run(long id, String startedAt, String finishedAt, String settingsJson, String metricsJson,
                      String note) {
    }

    /** 单个用例的结果。firstRank 为 null 表示前 k 没召回相关块。 */
    public record Result(long caseId, Integer firstRank, int hits, boolean refused, boolean correct, long tookMs,
                         String question, String expectMarker, String reason, String detailJson) {
    }

    /** from-feedback 捞出来的候选：问句 + 人工订正过的答案。marker 得人来补，所以只能生成未启用用例。 */
    public record FeedbackCandidate(UUID answerId, String question, String annotatedAnswer) {
    }

    private static final String CASE_COLUMNS = "id, kb_id, question, expect_marker, expect_doc, should_answer,"
            + " origin, origin_msg_id, note, enabled";

    private static final RowMapper<Case> CASE_MAPPER = (rs, rowNum) -> new Case(
            rs.getLong("id"), rs.getLong("kb_id"), rs.getString("question"), rs.getString("expect_marker"),
            rs.getString("expect_doc"), rs.getBoolean("should_answer"), rs.getString("origin"),
            rs.getObject("origin_msg_id", UUID.class), rs.getString("note"), rs.getBoolean("enabled"));

    private static final RowMapper<Run> RUN_MAPPER = (rs, rowNum) -> new Run(
            rs.getLong("id"), rs.getString("started_at"), rs.getString("finished_at"),
            rs.getString("settings"), rs.getString("metrics"), rs.getString("note"));

    private static final RowMapper<Result> RESULT_MAPPER = (rs, rowNum) -> new Result(
            rs.getLong("case_id"), (Integer) rs.getObject("first_rank"), rs.getInt("hits"),
            rs.getBoolean("refused"), rs.getBoolean("correct"), rs.getLong("took_ms"),
            rs.getString("question"), rs.getString("expect_marker"), rs.getString("reason"),
            rs.getString("detail"));

    private final JdbcTemplate jdbc;

    public EvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long addCase(long kbId, String question, String expectMarker, String expectDoc, boolean shouldAnswer,
                        String origin, UUID originMsgId, String note, boolean enabled) {
        return jdbc.queryForObject("INSERT INTO eval_case (kb_id, question, expect_marker, expect_doc,"
                + " should_answer, origin, origin_msg_id, note, enabled)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?::uuid, ?, ?) RETURNING id",
                Long.class, kbId, question, blankToNull(expectMarker), blankToNull(expectDoc), shouldAnswer,
                origin, originMsgId == null ? null : originMsgId.toString(), note, enabled);
    }

    /**
     * 局部更新：null = 这次不改，空串 = 清空（与问答反馈那条 PATCH 同一套语义）。
     * 不做成「读回来再整体写回」，否则两个页面同时改同一个用例会互相覆盖。
     */
    public int updateCase(long id, String expectMarker, String expectDoc, String note, Boolean enabled) {
        return jdbc.update("UPDATE eval_case SET expect_marker = COALESCE(?::text, expect_marker),"
                + " expect_doc = COALESCE(?::text, expect_doc), note = COALESCE(?::text, note),"
                + " enabled = COALESCE(?::boolean, enabled) WHERE id = ?",
                expectMarker == null ? null : blankToNull(expectMarker),
                expectDoc == null ? null : blankToNull(expectDoc),
                note == null ? null : blankToNull(note), enabled, id);
    }

    /** 空串落成 NULL：负例本来就没有 marker，留个空串会把「不需要标注」显示成「标注丢了」。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public int deleteCase(long id) {
        return jdbc.update("DELETE FROM eval_case WHERE id = ?", id);
    }

    public List<Case> listCases(Long kbId, boolean enabledOnly) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(CASE_COLUMNS).append(" FROM eval_case WHERE 1=1");
        if (kbId != null) {
            sql.append(" AND kb_id = ?");
            args.add(kbId);
        }
        if (enabledOnly) {
            sql.append(" AND enabled");
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), CASE_MAPPER, args.toArray());
    }

    public Case findCase(long id) {
        List<Case> found = jdbc.query("SELECT " + CASE_COLUMNS + " FROM eval_case WHERE id = ?", CASE_MAPPER, id);
        return found.isEmpty() ? null : found.get(0);
    }

    public int countCases(Long kbId, boolean enabledOnly) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT count(*) FROM eval_case WHERE 1=1"
                + (kbId == null ? "" : " AND kb_id = ?")
                + (enabledOnly ? " AND enabled" : "");
        if (kbId != null) {
            args.add(kbId);
        }
        return jdbc.queryForObject(sql, Integer.class, args.toArray());
    }

    public long startRun(String settingsJson, String note) {
        return jdbc.queryForObject("INSERT INTO eval_run (settings, note) VALUES (?::jsonb, ?) RETURNING id",
                Long.class, settingsJson, note);
    }

    public void addResult(long runId, Result result) {
        jdbc.update("INSERT INTO eval_result (run_id, case_id, first_rank, hits, refused, correct, took_ms,"
                + " question, expect_marker, reason, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                runId, result.caseId(), result.firstRank(), result.hits(), result.refused(), result.correct(),
                result.tookMs(), result.question(), result.expectMarker(), result.reason(), result.detailJson());
    }

    public void finishRun(long runId, String metricsJson) {
        jdbc.update("UPDATE eval_run SET metrics = ?::jsonb, finished_at = now() WHERE id = ?", metricsJson, runId);
    }

    public List<Run> listRuns(int limit) {
        return jdbc.query("SELECT id, started_at::text AS started_at, finished_at::text AS finished_at,"
                + " settings::text AS settings, metrics::text AS metrics, note"
                + " FROM eval_run ORDER BY id DESC LIMIT ?", RUN_MAPPER, limit);
    }

    public Run findRun(long id) {
        List<Run> found = jdbc.query("SELECT id, started_at::text AS started_at, finished_at::text AS finished_at,"
                + " settings::text AS settings, metrics::text AS metrics, note FROM eval_run WHERE id = ?",
                RUN_MAPPER, id);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 错例排前面：跑分报表第一眼要找的是「哪条掉了」，不是「哪条一直对」。 */
    public List<Result> listResults(long runId) {
        return jdbc.query("SELECT case_id, first_rank, hits, refused, correct, took_ms, question, expect_marker,"
                + " reason, detail::text AS detail FROM eval_result WHERE run_id = ?"
                + " ORDER BY correct, first_rank NULLS LAST, case_id", RESULT_MAPPER, runId);
    }

    /**
     * 反馈里已经有人给出正确答案的问句——评测集的天然样本来源。
     * 问句取 {@code query_final}：回答行本来就带着它（引用面板回放要用），而 {@code parent_msg_id}
     * 是个从没被写过的列，靠它 join 只会查出空集。
     * 只回问句和订正文本，不猜 marker：机器从订正答案里截一段当判据，等于让评测「自己考自己」，
     * 分数会随订正的措辞涨，检索却一点没变。补标注这步必须是人做的。
     */
    public List<FeedbackCandidate> findCorrectedBadAnswers(Long kbId) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id AS answer_id, a.query_final AS question, a.annotated_answer AS annotated
                FROM chat_message a
                WHERE a.role = 'assistant' AND a.rating = -1
                  AND coalesce(a.annotated_answer, '') <> ''
                  AND coalesce(a.query_final, '') <> ''
                """);
        List<Object> args = new ArrayList<>();
        if (kbId != null) {
            sql.append("  AND EXISTS (SELECT 1 FROM chat_session s WHERE s.id = a.session_id AND ? = ANY (s.kb_ids))\n");
            args.add(kbId);
        }
        sql.append("ORDER BY a.id DESC LIMIT 50");
        return jdbc.query(sql.toString(), (rs, rowNum) -> new FeedbackCandidate(
                rs.getObject("answer_id", UUID.class), rs.getString("question"), rs.getString("annotated")),
                args.toArray());
    }
}
