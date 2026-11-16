<script setup lang="ts">
import { onMounted } from 'vue'
import { useHealthStore } from '@/stores/health'

const health = useHealthStore()

// 起页面先探一次依赖：本机最常见的情况是「前端先开了，后端或数据库还没起」
onMounted(() => {
  void health.refresh()
})
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="brand">
        <span class="brand-name">本地知识库</span>
        <span class="brand-sub">RAG · 全程不出局域网</span>
      </div>

      <nav class="nav">
        <RouterLink to="/chat">问答</RouterLink>
        <RouterLink to="/documents">文档</RouterLink>
        <RouterLink to="/search">检索调试</RouterLink>
        <RouterLink to="/settings">自检</RouterLink>
      </nav>

      <button class="health-pill" :class="health.tone" type="button" title="点击重新探测" @click="health.refresh()">
        <span class="dot" />
        {{ health.label }}
      </button>
    </header>

    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
