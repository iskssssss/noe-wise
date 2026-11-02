<script setup lang="ts">
import {
  Check,
  ChevronDown,
  Clock3,
  FileCheck2,
  GitBranch,
  Layers3,
  MessageSquare,
  Quote,
  Search,
  Sparkles,
  Wrench,
  X,
  CircleAlert
} from 'lucide-vue-next'
import { computed, ref, useId } from 'vue'
import type { Process, Reference } from '../protocol'
import { processElapsed } from './conversationTimeline'
import Markdown from './Markdown.vue'
import { ChatTable, ChatTableColumn } from './controls'

const props = defineProps<{
  item: Process
  locked: boolean
  now: number
  live: boolean
  targetName?: string
}>()
const emit = defineEmits<{ reference: [value: Reference] }>()
const detailsOpen = ref(false)
const detailId = `process-detail-${useId()}`
const elapsed = computed(() => processElapsed(props.item, props.now, props.live))
const icons = {
  commentary: MessageSquare,
  reasoning: Sparkles,
  tool: Wrench,
  knowledge: Search,
  memory: Layers3,
  skill: FileCheck2,
  workflow: GitBranch,
  delegation: GitBranch
}
const statuses: Record<string, string> = {
  running: '进行中',
  queued: '排队中',
  waiting: '等待结果',
  waiting_hitl: '等待输入',
  waiting_input: '等待输入',
  suspended: '已挂起',
  succeeded: '已完成',
  completed: '已完成',
  failed: '失败',
  outcome_unknown: '待核实',
  cancelled: '已停止',
  cancelling: '正在停止'
}
const attention = computed(() =>
  ['failed', 'outcome_unknown', 'waiting_hitl', 'waiting_input'].includes(props.item.status)
)
/** 相同公开文本只展示一次；详情入口只承载摘要之外的结果和来源。 */
const detailResults = computed(() =>
  (props.item.presentation.results ?? []).filter(
    (result) =>
      result.kind !== 'text' || result.text.trim() !== props.item.presentation.summary.trim()
  )
)
const hasDetails = computed(() =>
  Boolean(detailResults.value.length || props.item.presentation.references?.length)
)

/** 引用保持原过程身份及更新序号，不用阶段聚合或摘要替换来源。 */
function reference() {
  emit('reference', {
    label: props.item.presentation.title,
    source: {
      kind: 'process',
      source: { kind: 'session' },
      process_id: props.item.process_id,
      updated_sequence: props.item.updated_sequence
    }
  })
}
</script>

<template>
  <li
    class="process-step"
    :class="{ 'is-commentary': item.type === 'commentary', 'needs-attention': attention }"
    :data-process-id="item.process_id"
    :data-process-state="item.status"
  >
    <div class="step-heading">
      <span v-if="item.status === 'running' && live" class="activity-spinner" aria-hidden="true" />
      <CircleAlert
        v-else-if="['failed', 'outcome_unknown'].includes(item.status)"
        class="step-symbol"
        aria-hidden="true"
      />
      <Clock3 v-else-if="attention" class="step-symbol" aria-hidden="true" />
      <X v-else-if="item.status === 'cancelled'" class="step-symbol" aria-hidden="true" />
      <component
        :is="icons[item.type as keyof typeof icons] || Check"
        v-else
        class="step-symbol"
        aria-hidden="true"
      />
      <div class="step-heading-content">
        <div class="step-title">
          <strong>{{ item.presentation.title }}</strong>
          <span class="step-state" v-if="item.type !== 'commentary'">{{
            statuses[item.status] || item.status
          }}</span>
          <span
            v-if="item.actor_name && item.actor_name !== '助手' && item.actor_name !== targetName"
            class="step-actor"
            >{{ item.actor_name }}</span
          >
          <span v-if="elapsed && item.type !== 'commentary'" class="step-elapsed">{{
            elapsed
          }}</span>
        </div>
      </div>
      <div class="step-actions">
        <button
          v-if="hasDetails"
          type="button"
          class="step-detail-toggle"
          :aria-expanded="detailsOpen"
          :aria-controls="detailId"
          :aria-label="`${detailsOpen ? '收起' : '展开'}${item.presentation.title}详情`"
          @click="detailsOpen = !detailsOpen"
        >
          详情<ChevronDown :class="{ 'is-open': detailsOpen }" aria-hidden="true" />
        </button>
        <button
          type="button"
          class="process-reference"
          :disabled="locked"
          :aria-label="`引用${item.presentation.title}过程`"
          title="引用过程"
          @click="reference"
        >
          <Quote aria-hidden="true" />
        </button>
      </div>
    </div>
    <Markdown
      v-if="item.presentation.summary"
      :text="item.presentation.summary"
      class="step-summary"
    />
    <div v-if="hasDetails" :id="detailId" :hidden="!detailsOpen" class="step-details">
      <template v-if="detailsOpen">
        <template v-for="(result, index) in detailResults" :key="index">
          <Markdown
            v-if="result.kind === 'text' && result.format === 'markdown'"
            :text="result.text"
          />
          <pre v-else-if="result.kind === 'text'">{{ result.text }}</pre>
          <ChatTable
            v-else
            :data="
              result.rows.map((row) =>
                Object.fromEntries(result.columns.map((column, index) => [column.key, row[index]]))
              )
            "
          >
            <ChatTableColumn
              v-for="column in result.columns"
              :key="column.key"
              :prop="column.key"
              :label="column.label"
            />
          </ChatTable>
        </template>
        <div
          v-for="(source, index) in item.presentation.references"
          :key="index"
          class="step-source"
        >
          <span>{{ source.label }}</span>
          <button
            type="button"
            class="process-reference"
            :disabled="locked"
            :aria-label="`引用来源：${source.label}`"
            title="引用来源"
            @click="emit('reference', source)"
          >
            <Quote aria-hidden="true" />
          </button>
        </div>
      </template>
    </div>
  </li>
