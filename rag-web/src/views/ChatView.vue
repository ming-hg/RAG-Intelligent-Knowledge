<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownView from '@/components/MarkdownView.vue'
import RetrievalMetaBar from '@/components/RetrievalMetaBar.vue'
import ScopePicker from '@/components/ScopePicker.vue'
import SourcePanel from '@/components/SourcePanel.vue'
import { useChatStore } from '@/stores/chat'
import { useUiStore } from '@/stores/ui'

const chat = useChatStore()
const ui = useUiStore()
const router = useRouter()

const draft = ref('')
const scroller = ref<HTMLElement | null>(null)

onMounted(async () => {
  await chat.loadSessions()
  if (chat.activeId && chat.messages.length === 0) await chat.loadMessages(chat.activeId)
})

// 新内容出现在底部才自动滚动；用户往上翻阅历史时不应被强行拽回
async function scrollToEnd(force = false) {
  await nextTick()
  const node = scroller.value
  if (!node) return
  const nearBottom = node.scrollHeight - node.scrollTop - node.clientHeight < 160
  if (force || nearBottom) node.scrollTop = node.scrollHeight
}

watch(
  () => [chat.live?.text, chat.messages.length],
  () => void scrollToEnd(),
)

async function send() {
  const question = draft.value.trim()
  if (!question) return
  draft.value = ''
  await scrollToEnd(true)
  void chat.ask(question)
}

function stop() {
  chat.stop()
  ElMessage.info('已停止接收，后端仍会把这条回答生成完并落库')
}

async function createSession() {
  await chat.newSession()
}

async function removeSession(id: string) {
  await ElMessageBox.confirm('删除后该会话的消息不再出现在列表里（软删除，数据仍在库中）。', '删除会话', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消',
  })
  await chat.removeSession(id)
}

async function renameSession(id: string, current: string) {
  const result = await ElMessageBox.prompt('会话标题', '重命名', { inputValue: current, confirmButtonText: '保存' })
  const title = String(result.value ?? '').trim()
  if (title) await chat.rename(id, title)
}

function inspect(payload: { docId: number; segmentId: number }) {
  void router.push({ name: 'documents', query: { doc: String(payload.docId), segment: String(payload.segmentId) } })
}

const statusText = (status: string | null): string | null => {
  switch (status) {
    case 'REFUSED':
      return '空召回，已拒答（未调用模型）'
    case 'INTERRUPTED':
      return '生成中断，保留已产出的部分'
    case 'FAILED':
      return '生成失败'
    default:
      return null
  }
}
</script>

