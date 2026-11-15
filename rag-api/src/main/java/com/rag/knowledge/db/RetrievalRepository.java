package com.rag.knowledge.db;

import com.rag.knowledge.app.rag.KeywordEngine;
import com.rag.knowledge.app.rag.RetrievalMode;
import com.rag.knowledge.app.rag.RetrievalQuery;
import com.rag.knowledge.app.rag.SearchHit;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 检索侧唯一的 SQL 出入口：一条语句跑完「召回 → 融合 → 排序 → 截断」。
 * <p>
 * 融合留在数据库里做，而不是两路各拉几百行回 Java 再合并：省掉把整块正文搬出库的开销，而且一次查询只有
 * 一个一致性快照，不会出现两路结果来自不同入库瞬间。
 * <p>
 * RRF 用 1/(rrfK+rank) 而不是加权求和：余弦分和 ts_rank 分不在同一个量纲上，相加等于让其中一路说了算。
 * <p>
 * 过滤条件写在每一路自己的 FROM/WHERE 里，不预先物化 scope CTE：那样 HNSW 索引对上层查询不可见，
 * 向量路会退化成全表精算。用命名参数而不是位置参数，是因为同一段范围过滤要在两条召回路里各写一遍，
 * 位置参数一旦调换顺序就会把向量塞进关键词槽位，而且不报错、只出脏结果。
 */
@Repository
public class RetrievalRepository {

    /**
     * pg_trgm 分支的绝对分数下限。RRF 只有名次没有绝对分，纯关键词检索时靠它把「只剩一点点像」的尾巴切掉，
     * 否则第 40 名的噪音会和第 1 名一起进上下文。0.25 是 trigram 在中文长块上的经验拐点：
     * 再低就基本只剩共用「的/了/是」这类高频字的块。zhparser 分支不需要它，因为 ts 匹配本身就是二值的。
     */
    private static final double TRIGRAM_MIN_SIMILARITY = 0.25;

    private final NamedParameterJdbcTemplate jdbc;

