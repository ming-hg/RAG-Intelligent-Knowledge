<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useChatStore } from '@/stores/chat'
import type { ChatMessage } from '@/types/api'

const props = defineProps<{ message: ChatMessage }>()
const chat = useChatStore()

const editing = ref(false)
const saving = ref(false)
const form = reactive({ feedbackText: '', annotatedAnswer: '' })

/** 拒答行没有模型产出可改，评分照旧（拒答对不对也是要统计的）。 */
const canAnnotate = computed(() => props.message.status !== 'REFUSED')

/** 同一颗按钮再点一次就是撤销：后端把 0 当成中性，不额外开一个「删除评分」接口。 */
async function rate(value: 1 | -1) {
  try {
    await chat.setFeedback(props.message.id, { rating: props.message.rating === value ? 0 : value })
  } catch {
    // 提示已由 http 层统一弹出
  }
}

function openEdit() {
  form.feedbackText = props.message.feedbackText ?? ''
  form.annotatedAnswer = props.message.annotatedAnswer ?? ''
  editing.value = true
}

async function save() {
  saving.value = true
  try {
    // 两个字段一律按字符串提交：清空 = 空串 = 撤销，这跟「这次不改」（null）在后端是两种语义
    await chat.setFeedback(props.message.id, {
      feedbackText: form.feedbackText.trim(),
      annotatedAnswer: canAnnotate.value ? form.annotatedAnswer.trim() : null,
    })
    editing.value = false
    ElMessage.success('反馈已记录')
  } catch {
    // 保存失败时编辑框保持打开，用户不必重打一遍
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <footer class="row feedback">
    <button
      class="icon rate"
      :class="{ on: message.rating === 1 }"
      type="button"
      title="这条回答有用（再点一次撤销）"
      @click="rate(1)"
    >
      👍
    </button>
    <button
      class="icon rate"
      :class="{ on: message.rating === -1 }"
      type="button"
      title="这条回答有问题（再点一次撤销）"
      @click="rate(-1)"
    >
      👎
    </button>

    <button class="link" type="button" @click="openEdit">
      {{ message.feedbackText || message.annotatedAnswer ? '反馈与订正 · 已记录' : '反馈与订正' }}
    </button>

    <span v-if="message.feedbackText" class="muted quote" :title="message.feedbackText">
      {{ message.feedbackText }}
    </span>
    <span v-if="message.annotatedAnswer" class="tag warn">答案已人工订正</span>

    <el-dialog v-model="editing" title="反馈与人工订正" width="min(680px, 94%)" append-to-body>
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="问题反馈（哪里不对，最长 2000 字；清空即删除）">
          <el-input v-model="form.feedbackText" type="textarea" :rows="3" maxlength="2000" show-word-limit />
        </el-form-item>

        <el-form-item v-if="canAnnotate" label="订正答案（最长 20000 字）">
          <el-input v-model="form.annotatedAnswer" type="textarea" :rows="10" maxlength="20000" />
          <p class="muted hint">
            订正后的文本会成为这个会话后续问答引用的事实，并优先于模型原文显示；清空则回到模型原文。
          </p>
        </el-form-item>

        <p v-else class="muted hint">这是一条拒答记录，没有模型产出可订正；评分仍会计入拒答正确率。</p>
      </el-form>

      <template #footer>
        <el-button @click="editing = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </footer>
</template>

<style scoped>
.feedback {
  gap: 8px;
  margin-top: 2px;
  font-size: 12px;
  color: var(--muted);
}

.rate {
  padding: 0 2px;
  border: 0;
  background: none;
  cursor: pointer;
  opacity: 0.45;
}

.rate:hover {
  opacity: 1;
}

.rate.on {
  opacity: 1;
}

.link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--accent);
  cursor: pointer;
  font-size: 12px;
}

.quote {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
}
</style>
