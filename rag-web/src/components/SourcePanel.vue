<script setup lang="ts">
import type { Source } from '@/types/api'

const props = defineProps<{ sources: Source[]; selected?: number | null }>()
const emit = defineEmits<{ inspect: [payload: { docId: number; segmentId: number }] }>()

const rank = (value: number | null): string => (value === null || value === undefined ? '—' : `#${value}`)
const score = (value: number | null | undefined, digits: number): string =>
  value === null || value === undefined ? '—' : value.toFixed(digits)
</script>

<template>
  <div class="sources">
    <p v-if="props.sources.length === 0" class="muted empty">
      本次回答没有来源（空召回会被直接拒绝，不会让模型凭想象作答）。
    </p>

    <article
      v-for="source in props.sources"
      :key="source.segmentId"
      class="source"
      :class="{ active: props.selected === source.citation }"
    >
      <header class="row">
        <span class="cite-number">[{{ source.citation }}]</span>
        <button class="doc" type="button" @click="emit('inspect', { docId: source.docId, segmentId: source.segmentId })">
          {{ source.docName }} · 块 {{ source.chunkNo }}
        </button>
        <span v-if="source.parentExpanded" class="tag" title="子块命中，父块正文进入上下文">父块</span>
        <span class="tag">{{ source.chars }} 字</span>
      </header>

      <p class="location muted">{{ source.location }}</p>

      <dl class="scores">
        <div class="kv">
          <dt>向量名次</dt>
          <dd>{{ rank(source.vectorRank) }}</dd>
        </div>
        <div class="kv">
          <dt>关键词名次</dt>
          <dd>{{ rank(source.keywordRank) }}</dd>
        </div>
        <div class="kv" title="RRF 融合分，量级 0.01x，与余弦分不可直接比较">
          <dt>RRF</dt>
          <dd>{{ score(source.rrf, 5) }}</dd>
        </div>
        <div class="kv">
          <dt>余弦相似</dt>
          <dd>{{ score(source.similarity, 3) }}</dd>
        </div>
      </dl>
    </article>

    <p v-if="props.sources.length" class="muted note">
      引用编号在生成时分配并随消息落库，回看历史时不会重新编号。
    </p>
  </div>
</template>

<style scoped>
.sources {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 12px 16px;
}

.empty {
  padding: 8px 0;
  font-size: 12px;
}

.source {
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fbfcfd;
}

.source.active {
  border-color: var(--accent);
  background: #f2f7ff;
}

.cite-number {
  font-family: var(--mono);
  color: var(--accent);
  font-weight: 600;
}

.doc {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: 0;
  background: none;
  color: var(--text);
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc:hover {
  color: var(--accent);
  text-decoration: underline;
}

.location {
  margin: 4px 0 6px;
  font-size: 12px;
}

.scores {
  margin: 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 12px;
  font-size: 12px;
}

.note {
  margin: 4px 0 0;
  font-size: 12px;
}
</style>
