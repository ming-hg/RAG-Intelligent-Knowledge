<template>
  <div class="markdown-renderer" v-html="renderedContent"></div>
</template>

<script setup>
import { ref, watch } from 'vue'
import markdownIt from 'markdown-it'
import 'highlight.js/styles/github.css'

// 接收父组件传递的Markdown内容
const props = defineProps({
  content: { type: String, default: '' }
})

// Markdown实例初始化（支持代码高亮、HTML）
const md = markdownIt({
  html: true,
  linkify: true,
  typographer: true,
  highlight: function (str, lang) {
    if (lang && window.hljs.getLanguage(lang)) {
      try {
        return window.hljs.highlight(str, { language: lang }).value
      } catch (__) {}
    }
    return md.utils.escapeHtml(str)
  }
})

// 渲染后的内容
const renderedContent = ref('')

// 监听content变化，重新渲染
watch(
  () => props.content,
  (newVal) => {
    renderedContent.value = md.render(newVal)
  },
  { immediate: true }
)
</script>

<style scoped>
.markdown-renderer {
  line-height: 1.8;
  font-size: 14px;
  color: #333;
}

/* Markdown基础样式 */
.markdown-renderer h1 {
  font-size: 24px;
  font-weight: 600;
  margin: 24px 0 16px;
  border-bottom: 1px solid #e5e7eb;
  padding-bottom: 8px;
}

.markdown-renderer h2 {
  font-size: 20px;
  font-weight: 600;
  margin: 20px 0 12px;
}

.markdown-renderer h3 {
  font-size: 18px;
  font-weight: 600;
  margin: 16px 0 8px;
}

.markdown-renderer p {
  margin: 8px 0;
}

.markdown-renderer ul, .markdown-renderer ol {
  margin: 8px 0 8px 24px;
  padding: 0;
}

.markdown-renderer li {
  margin: 4px 0;
}

.markdown-renderer a {
  color: #409eff;
  text-decoration: none;
}

.markdown-renderer a:hover {
  text-decoration: underline;
}

.markdown-renderer blockquote {
  border-left: 4px solid #e5e7eb;
  padding: 8px 16px;
  background-color: #f8fafc;
  margin: 8px 0;
}

/* 代码块样式 */
.markdown-renderer pre {
  background-color: #f8fafc;
  padding: 12px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 12px 0;
}

.markdown-renderer code {
  background-color: #f8fafc;
  padding: 2px 4px;
  border-radius: 4px;
  font-size: 13px;
}
</style>