package com.rag.knowledge.app.eval;

import java.util.List;
import java.util.Locale;

/**
 * 跑分的判据与指标（纯计算，不碰数据库也不碰模型）。
 * <p>
 * 指标只回答一件事：<b>该找到的那块找没找到、排第几，不该找的有没有被牵出来</b>。答案质量不在这里，
 * 因为混进模型随机性之后，同一套参数两次跑出不同分数，调参就没法比较——而这正是评测集存在的唯一理由。
 * <p>
 * 相关性判据用「正文里必须出现 expect_marker」而不是「某个 segment_id」：改分块尺寸或重算向量之后
 * 块的 id 会变，而评测要问的偏偏就是「换参数之后还找不找得到」。
 */
public final class EvalScorer {

    /** 报表除跑分自身的 topK 之外，固定多看这两档：调参时最常见的问题是「第 4 名以后就装不进上下文了」。 */
    static final int SHORT_K_1 = 1;
    static final int SHORT_K_2 = 3;

    private EvalScorer() {
    }

    /**
     * 一次判定的结果。
     *
     * @param invalid   正例没标 expect_marker：判不了。不能算成「没召回」，否则加一批未标注用例会把分数压低
     * @param firstRank 正例里第一条相关命中的名次（1 起），null = 没进前 k
     * @param refused   装配一块都没装下（= 线上真的拒答）。装下 3 块、截掉 3 块不算拒答，那叫截断
     * @param unplaced  这次召回里没装进上下文的块数，只参与汇总，不落库（落库的是 reason 里那句人话）
     * @param reason    错例的人话解释，报表第一列要用
     */
    public record Verdict(boolean positive, boolean invalid, boolean correct, Integer firstRank, int hits,
                          boolean refused, int unplaced, long tookMs, String reason) {
    }

    /**
     * 一次跑分的汇总。分母为 0 时对应指标是 null（不是 0）：
     * 「这批用例里没有负例」和「负例全错」是两件事，压成 0 就分不清了。
     * <p>
     * {@code truncatedCaseRate} 是正例里「有块没装进上下文」的占比——它不参与 hit@k 的判定，
     * 因为 hit@k 量的是检索（相关块排第几），这条量的是装配（排到了但预算装不下）。
     * 两者必须分开看：前者说该调排序，后者说该加大 n_ctx 或减小 parent-max-chars。
     */
    public record Metrics(int cases, int positive, int negative, int invalid,
                          Double hitAt1, Double hitAt3, Double hitAtK, Double mrr,
                          Double refusalAccuracy, Double emptyRecallRate, Double rankedBeyondK,
                          Double truncatedCaseRate, long avgTookMs) {
    }

    /**
     * 判一条用例。
     *
     * @param contents 按名次（1 起）排列的命中正文
     * @param docNames 与 contents 同序的命中文档名
     * @param refused  问答链路会不会拒答 = 装配一块都没装下（{@code plan.empty()}）。
     *                 「召回 6 块只装下 3 块」不是拒答而是截断，模型照样被问到，别把两者混成一个旗子
     * @param unplaced 召回了却没装进上下文的块数（预算截断、父块去重、空正文都算），只用来把人话原因写准
     */
    public static Verdict judge(boolean shouldAnswer, String expectMarker, String expectDoc, List<String> docNames,
                               List<String> contents, boolean refused, int unplaced, long tookMs) {
        if (shouldAnswer && (expectMarker == null || expectMarker.isBlank())) {
            return new Verdict(true, true, false, null, contents.size(), refused, unplaced, tookMs,
                    "正例没标 expect_marker，判不了（补上标注或停用这条）");
        }
        if (!shouldAnswer) {
            // 负例只看「有没有拿出证据」：装配空跑 = 模型根本没被问到 = 拒答正确。
            // refused 这个字段必须照实写：真机第一轮就是这里传了常量 true，报表上才会出现
            // 「refused=true 却判错，原因写着被召回了」这种自相矛盾的行，而它正是幻觉没被压住的那条读数。
            return new Verdict(false, false, refused, null, contents.size(), refused, unplaced, tookMs,
                    refused ? null : "库里没有的东西被召回了：" + docNames.get(0) + " 第 1 名");
        }
        Integer first = null;
        for (int i = 0; i < contents.size(); i++) {
            if (matches(contents.get(i), expectMarker) && docMatches(docNames.get(i), expectDoc)) {
                first = i + 1;
                break;
            }
        }
        String reason;
        if (first == null) {
            reason = explainMiss(contents.size(), refused, unplaced);
        } else {
            // 装配按名次从前往后装、预算不够就停，所以「排第几」和「装了几块」能定出相关块到底进没进模型。
            // 「排到了但没装下」优先级最高：此时 hit@k 记的是命中，线上模型却压根读不到这块
            int placed = contents.size() - unplaced;
            if (first > placed) {
                reason = "排在第 " + first + " 名，本次上下文只装下 " + placed + " 块，模型没读到它";
            } else if (first > SHORT_K_2) {
                reason = "召回了但排在第 " + first + " 名，前 " + SHORT_K_2 + " 名是别的";
            } else if (unplaced > 0) {
                reason = "找到了，但召回 " + contents.size() + " 块里有 " + unplaced + " 块没装进上下文";
            } else {
                reason = null;
            }
        }
        return new Verdict(true, false, first != null, first, contents.size(), refused, unplaced, tookMs, reason);
    }

