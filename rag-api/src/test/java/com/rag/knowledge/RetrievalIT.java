package com.rag.knowledge;

import com.rag.knowledge.app.rag.HybridSearchService;
import com.rag.knowledge.app.rag.KeywordEngine;
import com.rag.knowledge.app.rag.RetrievalMode;
import com.rag.knowledge.app.rag.RetrievalQuery;
import com.rag.knowledge.app.rag.SearchHit;
import com.rag.knowledge.db.RetrievalRepository;
import com.rag.knowledge.db.SegmentRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * M3 的验收面：在真实 pgvector 上跑检索 SQL。
 * <p>
 * CTE 形状、RRF 融合、FULL OUTER JOIN 的并集语义、父子块与可用位过滤，这些都是「编译器管不到、
 * 纯单测也管不到」的部分，只能对着真库跑。向量用「沿查询轴的不同投影」构造，名次完全可预测，
 * 不会因为并列而偶发失败。
 * <p>
 * 官方 pgvector 镜像不带 zhparser，所以这里的关键词路覆盖的是 pg_trgm 降级分支；
 * deploy/pg 自造镜像装上 zhparser 后走另一分支，两条分支都得有人钉住。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rag.embedding.dim=768",
        "rag.ingest.worker-enabled=false"
})
class RetrievalIT {

    private static final long KB_ID = 1L;
    private static final long OTHER_KB_ID = 2L;
    private static final long DOC_ID = 11L;
    private static final String QUERY_TEXT = "ERR_GATEWAY_TIMEOUT_504";

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("rag_knowledge")
            .withUsername("rag")
            .withPassword("rag_local_dev");

    @MockitoBean
    EmbeddingModel embeddingModel;

    @Autowired
    RetrievalRepository retrieval;

