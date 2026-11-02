<script setup lang="ts">
import FeedbackNotice from '../feedback/FeedbackNotice.vue'
import { computed, nextTick, ref, watch } from 'vue'
import {
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDrawer,
  ElEmpty,
  ElInput,
  ElSkeleton,
  ElTag,
  ElTree,
  type TreeInstance
} from 'element-plus'
import { guardFormEnter } from '../admin/keyboard'
import { statusLabel } from '../admin/presentation'
import { formatDateTime } from '../utils/datetime'
import JsonViewer from '../components/JsonViewer.vue'
import BudgetTerminationNotice from './BudgetTerminationNotice.vue'
import {
  canReadObservationContent,
  formatObservationDuration,
  formatObservationCosts,
  formatObservationTokens,
  observationAvailability,
  observationCoverage,
  observationCostCoverage,
  observationTokenTotal,
  observationTree,
  type ObservationTreeItem
} from './presentation'
import type { ObservationNode, ObservationPage, TrajectoryContent, TrajectoryDetail } from './types'

const props = withDefaults(
  defineProps<{
    detail?: TrajectoryDetail
    page?: ObservationPage
    appliedQuery?: string
    busy?: boolean
    error?: string
    scopeKey: string
    focusNode?: string
    content?: TrajectoryContent
    contentBusy?: boolean
    contentError?: string
    allowAttemptDiagnostics?: boolean
  }>(),
  {
    busy: false,
    appliedQuery: '',
    error: '',
    focusNode: '',
    contentBusy: false,
    contentError: '',
    allowAttemptDiagnostics: false
  }
)
const emit = defineEmits<{
  search: [query: string]
  more: []
  focus: [node: ObservationNode]
  content: [ref: string]
  contentClose: []
  retry: []
  attempt: [attemptId: string, sourceSessionId: string, rootInputId: string]
}>()

const tree = ref<TreeInstance>()
const MAX_SEARCH_CODE_POINTS = 256
const searchInput = ref('')
const submittedSearch = ref('')
const appliedSearch = computed(() => props.appliedQuery)
const searchError = ref('')
const searchState = ref<'idle' | 'pending' | 'failed'>('idle')
let beforeSearch: { expanded: string[]; collapsed: string[]; selected: string } | undefined
const selectedId = ref('')
const pendingFocus = ref('')
const requestedRef = ref('')
const jsonOpen = ref(false)
const expandedIds = ref<string[]>([])
const visitedIds = new Set<string>()
const manuallyCollapsed = new Set<string>()
const treeNodes = computed(() => observationTree(props.page?.items ?? [], props.page?.ancestors))
const nodeIndex = computed(() => {
  const index = new Map<string, ObservationNode>()
  for (const node of [...(props.page?.ancestors ?? []), ...(props.page?.items ?? [])])
    index.set(node.node_id, node)
  return index
})
const selected = computed(() => nodeIndex.value.get(selectedId.value))
const showAttemptContent = computed(() => {
  const current = selected.value
  return Boolean(
    current?.attempts.length && (current.kind === 'LLM' || current.attempts.length > 1)
  )
})
const summary = computed(() => props.detail?.summary)
const loadedContent = computed(() =>
  requestedRef.value && props.content?.content_ref === requestedRef.value
    ? props.content
    : undefined
)
const contentOpen = computed({
  get: () => Boolean(requestedRef.value),
  set: (open: boolean) => {
    if (!open) requestedRef.value = ''
  }
})
const readerAttemptIndex = computed(
  () =>
    selected.value?.attempts.findIndex(
      (attempt) =>
        attempt.input.ref === requestedRef.value || attempt.output.ref === requestedRef.value
    ) ?? -1
)
const readerBinding = computed(() =>
  readerAttemptIndex.value >= 0
    ? selected.value?.attempts[readerAttemptIndex.value]
    : selected.value
)
const readerSide = computed(() =>
  requestedRef.value === readerBinding.value?.input.ref ? 'input' : 'output'
)
const readerLabels = computed(() =>
  selected.value?.kind === 'LLM'
    ? { input: '原始请求', output: '原始返回' }
    : { input: '原始输入', output: '原始输出' }
)
function adjacentReaderAttempt(direction: 1 | -1) {
  const attempts = selected.value?.attempts ?? []
  for (
    let index = readerAttemptIndex.value + direction;
    index >= 0 && index < attempts.length;
    index += direction
  ) {
    const attempt = attempts[index]!
    if (canReadObservationContent(attempt.input) || canReadObservationContent(attempt.output))
      return attempt
  }
}
const previousReaderAttempt = computed(() => adjacentReaderAttempt(-1))
const nextReaderAttempt = computed(() => adjacentReaderAttempt(1))
watch(requestedRef, (reference, previous) => {
  if (!reference && previous) emit('contentClose')
})
const trajectoryJson = computed(() =>
  jsonOpen.value
    ? JSON.stringify(
        {
          detail: props.detail ?? null,
          nodes: props.page ?? null,
          loaded_node_count: props.page?.items.length ?? 0,
          has_more: Boolean(props.page?.next_cursor)
        },
        null,
        2
      )
    : ''
)

