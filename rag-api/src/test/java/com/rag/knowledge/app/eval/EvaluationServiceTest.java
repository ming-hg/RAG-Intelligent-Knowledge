package com.rag.knowledge.app.eval;

import com.rag.knowledge.app.eval.EvaluationService.CaseInput;
import com.rag.knowledge.app.eval.EvaluationService.PerCase;
import com.rag.knowledge.app.eval.EvaluationService.RunDetail;
import com.rag.knowledge.app.rag.ContextPlanner;
import com.rag.knowledge.app.rag.EvidencePlanner;
import com.rag.knowledge.app.rag.HybridSearchService;
import com.rag.knowledge.app.rag.KeywordEngine;
import com.rag.knowledge.app.rag.RetrievalMode;
import com.rag.knowledge.app.rag.SearchHit;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.EvaluationRepository;
import com.rag.knowledge.db.EvaluationRepository.Case;
import com.rag.knowledge.db.EvaluationRepository.Result;
import com.rag.knowledge.db.EvaluationRepository.Run;
import com.rag.knowledge.db.KnowledgeBaseRepository;
import com.rag.knowledge.db.KnowledgeBaseRepository.KnowledgeBase;
import com.rag.knowledge.db.RetrievalRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跑分编排：用例的启用规则、参数快照、单条失败不毁整轮，以及分数到底写回了什么。
 * <p>
 * 拒答判据这里用真的 {@link EvidencePlanner}，不用 mock：M5e 的全部价值在于「评测说的拒答」和
 * 「问答链路做的拒答」是同一件事，把这段 mock 掉等于把这条契约藏起来。
 */
class EvaluationServiceTest {

    private final EvaluationRepository evals = mock(EvaluationRepository.class);
    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final HybridSearchService search = mock(HybridSearchService.class);
    private final RetrievalRepository retrieval = mock(RetrievalRepository.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final RagProperties props = new RagProperties(
            new RagProperties.LmStudio("http://localhost:1234/v1", "lm-studio", "chat", "bge-base-zh",
                    0.3, 0.8, null, Duration.ofSeconds(5), 1),
            new RagProperties.Storage("target/rag-test"),
            new RagProperties.Embedding(768, 16),
            RagProperties.Ingest.defaults(),
            new RagProperties.Retrieval("hybrid", 6, 40, 60, 0.45, 0.012, true,
                    new RagProperties.Budget(16384, 700, 900, 1.0, 4, 800)),
            RagProperties.Archive.defaults());
    private final EvaluationService service = new EvaluationService(evals, knowledgeBases, search,
            new EvidencePlanner(retrieval, new ContextPlanner(), props), props, json);

    /** 让 mock 仓储把写进去的东西存下来，detail() 再读回来——否则测的是「有没有调用」而不是「存了什么」。 */
    private final List<Result> written = new ArrayList<>();
    private String settingsJson;
    private String metricsJson;

    private void stubRunRow() {
        when(evals.startRun(anyString(), any())).thenAnswer(invocation -> {
            settingsJson = invocation.getArgument(0);
            return 7L;
        });
        doAnswer(invocation -> {
            written.add(invocation.getArgument(1));
            return null;
        }).when(evals).addResult(eq(7L), any(Result.class));
        doAnswer(invocation -> {
            metricsJson = invocation.getArgument(1);
            return null;
        }).when(evals).finishRun(eq(7L), anyString());
        when(evals.findRun(7L)).thenAnswer(invocation ->
                new Run(7L, "2026-11-16 10:00:00", "2026-11-16 10:00:01", settingsJson, metricsJson, "备注"));
        when(evals.listResults(7L)).thenAnswer(invocation -> written);
        when(search.engine()).thenReturn(KeywordEngine.CHINESE_TSVECTOR);
    }

    @Test
    void positiveCaseWithoutAMarkerIsCreatedDisabledInsteadOfSilentlyScoringZero() {
        when(knowledgeBases.require(1L)).thenReturn(new KnowledgeBase(1L, "默认库", "bge-base-zh", 768, 1));
        when(evals.addCase(anyLong(), anyString(), any(), any(), anyBoolean(), anyString(), any(), any(),
                anyBoolean())).thenReturn(9L);
        when(evals.findCase(9L)).thenReturn(aCase(9L, "怎么分块", null, true, false));

        Case created = service.addCase(new CaseInput(1L, "怎么分块", null, null, true, "手写"));

        assertThat(created.enabled()).isFalse();
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(evals).addCase(eq(1L), eq("怎么分块"), isNull(), isNull(), eq(true), eq("manual"), isNull(),
                note.capture(), eq(false));
        // 原因写进库里那一行：待标注的用例过几天再看，得知道它是「没标注」而不是「标了但检索不行」
        assertThat(note.getValue()).contains("手写").contains("补上 expect_marker");
    }

    @Test
    void negativeCaseNeedsNoMarkerAndIsUsableImmediately() {
        when(knowledgeBases.require(1L)).thenReturn(new KnowledgeBase(1L, "默认库", "bge-base-zh", 768, 1));
        when(evals.addCase(eq(1L), eq("食堂今天吃什么"), isNull(), isNull(), eq(false), eq("manual"), isNull(),
                any(), eq(true))).thenReturn(11L);
        when(evals.findCase(11L)).thenReturn(aCase(11L, "食堂今天吃什么", null, false, true));

        assertThat(service.addCase(new CaseInput(1L, "食堂今天吃什么", null, null, false, null)).enabled()).isTrue();
    }

    @Test
    void enablingAnUnlabelledPositiveIsRefusedWithTheReasonInTheMessage() {
        when(evals.findCase(9L)).thenReturn(aCase(9L, "怎么分块", null, true, false));

        assertThatThrownBy(() -> service.updateCase(9L, null, null, null, true))
                .isInstanceOfSatisfying(RagException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("expect_marker");
                });
        verify(evals, never()).updateCase(anyLong(), any(), any(), any(), any());
    }

