import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'
import { chatApi } from '@/api/chat'
import { useUiStore } from './ui'
import type { AskDone, AskMeta, ChatMessage, ChatSession, Source } from '@/types/api'

/** 正在生成的那条回答：落库前后它替代历史里的 assistant 行显示。 */
interface Live {
  question: string
  text: string
  meta: AskMeta | null
  done: AskDone | null
  error: string | null
  /** 用户主动停止：断开的是这条显示流，不是后端的生成，语义上和「失败」必须分开。 */
  aborted: boolean
  running: boolean
}

function emptyLive(question: string): Live {
  return { question, text: '', meta: null, done: null, error: null, aborted: false, running: true }
}

export const useChatStore = defineStore('chat', {
  state: () => ({
    sessions: [] as ChatSession[],
    activeId: null as string | null,
    messages: [] as ChatMessage[],
    live: null as Live | null,
    selectedCitation: null as number | null,
    loadingSessions: false,
    loadingMessages: false,
    /** 不属于 state 的语义，但 pinia 需要一个地方放它才能被 stop() 拿到 */
    controller: null as AbortController | null,
  }),

  getters: {
    streaming: (state) => state.live?.running === true,
    /** 右侧引用面板的数据源：正在生成的用 meta，回看历史的用最后一条 assistant 消息。 */
    activeSources(state): Source[] {
      if (state.live?.meta) return state.live.meta.sources
      for (let index = state.messages.length - 1; index >= 0; index--) {
        const message = state.messages[index]
        if (message?.role === 'assistant' && message.sources?.length) return message.sources
      }
      return []
    },
    activeMeta(state) {
      if (state.live?.meta) return state.live.meta
      for (let index = state.messages.length - 1; index >= 0; index--) {
        const message = state.messages[index]
        if (message?.role === 'assistant' && message.retrievalMeta) return message.retrievalMeta
      }
      return null
    },
  },

  actions: {
    async loadSessions() {
      this.loadingSessions = true
      try {
        this.sessions = await chatApi.sessions(useUiStore().sessionLimit)
        if (this.activeId && !this.sessions.some((item) => item.id === this.activeId)) this.activeId = null
      } finally {
        this.loadingSessions = false
      }
    },

    async openSession(id: string) {
      if (this.streaming) this.stop()
      this.activeId = id
      this.selectedCitation = null
      await this.loadMessages(id)
    },

    async loadMessages(id: string) {
      this.loadingMessages = true
      try {
        this.messages = await chatApi.messages(id)
      } finally {
        this.loadingMessages = false
      }
    },

    async newSession(): Promise<string | null> {
      const ui = useUiStore()
      try {
        const session = await chatApi.createSession('新会话', ui.kbIds)
        this.sessions = [session, ...this.sessions]
        this.activeId = session.id
        this.messages = []
        this.live = null
        this.selectedCitation = null
        return session.id
      } catch {
        return null
      }
    },

    async removeSession(id: string) {
      await chatApi.remove(id)
      this.sessions = this.sessions.filter((item) => item.id !== id)
      if (this.activeId === id) {
        this.activeId = this.sessions[0]?.id ?? null
        this.messages = this.activeId ? await chatApi.messages(this.activeId) : []
      }
    },

    async togglePin(id: string) {
      const session = this.sessions.find((item) => item.id === id)
      if (!session) return
      const updated = await chatApi.setPinned(id, !session.pinned)
      Object.assign(session, updated)
      await this.loadSessions()
    },

    async rename(id: string, title: string) {
      const updated = await chatApi.rename(id, title)
      Object.assign(this.sessions.find((item) => item.id === id) ?? {}, updated)
    },

    /** 提问入口。作用域缺失时直接拦下：后端的 400 说的是「必须指定库」，界面不该等到请求发出才告诉用户。 */
    async ask(question: string) {
      if (this.streaming || !question.trim()) return
      const ui = useUiStore()
      if (ui.kbIds.length === 0 && ui.docIds.length === 0) {
        ElMessage.warning('先选至少一个知识库或文档')
        return
      }
      if (!this.activeId && (await this.newSession()) === null) return

      this.live = emptyLive(question)
      this.selectedCitation = null
      const controller = new AbortController()
      this.controller = controller
      const sessionId = this.activeId as string

      try {
        await chatApi.ask(
          {
            sessionId,
            question,
            kbIds: ui.kbIds,
            docIds: ui.docIds,
            mode: ui.mode,
            topK: ui.topK,
          },
          {
            onMeta: (meta) => {
              if (this.live) this.live.meta = meta
            },
            onDelta: (text) => {
              if (this.live) this.live.text += text
            },
            onDone: (done) => {
              if (this.live) {
                this.live.done = done
                this.live.running = false
              }
            },
            onServerError: (code, message) => {
              if (this.live) {
                this.live.error = `${code} ${message}`
                this.live.running = false
              }
            },
          },
          controller.signal,
        )
      } catch (error) {
        if (this.live) {
          // 停止接收会让 Chrome 在读流中途抛 "BodyStreamBuffer was aborted"，那是预期行为，不是故障
          if (!controller.signal.aborted) {
            this.live.error = error instanceof Error ? error.message : String(error)
          }
          this.live.running = false
        }
      } finally {
        this.controller = null
      }

      // 正常收尾就以库里的行为准（顺便拿到自动改名的标题）；失败时保留半截 live，让用户看见到底断在哪
      if (this.live && !this.live.error) {
        await this.loadMessages(sessionId)
        this.live = null
      }
      await this.loadSessions()
    },

    /** 停止只是断开这条流：后端会继续生成并落库，重新打开会话就能看到完整回答。 */
    stop() {
      this.controller?.abort()
      this.controller = null
      if (this.live) {
        this.live.running = false
        this.live.aborted = true
        this.live.error = '已断开显示，后端仍在生成并落库；重新打开本会话可见完整回答'
      }
    },
  },

  persist: {
    // 只有当前会话 id 值得留；消息内容一律从数据库读，避免刷新后看到一份过期副本
    pick: ['activeId'],
  },
})
