package com.rag.knowledge.db;

import com.rag.knowledge.domain.SegmentDraft;
import com.rag.knowledge.domain.SegmentView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * kb_segment 仓储：先写「影子版本」，成功后才放开 available，再删旧版本。
 * <p>
 * 蓝绿切换是必要的：重新入库要跑几十秒到几分钟，直接覆盖会让这段时间里的问答拿到半套索引，
 * 表现为「答案时好时坏」，而且旧块一旦被删就没法回退。
 */
@Repository
public class SegmentRepository {

    /** PG 单条语句最多 65535 个参数，父子关联每对占 2 个，留足余量分批。 */
    private static final int LINK_BATCH = 20000;

    private final JdbcTemplate jdbc;

    public SegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 整代块先以 available=false 落库，向量与文本一起写，避免「有块无向量」的中间态。 */
    public int insertGeneration(long docId, long kbId, int docVersion, String embeddingModel,
                               List<SegmentDraft> drafts) {
        if (drafts.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO kb_segment (doc_id, kb_id, chunk_no, doc_version, content, heading_path,
                                        page_start, page_end, char_start, char_end,
                                        embedding, embedding_model, emb_status, available)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?, ?, false)
                """;
        List<Object[]> batch = new ArrayList<>(drafts.size());
        for (SegmentDraft draft : drafts) {
            batch.add(new Object[] {
                    docId, kbId, draft.chunkNo(), docVersion, draft.content(), nullIfBlank(draft.headingPath()),
                    draft.pageStart(), draft.pageEnd(), draft.charStart(), draft.charEnd(),
                    toVectorLiteral(draft.embedding()), embeddingModel, draft.embedding() == null ? "SKIPPED" : "READY" });
        }
        int[] rows = jdbc.batchUpdate(sql, batch);
        return rows.length;
    }

    /**
     * 父子关系在插入后一次性补齐：JDBC 批量插入拿不到自增 id，用 (子块号, 父块号) 自连接比逐行 RETURNING 快得多。
     */
    public void linkParents(long docId, int docVersion, List<int[]> childToParentChunkNo) {
        for (int from = 0; from < childToParentChunkNo.size(); from += LINK_BATCH) {
            List<int[]> slice = childToParentChunkNo.subList(from, Math.min(from + LINK_BATCH,
                    childToParentChunkNo.size()));
            StringJoiner tuples = new StringJoiner(",");
            List<Object> args = new ArrayList<>(slice.size() * 2 + 4);
            for (int[] pair : slice) {
                tuples.add("(?,?)");
                args.add((long) pair[0]);
                args.add((long) pair[1]);
            }
            String sql = """
                    UPDATE kb_segment c
                    SET parent_id = p.id
                    FROM (VALUES %s) AS m(child_no, parent_no)
                    JOIN kb_segment p ON p.doc_id = ? AND p.doc_version = ? AND p.chunk_no = m.parent_no
                    WHERE c.doc_id = ? AND c.doc_version = ? AND c.chunk_no = m.child_no
                    """.formatted(tuples);
            args.add(docId);
            args.add(docVersion);
            args.add(docId);
            args.add(docVersion);
            jdbc.update(sql, args.toArray());
        }
    }

    public int publishGeneration(long docId, int docVersion) {
        return jdbc.update("UPDATE kb_segment SET available = true WHERE doc_id = ? AND doc_version = ?",
                docId, docVersion);
    }

    public int deleteOtherGenerations(long docId, int keepVersion) {
        return jdbc.update("DELETE FROM kb_segment WHERE doc_id = ? AND doc_version <> ?", docId, keepVersion);
    }

    public int deleteGeneration(long docId, int docVersion) {
        return jdbc.update("DELETE FROM kb_segment WHERE doc_id = ? AND doc_version = ?", docId, docVersion);
    }

    /**
     * 文档详情页/分块调参预览用：按 chunk_no 顺序返回当前生效那一代的块。
     * onlySearchable 过滤的是「进不进向量索引」，判据是 emb_status：父块只当上下文，不生成向量。
     */
    public List<SegmentView> listForDocument(long docId, int docVersion, boolean onlySearchable, int limit,
                                             int offset) {
        String sql = """
                SELECT s.id, s.chunk_no, p.chunk_no AS parent_chunk_no, s.content, s.heading_path,
                       s.page_start, s.page_end, s.char_start, s.char_end, s.available, s.emb_status,
                       s.edited_at, s.hit_count
                FROM kb_segment s
                LEFT JOIN kb_segment p ON p.id = s.parent_id
                WHERE s.doc_id = ? AND s.doc_version = ?
                """
                + (onlySearchable ? "AND s.emb_status = 'READY' " : "")
                + "ORDER BY s.chunk_no LIMIT ? OFFSET ?";
        return jdbc.query(sql, SEGMENT_MAPPER, docId, docVersion, limit, offset);
    }

    /** 编辑框要拿整块全文，列表里的正文是按宽度截断显示的。 */
    public Optional<SegmentView> findView(long docId, long segmentId) {
        return jdbc.query("""
                SELECT s.id, s.chunk_no, p.chunk_no AS parent_chunk_no, s.content, s.heading_path,
                       s.page_start, s.page_end, s.char_start, s.char_end, s.available, s.emb_status,
                       s.edited_at, s.hit_count
                FROM kb_segment s
                LEFT JOIN kb_segment p ON p.id = s.parent_id
                WHERE s.id = ? AND s.doc_id = ?
                """, SEGMENT_MAPPER, segmentId, docId).stream().findFirst();
    }

    public int countForDocument(long docId, int docVersion) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM kb_segment WHERE doc_id = ? AND doc_version = ?",
                Integer.class, docId, docVersion);
        return count == null ? 0 : count;
    }

    private static final RowMapper<SegmentView> SEGMENT_MAPPER = (rs, rowNum) -> new SegmentView(
            rs.getLong("id"),
            rs.getInt("chunk_no"),
            (Integer) rs.getObject("parent_chunk_no"),
            rs.getString("content"),
            rs.getString("heading_path"),
            rs.getInt("page_start"),
            rs.getInt("page_end"),
            rs.getInt("char_start"),
            rs.getInt("char_end"),
            rs.getBoolean("available"),
            rs.getString("emb_status"),
            rs.getObject("edited_at") != null,
            rs.getInt("hit_count"));

    public int count(long docId, int docVersion, boolean onlyPublished) {
        String sql = "SELECT count(*) FROM kb_segment WHERE doc_id = ? AND doc_version = ?"
                + (onlyPublished ? " AND available" : "");
        Integer count = jdbc.queryForObject(sql, Integer.class, docId, docVersion);
        return count == null ? 0 : count;
    }

    public int countMissingEmbedding(long docId, int docVersion) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM kb_segment
                WHERE doc_id = ? AND doc_version = ? AND available AND emb_status = 'READY' AND embedding IS NULL
                """, Integer.class, docId, docVersion);
        return count == null ? 0 : count;
    }

    /**
     * 禁用/恢复单个块：只动 available，检索谓词固定带这个条件。
     * doc_id 进 WHERE 是防越权——不能拿着别人的 segmentId 关掉本库的块。
     */
    public int setAvailable(long docId, long segmentId, boolean available) {
        return jdbc.update("UPDATE kb_segment SET available = ? WHERE id = ? AND doc_id = ?",
                available, segmentId, docId);
    }

    /**
     * 改正文的同时把向量作废。留旧向量是更糟的选择：向量路会继续按改之前的文字把这块排到前面，
     * 用户看到的却是改后的文字，于是「相似度 0.83 但答案对不上」这种没法追查的现象就出现了。
     * <p>
     * 父块本来就不生成向量（SKIPPED），不能被这条语句带成「等着向量化」——那样它会出现在补算队列里，
     * 而给它算向量反而是错的。关键词路不需要这里做什么：`content_ts` 是生成列，正文一改就自动重算。
     */
    public int editContent(long docId, long segmentId, String content) {
        return jdbc.update("""
                UPDATE kb_segment
                SET content = ?, embedding = NULL, embedding_model = NULL, edited_at = now(),
                    emb_status = CASE WHEN emb_status = 'SKIPPED' THEN 'SKIPPED' ELSE 'PENDING' END
                WHERE id = ? AND doc_id = ?
                """, content, segmentId, docId);
    }

    /** 单块补算向量：与批量入库用同一套状态名，READY 才有向量可查。 */
    public int setEmbedding(long docId, long segmentId, String vectorLiteral, String embeddingModel) {
        return jdbc.update("UPDATE kb_segment SET embedding = ?::vector, embedding_model = ?,"
                + " emb_status = 'READY' WHERE id = ? AND doc_id = ?", vectorLiteral, embeddingModel, segmentId, docId);
    }

    public static String toVectorLiteral(float[] vector) {
        if (vector == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vector.length * 10 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
