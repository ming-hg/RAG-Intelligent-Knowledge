package com.rag.knowledge.app.ingest;

/**
 * 重试也不会变好的失败：文件本身有问题、维度对不上、解析不出正文。
 * <p>
 * 区分这一类很重要——否则一个乱码 PDF 会把队列的三次重试全部烧完，
 * 用户看到的是「转了三分钟然后失败」，而正确答案是「换个文件」。
 */
public class PermanentIngestException extends RuntimeException {

    private final String errorCode;

    public PermanentIngestException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
