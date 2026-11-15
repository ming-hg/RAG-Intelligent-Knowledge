package com.rag.knowledge.db;

import com.rag.knowledge.domain.SegmentView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单块编辑那几条语句的字符串级契约。连库的验证在 `SchemaMigrationIT`/`IngestPipelineIT` 里，但 Docker 没起来时
 * 也要有人钉住这几件事：改正文必须同时把向量作废、父块不能被带成「等待向量化」、doc_id 必须在 WHERE 里、
 * 读视图的列名必须和 RowMapper 一个个对上——对不上是运行期 SQLException，只有真跑一次才发现。
 */
class SegmentEditSqlTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SegmentRepository segments = new SegmentRepository(jdbc);

    @Test
    void editingContentInvalidatesTheVectorButKeepsParentsUnvectorised() {
        segments.editContent(7L, 101L, "改过的正文");

        String sql = capturedUpdate();
        assertThat(sql).contains("embedding = NULL").contains("embedding_model = NULL")
                .contains("edited_at = now()");
        // 父块的 SKIPPED 是「这块本来就没有向量」，改成 PENDING 会让它出现在补算队列里
        assertThat(sql).contains("CASE WHEN emb_status = 'SKIPPED' THEN 'SKIPPED' ELSE 'PENDING' END");
        assertThat(sql).contains("WHERE id = ? AND doc_id = ?");
    }

    @Test
    void writingOneVectorGoesThroughTheVectorCastAndMarksReady() {
        segments.setEmbedding(7L, 101L, "[0.5,0.1]", "bge-base-zh-v1.5");

        String sql = capturedUpdate();
        assertThat(sql).contains("embedding = ?::vector").contains("emb_status = 'READY'")
                .contains("WHERE id = ? AND doc_id = ?");
    }

    /** 参数顺序错了不会报错，只会把内容写进别人的块——位置参数唯一的软肋，所以按顺序钉。 */
    @Test
    void editContentArgumentsFollowThePlaceholderOrder() {
        segments.editContent(7L, 101L, "改过的正文");

        verify(jdbc).update(anyString(), aryEq(new Object[] { "改过的正文", 101L, 7L }));
    }

    @Test
    void setEmbeddingArgumentsFollowThePlaceholderOrder() {
        segments.setEmbedding(7L, 101L, "[0.5]", "bge-base-zh-v1.5");

        verify(jdbc).update(anyString(), aryEq(new Object[] { "[0.5]", "bge-base-zh-v1.5", 101L, 7L }));
    }

    @Test
    void theSingleBlockReadAlsoFiltersByDocument() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        segments.findView(7L, 101L);

        String sql = capturedQuery();
        assertThat(sql).contains("WHERE s.id = ? AND s.doc_id = ?");
        assertThat(sql).contains("s.edited_at");
    }

    /** RowMapper 读的列名与 SELECT 的别名各改一半，是只有跑一次才会炸的那类错误。 */
    @Test
    void theViewMapperReadsExactlyTheColumnsTheSelectProjects() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        segments.listForDocument(7L, 3, false, 10, 0);
        String sql = capturedQuery();

        ArgumentCaptor<RowMapper<SegmentView>> mapper = rowMapper();
        verify(jdbc).query(anyString(), mapper.capture(), any(Object[].class));
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("edited_at")).thenReturn(Timestamp.valueOf("2026-11-15 21:30:00"));
        when(rs.getString("emb_status")).thenReturn("PENDING");

        SegmentView view = mapper.getValue().mapRow(rs, 0);

        assertThat(view.edited()).isTrue();
        assertThat(view.embStatus()).isEqualTo("PENDING");
        assertThat(view.parentBlock()).isFalse();
        for (String column : List.of("id", "chunk_no", "parent_chunk_no", "content", "heading_path",
                "page_start", "page_end", "char_start", "char_end", "available", "emb_status", "edited_at",
                "hit_count")) {
            assertThat(sql).as("SELECT 里必须有 %s，否则下面这句 mapRow 会在运行期报列不存在", column)
                    .contains(column);
        }
    }

    private String capturedUpdate() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        return sql.getValue();
    }

    private String capturedQuery() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        return sql.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<RowMapper<SegmentView>> rowMapper() {
        return ArgumentCaptor.forClass(RowMapper.class);
    }
}
