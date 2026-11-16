package com.rag.knowledge.web;

import com.rag.knowledge.app.rag.ChatService;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.db.ChatRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /chat/ask 的失败必须能在**开流之前**读到一个 JSON 信封，哪怕客户端 Accept 的只有 text/event-stream。
 * <p>
 * 这条是被端到端回归打出来的：SSE 端点带着 produces=text/event-stream，出问题时异常处理器要做内容协商，
 * 协商结果是「这个客户端只收事件流」，于是 JSON 写不出去，参数错变成一个没有 body 的 500——
 * 前端那套「先按 content-type 判断走哪条分支」的逻辑一次都不会执行，用户看到的和「服务炸了」完全同形。
 * 所以这里验的不是文案，是「错误到底以什么形式离开」。
 */
class ChatAskErrorEnvelopeTest {

    private final ChatService chatService = Mockito.mock(ChatService.class);
    private final ChatRepository chats = Mockito.mock(ChatRepository.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ChatController(chatService, chats, Runnable::run, JsonMapper.builder().build()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void aBlankQuestionIsRejectedBeforeTheStreamOpens() throws Exception {
        mvc.perform(post("/api/chat/ask")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \",\"kbIds\":[1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(400));

        Mockito.verifyNoInteractions(chatService);
    }

    @Test
    void aScopelessQuestionComesBackAsJsonNotAnOpaqueStreamFailure() throws Exception {
        Mockito.doThrow(RagException.badRequest("问答必须落在明确的检索范围里", "在 body 里给 kbIds"))
                .when(chatService).requireScope(any());

        mvc.perform(post("/api/chat/ask")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"在吗\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // 处理建议拼在 message 里：光有「参数错」的 400，调用方只能把请求体贴回来问人
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("处理建议")));
    }
}
