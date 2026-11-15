package com.rag.knowledge.app.ingest;

import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.TaskRepository;
import com.rag.knowledge.db.TaskRepository.Task;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 入库 worker：轮询 kb_task，一次领一条，跑完再领。
 * <p>
 * 自己起线程而不是 @Scheduled/@Async：需要可控停机（退出时把在跑的任务放回队列）和
 * 「数据库不可用时只报警不退出」的语义，两者都不是注解能表达的。
 */
@Component
public class IngestWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestWorker.class);
    private static final Duration REAP_INTERVAL = Duration.ofSeconds(30);
    private static final Duration IDLE_BACKOFF = Duration.ofSeconds(5);

    private final RagProperties props;
    private final TaskRepository tasks;
    private final IngestService ingestService;
    private final String owner = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    private volatile Instant lastReap = Instant.EPOCH;
    private ExecutorService executor;

    public IngestWorker(RagProperties props, TaskRepository tasks, IngestService ingestService) {
        this.props = props;
        this.tasks = tasks;
        this.ingestService = ingestService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            int reaped = tasks.reapExpiredLeases();
            if (reaped > 0) {
                log.warn("启动回收：{} 个租约过期的任务已重新排队", reaped);
            }
        } catch (RuntimeException e) {
            // 数据库还没起来不能算启动失败：健康检查会报 postgres DOWN，worker 每轮自己重试
            log.error("启动时无法访问任务队列，worker 将继续重试：{}", e.getMessage());
        }
        if (!props.ingest().workerEnabled()) {
            log.info("rag.ingest.worker-enabled=false，入库队列不消费（任务留在 kb_task 里）");
            return;
        }
        int concurrency = Math.max(1, props.ingest().concurrency());
        executor = Executors.newFixedThreadPool(concurrency);
        for (int i = 0; i < concurrency; i++) {
            executor.submit(this::pollLoop);
        }
        log.info("入库 worker 已启动：owner={}，并发={}，轮询间隔={}，租约={}",
                owner, concurrency, props.ingest().pollInterval(), props.ingest().leaseDuration());
    }

    private void pollLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            List<Task> claimed;
            try {
                claimed = tasks.claim(owner, 1, props.ingest().leaseDuration());
            } catch (RuntimeException e) {
                log.error("领取任务失败，{} 后重试：{}", IDLE_BACKOFF, e.getMessage());
                sleep(IDLE_BACKOFF);
                continue;
            }
            if (claimed.isEmpty()) {
                sleep(props.ingest().pollInterval());
                reapExpired();
                continue;
            }
            for (Task task : claimed) {
                ingestService.process(task, owner);
            }
        }
    }

    private void reapExpired() {
        Instant now = Instant.now();
        if (now.isBefore(lastReap.plus(REAP_INTERVAL))) {
            return;
        }
        lastReap = now;
        try {
            int reaped = tasks.reapExpiredLeases();
            if (reaped > 0) {
                log.warn("{} 个任务租约过期，已重新排队", reaped);
            }
        } catch (RuntimeException e) {
            log.warn("租约回收失败：{}", e.getMessage());
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(100, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            int released = tasks.releaseOwned(owner);
            if (released > 0) {
                log.info("停机释放 {} 个在跑任务，重新排队", released);
            }
        } catch (RuntimeException e) {
            log.warn("停机释放任务失败，改由租约过期回收：{}", e.getMessage());
        }
    }
}
