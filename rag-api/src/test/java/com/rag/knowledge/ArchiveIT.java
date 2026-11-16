package com.rag.knowledge;

import com.rag.knowledge.app.archive.KbArchive;
import com.rag.knowledge.app.archive.KbExportService;
import com.rag.knowledge.app.archive.KbImportService;
import com.rag.knowledge.app.ingest.IngestService;
import com.rag.knowledge.app.rag.KeywordEngine;
import com.rag.knowledge.app.rag.RetrievalMode;
import com.rag.knowledge.app.rag.RetrievalQuery;
import com.rag.knowledge.app.rag.SearchHit;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.DocumentRepository;
import com.rag.knowledge.db.RetrievalRepository;
import com.rag.knowledge.db.SegmentRepository;
import com.rag.knowledge.db.TaskRepository;
import com.rag.knowledge.domain.DocStatus;
import com.rag.knowledge.domain.Document;
import com.rag.knowledge.domain.SegmentView;
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
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M5d 的验收面：归档包在真实 pgvector 上过一遍（导出 → 清场 → 导入 → 检索复验）。
 * <p>
 * 编解码与拒绝规则已经有 {@code KbArchiveRoundTripTest} 用记账假件覆盖，这里补的是假件看不见的那一半：
 * 块真的带着向量落进了一代索引、父子链接真的建了、{@code available=false} 与 {@code edited_at} 真的在行里而不是只在
 * 内存对象里、导入后检索 SQL 真能按目标库范围召回。这些是「pgvector 整条语句失败」那一类问题的现场，
 * 编译器与 mock 都管不到。
 * <p>
 * 「免重算」这条用 mock 交互来断言（导入期间 embedding 零调用），比看日志或者看 {@code mode} 字段硬得多：
 * 用户搬库时真正想知道的是「这次要烧多少模型时间」。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rag.embedding.dim=768",
        "rag.ingest.worker-enabled=false",
        // 模型名与维度必须与包内来源一致，否则 CARRIED 分支永远走不到；这里用假名字把配置钉死
        "rag.lm-studio.embedding-model=bge-it-v1",
        "rag.storage.dir=target/archive-it"
})
class ArchiveIT {

    private static final String OWNER = "it-archive";
    private static final String MARKER_MANUAL = "ARCHIVE-7731";
    private static final String MARKER_LEDGER = "ARCHIVE-9F3C";

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
    KbExportService export;

    @Autowired
    KbImportService importer;

    @Autowired
    DocumentRepository documents;

    @Autowired
    SegmentRepository segments;

    @Autowired
    RetrievalRepository retrieval;

    @Autowired
    TaskRepository tasks;

    @Autowired
    RagProperties props;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void startFromAnEmptyLibrary() throws IOException {
        jdbc.update("TRUNCATE kb_segment, kb_document, kb_task, knowledge_base, chat_message, chat_session"
                + " RESTART IDENTITY CASCADE");
        // 原件也清：留着上一轮的文件，「导入把原件落到了新位置」就分不清是新证据还是旧残留
        Path root = Path.of(props.storage().dir());
        if (Files.exists(root)) {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.equals(root)) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
        Files.createDirectories(root);
        stubEmbedding();
    }

