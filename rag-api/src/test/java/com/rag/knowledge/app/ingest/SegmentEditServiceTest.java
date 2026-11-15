package com.rag.knowledge.app.ingest;

import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.DocumentRepository;
import com.rag.knowledge.db.SegmentRepository;
import com.rag.knowledge.domain.DocStatus;
import com.rag.knowledge.domain.Document;
import com.rag.knowledge.domain.SegmentView;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 单块人工编辑的分支：文字必须存下来、向量必须跟着走、模型不在的时候不能把主操作判成失败。
 * 这三件事各自出错的表现都不一样，所以逐条钉住，而不是只测一条顺利路径。
 */
class SegmentEditServiceTest {

    private static final int DIM = 8;
    private static final long DOC_ID = 7L;
    private static final long SEGMENT_ID = 101L;

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final SegmentRepository segments = mock(SegmentRepository.class);
    private final StubEmbedding embeddingModel = new StubEmbedding();

    @Test
    void editingCleansTheTextThenRecomputesThatOneVector() {
        stubDocument(DocStatus.READY);
        when(segments.editContent(DOC_ID, SEGMENT_ID, "向量检索\n与关键词检索互补。")).thenReturn(1);
        when(segments.findView(DOC_ID, SEGMENT_ID))
                .thenReturn(Optional.of(segment("PENDING", "向量检索\n与关键词检索互补。")),
                        Optional.of(segment("READY", "向量检索\n与关键词检索互补。")));
        SegmentEditService service = service();

        SegmentEditService.EditResult result = service.edit(DOC_ID, SEGMENT_ID,
                "  向量检索\r\n与关键词检索互补。  ", true);

        assertThat(result.embedded()).isTrue();
        assertThat(result.note()).isNull();
        assertThat(result.segment().embStatus()).isEqualTo("READY");
        // 送进模型的是库里读回来的那一份，不是请求体：清洗这一步必须由数据库这一侧证实
        assertThat(embeddingModel.embedded).containsExactly("向量检索\n与关键词检索互补。");
        ArgumentCaptor<String> literal = ArgumentCaptor.forClass(String.class);
        verify(segments).setEmbedding(eq(DOC_ID), eq(SEGMENT_ID), literal.capture(), eq("bge-base-zh-v1.5"));
        assertThat(literal.getValue()).startsWith("[").doesNotContain("null");
    }

    /** 改完文字后模型没起来是最常见的路径：编辑本身成功了，报 500 会让人以为改动丢了并反复重改。 */
    @Test
    void aVectorFailureKeepsTheSavedTextAndSaysWhatIsStillMissing() {
        stubDocument(DocStatus.READY);
        embeddingModel.fail = true;
        when(segments.editContent(anyLong(), anyLong(), anyString())).thenReturn(1);
        when(segments.findView(DOC_ID, SEGMENT_ID)).thenReturn(Optional.of(segment("PENDING")));
        SegmentEditService service = service();

        SegmentEditService.EditResult result = service.edit(DOC_ID, SEGMENT_ID, "改过的正文", true);

        assertThat(result.embedded()).isFalse();
        assertThat(result.note()).contains("关键词检索").contains("重算向量");
        verify(segments, never()).setEmbedding(anyLong(), anyLong(), any(), any());
    }

    /** 换错模型时维度会跟着变：写进去的向量与列宽不匹配是数据库错误，与旧向量混用是检索错误，都不如不写。 */
    @Test
    void aVectorOfTheWrongDimensionIsNotWritten() {
        stubDocument(DocStatus.READY);
        embeddingModel.dim = DIM + 8;
        when(segments.editContent(anyLong(), anyLong(), anyString())).thenReturn(1);
        when(segments.findView(DOC_ID, SEGMENT_ID)).thenReturn(Optional.of(segment("PENDING")));
        SegmentEditService service = service();

        SegmentEditService.EditResult result = service.edit(DOC_ID, SEGMENT_ID, "改过的正文", true);

        assertThat(result.embedded()).isFalse();
        assertThat(result.note()).contains("维度").contains(String.valueOf(DIM + 8));
        verify(segments, never()).setEmbedding(anyLong(), anyLong(), any(), any());
    }

    @Test
    void emptyAndOversizedTextAreRejectedBeforeTouchingTheDatabase() {
        stubDocument(DocStatus.READY);
        SegmentEditService service = service();

        assertThatThrownBy(() -> service.edit(DOC_ID, SEGMENT_ID, "  \r\n  ", true))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("不能为空")
                .hasMessageContaining("available=false");
        assertThatThrownBy(() -> service.edit(DOC_ID, SEGMENT_ID, "字".repeat(8001), true))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("最长 8000 字")
                .hasMessageContaining("8001");
        verifyNoInteractions(segments);
    }

    /** doc_id 进 WHERE 之后，拿着别人的 segmentId 改块会得到 0 行，这里必须翻成 404 而不是空指针。 */
    @Test
    void aBlockThatIsNotPartOfTheDocumentIsNotFound() {
        stubDocument(DocStatus.READY);
        when(segments.editContent(DOC_ID, SEGMENT_ID, "改过的正文")).thenReturn(0);
        SegmentEditService service = service();

        assertThatThrownBy(() -> service.edit(DOC_ID, SEGMENT_ID, "改过的正文", true))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("不属于文档");
        verify(segments, never()).findView(anyLong(), anyLong());
    }

