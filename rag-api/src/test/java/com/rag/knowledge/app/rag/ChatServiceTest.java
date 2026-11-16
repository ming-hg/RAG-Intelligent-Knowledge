package com.rag.knowledge.app.rag;

import com.rag.knowledge.app.diag.RunDiagnostics;
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
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问答编排的事件顺序、落库时机与「空召回不发模型」这条底线。
 * <p>
 * 流式回调用 doAnswer 同步触发，这样断言能看到生成结束后的完整状态；RecordingSink 按顺序记事件，
 * 因为前端就是按顺序消费的。
 */
class ChatServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID QUESTION_ID = UUID.randomUUID();
    private static final UUID ANSWER_ID = UUID.randomUUID();

    private final ChatRepository chats = mock(ChatRepository.class);
    private final RetrievalRepository retrieval = mock(RetrievalRepository.class);
    private final HybridSearchService searchService = mock(HybridSearchService.class);
    private final StreamingChatModel streamingModel = mock(StreamingChatModel.class);
    private final RunDiagnostics diagnostics = new RunDiagnostics();
    private final ObjectMapper json = JsonMapper.builder().build();

    private final RecordingSink sink = new RecordingSink();

    @Test
    void emptyQuestionNeverReachesTheDatabase() {
        ChatService service = service();

        service.ask(new ChatService.AskRequest(SESSION_ID, "   ", null, null, null, null), sink);

        assertThat(sink.events).containsExactly("error", "complete");
        assertThat(sink.errorCode).isEqualTo(400);
        verifyNoModelCall();
        verify(chats, never()).insertQuestion(any(), anyString(), any());
    }

    @Test
    void emptyRecallRefusesWithoutAskingTheModel() {
        stubSession();
        stubSearch(List.of());
        stubAnswerRow();
        ChatService service = service();

        service.ask(request("网关超时怎么排查"), sink);

        assertThat(sink.events).containsExactly("meta", "delta", "done", "complete");
        assertThat(sink.delta).isEqualTo(AnswerPrompt.NO_EVIDENCE);
        assertThat(sink.done.status()).isEqualTo(ChatService.STATUS_REFUSED);
        verify(chats).finishAnswer(ANSWER_ID, AnswerPrompt.NO_EVIDENCE, ChatService.STATUS_REFUSED, null, null, null);
        verifyNoModelCall();
        verify(retrieval, never()).touchHits(anyList());
        // 拒答单独计数：一轮实测里 refused 涨得比预期多，说明阈值或分块出了问题，而不是模型不行
        assertThat(diagnostics.snapshot().counters().refused()).isEqualTo(1);
    }

    /** 截断要拿小窗口试，别绑在默认预算上：默认窗口跟着模型走（现在 16384），换一次模型这条就会静默失效。 */
    @Test
    void contextTruncationIsCountedAndSaysSoInTheMetaFrame() {
        stubSession();
        stubSearch(List.of(hit(11, "长".repeat(3000))));
        stubAnswerRow();
        generate("答");

        service(new RagProperties.Budget(4096, 700, 900, 1.0, 4, 800)).ask(request("这一轮的正文比预算还长"), sink);

        assertThat(sink.meta.contextTruncated()).isTrue();
        assertThat(diagnostics.snapshot().counters().contextTruncated()).isEqualTo(1);
        assertThat(diagnostics.snapshot().counters().refused()).isZero();
    }

    @Test
    void historyIsReadBeforeTheQuestionIsStored() {
        // 先写问题再取历史，本轮问题会被当成「上一轮」重复塞进 prompt，模型会答两遍
        stubSession();
        stubSearch(List.of());
        stubAnswerRow();
        ChatService service = service();

        service.ask(request("第二个问题"), sink);

        InOrder order = inOrder(chats);
        order.verify(chats).listMessages(eq(SESSION_ID), anyInt());
        order.verify(chats).insertQuestion(eq(SESSION_ID), eq("第二个问题"), any());
        order.verify(chats).nameFromFirstQuestion(SESSION_ID, "第二个问题");
        order.verify(chats).beginAnswer(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void recalledBlocksBecomeNumberedCitationsInThePromptAndInMeta() {
        stubSession();
        stubSearch(List.of(hit(11, "子块正文")));
        when(retrieval.parentContents(anyList())).thenReturn(Map.of(99L, "父块完整段落，比子块更适合让模型读。"));
        stubAnswerRow();
        generate("答案带引用 [1]");

        ChatService service = service();
        service.ask(request("父块会替换子块吗"), sink);

        assertThat(sink.meta.sources()).hasSize(1);
        assertThat(sink.meta.sources().get(0).citation()).isEqualTo(1);
        assertThat(sink.meta.sources().get(0).parentExpanded()).isTrue();
        assertThat(sink.meta.effectiveMode()).isEqualTo("HYBRID");

        List<ChatMessage> messages = sentMessages();
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) messages.get(0)).text()).contains("[编号]");
        UserMessage last = (UserMessage) messages.get(messages.size() - 1);
        assertThat(last.singleText()).contains("[1] 手册.pdf").contains("父块完整段落").doesNotContain("子块正文");
        assertThat(sink.done.status()).isEqualTo(ChatService.STATUS_OK);
        verify(retrieval).touchHits(List.of(11L));
    }

    @Test
    void sourcesAndRetrievalMetaAreStoredWithTheAnswerRow() {
        stubSession();
        stubSearch(List.of(hit(11, "一段可以引用的正文")));
        stubAnswerRow();
        generate("好");
        ChatService service = service();

        service.ask(request("存下来"), sink);

        ArgumentCaptor<String> sources = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(chats).beginAnswer(eq(SESSION_ID), eq(""), eq("存下来"), sources.capture(), meta.capture(), any());
        assertThat(sources.getValue()).contains("\"citation\":1").contains("手册.pdf");
        assertThat(meta.getValue()).contains("\"effectiveMode\":\"HYBRID\"").contains("\"usedBlocks\":1");
    }

    @Test
    void previousTurnsAreReplayedIntoTheModelConversation() {
        stubSession();
        when(chats.listMessages(eq(SESSION_ID), anyInt())).thenReturn(List.of(
                message("user", "上一轮的问题", "DONE"), message("assistant", "上一轮的回答", "OK")));
        stubSearch(List.of(hit(11, "正文")));
        stubAnswerRow();
        generate("新答案");

        service().ask(request("追问"), sink);

        List<ChatMessage> messages = sentMessages();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(2)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) messages.get(2)).text()).isEqualTo("上一轮的回答");
    }

    /** 人工订正过的回答要顶替模型原文进入下一轮上下文：否则改完再问，模型还在引用自己那句错话。 */
    @Test
    void annotatedAnswerReplacesTheModelOriginalInFollowingTurns() {
        stubSession();
        when(chats.listMessages(eq(SESSION_ID), anyInt())).thenReturn(List.of(
                message("user", "上一轮的问题", "DONE"), answered("模型答错了", "订正后的答案")));
        stubSearch(List.of(hit(11, "正文")));
        stubAnswerRow();
        generate("新答案");

        service().ask(request("追问"), sink);

        List<ChatMessage> messages = sentMessages();
        assertThat(((AiMessage) messages.get(2)).text()).isEqualTo("订正后的答案");
        assertThat(((AiMessage) messages.get(2)).text()).doesNotContain("模型答错了");
    }

    @Test
    void partialAnswerIsKeptWhenTheModelDiesMidStream() {
        stubSession();
        stubSearch(List.of(hit(11, "正文")));
        stubAnswerRow();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("已经吐出的");
            handler.onError(new IllegalStateException("connection reset"));
            return null;
        }).when(streamingModel).chat(anyList(), any(StreamingChatResponseHandler.class));

        service().ask(request("会断在哪"), sink);

        assertThat(sink.events).containsExactly("meta", "delta", "error", "complete");
        assertThat(sink.errorCode).isEqualTo(503);
        assertThat(sink.errorMessage).contains("connection reset");
        verify(chats).markAnswer(ANSWER_ID, "已经吐出的", ChatService.STATUS_INTERRUPTED);
        verify(chats, never()).finishAnswer(eq(ANSWER_ID), anyString(), eq(ChatService.STATUS_OK), any(), any(),
                any());
    }

    /**
     * 超限的响应体是实测抓下来的原样文本（192.168.1.61:1234，2026-11-16），并且被 langchain4j 包在 cause 里：
     * 只看最外层异常永远读不到 n_ctx，所以这条同时钉住「往上溯 cause」这件事。
     */
    @Test
    void contextOverflowGetsItsOwnBranchInsteadOfTheGenericOutageMessage() {
        stubSession();
        stubSearch(List.of(hit(11, "正文")));
        stubAnswerRow();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onError(new RuntimeException("Chat completion failed",
                    new IllegalStateException("{\"error\":\"Engine protocol predict request returned 400: "
                            + "{\\\"error\\\":{\\\"code\\\":400,\\\"message\\\":\\\"request (36005 tokens) "
                            + "exceeds the available context size (16384 tokens), try increasing it\\\","
                            + "\\\"type\\\":\\\"exceed_context_size_error\\\",\\\"n_prompt_tokens\\\":36005,"
                            + "\\\"n_ctx\\\":16384}}\"}")));
            return null;
        }).when(streamingModel).chat(anyList(), any(StreamingChatResponseHandler.class));

        service().ask(request("资料比窗口还长"), sink);

        // 一个字都没生成，所以没有 delta；状态也不是 INTERRUPTED：那是「模型半路死了」，这是「预算配错了」
        assertThat(sink.events).containsExactly("meta", "error", "complete");
        assertThat(sink.errorCode).isEqualTo(413);
        assertThat(sink.errorMessage).contains("上下文超出模型窗口").contains("36005 token").contains("回传 16384");
        verify(chats).markAnswer(ANSWER_ID, "", ChatService.STATUS_FAILED);
        assertThat(diagnostics.snapshot().counters().contextOverflow()).isEqualTo(1);
        assertThat(diagnostics.snapshot().lastContextOverflow().reportedCtx()).isEqualTo(16384);
        assertThat(diagnostics.snapshot().lastContextOverflow().configuredCtx()).isEqualTo(16384);
    }

    @Test
    void sessionWithoutExplicitScopeSearchesItsOwnKnowledgeBase() {
        when(chats.requireSession(SESSION_ID)).thenReturn(session(List.of(42L)));
        when(chats.listMessages(eq(SESSION_ID), anyInt())).thenReturn(List.of());
        stubSearch(List.of());
        stubAnswerRow();

        service().ask(request("库里有什么"), sink);

        verify(searchService).search(eq("库里有什么"), eq(List.of(42L)), any(), any(), any());
    }

    /**
     * 范围缺失要在任何写入之前挡下，而不是兜底到默认库：兜底等于替用户决定「查哪个库」，
     * 而答案里看不出资料来自哪一层范围——前端正是靠这条 400 提前拦住提问按钮的。
     */
    @Test
    void scopelessQuestionIsRejectedBeforeAnythingIsWritten() {
        service().ask(new ChatService.AskRequest(null, "在吗", null, null, null, null), sink);

        assertThat(sink.events).containsExactly("error", "complete");
        assertThat(sink.errorCode).isEqualTo(400);
        assertThat(sink.errorMessage).contains("检索范围");
        verifyNoModelCall();
        verify(chats, never()).createSession(anyString(), anyList());
        verify(chats, never()).insertQuestion(any(), anyString(), any());
    }

    /** 会话 id 不给但范围给了：这时才建会话，且会话记住的就是这次的范围。 */
    @Test
    void newSessionIsCreatedWithTheRequestedScope() {
        when(chats.createSession(anyString(), anyList())).thenReturn(session(List.of(7L)));
        when(chats.listMessages(any(), anyInt())).thenReturn(List.of());
        stubSearch(List.of());
        stubAnswerRow();

        service().ask(new ChatService.AskRequest(null, "第一个问题", List.of(7L), null, null, null), sink);

        verify(chats).createSession(eq("新会话"), eq(List.of(7L)));
        verify(searchService).search(eq("第一个问题"), eq(List.of(7L)), any(), any(), any());
    }

    @Test
    void missingSessionIsReportedAsAnErrorEvent() {
        when(chats.requireSession(SESSION_ID)).thenThrow(RagException.notFound("会话不存在或已删除：" + SESSION_ID));

        service().ask(request("问题"), sink);

        assertThat(sink.events).containsExactly("error", "complete");
        assertThat(sink.errorCode).isEqualTo(404);
        verifyNoModelCall();
    }

    @Test
    void searchFailureKeepsItsHintsForTheUser() {
        stubSession();
        when(searchService.search(any(), anyList(), any(), any(), any()))
                .thenThrow(RagException.conflict("模型没加载", "去 LM Studio 里加载 bge-base-zh"));

        service().ask(request("问题"), sink);

        assertThat(sink.events).containsExactly("error", "complete");
        assertThat(sink.errorCode).isEqualTo(409);
        assertThat(sink.errorMessage).contains("去 LM Studio 里加载 bge-base-zh");
    }

    @Test
    void historyIsTrimmedFromTheOldestTurnAndRefusedAnswersAreDropped() {
        ChatRepository.Message old = message("user", "很久以前的一长串问题".repeat(100), "DONE");
        ChatRepository.Message refused = message("assistant", AnswerPrompt.NO_EVIDENCE, "REFUSED");
        ChatRepository.Message fresh = message("user", "最近的问题", "DONE");

        List<ChatRepository.Message> kept = ChatService.trimHistory(List.of(old, refused, fresh),
                RagProperties.Budget.defaults());

        assertThat(kept).containsExactly(fresh);
        assertThat(ChatService.trimHistory(List.of(), RagProperties.Budget.defaults())).isEmpty();
    }

    private void stubSession() {
        when(chats.requireSession(SESSION_ID)).thenReturn(session(List.of(1L)));
        when(chats.listMessages(eq(SESSION_ID), anyInt())).thenReturn(List.of());
        when(chats.insertQuestion(eq(SESSION_ID), anyString(), any())).thenReturn(QUESTION_ID);
    }

    private void stubSearch(List<SearchHit> hits) {
        when(searchService.search(any(), anyList(), any(), any(), any())).thenReturn(
                new HybridSearchService.Outcome(RetrievalMode.HYBRID, RetrievalMode.HYBRID,
                        KeywordEngine.CHINESE_TSVECTOR, null, hits, 12L));
    }

    private void stubAnswerRow() {
        when(chats.beginAnswer(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(ANSWER_ID);
    }

    private void generate(String answer) {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse(answer);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(answer))
                    .modelName("qwen2.5-vl-7b-instruct")
                    .tokenUsage(new TokenUsage(120, 30))
                    .build());
            return null;
        }).when(streamingModel).chat(anyList(), any(StreamingChatResponseHandler.class));
    }

    private List<ChatMessage> sentMessages() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(streamingModel).chat(captor.capture(), any(StreamingChatResponseHandler.class));
        return captor.getValue();
    }

    private void verifyNoModelCall() {
        verify(streamingModel, never()).chat(anyList(), any(StreamingChatResponseHandler.class));
    }

    private ChatService service() {
        return service(RagProperties.Retrieval.defaults().budget());
    }

    private ChatService service(RagProperties.Budget budget) {
        return new ChatService(chats, retrieval, searchService, new ContextPlanner(),
                streamingModel, props(budget), json, diagnostics);
    }

    private static RagProperties props(RagProperties.Budget budget) {
        return new RagProperties(
                new RagProperties.LmStudio("http://localhost:1234/v1", "lm-studio", "chat", "bge",
                        0.3, 0.8, null, Duration.ofSeconds(5), 1),
                new RagProperties.Storage("target/rag-test"),
                new RagProperties.Embedding(768, 16),
                RagProperties.Ingest.defaults(),
                new RagProperties.Retrieval("hybrid", 6, 40, 60, 0.45, 0.012, true, budget),
                RagProperties.Archive.defaults());
    }

    private static ChatRepository.Session session(List<Long> kbIds) {
        return new ChatRepository.Session(SESSION_ID, "测试会话", kbIds, false, null, null);
    }

    /** 反馈三兄弟默认全空：历史裁剪与上下文拼装只关心 role/content/status，评分与订正另有测试覆盖。 */
    private static ChatRepository.Message message(String role, String content, String status) {
        return new ChatRepository.Message(UUID.randomUUID(), SESSION_ID, role, content, status, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private static ChatRepository.Message answered(String content, String annotated) {
        return new ChatRepository.Message(UUID.randomUUID(), SESSION_ID, "assistant", content, "DONE", null, null, null,
                null, null, null, 1, null, annotated, null, null, null);
    }

    private static ChatService.AskRequest request(String question) {
        return new ChatService.AskRequest(SESSION_ID, question, null, null, null, null);
    }

    private static SearchHit hit(long segmentId, String content) {
        return new SearchHit(segmentId, 7, "手册.pdf", 3, 2, content, null, 2, 2, 0, content.length(), 99L,
                1, 2, 0.032, 0.71);
    }

    /** 按顺序记录 sink 收到的一切，事件名顺序就是前端看到的顺序。 */
    private static final class RecordingSink implements ChatService.Sink {

        private final List<String> events = new ArrayList<>();
        private ChatService.MetaView meta;
        private ChatService.DoneView done;
        private String delta;
        private int errorCode;
        private String errorMessage;

        @Override
        public void meta(ChatService.MetaView meta) {
            events.add("meta");
            this.meta = meta;
        }

        @Override
        public void delta(String text) {
            events.add("delta");
            delta = delta == null ? text : delta + text;
        }

        @Override
        public void done(ChatService.DoneView done) {
            events.add("done");
            this.done = done;
        }

        @Override
        public void error(int code, String message) {
            events.add("error");
            errorCode = code;
            errorMessage = message;
        }

        @Override
        public void complete() {
            events.add("complete");
        }
    }
}
