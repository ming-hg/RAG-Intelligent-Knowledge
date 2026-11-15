package com.rag.knowledge.db;

import com.rag.knowledge.common.RagException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * chat_session / chat_message 仓储。
 * <p>
 * 一次问答是「先落用户问题，再落一条 STREAMING 的空壳回答，流结束后回填正文与状态」三段写。
 * 中间态必须留痕：本地模型跑几分钟然后被 kill 是常态，只剩问题没有回答时界面要能显示「本轮已中断」，
 * 而不是把问题一起丢掉。
 * <p>
 * jsonb 一律在 Java 侧序列化后 CAST 进去：PG 端拼 jsonb 会让列形状跟着提示词版本变，回放历史时无法解码。
 */
@Repository
public class ChatRepository {

    public record Session(UUID id, String title, List<Long> kbIds, boolean pinned, Instant createdAt,
                          Instant updatedAt) {
    }

    public record Message(UUID id, UUID sessionId, String role, String content, String status, String queryFinal,
                          String sourcesJson, String retrievalMetaJson, Integer promptTokens,
                          Integer completionTokens, String model, String traceId, Instant createdAt,
                          Instant finishedAt) {

        public boolean fromUser() {
            return "user".equals(role);
        }
    }

    private static final String SESSION_COLUMNS = "id, title, kb_ids, pinned, created_at, updated_at";
    private static final String MESSAGE_COLUMNS = """
            id, session_id, role, content, status, query_final, sources::text AS sources_json,
            retrieval_meta::text AS retrieval_meta_json, prompt_tokens, completion_tokens, model, trace_id,
            created_at, finished_at
            """;

    private static final RowMapper<Session> SESSION_MAPPER = (rs, rowNum) -> new Session(
            (UUID) rs.getObject("id"),
            rs.getString("title"),
            readKbIds(rs.getObject("kb_ids")),
            rs.getBoolean("pinned"),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at")));

    private static final RowMapper<Message> MESSAGE_MAPPER = (rs, rowNum) -> new Message(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("session_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("status"),
            rs.getString("query_final"),
            rs.getString("sources_json"),
            rs.getString("retrieval_meta_json"),
            (Integer) rs.getObject("prompt_tokens"),
            (Integer) rs.getObject("completion_tokens"),
            rs.getString("model"),
            rs.getString("trace_id"),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("finished_at")));

    private final NamedParameterJdbcTemplate jdbc;

    public ChatRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Session createSession(String title, List<Long> kbIds) {
        return jdbc.queryForObject("""
                INSERT INTO chat_session (title, kb_ids)
                VALUES (:title, CAST(:kbIds AS bigint[]))
                RETURNING """ + SESSION_COLUMNS,
                new MapSqlParameterSource().addValue("title", title).addValue("kbIds", arrayLiteral(kbIds)),
                SESSION_MAPPER);
    }

    /** 会话按最近活动排序，置顶的排前面：本地部署只有一个用户，不需要更复杂的排序。 */
    public List<Session> listSessions(int limit, int offset) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + " FROM chat_session WHERE deleted_at IS NULL"
                + " ORDER BY pinned DESC, updated_at DESC LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset), SESSION_MAPPER);
    }

    public Optional<Session> findSession(UUID id) {
        List<Session> rows = jdbc.query("SELECT " + SESSION_COLUMNS + " FROM chat_session"
                + " WHERE id = :id AND deleted_at IS NULL", new MapSqlParameterSource("id", id), SESSION_MAPPER);
        return rows.stream().findFirst();
    }

    public Session requireSession(UUID id) {
        return findSession(id).orElseThrow(() -> RagException.notFound("会话不存在或已删除：" + id));
    }

