package com.rag.knowledge.db;

import com.rag.knowledge.domain.DocStatus;
import com.rag.knowledge.domain.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * kb_document 仓储。
 * <p>
 * 约定：正文（content）永远单独取。列表接口一次读几十篇全文会把堆打满，而这是前端轮询进度时调得最勤的接口。
 */
@Repository
public class DocumentRepository {

    private static final String COLUMNS = """
            id, kb_id, name, sha256, file_path, file_size, mime, page_count, char_count,
            status, progress, progress_msg, version, index_version, error_code, error_msg,
            chunk_override::text AS chunk_override
            """;

    private static final RowMapper<Document> MAPPER = (ResultSet rs, int rowNum) -> new Document(
            rs.getLong("id"),
            rs.getLong("kb_id"),
            rs.getString("name"),
            rs.getString("sha256"),
            rs.getString("file_path"),
            (Long) rs.getObject("file_size"),
            rs.getString("mime"),
            (Integer) rs.getObject("page_count"),
            (Integer) rs.getObject("char_count"),
            statusOf(rs.getString("status")),
            rs.getInt("progress"),
            rs.getString("progress_msg"),
            rs.getInt("version"),
            rs.getInt("index_version"),
            rs.getString("error_code"),
            rs.getString("error_msg"),
            rs.getString("chunk_override"));

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long kbId, String name, String sha256, String filePath, Long fileSize, String mime) {
        // version 从 0 起算：每次入库写入 version+1 这一代块，首发即 version=1，重新入库则是 2、3……
        Long id = jdbc.queryForObject("""
                INSERT INTO kb_document (kb_id, name, source_type, file_path, file_size, mime, sha256, status, version)
                VALUES (?, ?, 'FILE', ?, ?, ?, ?, 'PENDING', 0)
                RETURNING id
                """, Long.class, kbId, name, filePath, fileSize, mime, sha256);
        if (id == null) {
            throw new IllegalStateException("插入 kb_document 后没有拿到 id：" + name);
        }
        return id;
    }

    /**
     * 归档导入落文档行：正文、页数、字数、分块覆盖都按包里的那份写，不再回原文件跑一遍 Tika。
     * <p>
     * status 由调用方给：带着可用向量导入直接 READY，向量空间对不上、要交给入库流水线重算的留 PENDING。
     * filePath 只在包里真带了原始文件并落盘成功后才给；否则留 NULL——留着假路径只会在下次重解析时
     * 报一个「文件读不到」的错，那个错看起来像磁盘坏了，不像「这是导入进来的文档」。
     */
    public long insertArchived(long kbId, String name, String sha256, String mime, Long fileSize, String content,
                               Integer pageCount, int version, int indexVersion, DocStatus status,
                               String chunkOverrideJson, String filePath) {
        Long id = jdbc.queryForObject("""
                INSERT INTO kb_document (kb_id, name, source_type, file_path, file_size, mime, sha256, content,
                                         page_count, char_count, status, progress, version, index_version, available,
                                         chunk_override, parsed_at, indexed_at)
                VALUES (?, ?, 'FILE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?::jsonb, now(),
                        CASE WHEN ? THEN now() ELSE NULL END)
                RETURNING id
                """, Long.class, kbId, name, filePath, fileSize, mime, sha256, content, pageCount,
                content.length(), status.name(), status == DocStatus.READY ? 100 : 0, version, indexVersion,
                chunkOverrideJson, status == DocStatus.READY);
        if (id == null) {
            throw new IllegalStateException("插入 kb_document 后没有拿到 id：" + name);
        }
        return id;
    }

    public Optional<Document> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM kb_document WHERE id = ? AND deleted_at IS NULL",
                MAPPER, id).stream().findFirst();
    }

    /** 同库同哈希即同一份文档，重复上传直接复用已有记录，不再烧一遍 embedding。 */
    public Optional<Document> findBySha(long kbId, String sha256) {
        return jdbc.query("SELECT " + COLUMNS + " FROM kb_document WHERE kb_id = ? AND sha256 = ? AND deleted_at IS NULL",
                MAPPER, kbId, sha256).stream().findFirst();
    }

    public List<Document> list(long kbId, DocStatus status, int limit, int offset) {
        if (status == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM kb_document WHERE kb_id = ? AND deleted_at IS NULL"
                    + " ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, kbId, limit, offset);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM kb_document WHERE kb_id = ? AND status = ? AND deleted_at IS NULL"
                + " ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, kbId, status.name(), limit, offset);
    }

    /** 归档导出用：整库存活文档，按 id 升序（包里的 slot 就是这个顺序，导入端据此定位正文与分块文件）。 */
    public List<Document> listLive(long kbId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM kb_document WHERE kb_id = ? AND deleted_at IS NULL"
                + " ORDER BY id", MAPPER, kbId);
    }

    public int count(long kbId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM kb_document WHERE kb_id = ? AND deleted_at IS NULL", Integer.class, kbId);
        return count == null ? 0 : count;
    }

    public int countByStatus(long kbId, DocStatus status) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM kb_document
                WHERE kb_id = ? AND status = ? AND deleted_at IS NULL
                """, Integer.class, kbId, status.name());
        return count == null ? 0 : count;
    }

    /**
     * 「还没解析过」在这条路上是正常状态：上传只写元数据，content 要等解析完才回填。
     * 但 content 是可空列，单列 mapper 会返回 null，而 `Stream.findFirst()` 内部是
     * `Optional.of(...)`——遇到 null 元素直接抛 NullPointerException（不是 empty）。
     * 现象是每一次入库都在还没干活的地方失败，异常还被归进「稍后重试」，看起来像模型服务的问题。
     */
    public String content(long id) {
        List<String> contents = jdbc.queryForList("SELECT content FROM kb_document WHERE id = ?", String.class, id);
        return contents.isEmpty() ? null : contents.get(0);
    }

    /**
     * 解析完成后落正文、页数与真实类型：后续重新分块不必再解析一次文件。
     * mime 用 Tika 探测的结果覆盖上传头——浏览器给的 Content-Type 经常是 application/octet-stream。
     */
    public void saveParsed(long id, String content, int pageCount, String mime) {
        jdbc.update("UPDATE kb_document SET content = ?, char_count = ?, page_count = ?,"
                + " mime = COALESCE(?, mime), parsed_at = now(), updated_at = now() WHERE id = ?",
                content, content.length(), pageCount, mime, id);
    }

    /**
     * 写文档级分块覆盖。传 null 就是把列清回 NULL，即「这篇跟随全局默认」——
     * 用空对象 {} 表达同一件事会让「没设过」和「设成全部等于默认」在库里长得一样。
     */
    public int setChunkOverride(long id, String json) {
        return jdbc.update("UPDATE kb_document SET chunk_override = ?::jsonb, updated_at = now()"
                + " WHERE id = ? AND deleted_at IS NULL", json, id);
    }

    public void updateStage(long id, DocStatus status, int progress, String progressMsg) {
        int rows = jdbc.update("UPDATE kb_document SET status = ?, progress = ?, progress_msg = ?,"
                + " updated_at = now() WHERE id = ? AND deleted_at IS NULL", status.name(), progress, progressMsg, id);
        if (rows == 0) {
            throw new IllegalStateException("文档不存在或已被删除，无法更新为 " + status + "：id=" + id);
        }
    }

    public void markReady(long id, int version, int indexVersion) {
        jdbc.update("""
                UPDATE kb_document
                SET status = 'READY', progress = 100, progress_msg = '入库完成', version = ?, index_version = ?,
                    error_code = NULL, error_msg = NULL, indexed_at = now(), parsed_at = COALESCE(parsed_at, now()),
                    retry_count = 0, updated_at = now()
                WHERE id = ?
                """, version, indexVersion, id);
    }

    public void markFailed(long id, String errorCode, String errorMsg) {
        jdbc.update("""
                UPDATE kb_document
                SET status = 'FAILED', progress_msg = NULL, error_code = ?, error_msg = ?, updated_at = now()
                WHERE id = ?
                """, errorCode, truncate(errorMsg), id);
    }

    public void increaseRetry(long id) {
        jdbc.update("UPDATE kb_document SET retry_count = retry_count + 1, updated_at = now() WHERE id = ?", id);
    }

    public void softDelete(long id) {
        jdbc.update("UPDATE kb_document SET deleted_at = now(), available = false, updated_at = now()"
                + " WHERE id = ? AND deleted_at IS NULL", id);
    }

    /**
     * 进程被 kill 时文档会永久停在中间态，界面一直转圈且没人知道为什么。
     * 启动时把这些行交给重试路径，计数返回给日志。
     */
    public int failInterrupted(String reason) {
        return jdbc.update("""
                UPDATE kb_document
                SET status = 'FAILED', error_code = 'INTERRUPTED', error_msg = ?, updated_at = now()
                WHERE status IN ('PARSING', 'SPLITTING', 'EMBEDDING', 'INDEXING') AND deleted_at IS NULL
                """, reason);
    }

    private static DocStatus statusOf(String raw) {
        try {
            return DocStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("kb_document.status 出现未知值：" + raw
                    + "（数据库里的状态必须在 DocStatus 里定义，否则进度无法显示）", e);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
