package com.rag.knowledge.app.archive;

import com.rag.knowledge.app.archive.KbArchive.DocEntry;
import com.rag.knowledge.app.archive.KbArchive.Manifest;
import com.rag.knowledge.app.archive.KbArchive.SegmentEntry;
import com.rag.knowledge.app.archive.KbArchive.Source;
import com.rag.knowledge.app.ingest.FileStorage;
import com.rag.knowledge.app.ingest.IngestService;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.DocumentRepository;
import com.rag.knowledge.db.KnowledgeBaseRepository;
import com.rag.knowledge.db.KnowledgeBaseRepository.KnowledgeBase;
import com.rag.knowledge.domain.ArchivedSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 归档导入：读一个 .zip，两种模式之一落进目标库。
 * <p>
 * 模式只由向量空间决不决定得通这件事决定，不留给人猜：
 * <ul>
 *   <li><b>CARRIED</b>：包里的向量本机可用（带了向量、维度与本机的 schema 维度一致、模型名一致），
 *       原样落块、直接 READY，一次 embedding 都不花；</li>
 *   <li><b>REBUILT</b>：任何一条不满足，就只取正文与分块参数，走正常入库流水线重切重算。</li>
 * </ul>
 * 维度必须相等不是洁癖：{@code kb_segment.embedding} 是 {@code vector(dim)} 的列级约束，
 * 维度不同的向量根本插不进去；而模型名一致、维度也一致时（例如 bge 与 nomic 同为 768 维）向量空间
 * 其实不互通，那种库看着能检索、分数却全是噪声，所以模型名同样是判据。
 */
@Service
public class KbImportService {

    private static final Logger log = LoggerFactory.getLogger(KbImportService.class);
    private static final int MAX_NOTES = 40;

    /**
     * @param mode        CARRIED 或 REBUILT，整个包一个模式，逐篇失败只影响那一篇
     * @param queued      交给入库流水线重算的篇数（REBUILT 下正文已落库，检索要等 worker 跑完）
     * @param skippedSame 目标库里已有同哈希文档，按「不重复烧 embedding」的老规矩跳过
     */
    public record Summary(long kbId, String mode, int imported, int queued, int segmentsCarried,
                          int originalsRestored, int skippedSame, int skippedEmpty, int failed,
                          List<String> notes) {
    }

    private final RagProperties props;
    private final KnowledgeBaseRepository knowledgeBases;
    private final DocumentRepository documents;
    private final FileStorage storage;
    private final IngestService ingest;
    private final KbImportWriter writer;
    private final ObjectMapper json;

    public KbImportService(RagProperties props, KnowledgeBaseRepository knowledgeBases, DocumentRepository documents,
                           FileStorage storage, IngestService ingest, KbImportWriter writer, ObjectMapper json) {
        this.props = props;
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.storage = storage;
        this.ingest = ingest;
        this.writer = writer;
        this.json = json;
    }

