package com.rag.knowledge.web;

import com.rag.knowledge.app.diag.RunDiagnostics;
import com.rag.knowledge.app.rag.ContextPlanner;
import com.rag.knowledge.common.ApiResponse;
import com.rag.knowledge.config.RagProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * GET /api/diagnostics —— 一屏看完「刚刚有没有偷偷变差」和「现在生效的到底是哪套参数」。
 * <p>
 * /api/health 回答的是依赖通不通（PG、模型服务、目录可写），这里回答的是另一类问题：依赖都通，但结果可疑。
 * 本地部署最容易踩的两个坑都不报错——换了个维度相同的 embedding 模型（检索照跑，只是分数不可比），
 * 以及 {@code min-rrf-score} 照抄了余弦阈值的量纲（检索永远 0 命中，看起来像「库里没资料」）。
 * 所以除了回显，这里还带一组当场算出来的告警。
 */
@RestController
@RequestMapping("/api")
public class DiagnosticsController {

    private final RunDiagnostics diagnostics;
    private final RagProperties props;

    public DiagnosticsController(RunDiagnostics diagnostics, RagProperties props) {
        this.diagnostics = diagnostics;
        this.props = props;
    }

    public record Effective(
            String mode, int topK, int candidateK, int rrfK, double minScore, double minRrfScore,
            boolean parentContext, int embeddingDim, int embeddingBatchSize, String embeddingModel,
            String chatModel, String baseUrl, Duration modelTimeout,
            int nCtx, int outputReserveTokens, int templateChars, double charsPerToken,
            int historyMaxTurns, int historyMaxChars, int contextBudgetChars,
            int childChars, int childMinChars, int overlapChars, int parentMaxChars,
            boolean ingestWorkerEnabled, int ingestConcurrency, Duration ingestLease) {
    }

    public record View(RunDiagnostics.Snapshot run, Effective effective, List<String> warnings) {
    }

    @GetMapping("/diagnostics")
    public ApiResponse<View> diagnostics() {
        RagProperties.Retrieval r = props.retrieval();
        RagProperties.Budget b = r.budget();
        RagProperties.Chunk c = props.ingest().chunk();
        RagProperties.LmStudio lm = props.lmStudio();
        RunDiagnostics.Snapshot run = diagnostics.snapshot();
        // 0 = 本轮不带历史与问题时的上限，也就是上下文最多能装多少字；实际一轮会比这个小
        int budget = ContextPlanner.budgetChars(b, 0);
        Effective effective = new Effective(r.mode(), r.topK(), r.candidateK(), r.rrfK(), r.minScore(),
                r.minRrfScore(), r.parentContext(), props.embedding().dim(), props.embedding().batchSize(),
                lm.embeddingModel(), lm.chatModel(), lm.baseUrlV1(), lm.timeout(),
                b.nCtx(), b.outputReserveTokens(), b.templateChars(), b.charsPerToken(),
                b.historyMaxTurns(), b.historyMaxChars(), budget,
                c.childChars(), c.childMinChars(), c.overlapChars(), c.parentMaxChars(),
                props.ingest().workerEnabled(), props.ingest().concurrency(), props.ingest().leaseDuration());
        return ApiResponse.ok(new View(run, effective, warnings(r, b, c, run.lastContextOverflow())));
    }

    /** 只报「配了但几乎一定是错的」那种。告警一多就没人看，和没有一样。 */
    private List<String> warnings(RagProperties.Retrieval r, RagProperties.Budget b, RagProperties.Chunk c,
                                  RunDiagnostics.OverflowEvent overflow) {
        List<String> list = new ArrayList<>();
        // 混合/向量路的分数量级是 1/(k+rank)，第一名也只有 1/(60+1)≈0.016
        double firstRankRrf = 1.0 / (r.rrfK() + 1);
        if (r.minRrfScore() > firstRankRrf) {
            list.add("min-rrf-score=" + r.minRrfScore() + " 高于 RRF 第一名的分数 "
                    + String.format("%.4f", firstRankRrf) + "（1/(rrf-k+1)）——混合检索会长期 0 命中，"
                    + "看起来像库里没资料。这个阈值只能待在 0.01x 量纲");
        }
        if (c.childChars() > 480) {
            list.add("child-chars=" + c.childChars() + " 超过 bge 系列 512 token 窗口（约 480 汉字）——"
                    + "超出部分会被模型静默截断，表现为「文档里明明有却搜不到」");
        }
        // 模型的真实窗口只有被 400 拒过一次才知道，所以这条只在真的撞过之后才出现——但它值得等：
        // n-ctx 配小了不会报错，只会每轮白白少装几块资料，而从答案质量上看不出是配置的问题。
        if (overflow != null && overflow.reportedCtx() != null) {
            int reported = overflow.reportedCtx();
            if (reported > overflow.configuredCtx()) {
                list.add("模型侧回传的真实窗口是 " + reported + " token，配置 n-ctx 只有 " + overflow.configuredCtx()
                        + "：装配预算白少一大截（当前上限 " + ContextPlanner.budgetChars(b, 0) + " 字），"
                        + "把 RAG_LLM_N_CTX 提到 " + reported);
            } else if (overflow.configuredCtx() > reported) {
                list.add("配置 n-ctx=" + overflow.configuredCtx() + " 比模型真实窗口 " + reported
                        + " 还大：装进去的资料会被 400 拒掉，要么在 LM Studio 里调大 context，要么把 n-ctx 降到 "
                        + reported);
            }
        }
        return list;
    }
}
