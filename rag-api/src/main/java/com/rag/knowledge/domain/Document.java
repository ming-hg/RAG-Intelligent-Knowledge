package com.rag.knowledge.domain;

/**
 * 文档行（不含正文）。正文单独取，列表接口不应该把几 MB 文本读进内存。
 */
public record Document(
        long id,
        long kbId,
        String name,
        String sha256,
        String filePath,
        Long fileSize,
        String mime,
        Integer pageCount,
        Integer charCount,
        DocStatus status,
        int progress,
        String progressMsg,
        int version,
        int indexVersion,
        String errorCode,
        String errorMsg) {

    public boolean rerunnable() {
        return status != null && status.isRerunnable();
    }
}
