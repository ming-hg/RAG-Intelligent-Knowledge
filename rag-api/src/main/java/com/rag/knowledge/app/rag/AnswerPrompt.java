package com.rag.knowledge.app.rag;

/**
 * 提示词模板。拆成 system + user 两段，是为了让「资料」和「本轮问题」在模型眼里不是一回事：
 * 小模型会把长资料里的句子当成用户在跟它对话，从而接着复述而不是回答。
 */
public final class AnswerPrompt {

    /**
     * 空召回时的固定话术。这句话不调用模型：没有资料还让模型开口，它一定开始编，
     * 而编出来的内容带着「引用格式」看起来格外可信。
     */
    public static final String NO_EVIDENCE =
            "知识库里没有检索到与这个问题相关的内容，所以我不能给出答案。"
                    + "可以换个说法再问一次，或者确认相关文档已经入库到「就绪」。";

    private static final String SYSTEM = """
            你是一个严格依据给定资料作答的知识库助手。规则：
            1. 只能使用【资料】中的信息回答；资料里没有的，直接说不知道，不要用自己的知识补。
            2. 每个结论后面用 [编号] 标注来源，编号对应资料前的 [n]；一句话来自多处就写 [1][3]。
            3. 资料之间互相矛盾时，把两种说法和各自出处都摆出来，不要自行裁决。
            4. 资料被截断或不足以支撑完整回答时，先说明缺什么，再就已有部分作答。
            5. 用简体中文回答，结论先行，其次要点，最后补充；不要复述资料原文。""";

    private AnswerPrompt() {
    }

    public record Messages(String system, String user) {
    }

    /**
     * @param context 已编号的资料块文本，空串表示这次没有资料
     * @param question 本轮问题
     */
    public static Messages build(String context, String question) {
        String body = context == null || context.isBlank()
                ? "【资料】\n（本次没有检索到资料）\n\n【问题】\n" + question
                : "【资料】\n" + context + "\n\n【问题】\n" + question;
        return new Messages(SYSTEM, body);
    }
}