    @Test
    void packageIsSelfDescribingAndCarriesContentVectorsAndTheOriginalBytes() throws IOException {
        long kbId = seedTwoReadyDocuments();
        KbExportService.Info info = export.prepare(kbId, true, true);
        Path zip = exportArchive(kbId, true, true);

        try (ZipFile file = open(zip)) {
            KbArchive.Manifest manifest = manifest(file);
            assertThat(manifest.format()).isEqualTo(KbArchive.FORMAT_VERSION);
            assertThat(manifest.vectors()).isTrue();
            assertThat(manifest.files()).isTrue();
            assertThat(manifest.source().embeddingModel()).isEqualTo(props.lmStudio().embeddingModel());
            assertThat(manifest.source().embeddingDim()).isEqualTo(props.embedding().dim());
            assertThat(info.fileName()).matches("kb-\\d+-\\d{8}-\\d{6}\\.zip");
            assertThat(info.documents()).isEqualTo(2);
            // slot 从 0 连续编号而不是数据库 id：id 属于源库，写进包里就是让别的库继承一个没意义的号
            assertThat(manifest.documents()).extracting(KbArchive.DocEntry::slot).containsExactly(0, 1);
            assertThat(manifest.documents()).allSatisfy(entry -> assertThat(entry.segmentCount()).isPositive());

            assertThat(entryText(file, KbArchive.contentEntry(0))).contains(MARKER_MANUAL);
            assertThat(entryLines(file, KbArchive.segmentsEntry(0)))
                    .hasSize(manifest.documents().get(0).segmentCount());
            // 原件按 slot + 安全文件名落进包里，字节与上传时同一份：以后 reparse=true 才有东西可解析
            byte[] original = readEntry(file, KbArchive.fileEntry(0, "运维手册.md"));
            assertThat(original).isNotNull();
            assertThat(original).hasSize(Math.toIntExact(manifest.documents().get(0).fileSize()));
            assertThat(new String(original, StandardCharsets.UTF_8)).contains(MARKER_MANUAL);
        }
    }

    @Test
    void carriedImportSpendsNoEmbeddingAndLandsReadySearchableInTheTargetLibrary() throws IOException {
        long kbId = seedTwoReadyDocuments();
        Path zip = exportArchive(kbId, true, true);
        long target = insertKb(2L, "迁移目标库", props.lmStudio().embeddingModel(), props.embedding().dim());

        clearInvocations(embeddingModel);
        KbImportService.Summary summary = importer.importArchive(target, zip, false);
        // 「没重算」的硬证据不是 mode 字段，是这一次导入真没碰过向量模型
        verifyNoInteractions(embeddingModel);

        assertThat(summary.mode()).isEqualTo("CARRIED");
        assertThat(summary.imported()).isEqualTo(2);
        assertThat(summary.queued()).isZero();
        assertThat(summary.failed()).isZero();
        assertThat(summary.skippedSame()).isZero();
        assertThat(summary.segmentsCarried()).isEqualTo(publishedSegments(kbId));
        assertThat(summary.originalsRestored()).isEqualTo(2);
        assertThat(tasks.countActive(target)).isZero();

        List<Document> imported = documents.listLive(target);
        assertThat(imported).hasSize(2);
        assertThat(imported).allSatisfy(doc -> {
            assertThat(doc.status()).isEqualTo(DocStatus.READY);
            assertThat(doc.filePath()).isNotBlank();
            // 代次原样带过来，导入端不能把它压回 0：块按 doc.version() 这一代落库
            assertThat(segments.countForDocument(doc.id(), doc.version())).isPositive();
        });
        assertThat(Files.exists(Path.of(props.storage().dir()).resolve(imported.get(0).filePath()))).isTrue();
        // 有向量的块数对齐，而不是总块数：父块 emb_status=SKIPPED 本来就不该有向量，
        // 导入要是顺手给父块补了向量，pgvector 那一侧看不出问题，检索却会多召回一截长上下文
        assertThat(vectorizedSegments(target)).isEqualTo(vectorizedSegments(kbId));
        assertThat(vectorizedSegments(kbId)).isLessThan(publishedSegments(kbId));

        List<SearchHit> hits = keywordSearch(target, MARKER_MANUAL);
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(hit -> assertThat(hit.docName()).isEqualTo("运维手册.md"));
    }

