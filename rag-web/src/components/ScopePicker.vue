<script setup lang="ts">
import { onMounted } from 'vue'
import { useKbStore, useUiStore } from '@/stores/ui'
import type { RetrievalMode } from '@/types/api'

const ui = useUiStore()
const kb = useKbStore()

const MODES: Array<{ value: RetrievalMode; label: string; hint: string }> = [
  { value: 'hybrid', label: '混合', hint: '向量路 + 关键词路 RRF 融合' },
  { value: 'vector', label: '纯向量', hint: '只走向量，阈值按余弦分' },
  { value: 'keyword', label: '纯关键词', hint: '只走全文/trigram' },
]

onMounted(() => {
  void kb.load()
})
</script>

<template>
  <div class="scope">
    <div class="row scope-row">
      <span class="label">知识库</span>
      <el-select
        v-model="ui.kbIds"
        multiple
        collapse-tags
        collapse-tags-tooltip
        placeholder="未选择（问答与检索必须选一个）"
        size="small"
        style="flex: 1"
        @visible-change="(open: boolean) => open && kb.load(true)"
      >
        <el-option v-for="item in kb.items" :key="item.id" :value="item.id" :label="item.name">
          <span>{{ item.name }}</span>
          <span class="muted mono option-dim">{{ item.embeddingDim }}d · {{ item.embeddingModel }}</span>
        </el-option>
      </el-select>
    </div>

    <div class="row scope-row">
      <span class="label">模式</span>
      <el-select v-model="ui.mode" size="small" style="flex: 1">
        <el-option v-for="mode in MODES" :key="mode.value" :value="mode.value" :label="mode.label">
          <span>{{ mode.label }}</span>
          <span class="muted option-dim">{{ mode.hint }}</span>
        </el-option>
      </el-select>

      <span class="label">topK</span>
      <el-input-number v-model="ui.topK" :min="1" :max="50" size="small" controls-position="right" />
    </div>

    <p v-if="ui.kbIds.length === 0" class="warn muted">
      尚未选择知识库。后端拒绝无作用域的检索，避免把无关库的资料塞进上下文。
    </p>
  </div>
</template>

<style scoped>
.scope {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.scope-row {
  gap: 8px;
}

.label {
  flex: none;
  width: 52px;
  color: var(--muted);
  font-size: 12px;
}

.option-dim {
  float: right;
  margin-left: 16px;
  font-size: 12px;
}

.warn {
  margin: 2px 0 0;
  font-size: 12px;
}
</style>
