<script setup lang="ts">
import { ChevronDown, CircleAlert, Clock3, ListChecks, Route } from 'lucide-vue-next'
import { computed, onBeforeUnmount, reactive, ref, useId, watch } from 'vue'
import type { Reference } from '../protocol'
import ProcessStep from './ProcessStep.vue'
import {
  finalReady,
  modelCallCount,
  needsAttention,
  operationCount,
  processElapsed,
  processIsLive,
  processProgress,
  processStages,
  stageNeedsExpansion,
  visibleProcesses,
  type ConversationTurn,
  type ProcessStageGroup
} from './conversationTimeline'

const props = withDefaults(
  defineProps<{
    turn: ConversationTurn
    overview?: ConversationTurn
    continuation?: boolean
    traceExpanded?: boolean
    notices?: boolean
    runStates: Record<string, string>
    pending: boolean
    connection?: string
    locked: boolean
    targetName?: string
    compact?: boolean
  }>(),
  { traceExpanded: undefined, notices: true }
)
const emit = defineEmits<{ reference: [value: Reference]; trace: [expanded: boolean] }>()
const expanded = ref(!props.compact),
  localTrace = ref(false),
  now = ref(Date.now())
const fullTrace = computed(() => props.traceExpanded ?? localTrace.value)
const overview = computed(() => props.overview ?? props.turn)
const stageExpanded = reactive<Record<string, boolean>>({})
const id = useId()
let manuallyToggled = false,
  autoCollapsed = false
let timer: ReturnType<typeof setInterval> | undefined
const progress = computed(() => processProgress(overview.value, props.runStates, props.pending))
const live = computed(() => processIsLive(props.turn, props.runStates))
const attention = computed(
  () => needsAttention(overview.value) || ['error', 'warning'].includes(progress.value.tone)
)
const counts = computed(() =>
  operationCount(props.continuation ? props.turn.processes : overview.value.processes)
)
const operationLabel = computed(() => {
  if (!processIsLive(overview.value, props.runStates)) return `${counts.value.total} 项执行记录`
  return counts.value.completed === counts.value.total
    ? `${counts.value.total} 项操作`
    : `已完成 ${counts.value.completed}/${counts.value.total} 项操作`
})
const calls = computed(() => modelCallCount(overview.value))
const hasDiagnostics = computed(
  () => visibleProcesses(overview.value).length < overview.value.processes.length
)
const groups = computed(() =>
  processStages(props.turn)
    .map((group) => ({
      ...group,
      visible: visibleProcesses({ ...props.turn, processes: group.processes }, fullTrace.value)
    }))
    .filter((group) => group.visible.length)
)
const toggleLabel = computed(() =>
  [
    props.continuation ? '后续轨迹' : progress.value.label,
    counts.value.total ? operationLabel.value : '',
    !props.continuation && calls.value ? `${calls.value} 次模型调用` : '',
    `${expanded.value && (groups.value.length || !props.turn.processes.length) ? '收起' : '查看'}执行轨迹`
  ]
    .filter(Boolean)
    .join('，')
)
const complete = computed(
  () =>
    overview.value.runIds.length > 0 &&
    overview.value.runIds.every((run) => props.runStates[run] === 'completed') &&
    finalReady(overview.value) &&
    !attention.value
)
const elapsed = computed(() => processElapsed(progress.value.active, now.value))
const connectionNotice = computed(() =>
  live.value && props.connection && props.connection !== '已连接' ? props.connection : ''
)
const attentionIssue = computed(
  () =>
    props.turn.processes.find((process) => process.status === 'outcome_unknown') ||
    props.turn.processes.find((process) => process.status === 'failed')
)
/** 可恢复动作失败只留在轨迹；真正运行失败/停止或未知结果才提升到折叠区域之外。 */
const showAttentionSummary = computed(() =>
  Boolean(
    attentionIssue.value &&
    !expanded.value &&
    (attentionIssue.value.status === 'outcome_unknown' ||
      progress.value.tone === 'error' ||
      overview.value.runIds.some((run) =>
        ['failed', 'cancelled'].includes(props.runStates[run] ?? '')
      ))
  )
)
const attentionText = computed(
  () => attentionIssue.value?.presentation.summary || attentionIssue.value?.presentation.title
)
watch(
  () => props.traceExpanded,
  (value) => {
    if (value) {
      expanded.value = true
      manuallyToggled = true
    }
  },
  { immediate: true }
)

