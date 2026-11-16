import { defineStore } from 'pinia'
import { requestEnvelope } from '@/api/http'
import type { KnowledgeBase, RetrievalMode } from '@/types/api'

export const useUiStore = defineStore('ui', {
  state: () => ({
    /** 检索与问答的作用域：后端要求至少给一个库，为空时提交按钮会被禁用而不是报 400。 */
    kbIds: [] as number[],
    docIds: [] as number[],
    mode: 'hybrid' as RetrievalMode,
    topK: 6,
    sessionLimit: 50,
  }),

  // 只持久化界面偏好；会话与消息永远以数据库为准
  persist: true,

  actions: {
    toggleKb(id: number) {
      this.kbIds = this.kbIds.includes(id)
        ? this.kbIds.filter((item) => item !== id)
        : [...this.kbIds, id]
    },
  },
})

export const useKbStore = defineStore('kb', {
  state: () => ({
    items: [] as KnowledgeBase[],
    loaded: false,
    loading: false,
    /** 后端把「默认知识库 id=N」写在 message 里；不选库上传时就是它，展示用。 */
    hint: '',
  }),

  getters: {
    nameOf(state) {
      return (id: number): string => state.items.find((item) => item.id === id)?.name ?? `库 ${id}`
    },
  },

  actions: {
    async load(force = false) {
      if (this.loading || (this.loaded && !force)) return
      this.loading = true
      try {
        // 默认库 id 只出现在信封的 message 里，所以这里要整封信，而不是只要 data
        const envelope = await requestEnvelope<KnowledgeBase[]>('/knowledge-bases')
        this.items = envelope.data ?? []
        this.hint = envelope.message ?? ''
        this.loaded = true
      } catch {
        // 后端未起时不要把空列表当成「真的没有知识库」，自检页负责说清原因
        this.items = []
        this.loaded = false
      } finally {
        this.loading = false
      }
    },
  },
})
