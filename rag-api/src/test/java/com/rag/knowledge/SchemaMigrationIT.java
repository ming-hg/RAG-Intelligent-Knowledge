package com.rag.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Random;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在真实 pgvector 上校验 V1/V2/V3 迁移产物与检索谓词能力。
 * 该用例故意用不含 zhparser 的官方镜像，覆盖「中文分词不可用 → 退化 pg_trgm」这条分支。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "rag.embedding.dim=768")
class SchemaMigrationIT {

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("rag_knowledge")
            .withUsername("rag")
            .withPassword("rag_local_dev");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationsCreateExpectedTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' ORDER BY table_name
                """, String.class);
        assertTrue(tables.containsAll(List.of("knowledge_base", "kb_document", "kb_segment", "kb_task",
                "chat_session", "chat_message", "eval_case", "eval_run", "eval_result")), "实际表: " + tables);
    }

    @Test
    void hnswIndexExistsOnEmbeddingColumn() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'kb_segment' AND indexname = 'idx_segment_embedding_hnsw'
                """, Integer.class);
        assertEquals(1, count, "HNSW 索引必须由 migration 手工建立（LangChain4j 只会建 IVFFlat）");
    }

    @Test
    void zhparserAbsentFallsBackToTrgmWithoutBreakingMigration() {
        Integer chineseCfg = jdbc.queryForObject("SELECT count(*) FROM pg_ts_config WHERE cfgname = 'chinese'", Integer.class);
        assertEquals(0, chineseCfg, "官方 pgvector 镜像不含 zhparser");

        Integer trgm = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'kb_segment' AND indexname = 'idx_segment_content_trgm'
                """, Integer.class);
        assertEquals(1, trgm, "pg_trgm 兜底索引必须存在");
    }

    @Test
    void vectorAndKeywordSearchBothWorkForChineseContent() throws Exception {
        Long kbId = jdbc.queryForObject("""
                INSERT INTO knowledge_base (name, embedding_model, embedding_dim)
                VALUES ('it-kb', 'bge-base-zh-v1.5', 768) RETURNING id
                """, Long.class);
        Long docId = jdbc.queryForObject("""
                INSERT INTO kb_document (kb_id, name, sha256, status, char_count)
                VALUES (?, '重构方案.md', 'it-sha-1', 'READY', 40) RETURNING id
                """, Long.class, kbId);

        float[] vector = randomUnitVector(768, 42L);
        try (Connection conn = jdbc.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO kb_segment (doc_id, kb_id, chunk_no, content, embedding, embedding_model, emb_status)
                     VALUES (?, ?, 0, '本地私有化部署的知识库问答系统重构方案', ?::vector, 'bge-base-zh-v1.5', 'READY')
                     """)) {
            ps.setLong(1, docId);
            ps.setLong(2, kbId);
            ps.setString(3, toVectorLiteral(vector));
            assertEquals(1, ps.executeUpdate());
        }

        // 向量检索：必须走 ORDER BY 操作符形式，否则静默退化为顺序扫描
        Long hitId = jdbc.queryForObject("""
                SELECT id FROM kb_segment
                WHERE kb_id = ? AND available AND embedding IS NOT NULL
                ORDER BY embedding <=> ?::vector LIMIT 1
                """, Long.class, kbId, toVectorLiteral(randomUnitVector(768, 7L)));
        assertNotNull(hitId);

        // 关键词检索兜底路径：与 RetrievalRepository 的退化分支用同一个谓词。
        // 这里刻意不用 % 操作符——它按 similarity（整句重合比例）过滤，短查询对长正文必然低于阈值，
        // 真跑一次就发现整路空召回；word_similarity 才表达「查询是正文的一段」，也是线上实际走的那条。
        Integer byTrgm = jdbc.queryForObject(
                "SELECT count(*) FROM kb_segment WHERE word_similarity('知识库问答', content) >= 0.25",
                Integer.class);
        assertTrue(byTrgm != null && byTrgm > 0, "pg_trgm 的 word_similarity 兜底应命中中文内容");
    }

    private static float[] randomUnitVector(int dim, long seed) {
        Random random = new Random(seed);
        float[] v = new float[dim];
        double norm = 0;
        for (int i = 0; i < dim; i++) {
            v[i] = random.nextFloat() * 2 - 1;
            norm += v[i] * v[i];
        }
        double scale = 1 / Math.sqrt(norm);
        for (int i = 0; i < dim; i++) {
            v[i] = (float) (v[i] * scale);
        }
        return v;
    }

    private static String toVectorLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float f : vector) {
            joiner.add(String.format("%.6f", f));
        }
        return joiner.toString();
    }
}
