package com.rag.knowledge.app.archive;

import com.rag.knowledge.app.ingest.FileStorage;
import com.rag.knowledge.app.ingest.IngestService;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.DocumentRepository;
import com.rag.knowledge.db.KnowledgeBaseRepository;
import com.rag.knowledge.db.KnowledgeBaseRepository.KnowledgeBase;
import com.rag.knowledge.db.SegmentRepository;
import com.rag.knowledge.domain.ArchivedSegment;
import com.rag.knowledge.domain.DocStatus;
import com.rag.knowledge.domain.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出→导入整包往返。这是 M5d 唯一能不连数据库就跑的证明：包格式、维度判据，以及
 * 「人工关掉/人工改过的块在换机器之后仍然是关掉/改过的」——这三件事错在包里，界面上完全看不出来。
 * <p>
 * 目标端用记账假件而不是逐参数 verify：一次插入十二个参数，按位置断言会把测试写成第二份仓储代码。
 */
class KbArchiveRoundTripTest {

    private static final int DIM = 4;
    private static final String MODEL = "bge-base-zh-v1.5";
    private static final long KB_ID = 1L;
    private static final String TEXT_A = "架构说明：混合检索把向量路与关键词路的候选做 RRF 融合。\n\f第二页开始。";
    private static final String TEXT_B = "运维手册：备份目录在 storage 下，按库号分文件夹。";

    @TempDir
    Path temp;

    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    void carriedRoundTripKeepsDisabledBlocksParentsAndManualEdits() throws IOException {
        byte[] original = "伪造但哈希一致的原始文件".getBytes(StandardCharsets.UTF_8);
        FileStorage storage = new FileStorage(props(MODEL));
        FileStorage.Stored stored = storage.store(KB_ID, "架构白皮书.pdf", original);
        KnowledgeBase kb = new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 7);
        float[] vector1 = {0.1f, -0.2f, 0.3f, 0.4f};
        float[] vector2 = {0.5f, 0.6f, -0.7f, 0.8f};
        float[] vector3 = {-0.9f, 0.25f, 0.125f, 0.75f};

        ArchivedSegment parent = new ArchivedSegment(0, null, "父块：整节原文", "架构 > 检索", 1, 2, 0, 900,
                true, "SKIPPED", null, null);
        ArchivedSegment childOn = new ArchivedSegment(1, 0, "子块一", "架构 > 检索", 1, 1, 0, 400,
                true, "READY", null, vector1);
        ArchivedSegment childOff = new ArchivedSegment(2, 0, "子块二", "架构 > 检索", 2, 2, 400, 800,
                false, "READY", null, vector2);
        ArchivedSegment editedChild = new ArchivedSegment(3, null, "人工改过的子块", "运维", 1, 1, 0, 300,
                true, "READY", "2026-11-01 09:00:00+00", vector3);

        Path zip = export(kb, List.of(
                        doc(10, "架构白皮书.pdf", stored.sha256(), 2, DocStatus.READY, stored.relativePath()),
                        doc(11, "运维手册.md", "sha-b", 1, DocStatus.READY, null),
                        doc(12, "还在入库.md", "sha-c", 0, DocStatus.EMBEDDING, null)),
                Map.of(10, TEXT_A, 11, TEXT_B),
                Map.of(10, List.of(parent, childOn, childOff), 11, List.of(editedChild)));

        RecordingDocuments targetDocs = new RecordingDocuments();
        RecordingSegments targetSegments = new RecordingSegments();
        IngestService ingest = mock(IngestService.class);
        KbImportService service = importService(props(MODEL), kb, targetDocs, targetSegments, storage, ingest);

        KbImportService.Summary summary = service.importArchive(KB_ID, zip, false);

        assertThat(summary.mode()).isEqualTo("CARRIED");
        assertThat(summary.imported()).isEqualTo(2);
        assertThat(summary.queued()).isZero();
        assertThat(summary.failed()).isZero();
        assertThat(summary.notes()).isEmpty();
        assertThat(summary.segmentsCarried()).isEqualTo(4);
        assertThat(summary.originalsRestored()).isEqualTo(1);
        verify(ingest, never()).reindex(anyLong(), anyBoolean());

