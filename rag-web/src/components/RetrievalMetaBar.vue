<script setup lang="ts">
import { computed } from 'vue'
import type { RetrievalMeta } from '@/types/api'

const props = defineProps<{ meta: RetrievalMeta | null }>()

const degraded = computed(() => props.meta?.degraded === true)
const modeText = computed(() => {
  const meta = props.meta
  if (!meta?.effectiveMode) return null
  return meta.requestedMode && meta.requestedMode !== meta.effectiveMode
    ? `${meta.requestedMode} → ${meta.effectiveMode}`
    : meta.effectiveMode
})
const budget = computed(() => {
  const meta = props.meta
  if (!meta?.budgetChars) return null
  const used = meta.contextChars ?? 0
  const percent = Math.round((used / meta.budgetChars) * 100)
  return { used, total: meta.budgetChars, percent }
})
const truncated = computed(() => props.meta?.contextTruncated === true || (props.meta?.unplacedHits ?? 0) > 0)
</script>

<template>
  <div v-if="meta" class="meta row">
    <span class="tag" :class="degraded ? 'warn' : 'ok'" :title="meta.vectorFailure ?? undefined">
      {{ degraded ? `降级：${modeText ?? meta.effectiveMode}` : `模式：${modeText}` }}
    </span>
    <span class="tag" :class="meta.keywordEngine === 'CHINESE_TSVECTOR' ? 'ok' : 'warn'" :title="meta.keywordEngine">
      {{ meta.keywordEngine === 'CHINESE_TSVECTOR' ? 'zhparser' : meta.keywordEngine === 'TRIGRAM' ? 'trigram' : '无全文' }}
    </span>
    <span v-if="budget" class="tag" :class="truncated ? 'warn' : ''" title="上下文用量">
      上下文 {{ budget.used }}/{{ budget.total }} 字（{{ budget.percent }}%）
    </span>
    <span v-if="truncated" class="tag warn" :title="`有 ${meta.unplacedHits ?? 0} 条命中因预算没进上下文`">
      截断，未放入 {{ meta.unplacedHits ?? 0 }} 条
    </span>
    <span v-if="meta.retrievalMs !== undefined" class="tag" :title="meta.vectorFailure ?? undefined">
      检索 {{ meta.retrievalMs }}ms
    </span>
  </div>
</template>

<style scoped>
.meta {
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
}
</style>
