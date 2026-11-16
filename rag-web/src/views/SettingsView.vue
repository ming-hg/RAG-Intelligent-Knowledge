<script setup lang="ts">
import { onMounted } from 'vue'
import { useHealthStore } from '@/stores/health'
import { useKbStore } from '@/stores/ui'

const health = useHealthStore()
const kb = useKbStore()

onMounted(() => {
  void health.refresh()
  void kb.load(true)
})

const tone = (status: string) => (status === 'UP' ? 'ok' : status === 'DEGRADED' ? 'warn' : 'down')
</script>

<template>
  <div class="content">
    <div class="toolbar row">
      <div class="stack head">
        <strong>{{ health.ready ? '关键依赖就绪' : health.message || '依赖未就绪' }}</strong>
        <span class="muted">
          自检只读，不会写库。模型类探测会真的调用一次推理，所以下面第二个按钮点下去会花几秒。
        </span>
      </div>
      <span class="spacer" />
      <el-button :loading="health.checking" @click="health.refresh()">快速自检</el-button>
      <el-button :loading="health.checking" type="primary" @click="health.refresh(true)">含模型调用自检</el-button>
    </div>

    <div class="scroll-y body stack">
      <el-table :data="health.probes" size="small" row-key="name">
        <el-table-column label="探测" width="180">
          <template #default="{ row }"><span class="mono">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><span class="tag" :class="tone(row.status)">{{ row.status }}</span></template>
        </el-table-column>
        <el-table-column label="说明" min-width="360">
          <template #default="{ row }"><span class="detail">{{ row.detail }}</span></template>
        </el-table-column>
        <el-table-column label="耗时" width="80">
          <template #default="{ row }"><span class="mono">{{ row.tookMs }}ms</span></template>
        </el-table-column>
      </el-table>

      <h3 class="block-title">知识库</h3>
      <el-table :data="kb.items" size="small" row-key="id">
        <el-table-column label="id" width="70">
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column label="向量模型" min-width="240">
          <template #default="{ row }"><span class="mono">{{ row.embeddingModel }}</span></template>
        </el-table-column>
        <el-table-column label="维度" width="80">
          <template #default="{ row }"><span class="mono">{{ row.embeddingDim }}</span></template>
        </el-table-column>
        <el-table-column label="索引代次" width="90">
          <template #default="{ row }"><span class="mono">{{ row.indexVersion }}</span></template>
        </el-table-column>
      </el-table>
      <p v-if="kb.hint" class="muted hint">后端回显：{{ kb.hint }}</p>

      <h3 class="block-title">这些不在界面改</h3>
      <ul class="muted list">
        <li>
          检索与上下文预算：<span class="mono">rag-api/src/main/resources/application.yml</span> 的
          <span class="mono">rag.retrieval</span>（mode / top-k / candidate-k / rrf-k / min-score / min-rrf-score / budget），
          可用 <span class="mono">RAG_RETRIEVAL_*</span>、<span class="mono">RAG_LLM_N_CTX</span> 覆盖。
        </li>
        <li>
          分块与入库：<span class="mono">rag.ingest</span>，改 <span class="mono">child-chars</span> 之类只影响之后的入库，
          想看效果先用文档详情页的「分块预览」。
        </li>
        <li>
          <span class="mono">n-ctx</span> 必须与 LM Studio 实际加载的窗口一致，否则上下文会超窗并被模型静默截断。
        </li>
        <li>dev 前端口的后端地址由 <span class="mono">VITE_API_TARGET</span> 决定，默认 <span class="mono">http://localhost:8080</span>。</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.head {
  flex: 1;
  min-width: 0;
  gap: 4px;
}

.body {
  flex: 1;
  padding: 12px 14px;
}

.spacer {
  flex: 1;
}

.detail {
  font-size: 12px;
  white-space: pre-wrap;
}

.block-title {
  margin: 16px 0 4px;
  font-size: 13px;
}

.hint,
.list {
  font-size: 12px;
}

.list {
  margin: 4px 0 0;
  padding-left: 18px;
  line-height: 1.9;
}
</style>