/** 只在明确完成且最终正文就绪后收起一次，用户选择始终优先。 */
watch(
  complete,
  (done) => {
    if (done && !manuallyToggled && !autoCollapsed) {
      expanded.value = false
      autoCollapsed = true
    }
  },
  { immediate: true }
)

/** 计时只依赖真实开始时间；已收口或卸载的过程不保留定时器。 */
watch(
  () =>
    live.value &&
    (props.continuation ? props.turn.processes : overview.value.processes).some(
      (process) =>
        ['running', 'waiting'].includes(process.status) &&
        !process.finished_at &&
        processElapsed(process, Date.now())
    ),
  (enabled) => {
    clearInterval(timer)
    timer = undefined
    now.value = Date.now()
    if (enabled)
      timer = setInterval(() => {
        now.value = Date.now()
      }, 1000)
  },
  { immediate: true }
)
onBeforeUnmount(() => clearInterval(timer))

/** 展开状态属于本轮显示偏好，不写回 Runtime 或改变过程状态。 */
function toggle() {
  // 精简视图可能只剩模型计数；点击状态仍需展示真实记录，不能展开空区域。
  if (!groups.value.length && props.turn.processes.length && !fullTrace.value) {
    toggleTrace()
    return
  }
  expanded.value = !expanded.value
  manuallyToggled = true
}
/** 完整轨迹是同轮展示偏好，跨公开说明分段时仍共享同一个入口。 */
function toggleTrace() {
  const value = !fullTrace.value
  localTrace.value = value
  expanded.value = true
  manuallyToggled = true
  emit('trace', value)
}
/** 阶段偏好优先于完整轨迹的默认展开策略。 */
function stageHasHeader(group: ProcessStageGroup) {
  return (
    group.index !== undefined &&
    (fullTrace.value || operationCount(group.processes).total > 0 || stageNeedsExpansion(group))
  )
}
function stageOpen(group: ProcessStageGroup) {
  return (
    !stageHasHeader(group) ||
    (stageExpanded[group.key] ?? (fullTrace.value || stageNeedsExpansion(group)))
  )
}
/** 默认用真实操作命名；完整轨迹明确展示 Runtime 执行轮次，不称作业务阶段。 */
function stageTitle(group: ProcessStageGroup) {
  if (fullTrace.value) return `第 ${group.index} 轮执行`
  const operations = group.processes.filter((process) => operationCount([process]).total)
  const title = (operations[0] ?? group.processes[0])?.presentation.title || '执行操作'
  return operations.length > 1 ? `${title}等 ${operations.length} 项操作` : title
}
/** 收起时只展示真实操作或异常的公开结果，不再重复工作摘要。 */
function stageSummary(group: ProcessStageGroup) {
  const issue = group.processes.find((process) =>
    ['failed', 'outcome_unknown'].includes(process.status)
  )
  return (
    issue?.presentation.summary ||
    [...group.processes].reverse().find((process) => operationCount([process]).total)?.presentation
      .summary ||
    ''
  )
}
/** 仅汇总明确步骤状态，未识别状态不能推断为成功。 */
function stageState(group: ProcessStageGroup) {
  const states = group.processes.map((process) => process.status)
  if (states.includes('outcome_unknown')) return '结果待核实'
  if (states.includes('failed')) return '有步骤失败'
  if (states.some((state) => ['waiting_hitl', 'waiting_input'].includes(state)))
    return '等待你的输入'
  if (states.includes('cancelling')) return '正在停止'
  if (
    states.some((state) =>
      ['waiting', 'waiting_hitl', 'waiting_input', 'suspended'].includes(state)
    )
  )
    return '等待中'
  if (states.includes('running')) return '进行中'
  if (states.includes('cancelled')) return '有步骤已停止'
  if (states.includes('queued')) return '排队中'
  return states.every((state) => ['succeeded', 'completed'].includes(state))
    ? '已完成'
    : '状态待同步'
}
</script>