    /** 父块没有向量是设计而不是缺陷：给它算向量会让 onlySearchable 的过滤把上下文块当检索块用。 */
    @Test
    void editingAParentBlockSavesTextWithoutAnyEmbeddingCall() {
        stubDocument(DocStatus.READY);
        when(segments.editContent(anyLong(), anyLong(), anyString())).thenReturn(1);
        when(segments.findView(DOC_ID, SEGMENT_ID)).thenReturn(Optional.of(segment("SKIPPED")));
        SegmentEditService service = service();

        SegmentEditService.EditResult result = service.edit(DOC_ID, SEGMENT_ID, "父块正文", true);

        assertThat(result.embedded()).isFalse();
        assertThat(result.note()).contains("父块");
        assertThat(embeddingModel.embedded).isEmpty();
        verify(segments, never()).setEmbedding(anyLong(), anyLong(), any(), any());
    }

    @Test
    void skippingReembedKeepsTheBlockPendingAndNamesTheConsequence() {
        stubDocument(DocStatus.READY);
        when(segments.editContent(anyLong(), anyLong(), anyString())).thenReturn(1);
        when(segments.findView(DOC_ID, SEGMENT_ID)).thenReturn(Optional.of(segment("PENDING")));
        SegmentEditService service = service();

        SegmentEditService.EditResult result = service.edit(DOC_ID, SEGMENT_ID, "改过的正文", false);

        assertThat(result.embedded()).isFalse();
        assertThat(result.note()).contains("关键词检索命中");
        assertThat(embeddingModel.embedded).isEmpty();
    }

    /** 入库进行中改块，等这一代发布时旧块整批删除，改动会静默消失——界面却已经报过「保存成功」。 */
    @Test
    void editingWhileTheDocumentIsMidIngestIsRefused() {
        stubDocument(DocStatus.EMBEDDING);
        SegmentEditService service = service();

        assertThatThrownBy(() -> service.edit(DOC_ID, SEGMENT_ID, "改过的正文", true))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("文档正在入库");
        verifyNoInteractions(segments);
    }

    @Test
    void reembedCoversABlockLeftPendingByAnEarlierFailure() {
        stubDocument(DocStatus.FAILED);
        when(segments.findView(DOC_ID, SEGMENT_ID))
                .thenReturn(Optional.of(segment("PENDING")), Optional.of(segment("READY")));
        SegmentEditService service = service();

        SegmentEditService.EditResult result = service.reembed(DOC_ID, SEGMENT_ID);

        assertThat(result.embedded()).isTrue();
        assertThat(result.segment().embStatus()).isEqualTo("READY");
        verify(segments).setEmbedding(eq(DOC_ID), eq(SEGMENT_ID), anyString(), eq("bge-base-zh-v1.5"));
    }

    @Test
    void aMissingDocumentFailsBeforeAnyBlockLookup() {
        when(documents.findById(DOC_ID)).thenReturn(Optional.empty());
        SegmentEditService service = service();

        assertThatThrownBy(() -> service.reembed(DOC_ID, SEGMENT_ID))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("文档不存在");
        verifyNoInteractions(segments);
    }

    private void stubDocument(DocStatus status) {
        when(documents.findById(DOC_ID)).thenReturn(Optional.of(new Document(DOC_ID, 1L, "重构方案.md",
                "sha", "kb1/x.md", 1L, "text/markdown", 1, 100, status, 100, "入库完成", 3, 1,
                null, null, null)));
    }

    private SegmentView segment(String embStatus) {
        return segment(embStatus, "改过的正文");
    }

    private SegmentView segment(String embStatus, String content) {
        return new SegmentView(SEGMENT_ID, 2, null, content, null, 1, 1, 0, 6, true, embStatus,
                "PENDING".equals(embStatus), 5);
    }

    private SegmentEditService service() {
        RagProperties props = new RagProperties(
                new RagProperties.LmStudio("http://localhost:1234/v1", "lm-studio", "qwen2.5-vl-7b-instruct",
                        "bge-base-zh-v1.5", 0.3, 0.8, null, Duration.ofSeconds(5), 1),
                new RagProperties.Storage("./target/segment-edit-test-storage"),
                new RagProperties.Embedding(DIM, 2),
                RagProperties.Ingest.defaults(), RagProperties.Retrieval.defaults(),
                RagProperties.Archive.defaults());
        return new SegmentEditService(documents, segments, embeddingModel, props);
    }

    /** 只实现 embedAll 的最小模型：记录送进去的文字，按需返回指定维度或直接失败。 */
    private static final class StubEmbedding implements EmbeddingModel {

        private final List<String> embedded = new ArrayList<>();
        private int dim = DIM;
        private boolean fail;

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            for (TextSegment segment : textSegments) {
                embedded.add(segment.text());
            }
            if (fail) {
                throw new IllegalStateException("LM Studio 未启动");
            }
            List<Embedding> vectors = new ArrayList<>(textSegments.size());
            for (int i = 0; i < textSegments.size(); i++) {
                float[] vector = new float[dim];
                vector[0] = 0.5f;
                vectors.add(new Embedding(vector));
            }
            return Response.from(vectors);
        }
    }
}
