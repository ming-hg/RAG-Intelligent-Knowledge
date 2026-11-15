package com.rag.knowledge.db;

import com.rag.knowledge.app.rag.KeywordEngine;
import com.rag.knowledge.app.rag.RetrievalMode;
import com.rag.knowledge.app.rag.RetrievalQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 检索 SQL 的字符串级契约测试：Docker 不可用时 RetrievalIT 会整批跳过，而这一段最容易坏的地方恰恰是纯字符串问题
 * ——加了占位符忘了绑值、阈值列选错一路、把范围过滤挪出召回路。这些都不需要真的连库就能钉死。
 * <p>
 * 断言刻意只盯「结构不变量」而不是整条 SQL 全文：改写 SQL 时不必同步改测试，但违反约束会立刻失败。
 */
class RetrievalSqlTest {

    private static final Pattern PLACEHOLDER = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");

    @Test
    void everyBranchBindsExactlyThePlaceholdersItEmits() {
        for (RetrievalMode mode : RetrievalMode.values()) {
            for (KeywordEngine engine : KeywordEngine.values()) {
                for (boolean withDocs : List.of(true, false)) {
                    RetrievalQuery query = query(mode, engine, withDocs);
                    if (!query.usable()) {
                        // 两路都不可用时服务层根本不会下发（见 HybridSearchService），这里不该拼出一条半成品 SQL
                        continue;
                    }
                    Set<String> emitted = placeholders(capture(query));
                    assertThat(emitted).as("%s + %s 至少要有分数阈值和截断".formatted(mode, engine))
                            .contains("threshold", "topK");
                    assertThat(boundNames(mode, engine, withDocs)).as("%s + %s 的绑定集合".formatted(mode, engine))
                            .isEqualTo(emitted);
                }
            }
        }
    }

    /** 范围谓词一旦挪进公共 CTE，HNSW 对向量路不可见，整条语句退化成全表精算——而且照样返回「对的」结果。 */
    @Test
    void scopeFilterStaysInsideEachRecallPath() {
        String sql = capture(query(RetrievalMode.HYBRID, KeywordEngine.TRIGRAM, true));

        assertThat(sql).doesNotContain("WITH scope").doesNotContain("scope AS (");
        assertThat(countOf(sql, "d.deleted_at IS NULL")).isEqualTo(2);
        // 向量路自己那一段：范围过滤必须在 ORDER BY dist 之前，LIMIT 才能走索引而不是精算后截断
        String vectorPath = sql.substring(sql.indexOf("FROM (SELECT s.id"), sql.indexOf("ORDER BY dist LIMIT"));
        assertThat(vectorPath).contains("s.available").contains("s.doc_id IN").contains("d.deleted_at IS NULL");
        assertThat(vectorPath).doesNotContain(":threshold");
    }

    @Test
    void thresholdColumnFollowsTheModeThatActuallyRan() {
        assertThat(capture(query(RetrievalMode.VECTOR, KeywordEngine.CHINESE_TSVECTOR, false)))
                .contains("v.similarity >= :threshold")
                .contains("ORDER BY v.similarity DESC NULLS LAST");

        assertThat(capture(query(RetrievalMode.HYBRID, KeywordEngine.CHINESE_TSVECTOR, false)))
                .contains("f.rrf >= :threshold");

        String keywordOnly = capture(query(RetrievalMode.KEYWORD, KeywordEngine.TRIGRAM, false));
        assertThat(keywordOnly).contains("f.rrf >= :threshold")
                // 关键词模式下连向量子查询都不该出现，否则白烧一次 pgvector 精算
                .doesNotContain("WITH vec").doesNotContain("<=>");
    }

    @Test
    void keywordFallbackKeepsItsOwnScoringOperator() {
        String tsvector = capture(query(RetrievalMode.KEYWORD, KeywordEngine.CHINESE_TSVECTOR, false));
        assertThat(tsvector).contains("@@ plainto_tsquery('chinese', :keywordText)")
                .doesNotContain("word_similarity");

        String trigram = capture(query(RetrievalMode.KEYWORD, KeywordEngine.TRIGRAM, false));
        // RRF 只给名次，绝对分下限是唯一能砍掉关键词长尾的东西
        assertThat(trigram).contains("word_similarity(:keywordText, s.content) >= :trigramFloor")
                .doesNotContain("plainto_tsquery")
                // 也不用 % 操作符：它按会话级 similarity_threshold 过滤，长块对短查询会整路空掉
                .doesNotContain(" s.content % ");
    }