watch(
  () => props.scopeKey,
  () => {
    searchInput.value = ''
    submittedSearch.value = ''
    searchError.value = ''
    searchState.value = 'idle'
    beforeSearch = undefined
    selectedId.value = ''
    pendingFocus.value = props.focusNode
    requestedRef.value = ''
    jsonOpen.value = false
    expandedIds.value = []
    visitedIds.clear()
    manuallyCollapsed.clear()
  },
  { immediate: true }
)

watch(
  () => [props.page, props.appliedQuery] as const,
  ([page]) => {
    if (!page) return
    // 查询失败保留旧页时，仍显示该页实际使用的条件。
    if (searchState.value !== 'pending' || appliedSearch.value !== submittedSearch.value) return
    searchState.value = 'idle'
    requestedRef.value = ''
    let restoredExpansion: Set<string> | undefined
    if (appliedSearch.value) {
      // 搜索结果是明确的定位动作，必须展开已有且可能已折叠的整条命中路径。
      // 只在本次搜索成功时覆盖折叠；普通续页继续保留用户选择。
      for (const node of page.items) expandPath(node.node_id, true)
      selectedId.value = page.items[0]?.node_id ?? ''
    } else if (beforeSearch) {
      expandedIds.value = beforeSearch.expanded
      restoredExpansion = new Set(beforeSearch.expanded)
      manuallyCollapsed.clear()
      beforeSearch.collapsed.forEach((id) => manuallyCollapsed.add(id))
      selectedId.value = beforeSearch.selected
      beforeSearch = undefined
    }
    void nextTick(() => {
      // ElTree 的 default-expanded-keys 只展开节点，不负责收起曾展开的搜索路径。
      if (restoredExpansion)
        for (const id of nodeIndex.value.keys())
          if (!restoredExpansion.has(id)) tree.value?.getNode(id)?.collapse()
      tree.value?.setCurrentKey(selectedId.value, false)
    })
  }
)

watch(
  () => props.error,
  (error) => {
    if (error) searchState.value = searchState.value === 'pending' ? 'failed' : 'idle'
  }
)

function retry() {
  if (searchState.value === 'failed') searchState.value = 'pending'
  emit('retry')
}

function expandPath(id: string, explicit = false) {
  const expanded = new Set(expandedIds.value)
  const visited = new Set<string>()
  let node = nodeIndex.value.get(id)
  while (node && !visited.has(node.node_id)) {
    visited.add(node.node_id)
    if (explicit || !manuallyCollapsed.has(node.node_id)) expanded.add(node.node_id)
    node = node.parent_node_id ? nodeIndex.value.get(node.parent_node_id) : undefined
  }
  expandedIds.value = [...expanded]
}

watch(
  nodeIndex,
  (index) => {
    for (const node of index.values()) {
      if (visitedIds.has(node.node_id)) continue
      visitedIds.add(node.node_id)
      if (
        ['ENTRY', 'AGENT'].includes(node.kind) ||
        [
          'FAILED',
          'RUNNING',
          'WAITING',
          'WAITING_INPUT',
          'INPUT_REQUIRED',
          'UNKNOWN',
          'OUTCOME_UNKNOWN'
        ].includes(node.status)
      )
        expandPath(node.node_id)
    }
    if (pendingFocus.value && index.has(pendingFocus.value)) {
      selectedId.value = pendingFocus.value
      expandPath(pendingFocus.value, true)
      pendingFocus.value = ''
      requestedRef.value = ''
    } else if (!index.has(selectedId.value)) {
      const problemNodes = [...index.values()].filter((node) =>
        ['FAILED', 'UNKNOWN', 'OUTCOME_UNKNOWN'].includes(node.status)
      )
      selectedId.value =
        (props.focusNode && index.has(props.focusNode) ? props.focusNode : '') ||
        problemNodes.find((node) => !['ENTRY', 'AGENT', 'STEP'].includes(node.kind))?.node_id ||
        problemNodes[0]?.node_id ||
        [...index.values()].find((node) => node.kind === 'AGENT')?.node_id ||
        props.page?.items[0]?.node_id ||
        ''
      requestedRef.value = ''
    }
    const current = index.get(selectedId.value)
    if (
      requestedRef.value &&
      current?.input.ref !== requestedRef.value &&
      current?.output.ref !== requestedRef.value &&
      !current?.attempts.some(
        (attempt) =>
          attempt.input.ref === requestedRef.value || attempt.output.ref === requestedRef.value
      )
    )
      requestedRef.value = ''
    if (props.focusNode && index.has(props.focusNode)) expandPath(props.focusNode)
    void nextTick(() => tree.value?.setCurrentKey(selectedId.value, false))
  },
  { immediate: true }
)

