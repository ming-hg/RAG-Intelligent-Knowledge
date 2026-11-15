package com.rag.knowledge.app.rag;

import com.rag.knowledge.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文装配：把检索命中按相关度排进有限的上下文窗口，并给出稳定的引用编号。
 * <p>
 * 预算按「字符」算而不是按 token 算：本地模型没有可靠的免费分词接口，为算长度多打一次服务不值。
 * 换算系数来自配置（中文约 1 字 ≈ 1 token，取 1.0 是偏保守的一侧），所以宁可少装也不要撑爆 n_ctx——
 * 撑爆的后果是模型静默丢掉中间段落，答案看起来「少了一块」却没有任何报错。
 * <p>
 * 子块命中时换成父块正文： embedding 要短、给模型的要长，这两个诉求靠父子块分开同时满足。
 */
@Component
public class ContextPlanner {

    /** 只剩这么点空间时装进去的片段已经没有语义价值，不如空着让模型明确说「资料不足」。 */
    private static final int MIN_USEFUL_CHARS = 120;
    private static final String SEPARATOR = "\n\n";

    public record Slot(int citation, SearchHit hit, boolean parentExpanded, String text, int chars) {
    }

    public record Plan(List<Slot> slots, String context, int usedChars, int budgetChars, int unplacedHits,
                       boolean truncated) {

        public boolean empty() {
            return slots.isEmpty();
        }

        public List<Long> segmentIds() {
            return slots.stream().map(s -> s.hit().segmentId()).toList();
        }
    }

    /**
     * 可用于装资料的字符数 = 窗口总量 - 回答预留 - 提示词模板 - 对话部分（历史 + 本轮问题）。
     * 结果为负说明 n_ctx 小到装不下「回答 + 模板」，此时装配直接空跑，让上层去提示调大窗口。
     */
    public static int budgetChars(RagProperties.Budget budget, int conversationChars) {
        double perToken = budget.charsPerToken() <= 0 ? 1.0 : budget.charsPerToken();
        int total = (int) Math.round(budget.nCtx() * perToken);
        int reserve = (int) Math.round(budget.outputReserveTokens() * perToken);
        return Math.max(0, total - reserve - budget.templateChars() - Math.max(0, conversationChars));
    }

    public Plan assemble(List<SearchHit> hits, Map<Long, String> parentContents, int budgetChars,
                         boolean useParentContext) {
        List<Slot> slots = new ArrayList<>();
        // 同一个父块被多个子块命中时只装一次，后续子块不再产生新引用
        Map<Long, Integer> parentCitation = new LinkedHashMap<>();
        int used = 0;
        boolean truncated = false;

        for (SearchHit hit : hits) {
            boolean expand = useParentContext && hit.parentId() != null;
            if (expand && parentCitation.containsKey(hit.parentId())) {
                continue;
            }
            if (budgetChars - used < MIN_USEFUL_CHARS) {
                break;
            }
            String full = expand ? parentContents.getOrDefault(hit.parentId(), hit.content()) : hit.content();
            if (full == null || full.isBlank()) {
                continue;
            }
            int room = budgetChars - used;
            String text = full;
            if (full.length() > room) {
                text = full.substring(0, room);
                truncated = true;
            }
            int citation = slots.size() + 1;
            slots.add(new Slot(citation, hit, expand, text, text.length()));
            if (expand) {
                parentCitation.put(hit.parentId(), citation);
            }
            used += text.length() + SEPARATOR.length();
        }

        StringBuilder context = new StringBuilder(used);
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (i > 0) {
                context.append(SEPARATOR);
            }
            context.append('[').append(slot.citation()).append("] ")
                    .append(slot.hit().location()).append('\n')
                    .append(slot.text());
        }
        // 召回了但没进上下文的条数（父块去重、空正文、预算不足都算），前端用它提示「还有资料没装下」
        int unplaced = hits.size() - slots.size();
        return new Plan(List.copyOf(slots), context.toString(), context.length(), budgetChars, unplaced, truncated);
    }
}