</template>

<style scoped>
.process-step {
  --step-action-size: 28px;
  min-width: 0;
  padding: 3px 0 5px;
  --step-color: var(--muted);
}
.step-heading {
  display: grid;
  grid-template-columns: 14px minmax(0, 1fr) auto;
  align-items: start;
  gap: 6px;
  min-height: 28px;
  font-size: 12px;
  line-height: 1.5;
}
.step-heading-content {
  min-width: 0;
  padding-top: 4px;
}
.step-title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px 8px;
}
.step-heading > .step-symbol,
.step-heading > .activity-spinner {
  margin-top: 7px;
}
.step-state {
  white-space: nowrap;
}
.step-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}
.step-heading strong {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 12px;
  font-weight: 500;
  color: var(--text);
}
.step-symbol {
  width: 14px;
  height: 14px;
  flex: none;
  color: var(--step-color);
  stroke-width: 1.6;
}
.step-state,
.step-actor,
.step-elapsed {
  font-size: 11px;
  color: var(--step-color);
}
.step-actor {
  overflow-wrap: anywhere;
}
.step-elapsed {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.process-step[data-process-state='running'],
.process-step[data-process-state='succeeded'],
.process-step[data-process-state='completed'] {
  --step-color: var(--accent);
}
.process-step[data-process-state='failed'],
.process-step[data-process-state='outcome_unknown'] {
  --step-color: var(--error);
}
.process-step[data-process-state='waiting_hitl'],
.process-step[data-process-state='waiting_input'] {
  --step-color: var(--warning);
}
.process-reference {
  appearance: none;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: none;
  padding: 0;
  background: transparent;
  color: var(--muted);
  border: 0;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition:
    color var(--duration-fast) var(--ease),
    background var(--duration-fast) var(--ease);
}
.process-reference svg {
  width: 14px;
  height: 14px;
}
.process-reference:hover:not(:disabled) {
  color: var(--accent);
  background: var(--notice);
}
.process-reference:disabled {
  cursor: default;
  opacity: 0.5;
}
button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
}
.step-summary {
  margin: 0 0 0 20px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}
.is-commentary .step-heading strong {
  color: var(--muted);
  font-size: 11px;
  font-weight: 400;
}
.process-step[data-process-state='failed'] > .step-summary,
.process-step[data-process-state='outcome_unknown'] > .step-summary {
  color: var(--error);
}
.step-detail-toggle {
  appearance: none;
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: var(--step-action-size);
  margin: 0;
  padding: 4px 5px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 11px;
  cursor: pointer;
}
.step-detail-toggle:hover {
  color: var(--accent);
}
.step-detail-toggle svg {
  width: 13px;
  height: 13px;
  transition: transform var(--duration-fast) var(--ease);
}
.step-detail-toggle svg.is-open {
  transform: rotate(180deg);
}
.step-details {
  min-width: 0;
  margin: 4px 0 0 20px;
  padding: 6px 8px;
  border-left: 1px solid var(--border);
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}
.step-summary :deep(:is(p, ul, ol)),
.step-details :deep(:is(p, ul, ol)) {
  margin: 0 0 6px;
}
.step-summary :deep(li),
.step-details :deep(li) {
  margin-bottom: 3px;
}
.step-summary :deep(:is(h1, h2, h3, h4, h5, h6)),
.step-details :deep(:is(h1, h2, h3, h4, h5, h6)) {
  margin: 6px 0 4px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.6;
}
.step-summary :deep(:is(pre, blockquote, table)),
.step-details :deep(:is(pre, blockquote, table)) {
  margin: 6px 0;
}
.step-summary :deep(.markdown-section:last-child > :last-child),
.step-details :deep(.markdown-section:last-child > :last-child) {
  margin-bottom: 0;
}
.step-details pre {
  margin: 8px 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
}
.step-source {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 8px 0;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.step-source > span {
  min-width: 0;
}
@media (pointer: coarse) {
  .process-step {
    --step-action-size: 44px;
  }
  .process-reference {
    width: 44px;
    height: 44px;
  }
  .step-detail-toggle {
    min-height: 44px;
  }
  .step-heading-content {
    padding-top: 10px;
  }
  .step-heading > .step-symbol,
  .step-heading > .activity-spinner {
    margin-top: 15px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .process-reference,
  .step-detail-toggle svg {
    transition: none;
  }
}
</style>
