<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { documentsApi, type ChunkParams } from '@/api/documents'
import type {
  ChunkConfigView,
  ChunkOverride,
  DocumentView,
  PreviewBlock,
  SegmentEditResult,
  SegmentView,
} from '@/types/api'

const props = defineProps<{ docId: number | null; focusSegment?: number | null }>()

const root = ref<HTMLElement | null>(null)
const doc = ref<DocumentView | null>(null)
const segments = ref<SegmentView[]>([])
const content = ref('')
const preview = ref<{ blocks: PreviewBlock[]; totalBlocks: number; searchableBlocks: number } | null>(null)
const chunk = ref<ChunkConfigView | null>(null)
const params = ref<ChunkParams>({})
const saving = ref(false)
const busy = ref(false)
const tab = ref<'segments' | 'content' | 'preview'>('segments')

const hasOverride = computed(() => Object.values(chunk.value?.override ?? {}).some((value) => value !== null))

watch(
  () => props.docId,
  async (id) => {
    segments.value = []
    content.value = ''
    preview.value = null
    doc.value = null
    chunk.value = null
    if (!id) return
    busy.value = true
    try {
      const [detail, rows, config] = await Promise.all([
        documentsApi.detail(id),
        documentsApi.segments(id, false, 500),
        documentsApi.chunkConfig(id),
      ])
      doc.value = detail
      segments.value = rows
      chunk.value = config
      // 表单从「这篇文档真正生效的那组值」起步，而不是前端抄一份默认值：两边各抄一份迟早会飘
      params.value = { ...config.effective }
      if (props.focusSegment) {
        await nextTick()
        root.value?.querySelector('.el-table__row.focus')?.scrollIntoView({ block: 'center' })
      }
    } finally {
      busy.value = false
    }
  },
  { immediate: true },
)

/** 引用跳转带 ?segment=，命中那块要在几百行里一眼看到，所以给它一个行级高亮。 */
const focusRow = ({ row }: { row: SegmentView }) => (row.id === props.focusSegment ? 'focus' : '')

async function toggle(segment: SegmentView) {
  if (!doc.value) return
  // 失败时先把界面改回去：后端的 404 说明这个块已被新版本替换，界面上留着「已禁用」是骗人
  const before = segment.available
  segment.available = !before
  try {
    await documentsApi.setSegmentAvailable(doc.value.id, segment.id, segment.available)
    ElMessage.success(segment.available ? '块已恢复检索' : '块已从检索中排除')
  } catch {
    segment.available = before
  }
}

async function loadContent() {
  if (!doc.value) return
  content.value = await documentsApi.content(doc.value.id)
}

const editing = ref<SegmentView | null>(null)
const draft = ref('')
const reembedDraft = ref(true)
const editSaving = ref(false)

/** 父块没向量是设计不是故障：勾上重算只会多打一次 embedding，后端也一样会短路回来，这里直接不让勾。 */
const isParentBlock = computed(() => editing.value?.embStatus === 'SKIPPED')

function openEditor(segment: SegmentView) {
  editing.value = segment
  draft.value = segment.content
  reembedDraft.value = segment.embStatus !== 'SKIPPED'
}

/** 整行换掉而不是只改 content：后端顺手把 emb_status / edited_at 的新状态带回来，界面跟着它走。 */
function replaceSegment(segment: SegmentView) {
  const index = segments.value.findIndex((row) => row.id === segment.id)
  if (index >= 0) segments.value[index] = segment
}

/** 勾了「同时重算向量」却没算成才算事故；父块和不重算那两种 note 是按设计给出的说明。 */
function reportEdit(result: SegmentEditResult, askedEmbed: boolean, okMessage: string) {
  if (result.note === null) {
    ElMessage.success(okMessage)
    return
  }
  ElMessage({ message: result.note, type: askedEmbed ? 'warning' : 'success', duration: 6000 })
}

async function saveEdit() {
  if (!doc.value || !editing.value) return
  editSaving.value = true
  try {
    const result = await documentsApi.editSegment(doc.value.id, editing.value.id, draft.value, reembedDraft.value)
    replaceSegment(result.segment)
    editing.value = null
    reportEdit(result, reembedDraft.value, '正文已保存，向量已同步重算')
  } catch {
    // toast 已由 http 层弹过；这里不关弹框，让用户拿改过的草稿接着编辑
  } finally {
    editSaving.value = false
  }
}

async function reembedOne(segment: SegmentView) {
  if (!doc.value) return
  try {
    const result = await documentsApi.reembedSegment(doc.value.id, segment.id)
    replaceSegment(result.segment)
    reportEdit(result, true, '向量已重算，这一条重新进入语义召回')
  } catch {
    // 同上：409/503 都已经弹过 toast，界面保持原样就是最准确的反馈
  }
}

/** 分块预览不写库、不调模型：试参数唯一花钱的地方是 embedding，先看清再重算。 */
async function runPreview() {
  if (!doc.value) return
  preview.value = await documentsApi.preview(doc.value.id, params.value, 40)
}

const NO_OVERRIDE: ChunkOverride = {
  childChars: null,
  childMinChars: null,
  overlapChars: null,
  parentMaxChars: null,
  parentChild: null,
}