    /** 人工意图是数据的一部分（ADR-018）：关掉检索的块、改过的正文、文档级分块覆盖都得跟着包走。 */
    @Test
    void disabledAndEditedBlocksComeBackTheSameWayTheyLeft() throws IOException {
        long kbId = seedTwoReadyDocuments();
        long docId = documents.listLive(kbId).get(0).id();
        Document source = document(docId);
        List<SegmentView> views = segments.listForDocument(docId, source.version(), true, 100, 0);
        SegmentView disabled = views.get(0);
        SegmentView edited = views.get(1);
        assertThat(segments.setAvailable(docId, disabled.id(), false)).isEqualTo(1);
        assertThat(segments.editContent(docId, edited.id(), "人工订正过的正文 " + MARKER_MANUAL)).isEqualTo(1);
        documents.setChunkOverride(docId, "{\"childChars\":260}");
        long before = publishedSegments(kbId);

        Path zip = exportArchive(kbId, true, true);
        long target = insertKb(3L, "人工意图目标库", props.lmStudio().embeddingModel(), props.embedding().dim());
        assertThat(importer.importArchive(target, zip, false).mode()).isEqualTo("CARRIED");

        Document carried = documents.listLive(target).stream()
                .filter(doc -> doc.name().equals(source.name())).findFirst().orElseThrow();
        assertThat(carried.chunkOverride()).contains("260");
        List<SegmentView> carriedViews = segments.listForDocument(carried.id(), carried.version(), false, 100, 0);
        assertThat(carriedViews).hasSize((int) publishedSegmentsOf(docId, source.version()));
        SegmentView carriedDisabled = carriedViews.stream()
                .filter(view -> view.chunkNo() == disabled.chunkNo()).findFirst().orElseThrow();
        SegmentView carriedEdited = carriedViews.stream()
                .filter(view -> view.chunkNo() == edited.chunkNo()).findFirst().orElseThrow();
        // 导入绕开索引发布器就是为了这一刻：状态由包里带来，不由代码推导
        assertThat(carriedDisabled.available()).isFalse();
        assertThat(carriedEdited.edited()).isTrue();
        assertThat(carriedEdited.content()).contains("人工订正过的正文");
        assertThat(publishedSegments(target)).isEqualTo(before);

        // 关掉的那块两条路都不该召回来
        assertThat(keywordSearch(target, MARKER_MANUAL)).extracting(SearchHit::segmentId)
                .doesNotContain(jdbc.queryForObject("SELECT id FROM kb_segment WHERE doc_id = ? AND chunk_no = ?",
                        Long.class, carried.id(), disabled.chunkNo()));
    }

    @Test
    void samePackageImportedTwiceSkipsByHashAndWritesNoNewRows() throws IOException {
        long kbId = seedTwoReadyDocuments();
        Path zip = exportArchive(kbId, true, false);
        long target = insertKb(4L, "重复导入库", props.lmStudio().embeddingModel(), props.embedding().dim());

        importer.importArchive(target, zip, false);
        int docs = documents.count(target);
        int rows = jdbc.queryForObject("SELECT count(*) FROM kb_segment WHERE kb_id = ?", Integer.class, target);

        KbImportService.Summary second = importer.importArchive(target, zip, false);

        assertThat(second.imported()).isZero();
        assertThat(second.queued()).isZero();
        assertThat(second.segmentsCarried()).isZero();
        assertThat(second.skippedSame()).isEqualTo(2);
        assertThat(documents.count(target)).isEqualTo(docs);
        // 只标 deleted_at 的软删残留是另一件事；这里要钉的是「重复导入一行都不写」
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_segment WHERE kb_id = ?", Integer.class, target))
                .isEqualTo(rows);
    }

