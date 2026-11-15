package com.rag.knowledge.app.rag;

import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.RetrievalRepository;
import com.rag.knowledge.db.SegmentRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 混合检索的编排：算查询向量 → 决定实际能走哪几路 → 一条 SQL 融合 → 给出「这次到底怎么搜的」。
 * <p>
 * 实际模式可能与请求模式不同（embedding 服务挂了、库里没建分词列），差异全部写进 Outcome 回给前端。
 * 静默降级是最难排查的行为：用户只会觉得「答案变笨了」，说不出哪里变了。
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final RetrievalRepository retrieval;
    private final EmbeddingModel embeddingModel;
    private final RagProperties props;

    private volatile KeywordEngine engine;

    public HybridSearchService(RetrievalRepository retrieval, EmbeddingModel embeddingModel, RagProperties props) {
        this.retrieval = retrieval;
        this.embeddingModel = embeddingModel;
        this.props = props;
    }

    /**
     * @param vectorFailure 向量路没走成的原因，非空时前端要亮黄条；两路都不可用时它是唯一的解释
     */
    public record Outcome(RetrievalMode requested, RetrievalMode effective, KeywordEngine engine,
                          String vectorFailure, List<SearchHit> hits, long tookMs) {

        public boolean degraded() {
            return requested != effective || vectorFailure != null;
        }
    }

    public Outcome search(String question, List<Long> kbIds, List<Long> docIds, String modeOverride,
                          Integer topKOverride) {
        long start = System.currentTimeMillis();
        if (kbIds == null || kbIds.isEmpty()) {
            throw RagException.badRequest("检索必须指定至少一个知识库（kbId）");
        }
        RetrievalSettings settings = RetrievalSettings.of(props.retrieval(), modeOverride, topKOverride);
        KeywordEngine available = engine();
        String vectorLiteral = null;
        String vectorFailure = null;
        // 关键词路整个不可用时，向量是唯一的退路：即使请求写的是 keyword 也得把向量算出来
        if (settings.mode().usesVector() || !available.available()) {
            try {
                vectorLiteral = embed(question);
            } catch (RuntimeException ex) {
                vectorFailure = rootMessage(ex);
                log.warn("查询向量计算失败，本次检索降级为关键词路：{}", vectorFailure);
            }
        }

        RetrievalMode effective = effectiveMode(settings.mode(), vectorLiteral != null, available.available());
        if (effective == null) {
            return new Outcome(settings.mode(), settings.mode(), available,
                    vectorFailure == null
                            ? "向量路与关键词路都不可用：库内没有已生成向量的块，且数据库没有可用的中文全文能力"
                            : vectorFailure,
                    List.of(), System.currentTimeMillis() - start);
        }
        double threshold = effective.effectiveThreshold(settings.minScore(), settings.minRrfScore());
        RetrievalQuery query = new RetrievalQuery(List.copyOf(kbIds),
                docIds == null ? List.of() : List.copyOf(docIds), question, vectorLiteral, effective, available,
                settings.candidateK(), settings.topK(), settings.rrfK(), threshold);
        List<SearchHit> hits = retrieval.search(query);
        return new Outcome(settings.mode(), effective, available, vectorFailure, hits,
                System.currentTimeMillis() - start);
    }

    /** 关键词能力取决于 migration 结果，运行期不会变，探测一次记下来。 */
    public KeywordEngine engine() {
        KeywordEngine cached = engine;
        if (cached == null) {
            cached = retrieval.probeEngine();
            engine = cached;
        }
        return cached;
    }

    /** 装完 zhparser 或重跑 migration 之后调一下，不必重启。 */
    public void refreshEngine() {
        engine = retrieval.probeEngine();
    }

    /** 两路都不可用时返回 null，由调用方给出人话，别让一条没有 CTE 的 SQL 打到数据库。 */
    static RetrievalMode effectiveMode(RetrievalMode requested, boolean vectorReady, boolean keywordReady) {
        if (!vectorReady && !keywordReady) {
            return null;
        }
        return switch (requested) {
            case VECTOR -> vectorReady ? RetrievalMode.VECTOR : RetrievalMode.KEYWORD;
            case KEYWORD -> keywordReady ? RetrievalMode.KEYWORD : RetrievalMode.VECTOR;
            case HYBRID -> vectorReady && keywordReady
                    ? RetrievalMode.HYBRID
                    : vectorReady ? RetrievalMode.VECTOR : RetrievalMode.KEYWORD;
        };
    }

    private String embed(String question) {
        Embedding embedding = embeddingModel.embed(question).content();
        int expected = props.embedding().dim();
        if (embedding.dimension() != expected) {
            throw new IllegalStateException("模型返回 " + embedding.dimension() + " 维向量，而 rag.embedding.dim="
                    + expected + "；库里的向量列宽与之不符，请改回原模型或重建 schema 并全量重算");
        }
        return SegmentRepository.toVectorLiteral(embedding.vector());
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
