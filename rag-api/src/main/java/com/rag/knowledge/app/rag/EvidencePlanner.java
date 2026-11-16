package com.rag.knowledge.app.rag;

import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.RetrievalRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 「命中之后到底给模型看什么」这一步的唯一出处。
 * <p>
 * 父块取正文 + 预算换算 + 装配这三件事必须绑在一起：问答靠 {@code plan.empty()} 决定拒答，
 * 评测集也要靠它算拒答正确率。两边各写一遍的话，评测报的「会不会拒答」和真实行为可以悄悄分叉，
 * 而分叉的方向恰好是让分数看着更漂亮。
 */
@Component
public class EvidencePlanner {

    private final RetrievalRepository retrieval;
    private final ContextPlanner planner;
    private final RagProperties props;

    public EvidencePlanner(RetrievalRepository retrieval, ContextPlanner planner, RagProperties props) {
        this.retrieval = retrieval;
        this.planner = planner;
        this.props = props;
    }

    /**
     * @param conversationChars 本轮问题 + 要带上的历史，这些先占掉预算，剩下的才是资料空间
     */
    public ContextPlanner.Plan plan(List<SearchHit> hits, int conversationChars) {
        RagProperties.Retrieval cfg = props.retrieval();
        int budgetChars = ContextPlanner.budgetChars(cfg.budget(), conversationChars);
        Map<Long, String> parents = retrieval.parentContents(
                hits.stream().map(SearchHit::parentId).toList());
        return planner.assemble(hits, parents, budgetChars, cfg.parentContext());
    }
}
