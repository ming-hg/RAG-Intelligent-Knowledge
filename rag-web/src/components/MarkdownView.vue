<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/core'
import type { LanguageFn } from 'highlight.js'
import javascript from 'highlight.js/lib/languages/javascript'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import bash from 'highlight.js/lib/languages/bash'
import json from 'highlight.js/lib/languages/json'
import 'highlight.js/styles/github.css'

const props = defineProps<{ text: string; streaming?: boolean }>()
const emit = defineEmits<{ select: [citation: number] }>()

// 只注册本机常见的几种语言：整包 highlight.js 会让首屏多下 900KB 却用不上
const languages: Array<[string, LanguageFn]> = [
  ['javascript', javascript],
  ['python', python],
  ['sql', sql],
  ['bash', bash],
  ['json', json],
]
languages.forEach(([name, language]) => hljs.registerLanguage(name, language))

const markdown = new MarkdownIt({
  html: false, // 文档正文里的原始 HTML 一律当文本，本地系统同样不该被一份 PDF 注入脚本
  linkify: true,
  breaks: true,
  highlight: (code, lang) => (hljs.getLanguage(lang) ? hljs.highlight(code, { language: lang }).value : ''),
})

const html = computed(() => renderCitations(markdown.render(props.text ?? '')))

/**
 * 正文里的 [3] 换成可点 chip。跳过 <pre>/<code> 片段：代码块里的 [0] 是下标，不是引用编号。
 */
function renderCitations(rendered: string): string {
  return rendered
    .split(/(<(?:pre|code)\b[^>]*>[\s\S]*?<\/(?:pre|code)>)/g)
    .map((part, index) =>
      index % 2 === 1
        ? part
        : part.replace(/\[(\d{1,3})\]/g, (_match, digits: string) => {
            const number = Number(digits)
            return `<span class="cite" data-cite="${number}" title="查看第 ${number} 条来源">[${digits}]</span>`
          }),
    )
    .join('')
}

function onClick(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  const citation = target?.dataset?.cite
  if (citation) emit('select', Number(citation))
}
</script>

<template>
  <div class="markdown" @click="onClick">
    <span v-html="html" />
    <span v-if="streaming" class="caret" />
  </div>
</template>

<style scoped>
.caret {
  display: inline-block;
  width: 6px;
  height: 1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--accent);
  animation: blink 1s steps(2, start) infinite;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}
</style>
