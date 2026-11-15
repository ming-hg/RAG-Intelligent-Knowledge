package com.rag.knowledge.web;

import com.rag.knowledge.app.archive.KbExportService;
import com.rag.knowledge.app.archive.KbImportService;
import com.rag.knowledge.app.ingest.IngestService;
import com.rag.knowledge.common.ApiResponse;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 归档接口的入口守卫：空包不能算成功、超限要在解包前挡住、临时包在失败路径上也要回收、
 * 导出必须先有文件名——响应头一旦发出就没法再改成 4xx。
 */
class KnowledgeBaseArchiveApiTest {

    private static final long KB_ID = 1L;

    private final KbExportService exporter = mock(KbExportService.class);
    private final KbImportService importer = mock(KbImportService.class);
    private final KnowledgeBaseController controller = new KnowledgeBaseController(
            mock(KnowledgeBaseRepository.class), mock(IngestService.class), exporter, importer, props(1));

    @Test
    void anEmptyUploadNeverReachesTheImporter() {
        MockMultipartFile file = new MockMultipartFile("file", "kb.zip", "application/zip", new byte[0]);

        assertThatThrownBy(() -> controller.importArchive(KB_ID, file, false))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("归档包是空的");

        verifyNoInteractions(importer);
    }

    @Test
    void anOversizedPackageIsRefusedBeforeUnzipping() {
        MockMultipartFile file = new MockMultipartFile("file", "kb.zip", "application/zip",
                new byte[2 * 1024 * 1024]);

        assertThatThrownBy(() -> controller.importArchive(KB_ID, file, false))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("超过 1MB")
                .hasMessageContaining("rag.archive.max-import-mb");

        verifyNoInteractions(importer);
    }

    /** 临时包落在系统临时目录，导入失败时留在磁盘上是会攒起来的——这条路必须自己收尾。 */
    @Test
    void theTempPackageIsDeletedEvenWhenImportingFails() {
        MockMultipartFile file = new MockMultipartFile("file", "kb.zip", "application/zip", "PK zip".getBytes());
        Path[] seen = new Path[1];
        when(importer.importArchive(anyLong(), any(Path.class), anyBoolean())).thenAnswer(call -> {
            seen[0] = call.getArgument(1);
            throw RagException.badRequest("包里缺 manifest.json");
        });

        assertThatThrownBy(() -> controller.importArchive(KB_ID, file, false))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("manifest");

        assertThat(Files.exists(seen[0])).isFalse();
    }

    @Test
    void aSuccessfulCarriedImportIsExplainedAsNoEmbeddingSpent() {
        MockMultipartFile file = new MockMultipartFile("file", "kb.zip", "application/zip", "PK zip".getBytes());
        Path[] seen = new Path[1];
        when(importer.importArchive(anyLong(), any(Path.class), anyBoolean())).thenAnswer(call -> {
            seen[0] = call.getArgument(1);
            assertThat(Files.exists(seen[0])).as("服务是照着这个路径解包的，调用时临时包必须还在").isTrue();
            return new KbImportService.Summary(KB_ID, "CARRIED", 3, 0, 421, 1, 0, 0, 0, List.of());
        });

        ApiResponse<KbImportService.Summary> response = controller.importArchive(KB_ID, file, false);

        assertThat(response.data().mode()).isEqualTo("CARRIED");
        assertThat(response.message()).contains("未花一次 embedding").contains("421 块");
        assertThat(Files.exists(seen[0])).isFalse();
    }

    @Test
    void exportSendsTheFileNameFromPrepareAndStreamsAfterwards() throws IOException {
        when(exporter.prepare(KB_ID, true, false)).thenReturn(
                new KbExportService.Info("kb-1-20261115-2200.zip", KB_ID, "默认知识库", 4, 1, true, false));

        ResponseEntity<StreamingResponseBody> response = controller.export(KB_ID, true, false);

        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("kb-1-20261115-2200.zip");
        response.getBody().writeTo(new ByteArrayOutputStream());

        verify(exporter).writeTo(eq(KB_ID), eq(true), eq(false), any());
    }

    @Test
    void aMissingKnowledgeBaseFailsBeforeAnyByteIsWritten() throws IOException {
        when(exporter.prepare(KB_ID, true, true)).thenThrow(RagException.notFound("知识库不存在：1"));

        assertThatThrownBy(() -> controller.export(KB_ID, true, true))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("知识库不存在");

        verify(exporter, never()).writeTo(anyLong(), anyBoolean(), anyBoolean(), any());
    }

    /** 限值来自配置：写死一个数字的话，换机器的人只会看到一个解释不了的 400。 */
    @Test
    void theSizeLimitComesFromConfiguration() {
        KnowledgeBaseController configured = new KnowledgeBaseController(mock(KnowledgeBaseRepository.class),
                mock(IngestService.class), exporter, importer, props(2));
        MockMultipartFile file = new MockMultipartFile("file", "kb.zip", "application/zip",
                new byte[3 * 1024 * 1024]);

        assertThatThrownBy(() -> configured.importArchive(KB_ID, file, false))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("超过 2MB");
    }

    private static RagProperties props(int maxImportMb) {
        return new RagProperties(
                new RagProperties.LmStudio("http://localhost:1234/v1", "lm-studio", "chat", "bge",
                        0.3, 0.8, null, Duration.ofSeconds(5), 1),
                new RagProperties.Storage("./target/unused"),
                new RagProperties.Embedding(4, 16),
                RagProperties.Ingest.defaults(), RagProperties.Retrieval.defaults(),
                new RagProperties.Archive(maxImportMb));
    }
}