    @Test
    void rebuildTrueGoesBackThroughThePipelineAndActuallySpendsTheModel() throws IOException {
        long kbId = seedTwoReadyDocuments();
        Path zip = exportArchive(kbId, true, true);
        long target = insertKb(5L, "强制重算库", props.lmStudio().embeddingModel(), props.embedding().dim());

        KbImportService.Summary summary = importer.importArchive(target, zip, true);

        assertThat(summary.mode()).isEqualTo("REBUILT");
        assertThat(summary.queued()).isEqualTo(2);
        assertThat(summary.imported()).isZero();
        assertThat(summary.segmentsCarried()).isZero();
        assertThat(summary.notes()).anyMatch(note -> note.contains("rebuild=true"));
        assertThat(tasks.countActive(target)).isEqualTo(2);
        assertThat(documents.listLive(target)).extracting(Document::status).containsExactly(DocStatus.PENDING,
                DocStatus.PENDING);
        // 正文与原件已经落库，缺的只是分块与向量：worker 跑完才变 READY
        assertThat(documents.content(documents.listLive(target).get(0).id())).contains(MARKER_MANUAL);

        runNextTask();
        runNextTask();

        assertThat(documents.listLive(target)).allSatisfy(doc -> {
            assertThat(doc.status()).isEqualTo(DocStatus.READY);
            assertThat(segments.countMissingEmbedding(doc.id(), doc.version())).isZero();
        });
    }

    @Test
    void brokenPackagesAndDimensionDriftAreRefusedBeforeTheFirstRow() throws IOException {
        long kbId = seedTwoReadyDocuments();
        Path zip = exportArchive(kbId, true, true);
        long target = insertKb(6L, "拒绝路径库", props.lmStudio().embeddingModel(), props.embedding().dim());
        long small = insertKb(7L, "512 维的库", props.lmStudio().embeddingModel(), 512);

        Path notAZip = Files.write(Files.createTempFile("rag-archive-it-", ".zip"),
                "这不是 zip，只是被人改了后缀".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> importer.importArchive(target, notAZip, false))
                .isInstanceOfSatisfying(RagException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("不是有效的 zip");
                });

