package com.rag.knowledge.db;

import com.rag.knowledge.domain.ArchivedSegment;
import com.rag.knowledge.domain.DocStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 归档那几条语句的字符串级契约。真库验证在 `SchemaMigrationIT` / `IngestPipelineIT` 里，但虚拟化没开时
 * 也要有人钉住这几件事：位置参数的顺序、`?::vector` / `?::timestamptz` 两处 cast、SELECT 的列别名与
 * RowMapper 读的列名是否一个个对得上——这些错都是运行期 SQLException，而且只有导入端才会走到。
 */
class ArchiveSqlTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SegmentRepository segments = new SegmentRepository(jdbc);
    private final DocumentRepository documents = new DocumentRepository(jdbc);

    @Test
    void insertArchivedWritesFilePathAndCastsTheOverrideAsJsonb() {
        stubInsertedId();
        documents.insertArchived(1L, "手册.md", "sha-1", "text/markdown", 2048L, "正文", 3, 2, 7,
                DocStatus.READY, "{\"childChars\":600}", "kb1/sha-1.md");

        String sql = capturedDocumentInsert();
        assertThat(sql).contains("file_path").contains("page_count").contains("char_count");
        // jsonb 与 timestamptz 都必须显式 cast：PG 不会把 text 参数自己当成 jsonb
        assertThat(sql).contains("chunk_override, parsed_at, indexed_at").contains("?::jsonb");
        assertThat(sql).contains("CASE WHEN ? THEN now() ELSE NULL END").contains("RETURNING id");
    }

    /** 顺序错了不会报错，只会把正文写进 mime、把版本写进状态——位置参数唯一的软肋。 */
    @Test
    void insertArchivedArgumentsFollowThePlaceholderOrder() {
        stubInsertedId();
        documents.insertArchived(1L, "手册.md", "sha-1", "text/markdown", 2048L, "正文", 3, 2, 7,
                DocStatus.PENDING, null, null);

        // 字数由正文算出来，不是传进来的：包里的 charCount 与实际正文长度不一致时以正文为准
        verify(jdbc).queryForObject(anyString(), eq(Long.class), aryEq(new Object[] {
                1L, "手册.md", null, 2048L, "text/markdown", "sha-1", "正文", 3, 2, "PENDING", 0, 2, 7, null, false }));
    }

    @Test
    void readyArchiveStampsIndexedAtWhilePendingDoesNot() {
        stubInsertedId();
        documents.insertArchived(1L, "a.md", "sha", null, null, "正文", null, 1, 1, DocStatus.READY, null, null);

        verify(jdbc).queryForObject(anyString(), eq(Long.class), aryEq(new Object[] {
                1L, "a.md", null, null, null, "sha", "正文", null, 2, "READY", 100, 1, 1, null, true }));
    }

    @Test
    void archivedReadKeepsDisabledBlocksAndNullPageNumbers() {
        segments.listArchived(7L, 2);

        String sql = capturedSegmentQuery();
        // available=false 是用户在界面上手工关掉检索的，导出必须原样带走，否则导入后它悄悄回到检索结果里
        assertThat(sql).doesNotContain("AND available").doesNotContain("available = true");
        assertThat(sql).contains("LEFT JOIN kb_segment p ON p.id = s.parent_id");
        assertThat(sql).contains("ORDER BY s.chunk_no");
        // 时间戳以文本带出包，导入端再按 timestamptz 写回：中间隔着一个 zip，没有连接行为可以依赖
        assertThat(sql).contains("s.edited_at::text AS edited_at");
    }

    @Test
    void archivedWritePassesVectorAndTimestampThroughTheirCastsInOrder() {
        when(jdbc.batchUpdate(anyString(), any(List.class))).thenReturn(new int[] { 1 });

        assertThat(segments.insertArchivedGeneration(7L, 1L, 2, "bge-base-zh-v1.5", List.of(
                new ArchivedSegment(3, 0, "子块正文", "标题 > 节", 1, 2, 10, 200, false, "READY",
                        "2026-11-01 09:00:00+00", new float[] {0.5f, -0.25f})))).isEqualTo(1);

        String sql = capturedBatchInsert();
        assertThat(sql).contains("?::vector").contains("?::timestamptz");
        assertThat(sql).contains("emb_status, available, edited_at");

        ArgumentCaptor<List<Object[]>> rows = batchArgs();
        verify(jdbc).batchUpdate(anyString(), rows.capture());
        assertThat(rows.getValue()).singleElement().as("批量插入只有一行，参数就是这一行的十二个值")
                .satisfies(args -> assertThat(args).containsExactly(7L, 1L, 3, 2, "子块正文", "标题 > 节",
                        1, 2, 10, 200, "[0.5,-0.25]", "bge-base-zh-v1.5", "READY", false,
                        "2026-11-01 09:00:00+00"));
    }

    /** 空代不该发出 SQL：batchUpdate 收到空列表在不同驱动版本上行为不一致。 */
    @Test
    void anEmptyGenerationSkipsTheBatchUpdateEntirely() {
        assertThat(segments.insertArchivedGeneration(7L, 1L, 2, "bge", List.of())).isZero();

        verify(jdbc, never()).batchUpdate(anyString(), any(List.class));
    }

    /** 列别名与 RowMapper 各改一半，是只有真跑一次才会炸的那类错误，所以这里先把两边钉在一起。 */
    @Test
    void archivedMapperReadsExactlyTheColumnsTheSelectProjects() {
        segments.listArchived(7L, 2);
        String sql = capturedSegmentQuery();

        for (String column : List.of("chunk_no", "parent_chunk_no", "content", "heading_path", "page_start",
                "page_end", "char_start", "char_end", "available", "emb_status", "edited_at", "embedding")) {
            assertThat(sql).as("SELECT 里必须有 %s，否则 ARCHIVED_MAPPER 会在运行期报列不存在", column).contains(column);
        }
    }

    @Test
    void archivedMapperTreatsMissingValuesAsMissingNotAsZero() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        segments.listArchived(7L, 2);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("chunk_no")).thenReturn(5);
        when(rs.getString("content")).thenReturn("正文");
        when(rs.getString("emb_status")).thenReturn("READY");
        when(rs.getBoolean("available")).thenReturn(true);

        ArchivedSegment row = capturedSegmentMapper().getValue().mapRow(rs, 0);

        // 页码没解析出来和页码是第 0 页在包里必须是两件事：折成 0 会让引用指向不存在的页
        assertThat(row.pageStart()).isNull();
        assertThat(row.parentChunkNo()).isNull();
        assertThat(row.embedding()).isNull();
        assertThat(row.editedAt()).isNull();
        assertThat(row.chunkNo()).isEqualTo(5);
        assertThat(row.content()).isEqualTo("正文");
    }

    @Test
    void vectorLiteralParsesBackIntoTheArrayThatWroteIt() {
        assertThat(SegmentRepository.parseVectorLiteral("[0.5,-0.25,3]")).containsExactly(0.5f, -0.25f, 3f);
        assertThat(SegmentRepository.parseVectorLiteral("[1.5, 2.5]")).containsExactly(1.5f, 2.5f);
        assertThat(SegmentRepository.parseVectorLiteral(null)).isNull();
        assertThat(SegmentRepository.parseVectorLiteral("  ")).isNull();
        assertThat(SegmentRepository.parseVectorLiteral("[]")).isEmpty();
        assertThatThrownBy(() -> SegmentRepository.parseVectorLiteral("[0.5,oops]"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void emptyArchiveBodyStillMeansNoVectorNotZeroVector() {
        assertThat(SegmentRepository.toVectorLiteral(null)).isNull();
        assertThat(SegmentRepository.parseVectorLiteral(SegmentRepository.toVectorLiteral(new float[] {1f})))
                .containsExactly(1f);
    }

    /** RETURNING id 拿不到值时仓储会抛 IllegalState，那是「插入失败」的分支，不是本测试要钉的东西。 */
    private void stubInsertedId() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
    }

    private String capturedDocumentInsert() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        return sql.getValue();
    }

    private String capturedSegmentQuery() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        return sql.getValue();
    }

    private String capturedBatchInsert() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).batchUpdate(sql.capture(), any(List.class));
        return sql.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<Object[]>> batchArgs() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<RowMapper<ArchivedSegment>> capturedSegmentMapper() {
        ArgumentCaptor<RowMapper<ArchivedSegment>> mapper = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbc).query(anyString(), mapper.capture(), any(Object[].class));
        return mapper;
    }
}