watch(
  () => props.focusNode,
  async (id) => {
    pendingFocus.value = id
    if (!id || !nodeIndex.value.has(id)) return
    selectedId.value = id
    requestedRef.value = ''
    expandPath(id, true)
    pendingFocus.value = ''
    await nextTick()
    tree.value?.setCurrentKey(id, true)
  },
  { immediate: true }
)

function selectNode(node: ObservationTreeItem) {
  pendingFocus.value = ''
  if (selectedId.value !== node.node_id) requestedRef.value = ''
  selectedId.value = node.node_id
  emit('focus', node)
}

function expanded(node: ObservationTreeItem) {
  manuallyCollapsed.delete(node.node_id)
  if (!expandedIds.value.includes(node.node_id)) expandedIds.value.push(node.node_id)
}

function collapsed(node: ObservationTreeItem) {
  manuallyCollapsed.add(node.node_id)
  expandedIds.value = expandedIds.value.filter((id) => id !== node.node_id)
}

function search() {
  if (props.busy) return
  const query = searchInput.value.trim()
  if (Array.from(query).length > MAX_SEARCH_CODE_POINTS) {
    searchError.value = `步骤搜索最多 ${MAX_SEARCH_CODE_POINTS} 个字符，请缩短关键词`
    return
  }
  searchError.value = ''
  submittedSearch.value = query
  if (submittedSearch.value && !appliedSearch.value)
    beforeSearch = {
      expanded: [...expandedIds.value],
      collapsed: [...manuallyCollapsed],
      selected: selectedId.value
    }
  searchState.value = 'pending'
  pendingFocus.value = ''
  requestedRef.value = ''
  emit('search', submittedSearch.value)
}

function clearSearch() {
  if (props.busy) return
  searchInput.value = ''
  searchError.value = ''
  submittedSearch.value = ''
  searchState.value = 'pending'
  pendingFocus.value = ''
  emit('search', '')
}

function readContent(reference: string | null) {
  if (!reference) return
  requestedRef.value = reference
  emit('content', reference)
}

function changeReaderAttempt(attemptId: string) {
  const attempt = selected.value?.attempts.find((item) => item.attempt_id === attemptId)
  if (!attempt) return
  const preferred = attempt[readerSide.value]
  const other = attempt[readerSide.value === 'input' ? 'output' : 'input']
  readContent(canReadObservationContent(preferred) ? preferred.ref : other.ref)
}

function tagType(status: string) {
  if (['FAILED', 'REJECTED', 'TERMINATED'].includes(status)) return 'danger'
  if (['UNKNOWN', 'OUTCOME_UNKNOWN', 'WAITING', 'INPUT_REQUIRED'].includes(status)) return 'warning'
  if (['SUCCEEDED', 'COMPLETED', 'PASSED', 'ACCEPTED'].includes(status)) return 'success'
  return 'info'
}

function nodeLabel(kind: string) {
  const names: Record<string, string> = {
    ENTRY: '请求入口',
    AGENT: 'Agent 执行',
    STEP: '执行轮',
    LLM: '模型调用',
    TOOL: '工具调用',
    RETRIEVAL: '检索',
    MEMORY: '记忆操作',
    GUARDRAIL: '护栏校验',
    COMPACTION: '上下文压缩',
    WORKFLOW: '工作流'
  }
  return names[kind] ?? kind
}
</script>

