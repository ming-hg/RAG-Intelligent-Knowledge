package com.rag.knowledge.web;

import com.rag.knowledge.app.rag.ChatService;
import com.rag.knowledge.common.ApiResponse;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.ChatExecutorConfig;
import com.rag.knowledge.db.ChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话与流式问答接口。
 * <p>
 * /ask 走 SSE：本地 7B 模型首字延迟就有好几秒，等全文返回再显示会让用户以为系统卡死。
 * 事件顺序固定为 meta → delta* → done（异常时 meta → delta* → error），前端只需按名字分发。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 生成上限按「一次回答跑 10 分钟」留余量；MVC 的异步默认超时是容器给的 30 秒，本地模型必然被掐断。 */
    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(10).toMillis();

    private final ChatService chatService;
    private final ChatRepository chats;
    private final Executor chatExecutor;
    private final ObjectMapper json;

    public ChatController(ChatService chatService, ChatRepository chats,
                          @Qualifier(ChatExecutorConfig.CHAT_EXECUTOR) Executor chatExecutor, ObjectMapper json) {
        this.chatService = chatService;
        this.chats = chats;
        this.chatExecutor = chatExecutor;
        this.json = json;
    }

    public record AskBody(UUID sessionId, String question, List<Long> kbIds, List<Long> docIds, String mode,
                          Integer topK) {
    }

    public record SessionView(UUID id, String title, List<Long> kbIds, boolean pinned, Instant createdAt,
                              Instant updatedAt) {
    }

    public record MessageView(UUID id, String role, String content, String status, String queryFinal,
                              List<ChatService.SourceView> sources, Map<String, Object> retrievalMeta,
                              Integer promptTokens, Integer completionTokens, String model, String traceId,
                              Instant createdAt, Instant finishedAt) {
    }

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody AskBody body) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        SseSink sink = new SseSink(emitter);
        emitter.onTimeout(sink::close);
        emitter.onError(ex -> sink.close());
        ChatService.AskRequest request = toRequest(body);
        try {
            chatExecutor.execute(() -> chatService.ask(request, sink));
        } catch (RejectedExecutionException ex) {
            // 池满时明确回一条 error 事件：排队几分钟不吐字，用户只会当成故障
            sink.error(503, "并发问答已达上限，请稍后重试");
            sink.complete();
        }
        return emitter;
    }

    @PostMapping("/sessions")
    public ApiResponse<SessionView> createSession(@RequestBody(required = false) Map<String, Object> body) {
        String title = body == null || body.get("title") == null ? "新会话" : String.valueOf(body.get("title"));
        List<Long> kbIds = body == null || !(body.get("kbIds") instanceof List<?> raw)
                ? List.of() : raw.stream().map(v -> Long.valueOf(String.valueOf(v))).toList();
        return ApiResponse.ok(view(chats.createSession(title, kbIds)));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<SessionView>> listSessions(@RequestParam(defaultValue = "50") int limit,
                                                      @RequestParam(defaultValue = "0") int offset) {
        return ApiResponse.ok(chats.listSessions(Math.min(Math.max(limit, 1), 200), Math.max(offset, 0)).stream()
                .map(ChatController::view).toList());
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<MessageView>> messages(@PathVariable UUID id,
                                                  @RequestParam(defaultValue = "100") int limit) {
        chats.requireSession(id);
        return ApiResponse.ok(chats.listMessages(id, Math.min(Math.max(limit, 1), 500)).stream()
                .map(this::view).toList());
    }

    @PatchMapping("/sessions/{id}")
    public ApiResponse<SessionView> patchSession(@PathVariable UUID id,
                                                @RequestParam(required = false) String title,
                                                @RequestParam(required = false) Boolean pinned) {
        ChatRepository.Session session = chats.requireSession(id);
        if (title != null && !title.isBlank()) {
            chats.rename(id, title.trim());
        }
        if (pinned != null && pinned != session.pinned()) {
            chats.setPinned(id, pinned);
        }
        return ApiResponse.ok(view(chats.requireSession(id)));
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Map<String, Object>> deleteSession(@PathVariable UUID id) {
        if (chats.softDeleteSession(id) == 0) {
            throw RagException.notFound("会话不存在或已删除：" + id);
        }
        return ApiResponse.ok(Map.of("sessionId", id), "会话已删除");
    }

    private ChatService.AskRequest toRequest(AskBody body) {
        if (body == null || body.question() == null || body.question().isBlank()) {
            throw RagException.badRequest("question 不能为空");
        }
        return new ChatService.AskRequest(body.sessionId(), body.question(), body.kbIds(), body.docIds(),
                body.mode(), body.topK());
    }

    private static SessionView view(ChatRepository.Session session) {
        return new SessionView(session.id(), session.title(), session.kbIds(), session.pinned(),
                session.createdAt(), session.updatedAt());
    }

    private MessageView view(ChatRepository.Message message) {
        return new MessageView(message.id(), message.role(), message.content(), message.status(),
                message.queryFinal(), readList(message.sourcesJson()), readMap(message.retrievalMetaJson()),
                message.promptTokens(), message.completionTokens(), message.model(), message.traceId(),
                message.createdAt(), message.finishedAt());
    }

    /** 落库时是什么形状，读出来就是什么形状；解析失败只记日志，不能让一条坏数据把整个会话历史打不开。 */
    private List<ChatService.SourceView> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<ChatService.SourceView>>() {
            });
        } catch (RuntimeException ex) {
            log.warn("历史引用解析失败，按空引用返回：{}", ex.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (RuntimeException ex) {
            log.warn("检索元信息解析失败，按空对象返回：{}", ex.getMessage());
            return Map.of();
        }
    }

    /**
     * SseEmitter 的适配器。
     * <p>
     * 写失败（浏览器关页、网络断开）之后不往上抛：生成照常跑完并落库，用户重新打开会话就能看到完整答案。
     * 反过来——因为客户端断了就中断生成——会把「关掉标签页」变成一次真实的算力浪费，还会留下半截回答。
     */
    private static final class SseSink implements ChatService.Sink {

        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        SseSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void meta(ChatService.MetaView meta) {
            send("meta", meta);
        }

        @Override
        public void delta(String text) {
            send("delta", Map.of("text", text));
        }

        @Override
        public void done(ChatService.DoneView done) {
            send("done", done);
        }

        @Override
        public void error(int code, String message) {
            send("error", Map.of("code", code, "message", message));
        }

        @Override
        public void complete() {
            if (closed.compareAndSet(false, true)) {
                emitter.complete();
            }
        }

        void close() {
            closed.set(true);
        }

        private void send(String event, Object payload) {
            if (closed.get() || payload == null) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException | RuntimeException ex) {
                // 连接已断时 SseEmitter 抛 IllegalStateException，底层 IO 问题抛 IOException
                log.debug("SSE {} 事件发送失败，客户端可能已断开：{}", event, ex.getMessage());
                closed.set(true);
            }
        }
    }
}
