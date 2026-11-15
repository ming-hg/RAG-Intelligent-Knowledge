package com.rag.knowledge.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 任务队列：PG 表 + FOR UPDATE SKIP LOCKED + 租约，不引入 MQ。
 * <p>
 * 单机部署里再塞一个 Redis 只会多一个要维护的进程；PG 的行锁足够支撑这个并发量，
 * 而且任务状态和业务数据同库同事务，不会出现「入队成功但业务表回滚」的错位。
 */
@Repository
public class TaskRepository {

    public static final String TYPE_INGEST = "INGEST";

    public record Task(long id, String type, long kbId, Long docId, String payload, int attempt, int maxAttempts) {
    }

    private static final RowMapper<Task> MAPPER = (rs, rowNum) -> new Task(
            rs.getLong("id"),
            rs.getString("task_type"),
            rs.getLong("kb_id"),
            (Long) rs.getObject("doc_id"),
            rs.getString("payload"),
            rs.getInt("attempt"),
            rs.getInt("max_attempts"));

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long enqueue(String type, long kbId, Long docId, String payloadJson, int maxAttempts) {
        Long id = jdbc.queryForObject("""
                INSERT INTO kb_task (task_type, kb_id, doc_id, payload, status, max_attempts)
                VALUES (?, ?, ?, ?::jsonb, 'QUEUED', ?)
                RETURNING id
                """, Long.class, type, kbId, docId, payloadJson, maxAttempts);
        if (id == null) {
            throw new IllegalStateException("任务入队后没有拿到 id：" + type);
        }
        return id;
    }

    /**
     * 领取任务。SKIP LOCKED 让多 worker 互不阻塞；attempt 在这里自增，
     * 所以「领取即计数」，worker 崩在业务代码里也会消耗重试次数，不会出现无限重跑。
     */
    public List<Task> claim(String owner, int limit, Duration lease) {
        return jdbc.query("""
                WITH picked AS (
                    SELECT id FROM kb_task
                    WHERE status = 'QUEUED' AND next_retry_at <= now()
                    ORDER BY id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE kb_task t
                SET status = 'RUNNING', lease_owner = ?, lease_until = now() + make_interval(secs => ?),
                    attempt = attempt + 1, started_at = COALESCE(t.started_at, now())
                FROM picked p
                WHERE t.id = p.id
                RETURNING t.id, t.task_type, t.kb_id, t.doc_id, t.payload::text AS payload, t.attempt, t.max_attempts
                """, MAPPER, limit, owner, Math.toIntExact(Math.max(1, lease.toSeconds())));
    }

    public void finish(long id, String owner) {
        jdbc.update("""
                UPDATE kb_task SET status = 'DONE', finished_at = now(), lease_owner = NULL, lease_until = NULL,
                       error_type = NULL, error_msg = NULL
                WHERE id = ? AND lease_owner = ?
                """, id, owner);
    }

    /** 返回 true 表示还会重试（重新排队），false 表示已到上限或租约已被别人收回。 */
    public boolean fail(long id, String owner, String errorType, String message, Duration backoff) {
        List<Boolean> retried = jdbc.query("""
                UPDATE kb_task
                SET status = CASE WHEN attempt >= max_attempts THEN 'FAILED' ELSE 'QUEUED' END,
                    next_retry_at = now() + make_interval(secs => ?),
                    lease_owner = NULL, lease_until = NULL,
                    error_type = ?, error_msg = ?,
                    finished_at = CASE WHEN attempt >= max_attempts THEN now() ELSE NULL END
                WHERE id = ? AND lease_owner = ?
                RETURNING (status = 'QUEUED')
                """, (rs, rowNum) -> rs.getBoolean(1),
                Math.toIntExact(Math.max(1, backoff.toSeconds())), errorType, truncate(message), id, owner);
        return !retried.isEmpty() && retried.get(0);
    }

    /** 重试也救不了的失败（文件本身有问题、维度不匹配）：直接落 FAILED，不占重试额度。 */
    public void abandon(long id, String owner, String errorType, String message) {
        jdbc.update("""
                UPDATE kb_task SET status = 'FAILED', error_type = ?, error_msg = ?, finished_at = now(),
                       lease_owner = NULL, lease_until = NULL
                WHERE id = ? AND lease_owner = ?
                """, errorType, truncate(message), id, owner);
    }

    /** 进程被 kill 后 RUNNING 会永久残留，这条 SQL 负责把它们放回队列。 */
    public int reapExpiredLeases() {
        Integer rows = jdbc.update("""
                WITH expired AS (
                    SELECT id FROM kb_task
                    WHERE status = 'RUNNING' AND lease_until < now()
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE kb_task t
                SET status = CASE WHEN t.attempt >= t.max_attempts THEN 'FAILED' ELSE 'QUEUED' END,
                    next_retry_at = now(), lease_owner = NULL, lease_until = NULL,
                    error_type = 'LEASE_EXPIRED', error_msg = '租约过期：处理进程可能被中断，已重新排队'
                FROM expired e
                WHERE t.id = e.id
                """);
        return rows == null ? 0 : rows;
    }

    /** 优雅停机：本进程持有的 RUNNING 任务立刻回队列，不用等租约超时。 */
    public int releaseOwned(String owner) {
        return jdbc.update("""
                UPDATE kb_task SET status = 'QUEUED', next_retry_at = now(), lease_owner = NULL, lease_until = NULL
                WHERE status = 'RUNNING' AND lease_owner = ?
                """, owner);
    }

    public int cancelForDoc(long docId) {
        return jdbc.update("UPDATE kb_task SET status = 'CANCELLED', lease_owner = NULL, lease_until = NULL,"
                + " finished_at = now() WHERE doc_id = ? AND status IN ('QUEUED', 'RUNNING')", docId);
    }

    public Optional<Task> findActiveForDoc(String type, long docId) {
        return jdbc.query("""
                SELECT id, task_type, kb_id, doc_id, payload::text AS payload, attempt, max_attempts
                FROM kb_task
                WHERE task_type = ? AND doc_id = ? AND status IN ('QUEUED', 'RUNNING')
                ORDER BY id DESC LIMIT 1
                """, MAPPER, type, docId).stream().findFirst();
    }

    public int countActive(long kbId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM kb_task WHERE kb_id = ? AND status IN ('QUEUED', 'RUNNING')",
                Integer.class, kbId);
        return count == null ? 0 : count;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