    @Test
    void labellingThenEnablingTheSameCaseGoesThrough() {
        when(evals.findCase(9L)).thenReturn(aCase(9L, "怎么分块", null, true, false),
                aCase(9L, "怎么分块", "RRF-2-7", true, true));

        assertThat(service.updateCase(9L, "RRF-2-7", null, null, true).enabled()).isTrue();
        verify(evals).updateCase(9L, "RRF-2-7", null, null, true);
    }

    @Test
    void anEmptyEvalSetIsRejectedBeforeAnyRunRowExists() {
        when(evals.listCases(null, true)).thenReturn(List.of());

        assertThatThrownBy(() -> service.run(null, null, null, null))
                .isInstanceOfSatisfying(RagException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("评测集是空的");
                });
        verify(evals, never()).startRun(anyString(), any());
    }

    @Test
    void unknownKnowledgeBaseIsRejectedBeforeTheCaseRow() {
        when(knowledgeBases.require(404L)).thenThrow(new IllegalStateException("没有这行"));

        assertThatThrownBy(() -> service.addCase(new CaseInput(404L, "问题", "M", null, true, null)))
                .isInstanceOfSatisfying(RagException.class, ex -> assertThat(ex.code()).isEqualTo(404));
        verify(evals, never()).addCase(anyLong(), anyString(), any(), any(), anyBoolean(), anyString(), any(),
                any(), anyBoolean());
    }

    @Test
    void runSnapshotsTheSettingsThatActuallyTookEffect() {
        stubTwoCases();
        stubRunRow();
        stubBothPaths("编号 RRF-2-7 在这里", List.of());

        service.run(null, null, null, "调阈值前");

        Map<String, Object> snapshot = read(settingsJson);
        assertThat(snapshot)
                .containsEntry("mode", "HYBRID")
                .containsEntry("topK", 6)
                .containsEntry("rrfK", 60)
                .containsEntry("keywordEngine", "CHINESE_TSVECTOR")
                .containsEntry("embeddingModel", "bge-base-zh")
                .containsEntry("embeddingDim", 768)
                .containsEntry("nCtx", 16384)
                .containsEntry("topKOverride", null);
        // 混合与关键词路生效的是 RRF 量纲那个阈值，不是余弦的 0.45：抄错这一个数就能让检索常年 0 命中
        assertThat(snapshot.get("threshold")).isEqualTo(0.012);
        assertThat(snapshot.get("contextBudgetChars")).isEqualTo(14784);
    }

    @Test
    void perCaseFailureIsRecordedAndTheRestOfTheRunStillScores() {
        stubTwoCases();
        stubRunRow();
        stubSearch("怎么分块", outcome(List.of(hit("编号 RRF-2-7 在这里"))));
        when(search.search(eq("食堂吃什么"), anyList(), isNull(), isNull(), isNull()))
                .thenThrow(new IllegalStateException("embedding 服务没起来"));

        RunDetail detail = service.run(null, null, null, null);

        assertThat(written).hasSize(2);
        assertThat(written.get(0).correct()).isTrue();
        assertThat(written.get(1).reason()).as("失败原因得留在行里，不然报表只看到一个 0").contains("这条没跑成")
                .contains("embedding 服务没起来");
        assertThat(read(metricsJson))
                .as("坏掉的那条不该压低另一条的分数")
                .containsEntry("hitAtK", 1.0);
        assertThat(detail.run().metrics().cases()).isEqualTo(2);
    }

    @Test
    void aMissingDenominatorStaysNullInsteadOfReadingAsZero() {
        stubTwoCases();
        stubRunRow();
        // 正例召回了别的正文（排在 k 外），负例这次没有：拒答正确率应当缺席而不是 0
        stubSearch("怎么分块", outcome(List.of(hit("别的正文"))));
        when(evals.listCases(null, true)).thenReturn(List.of(aCase(1L, "怎么分块", "RRF-2-7", true, true)));
        when(evals.findCase(1L)).thenReturn(aCase(1L, "怎么分块", "RRF-2-7", true, true));

        service.run(null, null, null, null);

        Map<String, Object> metrics = read(metricsJson);
        assertThat(metrics).containsEntry("refusalAccuracy", null);
        assertThat(metrics).containsEntry("negative", 0);
        assertThat(metrics).containsEntry("rankedBeyondK", 1.0);
        assertThat(metrics).containsEntry("emptyRecallRate", 0.0);
    }

    @Test
    void aPositiveThatRecalledNothingIsReportedAsEmptyRecall() {
        when(evals.listCases(1L, true)).thenReturn(List.of(aCase(3L, "怎么分块", "RRF-2-7", true, true)));
        when(evals.findCase(3L)).thenReturn(aCase(3L, "怎么分块", "RRF-2-7", true, true));
        stubRunRow();
        stubSearch("怎么分块", outcome(List.of()));

        RunDetail detail = service.run(1L, "keyword", 3, null);

        assertThat(detail.cases()).hasSize(1);
        assertThat(detail.cases().get(0).reason()).contains("空召回");
        assertThat(detail.run().settings())
                .containsEntry("modeOverride", "keyword")
                .containsEntry("topKOverride", 3)
                .containsEntry("topK", 3)
                .containsEntry("mode", "KEYWORD");
        assertThat(read(metricsJson)).containsEntry("emptyRecallRate", 1.0);
    }

    /**
     * 「召回 4 块只装下 2 块、相关块排第 4」这条路径必须走到真的 {@link EvidencePlanner}：
     * 预算是 ContextPlanner 算的，mock 掉它就等于在测一个不存在的判据。
     * 这里 4 条命中里前两条就把 14784 字符的预算吃完（第三条起 room &lt; 120 直接 break），
     * 所以 unplaced=2、装下 2 块，而相关块在第 4 名——检索算命中，线上模型却读不到它。
     */
    @Test
    void aHitThatTheBudgetCouldNotPlaceIsReportedAsNotReadByTheModel() {
        when(evals.listCases(1L, true)).thenReturn(List.of(aCase(3L, "怎么分块", "RRF-2-7", true, true)));
        when(evals.findCase(3L)).thenReturn(aCase(3L, "怎么分块", "RRF-2-7", true, true));
        stubRunRow();
        String filler = "无关正文".repeat(2000);
        stubSearch("怎么分块", outcome(List.of(hit(filler), hit(filler), hit(filler), hit("里面有 RRF-2-7"))));

        RunDetail detail = service.run(1L, null, null, null);

        PerCase row = detail.cases().get(0);
        assertThat(row.firstRank()).as("检索层确实把它召回进了前 6").isEqualTo(4);
        assertThat(row.correct()).isTrue();
        assertThat(row.refused()).as("装下两块不是拒答").isFalse();
        assertThat(row.reason()).contains("排在第 4 名").contains("只装下 2 块").contains("模型没读到它");
        assertThat(read(metricsJson))
                .as("截断单独一项读数，不去污染 hit@k").containsEntry("truncatedCaseRate", 1.0)
                .containsEntry("hitAtK", 1.0);
    }

    /** embedding 服务没起来时检索不报错，而是当场退成关键词路：分数照出，但每一行都得写明这轮不可比。 */
    @Test
    void aDegradedRunSaysSoOnEveryRowBecauseOtherwiseTwoRoundsLookComparable() {
        when(evals.listCases(1L, true)).thenReturn(List.of(aCase(3L, "怎么分块", "RRF-2-7", true, true)));
        when(evals.findCase(3L)).thenReturn(aCase(3L, "怎么分块", "RRF-2-7", true, true));
        stubRunRow();
        stubSearch("怎么分块", new HybridSearchService.Outcome(RetrievalMode.HYBRID, RetrievalMode.KEYWORD,
                KeywordEngine.CHINESE_TSVECTOR, "embedding 服务没起来", List.of(hit("编号 RRF-2-7 在这里")), 12));

        RunDetail detail = service.run(1L, "hybrid", null, null);

        PerCase row = detail.cases().get(0);
        assertThat(row.correct()).as("判分本身不受降级影响，别把分数改成 0").isTrue();
        assertThat(row.reason()).contains("降级跑的").contains("实际只走 KEYWORD").contains("embedding 服务没起来");
    }

    @Test
    void detailRowsCarryWhatActuallyRankedAheadOfTheExpectedBlock() {
        when(evals.listCases(1L, true)).thenReturn(List.of(aCase(3L, "怎么分块", "RRF-2-7", true, true)));
        when(evals.findCase(3L)).thenReturn(aCase(3L, "怎么分块", "RRF-2-7", true, true));
        stubRunRow();
        stubSearch("怎么分块", outcome(List.of(hit("第一条挡在前面"), hit("里面有 RRF-2-7"))));

        service.run(1L, null, null, null);

        assertThat(written.get(0).firstRank()).isEqualTo(2);
        assertThat(written.get(0).detailJson()).contains("\"rank\":1").contains("挡在前面")
                .contains("\"relevant\":false");
    }

    @Test
    void deletingSomethingThatIsGoneSaysSoInsteadOfReportingSuccess() {
        when(evals.deleteCase(99L)).thenReturn(0);

        assertThatThrownBy(() -> service.deleteCase(99L))
                .isInstanceOfSatisfying(RagException.class, ex -> assertThat(ex.code()).isEqualTo(404));
    }

    private void stubTwoCases() {
        List<Case> cases = List.of(aCase(1L, "怎么分块", "RRF-2-7", true, true),
                aCase(2L, "食堂吃什么", null, false, true));
        when(evals.listCases(null, true)).thenReturn(cases);
        when(evals.findCase(1L)).thenReturn(cases.get(0));
        when(evals.findCase(2L)).thenReturn(cases.get(1));
    }

    private void stubBothPaths(String positiveContent, List<SearchHit> negativeHits) {
        stubSearch("怎么分块", outcome(List.of(hit(positiveContent))));
        stubSearch("食堂吃什么", outcome(negativeHits));
    }

    private void stubSearch(String question, HybridSearchService.Outcome outcome) {
        when(search.search(eq(question), anyList(), isNull(), any(), any())).thenReturn(outcome);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String raw) {
        try {
            return (Map<String, Object>) json.readValue(raw, Map.class);
        } catch (RuntimeException e) {
            throw new AssertionError("不是合法 json：" + raw, e);
        }
    }

    private HybridSearchService.Outcome outcome(List<SearchHit> hits) {
        return new HybridSearchService.Outcome(RetrievalMode.HYBRID, RetrievalMode.HYBRID,
                KeywordEngine.CHINESE_TSVECTOR, null, hits, 12);
    }

    private SearchHit hit(String content) {
        return new SearchHit(11, 5, "运维手册.md", 3, 1, content, "分块", 1, 1, 0, content.length(),
                null, 2, 1, 0.0321, 0.71);
    }

    private static Case aCase(long id, String question, String marker, boolean shouldAnswer, boolean enabled) {
        return new Case(id, 1L, question, marker, null, shouldAnswer, "manual", null, null, enabled);
    }
}
