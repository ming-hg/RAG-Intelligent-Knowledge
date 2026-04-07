<template>
  <div class="chat-container">
    <!-- 顶部区域 -->
    <div class="chat-header">
      <el-input 
        v-model="sessionName" 
        placeholder="请输入会话名称" 
        class="session-name-input"
        @blur="updateSessionName"
      />
      <div class="header-buttons">
        <el-button type="primary" size="small" @click="switchKnowledgeBase">
          切换知识库
        </el-button>
        <el-button size="small" @click="clearCurrentChat">清空会话</el-button>
        <el-button size="small" type="danger" @click="deleteCurrentSession">删除会话</el-button>
      </div>
    </div>

    <!-- 消息展示区 -->
    <div class="chat-message-container" ref="messageContainer">
      <div class="message-item" v-for="(msg, index) in messageList" :key="index" :class="{'user-message': msg.role === 'user', 'ai-message': msg.role === 'ai'}">
        <div class="message-avatar">
          {{ msg.role === 'user' ? '我' : 'AI' }}
        </div>
        <div class="message-content">
          <!-- Markdown渲染 + 代码高亮 -->
          <div v-html="renderMarkdown(msg.content)" class="markdown-content"></div>
          <!-- 引用来源展示（AI消息专属） -->
          <div class="source-list" v-if="msg.role === 'ai' && msg.sourceList?.length">
            <el-divider content-position="left">引用来源</el-divider>
            <el-tag v-for="(source, idx) in msg.sourceList" :key="idx" size="small" type="info">
              {{ source.fileName }}（第{{ source.chunkIndex + 1 }}块）
            </el-tag>
          </div>
        </div>
      </div>
      <!-- 流式加载中提示 -->
      <div class="loading-tip" v-if="streamLoading">AI正在思考...</div>
    </div>

    <!-- 输入区 -->
    <div class="chat-input-container">
      <el-input
        v-model="question"
        type="textarea"
        placeholder="请输入您的问题..."
        class="chat-input"
        rows="3"
        @keyup.enter.prevent="handleStreamChat"
        @keyup.shift.enter="handleLineBreak"
      />
      <div class="input-buttons">
        <el-button size="small" @click="clearInput">清空</el-button>
        <el-button type="primary" size="small" @click="handleStreamChat" :disabled="!question.trim() || streamLoading">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useChatStore } from '@/store/chatStore'
import { ElButton, ElInput, ElDivider, ElTag, ElMessage } from 'element-plus'
import markdownIt from 'markdown-it'
import highlightJs from 'highlight.js'
import 'highlight.js/styles/github.css'
import request, { streamRequest } from '@/utils/request'

// 初始化Markdown渲染器（支持代码高亮）
const md = markdownIt({
  highlight: (str, lang) => {
    if (lang && highlightJs.getLanguage(lang)) {
      try {
        return highlightJs.highlight(str, { language: lang }).value
      } catch (err) {}
    }
    return md.utils.escapeHtml(str)
  }
})

// Pinia状态
const chatStore = useChatStore()
const messageList = ref(chatStore.messageList)
const streamLoading = ref(chatStore.streamLoading)
const currentSessionId = ref(chatStore.currentSessionId)
const sessionName = ref('默认会话')
const question = ref('')
const messageContainer = ref(null)

// 监听状态变化，同步更新
watch(() => chatStore.messageList, (val) => {
  messageList.value = val
  // 消息更新后滚动到底部
  scrollToBottom()
}, { deep: true })

watch(() => chatStore.streamLoading, (val) => {
  streamLoading.value = val
})

watch(() => chatStore.currentSessionId, (val) => {
  currentSessionId.value = val
  // 切换会话后重置输入框
  question.value = ''
})

// 页面挂载时滚动到底部
onMounted(() => {
  scrollToBottom()
  // 初始化会话名称
  if (chatStore.sessionList.length) {
    const currentSession = chatStore.sessionList.find(item => item.id === currentSessionId.value)
    if (currentSession) sessionName.value = currentSession.name
  }
})

// Markdown渲染方法
const renderMarkdown = (content) => {
  return md.render(content)
}

// 滚动到底部
const scrollToBottom = () => {
  setTimeout(() => {
    if (messageContainer.value) {
      messageContainer.value.scrollTop = messageContainer.value.scrollHeight
    }
  }, 100)
}

// 更新会话名称
const updateSessionName = () => {
  if (!sessionName.value.trim()) {
    sessionName.value = '默认会话'
  }
  const currentSessionIndex = chatStore.sessionList.findIndex(item => item.id === currentSessionId.value)
  if (currentSessionIndex !== -1) {
    chatStore.sessionList[currentSessionIndex].name = sessionName.value
    chatStore.sessionList = [...chatStore.sessionList]
  }
}