<template>
  <section
    class="process-group"
    :class="{
      'needs-attention': attention,
      'has-error': progress.tone === 'error',
      'has-warning': progress.tone === 'warning'
    }"
  >
    <div class="process-toolbar" :class="{ 'is-continuation': continuation }">
      <button
        type="button"
        class="process-toggle"
        :aria-label="toggleLabel"
        :aria-expanded="expanded"
        :aria-controls="`${id}-content`"
        @click="toggle"
      >
        <ChevronDown class="process-chevron" :class="{ 'is-open': expanded }" aria-hidden="true" />
        <span class="process-heading">
          <span
            v-if="!continuation && progress.tone === 'active' && !connectionNotice"
            class="activity-spinner"
            aria-hidden="true"
          />
          <CircleAlert
            v-else-if="!continuation && progress.tone === 'error'"
            class="process-symbol"
            aria-hidden="true"
          />
          <Clock3
            v-else-if="!continuation && progress.tone === 'warning'"
            class="process-symbol"
            aria-hidden="true"
          />
          <ListChecks v-else-if="!continuation" class="process-symbol" aria-hidden="true" />
          <span class="process-progress-label" :role="continuation ? undefined : 'status'">{{
            continuation ? '后续轨迹' : progress.label
          }}</span>
          <small v-if="elapsed && !continuation" class="process-elapsed"
            >本步骤 {{ elapsed }}</small
          >
          <small v-if="counts.total" class="process-operation-count">{{ operationLabel }}</small>
        </span>
      </button>
      <div v-if="(calls || hasDiagnostics) && !continuation" class="process-view-controls">
        <button
          type="button"
          :aria-pressed="fullTrace"
          data-process-trace-toggle
          @click="toggleTrace"
        >
          <Route aria-hidden="true" />{{ fullTrace ? '收起完整轨迹' : '完整轨迹'
          }}<small v-if="calls">{{ calls }} 次模型调用</small>
        </button>
      </div>
    </div>
    <div :id="`${id}-content`" :hidden="!expanded" class="process-content">
      <template v-if="expanded">
        <ol v-if="groups.length" class="process-stages">
          <li
            v-for="group in groups"
            :key="group.key"
            class="process-stage"
            :class="{ 'is-standalone': !stageHasHeader(group) }"
            :data-stage-key="group.key"
          >
            <template v-if="stageHasHeader(group)">
              <button
                type="button"
                class="stage-toggle"
                :aria-expanded="stageOpen(group)"
                :aria-controls="`${id}-${group.key}`"
                @click="stageExpanded[group.key] = !stageOpen(group)"
              >
                <ChevronDown :class="{ 'is-open': stageOpen(group) }" aria-hidden="true" /><span>{{
                  stageTitle(group)
                }}</span
                ><small>{{ stageState(group) }}</small
                ><small v-if="operationCount(group.processes).total"
                  >{{ operationCount(group.processes).total }} 项操作</small
                >
              </button>
              <p v-if="!stageOpen(group) && stageSummary(group)" class="stage-excerpt">
                {{ stageSummary(group) }}
              </p>
            </template>
            <div :id="`${id}-${group.key}`" :hidden="!stageOpen(group)">
              <ol v-if="stageOpen(group)" class="process-events">
                <ProcessStep
                  v-for="item in group.visible"
                  :key="item.process_id"
                  :item="item"
                  :locked="locked"
                  :now="now"
                  :live="live"
                  :target-name="targetName"
                  @reference="emit('reference', $event)"
                />
              </ol>
            </div>
          </li>
        </ol>
        <p v-if="!turn.processes.length" class="process-empty-state" role="status">
          暂无可展示的执行步骤。
        </p>
      </template>
    </div>
    <p v-if="showAttentionSummary" class="attention-summary issue-summary" role="status">
      {{ attentionText }}
    </p>
    <p v-if="pending && notices" class="attention-summary pending-summary" role="status">
      等待你的输入，确认或补充后继续。
    </p>
    <p v-if="connectionNotice && notices" class="process-connection-notice" role="status">
      {{ connectionNotice }}，执行状态等待同步。
    </p>
  </section>
