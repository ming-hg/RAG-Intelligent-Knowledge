package com.rag.knowledge.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traceId 跨线程的传递。这里只关心日志上能不能对得上号：问答的检索部分在 HTTP 线程、生成部分在 rag-chat-* 线程，
 * 少搬这一步，一次问答的日志就是两段互不相干的行。
 */
class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @Test
    void callerTraceIdReachesTheWorkerThreadAndIsCleanedUpAfterwards() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 先让池线程跑一次「没有上下文」的任务，把它变成一个带脏上下文的复用线程
            runOnPool(pool, () -> MDC.put("traceId", "上一轮残留"));
            AtomicReference<String> inside = new AtomicReference<>();
            AtomicReference<String> after = new AtomicReference<>();

            MDC.put("traceId", "abcd1234");
            Runnable decorated = decorator.decorate(() -> inside.set(MDC.get("traceId")));
            pool.submit(() -> {
                decorated.run();
                after.set(MDC.get("traceId"));
            });
            pool.submit(() -> {
            }).get(5, TimeUnit.SECONDS);

            assertThat(inside.get()).isEqualTo("abcd1234");
            // 线程会复用：跑完要把线程原来的上下文放回去，不能让上一个请求的 traceId 跟着这条线程去服务下一个请求
            assertThat(after.get()).isEqualTo("上一轮残留");
        } finally {
            pool.shutdownNow();
            MDC.clear();
        }
    }

    @Test
    void submittingWithoutAnyTraceIdDoesNotInventOne() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            runOnPool(pool, () -> MDC.put("traceId", "stale"));
            MDC.remove("traceId");
            AtomicReference<String> inside = new AtomicReference<>("not-run");

            runOnPool(pool, decorator.decorate(() -> inside.set(MDC.get("traceId"))));

            assertThat(inside.get()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void runOnPool(ExecutorService pool, Runnable task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        pool.submit(() -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
