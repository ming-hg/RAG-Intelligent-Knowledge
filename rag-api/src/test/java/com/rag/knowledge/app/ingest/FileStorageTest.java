package com.rag.knowledge.app.ingest;

import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 落盘层的两条底线：内容寻址去重 + 库里的相对路径不能翻出 storage 根目录。
 */
class FileStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void identicalContentIsStoredOncePerKnowledgeBase() throws IOException {
        FileStorage storage = storage();
        byte[] bytes = "同一份方案".getBytes(StandardCharsets.UTF_8);

        FileStorage.Stored first = storage.store(1L, "方案 v1.pdf", bytes);
        FileStorage.Stored second = storage.store(1L, "改名还是它.pdf", bytes);

        assertThat(second.sha256()).isEqualTo(first.sha256());
        assertThat(second.relativePath()).isEqualTo(first.relativePath());
        try (var files = Files.walk(tempDir)) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
        }
        assertThat(new String(storage.read(first.relativePath()), StandardCharsets.UTF_8)).isEqualTo("同一份方案");
    }

    @Test
    void sameContentInDifferentKnowledgeBaseIsKeptApart() {
        FileStorage storage = storage();
        byte[] bytes = "内容".getBytes(StandardCharsets.UTF_8);

        assertThat(storage.store(1L, "a.md", bytes).relativePath())
                .startsWith("kb1/")
                .endsWith(".md");
        assertThat(storage.store(2L, "a.md", bytes).relativePath()).startsWith("kb2/");
    }

    @Test
    void pathSeparatorsInFileNameCannotEscapeTheKnowledgeBaseDirectory() {
        FileStorage storage = storage();
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);

        FileStorage.Stored stored = storage.store(1L, "报告.pdf/../../windows/win.ini", bytes);

        assertThat(stored.relativePath()).matches("kb1/[0-9a-f]{64}\\.\\w+");
        assertThat(isInsideRoot(storage, stored.relativePath())).isTrue();
    }

    @Test
    void readingOutsideTheStorageRootIsRefused() {
        FileStorage storage = storage();

        assertThatThrownBy(() -> storage.read("../application.yml"))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("非法文件路径");
        assertThatThrownBy(() -> storage.read("kb1/../../../../Windows/win.ini"))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("非法文件路径");
        // 绝对路径按当前系统构造，Linux/Windows 上都能确定落在 storage 根之外
        String outside = Path.of(tempDir.getParent().toString(), "elsewhere.txt").toString();
        assertThatThrownBy(() -> storage.read(outside))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("非法文件路径");
    }

    @Test
    void missingFileIsReportedWithARecoverableHint() {
        FileStorage storage = storage();

        assertThatThrownBy(() -> storage.read("kb1/deadbeef.md"))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("原始文件读不到")
                .hasMessageContaining("重新上传");
    }

    @Test
    void deleteIsIdempotent() {
        FileStorage storage = storage();
        String path = storage.store(1L, "临时.md", "x".getBytes(StandardCharsets.UTF_8)).relativePath();

        storage.delete(path);
        storage.delete(path);

        assertThat(Files.exists(Path.of(storage.root(), path))).isFalse();
    }

    @Test
    void extensionIsOnlyAlnumOrNothingAtAll() {
        assertThat(FileStorage.extensionOf("a.b.PDF")).isEqualTo(".pdf");
        assertThat(FileStorage.extensionOf("无扩展名")).isEmpty();
        assertThat(FileStorage.extensionOf("endswithdot.")).isEmpty();
        assertThat(FileStorage.extensionOf("evil.pdf/../../x")).isEqualTo(".x");
        assertThat(FileStorage.extensionOf(null)).isEmpty();
    }

    private static boolean isInsideRoot(FileStorage storage, String relative) {
        Path root = Path.of(storage.root());
        return root.resolve(relative).normalize().startsWith(root);
    }

    private FileStorage storage() {
        RagProperties props = new RagProperties(
                new RagProperties.LmStudio("http://localhost:1234/v1", "lm-studio", "chat", "bge",
                        0.3, 0.8, null, Duration.ofSeconds(5), 1),
                new RagProperties.Storage(tempDir.toString()),
                new RagProperties.Embedding(768, 16),
                new RagProperties.Ingest(true, Duration.ofSeconds(2), Duration.ofMinutes(10), 3, 1, 100,
                        List.of("pdf", "md"), RagProperties.Chunk.defaults()),
                RagProperties.Retrieval.defaults(), RagProperties.Archive.defaults());
        return new FileStorage(props);
    }
}
