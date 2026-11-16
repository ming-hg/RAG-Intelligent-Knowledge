package com.rag.knowledge.app.rag;

import com.rag.knowledge.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文装配：预算算得对不对、父块去重、截断标记、引用编号是否稳定。
 * 这些出错不会抛异常，只会让答案「莫名其妙少了一块」，所以每条规则都钉一个断言。
 */
class ContextPlannerTest {

    private final ContextPlanner planner = new ContextPlanner();
    // 预算算术用固定的 4096：默认窗口跟着模型走（现在是 16384），把断言绑在默认值上等于每换一次模型改一遍数
    private final RagProperties.Budget budget = new RagProperties.Budget(4096, 700, 900, 1.0, 4, 800);

    @Test
    void budgetSubtractsAnswerReserveTemplateAndConversation() {
        // 4096 - 700（回答预留）- 900（模板）- 500（对话）= 1996
        assertThat(ContextPlanner.budgetChars(budget, 500)).isEqualTo(1996);
        assertThat(ContextPlanner.budgetChars(budget, 0)).isEqualTo(2496);
    }

    @Test
    void budgetFloorsAtZeroInsteadOfGoingNegative() {
        assertThat(ContextPlanner.budgetChars(budget, 999_999)).isZero();
        assertThat(ContextPlanner.budgetChars(new RagProperties.Budget(800, 700, 900, 1.0, 4, 800), 0)).isZero();
    }

    @Test
    void charsPerTokenScalesTokensButNotTheAlreadyCharBasedTemplate() {
        RagProperties.Budget twoCharsPerToken = new RagProperties.Budget(4096, 700, 900, 2.0, 4, 800);

        // 窗口与回答预留按系数换算成字符，模板本身就配的是字符，不再乘一遍
        assertThat(ContextPlanner.budgetChars(twoCharsPerToken, 0)).isEqualTo(4096 * 2 - 700 * 2 - 900);
        assertThat(ContextPlanner.budgetChars(new RagProperties.Budget(4096, 700, 900, 0, 4, 800), 0))
                .isEqualTo(ContextPlanner.budgetChars(budget, 0));
    }

    @Test
    void hitsAreNumberedInRelevanceOrderAndRenderedWithLocation() {
        List<SearchHit> hits = List.of(hit(1, "第一段内容", null), hit(2, "第二段内容", null));

        ContextPlanner.Plan plan = planner.assemble(hits, Map.of(), 4000, true);

        assertThat(plan.slots()).extracting(ContextPlanner.Slot::citation).containsExactly(1, 2);
        assertThat(plan.context()).contains("[1] 手册.pdf 第2页 · 安装\n第一段内容")
                .contains("[2] 手册.pdf 第2页 · 安装\n第二段内容");
        assertThat(plan.unplacedHits()).isZero();
        assertThat(plan.truncated()).isFalse();
    }

    @Test
    void childHitExpandsToItsParentAndSiblingsShareOneCitation() {
        SearchHit first = hit(11, "子块A", 99L);
        SearchHit sibling = hit(12, "子块B", 99L);
        Map<Long, String> parents = Map.of(99L, "父块完整段落，比子块长得多，包含上下文。");

        ContextPlanner.Plan plan = planner.assemble(List.of(first, sibling), parents, 4000, true);

        assertThat(plan.slots()).hasSize(1);
        assertThat(plan.slots().get(0).parentExpanded()).isTrue();
        assertThat(plan.slots().get(0).text()).startsWith("父块完整段落");
        // 去重掉的那条算「召回了但没进上下文」，不能当成丢失
        assertThat(plan.unplacedHits()).isEqualTo(1);
    }

    @Test
    void parentExpansionCanBeTurnedOffAndUsesTheChildText() {
        SearchHit child = hit(11, "子块A", 99L);

        ContextPlanner.Plan plan = planner.assemble(List.of(child), Map.of(99L, "父块"), 4000, false);

        assertThat(plan.slots().get(0).text()).isEqualTo("子块A");
        assertThat(plan.slots().get(0).parentExpanded()).isFalse();
    }

    @Test
    void missingParentRowFallsBackToTheChildText() {
        SearchHit hit = hit(11, "子块A", 404L);

        ContextPlanner.Plan plan = planner.assemble(List.of(hit), Map.of(), 4000, true);

        assertThat(plan.slots().get(0).text()).isEqualTo("子块A");
    }

    @Test
    void onlyTheBlockThatBreaksTheBudgetGetsTruncated() {
        List<SearchHit> hits = List.of(hit(1, "A".repeat(300), null), hit(2, "B".repeat(300), null));

        ContextPlanner.Plan plan = planner.assemble(hits, Map.of(), 250, true);

        assertThat(plan.slots()).hasSize(1);
        assertThat(plan.slots().get(0).text()).hasSize(250);
        assertThat(plan.truncated()).isTrue();
        assertThat(plan.unplacedHits()).isEqualTo(1);
    }

    @Test
    void aWindowTooSmallToHoldAnythingProducesAnEmptyPlan() {
        // 剩下不到 120 字时装进去的片段没有语义价值，宁可由上层走「资料不足」分支
        ContextPlanner.Plan plan = planner.assemble(List.of(hit(1, "A".repeat(500), null)), Map.of(), 100, true);

        assertThat(plan.empty()).isTrue();
        assertThat(plan.context()).isEmpty();
        assertThat(plan.unplacedHits()).isEqualTo(1);
    }

    @Test
    void blankContentIsSkippedWithoutConsumingACitationNumber() {
        List<SearchHit> hits = List.of(hit(1, "   ", null), hit(2, "有效内容", null));

        ContextPlanner.Plan plan = planner.assemble(hits, Map.of(), 4000, true);

        assertThat(plan.slots()).hasSize(1);
        assertThat(plan.slots().get(0).citation()).isEqualTo(1);
        assertThat(plan.slots().get(0).hit().segmentId()).isEqualTo(2);
    }

    @Test
    void snippetAndLocationAreReadableForTheCitationPanel() {
        SearchHit hit = new SearchHit(1, 7, "运维手册.pdf", 3, 2, "第一行\n\n第二行  空格", "部署 > 网关",
                4, 6, 10, 40, null, 1, null, 0.0322, 0.71);
        SearchHit anonymous = new SearchHit(1, 7, null, 3, 2, "x", null, null, null, null, null, null,
                null, 2, 0.016, null);
        SearchHit singlePage = new SearchHit(1, 7, "a.md", 3, 2, "x", null, 5, 5, null, null, null,
                null, 2, 0.016, null);

        assertThat(hit.snippet(200)).isEqualTo("第一行 第二行 空格");
        assertThat(hit.location()).isEqualTo("运维手册.pdf 第4-6页 · 部署 > 网关");
        assertThat(anonymous.location()).isEqualTo("文档 7");
        assertThat(singlePage.location()).isEqualTo("a.md 第5页");
    }

    private static SearchHit hit(long segmentId, String content, Long parentId) {
        return new SearchHit(segmentId, 7, "手册.pdf", 3, 2, content, "安装", 2, 2, 0, content.length(),
                parentId, 1, 2, 0.032, 0.71);
    }
}