    public int rename(UUID id, String title) {
        return jdbc.update("UPDATE chat_session SET title = :title, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("title", title));
    }

    /** 首问即标题：新建会话时没人想先起名字，但列表里全是「新会话」也没法用。 */
    public int nameFromFirstQuestion(UUID id, String question) {
        String title = question.trim();
        title = title.length() <= 24 ? title : title.substring(0, 24) + "…";
        return jdbc.update("UPDATE chat_session SET title = :title, updated_at = now()"
                + " WHERE id = :id AND title = '新会话'", new MapSqlParameterSource("id", id).addValue("title", title));
    }

    public int setPinned(UUID id, boolean pinned) {
        return jdbc.update("UPDATE chat_session SET pinned = :pinned, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("pinned", pinned));
    }

    public int softDeleteSession(UUID id) {
        return jdbc.update("UPDATE chat_session SET deleted_at = now() WHERE id = :id AND deleted_at IS NULL",
                new MapSqlParameterSource("id", id));
    }

    /** 返回最近 limit 条，但按时间正序，方便直接当对话历史喂给模型。 */
    public List<Message> listMessages(UUID sessionId, int limit) {
        List<Message> rows = new ArrayList<>(jdbc.query("SELECT " + MESSAGE_COLUMNS + " FROM chat_message"
                + " WHERE session_id = :id ORDER BY created_at DESC, id DESC LIMIT :limit",
                new MapSqlParameterSource().addValue("id", sessionId).addValue("limit", limit), MESSAGE_MAPPER));
        Collections.reverse(rows);
        return List.copyOf(rows);
    }

    public UUID insertQuestion(UUID sessionId, String content, String traceId) {
        return insert(sessionId, "user", content, "DONE", null, null, null, traceId);
    }

    /** 回答先以 STREAMING 落库，正文留空；客户端断开时这行就是「中断」的证据。 */
    public UUID beginAnswer(UUID sessionId, String content, String queryFinal, String sourcesJson,
                            String retrievalMetaJson, String traceId) {
        return jdbc.queryForObject("INSERT INTO chat_message (session_id, role, content, status, query_final,"
                + " sources, retrieval_meta, trace_id, created_at) VALUES (:sessionId, 'assistant', :content,"
                + " 'STREAMING', :queryFinal, CAST(:sources AS jsonb), CAST(:meta AS jsonb), :traceId, now())"
                + " RETURNING id",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("content", content)
                        .addValue("queryFinal", queryFinal)
                        .addValue("sources", sourcesJson)
                        .addValue("meta", retrievalMetaJson)
                        .addValue("traceId", traceId),
                UUID.class);
    }

    public int finishAnswer(UUID messageId, String content, String status, Integer promptTokens,
                            Integer completionTokens, String model) {
        return jdbc.update("UPDATE chat_message SET content = :content, status = :status,"
                + " prompt_tokens = :promptTokens, completion_tokens = :completionTokens, model = :model,"
                + " finished_at = now() WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", messageId)
                        .addValue("content", content)
                        .addValue("status", status)
                        .addValue("promptTokens", promptTokens)
                        .addValue("completionTokens", completionTokens)
                        .addValue("model", model));
    }

    public int markAnswer(UUID messageId, String content, String status) {
        return jdbc.update("UPDATE chat_message SET content = :content, status = :status, finished_at = now()"
                + " WHERE id = :id",
                new MapSqlParameterSource().addValue("id", messageId).addValue("content", content)
                        .addValue("status", status));
    }

    public void touchSession(UUID sessionId) {
        jdbc.update("UPDATE chat_session SET updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", sessionId));
    }

    private UUID insert(UUID sessionId, String role, String content, String status, String queryFinal,
                        String sourcesJson, String metaJson, String traceId) {
        return jdbc.queryForObject("INSERT INTO chat_message (session_id, role, content, status, query_final,"
                + " sources, retrieval_meta, trace_id) VALUES (:sessionId, :role, :content, :status, :queryFinal,"
                + " CAST(:sources AS jsonb), CAST(:meta AS jsonb), :traceId) RETURNING id",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("role", role)
                        .addValue("content", content)
                        .addValue("status", status)
                        .addValue("queryFinal", queryFinal)
                        .addValue("sources", sourcesJson == null ? "[]" : sourcesJson)
                        .addValue("meta", metaJson == null ? "{}" : metaJson)
                        .addValue("traceId", traceId),
                UUID.class);
    }

    /** PG 数组字面量：bigint[] 走 CAST(text) 比 createArrayOf 少一层 Connection 回调。 */
    static String arrayLiteral(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(ids.size() * 8 + 2).append('{');
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.append('}').toString();
    }

    private static List<Long> readKbIds(Object array) {
        if (array == null) {
            return List.of();
        }
        if (array instanceof Object[] cells) {
            return Arrays.stream(cells)
                    .filter(Objects::nonNull)
                    .map(v -> Long.valueOf(v.toString()))
                    .toList();
        }
        return List.of();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