    public Summary importArchive(long kbId, Path zipPath, boolean rebuild) {
        KnowledgeBase kb = requireKb(kbId);
        if (kb.embeddingDim() != props.embedding().dim()) {
            throw RagException.conflict("知识库 " + kb.id() + " 的向量维度是 " + kb.embeddingDim()
                    + "，当前配置 rag.embedding.dim=" + props.embedding().dim(),
                    "换回原 embedding 模型，或按新维度重建知识库再导入");
        }
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            Manifest manifest = readManifest(zip);
            return importManifest(kb, manifest, zip, rebuild);
        } catch (ZipException e) {
            throw RagException.badRequest("这个包不是有效的 zip：" + message(e)
                    + " ｜ 处理建议: 只能用本页「导出知识库」生成的 .zip，别的压缩结构（rar/7z/改名文件）认不出来");
        } catch (IOException e) {
            throw RagException.badRequest("读取归档包失败：" + message(e));
        }
    }

    private Summary importManifest(KnowledgeBase kb, Manifest manifest, ZipFile zip, boolean rebuild) {
        String rejection = rejectionReason(manifest, rebuild);
        boolean carried = rejection == null;
        List<String> notes = new ArrayList<>();
        if (!carried) {
            notes.add("向量不能直接用：" + rejection + "，本次按重新分块+重新向量化导入");
        }
        int imported = 0, queued = 0, segmentsCarried = 0, originals = 0, same = 0, empty = 0, failed = 0;
        for (DocEntry entry : manifest.documents() == null ? List.<DocEntry>of() : manifest.documents()) {
            int slot = entry.slot();
            try {
                String content = readContent(zip, slot);
                if (content.isBlank()) {
                    empty++;
                    continue;
                }
                String sha = shaOf(entry, content);
                if (documents.findBySha(kb.id(), sha).isPresent()) {
                    same++;
                    continue;
                }
                String filePath = restoreOriginal(kb.id(), manifest, zip, entry, slot, notes);
                if (filePath != null) {
                    originals++;
                }
                List<ArchivedSegment> rows = carried ? readSegments(zip, slot, entry) : List.of();
                long docId;
                if (carried) {
                    docId = writer.writeReady(kb.id(), entry, content, filePath, rows, kb.embeddingModel(),
                            kb.indexVersion());
                    imported++;
                    segmentsCarried += rows.size();
                } else {
                    docId = writer.writePending(kb.id(), entry, content, filePath);
                    ingest.reindex(docId, false);
                    queued++;
                }
            } catch (RuntimeException e) {
                failed++;
                note(notes, "「" + KbArchive.safeFileName(entry.name()) + "」导入失败：" + message(e));
            }
        }
        Summary result = new Summary(kb.id(), carried ? "CARRIED" : "REBUILT", imported, queued, segmentsCarried,
                originals, same, empty, failed, notes);
        log.info("归档导入完成：库 {} 模式 {}，导入 {} 篇（其中排队重算 {}），跳过同哈希 {}，空正文 {}，失败 {}，恢复原文件 {}",
                kb.id(), result.mode(), imported, queued, same, empty, failed, originals);
        return result;
    }

    /** 包里没带原始文件是常态（{@code files=false} 导出的包就只有正文），这种情况静默返回 null。 */
    private String restoreOriginal(long kbId, Manifest manifest, ZipFile zip, DocEntry entry, int slot,
                                   List<String> notes) {
        if (!manifest.files()) {
            return null;
        }
        byte[] bytes = readEntry(zip, KbArchive.fileEntry(slot, entry.name()));
        if (bytes == null) {
            return null;
        }
        String stored = FileStorage.sha256(bytes);
        if (entry.sha256() != null && !entry.sha256().equalsIgnoreCase(stored)) {
            note(notes, "「" + KbArchive.safeFileName(entry.name())
                    + "」包内原始文件与清单哈希不符，只落了正文与分块（以后不能重新解析原文件）");
            return null;
        }
        return storage.store(kbId, KbArchive.safeFileName(entry.name()), bytes).relativePath();
    }

    /** 正文条目缺失按空串处理：调用方会把这篇算进 skippedEmpty，而不是在 zip 解析层抛一个看不懂的错。 */
    private String readContent(ZipFile zip, int slot) {
        byte[] bytes = readEntry(zip, KbArchive.contentEntry(slot));
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private List<ArchivedSegment> readSegments(ZipFile zip, int slot, DocEntry entry) {
        if (entry.segmentCount() <= 0) {
            throw new IllegalStateException("清单说这篇有 " + entry.segmentCount() + " 块，包里却没有分块文件");
        }
        byte[] bytes = readEntry(zip, KbArchive.segmentsEntry(slot));
        if (bytes == null) {
            throw new IllegalStateException("包里缺 " + KbArchive.segmentsEntry(slot));
        }
        List<ArchivedSegment> rows = new ArrayList<>(entry.segmentCount());
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            rows.add(fromEntry(json.readValue(line, SegmentEntry.class)));
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("分块文件里一行都没解析出来");
        }
        return rows;
    }

    /**
     * 维度校验放在这里而不是等 INSERT 报数据库错：pgvector 对维度不符是整条语句失败，
     * 报错信息里不会告诉你是哪一块，而这时文档行已经建好了。
     */
    private ArchivedSegment fromEntry(SegmentEntry row) {
        float[] vector = VectorCodec.decode(row.embedding());
        if (vector != null && vector.length != props.embedding().dim()) {
            throw new IllegalStateException("块 " + row.chunkNo() + " 的向量是 " + vector.length + " 维，本机是 "
                    + props.embedding().dim() + " 维");
        }
        return new ArchivedSegment(row.chunkNo(), row.parentChunkNo(), row.content(), row.headingPath(),
                row.pageStart(), row.pageEnd(), row.charStart(), row.charEnd(), row.available(),
                row.embStatus() == null ? "READY" : row.embStatus(), row.editedAt(), vector);
    }

    private Manifest readManifest(ZipFile zip) throws IOException {
        byte[] bytes = readEntry(zip, KbArchive.MANIFEST_ENTRY);
        if (bytes == null) {
            throw RagException.badRequest("包里缺 " + KbArchive.MANIFEST_ENTRY
                    + " ｜ 原因: 这不是本系统导出的知识库归档包（zip 只是容器）");
        }
        Manifest manifest = json.readValue(new String(bytes, StandardCharsets.UTF_8), Manifest.class);
        if (manifest.format() > KbArchive.FORMAT_VERSION) {
            throw RagException.badRequest("包格式是 v" + manifest.format() + "，当前服务只认到 v"
                    + KbArchive.FORMAT_VERSION + " ｜ 处理建议: 先升级 rag-api 再导入"
                    + "（旧包往新服务导是兼容的，反过来不保证）");
        }
        return manifest;
    }

    /** null 表示「向量可以直接用」；否则返回的原因会原样出现在导入结果的说明里。 */
    private String rejectionReason(Manifest manifest, boolean rebuild) {
        if (rebuild) {
            return "调用方指定 rebuild=true";
        }
        if (!manifest.vectors()) {
            return "导出时没带向量";
        }
        Source source = manifest.source();
        if (source == null) {
            return "包里没写来源模型与维度";
        }
        if (source.embeddingDim() != props.embedding().dim()) {
            return "包内向量是 " + source.embeddingDim() + " 维，本机 schema 是 " + props.embedding().dim() + " 维";
        }
        String expected = props.lmStudio().embeddingModel();
        if (source.embeddingModel() == null || expected == null
                || !source.embeddingModel().equalsIgnoreCase(expected.trim())) {
            return "包内向量出自 " + source.embeddingModel() + "，本机配置是 " + expected + "（维度相同也不等于向量空间相同）";
        }
        return null;
    }

    /** 老包或手工拼的包可能没写 sha：退化成正文哈希，至少同哈希去重这条规矩仍然成立。 */
    private static String shaOf(DocEntry entry, String content) {
        if (entry.sha256() != null && !entry.sha256().isBlank()) {
            return entry.sha256();
        }
        return FileStorage.sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private KnowledgeBase requireKb(long kbId) {
        try {
            return knowledgeBases.require(kbId);
        } catch (RuntimeException e) {
            throw RagException.notFound("知识库不存在：" + kbId);
        }
    }

    private static byte[] readEntry(ZipFile zip, String name) {
        java.util.zip.ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读取包内条目失败：" + name, e);
        }
    }

    private static void note(List<String> notes, String text) {
        if (notes.size() < MAX_NOTES) {
            notes.add(text);
        } else if (notes.size() == MAX_NOTES) {
            notes.add("其余说明过多已省略（共 " + MAX_NOTES + "+ 条）");
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
