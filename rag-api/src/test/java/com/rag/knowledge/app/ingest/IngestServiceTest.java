package com.rag.knowledge.app.ingest;

import com.rag.knowledge.app.ingest.TikaDocumentParser.Parsed;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.DocumentRepository;
import com.rag.knowledge.db.KnowledgeBaseRepository;
import com.rag.knowledge.db.KnowledgeBaseRepository.KnowledgeBase;
import com.rag.knowledge.db.TaskRepository;
import com.rag.knowledge.db.TaskRepository.Task;
import com.rag.knowledge.domain.DocStatus;
import com.rag.knowledge.domain.Document;
import com.rag.knowledge.domain.SegmentDraft;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 入库编排的分支覆盖：排队去重、正文复用、永久失败与可重试失败的分账。
 * 这一层最容易出错的地方都在「有没有走错分支」，而不是算法本身，所以全部用 mock 协作方来钉死。
 */
class IngestServiceTest {

    private static final int DIM = 8;
    private static final long KB_ID = 1L;
    private static final long DOC_ID = 7L;
    private static final String FILE_PATH = "kb1/abcdef.md";
    private static final String OWNER = "worker-1";

    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final TikaDocumentParser parser = mock(TikaDocumentParser.class);
    private final SegmentIndexPublisher publisher = mock(SegmentIndexPublisher.class);

    @Test
    void uploadRejectsUnsupportedExtensionBeforeTouchingAnything() {
        IngestService service = service();

        assertThatThrownBy(() -> service.upload(KB_ID, "payload.exe", "application/octet-stream", new byte[] { 1 }))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("不支持的文件类型")
                .hasMessageContaining("pdf");
        verifyNoInteractions(documents, tasks, storage, publisher);
    }

    @Test
    void uploadRejectsEmptyAndOversizeFiles() {
        IngestService service = service();

        assertThatThrownBy(() -> service.upload(KB_ID, "a.md", "text/markdown", new byte[0]))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("文件内容为空");
        assertThatThrownBy(() -> service.upload(KB_ID, "b.md", "text/markdown",
                new byte[1024 * 1024 + 1]))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("超过 1MB 限制");
        verifyNoInteractions(storage, documents);
    }

