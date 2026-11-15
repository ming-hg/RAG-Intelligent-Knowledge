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
  /** 人工改过正文：charStart/charEnd 说的仍是编辑前那份正文里的位置。 */
  edited: boolean
  hitCount: number
}

/** PUT/POST 单块编辑的返回：note 不是错误，是「正文存下了、向量走到哪一步」。 */
export interface SegmentEditResult {
  segment: SegmentView
  embedded: boolean
  note: string | null
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

/** 五个数字/布尔都可空：空 = 这一项跟随全局默认，不是 0 也不是 false。 */
export interface ChunkOverride {
  childChars: number | null
  childMinChars: number | null
  overlapChars: number | null
  parentMaxChars: number | null
  parentChild: boolean | null
}

/** 五个值都定下来的那组参数：后端 RagProperties.Chunk（全局默认）与 ChunkConfig（生效值）结构相同。 */
export interface ChunkSettings {
  childChars: number
  childMinChars: number
  overlapChars: number
  parentMaxChars: number
  parentChild: boolean
}

/**
 * GET/PUT /api/documents/{id}/chunk-config 的返回。
 * override 是库里存的差异，defaults 是全局配置，effective 才是这篇文档下次入库真正用的那组值。
 */
export interface ChunkConfigView {
  override: ChunkOverride
  defaults: ChunkSettings
  effective: ChunkSettings
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
  /** -1 差 / 0 中性（也是撤销）/ 1 好；null 表示还没评过。 */
  rating: number | null
  feedbackText: string | null
  /** 人工订正过的答案：下一轮问答会用它顶替 content 进入上下文。 */
  annotatedAnswer: string | null
  traceId: string | null
  createdAt: string
  finishedAt: string | null
}

/**
 * PATCH /api/chat/messages/{id} 的请求体。
 * 省略或 null 都表示「这一项不改」，清空必须传空串——后端用 COALESCE 区分两者。
 */
export interface FeedbackPatch {
  rating?: number | null
  feedbackText?: string | null
  annotatedAnswer?: string | null
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
