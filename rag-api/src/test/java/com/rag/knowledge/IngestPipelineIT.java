package com.rag.knowledge;

import com.rag.knowledge.app.ingest.ChineseSemanticSplitter;
import com.rag.knowledge.app.ingest.IngestService;
import com.rag.knowledge.db.DocumentRepository;
import com.rag.knowledge.db.SegmentRepository;
import com.rag.knowledge.db.TaskRepository;
import com.rag.knowledge.db.TaskRepository.Task;
import com.rag.knowledge.domain.DocStatus;
import com.rag.knowledge.domain.Document;
import com.rag.knowledge.domain.SegmentView;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * M2 的验收面：在真实 pgvector 上跑完整流水线（解析→分块→向量→索引切换→任务队列）。
 * embedding 换成确定性假向量，所以这里验的是 SQL、状态机与崩溃语义，不是模型质量。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rag.embedding.dim=768",
        // worker 由测试自己驱动，避免异步时序把断言变成睡眠等待
        "rag.ingest.worker-enabled=false"
})
class IngestPipelineIT {

    private static final String OWNER = "it-worker";

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("rag_knowledge")
            .withUsername("rag")
            .withPassword("rag_local_dev");

    @MockitoBean
    EmbeddingModel embeddingModel;

    @Autowired
    IngestService ingest;

    @Autowired
    DocumentRepository documents;

    @Autowired
    SegmentRepository segments;

    @Autowired
    TaskRepository tasks;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void startFromAnEmptyLibrary() {
        // 同一个容器与上下文跑完全类：不清队列的话，claim 会领到上一条用例留下的任务，断言就变成看运气
        jdbc.update("TRUNCATE kb_segment, kb_document, kb_task, knowledge_base RESTART IDENTITY CASCADE");
        stubEmbedding(768);
    }

