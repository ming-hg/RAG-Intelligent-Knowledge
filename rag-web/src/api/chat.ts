import { http } from './http'
import { postStream } from './sse'
import type { AskDone, AskMeta, AskRequest, ChatMessage, ChatSession, FeedbackPatch } from '@/types/api'

export interface AskHandlers {
  onMeta: (meta: AskMeta) => void
  onDelta: (text: string) => void
  onDone: (done: AskDone) => void
  /** 流已建立后的业务失败（模型不可达、检索异常），后端以 error 事件回报。 */
  onServerError: (code: number, message: string) => void
}

export const chatApi = {
  sessions: (limit = 50, offset = 0) =>
    http.get<ChatSession[]>('/chat/sessions', { limit, offset }),

  createSession: (title?: string, kbIds?: number[]) =>
    http.post<ChatSession>('/chat/sessions', { title, kbIds }),

  messages: (id: string, limit = 100) =>
    http.get<ChatMessage[]>(`/chat/sessions/${id}/messages`, { limit }),

  rename: (id: string, title: string) =>
    http.patch<ChatSession>(`/chat/sessions/${id}`, { title }),

  setPinned: (id: string, pinned: boolean) =>
    http.patch<ChatSession>(`/chat/sessions/${id}`, { pinned }),

  remove: (id: string) => http.del<{ sessionId: string }>(`/chat/sessions/${id}`),

  /**
   * 只发给 assistant 行。字段省略或传 null 都表示「这次不改」，清空要显式传空串——
   * 所以只点 👍 不会把之前写过的反馈抹掉（后端用 COALESCE 落实这一条）。
   */
  feedback: (id: string, patch: FeedbackPatch) =>
    http.patchJson<{ messageId: string }>(`/chat/messages/${id}`, patch),

  /** 返回的 Promise 在流结束时 resolve；调用方用 AbortSignal 主动停止。 */
  ask: async (request: AskRequest, handlers: AskHandlers, signal?: AbortSignal): Promise<void> => {
    await postStream('/chat/ask', request, (event) => {
      let payload: unknown
      try {
        payload = JSON.parse(event.data)
      } catch {
        return
      }
      switch (event.event) {
        case 'meta':
          handlers.onMeta(payload as AskMeta)
          break
        case 'delta': {
          const delta = payload as { text?: string }
          if (delta.text) handlers.onDelta(delta.text)
          break
        }
        case 'done':
          handlers.onDone(payload as AskDone)
          break
        case 'error': {
          const error = payload as { code?: number; message?: string }
          handlers.onServerError(error.code ?? 500, error.message ?? '问答失败')
          break
        }
      }
    }, signal)
  },
}