</template>

<style scoped>
.process-group {
  min-width: 0;
  margin: 0 0 12px;
  padding: 0 0 0 12px;
  border-left: 1px solid var(--border);
}
.process-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0 12px;
  min-width: 0;
}
.process-toggle {
  appearance: none;
  display: flex;
  align-items: center;
  flex: 1 1 auto;
  justify-content: flex-start;
  min-width: 0;
  min-height: 32px;
  gap: 6px;
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.process-toggle:hover {
  color: var(--text);
}
.process-chevron {
  width: 13px;
  height: 13px;
  flex: none;
  transform: rotate(-90deg);
  transition: transform var(--duration-fast) var(--ease);
}
.process-chevron.is-open {
  transform: rotate(0);
}
.process-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  min-width: 0;
  gap: 4px 8px;
  line-height: 1.5;
  text-align: left;
}
.process-progress-label {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--text);
  font-weight: 500;
}
.process-symbol {
  width: 14px;
  height: 14px;
  flex: none;
}
.process-heading small {
  font-size: 11px;
  font-weight: 400;
}
.process-elapsed {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.has-warning .process-symbol {
  color: var(--warning);
}
.process-operation-count {
  color: var(--muted);
}
.is-continuation .process-progress-label {
  color: var(--muted);
  font-weight: 400;
}
.process-view-controls {
  flex: none;
  max-width: 100%;
}
.process-view-controls button {
  appearance: none;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-height: 32px;
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 11px;
  cursor: pointer;
}
.process-view-controls button:hover {
  color: var(--accent);
}
.process-view-controls button svg {
  width: 14px;
  height: 14px;
}
.process-view-controls small {
  font-size: inherit;
}
.process-stages,
.process-events {
  list-style: none;
}
.process-stages {
  margin: 2px 0 0 19px;
  padding: 0;
}
.process-stage {
  min-width: 0;
  margin: 0;
}
.process-stage + .process-stage {
  margin-top: 4px;
}
.stage-toggle {
  appearance: none;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 8px;
  min-height: 28px;
  width: 100%;
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.stage-toggle small {
  color: var(--muted);
  font-size: 11px;
}
.stage-toggle svg {
  width: 14px;
  height: 14px;
  flex: none;
  transform: rotate(-90deg);
  transition: transform var(--duration-fast) var(--ease);
}
.stage-toggle svg.is-open {
  transform: rotate(0);
}
.stage-toggle:hover {
  color: var(--accent);
}
button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
}
.stage-excerpt {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  margin: 0 0 6px 22px;
  overflow-wrap: anywhere;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}
.process-events {
  margin: 0;
  padding: 0;
  border: 0;
}
.attention-summary,
.process-empty-state,
.process-connection-notice {
  margin: 4px 0 6px 19px;
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.process-connection-notice,
.process-empty-state {
  color: var(--muted);
}
.attention-summary {
  color: var(--warning);
}
.has-error .process-symbol,
.issue-summary {
  color: var(--error);
}
@media (pointer: coarse) {
  .stage-toggle,
  .process-view-controls button,
  .process-toggle {
    min-height: 44px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .stage-toggle svg,
  .process-chevron {
    transition: none;
  }
}
</style>