        Path zipWithoutManifest = Files.createTempFile("rag-archive-it-no-manifest-", ".zip");
        try (OutputStream out = Files.newOutputStream(zipWithoutManifest)) {
            writeZipEntry(out, "content/0.txt", "手工拼的包".getBytes(StandardCharsets.UTF_8));
        }
        assertThatThrownBy(() -> importer.importArchive(target, zipWithoutManifest, false))
                .isInstanceOfSatisfying(RagException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("manifest.json").contains("不是本系统导出的");
                });

        assertThatThrownBy(() -> importer.importArchive(small, zip, false))
                .isInstanceOfSatisfying(RagException.class, ex -> {
                    // 两个数字都要说：只看「维度不符」的人分不清是本库配错还是包来自别的模型
                    assertThat(ex.code()).isEqualTo(409);
                    assertThat(ex.getMessage()).contains("512").contains("768");
                });

        assertThat(documents.count(target)).isZero();
        assertThat(documents.count(small)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kb_segment", Integer.class))
                .isEqualTo(publishedSegments(kbId));
    }

    private long seedTwoReadyDocuments() {
        long kbId = ingest.defaultKbId();
        uploadReady(kbId, "运维手册.md", document(MARKER_MANUAL, "备份与恢复"));
        uploadReady(kbId, "变更台账.md", document(MARKER_LEDGER, "变更与回滚"));
        return kbId;
    }

    private long uploadReady(long kbId, String fileName, String content) {
        long docId = ingest.upload(kbId, fileName, "text/markdown", content.getBytes(StandardCharsets.UTF_8))
                .documentId();
        runNextTask();
        assertThat(document(docId).status()).isEqualTo(DocStatus.READY);
        return docId;
    }

    /** 每篇都要长到能切出多个子块并生成父块，否则父子链接这条链在测试里根本不存在。 */
    private static String document(String marker, String topic) {
        StringBuilder text = new StringBuilder();
        for (int section = 1; section <= 4; section++) {
            text.append("## 第 ").append(section).append(" 节 ").append(topic).append("\n\n");
            for (int line = 1; line <= 20; line++) {
                text.append("这一节讲 ").append(topic).append("，编号 ").append(marker).append('-')
                        .append(section).append('-').append(line)
                        .append("，只有把它单独切进块里才检索得到。\n");
            }
            text.append('\n');
        }
        return text.toString();
    }

    private Path exportArchive(long kbId, boolean vectors, boolean files) throws IOException {
        Path zip = Files.createTempFile("rag-archive-it-", ".zip");
        try (OutputStream out = Files.newOutputStream(zip)) {
            export.writeTo(kbId, vectors, files, out);
        }
        return zip;
    }

    private static ZipFile open(Path zip) throws IOException {
        return new ZipFile(zip.toFile(), StandardCharsets.UTF_8);
    }

    private KbArchive.Manifest manifest(ZipFile zip) {
        byte[] bytes = readEntry(zip, KbArchive.MANIFEST_ENTRY);
        assertThat(bytes).as("包里必须有一份自描述的清单").isNotNull();
        return json.readValue(new String(bytes, StandardCharsets.UTF_8), KbArchive.Manifest.class);
    }

    private static String entryText(ZipFile zip, String name) {
        byte[] bytes = readEntry(zip, name);
        assertThat(bytes).as("包里缺条目 " + name).isNotNull();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<String> entryLines(ZipFile zip, String name) {
        return Arrays.stream(entryText(zip, name).split("\n")).filter(line -> !line.isBlank()).toList();
    }

    private static byte[] readEntry(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeZipEntry(OutputStream out, String name, byte[] bytes) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
        zip.finish();
    }

    private long insertKb(long id, String name, String model, int dim) {
        jdbc.update("INSERT INTO knowledge_base (id, name, embedding_model, embedding_dim)"
                + " OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?)", id, name, model, dim);
        return id;
    }

    /** 关键词路 + 阈值 0：这里要问的是「块在不在库里、范不范围内」，不是模型质量。 */
    private List<SearchHit> keywordSearch(long kbId, String token) {
        KeywordEngine engine = retrieval.probeEngine();
        if (!engine.available()) {
            return List.of();
        }
        return retrieval.search(new RetrievalQuery(List.of(kbId), List.of(), token, null,
                RetrievalMode.KEYWORD, engine, 40, 10, 60, 0.0));
    }

    private int publishedSegments(long kbId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM kb_segment s JOIN kb_document d ON d.id = s.doc_id"
                + " WHERE d.kb_id = ? AND d.deleted_at IS NULL AND s.doc_version = d.version", Integer.class, kbId);
        return count == null ? 0 : count;
    }

    private int vectorizedSegments(long kbId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM kb_segment s JOIN kb_document d ON d.id = s.doc_id"
                + " WHERE d.kb_id = ? AND d.deleted_at IS NULL AND s.doc_version = d.version"
                + " AND s.embedding IS NOT NULL", Integer.class, kbId);
        return count == null ? 0 : count;
    }

    private long publishedSegmentsOf(long docId, int version) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM kb_segment WHERE doc_id = ? AND doc_version = ?",
                Long.class, docId, version);
        return count == null ? 0 : count;
    }

    private void stubEmbedding() {
        reset(embeddingModel);
        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<TextSegment> inputs = invocation.getArgument(0);
            List<Embedding> vectors = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) {
                float[] vector = new float[props.embedding().dim()];
                vector[0] = 1f;
                vector[1] = i;
                vectors.add(new Embedding(vector));
            }
            return Response.from(vectors);
        });
    }

    private void runNextTask() {
        List<TaskRepository.Task> claimed = tasks.claim(OWNER, 1, Duration.ofMinutes(5));
        assertThat(claimed).as("队列里没有待处理任务").hasSize(1);
        ingest.process(claimed.get(0), OWNER);
    }

    private Document document(long id) {
        var found = documents.findById(id);
        assertThat(found).isPresent();
        return found.get();
    }
}
