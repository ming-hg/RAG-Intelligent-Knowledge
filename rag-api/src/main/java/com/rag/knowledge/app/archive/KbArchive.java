package com.rag.knowledge.app.archive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.rag.knowledge.app.ingest.ChunkOverride;

import java.util.List;

/**
 * 知识库归档包（.zip）的词汇表。
 * <p>
 * 包结构固定四件事：{@code manifest.json} 说明这是谁、什么维度、带不带向量；{@code documents} 里每篇文档占一个
 * slot（从 0 起的包内序号，不是库里的 id——id 属于目标库，导出时写死没有意义）；正文与分块各占一个文件，
 * 逐篇流式读写，导出一个几百兆的库不需要把它整个装进内存。
 * <p>
 * 每个 record 都标了 {@code ignoreUnknown}：包是会被拿到别的版本上打开的，多出来的键不该让导入直接失败。
 */
public final class KbArchive {

    /** 包格式版本。加字段不算破坏兼容，改语义或删字段才需要升版本。 */
    public static final int FORMAT_VERSION = 1;
    public static final String MANIFEST_ENTRY = "manifest.json";

    private KbArchive() {
    }

    public static String contentEntry(int slot) {
        return "content/" + slot + ".txt";
    }

    public static String segmentsEntry(int slot) {
        return "segments/" + slot + ".jsonl";
    }

    /**
     * 原始文件在包里的路径。名字由 slot + 文档名推导，两端各算一次而不是写进清单：
     * 清单里存一个「包内路径」字段就等于多一处会和实际条目对不上的状态，而 zip 里没有那个条目时
     * 导入端只会静默少一个文件，报错还看起来像包损坏。
     */
    public static String fileEntry(int slot, String name) {
        return "files/" + slot + "/" + safeFileName(name);
    }

    /** 去掉路径分隔符与控制字符：文档名来自用户，不能直接当包内路径用。 */
    public static String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "document";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[\\p{Cntrl}\"*:<>?|]", "_").trim();
        if (base.isEmpty() || ".".equals(base) || "..".equals(base)) {
            return "document";
        }
        return base.length() > 160 ? base.substring(0, 160) : base;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manifest(int format, String exportedAt, Source source, boolean vectors, boolean files,
                           List<DocEntry> documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String name, String embeddingModel, int embeddingDim, int indexVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocEntry(int slot, String name, String sha256, String mime, Long fileSize, Integer pageCount,
                           Integer charCount, int version, int segmentCount, ChunkOverride chunkOverride) {
    }

    /**
     * 一行一块。{@code embedding} 是 base64(float32 小端)，为 null 表示包里没带向量（或这块本来就没向量，
     * 判据在 {@code embStatus}：SKIPPED 是父块，本来就不该有向量）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SegmentEntry(int chunkNo, Integer parentChunkNo, String content, String headingPath,
                              Integer pageStart, Integer pageEnd, Integer charStart, Integer charEnd,
                              boolean available, String embStatus, String editedAt, String embedding) {
    }
}
