import { http } from './http'
import type { KnowledgeBase } from '@/types/api'

export const knowledgeApi = {
  list: () => http.get<KnowledgeBase[]>('/knowledge-bases'),
}