    @Test
    void uploadingIdenticalReadyDocumentReusesItWithoutNewTask() {
        when(knowledgeBases.require(KB_ID)).thenReturn(kb(DIM));
        when(storage.store(KB_ID, "已入库.pdf", new byte[] { 1, 2 }))
                .thenReturn(new FileStorage.Stored(FILE_PATH, "sha-existing", 2L));
        when(documents.findBySha(KB_ID, "sha-existing"))
                .thenReturn(Optional.of(document(DocStatus.READY, 3)));
        IngestService service = service();

        IngestService.UploadResult result = service.upload(KB_ID, "已入库.pdf", "application/pdf", new byte[] { 1, 2 });

        assertThat(result.duplicated()).isTrue();
        assertThat(result.taskId()).isZero();
        assertThat(result.status()).isEqualTo(DocStatus.READY);
        verify(documents, never()).insert(anyLong(), any(), any(), any(), any(), any());
        verify(tasks, never()).enqueue(any(), anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void failedDuplicateIsRequeuedAndStillReusesStoredText() {
        when(knowledgeBases.require(KB_ID)).thenReturn(kb(DIM));
        when(storage.store(KB_ID, "重试.pdf", new byte[] { 3 }))
                .thenReturn(new FileStorage.Stored(FILE_PATH, "sha-failed", 1L));
        when(documents.findBySha(KB_ID, "sha-failed"))
                .thenReturn(Optional.of(document(DocStatus.FAILED, 2)));
        when(tasks.findActiveForDoc(TaskRepository.TYPE_INGEST, DOC_ID)).thenReturn(Optional.empty());
        when(tasks.enqueue(TaskRepository.TYPE_INGEST, KB_ID, DOC_ID, "{}", 3)).thenReturn(42L);
        IngestService service = service();

        IngestService.UploadResult result = service.upload(KB_ID, "重试.pdf", "application/pdf", new byte[] { 3 });

        assertThat(result.taskId()).isEqualTo(42L);
        assertThat(result.duplicated()).isTrue();
        verify(documents, never()).insert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void storedTextIsUsedInsteadOfRunningTikaAgain() {
        stubStoredText(sampleText());
        when(publisher.publish(anyLong(), anyLong(), anyInt(), any(), anyInt(), any(), any()))
                .thenReturn(new SegmentIndexPublisher.Result(6, 0));
        IngestService service = service();

        service.process(task("{}"), OWNER);

        verifyNoInteractions(parser, storage);
        verify(documents, never()).saveParsed(anyLong(), any(), anyInt(), any());
        verify(tasks).finish(100L, OWNER);

        List<SegmentDraft> drafts = capturedDrafts();
        assertThat(drafts).isNotEmpty();
        assertThat(drafts).anyMatch(draft -> draft.searchable() && draft.embedding() != null);
        assertThat(drafts).allSatisfy(draft -> assertThat(draft.content()).isNotBlank());
    }

    @Test
    void reparsePayloadGoesBackToTheOriginalFile() {
        stubStoredText(sampleText());
        when(storage.read(FILE_PATH)).thenReturn("原文".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(parser.parse(any(), eq("重构方案.md")))
                .thenReturn(new Parsed(sampleText() + "\n补充段落。向量检索与关键词检索互补。", 1, "text/markdown"));
        when(publisher.publish(anyLong(), anyLong(), anyInt(), any(), anyInt(), any(), any()))
                .thenReturn(new SegmentIndexPublisher.Result(6, 0));
        IngestService service = service();

        service.process(task("{\"reparse\":true}"), OWNER);

        verify(parser).parse(any(), eq("重构方案.md"));
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(documents).saveParsed(eq(DOC_ID), saved.capture(), eq(1), eq("text/markdown"));
        assertThat(saved.getValue()).contains("补充段落");
        // 清洗后的文本才是落库内容：原文里的裸 '\n' 前后空白被归一过
        assertThat(saved.getValue()).doesNotContain("\r");
    }

    @Test
    void targetIndexVersionAndParentLinksAreHandedToThePublisher() {
        stubStoredText(sampleText());
        when(publisher.publish(anyLong(), anyLong(), anyInt(), any(), anyInt(), any(), any()))
                .thenReturn(new SegmentIndexPublisher.Result(8, 2));
        IngestService service = service();

        service.process(task("{}"), OWNER);

        ArgumentCaptor<List<SegmentDraft>> drafts = draftsCaptor();
        ArgumentCaptor<List<int[]>> links = linksCaptor();
        verify(publisher).publish(eq(DOC_ID), eq(KB_ID), eq(4), eq("bge-base-zh-v1.5"), eq(11),
                drafts.capture(), links.capture());
        // 版本 = 旧版本 + 1，重跑不会覆盖正在服务的那一代索引
        assertThat(links.getValue()).isNotEmpty()
                .allSatisfy(pair -> assertThat(pair).hasSize(2));
        assertThat(drafts.getValue()).anyMatch(draft -> !draft.searchable() && draft.embedding() == null);
    }

    @Test
    void garbledTextIsPermanentAndDoesNotBurnTheRetryBudget() {
        when(documents.findById(DOC_ID)).thenReturn(Optional.of(document(DocStatus.PENDING, 1)));
        when(knowledgeBases.require(KB_ID)).thenReturn(kb(DIM));
        when(documents.content(DOC_ID)).thenReturn(null);
        when(storage.read(FILE_PATH)).thenReturn(new byte[] { 1 });
        StringBuilder mojibake = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            mojibake.append((char) 0xE000).append((char) 0xE050);
        }
        when(parser.parse(any(), any())).thenReturn(new Parsed(mojibake.toString(), 1, "application/pdf"));
        IngestService service = service();

        service.process(task("{}"), OWNER);

        verify(documents).markFailed(eq(DOC_ID), eq("GARBLED_TEXT"), any());
        verify(tasks).abandon(eq(100L), eq(OWNER), eq("GARBLED_TEXT"), any());
        verify(tasks, never()).fail(anyLong(), any(), any(), any(), any());
        verify(publisher, never()).publish(anyLong(), anyLong(), anyInt(), any(), anyInt(), any(), any());
    }

    @Test
    void emptyParseResultIsAlsoPermanent() {
        when(documents.findById(DOC_ID)).thenReturn(Optional.of(document(DocStatus.PENDING, 1)));
        when(knowledgeBases.require(KB_ID)).thenReturn(kb(DIM));
        when(documents.content(DOC_ID)).thenReturn(null);
        when(storage.read(FILE_PATH)).thenReturn(new byte[] { 1 });
        when(parser.parse(any(), any())).thenReturn(new Parsed("  \n ", 1, "application/pdf"));
        IngestService service = service();

        service.process(task("{}"), OWNER);

        verify(tasks).abandon(eq(100L), eq(OWNER), eq("GARBLED_TEXT"), any());
        verify(tasks, never()).fail(anyLong(), any(), any(), any(), any());
    }

    @Test
    void modelDimensionChangeIsPermanent() {
        stubStoredText(sampleText());
        IngestService service = service(embeddings(16));

        service.process(task("{}"), OWNER);

        verify(documents).markFailed(eq(DOC_ID), eq("DIM_MISMATCH"), any());
        verify(tasks).abandon(eq(100L), eq(OWNER), eq("DIM_MISMATCH"), any());
        verify(publisher, never()).publish(anyLong(), anyLong(), anyInt(), any(), anyInt(), any(), any());
    }

    @Test
    void transientModelFailureKeepsTheTaskQueuedForRetry() {
        stubStoredText(sampleText());
        IngestService service = service(new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                throw new IllegalStateException("LM Studio 连接被重置");
            }
        });
        when(tasks.fail(eq(100L), eq(OWNER), eq("IllegalStateException"), any(), any())).thenReturn(true);

        service.process(task("{}"), OWNER);

        verify(tasks).fail(eq(100L), eq(OWNER), eq("IllegalStateException"), any(), any());
        verify(documents).increaseRetry(DOC_ID);
        verify(documents, never()).markFailed(anyLong(), any(), any());
        verify(tasks, never()).abandon(anyLong(), any(), any(), any());
    }

    @Test
    void lastAttemptMarksTheDocumentFailed() {
        stubStoredText(sampleText());
        IngestService service = service(new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                throw new IllegalStateException("LM Studio 未启动");
            }
        });
        when(tasks.fail(eq(100L), eq(OWNER), any(), any(), any())).thenReturn(false);

