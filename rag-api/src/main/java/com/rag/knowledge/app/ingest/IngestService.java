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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 入库流水线：解析 → 清洗 → 分块 → 向量化 → 索引切换。
 * <p>
 * 慢的部分（解析、向量化）放在事务外，只有最后的索引切换是一个事务；
 * 进度按阶段写回 kb_document，前端轮询拿到的永远是「当前在干什么」而不是一条转圈动画。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    public record UploadResult(long documentId, long taskId, boolean duplicated, DocStatus status) {
    }

    private final RagProperties props;
    private final KnowledgeBaseRepository knowledgeBases;
    private final DocumentRepository documents;
    private final TaskRepository tasks;
    private final FileStorage storage;
    private final TikaDocumentParser parser;
    private final ChineseSemanticSplitter splitter;
    private final EmbeddingModel embeddingModel;
    private final SegmentIndexPublisher publisher;

    public IngestService(RagProperties props, KnowledgeBaseRepository knowledgeBases, DocumentRepository documents,
                         TaskRepository tasks, FileStorage storage, TikaDocumentParser parser,
                         ChineseSemanticSplitter splitter, EmbeddingModel embeddingModel,
                         SegmentIndexPublisher publisher) {
        this.props = props;
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.tasks = tasks;
        this.storage = storage;
        this.parser = parser;
        this.splitter = splitter;
        this.embeddingModel = embeddingModel;
        this.publisher = publisher;
    }

    /** 单库起步：没有库就建默认库，维度与当前配置对齐。 */
    public long defaultKbId() {
        return knowledgeBases
                .ensureDefault("默认知识库", props.lmStudio().embeddingModel(), props.embedding().dim())
                .id();
    }

    public UploadResult upload(long kbId, String fileName, String contentType, byte[] content) {
        // 先做纯输入校验：不合规的文件没必要再查一次库，报错也更贴近用户真正做错的事
        validate(fileName, content);
        KnowledgeBase kb = knowledgeBases.require(kbId);
        if (kb.embeddingDim() != props.embedding().dim()) {
            throw RagException.conflict("知识库 " + kb.id() + " 的向量维度是 " + kb.embeddingDim()
                    + "，当前配置 rag.embedding.dim=" + props.embedding().dim(),
                    "换回原 embedding 模型，或按新维度重建知识库并重新入库全部文档");
        }
        FileStorage.Stored stored = storage.store(kbId, fileName, content);

        Optional<Document> duplicate = documents.findBySha(kbId, stored.sha256());
        if (duplicate.isPresent()) {
            Document existing = duplicate.get();
            if (existing.status() == DocStatus.READY) {
                return new UploadResult(existing.id(), 0, true, existing.status());
            }
            if (tasks.findActiveForDoc(TaskRepository.TYPE_INGEST, existing.id()).isPresent()) {
                return new UploadResult(existing.id(), 0, true, existing.status());
            }
            long taskId = enqueueIngest(kbId, existing.id());
            return new UploadResult(existing.id(), taskId, true, existing.status());
        }

        long docId = documents.insert(kbId, safeName(fileName), stored.sha256(), stored.relativePath(),
                stored.size(), contentType);
        long taskId = enqueueIngest(kbId, docId);
        return new UploadResult(docId, taskId, false, DocStatus.PENDING);
    }

    /** 重新入库：换分块参数或模型出问题后的标准自救手段。 */
    public long reindex(long docId) {
        return reindex(docId, false);
    }

    /**
     * @param reparse 重新跑一次 Tika。默认 false 直接用库里已存的正文，
     *                只有清洗/解析规则改了或怀疑正文本身有问题时才需要。
     */
    public long reindex(long docId, boolean reparse) {
        Document doc = documents.findById(docId)
                .orElseThrow(() -> RagException.notFound("文档不存在：" + docId));
        if (!doc.rerunnable()) {
            throw RagException.conflict("文档「" + doc.name() + "」正在入库中（" + doc.status() + "），不能重复排队");
        }
        return enqueueIngest(doc.kbId(), docId, reparse);
    }

    private long enqueueIngest(long kbId, long docId) {
        return enqueueIngest(kbId, docId, false);
    }

    public record Preview(int pageCount, int totalBlocks, int searchableBlocks,
                          List<ChineseSemanticSplitter.Segment> blocks) {
    }

    /**
     * 分块预览：拿库里已存的正文按给定参数重切一遍，不写库、不调模型。
     * 前端调参靠它，否则每次试参数都要花一遍 embedding 的钱。
     */
    public Preview preview(long docId, Integer childChars, Integer childMinChars, Integer overlapChars,
                           Integer parentMaxChars, Boolean parentChild, int limit) {
        Document doc = documents.findById(docId)
                .orElseThrow(() -> RagException.notFound("文档不存在：" + docId));
        String cleaned = documents.content(docId);
        if (cleaned == null || cleaned.isBlank()) {
            throw RagException.conflict("文档「" + doc.name() + "」还没有可预览的正文",
                    "等入库到 READY 再预览；FAILED 时用 POST /api/documents/" + docId + "/reindex 重试");
        }
        RagProperties.Chunk defaults = props.ingest().chunk();
        int child = orDefault(childChars, defaults.childChars());
        int min = orDefault(childMinChars, defaults.childMinChars());
        int overlap = orDefault(overlapChars, defaults.overlapChars());
        int parent = orDefault(parentMaxChars, defaults.parentMaxChars());
        // 边界与 ChunkConfig 保持一致，否则预览会把 IllegalArgumentException 顶成 500
        if (child < 50 || child > 4000 || min <= 0 || min > child || overlap < 0 || overlap >= child
                || parent < child) {
            throw RagException.badRequest("分块参数不合理：child-chars=" + child + "，child-min-chars=" + min
                    + "，overlap-chars=" + overlap + "，parent-max-chars=" + parent
                    + " ｜ 约束: 50<=child<=4000，0<min<=child，overlap<child，parent>=child"
                    + "（子块超过 400 字会被 bge 的 512 token 窗口静默截断）");
        }
        List<ChineseSemanticSplitter.Segment> segments = splitter.split(cleaned,
                new ChineseSemanticSplitter.ChunkConfig(child, min, overlap, parent,
                        parentChild == null ? defaults.parentChild() : parentChild));
        int capped = Math.max(1, Math.min(limit, 200));
        long searchable = segments.stream().filter(ChineseSemanticSplitter.Segment::searchable).count();
        return new Preview(doc.pageCount() == null ? 1 : doc.pageCount(), segments.size(), (int) searchable,
                segments.stream().limit(capped).toList());
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private long enqueueIngest(long kbId, long docId, boolean reparse) {
        return tasks.enqueue(TaskRepository.TYPE_INGEST, kbId, docId,
                reparse ? "{\"reparse\":true}" : "{}", props.ingest().maxAttempts());
    }

    /** payload 目前只有 reparse 一个开关，字符串匹配够用；为一个布尔引 ObjectMapper 不划算。 */
    private static boolean wantsReparse(String payload) {
        return payload != null && payload.replace(" ", "").contains("\"reparse\":true");
    }

    /**
     * 删除文档：软删 + 取消排队任务，块数据留着。
     * 立刻物理删块会让「误删后恢复」不可能，而本地磁盘上这点数据根本不是压力。
     */
    public void delete(long docId) {
        Document doc = documents.findById(docId)
                .orElseThrow(() -> RagException.notFound("文档不存在：" + docId));
        int cancelled = tasks.cancelForDoc(doc.id());
        documents.softDelete(docId);
        log.info("文档 {} 已删除（取消任务 {} 个）", docId, cancelled);
    }

    /** worker 与手工重试共用这个入口：失败记账都收在这里。 */
    public void process(Task task, String owner) {
        if (task.docId() == null) {
            tasks.abandon(task.id(), owner, "BAD_PAYLOAD", "任务缺少 doc_id");
            return;
        }
        long docId = task.docId();
        try {
            run(docId, wantsReparse(task.payload()));
            tasks.finish(task.id(), owner);
        } catch (PermanentIngestException e) {
            log.warn("文档 {} 永久失败 [{}]: {}", docId, e.errorCode(), e.getMessage());
            documents.markFailed(docId, e.errorCode(), e.getMessage());
            tasks.abandon(task.id(), owner, e.errorCode(), e.getMessage());
        } catch (RuntimeException e) {
            boolean willRetry = tasks.fail(task.id(), owner, e.getClass().getSimpleName(), message(e), backoff(task));
            documents.increaseRetry(docId);
            if (!willRetry) {
                documents.markFailed(docId, e.getClass().getSimpleName(), message(e));
            }
            log.warn("文档 {} 入库失败（{}），{}", docId, message(e), willRetry ? "稍后重试" : "不再重试");
        }
    }

    private void run(long docId, boolean reparse) {
        long startedAt = System.currentTimeMillis();
        Document doc = documents.findById(docId)
                .orElseThrow(() -> new PermanentIngestException("DOC_GONE", "文档已不存在，任务作废"));
        DocStatus current = doc.status();
        KnowledgeBase kb = knowledgeBases.require(doc.kbId());
        if (kb.embeddingDim() != props.embedding().dim()) {
            throw new PermanentIngestException("DIM_MISMATCH", "知识库向量维度 " + kb.embeddingDim()
                    + " 与配置 " + props.embedding().dim() + " 不一致，先改回模型或重建知识库");
        }

        // 正文落库后就不再需要原文件：重新分块只花 embedding 的钱，不重跑 Tika。
        // 存的是清洗后、仍带 '\f' 分页符的文本，页码和分块偏移量都能从它复原。
        String cleaned = reparse ? null : documents.content(docId);
        if (cleaned == null || cleaned.isBlank()) {
            current = stage(docId, current, DocStatus.PARSING, 5, "解析文本");
            Parsed parsed = parser.parse(storage.read(doc.filePath()), doc.name());
            cleaned = TextCleaner.normalize(parsed.rawText());
            try {
                TextCleaner.rejectGarbled(cleaned, doc.name());
            } catch (TextCleaner.GarbledDocumentException e) {
                throw new PermanentIngestException("GARBLED_TEXT", e.getMessage());
            }
            documents.saveParsed(docId, cleaned, parsed.pageCount(), parsed.mime());
        } else {
            current = stage(docId, current, DocStatus.PARSING, 5,
                    "复用已解析正文 " + cleaned.length() + " 字");
        }

        current = stage(docId, current, DocStatus.SPLITTING, 30, "语义分块");
        List<ChineseSemanticSplitter.Segment> segments = splitter.split(cleaned, chunkConfig());
        if (segments.isEmpty()) {
            throw new PermanentIngestException("NO_SEGMENT", "分块后没有任何可入库文本块"
                    + " ｜ 处理建议: 检查文件是否只有图片或页眉页脚，或调小 rag.ingest.chunk.child-min-chars");
        }
        long searchableCount = segments.stream().filter(ChineseSemanticSplitter.Segment::searchable).count();

        current = stage(docId, current, DocStatus.EMBEDDING, 40,
                "向量化 " + searchableCount + " 块（batch=" + props.embedding().batchSize() + "）");
        List<SegmentDraft> drafts = embedAll(docId, current, segments);

        stage(docId, current, DocStatus.INDEXING, 88, "写入索引");
        List<int[]> links = new ArrayList<>();
        for (SegmentDraft draft : drafts) {
            if (draft.parentChunkNo() != null) {
                links.add(new int[] { draft.chunkNo(), draft.parentChunkNo() });
            }
        }
        int targetVersion = doc.version() + 1;
        SegmentIndexPublisher.Result result = publisher.publish(docId, doc.kbId(), targetVersion,
                props.lmStudio().embeddingModel(), kb.indexVersion(), drafts, links);

        log.info("文档 {} 入库完成：{} 块（检索块 {}，父块 {}），版本 {}，用时 {}ms",
                docId, result.publishedRows(), searchableCount, result.publishedRows() - searchableCount,
                targetVersion, System.currentTimeMillis() - startedAt);
    }

    /**
     * 按批向量化，批大小来自配置（实测 16 条一批 0.4s，再大就顶到 LM Studio 的处理上限）。
     * 每条都校验维度：模型被换掉时维度可能不变但空间不同，这里至少能挡住维度变化的那一种。
     */
    private List<SegmentDraft> embedAll(long docId, DocStatus current, List<ChineseSemanticSplitter.Segment> segments) {
        List<SegmentDraft> drafts = new ArrayList<>(segments.size());
        List<ChineseSemanticSplitter.Segment> searchable = segments.stream()
                .filter(ChineseSemanticSplitter.Segment::searchable).toList();
        float[][] vectors = new float[searchable.size()][];

        int batchSize = Math.max(1, props.embedding().batchSize());
        for (int from = 0; from < searchable.size(); from += batchSize) {
            int to = Math.min(from + batchSize, searchable.size());
            List<TextSegment> texts = searchable.subList(from, to).stream()
                    .map(segment -> TextSegment.from(segment.content()))
                    .toList();
            Response<List<Embedding>> response = embeddingModel.embedAll(texts);
            List<Embedding> batch = response.content();
            if (batch.size() != texts.size()) {
                throw new IllegalStateException("embedding 返回 " + batch.size() + " 条，提交 " + texts.size()
                        + " 条：模型服务在批量请求下行为异常，尝试调小 rag.embedding.batch-size");
            }
            for (int i = 0; i < batch.size(); i++) {
                Embedding embedding = batch.get(i);
                if (embedding.dimension() != props.embedding().dim()) {
                    throw new PermanentIngestException("DIM_MISMATCH", "模型返回 " + embedding.dimension()
                            + " 维，配置是 " + props.embedding().dim()
                            + " ｜ 处理建议: 检查 LM_STUDIO_EMBEDDING_MODEL 与 rag.embedding.dim 是否配套");
                }
                vectors[from + i] = embedding.vector();
            }
            int progress = 40 + (int) (45.0 * to / searchable.size());
            documents.updateStage(docId, current, Math.min(progress, 85),
                    "向量化 " + to + "/" + searchable.size() + " 块");
        }

        int cursor = 0;
        for (ChineseSemanticSplitter.Segment segment : segments) {
            float[] vector = null;
            if (segment.searchable()) {
                vector = vectors[cursor++];
            }
            drafts.add(new SegmentDraft(segment.content(), segment.chunkNo(), segment.parentChunkNo(),
                    segment.headingPath(), segment.pageStart(), segment.pageEnd(), segment.charStart(),
                    segment.charEnd(), segment.searchable(), vector));
        }
        return drafts;
    }

    private DocStatus stage(long docId, DocStatus from, DocStatus to, int progress, String message) {
        if (!to.canFollowFrom(from)) {
            throw new IllegalStateException("非法状态迁移 " + from + " -> " + to + "，文档 id=" + docId);
        }
        documents.updateStage(docId, to, progress, message);
        return to;
    }

    private ChineseSemanticSplitter.ChunkConfig chunkConfig() {
        RagProperties.Chunk chunk = props.ingest().chunk();
        return new ChineseSemanticSplitter.ChunkConfig(chunk.childChars(), chunk.childMinChars(),
                chunk.overlapChars(), chunk.parentMaxChars(), chunk.parentChild());
    }

    private Duration backoff(Task task) {
        long seconds = 5L << Math.min(task.attempt() - 1, 6);
        return Duration.ofSeconds(seconds);
    }

    private void validate(String fileName, byte[] content) {
        if (fileName == null || fileName.isBlank()) {
            throw RagException.badRequest("文件名不能为空");
        }
        if (content == null || content.length == 0) {
            throw RagException.badRequest("文件内容为空：" + fileName);
        }
        long maxBytes = (long) props.ingest().maxFileSizeMb() * 1024 * 1024;
        if (content.length > maxBytes) {
            throw RagException.badRequest("文件超过 " + props.ingest().maxFileSizeMb()
                    + "MB 限制（rag.ingest.max-file-size-mb）");
        }
        String ext = FileStorage.extensionOf(fileName);
        List<String> allowed = props.ingest().allowedExtensions();
        if (ext.isEmpty() || allowed.stream().noneMatch(candidate -> ext.equalsIgnoreCase("." + candidate))) {
            throw RagException.badRequest("不支持的文件类型：" + fileName
                    + " ｜ 允许的类型: " + String.join("/", allowed));
        }
    }

    private static String safeName(String fileName) {
        String name = fileName == null ? "untitled" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getName() : e.getMessage();
    }
}