    public RetrievalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 以 kb_segment.content_ts 是否存在作为判据：它是 migration 里确认 chinese 分词配置可用之后才加的生成列，
     * 列在就说明分词器真的能跑，比再去查一遍 pg_available_extensions 可靠。
     */
    public KeywordEngine probeEngine() {
        Integer tsvector = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'kb_segment' AND column_name = 'content_ts'
                """, new MapSqlParameterSource(), Integer.class);
        if (tsvector != null && tsvector > 0) {
            return KeywordEngine.CHINESE_TSVECTOR;
        }
        Integer trgm = jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'",
                new MapSqlParameterSource(), Integer.class);
        return trgm != null && trgm > 0 ? KeywordEngine.TRIGRAM : KeywordEngine.NONE;
    }

    public List<SearchHit> search(RetrievalQuery query) {
        Sql sql = new Sql();
        boolean vector = query.useVector();
        boolean keyword = query.useKeyword();

        if (vector) {
            sql.append("WITH vec AS (SELECT id, row_number() OVER (ORDER BY dist) AS rank, 1.0 - dist AS similarity"
                    + " FROM (SELECT s.id, s.embedding <=> CAST(:queryVec AS vector) AS dist FROM kb_segment s"
                    + " JOIN kb_document d ON d.id = s.doc_id WHERE ");
            sql.scope(query);
            sql.append(" AND s.embedding IS NOT NULL ORDER BY dist LIMIT :vectorCandidates) v)");
            sql.param("queryVec", query.vectorLiteral());
            sql.param("vectorCandidates", query.candidateK());
        }
        if (keyword) {
            sql.append(vector ? ", kw AS (" : "WITH kw AS (")
                    .append("SELECT id, row_number() OVER (ORDER BY m DESC, id) AS rank FROM (SELECT s.id, ");
            if (query.engine() == KeywordEngine.CHINESE_TSVECTOR) {
                // 同一个 plainto_tsquery 写两遍（一次过滤一次打分）：PG 对 immutable 函数只求值一次，
                // 为了复用一个 tsquery 再套一层 CROSS JOIN 反而会让 GIN 的运行时键变成跨层引用。
                sql.append("ts_rank(s.content_ts, plainto_tsquery('chinese', :keywordText)) AS m"
                        + " FROM kb_segment s JOIN kb_document d ON d.id = s.doc_id WHERE ");
                sql.scope(query);
                sql.append(" AND s.content_ts @@ plainto_tsquery('chinese', :keywordText)"
                        + " ORDER BY m DESC, s.id LIMIT :keywordCandidates) k)");
            } else {
                // 不用 % 操作符：它按 pg_trgm.similarity_threshold（默认 0.3）过滤，而 400 字的块对上十几个字的
                // 查询，trigram 交集比例必然远低于 0.3，一刀下去整路空召回。word_similarity 专治这种长度悬殊。
                sql.append("word_similarity(:keywordText, s.content) AS m"
                        + " FROM kb_segment s JOIN kb_document d ON d.id = s.doc_id WHERE ");
                sql.scope(query);
                sql.append(" AND word_similarity(:keywordText, s.content) >= :trigramFloor"
                        + " ORDER BY m DESC, s.id LIMIT :keywordCandidates) k)");
                sql.param("trigramFloor", TRIGRAM_MIN_SIMILARITY);
            }
            sql.param("keywordText", query.text());
            sql.param("keywordCandidates", query.candidateK());
        }
        fusion(sql, query, vector, keyword);

        sql.append(" SELECT f.id, s.doc_id, d.name AS doc_name, s.chunk_no, s.doc_version, s.content,"
                + " s.heading_path, s.page_start, s.page_end, s.char_start, s.char_end, s.parent_id,"
                + " f.vec_rank, f.kw_rank, f.rrf, ");
        if (vector) {
            sql.append("v.similarity FROM fused f JOIN kb_segment s ON s.id = f.id"
                    + " JOIN kb_document d ON d.id = s.doc_id LEFT JOIN vec v ON v.id = f.id WHERE ");
        } else {
            sql.append("CAST(NULL AS double precision) AS similarity FROM fused f JOIN kb_segment s ON s.id = f.id"
                    + " JOIN kb_document d ON d.id = s.doc_id WHERE ");
        }
        String score = query.mode() == RetrievalMode.VECTOR && vector ? "v.similarity" : "f.rrf";
        sql.appendRaw(score).append(" >= :threshold");
        sql.append(" ORDER BY ").appendRaw(score).append(" DESC NULLS LAST, f.id LIMIT :topK");
        sql.param("threshold", query.threshold());
        sql.param("topK", query.topK());

        return jdbc.query(sql.text(), sql.params(), HIT_MAPPER);
    }

    /** 父块正文按需回捞：子块命中后要换成父块给模型，一次批量取，别让上下文装配变成 N 次查询。 */
    public Map<Long, String> parentContents(List<Long> parentIds) {
        List<Long> ids = parentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (int i = 0; i < ids.size(); i++) {
            params.addValue("pid" + i, ids.get(i));
        }
        Map<Long, String> result = new LinkedHashMap<>();
        jdbc.query("SELECT id, content FROM kb_segment WHERE id IN (" + names(ids.size(), "pid") + ")",
                params, (RowCallbackHandler) rs -> result.put(rs.getLong("id"), rs.getString("content")));
        return result;
    }

    /** 命中计数：调参时靠它看出哪些块真的在被用。属于观测数据，不值得为它失败而牺牲回答。 */
    public int touchHits(List<Long> segmentIds) {
        List<Long> ids = segmentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (int i = 0; i < ids.size(); i++) {
            params.addValue("sid" + i, ids.get(i));
        }
        return jdbc.update("UPDATE kb_segment SET hit_count = hit_count + 1 WHERE id IN ("
                + names(ids.size(), "sid") + ")", params);
    }

    private static void fusion(Sql sql, RetrievalQuery query, boolean vector, boolean keyword) {
        String rrfK = String.valueOf(Math.max(1, query.rrfK()));
        if (vector && keyword) {
            sql.append(", fused AS (SELECT COALESCE(v.id, k.id) AS id, v.rank AS vec_rank, k.rank AS kw_rank,")
                    .append(" (CASE WHEN v.id IS NULL THEN 0.0 ELSE 1.0 / (" + rrfK + " + v.rank) END")
                    .append(" + CASE WHEN k.id IS NULL THEN 0.0 ELSE 1.0 / (" + rrfK + " + k.rank) END) AS rrf")
                    .append(" FROM vec v FULL OUTER JOIN kw k ON k.id = v.id)");
            return;
        }
        String source = vector ? "vec" : "kw";
        String ownRank = vector ? "rank AS vec_rank" : "rank AS kw_rank";
        String missingRank = vector ? "CAST(NULL AS integer) AS kw_rank" : "CAST(NULL AS integer) AS vec_rank";
        sql.append(", fused AS (SELECT id, ").appendRaw(ownRank).append(", ").append(missingRank)
                .append(", 1.0 / (" + rrfK + " + rank) AS rrf FROM ").appendRaw(source).append(")");
    }

    private static String names(int count, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i == 0 ? "" : ", ").append(':').append(prefix).append(i);
        }
        return sb.toString();
    }

    private static final RowMapper<SearchHit> HIT_MAPPER = (rs, rowNum) -> new SearchHit(
            rs.getLong("id"),
            rs.getLong("doc_id"),
            rs.getString("doc_name"),
            rs.getInt("chunk_no"),
            rs.getInt("doc_version"),
            rs.getString("content"),
            rs.getString("heading_path"),
            (Integer) rs.getObject("page_start"),
            (Integer) rs.getObject("page_end"),
            (Integer) rs.getObject("char_start"),
            (Integer) rs.getObject("char_end"),
            (Long) rs.getObject("parent_id"),
            (Integer) rs.getObject("vec_rank"),
            (Integer) rs.getObject("kw_rank"),
            rs.getDouble("rrf"),
            (Double) rs.getObject("similarity"));

    /** SQL 片段与命名参数一起构造，杜绝「加了占位符忘了加值」。 */
    private static final class Sql {

        private final StringBuilder text = new StringBuilder(1024);
        private final MapSqlParameterSource params = new MapSqlParameterSource();

        Sql append(String fragment) {
            text.append(fragment);
            return this;
        }

        /** 仅限类内常量（列名、CTE 名），绝不拼接外部输入。 */
        Sql appendRaw(String literalSql) {
            text.append(literalSql);
            return this;
        }

        Sql param(String name, Object value) {
            params.addValue(name, value);
            return this;
        }

        /** 两条召回路共用同一段范围过滤，参数名相同，重复 put 是幂等的。 */
        void scope(RetrievalQuery query) {
            text.append("(s.kb_id IN (").append(names(query.kbIds().size(), "kb")).append(")");
            for (int i = 0; i < query.kbIds().size(); i++) {
                params.addValue("kb" + i, query.kbIds().get(i));
            }
            List<Long> docIds = query.docIds();
            if (docIds != null && !docIds.isEmpty()) {
                text.append(" AND s.doc_id IN (").append(names(docIds.size(), "doc")).append(")");
                for (int i = 0; i < docIds.size(); i++) {
                    params.addValue("doc" + i, docIds.get(i));
                }
            }
            text.append(") AND s.available AND d.available AND d.deleted_at IS NULL");
        }

        String text() {
            return text.toString();
        }

        MapSqlParameterSource params() {
            return params;
        }
    }
}