    /** Spring 把 `::vector` 当成参数名解析；命名参数 SQL 里只能出现 CAST(x AS type)。 */
    @Test
    void neverUsesPostgresShorthandCastOnNamedParameters() {
        assertThat(capture(query(RetrievalMode.HYBRID, KeywordEngine.TRIGRAM, true)))
                .contains("CAST(:queryVec AS vector)");
        // 纯关键词路没有向量可比，用显式 NULL 占住 similarity 列，好让 HIT_MAPPER 只有一份
        assertThat(capture(query(RetrievalMode.KEYWORD, KeywordEngine.TRIGRAM, true)))
                .contains("CAST(NULL AS double precision) AS similarity");

        for (RetrievalMode mode : RetrievalMode.values()) {
            for (KeywordEngine engine : KeywordEngine.values()) {
                String sql = capture(query(mode, engine, true));
                assertThat(sql).as("%s + %s".formatted(mode, engine)).doesNotContain("::");
            }
        }
    }

    @Test
    void rrfSmoothingComesFromTheQueryAndBothPathsContributeInHybrid() {
        String sql = capture(new RetrievalQuery(List.of(1L), List.of(), "问题", "[0.1,0.2]",
                RetrievalMode.HYBRID, KeywordEngine.TRIGRAM, 40, 6, 60, 0.012));
        assertThat(sql).contains("FULL OUTER JOIN kw k ON k.id = v.id")
                .contains("1.0 / (60 + v.rank)").contains("1.0 / (60 + k.rank)");
        // 单路命中不能被当成 0 分丢掉，所以缺路一侧补 0.0 而不是整行消失
        assertThat(sql).contains("CASE WHEN v.id IS NULL THEN 0.0");

        // rrfK<=0 会让第一名除以自身，等于把名次放大成无穷小；配置写错时兜到 1
        assertThat(capture(new RetrievalQuery(List.of(1L), List.of(), "问题", "[0.1,0.2]",
                RetrievalMode.HYBRID, KeywordEngine.TRIGRAM, 40, 6, 0, 0.012))).contains("1.0 / (1 + v.rank)");
    }

    @Test
    void documentScopeIsOptional() {
        assertThat(capture(query(RetrievalMode.VECTOR, KeywordEngine.NONE, false))).doesNotContain("s.doc_id IN");
        assertThat(capture(query(RetrievalMode.VECTOR, KeywordEngine.NONE, true))).contains("s.doc_id IN (:doc0)");
    }

    /** 父块回捞和命中计数共用一套批量 IN 拼装：空集合不能拼出 `IN ()`，那是语法错误。 */
    @Test
    void batchHelpersSkipEmptyIdLists() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        RetrievalRepository repository = new RetrievalRepository(jdbc);

        assertThat(repository.parentContents(List.of())).isEmpty();
        assertThat(repository.touchHits(Arrays.asList(null, null))).isZero();
        verifyNoInteractions(jdbc);
    }

    private static String capture(RetrievalQuery query) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        new RetrievalRepository(jdbc).search(query);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        return sql.getValue();
    }

    private static Set<String> placeholders(String sql) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(sql);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Set<String> boundNames(RetrievalMode mode, KeywordEngine engine, boolean withDocs) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        when(jdbc.query(anyString(), params.capture(), any(RowMapper.class))).thenReturn(List.of());
        new RetrievalRepository(jdbc).search(query(mode, engine, withDocs));
        return new LinkedHashSet<>(params.getValue().getValues().keySet());
    }

    private static RetrievalQuery query(RetrievalMode mode, KeywordEngine engine, boolean withDocs) {
        return new RetrievalQuery(List.of(1L, 2L), withDocs ? List.of(11L) : Collections.<Long>emptyList(), "怎么分块",
                engine == KeywordEngine.NONE && mode == RetrievalMode.KEYWORD ? null : "[0.1,0.2]",
                mode, engine, 40, 6, 60, 0.012);
    }

    private static int countOf(String sql, String needle) {
        int count = 0;
        for (int i = sql.indexOf(needle); i >= 0; i = sql.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
