package com.rag.knowledge.db;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 反馈写入的字符串级契约：这段 SQL 连库才会暴露的问题恰恰是三类——占位符与绑定值对不上、
 * 忘了 `role = 'assistant'` 守卫（于是能给用户消息打分）、以及用 `::` 简写把命名参数解析器带崩。
 * RetrievalIT / SchemaMigrationIT 需要 Docker，这里不连库也能把这些钉死。
 */
class ChatFeedbackSqlTest {

    private static final Pattern PLACEHOLDER = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");

    @Test
    void bindsExactlyThePlaceholdersItEmits() {
        Call call = update(UUID.randomUUID(), 1, "引用错了段落", null);
        assertThat(call.bound()).containsExactlyInAnyOrderElementsOf(placeholders(call.sql()));
    }

    @Test
    void onlyRoundsCanBeRated() {
        assertThat(update(UUID.randomUUID(), -1, null, null).sql()).contains("role = 'assistant'");
    }

    /** 只点 👍 不该把之前写的反馈抹掉，所以「不改」必须由 COALESCE 表达，而不是在 Java 里拼分支。 */
    @Test
    void keepsFieldsThatWereNotSent() {
        String sql = update(UUID.randomUUID(), 1, null, null).sql();
        assertThat(sql).contains("COALESCE(CAST(:rating AS smallint), rating)");
        assertThat(sql).contains("COALESCE(CAST(:feedbackText AS text), feedback_text)");
        assertThat(sql).contains("COALESCE(CAST(:annotatedAnswer AS text), annotated_answer)");
    }

    @Test
    void clearsOnlyWhatIsSentAsAnEmptyString() {
        Call call = update(UUID.randomUUID(), 0, "", "");
        assertThat(call.sql()).contains("COALESCE");
        assertThat(call.bound()).contains("feedbackText", "annotatedAnswer");
    }

    @Test
    void neverUsesPostgresShorthandCastOnNamedParameters() {
        String sql = update(UUID.randomUUID(), 0, null, null).sql();
        assertThat(sql).doesNotContain("::smallint").doesNotContain("::text");
    }

    /** 读历史时漏加列不会编译报错，只会在运行期抛「列不存在」，所以写入列与读取列要一起钉住。 */
    @Test
    void readsBackTheColumnsItWrites() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        new ChatRepository(jdbc).listMessages(UUID.randomUUID(), 20);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertThat(sql.getValue()).contains("rating", "feedback_text", "annotated_answer");
    }

    /** 一条 UPDATE 的完整现场：SQL 文本加上真正绑定进去的参数名。 */
    private record Call(String sql, Set<String> bound) {
    }

    private Call update(UUID messageId, Integer rating, String feedback, String annotated) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        new ChatRepository(jdbc).setFeedback(messageId, rating, feedback, annotated);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        return new Call(sql.getValue(), new LinkedHashSet<>(params.getValue().getValues().keySet()));
    }

    private static Set<String> placeholders(String sql) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(sql);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
