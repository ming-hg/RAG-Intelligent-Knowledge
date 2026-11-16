package com.rag.knowledge.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * knowledge_base 仓储。
 * <p>
 * 维度是 schema 级约束（{@code kb_segment.embedding vector(dim)}），所以库里的 embedding_dim
 * 与配置不一致时必须拒绝写入，而不是默默产出无法检索的向量。
 */
@Repository
public class KnowledgeBaseRepository {

    public record KnowledgeBase(long id, String name, String embeddingModel, int embeddingDim, int indexVersion) {
    }

    private static final RowMapper<KnowledgeBase> MAPPER = (rs, rowNum) -> new KnowledgeBase(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("embedding_model"),
            rs.getInt("embedding_dim"),
            rs.getInt("index_version"));

    private final JdbcTemplate jdbc;

    public KnowledgeBaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 取第一个可用库，没有就建默认库。启动时调用一次，之后所有入库都挂在这个库上。 */
    public KnowledgeBase ensureDefault(String name, String embeddingModel, int dim) {
        List<KnowledgeBase> existing = jdbc.query("""
                SELECT id, name, embedding_model, embedding_dim, index_version
                FROM knowledge_base WHERE deleted_at IS NULL ORDER BY id LIMIT 1
                """, MAPPER);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO knowledge_base (name, description, embedding_model, embedding_dim)
                VALUES (?, '本地私有化知识库', ?, ?)
                RETURNING id, name, embedding_model, embedding_dim, index_version
                """, MAPPER, name, embeddingModel, dim);
    }

    public KnowledgeBase require(long id) {
        return jdbc.queryForObject("""
                SELECT id, name, embedding_model, embedding_dim, index_version
                FROM knowledge_base WHERE id = ? AND deleted_at IS NULL
                """, MAPPER, id);
    }

    public List<KnowledgeBase> list() {
        return jdbc.query("""
                SELECT id, name, embedding_model, embedding_dim, index_version
                FROM knowledge_base WHERE deleted_at IS NULL ORDER BY id
                """, MAPPER);
    }

    public int bumpIndexVersion(long kbId) {
        return jdbc.queryForObject("UPDATE knowledge_base SET index_version = index_version + 1,"
                + " updated_at = now() WHERE id = ? RETURNING index_version", Integer.class, kbId);
    }
}
