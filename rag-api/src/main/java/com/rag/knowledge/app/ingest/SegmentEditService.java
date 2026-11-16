package com.rag.knowledge.app.ingest;

import com.rag.knowledge.app.diag.RunDiagnostics;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.DocumentRepository;
import com.rag.knowledge.db.SegmentRepository;
import com.rag.knowledge.domain.DocStatus;
import com.rag.knowledge.domain.Document;
import com.rag.knowledge.domain.SegmentView;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 单块人工编辑与单块补算向量。
 * <p>
 * 这一层存在的理由是「一整篇重算」太贵：改一个错别字或一段被解析弄乱的表格，代价是整篇文档重新 embedding。
 * 这里只动一块，向量当场补算，补不上就留 PENDING 让关键词检索先顶上。
 */
@Service
public class SegmentEditService {

    private static final Logger log = LoggerFactory.getLogger(SegmentEditService.class);

    /**
     * 手工编辑的正文上限。比父块上限宽一截是给「把两段并到一起」留余地，再长就该去调分块参数重新入库，
     * 而不是把一块塞成一篇小文章——它进上下文时会挤掉别的来源，而且一定会被模型的 512 token 窗口截断。
     */
    static final int MAX_CONTENT_CHARS = 8000;

    private final DocumentRepository documents;
    private final SegmentRepository segments;
    private final EmbeddingModel embeddingModel;
    private final RagProperties props;
    private final RunDiagnostics diagnostics;

    public SegmentEditService(DocumentRepository documents, SegmentRepository segments,
                              EmbeddingModel embeddingModel, RagProperties props, RunDiagnostics diagnostics) {
        this.documents = documents;
        this.segments = segments;
        this.embeddingModel = embeddingModel;
        this.props = props;
        this.diagnostics = diagnostics;
    }

    /**
     * 编辑与补算的返回。`note` 不是错误信息而是「事情办到哪一步」：正文已经存下了、向量没补上，
     * 这种情况必须是 200 加一句说明，把用户的主操作（改文字）判成失败会让人以为改动丢了。
     */
    public record EditResult(SegmentView segment, boolean embedded, String note) {
    }

    public EditResult edit(long docId, long segmentId, String content, boolean reembed) {
        requireIdleDocument(docId);
        String cleaned = TextCleaner.normalize(content == null ? "" : content).trim();
        if (cleaned.isEmpty()) {
            throw RagException.badRequest("块正文不能为空：不想让这一块进检索，请用它「检索」开关（available=false）");
        }
        if (cleaned.length() > MAX_CONTENT_CHARS) {
            throw RagException.badRequest("块正文最长 " + MAX_CONTENT_CHARS + " 字，当前 " + cleaned.length()
                    + " ｜ 更长说明该调分块参数重新入库，而不是把一块改成一篇小文章");
        }
        if (segments.editContent(docId, segmentId, cleaned) == 0) {
            throw RagException.notFound("块 " + segmentId + " 不属于文档 " + docId + "，或该块已被新版本替换");
        }
        SegmentView saved = requireSegment(docId, segmentId);
        log.info("文档 {} 的块 {} 正文被人工改为 {} 字", docId, saved.chunkNo(), cleaned.length());
        if (!reembed) {
            return new EditResult(saved, false, saved.parentBlock()
                    ? "父块正文已更新：它本来不参与向量召回，命中子块时按新正文展开"
                    : "正文已更新，向量留到下次补算：这段时间这一块只能靠关键词检索命中");
        }
        return embed(docId, saved);
    }

    /** 补算一块的向量：模型起来以后把编辑时没算成的块点回来。 */
    public EditResult reembed(long docId, long segmentId) {
        requireIdleDocument(docId);
        return embed(docId, requireSegment(docId, segmentId));
    }

    /**
     * 入库进行中不让改块：重新入库写的是影子代次，发布时整批删掉旧代次——
     * 这段时间改的文字会跟着旧代次一起消失，而界面已经告诉用户「保存成功」了。
     */
    private Document requireIdleDocument(long docId) {
        Document doc = documents.findById(docId)
                .orElseThrow(() -> RagException.notFound("文档不存在：" + docId));
        if (doc.status() != DocStatus.READY && doc.status() != DocStatus.FAILED) {
            throw RagException.conflict("文档正在入库（" + doc.status() + "），改块会被这一代的结果覆盖",
                    "等它到 READY 或 FAILED 再编辑；想中止就先重新排队一次，别在半路上改文字");
        }
        return doc;
    }

    private EditResult embed(long docId, SegmentView segment) {
        if (segment.parentBlock()) {
            return new EditResult(segment, false, "父块不生成向量：它只作为命中子块的上下文展开");
        }
        try {
            Response<Embedding> response = embeddingModel.embed(segment.content());
            Embedding vector = response.content();
            if (vector.dimension() != props.embedding().dim()) {
                String reason = "文档 " + docId + " 的块 " + segment.chunkNo() + " 返回 " + vector.dimension()
                        + " 维向量，与 rag.embedding.dim=" + props.embedding().dim() + " 不符";
                diagnostics.embeddingFailure(reason);
                log.warn("补算向量被拒：{}", reason);
                return new EditResult(segment, false, "向量维度 " + vector.dimension() + "，与配置的 "
                        + props.embedding().dim() + " 不一致，没有写入：先核对 LM_STUDIO_EMBEDDING_MODEL 与 rag.embedding.dim");
            }
            segments.setEmbedding(docId, segment.id(), SegmentRepository.toVectorLiteral(vector.vector()),
                    props.lmStudio().embeddingModel());
        } catch (RuntimeException e) {
            diagnostics.embeddingFailure(e.getMessage());
            log.warn("文档 {} 的块 {} 补算向量失败：{}", docId, segment.chunkNo(), e.getMessage());
            return new EditResult(segment, false, "正文已保存，向量没算出来：" + e.getMessage()
                    + " ｜ 关键词检索已经能用这一条，等模型服务可用时点「重算向量」");
        }
        return new EditResult(requireSegment(docId, segment.id()), true, null);
    }

    private SegmentView requireSegment(long docId, long segmentId) {
        return segments.findView(docId, segmentId)
                .orElseThrow(() -> RagException.notFound("块 " + segmentId + " 不属于文档 " + docId
                        + "，或该块已被新版本替换"));
    }
}
