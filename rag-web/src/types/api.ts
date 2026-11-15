/**
 * 后端 DTO 的镜像。字段名与 rag-api 里的 record 组件一字不差——改后端 record 字段必须同步这里，
 * `npm run typecheck` 不会替你发现跨进程的不匹配，所以这几个类型就是两侧之间唯一的契约文档。
 */

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId: string | null
}

export type DocStatus =
  | 'PENDING'
  | 'PARSING'
  | 'SPLITTING'
  | 'EMBEDDING'
  | 'INDEXING'
  | 'READY'
  | 'FAILED'

export type RetrievalMode = 'vector' | 'keyword' | 'hybrid'

export type KeywordEngine = 'CHINESE_TSVECTOR' | 'TRIGRAM' | 'NONE'

export type AnswerStatus = 'DONE' | 'STREAMING' | 'OK' | 'REFUSED' | 'FAILED' | 'INTERRUPTED'

export interface KnowledgeBase {
  id: number
  name: string
  embeddingModel: string
  embeddingDim: number
  indexVersion: number
}

export interface DocumentView {
  id: number
  kbId: number
  name: string
  status: DocStatus
  progress: number
  progressMsg: string | null
  errorCode: string | null
  errorMsg: string | null
  version: number
  pageCount: number | null
  charCount: number | null
  fileSize: number | null
  mime: string | null
  sha256: string
}

export interface UploadResult {
  documentId: number
  taskId: number
  duplicated: boolean
  status: DocStatus
}

export interface DocumentSummary {
  kbId: number
  total: number
  ready: number
  failed: number
}

export interface SegmentView {
  id: number
  chunkNo: number
  parentChunkNo: number | null
  content: string
  headingPath: string | null
  pageStart: number | null
  pageEnd: number | null
  charStart: number | null
  charEnd: number | null
  available: boolean
  embStatus: string
  hitCount: number
}

export interface PreviewBlock {
  content: string
  chunkNo: number
  parentChunkNo: number | null
  charStart: number
  charEnd: number
  headingPath: string | null
  pageStart: number
  pageEnd: number
  searchable: boolean
}

export interface ChunkPreview {
  pageCount: number
  totalBlocks: number
  searchableBlocks: number
  blocks: PreviewBlock[]
}

export interface SearchHit {
  rank: number
  segmentId: number
  docId: number
  docName: string
  chunkNo: number
  location: string
  snippet: string
  pageStart: number | null
  pageEnd: number | null
  charStart: number | null
  charEnd: number | null
  hasParent: boolean
  vectorRank: number | null
  keywordRank: number | null
  rrf: number
  similarity: number | null
}

export interface SearchResponse {
  requestedMode: string
  effectiveMode: string
  keywordEngine: KeywordEngine
  keywordEngineNote: string
  degraded: boolean
  vectorFailure: string | null
  hitCount: number
  tookMs: number
  hits: SearchHit[]
}

export interface ChatSession {
  id: string
  title: string
  kbIds: number[]
  pinned: boolean
  createdAt: string
  updatedAt: string
}

/** 一条来源：引用编号在生成时分配并落库，前端绝不重新编号。 */
export interface Source {
  citation: number
  segmentId: number
  docId: number
  docName: string
  chunkNo: number
  location: string
  pageStart: number | null
  pageEnd: number | null
  parentExpanded: boolean
  chars: number
  vectorRank: number | null
  keywordRank: number | null
  rrf: number
  similarity: number | null
}

export interface RetrievalMeta {
  sessionId?: string
  questionId?: string
  answerId?: string
  requestedMode?: string
  effectiveMode?: string
  keywordEngine?: KeywordEngine
  degraded?: boolean
  vectorFailure?: string | null
  contextChars?: number
  budgetChars?: number
  unplacedHits?: number
  contextTruncated?: boolean
  retrievalMs?: number
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  status: AnswerStatus | null
  queryFinal: string | null
  sources: Source[] | null
  retrievalMeta: RetrievalMeta | null
  promptTokens: number | null
  completionTokens: number | null
  model: string | null
  traceId: string | null
  createdAt: string
  finishedAt: string | null
}

/** SSE meta 事件比落库的 retrieval_meta 多一个 sources 数组，单独开一个类型。 */
export interface AskMeta extends RetrievalMeta {
  sessionId: string
  questionId: string
  answerId: string
  sources: Source[]
}

export interface AskDone {
  answerId: string
  status: AnswerStatus
  model: string | null
  promptTokens: number | null
  completionTokens: number | null
  answerMs: number
  totalMs: number
}

export interface HealthProbe {
  name: string
  status: 'UP' | 'DEGRADED' | 'DOWN'
  detail: string
  tookMs: number
}

export interface HealthReport {
  ready: boolean
  probes: HealthProbe[]
}

export interface AskRequest {
  sessionId?: string
  question: string
  kbIds?: number[]
  docIds?: number[]
  mode?: RetrievalMode
  topK?: number
}
