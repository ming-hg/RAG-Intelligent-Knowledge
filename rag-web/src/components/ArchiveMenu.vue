<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { saveBlob } from '@/api/http'
import { knowledgeApi } from '@/api/knowledge'
import { useDocumentStore } from '@/stores/documents'
import { useKbStore, useUiStore } from '@/stores/ui'
import type { ImportSummary } from '@/types/api'

const docs = useDocumentStore()
const kb = useKbStore()
const ui = useUiStore()

const panel = ref(false)
const target = ref<number | null>(null)
const withVectors = ref(true)
const withOriginals = ref(false)
const forceRebuild = ref(false)
const exporting = ref(false)
const importing = ref(false)
const summary = ref<ImportSummary | null>(null)
const summaryOpen = ref(false)

// 导出和导入都只认一个库；打开面板时会把 target 填成当前列表在看的那个，这里只是兜底
const kbId = computed(() => target.value ?? ui.kbIds[0] ?? null)

watch(panel, async (isOpen) => {
  if (!isOpen) return
  await kb.load(true)
  target.value ??= ui.kbIds[0] ?? kb.items[0]?.id ?? null
})

async function doExport() {
  const id = kbId.value
  if (id === null) return ElMessage.warning('先选一个知识库')
  exporting.value = true
  try {
    const { blob, fileName } = await knowledgeApi.exportArchive(id, withVectors.value, withOriginals.value)
    saveBlob(blob, fileName || `kb-${id}.zip`)
    ElMessage.success(
      withVectors.value ? `已下载 ${fileName || '归档包'}` : `已下载 ${fileName || '归档包'}（未带向量，导入时需重新 embedding）`,
    )
    panel.value = false
  } catch {
    // 失败原因后端写好了中文，http.download 已经弹过一次，这里不重复说
  } finally {
    exporting.value = false
  }
}

async function onArchivePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const id = kbId.value
  if (id === null) return
  if (!file) return
  importing.value = true
  try {
    summary.value = await knowledgeApi.importArchive(id, file, forceRebuild.value)
    summaryOpen.value = true
    panel.value = false
    // 重新入库模式下文档排进了队列，进度和上传走同一条轮询
    await docs.load(true)
    void docs.pollWhileRunning()
  } catch {
    // 400/409 的「哪儿不对、改什么」由后端给出，http.upload 已弹过
  } finally {
    importing.value = false
    // 同一个包再选一次也要触发：不清空 value 浏览器会认为没变化
    input.value = ''
  }
}
</script>

<template>
  <el-button size="small" @click="panel = true">归档</el-button>

  <el-dialog
    v-model="panel"
    title="知识库导出 / 导入"
    width="min(580px, 94vw)"
    :close-on-click-modal="!exporting && !importing"
  >
    <div class="stack body">
      <div class="row">
        <span class="label">目标库</span>
        <el-select
          v-model="target"
          size="small"
          placeholder="未选择知识库"
          style="flex: 1"
          @visible-change="(open: boolean) => open && kb.load(true)"
        >
          <el-option v-for="item in kb.items" :key="item.id" :value="item.id" :label="item.name">
            <span>{{ item.name }}</span>
            <span class="muted mono dim">{{ item.embeddingDim }}d · {{ item.embeddingModel }}</span>
          </el-option>
        </el-select>
      </div>

      <section class="block">
        <h4>导出为归档包</h4>
        <div class="row">
          <el-switch v-model="withVectors" size="small" active-text="带向量" />
          <el-switch v-model="withOriginals" size="small" active-text="带原件" />
          <span class="spacer" />
          <el-button size="small" type="primary" plain :loading="exporting" :disabled="kbId === null" @click="doExport">
            {{ exporting ? '打包中…' : '导出' }}
          </el-button>
        </div>
        <p class="muted hint">
          向量是 base64 的 float32，带上它才能让另一个库不重算直接可用；但只有目标库的 embedding 模型与维度完全一致才算数。
          原件体积远大于正文，只在需要连原始 pdf/docx 一起迁移时勾选。
        </p>
      </section>

      <section class="block">
        <h4>导入归档包</h4>
        <div class="row">
          <label class="upload">
            <input type="file" accept=".zip" :disabled="importing || kbId === null" @change="onArchivePicked" />
            <span>{{ importing ? '导入中…' : '选择 .zip' }}</span>
          </label>
          <el-switch v-model="forceRebuild" size="small" active-text="强制重算向量" />
        </div>
        <p class="muted hint">
          同名（sha256 相同）的文档跳过并写进结果，不会覆盖已有内容。勾选「强制重算向量」则无视模型是否一致，
          全部按重新入库导入——换 embedding 模型时用这个。
        </p>
      </section>
    </div>
  </el-dialog>

  <el-dialog v-model="summaryOpen" title="导入结果" width="min(580px, 94vw)">
    <div v-if="summary" class="stack">
      <div class="row tags">
        <span class="tag" :class="summary.mode === 'CARRIED' ? 'ok' : 'warn'">{{ summary.mode }}</span>
        <span class="tag">导入 {{ summary.imported }}</span>
        <span class="tag">排队 {{ summary.queued }}</span>
        <span class="tag">照搬块 {{ summary.segmentsCarried }}</span>
        <span class="tag">原件 {{ summary.originalsRestored }}</span>
        <span v-if="summary.skippedSame" class="tag">同名跳过 {{ summary.skippedSame }}</span>
        <span v-if="summary.skippedEmpty" class="tag">空正文 {{ summary.skippedEmpty }}</span>
        <span v-if="summary.failed" class="tag down">失败 {{ summary.failed }}</span>
      </div>
      <p class="muted">
        {{
          summary.mode === 'CARRIED'
            ? '向量空间一致，块直接照搬，一次 embedding 都没花。'
            : '向量空间对不上，已按重新入库导入；等列表里的文档都变 READY 后检索才完整。'
        }}
      </p>
      <ul v-if="summary.notes.length" class="notes">
        <li v-for="(note, index) in summary.notes" :key="index">{{ note }}</li>
      </ul>
      <p v-else class="muted">没有需要提醒的条目。</p>
    </div>
  </el-dialog>
</template>

<style scoped>
.body {
  padding-bottom: 4px;
}

.label {
  flex: none;
  width: 52px;
  color: var(--muted);
  font-size: 12px;
}

.dim {
  float: right;
  margin-left: 16px;
  font-size: 12px;
}

.block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 6px;
  border-top: 1px solid var(--border);
}

.block h4 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.spacer {
  flex: 1;
}

.hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
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

.tags {
  flex-wrap: wrap;
}

.notes {
  margin: 0;
  padding-left: 18px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
  max-height: 220px;
  overflow-y: auto;
}
</style>