        service.process(task("{}"), OWNER);

        verify(documents).markFailed(eq(DOC_ID), eq("IllegalStateException"), any());
    }

    @Test
    void progressIsWrittenPerStageAndEmbeddingMovesWithinItsBand() {
        stubStoredText(sampleText());
        when(publisher.publish(anyLong(), anyLong(), anyInt(), any(), anyInt(), any(), any()))
                .thenReturn(new SegmentIndexPublisher.Result(6, 0));
        IngestService service = service();

        service.process(task("{}"), OWNER);

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> progress = ArgumentCaptor.forClass(Integer.class);
        verify(documents, atLeast(4)).updateStage(eq(DOC_ID), any(), progress.capture(), messages.capture());
        // 进度只能往前走，最后一步固定是「写入索引」的 88
        assertThat(progress.getAllValues()).isSorted().endsWith(88);
        assertThat(messages.getAllValues()).contains("语义分块");
        assertThat(messages.getAllValues()).anyMatch(message -> message.startsWith("向量化 "));
    }

    @Test
    void reindexRefusesADocumentThatIsStillBeingIngested() {
        when(documents.findById(DOC_ID)).thenReturn(Optional.of(document(DocStatus.EMBEDDING, 1)));
        IngestService service = service();

        assertThatThrownBy(() -> service.reindex(DOC_ID))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("正在入库中");
        verify(tasks, never()).enqueue(any(), anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void reindexWithReparseRecordsTheFlagInTheTaskPayload() {
        when(documents.findById(DOC_ID)).thenReturn(Optional.of(document(DocStatus.READY, 1)));
        when(tasks.enqueue(eq(TaskRepository.TYPE_INGEST), eq(KB_ID), eq(DOC_ID), eq("{\"reparse\":true}"), eq(3)))
                .thenReturn(55L);
        IngestService service = service();

        assertThat(service.reindex(DOC_ID, true)).isEqualTo(55L);
    }

    @Test
    void deletingACancelsQueuedTasksAndSoftDeletesTheRow() {
        when(documents.findById(DOC_ID)).thenReturn(Optional.of(document(DocStatus.PENDING, 1)));
        when(tasks.cancelForDoc(DOC_ID)).thenReturn(2);
        IngestService service = service();

        service.delete(DOC_ID);

        verify(tasks).cancelForDoc(DOC_ID);
        verify(documents).softDelete(DOC_ID);
    }

    @Test
    void wrongKbDimensionStopsTheRunBeforeParsing() {
        when(documents.findById(DOC_ID)).thenReturn(Optional.of(document(DocStatus.PENDING, 1)));
        when(knowledgeBases.require(KB_ID)).thenReturn(new KnowledgeBase(KB_ID, "1536 维库", "nomic", 1536, 4));
        IngestService service = service();

        service.process(task("{}"), OWNER);

        verify(tasks).abandon(eq(100L), eq(OWNER), eq("DIM_MISMATCH"), any());
        verifyNoInteractions(parser, storage, publisher);
    }

    @Test
    void previewReSplitsStoredTextWithoutSpendingAnyEmbedding() {
        when(documents.findById(DOC_ID)).thenReturn(Optional.of(document(DocStatus.READY, 3)));
        when(documents.content(DOC_ID)).thenReturn(sampleText());
        IngestService service = service();

        IngestService.Preview preview = service.preview(DOC_ID, 200, 60, 40, 800, true, 30);

        assertThat(preview.totalBlocks()).isPositive();
        assertThat(preview.searchableBlocks()).isBetween(1, preview.totalBlocks());
        assertThat(preview.blocks()).hasSizeLessThanOrEqualTo(30);
        assertThat(preview.blocks()).allSatisfy(block -> assertThat(block.content()).isNotBlank());
        verifyNoInteractions(parser, storage, publisher, tasks);
    }

    @Test
    void previewRefusesADocumentThatHasNoStoredTextYet() {
        when(documents.findById(DOC_ID)).thenReturn(Optional.of(document(DocStatus.PENDING, 0)));
        when(documents.content(DOC_ID)).thenReturn(null);
        IngestService service = service();

        RagException ex = catchThrowableOfType(
                () -> service.preview(DOC_ID, null, null, null, null, null, 10), RagException.class);

        // 提示走 hints 字段，由 GlobalExceptionHandler 拼进 message，所以这里分开断言
        assertThat(ex.getMessage()).contains("还没有可预览的正文");
        assertThat(ex.hints()).anyMatch(hint -> hint.contains("reindex"));
    }

    @Test
    void previewRejectsBadChunkParametersAsClientError() {
        when(documents.findById(DOC_ID)).thenReturn(Optional.of(document(DocStatus.READY, 3)));
        when(documents.content(DOC_ID)).thenReturn(sampleText());
        IngestService service = service();

        // 参数越界必须由接口挡住：让 ChunkConfig 抛 IllegalArgumentException 会变成 500
        assertThatThrownBy(() -> service.preview(DOC_ID, 30, 60, 10, 800, true, 10))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("分块参数不合理");
        assertThatThrownBy(() -> service.preview(DOC_ID, 400, 60, 400, 800, true, 10))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("overlap<child");
        assertThatThrownBy(() -> service.preview(DOC_ID, 400, 60, 60, 200, true, 10))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("parent>=child");
    }

    private IngestService service() {
        return service(embeddings(DIM));
    }

    private IngestService service(EmbeddingModel embeddingModel) {
        RagProperties props = new RagProperties(
                new RagProperties.LmStudio("http://localhost:1234/v1", "lm-studio", "qwen2.5-vl-7b-instruct",
                        "bge-base-zh-v1.5", 0.3, 0.8, null, Duration.ofSeconds(5), 1),
                new RagProperties.Storage("./target/ingest-test-storage"),
                new RagProperties.Embedding(DIM, 2),
                new RagProperties.Ingest(false, Duration.ofMillis(1), Duration.ofMinutes(5), 3, 1, 1,
                        List.of("pdf", "docx", "md", "txt"), RagProperties.Chunk.defaults()));
        return new IngestService(props, knowledgeBases, documents, tasks, storage, parser,
                new ChineseSemanticSplitter(), embeddingModel, publisher);
    }

    private void stubStoredText(String cleaned) {
        when(documents.findById(DOC_ID)).thenReturn(Optional.of(document(DocStatus.PENDING, 3)));
        when(knowledgeBases.require(KB_ID)).thenReturn(kb(DIM));
        when(documents.content(DOC_ID)).thenReturn(cleaned);
    }

    private List<SegmentDraft> capturedDrafts() {
        ArgumentCaptor<List<SegmentDraft>> captor = draftsCaptor();
        verify(publisher).publish(anyLong(), anyLong(), anyInt(), any(), anyInt(), captor.capture(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<SegmentDraft>> draftsCaptor() {
        return ArgumentCaptor.forClass((Class<List<SegmentDraft>>) (Class<?>) List.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<int[]>> linksCaptor() {
        return ArgumentCaptor.forClass((Class<List<int[]>>) (Class<?>) List.class);
    }

    private static Task task(String payload) {
        return new Task(100L, TaskRepository.TYPE_INGEST, KB_ID, DOC_ID, payload, 1, 3);
    }

    private static KnowledgeBase kb(int dim) {
        return new KnowledgeBase(KB_ID, "默认知识库", "bge-base-zh-v1.5", dim, 11);
    }

    private static Document document(DocStatus status, int version) {
        return new Document(DOC_ID, KB_ID, "重构方案.md", "sha", FILE_PATH, 1024L, "text/markdown",
                null, null, status, 0, null, version, 11, null, null);
    }

    private static String sampleText() {
        StringBuilder text = new StringBuilder("# 检索设计\n\n");
        for (int i = 1; i <= 12; i++) {
            text.append("第").append(i).append("节 混合检索。向量召回负责语义相近的表达，")
                    .append("关键词召回负责专有名词与文档编号，两路结果用倒数排名融合后再一起截断。\n\n");
        }
        return text.toString();
    }

    private static EmbeddingModel embeddings(int dim) {
        return new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                List<Embedding> vectors = new ArrayList<>(segments.size());
                for (int i = 0; i < segments.size(); i++) {
                    float[] vector = new float[dim];
                    vector[0] = 1f;
                    vectors.add(new Embedding(vector));
                }
                return Response.from(vectors);
            }
        };
    }
}
