import { http } from './http'
import type { ChunkPreview, DocumentSummary, DocumentView, SegmentView, UploadResult } from '@/types/api'

// 用 type 而不是 interface：http.get 的参数是 Record<string, QueryValue>，
// interface 没有隐式索引签名，传进去会在类型层被拒。
export type ListFilter = {
  kbId?: number
  status?: string
  limit?: number
  offset?: number
}

export type ChunkParams = {
  childChars?: number
  childMinChars?: number
  overlapChars?: number
  parentMaxChars?: number
  parentChild?: boolean
}

export const documentsApi = {
  list: (filter: ListFilter = {}) => http.get<DocumentView[]>('/documents', filter),

  summary: (kbId?: number) => http.get<DocumentSummary>('/documents/summary', { kbId }),

  detail: (id: number) => http.get<DocumentView>(`/documents/${id}`),

  content: (id: number) => http.get<string>(`/documents/${id}/content`),

  segments: (id: number, onlySearchable = true, limit = 500, offset = 0) =>
    http.get<SegmentView[]>(`/documents/${id}/segments`, { onlySearchable, limit, offset }),

  preview: (id: number, params: ChunkParams, limit = 30) =>
    http.get<ChunkPreview>(`/documents/${id}/chunks/preview`, { ...params, limit }),

  setSegmentAvailable: (id: number, segmentId: number, available: boolean) =>
    http.patch<{ documentId: number; segmentId: number; available: boolean }>(
      `/documents/${id}/segments/${segmentId}`,
      { available },
    ),

  // reparse 是后端的 @RequestParam，不是请求体，只能拼在 URL 上
  reindex: (id: number, reparse = false) =>
    http.post<{ documentId: number; taskId: number }>(`/documents/${id}/reindex?reparse=${reparse}`),

  remove: (id: number) => http.del<{ documentId: string }>(`/documents/${id}`),

  upload: (file: File, kbId?: number) => {
    const form = new FormData()
    form.append('file', file)
    const query = kbId === undefined ? '' : `?kbId=${kbId}`
    return http.upload<UploadResult>(`/documents${query}`, form)
  },
}