    @Test
    void uploadQueuesThenOneRunPublishesAReadyGeneration() {
        long kbId = ingest.defaultKbId();
        IngestService.UploadResult uploaded = uploadMarkdown(kbId, "检索设计.md", markdown(4));

        assertThat(uploaded.duplicated()).isFalse();
        assertThat(document(uploaded.documentId()).status()).isEqualTo(DocStatus.PENDING);
        assertThat(tasks.countActive(kbId)).isEqualTo(1);

        runNextTask();

        Document done = document(uploaded.documentId());
        assertThat(done.status()).isEqualTo(DocStatus.READY);
        // 代次编号从 0 起算：上传插的行是 version=0，第一次发布才 +1（IngestService 的 targetVersion），
        // 所以「首次入库 = 1、重算 = 2」，别按「上传即 1」写断言。
        assertThat(done.version()).isEqualTo(1);
        assertThat(done.charCount()).isPositive();
        assertThat(text("SELECT status FROM kb_task WHERE doc_id = ?", done.id())).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT indexed_at IS NOT NULL FROM kb_document WHERE id = ?",
                Boolean.class, done.id())).isTrue();

        List<SegmentView> published = segments.listForDocument(done.id(), 1, false, 500, 0);
        assertThat(published).isNotEmpty();
        assertThat(published).allMatch(SegmentView::available);
        assertThat(segments.countMissingEmbedding(done.id(), 1)).isZero();
        // 父子块：检索块都带向量，父块只做上下文、emb_status=SKIPPED
        assertThat(published).anyMatch(view -> "SKIPPED".equals(view.embStatus()));
        assertThat(published).anyMatch(view -> view.parentChunkNo() != null);
    }

    @Test
    void chunkOffsetsSliceBackExactlyTheStoredText() {
        long docId = uploadMarkdown(ingest.defaultKbId(), "偏移量.md", markdown(3)).documentId();
        runNextTask();

        String base = ChineseSemanticSplitter.offsetBase(documents.content(docId));
        List<SegmentView> views = segments.listForDocument(docId, document(docId).version(), true, 500, 0);

        assertThat(views).isNotEmpty();
        for (SegmentView view : views) {
            assertThat(view.charEnd()).as("块 %d 的偏移量必须落在原文范围内", view.chunkNo())
                    .isLessThanOrEqualTo(base.length());
            assertThat(base.substring(view.charStart(), view.charEnd())).isEqualTo(view.content());
        }
    }

    @Test
    void reindexReusesStoredTextAndSwapsGenerationsAtomically() {
        long docId = uploadMarkdown(ingest.defaultKbId(), "蓝绿.md", markdown(3)).documentId();
        runNextTask();
        String parsedAt = text("SELECT parsed_at::text FROM kb_document WHERE id = ?", docId);

        ingest.reindex(docId);
        runNextTask();

        Document after = document(docId);
        assertThat(after.status()).isEqualTo(DocStatus.READY);
        assertThat(after.version()).isEqualTo(2);
        // 旧代次整条消失、新代次全部可用：不存在半新半旧的中间态
        assertThat(segments.countForDocument(docId, 1)).isZero();
        assertThat(segments.count(docId, 2, true)).isPositive();
        // parsed_at 没动 = 这一轮没跑 Tika，正文来自 kb_document.content
        assertThat(text("SELECT parsed_at::text FROM kb_document WHERE id = ?", docId)).isEqualTo(parsedAt);
    }

    @Test
    void reparseFlagGoesBackToTheOriginalFile() {
        long docId = uploadMarkdown(ingest.defaultKbId(), "重解析.md", markdown(2)).documentId();
        runNextTask();
        String parsedAt = text("SELECT parsed_at::text FROM kb_document WHERE id = ?", docId);

        ingest.reindex(docId, true);
        runNextTask();

        assertThat(text("SELECT parsed_at::text FROM kb_document WHERE id = ?", docId)).isNotEqualTo(parsedAt);
        assertThat(document(docId).version()).isEqualTo(2);
    }

    @Test
    void identicalContentIsStoredOnceAndDoesNotReconsumeEmbedding() {
        long kbId = ingest.defaultKbId();
        byte[] bytes = markdown(2).getBytes(StandardCharsets.UTF_8);
        IngestService.UploadResult first = ingest.upload(kbId, "原名.md", "text/markdown", bytes);
        runNextTask();

        IngestService.UploadResult second = ingest.upload(kbId, "换个名字.md", "text/markdown", bytes);

        assertThat(second.duplicated()).isTrue();
        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(second.taskId()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_document WHERE kb_id = ?",
                Integer.class, kbId)).isEqualTo(1);
    }

    @Test
    void garbledDocumentFailsPermanentlyWithoutBurningTheRetryBudget() {
        StringBuilder mojibake = new StringBuilder("# 标题\n\n");
        for (int i = 0; i < 60; i++) {
            mojibake.append((char) 0xE001).append((char) 0xE040);
        }
        long docId = uploadBytes(ingest.defaultKbId(), "扫描件.md",
                mojibake.toString().getBytes(StandardCharsets.UTF_8)).documentId();

        runNextTask();

        Document failed = document(docId);
        assertThat(failed.status()).isEqualTo(DocStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("GARBLED_TEXT");
        assertThat(failed.errorMsg()).contains("处理建议");
        assertThat(text("SELECT status || '/' || attempt || '/' || COALESCE(error_type, '')"
                + " FROM kb_task WHERE doc_id = ?", docId)).isEqualTo("FAILED/1/GARBLED_TEXT");
        assertThat(segments.countForDocument(docId, 1)).isZero();
    }

    @Test
    void modelDimensionChangeAbortsBeforeWritingAnySegment() {
        stubEmbedding(512);
        long docId = uploadMarkdown(ingest.defaultKbId(), "维度漂移.md", markdown(2)).documentId();

        runNextTask();

        assertThat(document(docId).status()).isEqualTo(DocStatus.FAILED);
        assertThat(document(docId).errorCode()).isEqualTo("DIM_MISMATCH");
        assertThat(segments.countForDocument(docId, 1)).isZero();
    }

    @Test
    void transientModelFailureRetriesWithBackoffAndGivesUpAtTheLimit() {
        long docId = uploadMarkdown(ingest.defaultKbId(), "抖动.md", markdown(2)).documentId();
        reset(embeddingModel);
        when(embeddingModel.embedAll(anyList())).thenThrow(new IllegalStateException("LM Studio 连接被重置"));

        runNextTask();

        assertThat(document(docId).status()).isNotEqualTo(DocStatus.FAILED);
        assertThat(text("SELECT status FROM kb_task WHERE doc_id = ?", docId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT next_retry_at > now() FROM kb_task WHERE doc_id = ?",
                Boolean.class, docId)).as("退避必须让下一次领取延后").isTrue();

        for (int attempt = 0; attempt < 3 && document(docId).status() != DocStatus.FAILED; attempt++) {
            jdbc.update("UPDATE kb_task SET next_retry_at = now() WHERE doc_id = ?", docId);
            runNextTask();
        }

        assertThat(document(docId).status()).isEqualTo(DocStatus.FAILED);
        assertThat(text("SELECT status FROM kb_task WHERE doc_id = ?", docId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM kb_document WHERE id = ?", Integer.class, docId))
                .isPositive();
    }

    @Test
    void expiredLeaseGoesBackToTheQueueInsteadOfStayingRunning() {
        long docId = uploadMarkdown(ingest.defaultKbId(), "崩溃恢复.md", markdown(2)).documentId();
        assertThat(tasks.claim(OWNER, 1, Duration.ofSeconds(30))).hasSize(1);
        jdbc.update("UPDATE kb_task SET lease_until = now() - interval '1 second' WHERE doc_id = ?", docId);

        assertThat(tasks.reapExpiredLeases()).isEqualTo(1);

        assertThat(text("SELECT status FROM kb_task WHERE doc_id = ?", docId)).isEqualTo("QUEUED");
        assertThat(tasks.claim("another-worker", 1, Duration.ofSeconds(30))).hasSize(1);
        assertThat(document(docId).status()).isEqualTo(DocStatus.PENDING);
    }

    @Test
    void twoWorkersNeverGetTheSameTask() {
        long kbId = ingest.defaultKbId();
        for (int i = 0; i < 3; i++) {
            tasks.enqueue(TaskRepository.TYPE_INGEST, kbId, null, "{}", 3);
        }

        Set<Long> first = ids(tasks.claim("worker-a", 2, Duration.ofMinutes(1)));
        Set<Long> second = ids(tasks.claim("worker-b", 2, Duration.ofMinutes(1)));

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(first).doesNotContainAnyElementsOf(second);
    }

    @Test
    void deletingADocumentRemovesItFromTheQueueAndTheList() {
        long docId = uploadMarkdown(ingest.defaultKbId(), "误删.md", markdown(2)).documentId();

        ingest.delete(docId);

        assertThat(documents.findById(docId)).isEmpty();
        assertThat(tasks.findActiveForDoc(TaskRepository.TYPE_INGEST, docId)).isEmpty();
        assertThat(text("SELECT status FROM kb_task WHERE doc_id = ?", docId)).isEqualTo("CANCELLED");
        // 只是软删：原始文件与块数据都还在，误删可恢复
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM kb_document WHERE id = ?",
                Boolean.class, docId)).isTrue();
    }

    @Test
    void pdfSegmentsCarryTheirRealPageNumbers() throws IOException {
        long docId = uploadBytes(ingest.defaultKbId(), "两页.pdf", pdf(2, 14)).documentId();

        runNextTask();

        Document done = document(docId);
        assertThat(done.status()).isEqualTo(DocStatus.READY);
        assertThat(done.pageCount()).isEqualTo(2);
        List<SegmentView> views = segments.listForDocument(docId, done.version(), true, 500, 0);
        assertThat(views).anyMatch(view -> view.pageStart() != null && view.pageStart() == 1);
        assertThat(views).anyMatch(view -> view.pageStart() != null && view.pageStart() == 2);
    }

    private void stubEmbedding(int dim) {
        reset(embeddingModel);
        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<TextSegment> inputs = invocation.getArgument(0);
            List<Embedding> vectors = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) {
                float[] vector = new float[dim];
                vector[0] = 1f;
                vector[1] = i;
                vectors.add(new Embedding(vector));
            }
            return Response.from(vectors);
        });
    }

    /** 队列驱动：领一条就跑完，跑完 worker 会自己释放租约。 */
    private void runNextTask() {
        List<Task> claimed = tasks.claim(OWNER, 1, Duration.ofMinutes(5));
        assertThat(claimed).as("队列里没有待处理任务").hasSize(1);
        ingest.process(claimed.get(0), OWNER);
    }

    private IngestService.UploadResult uploadMarkdown(long kbId, String fileName, String content) {
        return ingest.upload(kbId, fileName, "text/markdown", content.getBytes(StandardCharsets.UTF_8));
    }

    private IngestService.UploadResult uploadBytes(long kbId, String fileName, byte[] content) {
        String mime = fileName.endsWith(".pdf") ? "application/pdf" : "text/markdown";
        return ingest.upload(kbId, fileName, mime, content);
    }

    private Document document(long id) {
        Optional<Document> found = documents.findById(id);
        assertThat(found).isPresent();
        return found.get();
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private static Set<Long> ids(List<Task> claimed) {
        return claimed.stream().map(Task::id).collect(Collectors.toSet());
    }

    /**
     * 每个小节都要长到能切出多个子块，否则父子块不会生成，
     * 「父块只做上下文」这条链路就永远测不到。
     */
    private static String markdown(int sections) {
        StringBuilder text = new StringBuilder();
        for (int section = 1; section <= sections; section++) {
            text.append("## 第 ").append(section).append(" 节 混合检索\n\n");
            for (int sentence = 1; sentence <= 22; sentence++) {
                text.append("向量召回负责语义相近的表达，关键词召回负责专有名词与文档编号，")
                        .append("两路结果用倒数排名融合后再一起截断，编号 RRF-")
                        .append(section).append('-').append(sentence)
                        .append(" 只有关键词路才能命中。\n");
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** 页码只能来自真实 PDF 事件，这里现场生成，不依赖测试资源文件。 */
    private static byte[] pdf(int pages, int linesPerPage) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int page = 1; page <= pages; page++) {
                PDPage pdPage = new PDPage();
                doc.addPage(pdPage);
                try (PDPageContentStream stream = new PDPageContentStream(doc, pdPage)) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(50, 700);
                    for (int line = 1; line <= linesPerPage; line++) {
                        stream.showText("page " + page + " line " + line
                                + " explains the retrieval design of a local knowledge base.");
                        stream.newLineAtOffset(0, -16);
                    }
                    stream.endText();
                }
            }
            doc.save(out);
        }
        return out.toByteArray();
    }
}
