package com.rag.knowledge.app.rag;

import com.rag.knowledge.app.diag.RunDiagnostics;
import com.rag.knowledge.app.model.ContextOverflow;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import com.rag.knowledge.db.ChatRepository;
import com.rag.knowledge.db.RetrievalRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 问答编排：检索 → 装配上下文 → 落库 → 流式生成 → 回填。
 * <p>
 * 空召回直接给固定话术，一次模型调用都不发。这是整套系统里最要紧的一条兜底：
 * 让小模型「无中生有」只需要一句「请根据资料回答」，而用户看不出区别。
 * <p>
 * 引用编号在装配阶段就定死并随消息一起落库，之后不重算：改一次分块参数就让历史答案的 [2] 指向另一段话，
 * 界面没法解释。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    public static final String STATUS_OK = "OK";
    public static final String STATUS_REFUSED = "REFUSED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";

    private final ChatRepository chats;
    private final RetrievalRepository retrieval;
    private final HybridSearchService searchService;
    private final EvidencePlanner evidence;
    private final StreamingChatModel streamingModel;
    private final RagProperties props;
    private final ObjectMapper json;
    private final RunDiagnostics diagnostics;

    public ChatService(ChatRepository chats, RetrievalRepository retrieval,
                       HybridSearchService searchService, EvidencePlanner evidence,
                       StreamingChatModel streamingModel, RagProperties props, ObjectMapper json,
                       RunDiagnostics diagnostics) {
        this.chats = chats;
        this.retrieval = retrieval;
        this.searchService = searchService;
        this.evidence = evidence;
        this.streamingModel = streamingModel;
        this.props = props;
        this.json = json;
        this.diagnostics = diagnostics;
    }

    public record AskRequest(UUID sessionId, String question, List<Long> kbIds, List<Long> docIds,
                             String mode, Integer topK) {
    }

    /** 一条引用。落库、SSE meta、历史回放三处共用同一形状，避免同一个来源在不同接口里长得不一样。 */
    public record SourceView(int citation, long segmentId, long docId, String docName, int chunkNo, String location,
                             Integer pageStart, Integer pageEnd, boolean parentExpanded, int chars,
                             Integer vectorRank, Integer keywordRank, double rrf, Double similarity) {
    }

    public record MetaView(UUID sessionId, UUID questionId, UUID answerId, String requestedMode, String effectiveMode,
                           String keywordEngine, boolean degraded, String vectorFailure, List<SourceView> sources,
                           int contextChars, int budgetChars, int unplacedHits, boolean contextTruncated,
                           long retrievalMs) {
    }

    public record DoneView(UUID answerId, String status, String model, Integer promptTokens,
                           Integer completionTokens, long answerMs, long totalMs) {
    }

    /** SSE 的实现留在 web 层，服务只认这个接口，方便用假 sink 断言事件顺序。 */
    public interface Sink {

        void meta(MetaView meta);

        void delta(String text);

        void done(DoneView done);

        void error(int code, String message);

        void complete();
    }

    public void ask(AskRequest request, Sink sink) {
        long start = System.currentTimeMillis();
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isBlank()) {
            sink.error(400, "问题不能为空");
            sink.complete();
            return;
        }
        String traceId = MDC.get("traceId");
        try {
            run(request, question, sink, start, traceId);
        } catch (RagException ex) {
            // 业务异常带着给人看的话，原样送出；其它异常走下面的通用分支
            sink.error(ex.code(), ex.getMessage() + hints(ex));
            sink.complete();
        } catch (RuntimeException ex) {
            log.error("问答失败", ex);
            sink.error(500, "问答失败：" + rootMessage(ex));
            sink.complete();
        }
    }

    private void run(AskRequest request, String question, Sink sink, long start, String traceId) {
        ChatRepository.Session session = resolveSession(request);
        // 范围先于任何写入决定：范围缺失是 400，此时问题还没入库，会话里不会留下一条答不下去的问句
        List<Long> scopedKbIds = scopeKbIds(request, session);
        // 历史要在写入本轮问题之前取，否则本轮问题会作为「上一轮」重复进 prompt
        List<ChatRepository.Message> history = chats.listMessages(session.id(),
                Math.max(1, props.retrieval().budget().historyMaxTurns()) * 2);
        UUID questionId = chats.insertQuestion(session.id(), question, traceId);
        chats.nameFromFirstQuestion(session.id(), question);

        HybridSearchService.Outcome outcome = searchService.search(question, scopedKbIds,
                request.docIds(), request.mode(), request.topK());
        RagProperties.Budget budget = props.retrieval().budget();
        List<ChatRepository.Message> recent = trimHistory(history, budget);
        int conversationChars = recent.stream().mapToInt(m -> m.contextText().length()).sum() + question.length();
        // 装配这一步交给 EvidencePlanner：评测集算「会不会拒答」用的是同一个方法，两处不能各写一遍
        ContextPlanner.Plan plan = evidence.plan(outcome.hits(), conversationChars);
        List<SourceView> sources = plan.slots().stream().map(ChatService::toSource).toList();
        UUID answerId = chats.beginAnswer(session.id(), "", question, jsonOf(sources),
                jsonOf(metaOf(outcome, plan)), traceId);
        MetaView meta = new MetaView(session.id(), questionId, answerId, outcome.requested().name(),
                outcome.effective().name(), outcome.engine().name(), outcome.degraded(), outcome.vectorFailure(),
                sources, plan.usedChars(), plan.budgetChars(), plan.unplacedHits(), plan.truncated(),
                outcome.tookMs());
        sink.meta(meta);

        if (plan.truncated()) {
            diagnostics.contextTruncated();
            // 截断只会让答案「少了后半段」，用户以为是模型能力问题；这里必须留下一条能对上的记录
            log.warn("上下文被预算截断 answerId={} usedChars={} budgetChars={} 召回={} 未装下={}",
                    answerId, plan.usedChars(), plan.budgetChars(), outcome.hits().size(), plan.unplacedHits());
        }
        if (plan.empty()) {
            diagnostics.refused();
            // 拒答的原因只有两种：库里真的没有 / 检索这路没走成。分不开就得靠猜，所以两条都打进日志
            log.warn("无证据拒答 answerId={} 问题=「{}」kbIds={} 请求模式={} 实际模式={} engine={} 命中={} 预算={} 字",
                    answerId, brief(question), scopedKbIds, outcome.requested(), outcome.effective(),
                    outcome.engine(), outcome.hits().size(), plan.budgetChars());
            // 没有资料就不问模型：固定话术直接返回，状态记成 REFUSED，评测时能与「答错」区分开
            chats.finishAnswer(answerId, AnswerPrompt.NO_EVIDENCE, STATUS_REFUSED, null, null, null);
            sink.delta(AnswerPrompt.NO_EVIDENCE);
            sink.done(new DoneView(answerId, STATUS_REFUSED, null, null, null, 0,
                    System.currentTimeMillis() - start));
            sink.complete();
            return;
        }
        retrieval.touchHits(plan.segmentIds());

        List<ChatMessage> messages = new ArrayList<>();
        AnswerPrompt.Messages prompt = AnswerPrompt.build(plan.context(), question);
        messages.add(SystemMessage.from(prompt.system()));
        messages.addAll(toChatHistory(recent));
        messages.add(UserMessage.from(prompt.user()));

        long genStart = System.currentTimeMillis();
        StringBuilder answer = new StringBuilder();
        streamingModel.chat(messages, new StreamingChatResponseHandler() {

            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) {
                    return;
                }
                answer.append(partial);
                sink.delta(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                String text = response.aiMessage() == null || response.aiMessage().text() == null
                        ? answer.toString() : response.aiMessage().text();
                Integer promptTokens = response.tokenUsage() == null ? null : response.tokenUsage().inputTokenCount();
                Integer completionTokens = response.tokenUsage() == null ? null
                        : response.tokenUsage().outputTokenCount();
                chats.finishAnswer(answerId, text, STATUS_OK, promptTokens, completionTokens, response.modelName());
                sink.done(new DoneView(answerId, STATUS_OK, response.modelName(), promptTokens, completionTokens,
                        System.currentTimeMillis() - genStart, System.currentTimeMillis() - start));
                sink.complete();
            }

            @Override
            public void onError(Throwable error) {
                if (ContextOverflow.from(error)) {
                    overflow(answerId, answer, plan, sink, error);
                    return;
                }
                String reason = rootMessage(error);
                log.warn("流式生成失败，answerId={}：{}", answerId, reason);
                // 已经吐出去的字要保住：回填成 INTERRUPTED 而不是删掉，界面上能看到断在哪里
                chats.markAnswer(answerId, answer.toString(), STATUS_INTERRUPTED);
                sink.error(503, "模型生成中断：" + reason);
                sink.complete();
            }
        });
    }

    /**
     * 超限发生在生成开始之前，一个都还没开始想：状态记 FAILED 而不是 INTERRUPTED，
     * 评测时才分得清「装配失败」和「生成中断」是两类原因。
     * <p>
     * 不在这里自动重装配：引用编号已经随 meta 发给前端并写进库里，偷偷少装两块会让 [2] 指向另一段话。
     * 所以把模型回传的真实 n_ctx 说清楚，让人去调 top-k 或配置，而不是让程序猜。
     */
    private void overflow(UUID answerId, StringBuilder answer, ContextPlanner.Plan plan, Sink sink, Throwable error) {
        RagProperties.Budget budget = props.retrieval().budget();
        Integer realCtx = ContextOverflow.reportedCtx(error);
        Integer promptTokens = ContextOverflow.reportedPromptTokens(error);
        String reason = "配置窗口 " + budget.nCtx() + " token，模型侧 "
                + (realCtx == null ? "未回传窗口" : "回传 " + realCtx + " token")
                + "，本次提示词 " + (promptTokens == null ? "?  token" : promptTokens + " token")
                + "，装配 " + plan.usedChars() + " 字";
        diagnostics.contextOverflow(budget.nCtx(), realCtx, reason);
        log.warn("上下文超出模型窗口，answerId={}：{}", answerId, reason);
        chats.markAnswer(answerId, answer.toString(), STATUS_FAILED);
        List<String> hints = new ArrayList<>();
        hints.add("调小 rag.retrieval.top-k 或 rag.ingest.chunk.child-chars，少装几块");
        if (realCtx != null && realCtx > budget.nCtx()) {
            hints.add("模型实际窗口是 " + realCtx + "，把 RAG_LLM_N_CTX 提到它，否则每轮白白少装资料");
        } else if (realCtx != null) {
            hints.add("模型实际窗口只有 " + realCtx + "，要在 LM Studio 里调大 context，"
                    + "或把 RAG_LLM_N_CTX 降到 " + realCtx);
        }
        sink.error(413, "上下文超出模型窗口：" + reason + " ｜ " + String.join(" ｜ ", hints));
        sink.complete();
    }

    /**
     * 历史按「轮」和「字符」双重上限裁剪，从最近一条往回填：
     * 用户刚说过的话一定要在场，被挤掉的一定是最早的，否则追问会指代到不存在的内容。
     */
    static List<ChatRepository.Message> trimHistory(List<ChatRepository.Message> history,
                                                    RagProperties.Budget budget) {
        int maxMessages = Math.max(0, budget.historyMaxTurns()) * 2;
        List<ChatRepository.Message> recent = history.size() > maxMessages
                ? history.subList(history.size() - maxMessages, history.size()) : history;
        int maxChars = Math.max(0, budget.historyMaxChars());
        List<ChatRepository.Message> kept = new ArrayList<>();
        int chars = 0;
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatRepository.Message message = recent.get(i);
            String text = message.contextText();
            if (text == null || text.isBlank() || STATUS_REFUSED.equals(message.status())) {
                continue;
            }
            if (!kept.isEmpty() && chars + text.length() > maxChars) {
                break;
            }
            chars += text.length();
            kept.add(0, message);
        }
        return kept;
    }

    private ChatRepository.Session resolveSession(AskRequest request) {
        if (request.sessionId() != null) {
            return chats.requireSession(request.sessionId());
        }
        // 新会话的范围只能来自请求。先验后建：否则一个注定 400 的请求会先留下一行垃圾会话，
        // 用户下次打开列表会看见它，比多打一次模型难查得多。
        if (request.kbIds() == null || request.kbIds().isEmpty()) {
            throw missingScope();
        }
        return chats.createSession("新会话", List.copyOf(request.kbIds()));
    }

    /**
     * 起流之前就定得下来的错，用异常离开，而不是塞进事件流里的 error：控制器据此回 JSON 信封，
     * 调用方不必为了一个 400 先开一条流再读事件。ask() 里会自己再算一遍范围，两处共用
     * {@link #scopeKbIds}，不存在「校验和执行的规则不一样」这种分裂。
     */
    public void requireScope(AskRequest request) {
        scopeKbIds(request, request.sessionId() == null ? null : chats.requireSession(request.sessionId()));
    }

    /**
     * 检索范围必须是明确的：请求带的 kbIds 优先，其次是会话自己选过的库。
     * 这里**不兜底默认知识库**——「全库检索」在单机上听起来方便，实际是把无关库的资料塞进上下文，
     * 逼模型编一段衔接话，而且用户从答案上看不出来。
     */
    private List<Long> scopeKbIds(AskRequest request, ChatRepository.Session session) {
        if (request.kbIds() != null && !request.kbIds().isEmpty()) {
            return List.copyOf(request.kbIds());
        }
        if (session != null && !session.kbIds().isEmpty()) {
            return session.kbIds();
        }
        throw missingScope();
    }

    private static RagException missingScope() {
        return RagException.badRequest("问答必须落在明确的检索范围里：请求带 kbIds，或会话本身选过知识库",
                "前端在左上角选至少一个库；直接调 API 就在 body 里给 kbIds");
    }

    /** 历史按 role 直白映射：本轮问题作为最后一条 user 消息单独拼，不在这里出现。 */
    private static List<ChatMessage> toChatHistory(List<ChatRepository.Message> history) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatRepository.Message message : history) {
            String text = message.contextText();
            if (text == null || text.isBlank() || STATUS_REFUSED.equals(message.status())) {
                continue;
            }
            if (message.fromUser()) {
                messages.add(UserMessage.from(text));
            } else if ("assistant".equals(message.role())) {
                messages.add(AiMessage.from(text));
            }
        }
        return messages;
    }

    private static SourceView toSource(ContextPlanner.Slot slot) {
        SearchHit hit = slot.hit();
        return new SourceView(slot.citation(), hit.segmentId(), hit.docId(), hit.docName(), hit.chunkNo(),
                hit.location(), hit.pageStart(), hit.pageEnd(), slot.parentExpanded(), slot.chars(),
                hit.vectorRank(), hit.keywordRank(), hit.rrf(), hit.similarity());
    }

    private static Map<String, Object> metaOf(HybridSearchService.Outcome outcome, ContextPlanner.Plan plan) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestedMode", outcome.requested().name());
        meta.put("effectiveMode", outcome.effective().name());
        meta.put("keywordEngine", outcome.engine().name());
        meta.put("vectorFailure", outcome.vectorFailure());
        meta.put("hitCount", outcome.hits().size());
        meta.put("usedBlocks", plan.slots().size());
        meta.put("contextChars", plan.usedChars());
        meta.put("budgetChars", plan.budgetChars());
        meta.put("unplacedHits", plan.unplacedHits());
        meta.put("contextTruncated", plan.truncated());
        meta.put("retrievalMs", outcome.tookMs());
        return meta;
    }

    private String jsonOf(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (RuntimeException ex) {
            log.warn("检索元信息序列化失败，按空值落库：{}", ex.getMessage());
            return value instanceof List<?> ? "[]" : "{}";
        }
    }

    private static String hints(RagException ex) {
        List<String> hints = ex.hints().stream().filter(Objects::nonNull).toList();
        return hints.isEmpty() ? "" : " ｜ " + String.join(" ｜ ", hints);
    }

    /** 日志里只留问题开头：整段粘贴进来的文本会把这一行挤成没人会读的一块砖。 */
    private static String brief(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
