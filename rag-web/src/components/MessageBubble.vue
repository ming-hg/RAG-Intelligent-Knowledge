<template>
  <div class="message-bubble" :class="{'user-message': isUser, 'ai-message': !isUser}">
    <!-- 头像 -->
    <div class="message-avatar">
      <el-avatar v-if="isUser" :icon="User" :size="40"></el-avatar>
      <el-avatar v-else :icon="Headset" :size="40"></el-avatar>
    </div>
    <!-- 消息内容 -->
    <div class="message-content">
      <div class="message-author">{{ isUser ? '我' : 'AI助手' }}</div>
      <div class="message-text" v-html="renderedContent"></div>
      <!-- 加载状态（AI流式响应时显示） -->
      <div class="loading-dot" v-if="loading && !isUser">
        <span class="dot"></span>
        <span class="dot"></span>
        <span class="dot"></span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElAvatar, ElIcon } from 'element-plus'
import { User, Headset } from '@element-plus/icons-vue'
// 引入Markdown渲染工具
import markdownIt from 'markdown-it'
import 'highlight.js/styles/github.css'

// 接收父组件传递的参数
const props = defineProps({
  // 消息内容（支持Markdown）
  content: { type: String, default: '' },
  // 是否为用户消息
  isUser: { type: Boolean, default: false },
  // 是否加载中（AI流式响应时）
  loading: { type: Boolean, default: false }
})

// Markdown实例初始化（支持代码高亮）
const md = markdownIt({
  html: true,
  highlight: function (str, lang) {
    if (lang && window.hljs.getLanguage(lang)) {
      try {
        return window.hljs.highlight(str, { language: lang }).value
      } catch (__) {}
    }
    return md.utils.escapeHtml(str)
  }
})

// 渲染后的Markdown内容
const renderedContent = ref('')

// 监听content变化，重新渲染Markdown
watch(
  () => props.content,
  (newVal) => {
    renderedContent.value = md.render(newVal)
  },
  { immediate: true }
)
</script>

<style scoped>
.message-bubble {
  display: flex;
  margin-bottom: 16px;
  align-items: flex-start;
}

/* 用户消息（右侧） */
.user-message {
  flex-direction: row-reverse;
}

.message-avatar {
  margin: 0 12px;
}

.message-content {
  max-width: 70%;
  padding: 12px 16px;
  border-radius: 8px;
  position: relative;
}

/* 用户消息样式 */
.user-message .message-content {
  background-color: #409eff;
  color: #fff;
}

/* AI消息样式 */
.ai-message .message-content {
  background-color: #fff;
  border: 1px solid #e5e7eb;
  color: #333;
}

.message-author {
  font-size: 12px;
  margin-bottom: 4px;
  opacity: 0.8;
}

.message-text {
  line-height: 1.6;
  font-size: 14px;
}

/* 代码块样式优化 */
.message-text pre {
  background-color: #f8fafc;
  padding: 8px;
  border-radius: 4px;
  overflow-x: auto;
  margin: 8px 0;
}

.message-text code {
  background-color: #f8fafc;
  padding: 2px 4px;
  border-radius: 4px;
}

/* 加载动画（三个点） */
.loading-dot {
  display: flex;
  align-items: center;
  margin-top: 8px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #409eff;
  margin-right: 4px;
  animation: dotFlashing 1.4s infinite both;
}

.dot:nth-child(2) {
  animation-delay: 0.2s;
}

.dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes dotFlashing {
  0% { opacity: 0.4; }
  50% { opacity: 1; }
  100% { opacity: 0.4; }
}
</style>