    /**
     * 正例没找到相关块，三种原因的修法完全不同：一条都没召回是阈值或库里真没有；
     * 召回了但装配装不下是分块太小或预算不够；召回了且装得下、只是相关块排在 k 之外，那才轮到调排序或加大窗口。
     * 混成一句「没召回」，跑分就退化成只能看出好坏、看不出改哪儿。
     */
    private static String explainMiss(int hits, boolean refused, int unplaced) {
        if (hits == 0) {
            return "空召回：阈值过滤后一条命中都没有（库里没有，或 min-score 太高）";
        }
        if (refused) {
            return "召回 " + hits + " 条但装配一条都没装下（正文为空或预算不足）";
        }
        if (unplaced > 0) {
            return "相关块没进前 " + (hits - unplaced) + " 名（召回 " + hits + " 条，其余 " + unplaced
                    + " 条没装进上下文，本来就够不到模型）";
        }
        return "相关块没进前 " + hits + " 名";
    }

    /**
     * 汇总。{@code hitAt1/3/K} 与 {@code mrr} 只在正例上算，{@code refusalAccuracy} 只在负例上算；
     * 空召回率与「排在 k 之外」是正例的两种不同失败——分开看才知道该调阈值还是该改分块。
     * 未标注的用例整个不进分母，否则「加了六条待标注」会读成「检索退化了」。
     */
    public static Metrics aggregate(List<Verdict> verdicts, int topK) {
        int positive = 0, negative = 0, invalid = 0, correctNegative = 0, emptyRecall = 0, beyondK = 0, truncated = 0;
        int hitAt1 = 0, hitAt3 = 0, hitAtK = 0;
        double rankSum = 0;
        long tookSum = 0;
        int judged = 0;
        for (Verdict v : verdicts) {
            if (v.invalid()) {
                invalid++;
                continue;
            }
            judged++;
            tookSum += v.tookMs();
            if (!v.positive()) {
                negative++;
                if (v.correct()) {
                    correctNegative++;
                }
                continue;
            }
            positive++;
            if (v.unplaced() > 0) {
                // 不管相关块找没找到，只要这次召回里有块没进上下文，就是「预算不够」的读数：
                // 它和 hit@k 各算各的——前者该改 n_ctx/parent-max-chars，后者才该动阈值与排序
                truncated++;
            }
            Integer rank = v.firstRank();
            if (rank == null) {
                // 按「有没有召回东西」分，不按「会不会拒答」分：拒答是装配的结果，
                // 一条都没召回和召回了但相关块排在外面的修法不一样
                if (v.hits() == 0) {
                    emptyRecall++;
                } else {
                    beyondK++;
                }
                continue;
            }
            rankSum += 1.0 / rank;
            if (rank <= SHORT_K_1) {
                hitAt1++;
            }
            if (rank <= SHORT_K_2) {
                hitAt3++;
            }
            if (rank <= topK) {
                hitAtK++;
            }
        }
        return new Metrics(verdicts.size(), positive, negative, invalid,
                rate(hitAt1, positive), rate(hitAt3, positive), rate(hitAtK, positive),
                positive == 0 ? null : round(rankSum / positive),
                rate(correctNegative, negative), rate(emptyRecall, positive), rate(beyondK, positive),
                rate(truncated, positive), judged == 0 ? 0 : Math.round((double) tookSum / judged));
    }

    /** 空白折叠后再比：分块会把换行变成空格或反之，字面相等判不了正文。 */
    static boolean matches(String content, String marker) {
        return content != null && fold(content).contains(fold(marker));
    }

    /** 文档名允许只标主干：「运维手册.md」与「运维手册」都算对上，标注的人不用记后缀。 */
    static boolean docMatches(String docName, String expectDoc) {
        if (expectDoc == null || expectDoc.isBlank()) {
            return true;
        }
        if (docName == null) {
            return false;
        }
        String want = fold(stripExtension(expectDoc));
        String have = fold(stripExtension(docName));
        return have.equals(want) || have.contains(want) || want.contains(have);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String fold(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static Double rate(int part, int whole) {
        return whole == 0 ? null : round((double) part / whole);
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