        assertThat(targetDocs.inserted).hasSize(2);
        RecordingDocuments.Row first = targetDocs.inserted.get(0);
        assertThat(first.status()).isEqualTo(DocStatus.READY);
        // 版本跟着包走：源库重算过两次，包里的块就是第 2 代，压回 1 会让旧一代块再也清不掉
        assertThat(first.version()).isEqualTo(2);
        assertThat(first.indexVersion()).isEqualTo(7);
        assertThat(first.filePath()).isNotNull();
        assertThat(targetDocs.inserted.get(1).filePath()).isNull();
        assertThat(targetDocs.inserted.get(1).content()).isEqualTo(TEXT_B);

        List<ArchivedSegment> restored = targetSegments.generations.get(0);
        assertThat(restored).extracting(ArchivedSegment::content)
                .containsExactly("父块：整节原文", "子块一", "子块二");
        assertThat(restored.get(0).embStatus()).isEqualTo("SKIPPED");
        assertThat(restored.get(0).embedding()).isNull();
        // 界面上手工关掉检索的块，导入后必须还是关着的：available 是人工意图，不是可以由代码推导的状态
        assertThat(restored.get(1).available()).isTrue();
        assertThat(restored.get(2).available()).isFalse();
        assertThat(restored.get(1).embedding()).containsExactly(vector1);
        assertThat(restored.get(2).embedding()).containsExactly(vector2);
        assertThat(targetSegments.generations.get(1).get(0).editedAt()).isEqualTo("2026-11-01 09:00:00+00");
        assertThat(pairs(targetSegments.links.get(0))).containsExactlyInAnyOrder("1->0", "2->0");
    }

    @Test
    void aDifferentEmbeddingModelTurnsTheWholeImportIntoReingest() throws IOException {
        Path zip = export(new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 3),
                List.of(doc(10, "a.md", "sha-a", 1, DocStatus.READY, null)), Map.of(10, TEXT_A),
                Map.of(10, List.of(new ArchivedSegment(0, null, "子块", null, 1, 1, 0, 10, true, "READY", null,
                        new float[] {0.1f, 0.2f, 0.3f, 0.4f}))));

        RecordingDocuments targetDocs = new RecordingDocuments();
        RecordingSegments targetSegments = new RecordingSegments();
        IngestService ingest = mock(IngestService.class);
        RagProperties nomic = props("nomic-embed-text-v1.5");
        // 同维度不同模型：数据库不会拒绝写入，检索却全是噪声，所以判据必须是模型名而不只是维度
        KbImportService service = importService(nomic,
                new KnowledgeBase(KB_ID, "默认知识库", "nomic-embed-text-v1.5", DIM, 3), targetDocs, targetSegments,
                new FileStorage(nomic), ingest);

        KbImportService.Summary summary = service.importArchive(KB_ID, zip, false);

        assertThat(summary.mode()).isEqualTo("REBUILT");
        assertThat(summary.imported()).isZero();
        assertThat(summary.queued()).isEqualTo(1);
        assertThat(summary.notes()).anyMatch(note -> note.contains("nomic") && note.contains("向量空间相同"));
        assertThat(targetDocs.inserted.get(0).status()).isEqualTo(DocStatus.PENDING);
        assertThat(targetDocs.inserted.get(0).version()).isZero();
        assertThat(targetSegments.generations).isEmpty();
        verify(ingest).reindex(100L, false);
    }

    @Test
    void sameContentHashInTheTargetLibraryIsSkippedNotRewritten() throws IOException {
        Path zip = export(new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 1),
                List.of(doc(10, "a.md", "sha-a", 1, DocStatus.READY, null)), Map.of(10, TEXT_A),
                Map.of(10, List.of(new ArchivedSegment(0, null, "子块", null, 1, 1, 0, 10, true, "READY", null,
                        new float[] {0.1f, 0.2f, 0.3f, 0.4f}))));

        RecordingDocuments targetDocs = new RecordingDocuments();
        targetDocs.bySha.put("sha-a", doc(99, "a.md", "sha-a", 1, DocStatus.READY, null));

        KbImportService.Summary summary = importService(props(MODEL),
                new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 1), targetDocs, new RecordingSegments(),
                new FileStorage(props(MODEL)), mock(IngestService.class)).importArchive(KB_ID, zip, false);

        assertThat(summary.skippedSame()).isEqualTo(1);
        assertThat(summary.imported()).isZero();
        assertThat(targetDocs.inserted).isEmpty();
    }

    @Test
    void aBlankBodyIsReportedInsteadOfImportedAsAnEmptyDocument() throws IOException {
        Path zip = export(new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 1),
                List.of(doc(10, "空文件.md", "sha-a", 1, DocStatus.READY, null)), Map.of(10, "   "),
                Map.of(10, List.of(new ArchivedSegment(0, null, "子块", null, 1, 1, 0, 10, true, "READY", null,
                        new float[] {0.1f, 0.2f, 0.3f, 0.4f}))));

        RecordingDocuments targetDocs = new RecordingDocuments();
        KbImportService.Summary summary = importService(props(MODEL),
                new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 1), targetDocs, new RecordingSegments(),
                new FileStorage(props(MODEL)), mock(IngestService.class)).importArchive(KB_ID, zip, false);

        assertThat(summary.skippedEmpty()).isEqualTo(1);
        assertThat(targetDocs.inserted).isEmpty();
    }

    /** rebuild=true 是「向量明明能用，但我想按新分块器重切一遍」的口子。 */
    @Test
    void forcingRebuildIgnoresUsableVectors() throws IOException {
        Path zip = export(new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 1),
                List.of(doc(10, "a.md", "sha-a", 1, DocStatus.READY, null)), Map.of(10, TEXT_A),
                Map.of(10, List.of(new ArchivedSegment(0, null, "子块", null, 1, 1, 0, 10, true, "READY", null,
                        new float[] {0.1f, 0.2f, 0.3f, 0.4f}))));

        RecordingDocuments targetDocs = new RecordingDocuments();
        RecordingSegments targetSegments = new RecordingSegments();
        IngestService ingest = mock(IngestService.class);

        KbImportService.Summary summary = importService(props(MODEL),
                new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 1), targetDocs, targetSegments,
                new FileStorage(props(MODEL)), ingest).importArchive(KB_ID, zip, true);

        assertThat(summary.mode()).isEqualTo("REBUILT");
        assertThat(summary.notes()).anyMatch(note -> note.contains("rebuild=true"));
        assertThat(targetSegments.generations).isEmpty();
        verify(ingest).reindex(100L, false);
    }

    @Test
    void aPackageWithoutVectorsIsStillImportableAsText() throws IOException {
        Path zip = export(new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 1),
                List.of(doc(10, "a.md", "sha-a", 1, DocStatus.READY, null)), Map.of(10, TEXT_A),
                Map.of(10, List.of(new ArchivedSegment(0, null, "子块", null, 1, 1, 0, 10, true, "READY", null,
                        new float[] {0.1f, 0.2f, 0.3f, 0.4f}))), false);

        RecordingDocuments targetDocs = new RecordingDocuments();
        KbImportService.Summary summary = importService(props(MODEL),
                new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 1), targetDocs, new RecordingSegments(),
                new FileStorage(props(MODEL)), mock(IngestService.class)).importArchive(KB_ID, zip, false);

        assertThat(summary.mode()).isEqualTo("REBUILT");
        assertThat(summary.notes()).anyMatch(note -> note.contains("导出时没带向量"));
        assertThat(targetDocs.inserted.get(0).content()).isEqualTo(TEXT_A);
    }

    @Test
    void aPackageFromAnotherDimensionFallsBackToReingest() throws IOException {
        // 包里是 768 维、本机是另一种维度：列级约束 vector(dim) 插不进去，只能在落库前换成重算
        RagProperties small = props(MODEL);
        Path zip = export(new KnowledgeBase(KB_ID, "默认知识库", MODEL, 768, 1),
                List.of(doc(10, "a.md", "sha-a", 1, DocStatus.READY, null)), Map.of(10, TEXT_A),
                Map.of(10, List.of(new ArchivedSegment(0, null, "子块", null, 1, 1, 0, 10, true, "READY", null,
                        new float[DIM]))));
        RecordingDocuments targetDocs = new RecordingDocuments();

        KbImportService.Summary summary = importService(small, new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 1),
                targetDocs, new RecordingSegments(), new FileStorage(small), mock(IngestService.class))
                .importArchive(KB_ID, zip, false);

        assertThat(summary.mode()).isEqualTo("REBUILT");
        assertThat(summary.notes()).anyMatch(note -> note.contains("768 维"));
        assertThat(targetDocs.inserted.get(0).status()).isEqualTo(DocStatus.PENDING);
    }

    @Test
    void prepareCountsTheDocumentsThatWillNotMakeItIntoThePackage() {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        when(knowledgeBases.require(anyLong()))
                .thenReturn(new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 1));
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.listLive(anyLong())).thenReturn(List.of(
                doc(10, "a.md", "sha-a", 1, DocStatus.READY, null),
                doc(11, "b.md", "sha-b", 0, DocStatus.EMBEDDING, null),
                doc(12, "c.md", "sha-c", 1, DocStatus.READY, null)));
        when(documents.count(anyLong())).thenReturn(3);
        KbExportService service = new KbExportService(knowledgeBases, documents,
                mock(SegmentRepository.class), new FileStorage(props(MODEL)), json);

        KbExportService.Info info = service.prepare(KB_ID, true, false);

        assertThat(info.documents()).isEqualTo(2);
        assertThat(info.skippedNotReady()).isEqualTo(1);
        assertThat(info.fileName()).startsWith("kb-1-").endsWith(".zip");
    }

    @Test
    void aFileThatIsNotASixteenByteZipHeaderIsRefusedWithAFixableMessage() throws IOException {
        Path notAZip = temp.resolve("备份.rar");
        Files.write(notAZip, "这不是 zip，只是改了名".getBytes(StandardCharsets.UTF_8));
        KbImportService service = importService(props(MODEL), new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 1),
                new RecordingDocuments(), new RecordingSegments(), new FileStorage(props(MODEL)),
                mock(IngestService.class));

        assertThatThrownBy(() -> service.importArchive(KB_ID, notAZip, false))
                .isInstanceOf(com.rag.knowledge.common.RagException.class)
                .hasMessageContaining("不是有效的 zip");
    }

    @Test
    void aZipWithoutManifestIsNotTreatedAsAnArchive() throws IOException {
        Path zipWithoutManifest = temp.resolve("随便一个.zip");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(zipWithoutManifest))) {
            out.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
            out.write("空的".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        KbImportService service = importService(props(MODEL), new KnowledgeBase(KB_ID, "默认知识库", MODEL, DIM, 1),
                new RecordingDocuments(), new RecordingSegments(), new FileStorage(props(MODEL)),
                mock(IngestService.class));

        assertThatThrownBy(() -> service.importArchive(KB_ID, zipWithoutManifest, false))
                .isInstanceOf(com.rag.knowledge.common.RagException.class)
                .hasMessageContaining("manifest.json");
    }

    // ---------- 组装 ----------

    private Path export(KnowledgeBase kb, List<Document> docs, Map<Integer, String> contents,
                        Map<Integer, List<ArchivedSegment>> blocks) throws IOException {
        return export(kb, docs, contents, blocks, true);
    }

    private Path export(KnowledgeBase kb, List<Document> docs, Map<Integer, String> contents,
                        Map<Integer, List<ArchivedSegment>> blocks, boolean vectors) throws IOException {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        when(knowledgeBases.require(anyLong())).thenReturn(kb);
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.listLive(anyLong())).thenReturn(docs);
        when(documents.count(anyLong())).thenReturn(docs.size());
        contents.forEach((docId, text) -> when(documents.content(eq(docId.longValue()))).thenReturn(text));
        SegmentRepository segments = mock(SegmentRepository.class);
        blocks.forEach((docId, rows) -> {
            when(segments.listArchived(eq(docId.longValue()), anyInt())).thenReturn(rows);
            when(segments.countForDocument(eq(docId.longValue()), anyInt())).thenReturn(rows.size());
        });
        KbExportService service = new KbExportService(knowledgeBases, documents, segments,
                new FileStorage(props(MODEL)), json);
        Path zip = temp.resolve("archive-" + System.nanoTime() + ".zip");
        try (OutputStream out = Files.newOutputStream(zip)) {
            service.writeTo(KB_ID, vectors, true, out);
        }
        return zip;
    }

    private KbImportService importService(RagProperties props, KnowledgeBase kb, RecordingDocuments docs,
                                          RecordingSegments segments, FileStorage storage, IngestService ingest) {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        when(knowledgeBases.require(anyLong())).thenReturn(kb);
        return new KbImportService(props, knowledgeBases, docs, storage, ingest,
                new KbImportWriter(docs, segments, json), json);
    }

    private static List<String> pairs(List<int[]> links) {
        return links.stream().map(pair -> pair[0] + "->" + pair[1]).toList();
    }

    private static Document doc(long id, String name, String sha, int version, DocStatus status, String filePath) {
        return new Document(id, KB_ID, name, sha, filePath, 1L, "text/markdown", 1, 20, status,
                status == DocStatus.READY ? 100 : 40, "入库完成", version, 1, null, null, null);
    }

    private RagProperties props(String embeddingModel) {
        return new RagProperties(
                new RagProperties.LmStudio("http://localhost:1234/v1", "lm-studio", "qwen2.5-vl-7b-instruct",
                        embeddingModel, 0.3, 0.8, null, Duration.ofSeconds(5), 1),
                new RagProperties.Storage(temp.toString()),
                new RagProperties.Embedding(DIM, 16),
                RagProperties.Ingest.defaults(), RagProperties.Retrieval.defaults(),
                RagProperties.Archive.defaults());
    }

    /** 记账假件：把十二个参数的插入原样存下来，断言读起来才是「导入进去的那篇长什么样」。 */
    private static final class RecordingDocuments extends DocumentRepository {

        record Row(long docId, String name, String sha256, String content, int version, int indexVersion,
                   DocStatus status, String chunkOverride, String filePath) {
        }

        final List<Row> inserted = new ArrayList<>();
        final Map<String, Document> bySha = new LinkedHashMap<>();
        private long nextId = 100L;

        RecordingDocuments() {
            super(null);
        }

        @Override
        public Optional<Document> findBySha(long kbId, String sha256) {
            return Optional.ofNullable(bySha.get(sha256));
        }

        @Override
        public long insertArchived(long kbId, String name, String sha256, String mime, Long fileSize, String content,
                                   Integer pageCount, int version, int indexVersion, DocStatus status,
                                   String chunkOverrideJson, String filePath) {
            long docId = nextId++;
            inserted.add(new Row(docId, name, sha256, content, version, indexVersion, status, chunkOverrideJson,
                    filePath));
            return docId;
        }
    }

    private static final class RecordingSegments extends SegmentRepository {

        final List<List<ArchivedSegment>> generations = new ArrayList<>();
        final List<List<int[]>> links = new ArrayList<>();

        RecordingSegments() {
            super(null);
        }

        @Override
        public int insertArchivedGeneration(long docId, long kbId, int docVersion, String embeddingModel,
                                            List<ArchivedSegment> rows) {
            generations.add(List.copyOf(rows));
            return rows.size();
        }

        @Override
        public void linkParents(long docId, int docVersion, List<int[]> childToParentChunkNo) {
            links.add(childToParentChunkNo);
        }
    }
}
