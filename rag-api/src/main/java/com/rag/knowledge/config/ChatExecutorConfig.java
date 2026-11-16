package com.rag.knowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 流式问答专用线程池。
 * <p>
 * 单独开一个池是为了把「等待模型吐字」和「处理 HTTP 请求」隔开：SSE 一挂就是几十秒到几分钟，
 * 占着 Web 层的请求线程，几个并发就能把整个后台堵成无响应。
 * <p>
 * 上限刻意压小：本地单卡，第 N 路并发生成只会让所有回答一起变慢，不如明确拒绝，让客户端重试。
 */
@Configuration
public class ChatExecutorConfig {

    public static final String CHAT_EXECUTOR = "chatExecutor";

    private static final int CONCURRENT_STREAMS = 4;
    private static final int WAITING_ROOM = 8;

    @Bean(name = CHAT_EXECUTOR)
    public Executor chatExecutor() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(CONCURRENT_STREAMS);
        pool.setMaxPoolSize(CONCURRENT_STREAMS);
        pool.setQueueCapacity(WAITING_ROOM);
        pool.setThreadNamePrefix("rag-chat-");
        // 流式跑在这个池上，不把 traceId 带过去，一次问答的日志会断成两截
        pool.setTaskDecorator(new MdcTaskDecorator());
        // 关闭时给在跑的流一点时间收尾，否则每次重启都会留下一堆 INTERRUPTED 却没有半句解释
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(20);
        pool.initialize();
        return pool;
    }
}
