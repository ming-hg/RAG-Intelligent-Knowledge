<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ScopePicker from '@/components/ScopePicker.vue'
import { searchApi } from '@/api/search'
import { useKbStore, useUiStore } from '@/stores/ui'
import type { SearchResponse } from '@/types/api'

const ui = useUiStore()
const kb = useKbStore()
const router = useRouter()

const query = ref('')
const result = ref<SearchResponse | null>(null)
const running = ref(false)

onMounted(() => {
  void kb.load()
})

async function run() {
  if (!query.value.trim()) return
  if (ui.kbIds.length === 0 && ui.docIds.length === 0) {
    ElMessage.warning('先选至少一个知识库')
    return
  }
  running.value = true
  try {
    result.value = await searchApi.run({
      query: query.value,
      kbIds: ui.kbIds,
      docIds: ui.docIds,
      mode: ui.mode,
      topK: ui.topK,
    })
  } finally {
    running.value = false
  }
}

function openDoc(docId: number, segmentId: number) {
  void router.push({ name: 'documents', query: { doc: String(docId), segment: String(segmentId) } })
}

const num = (value: number | null | undefined, digits = 5) =>
  value === null || value === undefined ? '—' : value.toFixed(digits)
const rank = (value: number | null) => (value === null || value === undefined ? '—' : `#${value}`)
</script>

<template>
  <div class="content">
    <div class="toolbar stack">
      <ScopePicker />
      <div class="row">
        <el-input
          v-model="query"
          placeholder="输入一句问题，看看检索到底召回了什么（不生成回答，也不花钱）"
          @keydown.enter="run"
        />
        <el-button type="primary" :loading="running" @click="run">检索</el-button>
      </div>
    </div>

    <div class="scroll-y body">
      <template v-if="result">
        <div class="row banner">
          <span class="tag" :class="result.degraded ? 'warn' : 'ok'">
            {{ result.requestedMode === result.effectiveMode ? result.effectiveMode : `${result.requestedMode} → ${result.effectiveMode}` }}
          </span>
          <span class="tag" :title="result.keywordEngineNote">{{ result.keywordEngine }}</span>
          <span class="tag">{{ result.hitCount }} 条命中</span>
          <span class="tag">{{ result.tookMs }} ms</span>
          <span v-if="result.degraded && result.vectorFailure" class="tag down">{{ result.vectorFailure }}</span>
        </div>

        <p v-if="result.hits.length === 0" class="muted empty">
          零命中。此时问答会直接拒答而不写提示词——可以先放宽 min-rrf-score、换 hybrid 模式，或确认这个库里有就绪文档。
        </p>

        <el-table v-else :data="result.hits" size="small" row-key="segmentId">
          <el-table-column label="#" width="46">
            <template #default="{ row }"><span class="mono">{{ row.rank }}</span></template>
          </el-table-column>
          <el-table-column label="来源" min-width="200">
            <template #default="{ row }">
              <button class="link" @click="openDoc(row.docId, row.segmentId)">
                {{ row.docName }} · 块 {{ row.chunkNo }}
              </button>
              <span v-if="row.hasParent" class="tag">有父块</span>
              <p class="muted location">{{ row.location }}</p>
            </template>
          </el-table-column>
          <el-table-column label="片段" min-width="300">
            <template #default="{ row }"><span class="snippet">{{ row.snippet }}</span></template>
          </el-table-column>
          <el-table-column label="向量" width="66">
            <template #default="{ row }"><span class="mono">{{ rank(row.vectorRank) }}</span></template>
          </el-table-column>
          <el-table-column label="关键词" width="76">
            <template #default="{ row }"><span class="mono">{{ rank(row.keywordRank) }}</span></template>
          </el-table-column>
          <el-table-column label="RRF" width="86">
            <template #default="{ row }"><span class="mono">{{ num(row.rrf) }}</span></template>
          </el-table-column>
          <el-table-column label="余弦" width="76">
            <template #default="{ row }"><span class="mono">{{ num(row.similarity, 3) }}</span></template>
          </el-table-column>
        </el-table>
      </template>

      <p v-else class="muted empty">
        检索调试用于回答「为什么没召回」：这里能看到每一路的名次、融合后的 RRF 分与相似度，
        以及当前用的关键词引擎和它为什么被降级。
      </p>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.body {
  flex: 1;
  padding: 12px 14px;
}

.banner {
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
  font-size: 12px;
}

.location {
  margin: 2px 0 0;
  font-size: 12px;
}

.snippet {
  font-size: 12px;
  color: #374151;
}

.link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--accent);
  cursor: pointer;
}

.empty {
  padding: 24px;
  text-align: center;
}
</style>