<template>
  <div class="split">
    <aside class="sidebar">
      <div class="row side-head">
        <span class="section-title">会话</span>
        <el-button size="small" type="primary" plain @click="createSession">新建</el-button>
      </div>

      <div class="scroll-y session-list">
        <div
          v-for="session in chat.sessions"
          :key="session.id"
          class="session"
          :class="{ active: session.id === chat.activeId }"
          @click="chat.openSession(session.id)"
        >
          <div class="row">
            <span class="title">{{ session.title }}</span>
          </div>
          <div class="row meta">
            <span class="muted mono">{{ session.kbIds.join(', ') || '默认库' }}</span>
            <span class="spacer" />
            <button class="icon" title="置顶" :class="{ on: session.pinned }" @click.stop="chat.togglePin(session.id)">
              ★
            </button>
            <button class="icon" title="重命名" @click.stop="renameSession(session.id, session.title)">✎</button>
            <button class="icon" title="删除" @click.stop="removeSession(session.id)">✕</button>
          </div>
        </div>
        <p v-if="!chat.loadingSessions && chat.sessions.length === 0" class="muted empty">
          还没有会话，直接提问即可新建。
        </p>
      </div>
    </aside>

    <section class="content">
      <div class="toolbar">
        <ScopePicker />
      </div>

      <div ref="scroller" class="scroll-y thread">
        <p v-if="!chat.activeId && !chat.live" class="muted empty">
          选择左侧会话，或直接输入问题开始新一轮问答。
        </p>

        <article v-for="message in chat.messages" :key="message.id" class="message" :class="message.role">
          <header class="row who">
            <span>{{ message.role === 'user' ? '我' : '模型' }}</span>
            <span v-if="message.model" class="tag">{{ message.model }}</span>
            <span v-if="statusText(message.status)" class="tag warn">{{ statusText(message.status) }}</span>
            <span v-if="message.promptTokens || message.completionTokens" class="tag">
              {{ message.promptTokens ?? 0 }}/{{ message.completionTokens ?? 0 }} tok
            </span>
          </header>

          <div v-if="message.role === 'user'" class="body">{{ message.content }}</div>
          <template v-else>
            <RetrievalMetaBar :meta="message.retrievalMeta" />
            <MarkdownView :text="message.content" @select="chat.selectedCitation = $event" />
          </template>
        </article>

        <article v-if="chat.live" class="message assistant live">
          <header class="row who">
            <span>模型</span>
            <span v-if="chat.live.running" class="tag">生成中</span>
            <span v-else-if="chat.live.aborted" class="tag warn">已中断</span>
            <span v-else-if="chat.live.error" class="tag down">失败</span>
          </header>

          <div class="body question muted">{{ chat.live.question }}</div>

          <RetrievalMetaBar :meta="chat.live.meta" />

          <p v-if="chat.live.error" :class="chat.live.aborted ? 'tag warn' : 'tag down'">
            {{ chat.live.error }}
          </p>
          <p v-else-if="!chat.live.text && chat.live.running" class="muted">检索中，尚未开始生成…</p>

          <MarkdownView :text="chat.live.text" :streaming="chat.live.running" @select="chat.selectedCitation = $event" />
        </article>
      </div>

      <footer class="composer">
        <el-input
          v-model="draft"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 8 }"
          placeholder="提问（Ctrl+Enter 发送）。答案只依据所选知识库，资料不足时会直接拒答。"
          @keydown.ctrl.enter.prevent="send"
          @keydown.meta.enter.prevent="send"
        />
        <div class="row composer-actions">
          <span class="muted">{{ ui.kbIds.length }} 个库 · {{ ui.mode }} · topK {{ ui.topK }}</span>
          <span class="spacer" />
          <el-button v-if="chat.streaming" @click="stop">停止接收</el-button>
          <el-button type="primary" :loading="chat.streaming" :disabled="!draft.trim()" @click="send">发送</el-button>
        </div>
      </footer>
    </section>

    <aside class="inspector">
      <div class="section-title">引用来源</div>
      <SourcePanel :sources="chat.activeSources" :selected="chat.selectedCitation" @inspect="inspect" />
    </aside>
  </div>
</template>

<style scoped>
.side-head {
  justify-content: space-between;
  padding: 0 12px 0 0;
}

.session-list {
  flex: 1;
  padding: 0 8px 12px;
}

.session {
  padding: 8px;
  border-radius: 8px;
  cursor: pointer;
}

.session:hover {
  background: #f2f4f7;
}

.session.active {
  background: #eaf1ff;
}

.session .title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session .meta {
  font-size: 12px;
  color: var(--muted);
}

.icon {
  padding: 0 2px;
  border: 0;
  background: none;
  color: var(--muted);
  cursor: pointer;
}

.icon:hover {
  color: var(--accent);
}

.icon.on {
  color: var(--warn);
}

.spacer {
  flex: 1;
}

.toolbar {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.thread {
  flex: 1;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.message {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.message.user .body {
  padding: 8px 12px;
  border-radius: 10px;
  background: #eaf1ff;
  white-space: pre-wrap;
}

.message.assistant .body {
  padding: 2px 0;
}

.message.live .question {
  font-size: 12px;
}

.who {
  gap: 6px;
  color: var(--muted);
  font-size: 12px;
}

.composer {
  padding: 10px 14px;
  border-top: 1px solid var(--border);
  background: var(--panel);
}

.composer-actions {
  margin-top: 8px;
  font-size: 12px;
}

.empty {
  padding: 20px;
  text-align: center;
}
</style>
