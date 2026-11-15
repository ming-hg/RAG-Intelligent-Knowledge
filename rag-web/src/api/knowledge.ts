import { http } from './http'
import type { ImportSummary, KnowledgeBase } from '@/types/api'

export const knowledgeApi = {
  list: () => http.get<KnowledgeBase[]>('/knowledge-bases'),

  /**
   * 整库导出成 zip 附件。
   * vectors=false 出来的包只能靠重新 embedding 恢复（换机迁移必须带上），files=true 会连原件一起走，包会大一个量级。
   */
  exportArchive: (kbId: number, vectors: boolean, files: boolean) =>
    http.download(`/knowledge-bases/${kbId}/export`, { vectors, files }),

  // rebuild 是查询参数，不是请求体： multipart 的 body 只有一个 file part
  importArchive: (kbId: number, file: File, rebuild: boolean) => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<ImportSummary>(`/knowledge-bases/${kbId}/import?rebuild=${rebuild}`, form)
  },
}
