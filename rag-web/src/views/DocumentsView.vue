<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ArchiveMenu from '@/components/ArchiveMenu.vue'
import DocumentDetail from '@/components/DocumentDetail.vue'
import ScopePicker from '@/components/ScopePicker.vue'
import { isInFlight, useDocumentStore } from '@/stores/documents'
import { useUiStore } from '@/stores/ui'
import type { DocumentView } from '@/types/api'

const docs = useDocumentStore()
const ui = useUiStore()
const route = useRoute()
const router = useRouter()

const selected = ref<number | null>(null)
const drawer = ref(false)
const statusFilter = ref('')

onMounted(async () => {
  await docs.load()
  const fromQuery = Number(route.query.doc ?? 0)
  if (fromQuery) open(fromQuery)
})

// 从问答页的引用跳过来时，链接里带的就是 docId
watch(
  () => route.query.doc,
  (value) => {
    const id = Number(value ?? 0)
    if (id) open(id)
  },
)

// 换库就重读列表：所有接口都按 kbId 过滤，留着上一个库的行会让人以为文档消失了
watch(() => ui.kbIds.join(','), () => docs.load())

onBeforeUnmount(() => {
  docs.polling = false
})

function open(id: number) {
  selected.value = id
  drawer.value = true
  void router.replace({ query: { ...route.query, doc: String(id) } })
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    await docs.upload(file)
  } finally {
    // 同一个文件再选一次也要触发：不清空 value 浏览器会认为没变化
    input.value = ''
  }
}

async function reindex(row: DocumentView, reparse: boolean) {
  await docs.reindex(row.id, reparse)
}

async function remove(row: DocumentView) {
  await ElMessageBox.confirm(
    `删除《${row.name}》会同时把它的块从检索中摘掉（软删除，向量与正文仍在库中，恢复需走数据库）。`,
    '删除文档',
    { type: 'warning', confirmButtonText: '删除' },
  )
  await docs.remove(row.id)
  if (selected.value === row.id) drawer.value = false
}

async function copySha(row: DocumentView) {
  await navigator.clipboard.writeText(row.sha256)
  ElMessage.success('sha256 已复制')
}

const visibleRows = computed(() =>
  statusFilter.value ? docs.rows.filter((row) => row.status === statusFilter.value) : docs.rows,
)

const statusClass = (status: string) =>
  status === 'READY' ? 'ok' : status === 'FAILED' ? 'down' : isInFlight(status) ? 'warn' : ''
</script>

<template>
  <div class="split">
    <section class="content">
      <div class="toolbar stack">
        <ScopePicker />
        <div class="row summary">
          <template v-if="docs.summary">
            <span class="tag">共 {{ docs.summary.total }}</span>
            <span class="tag ok">就绪 {{ docs.summary.ready }}</span>
            <span class="tag down">失败 {{ docs.summary.failed }}</span>
          </template>
          <span v-if="docs.polling" class="muted">进度轮询中…</span>
          <span class="spacer" />
          <el-select v-model="statusFilter" size="small" clearable placeholder="状态筛选" style="width: 130px">
            <el-option v-for="value in ['PENDING', 'PARSING', 'SPLITTING', 'EMBEDDING', 'INDEXING', 'READY', 'FAILED']"
              :key="value" :value="value" :label="value" />
          </el-select>
          <label class="upload">
            <input type="file" @change="onFileChange" :disabled="docs.uploading" />
            <span>{{ docs.uploading ? '上传中…' : '上传文档' }}</span>
          </label>
          <ArchiveMenu />
        </div>
      </div>

      <div class="scroll-y table-wrap">
        <el-table
          v-loading="docs.loading"
          :data="visibleRows"
          size="small"
          row-key="id"
          @row-click="(row: DocumentView) => open(row.id)"
        >
          <el-table-column label="文档" min-width="220">
            <template #default="{ row }">
              <span class="name">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <span class="tag" :class="statusClass(row.status)">{{ row.status }}</span>
              <el-progress
                v-if="isInFlight(row.status)"
                :percentage="row.progress"
                :stroke-width="4"
                :show-text="false"
              />
            </template>
          </el-table-column>
          <el-table-column label="进度说明" min-width="180">
            <template #default="{ row }">
              <span class="muted clamp">{{ row.errorCode ? `${row.errorCode}：${row.errorMsg ?? ''}` : row.progressMsg }}</span>
            </template>
          </el-table-column>
          <el-table-column label="页/字" width="90">
            <template #default="{ row }">
              <span class="mono">{{ row.pageCount ?? '—' }}/{{ row.charCount ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="代" width="52">
            <template #default="{ row }"><span class="mono">{{ row.version }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="210">
            <template #default="{ row }">
              <el-button size="small" @click.stop="open(row.id)">详情</el-button>
              <el-button size="small" @click.stop="reindex(row, false)">重算</el-button>
              <el-button size="small" @click.stop="reindex(row, true)" title="重新解析原文件，不复用库内正文">重解析</el-button>
              <el-button size="small" type="danger" plain @click.stop="remove(row)">删</el-button>
            </template>
          </el-table-column>
        </el-table>

        <p v-if="!docs.loading && docs.rows.length === 0" class="muted empty">
          这个库里还没有文档。上传后进度靠轮询显示，同内容重复上传不会重复消耗 embedding。
        </p>
      </div>
    </section>

    <el-drawer v-model="drawer" size="min(720px, 100%)" title="文档详情" direction="rtl">
      <DocumentDetail :doc-id="selected" :focus-segment="Number(route.query.segment ?? 0) || null" />
      <div class="row drawer-actions">
        <el-button size="small" @click="selected && copySha(docs.rows.find((r) => r.id === selected)!)"
          >复制 sha256</el-button
        >
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.toolbar {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.summary {
  gap: 6px;
  font-size: 12px;
}

.spacer {
  flex: 1;
}

.table-wrap {
  flex: 1;
  padding: 12px 14px;
}

.name {
  margin-right: 6px;
}

.clamp {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}

.upload {
  position: relative;
  overflow: hidden;
  padding: 4px 12px;
  border: 1px solid var(--accent);
  border-radius: 6px;
  color: var(--accent);
  cursor: pointer;
  white-space: nowrap;
}

.upload input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.empty {
  padding: 24px;
  text-align: center;
}

.drawer-actions {
  margin-top: 12px;
}
</style>