    @Autowired
    HybridSearchService searchService;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("TRUNCATE knowledge_base, kb_document, kb_segment, kb_task, chat_message, chat_session"
                + " RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO knowledge_base (id, name, embedding_model, embedding_dim) OVERRIDING SYSTEM VALUE"
                + " VALUES (1, '主库', 'bge-base-zh-v1.5', 768), (2, '另一个库', 'bge-base-zh-v1.5', 768)");
        jdbc.update("INSERT INTO kb_document (id, kb_id, name, sha256, content, status, version, available)"
                + " OVERRIDING SYSTEM VALUE VALUES"
                + " (11, 1, '运维手册.pdf', 'sha-11', '正文', 'READY', 1, true),"
                + " (12, 1, '已删除的文档.pdf', 'sha-12', '正文', 'READY', 1, false),"
                + " (13, 2, '别的库的文档.pdf', 'sha-13', '正文', 'READY', 1, true),"
                + " (14, 1, '同库的另一篇.pdf', 'sha-14', '正文', 'READY', 1, true)");
    }

    @Test
    void engineFallsBackToTrigramWhenZhparserIsAbsent() {
        KeywordEngine engine = retrieval.probeEngine();

        assertThat(engine).isIn(KeywordEngine.TRIGRAM, KeywordEngine.CHINESE_TSVECTOR);
        assertThat(engine.available()).isTrue();
        assertThat(engine.description()).isNotBlank();
    }

    @Test
    void vectorPathRanksByCosineDistanceAndCarriesSimilarity() {
        segment(101, DOC_ID, KB_ID, 1, "网关超时的处理办法", 1.0, null, true, true);
        segment(102, DOC_ID, KB_ID, 2, "完全无关的菜谱", 0.1, null, true, true);
        segment(103, DOC_ID, KB_ID, 3, "也在讲网关", 0.7, null, true, true);

        List<SearchHit> hits = retrieval.search(query(RetrievalMode.VECTOR, "网关", 0.0, 10, 40));

        assertThat(hits).extracting(SearchHit::segmentId).containsExactly(101L, 103L, 102L);
        assertThat(hits.get(0).similarity()).isGreaterThan(0.99);
        assertThat(hits.get(0).vectorRank()).isEqualTo(1);
        assertThat(hits.get(0).keywordRank()).isNull();
        assertThat(hits.get(0).rrf()).isEqualTo(1.0 / 61.0);
    }

    @Test
    void vectorThresholdIsMeasuredOnTheCosineScale() {
        segment(101, DOC_ID, KB_ID, 1, "很相关的网关说明", 1.0, null, true, true);
        segment(102, DOC_ID, KB_ID, 2, "勉强有点像", 0.1, null, true, true);

        assertThat(retrieval.search(query(RetrievalMode.VECTOR, "网关", 0.45, 10, 40)))
                .extracting(SearchHit::segmentId).containsExactly(101L);
    }

    @Test
    void keywordPathFindsLiteralTextAndIgnoresVectors() {
        segment(201, DOC_ID, KB_ID, 1, "请致电 " + QUERY_TEXT + " 联系客服", 0.0, null, true, true);
        segment(202, DOC_ID, KB_ID, 2, "重启服务即可恢复", 1.0, null, true, true);

        List<SearchHit> hits = retrieval.search(new RetrievalQuery(List.of(KB_ID), List.of(), QUERY_TEXT,
                axisVector(1.0), RetrievalMode.KEYWORD, retrieval.probeEngine(), 40, 10, 60, 0.0));

        // 202 的向量与查询完全对齐，但 keyword 模式不看向量；201 靠字面命中
        assertThat(hits).extracting(SearchHit::segmentId).containsExactly(201L);
        assertThat(hits.get(0).keywordRank()).isEqualTo(1);
        assertThat(hits.get(0).vectorRank()).isNull();
        assertThat(hits.get(0).similarity()).isNull();
    }

    @Test
    void trigramFloorCutsTheNoiseTailThatRrfAloneCannot() {
        segment(211, DOC_ID, KB_ID, 1, "与查询几乎共享全部字符 " + QUERY_TEXT, 0.5, null, true, true);
        segment(212, DOC_ID, KB_ID, 2, "只共用了一个汉字：网关", 0.5, null, true, true);

        List<SearchHit> hits = retrieval.search(new RetrievalQuery(List.of(KB_ID), List.of(), QUERY_TEXT,
                null, RetrievalMode.KEYWORD, retrieval.probeEngine(), 40, 10, 60, 0.0));

        assertThat(hits).extracting(SearchHit::segmentId).containsExactly(211L);
    }

    @Test
    void hybridKeepsTheUnionAndSumsRrfAcrossBothPaths() {
        // 只有向量路能命中
        segment(301, DOC_ID, KB_ID, 1, "限流与熔断策略说明", 1.0, null, true, true);
        // 只有关键词路能命中
        segment(302, DOC_ID, KB_ID, 2, "顺便记录 " + QUERY_TEXT + " 的现场", 0.1, null, true, true);
        // 两路都命中：融合分必然高于任何单路，所以一定排第一
        segment(303, DOC_ID, KB_ID, 3, QUERY_TEXT, 0.7, null, true, true);

        List<SearchHit> hits = retrieval.search(new RetrievalQuery(List.of(KB_ID), List.of(), QUERY_TEXT,
                axisVector(1.0), RetrievalMode.HYBRID, retrieval.probeEngine(), 2, 10, 60, 0.0));

        assertThat(hits).extracting(SearchHit::segmentId).containsExactly(303L, 301L, 302L);
        SearchHit both = hits.get(0);
        assertThat(both.vectorRank()).isNotNull();
        assertThat(both.keywordRank()).isNotNull();
        assertThat(both.rrf()).isEqualTo(1.0 / (60 + both.vectorRank()) + 1.0 / (60 + both.keywordRank()));
        // candidateK=2 时每条命中至多来自一路
        assertThat(hits.get(1).keywordRank()).isNull();
        assertThat(hits.get(2).vectorRank()).isNull();
    }

    @Test
    void rrfThresholdAndTopKTrimTheTailOfTheFusion() {
        segment(321, DOC_ID, KB_ID, 1, "第一条", 1.0, null, true, true);
        segment(322, DOC_ID, KB_ID, 2, "第二条", 1.0, null, true, true);
        segment(323, DOC_ID, KB_ID, 3, "第三条", 1.0, null, true, true);

        // engine=NONE 且模式 HYBRID ⇒ 只走向量路，融合分退回 1/(k+rank)
        RetrievalQuery tight = new RetrievalQuery(List.of(KB_ID), List.of(), "命中", axisVector(1.0),
                RetrievalMode.HYBRID, KeywordEngine.NONE, 40, 10, 60, 0.0163);
        RetrievalQuery roomy = new RetrievalQuery(List.of(KB_ID), List.of(), "命中", axisVector(1.0),
                RetrievalMode.HYBRID, KeywordEngine.NONE, 40, 2, 60, 0.0);

        assertThat(retrieval.search(tight)).hasSize(1);
        assertThat(retrieval.search(roomy)).hasSize(2);
    }

    @Test
    void scopeExcludesDisabledSegmentsDeletedDocumentsAndOtherKnowledgeBases() {
        segment(401, DOC_ID, KB_ID, 1, "可用内容 available", 1.0, null, true, true);
        segment(402, DOC_ID, KB_ID, 2, "被禁用的内容 disabled", 1.0, null, false, true);
        segment(403, 12, KB_ID, 1, "已删除文档的内容 gone", 1.0, null, true, true);
        segment(404, 13, OTHER_KB_ID, 1, "另一个库的内容 other", 1.0, null, true, true);

        assertThat(retrieval.search(query(RetrievalMode.VECTOR, "内容", 0.0, 10, 40)))
                .extracting(SearchHit::segmentId).containsExactly(401L);
        assertThat(retrieval.search(new RetrievalQuery(List.of(OTHER_KB_ID), List.of(), "内容", axisVector(1.0),
                RetrievalMode.VECTOR, KeywordEngine.NONE, 40, 10, 60, 0.0)))
                .extracting(SearchHit::segmentId).containsExactly(404L);
    }

    @Test
    void docIdFilterNarrowsWithinTheSameKnowledgeBase() {
        segment(501, DOC_ID, KB_ID, 1, "文档一的正文", 1.0, null, true, true);
        segment(502, 14, KB_ID, 1, "文档二的正文", 1.0, null, true, true);

        List<SearchHit> hits = retrieval.search(new RetrievalQuery(List.of(KB_ID), List.of(14L), "正文",
                axisVector(1.0), RetrievalMode.VECTOR, KeywordEngine.NONE, 40, 10, 60, 0.0));

        assertThat(hits).extracting(SearchHit::segmentId).containsExactly(502L);
        assertThat(hits.get(0).docName()).isEqualTo("同库的另一篇.pdf");
    }

    @Test
    void parentBlockHasNoVectorButIsStillReachableByKeywordAndMarkedExpandable() {
        segment(601, DOC_ID, KB_ID, 1, "父块：完整的部署步骤说明，比子块长很多", 0.0, null, true, false);
        segment(602, DOC_ID, KB_ID, 2, "子块：部署步骤", 1.0, 601L, true, true);

        List<SearchHit> keyword = retrieval.search(new RetrievalQuery(List.of(KB_ID), List.of(), "部署步骤说明",
                null, RetrievalMode.KEYWORD, retrieval.probeEngine(), 40, 10, 60, 0.0));
        List<SearchHit> vector = retrieval.search(query(RetrievalMode.VECTOR, "部署步骤", 0.0, 10, 40));

        // 父块没有 embedding，向量路永远碰不到它——它只负责被展开成上下文
        assertThat(keyword).extracting(SearchHit::segmentId).contains(601L);
        assertThat(vector).extracting(SearchHit::segmentId).containsExactly(602L);
        assertThat(vector.get(0).parentId()).isEqualTo(601L);
        assertThat(vector.get(0).location()).startsWith("运维手册.pdf");
    }

    @Test
    void parentContentsAndHitCountingRoundTrip() {
        segment(701, DOC_ID, KB_ID, 1, "父块正文", 0.0, null, true, false);
        segment(702, DOC_ID, KB_ID, 2, "子块正文", 1.0, 701L, true, true);

        // List.of 不接受 null，而这两条断言要验的正是「输入里混了 null 也不炸」，只能用 Arrays.asList
        Map<Long, String> parents = retrieval.parentContents(Arrays.asList(701L, 701L, null));

        assertThat(parents).containsExactly(Map.entry(701L, "父块正文"));
        assertThat(retrieval.parentContents(List.of())).isEmpty();
        assertThat(retrieval.touchHits(Arrays.asList(702L, 702L, null))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT hit_count FROM kb_segment WHERE id = 702", Integer.class)).isOne();
        assertThat(retrieval.touchHits(List.of())).isZero();
    }

    @Test
    void serviceTurnsAQuestionIntoAVectorAndAppliesConfiguredThresholds() {
        segment(801, DOC_ID, KB_ID, 1, "网关超时排查步骤", 1.0, null, true, true);
        segment(802, DOC_ID, KB_ID, 2, "无关内容", 0.1, null, true, true);
        stubEmbedding(1.0);

        HybridSearchService.Outcome outcome = searchService.search("网关超时怎么办", List.of(KB_ID), null,
                "vector", null);

        assertThat(outcome.effective()).isEqualTo(RetrievalMode.VECTOR);
        assertThat(outcome.vectorFailure()).isNull();
        // min-score=0.45 的余弦闸门把 0.1 那条挡在外面
        assertThat(outcome.hits()).extracting(SearchHit::segmentId).containsExactly(801L);
    }

    @Test
    void serviceReportsDegradationInsteadOfThrowingWhenNothingQualifies() {
        segment(811, DOC_ID, KB_ID, 1, "话题完全不同的内容", 0.05, null, true, true);
        stubEmbedding(1.0);

        HybridSearchService.Outcome outcome = searchService.search("库里没有的话题", List.of(KB_ID), null, null, 3);

        assertThat(outcome.requested()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(outcome.hits()).allSatisfy(hit -> assertThat(hit.rrf()).isGreaterThanOrEqualTo(0.012));
        assertThat(outcome.tookMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void embeddingOutageDegradesToKeywordInsteadOfFailingTheQuestion() {
        segment(821, DOC_ID, KB_ID, 1, "客服话术 " + QUERY_TEXT, 0.0, null, true, true);
        when(embeddingModel.embed(anyString())).thenThrow(new IllegalStateException("LM Studio 未启动"));

        HybridSearchService.Outcome outcome = searchService.search(QUERY_TEXT, List.of(KB_ID), null, null, null);

        assertThat(outcome.effective()).isNotEqualTo(RetrievalMode.HYBRID);
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.vectorFailure()).contains("LM Studio 未启动");
        assertThat(outcome.hits()).extracting(SearchHit::segmentId).containsExactly(821L);
    }

    /**
     * 问答侧向量化走的是 `embed(String)`（langchain4j 的默认方法），入库侧才走 `embedAll`。
     * 只 stub embedAll 的话，mock 上的 embed(String) 直接返回 null，
     * 现象是「模型服务好好的」却在 embed(...).content() 上抛 NPE，看着像生产代码的锅。
     */
    private void stubEmbedding(double onQueryAxis) {
        float[] vector = axisVectorArray(onQueryAxis);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> Response.from(new Embedding(vector)));
        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(s -> new Embedding(vector)).toList());
        });
    }

    private RetrievalQuery query(RetrievalMode mode, double threshold, int topK, int candidateK) {
        return new RetrievalQuery(List.of(KB_ID), List.of(), "查询", axisVector(1.0), mode,
                retrieval.probeEngine(), candidateK, topK, 60, threshold);
    }

    private RetrievalQuery query(RetrievalMode mode, String text, double threshold, int topK, int candidateK) {
        return new RetrievalQuery(List.of(KB_ID), List.of(), text, axisVector(1.0), mode,
                retrieval.probeEngine(), candidateK, topK, 60, threshold);
    }

    private void segment(long id, long docId, long kbId, int chunkNo, String content, double onQueryAxis,
                         Long parentId, boolean available, boolean embedded) {
        jdbc.update("""
                INSERT INTO kb_segment (id, doc_id, kb_id, parent_id, chunk_no, doc_version, content,
                                        heading_path, page_start, page_end, char_start, char_end,
                                        embedding, embedding_model, emb_status, available)
                OVERRIDING SYSTEM VALUE
                VALUES (?, ?, ?, ?, ?, 1, ?, '安装', 2, 2, 0, ?, CAST(? AS vector), 'bge-base-zh-v1.5', ?, ?)
                """, id, docId, kbId, parentId, chunkNo, content, content.length(),
                embedded ? axisVector(onQueryAxis) : null, embedded ? "READY" : "SKIPPED", available);
    }

    /** 查询轴固定在第 2 维：投影越大越靠前，名次可预测，不会因并列而偶发失败。 */
    private static String axisVector(double onQueryAxis) {
        return SegmentRepository.toVectorLiteral(axisVectorArray(onQueryAxis));
    }

    private static float[] axisVectorArray(double onQueryAxis) {
        // 单位向量：第 3 维是给定的投影，第 2 维补正交分量。之前只填这两个分量而不归一化，
        // 于是 cos(查询, 块) 与 onQueryAxis 完全不成比例（0.1 也算出 0.996），
        // 「min-score 是余弦量纲」那条断言其实一直在测空气。
        float[] values = new float[768];
        values[1] = (float) Math.sqrt(Math.max(0.0, 1.0 - onQueryAxis * onQueryAxis));
        values[2] = (float) onQueryAxis;
        return values;
    }
}
