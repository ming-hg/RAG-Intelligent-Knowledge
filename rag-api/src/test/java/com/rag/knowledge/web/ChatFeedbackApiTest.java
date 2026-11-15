package com.rag.knowledge.web;

import com.rag.knowledge.app.rag.ChatService;
import com.rag.knowledge.common.RagException;
import com.rag.knowledge.db.ChatRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 反馈接口的入参校验：这三条规则一旦松掉，脏数据是直接进库的，后面 M5e 导出评测集时才会发现——那时已经分不清
 * 是模型答错还是当初没校验。所以在这里用不起 Spring 上下文的方式把边界钉死。
 */
class ChatFeedbackApiTest {

    private final ChatRepository chats = mock(ChatRepository.class);
    private final ChatService chatService = mock(ChatService.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final ChatController controller = new ChatController(chatService, chats, Runnable::run, json);

    private final UUID messageId = UUID.randomUUID();

    @Test
    void anEmptyPatchIsRejectedInsteadOfSilentlySucceeding() {
        assertThatThrownBy(() -> controller.feedback(messageId, new ChatController.FeedbackBody(null, null, null)))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("至少给一个");
        verifyNoInteractions(chats);
    }

    @Test
    void onlyTheThreeLevelRatingIsAccepted() {
        for (int rating : new int[]{-2, 2, 100}) {
            assertThatThrownBy(() -> controller.feedback(messageId, body(rating, null, null)))
                    .isInstanceOf(RagException.class)
                    .hasMessageContaining("rating 只接受");
        }
        verifyNoInteractions(chats);
    }

    /** 👍 单独提交不能把之前写的反馈抹掉，所以「没传」必须是 null 而不是空串。 */
    @Test
    void absentFieldsTravelAsNull() {
        when(chats.setFeedback(any(), any(), any(), any())).thenReturn(1);

        controller.feedback(messageId, body(1, null, null));

        verify(chats).setFeedback(eq(messageId), eq(1), eq(null), eq(null));
    }

    @Test
    void blankStringsMeanClearAndReachTheDatabaseUntouched() {
        when(chats.setFeedback(any(), any(), any(), any())).thenReturn(1);

        controller.feedback(messageId, body(0, "   ", "  订正后的答案  "));

        ArgumentCaptor<String> feedback = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> annotated = ArgumentCaptor.forClass(String.class);
        verify(chats).setFeedback(eq(messageId), eq(0), feedback.capture(), annotated.capture());
        assertThat(feedback.getValue()).isEmpty();
        assertThat(annotated.getValue()).isEqualTo("订正后的答案");
    }

    @Test
    void oversizedTextIsRejectedBeforeItHitsTheColumn() {
        assertThatThrownBy(() -> controller.feedback(messageId,
                body(null, "字".repeat(2001), null)))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("反馈最长 2000 字");

        assertThatThrownBy(() -> controller.feedback(messageId,
                body(null, null, "长".repeat(20001))))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("订正答案最长 20000 字");
        verifyNoInteractions(chats);
    }

    /** 更新 0 行有两种原因：消息不存在，或者它是一条 user 消息。两种都要报 404，而不是假装记录成功。 */
    @Test
    void unknownOrNonAssistantMessageIsNotFound() {
        when(chats.setFeedback(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> controller.feedback(messageId, body(1, null, null)))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("不是一条模型回答");
    }

    private static ChatController.FeedbackBody body(Integer rating, String feedback, String annotated) {
        return new ChatController.FeedbackBody(rating, feedback, annotated);
    }
}
