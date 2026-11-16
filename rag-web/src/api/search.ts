import { http } from './http'
import type { RetrievalMode, SearchResponse } from '@/types/api'

export interface SearchParams {
  query: string
  kbIds?: number[]
  docIds?: number[]
  mode?: RetrievalMode
  topK?: number
  snippetChars?: number
}

export const searchApi = {
  /** POST 走 JSON 体，多库多文档时不会被 URL 长度限制住。 */
  run: (params: SearchParams) => http.post<SearchResponse>('/search', params),

  /** GET 便于粘链接复现一次调参结果。 */
  runGet: (params: SearchParams) =>
    http.get<SearchResponse>('/search', {
      q: params.query,
      kbIds: params.kbIds,
      docIds: params.docIds,
      mode: params.mode,
      topK: params.topK,
    }),
}
