package com.rag.knowledge.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 把提交任务那一刻的 MDC 复制到执行任务的那个线程上。
 * <p>
 * traceId 是 {@code TraceIdFilter} 放在 HTTP 线程的 MDC 里的，线程池看不见：不搬过去，一次问答的日志就断成两截
 * ——前半截（检索、装配）有 id，后半截（模型吐字、回填）没有，而这正是最需要按会话捞日志的一段。
 * 入库 worker 不走这里，它按任务自己造 id。
 * <p>
 * 执行完要还原成线程原来的上下文而不是清空：池线程会复用，留着上一条请求的 id 会让两次问答看起来像一次。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        Map<String, String> caller = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> worker = MDC.getCopyOfContextMap();
            apply(caller);
            try {
                task.run();
            } finally {
                apply(worker);
            }
        };
    }

    private static void apply(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