/**
 * PUT 是整份替换，五个键必须全给；等于全局的键由后端摘掉，界面不做这层判断。
 * 存完按返回的 effective 回填表单：清空覆盖时正是这一步让界面立刻跟回全局值。
 */
async function saveOverride(override: ChunkOverride) {
  if (!doc.value) return
  saving.value = true
  try {
    chunk.value = await documentsApi.setChunkConfig(doc.value.id, override)
    params.value = { ...chunk.value.effective }
    ElMessage.success(hasOverride.value ? '覆盖已保存，重新入库后生效' : '已恢复跟随全局默认，重新入库后生效')
  } finally {
    saving.value = false
  }
}

function saveFormAsOverride() {
  saveOverride({
    childChars: params.value.childChars ?? null,
    childMinChars: params.value.childMinChars ?? null,
    overlapChars: params.value.overlapChars ?? null,
    parentMaxChars: params.value.parentMaxChars ?? null,
    parentChild: params.value.parentChild ?? null,
  })
}

const pct = (progress: number) => `${progress}%`
</script>

<template>
  <div v-loading="busy" ref="root">
    <template v-if="doc">
      <dl class="facts">
        <div class="kv"><dt>文件</dt><dd>{{ doc.name }}</dd></div>
        <div class="kv"><dt>状态</dt><dd>{{ doc.status }} {{ pct(doc.progress) }}</dd></div>
        <div class="kv" v-if="doc.progressMsg"><dt>进度</dt><dd>{{ doc.progressMsg }}</dd></div>
        <div class="kv"><dt>页数 / 字数</dt><dd>{{ doc.pageCount ?? '—' }} / {{ doc.charCount ?? '—' }}</dd></div>
        <div class="kv"><dt>代次</dt><dd>version {{ doc.version }}</dd></div>
        <div class="kv"><dt>类型 / 大小</dt><dd>{{ doc.mime ?? '—' }} / {{ (doc.fileSize ?? 0) + ' B' }}</dd></div>
        <div class="kv"><dt>sha256</dt><dd class="mono">{{ doc.sha256.slice(0, 12) }}…</dd></div>
      </dl>

      <p v-if="doc.errorCode" class="tag down">{{ doc.errorCode }}：{{ doc.errorMsg }}</p>

      <el-tabs v-model="tab">
        <el-tab-pane label="分块" name="segments">
          <p class="muted hint">
            关闭「检索」即从检索谓词里排除该块，立刻生效且完全可逆；父块没有向量（SKIPPED），只作为上下文被展开。
            改正文只动这一块，不必整篇重算。
          </p>
          <el-table :data="segments" size="small" max-height="420" row-key="id" :row-class-name="focusRow">
            <el-table-column label="#" width="94">
              <template #default="{ row }">
                <span class="mono">{{ row.chunkNo }}</span>
                <span
                  v-if="row.edited"
                  class="tag edited"
                  title="正文是人工改过的：字符偏移记的还是编辑前的位置，整篇重算会被冲掉"
                  >已编辑</span
                >
              </template>
            </el-table-column>
            <el-table-column label="父块" width="60">
              <template #default="{ row }">{{ row.parentChunkNo ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="内容" min-width="240">
              <template #default="{ row }">
                <span class="clamp mono">{{ row.content }}</span>
              </template>
            </el-table-column>
            <el-table-column label="页" width="70">
              <template #default="{ row }">
                <span class="mono">{{ row.pageStart ?? '—' }}-{{ row.pageEnd ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="命中" width="64">
              <template #default="{ row }">
                <span class="mono">{{ row.hitCount }}</span>
              </template>
            </el-table-column>
            <el-table-column label="向量" width="72">
              <template #default="{ row }">
                <span class="tag" :class="row.embStatus === 'READY' ? 'ok' : ''">{{ row.embStatus }}</span>
              </template>
            </el-table-column>
            <el-table-column label="检索" width="70">
              <template #default="{ row }">
                <el-switch :model-value="row.available" size="small" @change="toggle(row)" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="132">
              <template #default="{ row }">
                <el-button size="small" text @click="openEditor(row)">编辑</el-button>
                <el-button
                  v-if="row.embStatus === 'PENDING'"
                  size="small"
                  text
                  type="primary"
                  title="这块的向量还是空的，只有关键词能命中它"
                  @click="reembedOne(row)"
                  >重算向量</el-button
                >
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="正文" name="content">
          <el-button size="small" @click="loadContent">读取清洗后的正文</el-button>
          <pre v-if="content" class="body-text">{{ content }}</pre>
        </el-tab-pane>

        <el-tab-pane label="分块预览" name="preview">
          <p class="row chunk-source">
            <span class="tag" :class="hasOverride ? 'ok' : ''">{{
              hasOverride ? '这篇文档有自己的覆盖' : '跟随全局默认'
            }}</span>
            <span class="muted">重切只是预览；改完参数点「存为本文档覆盖」，再回列表点「重算」才会重新分块入库。</span>
          </p>
          <div class="row tuning">
            <label title="超过 400 字不会报错，bge 的 512 token 窗口会把多出来的部分静默截断，表现为文档里有但搜不到">
              子块字数
            </label>
            <el-input-number v-model="params.childChars" :min="50" :max="4000" size="small" />
            <label>子块下限</label>
            <el-input-number v-model="params.childMinChars" :min="1" :max="4000" size="small" />
            <label>重叠</label>
            <el-input-number v-model="params.overlapChars" :min="0" :max="4000" size="small" />
            <label>父块上限</label>
            <el-input-number v-model="params.parentMaxChars" :min="50" :max="4000" size="small" />
            <el-checkbox v-model="params.parentChild">父子块</el-checkbox>
            <el-button size="small" type="primary" @click="runPreview">重切</el-button>
            <el-button size="small" :loading="saving" @click="saveFormAsOverride">存为本文档覆盖</el-button>
            <el-button v-if="hasOverride" size="small" :loading="saving" @click="saveOverride(NO_OVERRIDE)">
              恢复全局默认
            </el-button>
          </div>

          <div v-if="preview" class="row preview-summary">
            <span class="tag">页 {{ preview.blocks.length ? doc.pageCount ?? '—' : '—' }}</span>
            <span class="tag">共 {{ preview.totalBlocks }} 块</span>
            <span class="tag ok">可检索 {{ preview.searchableBlocks }} 块</span>
            <span class="muted">预览只列出前 {{ preview.blocks.length }} 块</span>
          </div>

          <ol v-if="preview" class="blocks">
            <li v-for="block in preview.blocks" :key="block.chunkNo" :class="{ parent: !block.searchable }">
              <div class="row">
                <span class="mono">#{{ block.chunkNo }}</span>
                <span v-if="block.parentChunkNo" class="tag">父 {{ block.parentChunkNo }}</span>
                <span v-if="!block.searchable" class="tag" title="父块只当上下文，不生成向量">不检索</span>
                <span class="muted mono">{{ block.charStart }}-{{ block.charEnd }}</span>
              </div>
              <p class="clamp-3">{{ block.content }}</p>
            </li>
          </ol>
        </el-tab-pane>
      </el-tabs>

      <el-dialog
        :model-value="editing !== null"
        top="5vh"
        width="min(680px, calc(100vw - 32px))"
        :title="editing ? `编辑块 #${editing.chunkNo}` : ''"
        @close="editing = null"
      >
        <template v-if="editing">
          <p class="row editor-meta">
            <span class="tag">向量 {{ editing.embStatus }}</span>
            <span class="muted mono">
              页 {{ editing.pageStart ?? '—' }}-{{ editing.pageEnd ?? '—' }} / 字 {{ editing.charStart ?? '—' }}-{{
                editing.charEnd ?? '—'
              }}
            </span>
            <span v-if="editing.parentChunkNo" class="tag">父块 {{ editing.parentChunkNo }}</span>
          </p>
          <el-input v-model="draft" type="textarea" :rows="10" resize="none" spellcheck="false" />
          <p class="muted editor-note">
            子块是命中后引用给你的那一段，父块是命中时一起展开的上下文——改父块不会影响它自己进不进检索，因为它不参与向量召回。
            字符偏移记的是编辑前的位置；这里的内容只活到下一次整篇重算。
          </p>
          <el-checkbox v-model="reembedDraft" :disabled="isParentBlock">
            同时重算向量
            <span v-if="isParentBlock" class="muted">（父块不生成向量）</span>
          </el-checkbox>
        </template>
        <template #footer>
          <span class="muted editor-count">{{ draft.length }} 字</span>
          <el-button size="small" @click="editing = null">取消</el-button>
          <el-button size="small" type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
        </template>
      </el-dialog>
    </template>

    <p v-else class="muted empty">选择左侧文档查看详情。</p>
  </div>
</template>

<style scoped>
.facts {
  margin: 0 0 12px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
  font-size: 13px;
}

.hint {
  margin: 0 0 8px;
  font-size: 12px;
}

.tag.edited {
  margin-left: 4px;
  padding: 0 4px;
  font-size: 11px;
  color: #a1620a;
  background: #fff4e0;
}

.editor-meta {
  margin: 0 0 8px;
  gap: 8px;
  font-size: 12px;
}

.editor-note {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.editor-count {
  float: left;
  font-size: 12px;
}

:deep(.el-table__row.focus) > td.el-table__cell {
  background: #fff4e0;
}

.clamp {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}

.body-text {
  max-height: 420px;
  overflow: auto;
  margin: 8px 0 0;
  padding: 10px;
  background: #f7f8fa;
  border-radius: 8px;
  font-size: 12px;
  white-space: pre-wrap;
}

.chunk-source {
  margin: 0 0 6px;
  gap: 8px;
  font-size: 12px;
}

.tuning {
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 12px;
}

.preview-summary {
  gap: 6px;
  margin-bottom: 8px;
  font-size: 12px;
}

.blocks {
  margin: 0;
  padding: 0 0 0 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.blocks .parent {
  opacity: 0.7;
}

.clamp-3 {
  margin: 2px 0 0;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 12px;
}

.empty {
  padding: 20px;
}
</style>
