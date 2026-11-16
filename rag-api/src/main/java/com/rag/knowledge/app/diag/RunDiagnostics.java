package com.rag.knowledge.app.diag;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内的「安静失败」计数器。
 * <p>
 * 降级、截断、拒答单看一次都是合法结果（模型没加载、预算装不下、库里确实没资料），所以它们都不该让请求失败。
 * 危险在于连着来：一轮实测跑完如果 {@code vectorDegraded > 0}，那这一轮的检索结论整批不可信——
 * 分数是关键词路给的，向量路根本没参与。这个端点存在的全部理由就是让「刚刚有没有偷偷变差」可以在一秒钟内看到，
 * 而不是让人去 grep 日志。
 * <p>
 * 只记计数与最后一次的原因，不做直方图也不落库：重启归零是可接受的，需要长期统计看 {@code deploy/logs} 里的文件，
 * 那里每一条都带着 traceId。
 */
@Component
public class RunDiagnostics {

    private final Instant since = Instant.now();
    private final LongAdder vectorDegraded = new LongAdder();
    private final LongAdder contextTruncated = new LongAdder();
    private final LongAdder refused = new LongAdder();
    private final LongAdder embeddingFailures = new LongAdder();
    private final LongAdder contextOverflow = new LongAdder();

    private volatile Event lastVectorFailure;
    private volatile Event lastEmbeddingFailure;
    private volatile OverflowEvent lastContextOverflow;

    public record Event(Instant at, String reason) {
    }

    /**
     * 超限事件必须带着「配了多少」和「模型实际给了多少」两个数：只有 reason 文本的话，
     * 想比较大小就得从中文句子里正则抠数字，而那句话里恰好有两个 n_ctx。
     */
    public record OverflowEvent(Instant at, int configuredCtx, Integer reportedCtx, String reason) {
    }

    public record Counters(long vectorDegraded, long contextTruncated, long refused, long embeddingFailures,
                           long contextOverflow) {
    }

    public record Snapshot(Instant since, long uptimeSeconds, Counters counters,
                           Event lastVectorFailure, Event lastEmbeddingFailure, OverflowEvent lastContextOverflow) {
    }

    /** 检索的向量路没走成，本次只用关键词路出结果。 */
    public void vectorDegraded(String reason) {
        vectorDegraded.increment();
        lastVectorFailure = event(reason);
    }

    /** 一次查询向量化失败既算降级也算模型故障，两个数回答的是不同的问题：还要不要用这批结果 / 该不该去动 LM Studio。 */
    public void embeddingFailure(String reason) {
        embeddingFailures.increment();
        lastEmbeddingFailure = event(reason);
    }

    public void contextTruncated() {
        contextTruncated.increment();
    }

    public void refused() {
        refused.increment();
    }

    /** 资料装得比模型窗口还多，请求在生成开始之前就被 400 拒了。 */
    public void contextOverflow(int configuredCtx, Integer reportedCtx, String reason) {
        contextOverflow.increment();
        lastContextOverflow = new OverflowEvent(Instant.now(), configuredCtx, reportedCtx,
                reason == null || reason.isBlank() ? "未给出原因" : reason);
    }

    public Snapshot snapshot() {
        return new Snapshot(since, Duration.between(since, Instant.now()).getSeconds(),
                new Counters(vectorDegraded.sum(), contextTruncated.sum(), refused.sum(), embeddingFailures.sum(),
                        contextOverflow.sum()),
                lastVectorFailure, lastEmbeddingFailure, lastContextOverflow);
    }

    /** 只给测试用：计数器是进程级的，用例之间必须能清干净。 */
    public void reset() {
        vectorDegraded.reset();
        contextTruncated.reset();
        refused.reset();
        embeddingFailures.reset();
        contextOverflow.reset();
        lastVectorFailure = null;
        lastEmbeddingFailure = null;
        lastContextOverflow = null;
    }

    private static Event event(String reason) {
        return new Event(Instant.now(), reason == null || reason.isBlank() ? "未给出原因" : reason);
    }
}