<template>
  <div class="observation-view">
    <section v-if="summary" class="trajectory-overview" aria-label="任务输入与结果">
      <header class="section-heading overview-heading">
        <h3>任务概览</h3>
        <ElTag size="small" :type="tagType(summary.status)">{{
          statusLabel(summary.status)
        }}</ElTag>
      </header>
      <div class="question-answer">
        <section class="overview-content">
          <h4>用户输入</h4>
          <p class="readable">{{ summary.input ?? '未记录可见输入' }}</p>
        </section>
        <section class="overview-content">
          <h4>公开结果</h4>
          <p class="readable">{{ summary.output ?? '尚无可见最终结果' }}</p>
        </section>
      </div>
      <BudgetTerminationNotice
        class="overview-termination"
        :termination="detail?.termination ?? null"
      />
      <div class="overview-statistics" aria-label="运行概况">
        <dl class="overview-metrics">
          <div>
            <dt>回答状态</dt>
            <dd>{{ statusLabel(summary.answer_status) }}</dd>
          </div>
          <div>
            <dt>执行轮</dt>
            <dd>{{ summary.step_count }} 个</dd>
          </div>
          <div>
            <dt>模型尝试</dt>
            <dd>{{ summary.model_calls }} 次</dd>
          </div>
          <div>
            <dt>工具调用</dt>
            <dd>{{ summary.tool_calls }} 次</dd>
          </div>
          <div>
            <dt>输入 Token</dt>
            <dd>{{ formatObservationTokens(summary.totals?.prompt_tokens) }}</dd>
          </div>
          <div>
            <dt>输出 Token</dt>
            <dd>{{ formatObservationTokens(summary.totals?.completion_tokens) }}</dd>
          </div>
          <div class="duration-metric">
            <dt>执行跨度（含等待）</dt>
            <dd>{{ formatObservationDuration(summary.duration_ms) }}</dd>
          </div>
        </dl>
        <p class="muted coverage-note">用量：{{ observationCoverage(summary.coverage?.status) }}</p>
        <div v-if="detail?.usage_summary" class="cost-summary">
          <span
            >已确认费用
            <strong>{{
              formatObservationCosts(detail.usage_summary.amount_by_currency)
            }}</strong></span
          >
          <span class="muted">{{
            observationCostCoverage(detail.usage_summary.cost_coverage)
          }}</span>
        </div>
      </div>
    </section>

    <section class="execution-process" aria-label="执行过程">
      <header class="section-heading process-heading">
        <h3>执行过程</h3>
        <ElButton :disabled="!detail && !page" @click="jsonOpen = true">查看轨迹 JSON</ElButton>
      </header>
      <div class="observation-toolbar">
        <form class="step-search" @submit.prevent="search" @keydown.capture="guardFormEnter">
          <label :for="'observation-search-' + scopeKey">搜索步骤</label>
          <ElInput
            :id="'observation-search-' + scopeKey"
            v-model="searchInput"
            placeholder="工具名、可见摘要或错误信息"
            :maxlength="MAX_SEARCH_CODE_POINTS * 2"
            :aria-describedby="'observation-search-help-' + scopeKey"
            :disabled="busy"
            clearable
            @clear="clearSearch"
          />
          <ElButton native-type="submit" :loading="busy">搜索</ElButton>
        </form>
      </div>
      <p :id="'observation-search-help-' + scopeKey" class="muted search-help">
        搜索整个当前轨迹，最多
        {{ MAX_SEARCH_CODE_POINTS }} 个字符；命中节点连同祖先显示，正文仍按权限读取。
      </p>
      <FeedbackNotice v-if="searchError" :title="searchError" type="error" :closable="false" />
      <FeedbackNotice v-if="error" :title="error" type="error" :closable="false" show-icon>
        <ElButton :loading="busy" @click="retry">重新读取</ElButton>
      </FeedbackNotice>
      <p v-if="page" class="muted node-coverage" role="status">
        执行结构：{{ observationCoverage(page.coverage) }} · 已加载 {{ page.items.length }} 个节点
        <template v-if="page.next_cursor">，还有记录可加载</template>
        <template v-if="appliedSearch"> · 当前搜索：{{ appliedSearch }}</template>
      </p>
      <ElSkeleton v-if="busy && !page" :rows="6" animated />
      <ElEmpty
        v-else-if="page && !page.items.length && !page.ancestors.length && !error"
        :description="
          appliedSearch ? '没有匹配的步骤，可清除关键词后重新查询' : '尚无可展示的执行节点'
        "
      >
        <ElButton v-if="appliedSearch" @click="clearSearch">清除搜索</ElButton>
      </ElEmpty>

      <div v-if="treeNodes.length" class="observation-panels">
        <section class="execution-tree" aria-label="执行调用树">
          <header class="panel-heading">
            <h4>执行调用树</h4>
            <span class="muted">选择节点查看详情</span>
          </header>
          <div class="execution-tree-scroll">
            <ElTree
              ref="tree"
              :data="treeNodes"
              node-key="node_id"
              :props="{ label: 'name', children: 'children' }"
              :default-expanded-keys="expandedIds"
              :auto-expand-parent="false"
              :current-node-key="selectedId"
              :expand-on-click-node="false"
              :indent="12"
              highlight-current
              aria-label="执行调用树"
              @node-click="selectNode"
              @node-expand="expanded"
              @node-collapse="collapsed"
            >
              <template #default="{ data, node }">
                <div
                  class="tree-row"
                  :data-node-id="data.node_id"
                  :style="{ '--tree-depth': node.level - 1 }"
                >
                  <span v-if="node.level > 1" class="tree-guides" aria-hidden="true" />
                  <span class="node-kind">{{ data.kind }}</span>
                  <span class="node-name">{{ data.name || nodeLabel(data.kind) }}</span>
                  <span class="node-status" :class="'is-' + tagType(data.status)"
                    >{{ statusLabel(data.status) }}
                  </span>
                  <span class="node-duration">{{
                    formatObservationDuration(data.duration_ms)
                  }}</span>
                </div>
              </template>
            </ElTree>
          </div>
        </section>

        <section class="node-detail" aria-label="当前执行节点详情">
          <header class="panel-heading"><h4>节点详情</h4></header>
          <div class="node-detail-content">
            <template v-if="selected">
              <div class="detail-heading">
                <h3>{{ selected.name || nodeLabel(selected.kind) }}</h3>
                <ElTag :type="tagType(selected.status)">{{ statusLabel(selected.status) }}</ElTag>
              </div>
              <p v-if="selected.summary" class="readable">{{ selected.summary }}</p>
              <p
                v-if="selected.parent_node_id && !nodeIndex.has(selected.parent_node_id)"
                class="muted"
              >
                父节点未加载
              </p>
              <ElDescriptions :column="1" border size="small">
                <ElDescriptionsItem label="操作类型"
                  >{{ selected.kind }} · {{ nodeLabel(selected.kind) }}</ElDescriptionsItem
                >
                <ElDescriptionsItem label="开始时间">{{
                  formatDateTime(selected.started_at)
                }}</ElDescriptionsItem>
                <ElDescriptionsItem label="结束时间">{{
                  formatDateTime(selected.finished_at)
                }}</ElDescriptionsItem>
                <ElDescriptionsItem label="执行耗时">{{
                  formatObservationDuration(selected.duration_ms)
                }}</ElDescriptionsItem>
              </ElDescriptions>

              <div
                v-if="!showAttemptContent"
                class="content-actions"
                aria-label="节点原始输入与输出"
              >
                <div>
                  <span>原始输入 · {{ observationAvailability(selected.input.availability) }}</span>
                  <ElButton
                    v-if="canReadObservationContent(selected.input)"
                    :loading="contentBusy && requestedRef === selected.input.ref"
                    @click="readContent(selected.input.ref)"
                    >查看原始输入</ElButton
                  >
                </div>
                <div>
                  <span
                    >原始输出 · {{ observationAvailability(selected.output.availability) }}</span
                  >
                  <ElButton
                    v-if="canReadObservationContent(selected.output)"
                    :loading="contentBusy && requestedRef === selected.output.ref"
                    @click="readContent(selected.output.ref)"
                    >查看原始输出</ElButton
                  >
                </div>
              </div>

              <section v-if="selected.attempts.length" class="attempts" aria-label="调用尝试">
                <h4>调用尝试（{{ selected.attempts.length }}）</h4>
                <p class="muted">每次尝试独立保留；用量是否报告不代表执行成功或失败。</p>
                <div
                  v-for="(attempt, attemptIndex) in selected.attempts"
                  :key="attempt.attempt_id"
                  class="attempt-record"
                  :data-attempt-id="attempt.attempt_id"
                >
                  <template v-if="showAttemptContent">
                    <h5 class="attempt-title">
                      第 {{ attemptIndex + 1 }} 次尝试 ·
                      {{ attempt.model ?? attempt.provider ?? nodeLabel(selected.kind) }}
                    </h5>
                    <div
                      class="content-actions attempt-content"
                      :aria-label="`第 ${attemptIndex + 1} 次尝试原始内容`"
                    >
                      <div>
                        <span
                          >原始请求 ·
                          {{ observationAvailability(attempt.input.availability) }}</span
                        >
                        <ElButton
                          v-if="canReadObservationContent(attempt.input)"
                          :loading="contentBusy && requestedRef === attempt.input.ref"
                          @click="readContent(attempt.input.ref)"
                          >查看原始请求</ElButton
                        >
                      </div>
                      <div>
                        <span
                          >原始返回 ·
                          {{ observationAvailability(attempt.output.availability) }}</span
                        >
                        <ElButton
                          v-if="canReadObservationContent(attempt.output)"
                          :loading="contentBusy && requestedRef === attempt.output.ref"
                          @click="readContent(attempt.output.ref)"
                          >查看原始返回</ElButton
                        >
                      </div>
                    </div>
                  </template>
                  <details class="attempt">
                    <summary>
                      <span>{{ attempt.model ?? attempt.provider ?? '调用尝试' }}</span>
                      <ElTag size="small" :type="tagType(attempt.status ?? '')">{{
                        statusLabel(attempt.status)
                      }}</ElTag>
                      <span>{{ formatObservationDuration(attempt.duration_ms) }}</span>
                    </summary>
                    <ElDescriptions :column="1" border size="small">
                      <ElDescriptionsItem label="Attempt ID"
                        ><code>{{ attempt.attempt_id }}</code></ElDescriptionsItem
                      >
                      <ElDescriptionsItem label="供应商 / 模型"
                        >{{ attempt.provider ?? '未记录' }} /
                        {{ attempt.model ?? '未记录' }}</ElDescriptionsItem
                      >
                      <ElDescriptionsItem label="输入 / 输出 Token"
                        >{{ formatObservationTokens(attempt.totals?.prompt_tokens) }} /
                        {{
                          formatObservationTokens(attempt.totals?.completion_tokens)
                        }}</ElDescriptionsItem
                      >
                      <ElDescriptionsItem label="合计 Token">{{
                        formatObservationTokens(observationTokenTotal(attempt.totals))
                      }}</ElDescriptionsItem>
                      <ElDescriptionsItem label="缓存读取 / 写入"
                        >{{ formatObservationTokens(attempt.totals?.cached_input_tokens) }} /
                        {{
                          formatObservationTokens(attempt.totals?.cache_write_input_tokens)
                        }}</ElDescriptionsItem
                      >
                      <ElDescriptionsItem label="推理输出 Token">{{
                        formatObservationTokens(attempt.totals?.reasoning_output_tokens)
                      }}</ElDescriptionsItem>
                      <ElDescriptionsItem label="用量报告">{{
                        observationCoverage(attempt.report_status)
                      }}</ElDescriptionsItem>
                      <ElDescriptionsItem label="开始 / 结束"
                        >{{ formatDateTime(attempt.started_at) }} /
                        {{ formatDateTime(attempt.finished_at) }}</ElDescriptionsItem
                      >
                    </ElDescriptions>
                    <ElButton
                      v-if="
                        allowAttemptDiagnostics &&
                        selected.kind === 'LLM' &&
                        attempt.diagnostic_available
                      "
                      class="attempt-diagnostic"
                      @click="
                        emit(
                          'attempt',
                          attempt.attempt_id,
                          selected.source_session_id,
                          selected.root_input_id
                        )
                      "
                      >调用诊断</ElButton
                    >
                  </details>
                </div>
              </section>

              <details class="node-identities">
                <summary>调用标识与来源记录</summary>
                <ElDescriptions :column="1" border size="small">
                  <ElDescriptionsItem label="节点"
                    ><code>{{ selected.node_id }}</code></ElDescriptionsItem
                  >
                  <ElDescriptionsItem label="父节点"
                    ><code>{{ selected.parent_node_id ?? '无父节点' }}</code></ElDescriptionsItem
                  >
                  <ElDescriptionsItem label="来源会话"
                    ><code>{{ selected.source_session_id }}</code></ElDescriptionsItem
                  >
                  <ElDescriptionsItem label="根输入"
                    ><code>{{ selected.root_input_id }}</code></ElDescriptionsItem
                  >
                  <ElDescriptionsItem label="本次输入"
                    ><code>{{ selected.input_id ?? '未记录' }}</code></ElDescriptionsItem
                  >
                  <ElDescriptionsItem label="Run"
                    ><code>{{ selected.run_id ?? '未记录' }}</code></ElDescriptionsItem
                  >
                  <ElDescriptionsItem label="Action"
                    ><code>{{ selected.action_id ?? '未记录' }}</code></ElDescriptionsItem
                  >
                  <ElDescriptionsItem label="Step / iteration"
                    ><code
                      >{{ selected.step_id ?? '未记录' }} /
                      {{ selected.iteration ?? '未记录' }}</code
                    ></ElDescriptionsItem
                  >
                  <ElDescriptionsItem label="Trace / Span"
                    ><code
                      >{{ selected.trace_id ?? '未记录' }} /
                      {{ selected.span_id ?? '未记录' }}</code
                    ></ElDescriptionsItem
                  >
                  <ElDescriptionsItem label="来源序号">{{ selected.sequence }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="来源事件"
                    ><code>{{
                      selected.source_event_ids.length
                        ? selected.source_event_ids.join('、')
                        : '未记录'
                    }}</code></ElDescriptionsItem
                  >
                </ElDescriptions>
              </details>
            </template>
            <ElEmpty v-else description="选择执行节点查看详情" />
          </div>
        </section>
      </div>

      <ElButton v-if="page?.next_cursor" :loading="busy" class="more-nodes" @click="emit('more')"
        >加载更多节点</ElButton
      >
    </section>
    <ElDrawer
      v-model="contentOpen"
      title="节点原始内容"
      size="min(1100px, 100vw)"
      append-to-body
      destroy-on-close
      class="admin-drawer observation-content-drawer"
    >
      <section v-if="requestedRef && selected" class="recorded-content" aria-label="节点原始内容">
        <div class="reader-context">
          <h3>{{ selected.name || nodeLabel(selected.kind) }}</h3>
          <ElTag :type="tagType(selected.status)">{{ statusLabel(selected.status) }}</ElTag>
          <p v-if="readerAttemptIndex >= 0" class="muted">
            第 {{ readerAttemptIndex + 1 }} 次尝试 ·
            {{
              selected.attempts[readerAttemptIndex]?.model ??
              selected.attempts[readerAttemptIndex]?.provider ??
              nodeLabel(selected.kind)
            }}
          </p>
        </div>
        <div class="reader-toolbar" aria-label="原始内容切换">
          <div
            v-if="selected.attempts.length > 1"
            class="reader-attempt-nav"
            aria-label="切换调用尝试"
          >
            <ElButton
              :disabled="!previousReaderAttempt"
              @click="
                previousReaderAttempt && changeReaderAttempt(previousReaderAttempt.attempt_id)
              "
              >上一次尝试</ElButton
            >
            <span>{{ readerAttemptIndex + 1 }} / {{ selected.attempts.length }}</span>
            <ElButton
              :disabled="!nextReaderAttempt"
              @click="nextReaderAttempt && changeReaderAttempt(nextReaderAttempt.attempt_id)"
              >下一次尝试</ElButton
            >
          </div>
          <ElButton
            v-for="side in ['input', 'output'] as const"
            :key="side"
            :type="readerSide === side ? 'primary' : 'default'"
            :aria-pressed="readerSide === side"
            :disabled="!readerBinding || !canReadObservationContent(readerBinding[side])"
            :loading="contentBusy && readerSide === side"
            @click="readContent(readerBinding?.[side].ref ?? null)"
            >{{ readerLabels[side] }}</ElButton
          >
        </div>
        <p class="muted" role="status">
          {{ readerLabels[readerSide] }} ·
          {{
            loadedContent
              ? observationAvailability(loadedContent.availability)
              : contentBusy
                ? '正在读取…'
                : '等待读取'
          }}
        </p>
        <FeedbackNotice v-if="contentError" :title="contentError" type="error" :closable="false">
          <ElButton :loading="contentBusy" @click="readContent(requestedRef)"
            >重新读取内容</ElButton
          >
        </FeedbackNotice>
        <ElSkeleton v-if="contentBusy && !loadedContent" :rows="6" />
        <JsonViewer
          v-if="
            loadedContent && ['FULL', 'AVAILABLE', 'PARTIAL'].includes(loadedContent.availability)
          "
          :key="requestedRef"
          :value="loadedContent.value"
          :label="`完整已记录${readerLabels[readerSide]}`"
          height="max(240px, calc(100dvh - 320px))"
        />
      </section>
    </ElDrawer>
    <ElDrawer
      v-model="jsonOpen"
      title="轨迹 JSON"
      size="min(960px, 100vw)"
      append-to-body
      class="admin-drawer"
    >
      <p class="muted">
        当前授权投影 · 执行结构：{{ observationCoverage(page?.coverage) }} ·
        {{
          page ? (page.next_cursor ? '存在未加载节点' : '当前节点查询已加载完毕') : '节点尚未加载'
        }}。JSON 不包含未读取的私有内容。
      </p>
      <JsonViewer v-if="jsonOpen" :value="trajectoryJson" label="当前授权轨迹 JSON" height="70vh" />
    </ElDrawer>
  </div>
</template>

<style scoped>
.observation-view {
  container: observation / inline-size;
  min-width: 0;
  color: var(--admin-text);
}
.trajectory-overview {
  margin-bottom: 24px;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  background: var(--admin-surface);
}
.section-heading,
.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 16px;
}
.section-heading h3,
.panel-heading h4 {
  margin: 0;
}
.overview-heading {
  padding: 16px 20px;
  border-bottom: 1px solid var(--admin-border);
}
.question-answer {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
}
.overview-content {
  min-width: 0;
  padding: 20px;
}
.overview-content + .overview-content {
  border-top: 1px solid var(--admin-border);
}
.overview-content .readable {
  margin-bottom: 0;
}
.overview-statistics {
  padding: 16px 20px;
  border-top: 1px solid var(--admin-border);
  background: var(--admin-hover);
  border-radius: 0 0 6px 6px;
}
.overview-termination {
  padding-inline: 20px;
}
h3,
h4 {
  margin: 0 0 12px;
  line-height: 1.5;
  font-size: 16px;
  font-weight: 600;
}
h4 {
  font-size: 14px;
}
.readable {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.7;
  margin: 0 0 16px;
}
.observation-toolbar,
.step-search {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}
.overview-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 16px 24px;
  margin: 0;
  line-height: 1.6;
}
.overview-metrics > div {
  min-width: 0;
}
.overview-metrics dt {
  margin-bottom: 4px;
  color: var(--admin-muted);
  font-size: 13px;
}
.overview-metrics dd {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.coverage-note {
  margin: 12px 0 0;
}
.cost-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--admin-border);
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.cost-summary strong {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.execution-process {
  min-width: 0;
}
.process-heading {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border);
}
.step-search {
  flex: 1;
  min-width: 0;
}
.step-search .el-input {
  flex: 1;
  min-width: min(180px, 100%);
  max-width: 480px;
}
.muted {
  color: var(--admin-muted);
  font-size: 13px;
  line-height: 1.6;
}
.search-help {
  margin: 8px 0 16px;
}
.node-coverage {
  margin: 12px 0;
}
.observation-panels {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  margin-top: 16px;
}
.execution-tree,
.node-detail {
  min-width: 0;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  background: var(--admin-surface);
}
.panel-heading {
  min-height: 48px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--admin-border);
}
.execution-tree-scroll {
  min-width: 0;
  max-height: min(64dvh, 720px);
  padding: 6px;
  overflow: auto;
}
.node-detail-content {
  min-width: 0;
  padding: 16px;
}
.tree-row {
  position: relative;
  display: grid;
  grid-template-columns: 72px minmax(56px, 1fr) 50px 58px;
  align-items: center;
  gap: 4px;
  min-width: 256px;
  flex: 1;
  min-height: 32px;
  padding-right: 8px;
}
.tree-guides {
  position: absolute;
  top: 0;
  bottom: 0;
  left: calc(-12px - var(--tree-depth) * 12px);
  width: calc(var(--tree-depth) * 12px);
  background: repeating-linear-gradient(to right, var(--admin-border) 0 1px, transparent 1px 12px);
  pointer-events: none;
}
.tree-guides::after {
  position: absolute;
  top: 50%;
  right: 0;
  width: 8px;
  border-top: 1px solid var(--admin-border);
  content: '';
}
.node-kind {
  display: inline-block;
  padding: 2px 4px;
  border-radius: 3px;
  background: var(--admin-hover);
  color: var(--admin-muted);
  text-align: center;
  font-size: 11px;
  line-height: 1.5;
  font-weight: 600;
}
.node-name {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  line-height: 1.6;
}
.node-status,
.node-duration {
  text-align: right;
  white-space: nowrap;
  font-size: 12px;
  line-height: 1.6;
}
.node-status {
  color: var(--admin-muted);
}
.node-status.is-danger {
  color: var(--el-color-danger);
}
.node-status.is-warning {
  color: var(--el-color-warning-dark-2);
}
.node-duration {
  color: var(--admin-muted);
  font-variant-numeric: tabular-nums;
}
.detail-heading {
  display: flex;
  justify-content: space-between;
  align-items: start;
  gap: 12px;
}
.detail-heading h3 {
  overflow-wrap: anywhere;
}
.content-actions {
  display: grid;
  gap: 12px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--admin-border);
}
.content-actions > div {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
  line-height: 1.6;
}
.recorded-content {
  min-width: 0;
}
.reader-context {
  display: flex;
  align-items: start;
  flex-wrap: wrap;
  gap: 8px 12px;
}
.reader-context h3 {
  flex: 1;
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}
.reader-context p {
  flex-basis: 100%;
  margin: 0;
}
.reader-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}
.reader-attempt-nav {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
}
.reader-toolbar .el-button + .el-button {
  margin-left: 0;
}
.attempts {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--admin-border);
}
.attempt {
  margin: 12px 0;
  border-bottom: 1px solid var(--admin-border);
  padding-bottom: 12px;
}
summary {
  cursor: pointer;
  min-height: 32px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  line-height: 1.6;
}
summary::before {
  content: '▸';
  color: var(--admin-muted);
}
details[open] > summary::before {
  content: '▾';
}
.attempt[open] > summary,
.node-identities[open] > summary {
  margin-bottom: 12px;
}
.attempt-diagnostic {
  margin-top: 12px;
}
.attempt-record + .attempt-record {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--admin-border);
}
.attempt-title {
  margin: 12px 0 8px;
  font-size: var(--el-font-size-base);
  overflow-wrap: anywhere;
}
.attempt-content {
  margin-bottom: 12px;
}
.node-identities {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--admin-border);
}
code {
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}
.more-nodes {
  margin-top: 16px;
}
:deep(.el-tree) {
  min-width: 0;
  background: var(--admin-surface);
  --el-tree-node-hover-bg-color: var(--admin-hover);
}
:deep(.el-tree-node__content) {
  min-height: 32px;
  height: auto;
  white-space: nowrap;
  align-items: center;
  box-shadow: inset 2px 0 transparent;
}
:deep(.el-tree--highlight-current .el-tree-node.is-current > .el-tree-node__content) {
  background: var(--el-color-primary-light-9);
  box-shadow: inset 2px 0 var(--admin-accent);
}
:deep(.el-tree-node__expand-icon) {
  padding: 6px;
  min-width: 24px;
  min-height: 24px;
}
:deep(.el-tree-node:focus-visible > .el-tree-node__content) {
  outline: 2px solid var(--admin-accent);
  outline-offset: -2px;
}
:deep(.el-descriptions__cell) {
  overflow-wrap: anywhere;
}
@container observation (min-width: 860px) {
  .observation-panels {
    grid-template-columns: minmax(300px, 34%) minmax(0, 1fr);
  }
}
@container observation (min-width: 700px) {
  .question-answer {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .overview-content + .overview-content {
    border-top: 0;
    border-left: 1px solid var(--admin-border);
  }
}
@container observation (max-width: 600px) {
  .overview-termination {
    padding-inline: 12px;
  }
  .overview-heading,
  .overview-content,
  .overview-statistics,
  .node-detail-content {
    padding: 12px;
  }
  .overview-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
  .overview-metrics .duration-metric {
    grid-column: 1 / -1;
    min-width: 0;
  }
}
</style>
