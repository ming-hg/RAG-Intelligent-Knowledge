package com.rag.knowledge.app.ingest;

import com.rag.knowledge.db.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 崩溃残留清理：上一进程被 kill 时，文档会停在 PARSING/EMBEDDING 这类中间态，
 * 队列里的任务则停在 RUNNING。两边都必须落地成「失败 + 可重试」，否则界面上永远转圈。
 */
@Component
public class IngestStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(IngestStartupRecovery.class);

    private final DocumentRepository documents;

    public IngestStartupRecovery(DocumentRepository documents) {
        this.documents = documents;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        int failed;
        try {
            failed = documents.failInterrupted("服务重启时该文档仍在入库中，已置为失败，可直接重试");
        } catch (RuntimeException e) {
            log.error("启动清理中断文档失败（数据库未就绪？）：{}", e.getMessage());
            return;
        }
        if (failed > 0) {
            log.warn("启动清理：{} 个中断在入库过程中的文档已标记为失败", failed);
        }
    }
}
