import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'
import { documentsApi } from '@/api/documents'
import { useUiStore } from './ui'
import type { DocumentSummary, DocumentView, UploadResult } from '@/types/api'

/** 中间态：这些状态还得靠轮询推进度，READY/FAILED 是终态。 */
const IN_FLIGHT = new Set(['PENDING', 'PARSING', 'SPLITTING', 'EMBEDDING', 'INDEXING'])

export const isInFlight = (status: string): boolean => IN_FLIGHT.has(status)

export const useDocumentStore = defineStore('documents', {
  state: () => ({
    rows: [] as DocumentView[],
    summary: null as DocumentSummary | null,
    loading: false,
    uploading: false,
    lastUpload: null as UploadResult | null,
    polling: false,
  }),

  getters: {
    inFlightCount: (state) => state.rows.filter((row) => IN_FLIGHT.has(row.status)).length,
  },

  actions: {
    async load(silent = false) {
      const kbId = useUiStore().kbIds[0]
      this.loading = true
      try {
        const [rows, summary] = await Promise.all([
          documentsApi.list({ kbId, limit: 200 }),
          documentsApi.summary(kbId),
        ])
        this.rows = rows
        this.summary = summary
      } catch {
        if (!silent) ElMessage.error('文档列表加载失败')
      } finally {
        this.loading = false
      }
    },

    /**
     * 轮询由列表自己控制：一旦没有中间态就停，不让它变成后台常驻的空转。
     * 进度写进 kb_document，所以只读接口 + 定时刷新，不额外开一条 SSE。
     */
    async pollWhileRunning(intervalMs = 2000) {
      if (this.polling) return
      this.polling = true
      try {
        while (this.inFlightCount > 0) {
          await new Promise((resolve) => setTimeout(resolve, intervalMs))
          await this.load(true).catch(() => undefined)
        }
      } finally {
        this.polling = false
      }
    },

    async upload(file: File) {
      const ui = useUiStore()
      this.uploading = true
      try {
        const result = await documentsApi.upload(file, ui.kbIds[0])
        this.lastUpload = result
        if (result.duplicated) ElMessage.success('内容相同的文档已存在，未重复消耗 embedding')
        else ElMessage.success(`已入队，任务 id=${result.taskId}`)
        await this.load(true)
        void this.pollWhileRunning()
        return result
      } finally {
        this.uploading = false
      }
    },

    async reindex(id: number, reparse = false) {
      await documentsApi.reindex(id, reparse)
      ElMessage.success(reparse ? '已重新排队入库（重新解析原文件）' : '已重新排队入库（复用库内正文）')
      await this.load(true)
      void this.pollWhileRunning()
    },

    async remove(id: number) {
      await documentsApi.remove(id)
      await this.load(true)
    },
  },
})