// 发送消息（支持流式响应）
const handleStreamChat = async () => {
  const inputValue = question.value.trim()
  if (!inputValue) return

  // 1. 添加用户消息
  chatStore.addMessage({
    role: 'user',
    content: inputValue,
    time: new Date().toLocaleTimeString()
  }, true)
  question.value = '' // 清空输入框

  // 2. 添加AI消息占位（用于打字机渲染）
  const aiMessageIndex = chatStore.messageList.length
  chatStore.addMessage({
    role: 'ai',
    content: '', // 初始为空，逐步拼接
    time: new Date().toLocaleTimeString(),
    sourceList: [] // 引用来源，流式结束后补充
  })

  // 3. 发起SSE流式请求
  const eventSource = streamRequest('/api/rag/chat/stream', {
    question: inputValue,
    sessionId: chatStore.currentSessionId
  })

  // 4. 监听流式数据
  eventSource.onmessage = (event) => {
    // 解析后端推送的数据（JSON格式）
    const data = JSON.parse(event.data)

    // 处理心跳包（避免浏览器断开连接）
    if (data === 'ping') return

    // 处理错误信息
    if (data.error) {
      ElMessage.error(data.error)
      // 更新AI消息为错误提示
      chatStore.messageList[aiMessageIndex].content = data.error
      eventSource.close() // 关闭连接
      return
    }

    // 流式拼接AI回答（打字机效果核心）
    if (data.content) {
      chatStore.messageList[aiMessageIndex].content += data.content
    }

    // 流式结束，补充引用来源，关闭连接
    if (data.done) {
      chatStore.messageList[aiMessageIndex].sourceList = data.sourceList || []
      eventSource.close()
      console.log('流式问答完成，连接已关闭')
    }
  }

  // 监听页面关闭，主动关闭SSE连接
  window.addEventListener('beforeunload', () => {
    eventSource.close()
  })
}

// 换行（Shift+Enter）
const handleLineBreak = (e) => {
  e.preventDefault()
  question.value += '\n'
}

// 清空输入框
const clearInput = () => {
  question.value = ''
}

// 清空当前会话消息
const clearCurrentChat = () => {
  chatStore.clearMessage()
}

// 删除当前会话
const deleteCurrentSession = () => {
  if (!currentSessionId.value) return
  chatStore.deleteSession(currentSessionId.value)
}

// 切换知识库（跳转至知识库管理页面，此处简化为提示，可结合路由实现）
const switchKnowledgeBase = () => {
  ElMessage.info('跳转至知识库管理页面')
  // 实际项目中可使用路由跳转：router.push('/knowledge')
}
</script>

<style scoped>
.chat-container {
  width: 100%;
  height: calc(100vh - 100px);
  display: flex;
  flex-direction: column;
}
.chat-header {
  padding: 10px 20px;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.session-name-input {
  width: 300px;
}
.header-buttons {
  display: flex;
  gap: 10px;
}
.chat-message-container {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
  background-color: #f9fafb;
}
.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}
.user-message {
  flex-direction: row-reverse;
}
.message-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background-color: #409eff;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}
.user-message .message-avatar {
  background-color: #67c23a;
}
.message-content {
  max-width: 70%;
  padding: 12px 16px;
  border-radius: 8px;
  background-color: white;
  box-shadow: 0 1px 2px rgba(0,0,0,0.1);
}
.ai-message {
  line-height: 1.6;
  white-space: pre-wrap; /* 保留换行和空格 */
  animation: typing 0.1s linear; /* 打字机动画，提升流畅度 */
}

@keyframes typing {
  from { opacity: 0.7; }
  to { opacity: 1; }
}
.user-message .message-content {
  background-color: #409eff;
  color: white;
}
.markdown-content {
  line-height: 1.6;
}
.markdown-content pre {
  background-color: #f5f5f5;
  padding: 10px;
  border-radius: 4px;
  overflow-x: auto;
  margin: 10px 0;
}
.markdown-content code {
  background-color: #f5f5f5;
  padding: 2px 4px;
  border-radius: 4px;
}
.user-message .markdown-content code {
  background-color: rgba(255,255,255,0.2);
}
.source-list {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.loading-tip {
  text-align: center;
  color: #9ca3af;
  font-size: 14px;
  margin: 10px 0;
}
.chat-input-container {
  padding: 20px;
  border-top: 1px solid #e5e7eb;
}
.chat-input {
  margin-bottom: 10px;
  resize: none;
}
.input-buttons {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>