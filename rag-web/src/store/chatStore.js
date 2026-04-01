import { defineStore } from 'pinia'
import { v4 as uuidv4 } from 'uuid' // 需安装：npm install uuid

export const useChatStore = defineStore('chat', {
  state: () => ({
    currentSessionId: '', // 当前会话ID
    sessionList: [], // 所有会话：[{id: 'xxx', title: 'xxx', createTime: 'xxx', messages: []}]
    messageList: [], // 当前会话消息：[{role: 'user/ai', content: 'xxx', time: 'xxx'}]
    streamLoading: false // 流式加载状态（控制打字机效果）
  }),
  actions: {
    // 1.1 新建会话（点击新建会话按钮触发）
    createNewSession() {
      const newSession = {
        id: uuidv4(), // 生成唯一会话ID
        title: '新会话', // 默认标题，可后续修改
        createTime: new Date().toLocaleString(), // 会话创建时间
        messages: [] // 初始无消息
      }
      this.sessionList.unshift(newSession) // 新增会话放最前面
      this.currentSessionId = newSession.id // 切换到新会话
      this.messageList = [] // 清空当前会话消息
    },
    // 1.2 切换会话（点击会话列表项触发）
    switchSession(sessionId) {
      const targetSession = this.sessionList.find(item => item.id === sessionId)
      if (targetSession) {
        this.currentSessionId = sessionId
        this.messageList = targetSession.messages || [] // 同步目标会话消息
      }
    },
    // 1.3 添加消息（发送问题/接收回答时触发）
    addMessage(message, isUpdateTitle = false) {
      this.messageList.push(message)
      // 若为用户首次提问，将会话标题改为用户问题（优化体验）
      if (isUpdateTitle && this.messageList.length === 1) {
        const currentSession = this.sessionList.find(item => item.id === this.currentSessionId)
        if (currentSession) {
          currentSession.title = message.content.length > 15 ? message.content.slice(0, 15) + '...' : message.content
        }
      }
      // 同步更新会话列表中的消息（用于持久化和切换会话）
      this.syncSessionMessages()
    },
    // 1.4 同步会话消息（内部方法，确保会话列表与当前消息一致）
    syncSessionMessages() {
      const currentSession = this.sessionList.find(item => item.id === this.currentSessionId)
      if (currentSession) {
        currentSession.messages = [...this.messageList]
      }
    },
    // 1.5 清空当前会话消息（点击清空按钮触发）
    clearCurrentSessionMessages() {
      this.messageList = []
      this.syncSessionMessages()
    },
    // 1.6 删除会话（点击会话删除按钮触发）
    deleteSession(sessionId) {
      const index = this.sessionList.findIndex(item => item.id === sessionId)
      if (index !== -1) {
        this.sessionList.splice(index, 1)
        // 若删除的是当前会话，切换到第一个会话（无会话则清空状态）
        if (this.currentSessionId === sessionId) {
          const firstSession = this.sessionList[0]
          if (firstSession) {
            this.currentSessionId = firstSession.id
            this.messageList = firstSession.messages || []
          } else {
            this.currentSessionId = ''
            this.messageList = []
          }
        }
      }
    }
  },
  persist: {
    key: 'chat-store',
    paths: ['sessionList', 'currentSessionId'] // 仅持久化会话列表和当前会话ID
  }
})