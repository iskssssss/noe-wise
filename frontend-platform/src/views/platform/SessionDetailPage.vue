<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AdminPage, statusLabel, useResource } from '@skillforge/shared/admin'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
import ObservationTree from '@skillforge/shared/observation/ObservationTree.vue'
import type {
  ObservationNode,
  ObservationPage,
  TrajectoryDetail,
  TrajectoryUsage,
  TrajectoryUsageAttempt as SessionUsageAttempt
} from '@skillforge/shared/observation/types'
import {
  api,
  getSessionId,
  type SessionDetail,
  type SessionRound,
  type SessionMessage,
  formatToken,
  formatCacheHitRatio
} from '../../api'
import { mergeById, mergeMessages, messageState } from './sessionTimeline'
import SessionAttemptDiagnostics from './SessionAttemptDiagnostics.vue'
import { useActionPermissions } from '../../composables/actionPermissions'
const route = useRoute(),
  router = useRouter()
const { allowed } = useActionPermissions()
const canReadDiagnostics = computed(() => allowed('foundation.sessions.diagnostic_read'))
const cacheAvailabilityLabels: Record<string, string> = {
  AVAILABLE: '已有缓存统计',
  UNKNOWN: '未知',
  NOT_APPLICABLE: '不适用'
}
const tenant = computed(() => String(route.query.tenant_id ?? route.query.tenantId ?? '')),
  session = computed(() => String(route.query.session_id ?? route.query.sessionId ?? ''))
const detail = ref<SessionDetail>(),
  rounds = ref<SessionRound[]>([]),
  roundCursor = ref<string | null>(null),
  messages = ref<SessionMessage[]>([]),
  messageCursor = ref<string | null>(null),
  usage = ref<TrajectoryUsage>(),
  usageCursor = ref<string | null>(null),
  rootInput = ref(''),
  trajectory = ref<TrajectoryDetail>(),
  observationPage = ref<ObservationPage>(),
  searchQuery = ref(''),
  appliedSearchQuery = ref(''),
  contentRef = ref(''),
  focusNode = ref(''),
  attemptOpen = ref(false),
  attemptTarget = ref<{ attempt_id: string; session_id: string; root_input_id?: string }>(),
  activeTab = ref(
    ['timeline', 'messages', 'trace', 'usage'].includes(String(route.query.tab))
      ? String(route.query.tab)
      : 'timeline'
  )
const resource = () => ({
  tenant_id: tenant.value,
  session_id: session.value,
  root_input_id: rootInput.value
})
const load = useResource((signal) => {
  if (!tenant.value || !session.value) throw new Error('会话详情需要机构 ID 和会话 ID')
  return api.sessions.detail(tenant.value, session.value, { signal, silent: true })
})
const initialRounds = useResource(async (signal) => {
  if (!tenant.value || !session.value) throw new Error('会话轮次需要机构 ID 和会话 ID')
  const targetTenant = tenant.value,
    targetSession = session.value,
    opts = { signal, silent: true }
  const id = typeof route.query.root_input_id === 'string' ? route.query.root_input_id : ''
  const page = await api.sessions.rounds(
    targetTenant,
    targetSession,
    undefined,
    20,
    undefined,
    opts
  )
  if (id && !page.items.some((round) => round.root_input_id === id)) {
    const located = await api.sessions.rounds(targetTenant, targetSession, undefined, 20, id, opts)
    page.items = mergeById(page.items, located.items, (round) => round.root_input_id)
  }
  return page
})
const messagesTask = useResource((signal) => {
  const target = resource()
  return api.sessions.messages(
    target.tenant_id,
    target.session_id,
    target.root_input_id || undefined,
    undefined,
    50,
    { signal, silent: true }
  )
})
const usageTask = useResource((signal) =>
  api.sessions.usage(tenant.value, session.value, undefined, undefined, 50, {
    signal,
    silent: true
  })
)
const trajectoryTask = useResource((signal) =>
  api.trajectories.detail(resource(), { signal, silent: true })
)
const messagesLoaded = ref(false)
const roundsTask = useResource((signal) =>
  api.sessions.rounds(tenant.value, session.value, roundCursor.value ?? undefined, 20, undefined, {
    signal,
    silent: true
  })
)
type PageKind = 'messages' | 'usage'
let pageKind: PageKind = 'messages'
const pageTask = useResource(async (signal) => {
  const kind = pageKind,
    opts = { signal, silent: true }
  if (kind === 'messages')
    return {
      kind,
      messages: await api.sessions.messages(
        tenant.value,
        session.value,
        rootInput.value || undefined,
        messageCursor.value ?? undefined,
        50,
        opts
      )
    }
  return {
    kind,
    usage: await api.sessions.usage(
      tenant.value,
      session.value,
      rootInput.value || undefined,
      usageCursor.value ?? undefined,
      50,
      opts
    )
  }
})
let appendNodes = false
let initialNodes = false
const nodesTask = useResource((signal) =>
  api.trajectories.nodes(
    {
      ...resource(),
      snapshot: initialNodes
        ? trajectory.value?.snapshot
        : (observationPage.value?.snapshot ?? trajectory.value?.snapshot),
      node_id: initialNodes ? focusNode.value || undefined : undefined,
      cursor: appendNodes ? (observationPage.value?.next_cursor ?? undefined) : undefined,
      query: (appendNodes ? appliedSearchQuery.value : searchQuery.value) || undefined,
      page_size: 100
    },
    { signal, silent: true }
  )
)
const contentTask = useResource((signal) =>
  api.trajectories.content(
    { ...resource(), content_ref: contentRef.value },
    { signal, silent: true }
  )
)
const busy = computed(
  () =>
    messagesTask.loading.value ||
    usageTask.loading.value ||
    trajectoryTask.loading.value ||
    pageTask.loading.value ||
    nodesTask.loading.value
)
const scopeKey = computed(() =>
  JSON.stringify([getSessionId(), tenant.value, session.value, rootInput.value])
)
const error = computed(() =>
  [
    load.error.value,
    initialRounds.error.value && '轮次读取失败：' + initialRounds.error.value,
    messagesTask.error.value && '消息读取失败：' + messagesTask.error.value,
    usageTask.error.value && '用量读取失败：' + usageTask.error.value,
    trajectoryTask.error.value && '轨迹摘要读取失败：' + trajectoryTask.error.value,
    roundsTask.error.value,
    pageTask.error.value
  ]
    .filter(Boolean)
    .join('；')
)
function clearContent() {
  contentTask.invalidate()
  contentTask.data.value = undefined
  contentTask.error.value = ''
  contentRef.value = ''
}
function clearRound() {
  messagesTask.invalidate()
  usageTask.invalidate()
  trajectoryTask.invalidate()
  pageTask.invalidate()
  nodesTask.invalidate()
  clearContent()
  messagesTask.error.value = ''
  usageTask.error.value = ''
  trajectoryTask.error.value = ''
  pageTask.error.value = ''
  nodesTask.error.value = ''
  messages.value = []
  messagesLoaded.value = false
  usage.value = undefined
  trajectory.value = undefined
  observationPage.value = undefined
  messageCursor.value = null
  usageCursor.value = null
  searchQuery.value = ''
  appliedSearchQuery.value = ''
  attemptOpen.value = false
  attemptTarget.value = undefined
}
async function readMessages() {
  const value = await messagesTask.reload()
  if (!value) return
  messages.value = value.items
  messageCursor.value = value.next_cursor
  messagesLoaded.value = true
}
async function readUsage() {
  const value = await usageTask.reload()
  if (!value) return
  usage.value = value
  usageCursor.value = value.next_cursor
}
async function readInitialNodes() {
  initialNodes = true
  appendNodes = false
  const value = await nodesTask.reload()
  if (!value) return
  clearContent()
  observationPage.value = value
  appliedSearchQuery.value = searchQuery.value
  const located = [...value.ancestors, ...value.items].find(
    (item) => item.node_id === focusNode.value || item.source_event_ids.includes(focusNode.value)
  )
  if (located) focusNode.value = located.node_id
}
async function readTrajectory() {
  const value = await trajectoryTask.reload()
  if (!value) return
  if (observationPage.value && observationPage.value.snapshot !== value.snapshot) {
    observationPage.value = undefined
    clearContent()
  }
  trajectory.value = value
  usage.value = value.usage ?? undefined
  usageCursor.value = value.usage?.next_cursor ?? null
  await readInitialNodes()
}
function retryNodes() {
  return appendNodes ? moreNodes() : initialNodes ? readInitialNodes() : search(searchQuery.value)
}
async function selectRound(id: string, node = '') {
  if (id !== rootInput.value) clearRound()
  rootInput.value = id
  focusNode.value = node
  nodesTask.invalidate()
  nodesTask.error.value = ''
  pageTask.invalidate()
  clearContent()
  // 各读取自行投影成功结果，另一视图失败不撤销已读回的消息、摘要或用量。
  await Promise.all([readMessages(), id ? readTrajectory() : readUsage()])
}
function updateLocation(root: string, node: string, tab: string) {
  return router.replace({
    query: {
      ...route.query,
      root_input_id: root || undefined,
      scope: root ? undefined : 'all',
      node_id: node || undefined,
      tab
    }
  })
}
function selectRoundFromMenu(value: string) {
  const id = value === 'all' ? '' : value.slice(6)
  if (id === rootInput.value) {
    if (!id && route.query.scope !== 'all') void updateLocation('', '', activeTab.value)
    return
  }
  void selectRound(id)
  void updateLocation(id, '', activeTab.value)
}
function selectTab() {
  void updateLocation(rootInput.value, focusNode.value, activeTab.value)
}
function openTimeline() {
  activeTab.value = 'timeline'
  selectTab()
}
async function reload() {
  const [value, roundPage] = await Promise.all([load.reload(), initialRounds.reload()])
  if (!value) return
  detail.value = value
  if (roundPage) {
    rounds.value = roundPage.items
    roundCursor.value = roundPage.next_cursor
  }
  const id =
    rootInput.value ||
    (typeof route.query.root_input_id === 'string' ? route.query.root_input_id : '')
  const target =
    id ||
    (route.query.scope !== 'all' && activeTab.value === 'timeline'
      ? (rounds.value[0]?.root_input_id ?? '')
      : '')
  await selectRound(target, typeof route.query.node_id === 'string' ? route.query.node_id : '')
}
async function moreRounds() {
  const value = await roundsTask.reload()
  if (value) {
    rounds.value = mergeById(rounds.value, value.items, (round) => round.root_input_id)
    roundCursor.value = value.next_cursor
  }
}
async function more(kind: PageKind) {
  if (busy.value) return
  // 问答用量已包含同一轨迹快照的全部attempt，不能续接到全Session的Core查询。
  if (kind === 'usage' && rootInput.value) return
  pageKind = kind
  const value = await pageTask.reload()
  if (!value) return
  if (value.messages) {
    messages.value = mergeMessages(messages.value, value.messages.items)
    messageCursor.value = value.messages.next_cursor
  }
  if (value.usage && usage.value) {
    usage.value = {
      ...usage.value,
      attempts: mergeById(
        usage.value.attempts,
        value.usage.attempts,
        (attempt) => attempt.attempt_id
      )
    }
    usageCursor.value = value.usage.next_cursor
  }
}
async function search(query: string) {
  nodesTask.invalidate()
  searchQuery.value = query
  initialNodes = false
  appendNodes = false
  const value = await nodesTask.reload()
  if (value) {
    clearContent()
    observationPage.value = value
    appliedSearchQuery.value = query
  }
}
async function moreNodes() {
  if (busy.value || !observationPage.value?.next_cursor) return
  initialNodes = false
  appendNodes = true
  const value = await nodesTask.reload()
  if (!value || !observationPage.value) return
  observationPage.value = {
    ...value,
    items: mergeById(observationPage.value.items, value.items, (node) => node.node_id),
    ancestors: mergeById(observationPage.value.ancestors, value.ancestors, (node) => node.node_id)
  }
}
function showContent(ref: string) {
  clearContent()
  contentRef.value = ref
  void contentTask.reload()
}
function focus(node: ObservationNode) {
  focusNode.value = node.node_id
  clearContent()
  activeTab.value = 'timeline'
  void updateLocation(rootInput.value, node.node_id, 'timeline')
}
function attemptRoot(attempt: SessionUsageAttempt) {
  return rootInput.value || attempt.source_root_input_id || undefined
}
function showAttempt(attempt: SessionUsageAttempt) {
  if (!canReadDiagnostics.value || !attempt.diagnostic_available || !attempt.source_session_id)
    return
  attemptTarget.value = {
    attempt_id: attempt.attempt_id,
    session_id: attempt.source_session_id,
    root_input_id: attempt.source_root_input_id ?? undefined
  }
  attemptOpen.value = true
}
function showNodeAttempt(attemptId: string, sourceSessionId: string, rootInputId: string) {
  if (!canReadDiagnostics.value) return
  attemptTarget.value = {
    attempt_id: attemptId,
    session_id: sourceSessionId,
    root_input_id: rootInputId
  }
  attemptOpen.value = true
}
async function locateAttempt(attempt: SessionUsageAttempt) {
  const root = attemptRoot(attempt)
  if (!root || !attempt.trace_node_id) return
  activeTab.value = 'timeline'
  const selected = selectRound(root, attempt.trace_node_id)
  await updateLocation(root, attempt.trace_node_id, 'timeline')
  await selected
}

watch(
  () => [tenant.value, session.value],
  () => {
    load.invalidate()
    initialRounds.invalidate()
    roundsTask.invalidate()
    clearRound()
    detail.value = undefined
    rounds.value = []
    roundCursor.value = null
    rootInput.value = ''
    focusNode.value = ''
    void reload()
  },
  { immediate: true }
)
watch(
  () => [route.query.root_input_id, route.query.node_id, route.query.tab, route.query.scope],
  () => {
    const tab = String(route.query.tab)
    if (['timeline', 'messages', 'trace', 'usage'].includes(tab)) activeTab.value = tab
    const root =
      typeof route.query.root_input_id === 'string'
        ? route.query.root_input_id
        : route.query.scope === 'all'
          ? ''
          : rootInput.value
    const node = typeof route.query.node_id === 'string' ? route.query.node_id : ''
    if (detail.value && (root !== rootInput.value || node !== focusNode.value))
      void selectRound(root, node)
  }
)
watch(attemptOpen, (open) => {
  if (!open) attemptTarget.value = undefined
})
watch(canReadDiagnostics, (allowed) => {
  if (!allowed) {
    attemptOpen.value = false
    attemptTarget.value = undefined
  }
})
</script>

<template>
  <AdminPage title="会话执行轨迹" description="按业务轮次查看执行树、问答与模型用量">
    <template #extra>
      <RouterLink class="el-button" to="/platform/trajectories">轨迹列表</RouterLink>
      <RouterLink class="el-button" to="/platform/sessions">会话列表</RouterLink>
      <ElButton
        :loading="load.loading.value || initialRounds.loading.value"
        :disabled="busy"
        @click="reload"
        >刷新</ElButton
      >
    </template>
    <FeedbackNotice v-if="error" :title="error" :closable="false" type="error" />
    <ElButton
      v-if="load.error.value || initialRounds.error.value"
      :loading="load.loading.value || initialRounds.loading.value"
      :disabled="busy"
      @click="reload"
      >重新读取会话与轮次</ElButton
    >
    <ElButton
      v-if="messagesTask.error.value"
      :disabled="messagesTask.loading.value"
      @click="readMessages"
      >重新读取消息</ElButton
    >
    <ElButton v-if="usageTask.error.value" :disabled="usageTask.loading.value" @click="readUsage"
      >重新读取用量</ElButton
    >
    <ElButton
      v-if="trajectoryTask.error.value"
      :disabled="trajectoryTask.loading.value"
      @click="readTrajectory"
      >重新读取轨迹摘要</ElButton
    >
    <ElButton v-if="nodesTask.error.value" :disabled="nodesTask.loading.value" @click="retryNodes"
      >重新读取轨迹节点</ElButton
    >
    <ElButton v-if="roundsTask.error.value" :disabled="busy" @click="moreRounds"
      >重新读取轮次续页</ElButton
    >
    <ElButton v-if="pageTask.error.value" :disabled="busy" @click="more(pageKind)">{{
      pageKind === 'messages' ? '重新读取消息续页' : '重新读取用量续页'
    }}</ElButton>
    <ElSkeleton v-if="load.loading.value && !detail" :rows="8" />
    <div v-else-if="detail" class="session-observation">
      <section class="session-overview" aria-label="会话概要">
        <header class="panel-heading">
          <div>
            <h2>会话概要</h2>
            <p>当前会话的机构、参与者与整体状态</p>
          </div>
        </header>
        <div class="session-overview-content">
          <details class="session-identity">
            <summary>会话标识：{{ session }}</summary>
            <p>机构 ID：{{ tenant }}</p>
          </details>
          <ElDescriptions :column="3" border>
            <ElDescriptionsItem label="机构">{{ detail.tenant_name ?? tenant }}</ElDescriptionsItem>
            <ElDescriptionsItem label="用户">{{
              detail.user_name ?? detail.user_id
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="执行状态">{{
              statusLabel(detail.execution_status)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="Agent">{{
              detail.agent_name ?? detail.agent_id
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="回答状态">{{
              messageState(detail.answer_status)
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="可见消息数">{{ detail.message_count }}</ElDescriptionsItem>
          </ElDescriptions>
        </div>
      </section>
      <div class="detail-grid">
        <aside class="round-panel" aria-label="业务轮次">
          <header class="panel-heading">
            <div>
              <h2>业务轮次</h2>
              <p>最新轮次在前，选择轮次查看执行</p>
            </div>
            <ElTag type="info" size="small">{{
              initialRounds.error.value && !rounds.length
                ? '尚未读取'
                : `${rounds.length}${roundCursor ? '+' : ''} 轮`
            }}</ElTag>
          </header>
          <ElMenu
            class="round-menu"
            :default-active="rootInput ? 'round:' + rootInput : 'all'"
            @select="selectRoundFromMenu"
          >
            <ElMenuItem index="all">全部消息与用量</ElMenuItem>
            <ElMenuItem
              v-for="round in rounds"
              :key="round.root_input_id"
              :index="'round:' + round.root_input_id"
              :data-round-id="round.root_input_id"
              ><div class="round-summary">
                <span :title="round.root_input_id">第 {{ round.round_number }} 轮</span
                ><small
                  >{{ statusLabel(round.execution_status) }} · {{ round.run_count }} 次运行</small
                ><time class="round-time" :datetime="round.started_at ?? undefined">{{
                  formatDateTime(round.started_at)
                }}</time>
              </div></ElMenuItem
            >
          </ElMenu>
          <ElButton
            v-if="roundCursor"
            class="round-more"
            :loading="roundsTask.loading.value"
            :disabled="busy"
            @click="moreRounds"
            >更多轮次</ElButton
          >
        </aside>
        <section class="session-workspace" aria-label="会话内容">
          <header class="panel-heading">
            <div>
              <h2>会话内容</h2>
              <p>按轮次查看执行，或切换消息与用量</p>
            </div>
          </header>
          <ElTabs v-model="activeTab" class="workspace-tabs" @tab-change="selectTab">
            <ElTabPane name="timeline" label="问答与调用">
              <ElAlert
                v-if="!rootInput"
                type="info"
                title="请选择业务轮次查看执行树"
                :closable="false"
              />
              <ObservationTree
                v-else
                :detail="trajectory"
                :page="observationPage"
                :applied-query="appliedSearchQuery"
                :busy="trajectoryTask.loading.value || nodesTask.loading.value"
                :error="nodesTask.error.value"
                :scope-key="scopeKey"
                :focus-node="focusNode"
                :content="contentTask.data.value"
                :content-busy="contentTask.loading.value"
                :content-error="contentTask.error.value"
                :allow-attempt-diagnostics="canReadDiagnostics"
                @search="search"
                @more="moreNodes"
                @focus="focus"
                @content="showContent"
                @content-close="clearContent"
                @retry="retryNodes"
                @attempt="showNodeAttempt"
              />
            </ElTabPane>
            <ElTabPane name="messages" label="消息">
              <ElSkeleton v-if="messagesTask.loading.value && !messages.length" :rows="5" />
              <ElCard v-for="message in messages" :key="message.message_id">
                <div class="actions">
                  <ElTag>{{ statusLabel(message.role) }}</ElTag>
                  <ElTag v-if="message.phase" type="info">{{
                    message.phase === 'commentary'
                      ? '公开过程'
                      : message.phase === 'final'
                        ? '最终回答'
                        : message.phase
                  }}</ElTag>
                  <ElTag v-if="message.state" type="info">{{ messageState(message.state) }}</ElTag>
                  <small>{{ formatDateTime(message.created_at) }}</small>
                </div>
                <div class="prewrap">{{ message.content ?? '未记录内容' }}</div>
              </ElCard>
              <ElEmpty
                v-if="
                  messagesLoaded &&
                  !messages.length &&
                  !messagesTask.loading.value &&
                  !messagesTask.error.value
                "
                description="没有消息记录"
              />
              <ElButton v-if="messageCursor" :loading="busy" @click="more('messages')"
                >加载更多消息</ElButton
              >
            </ElTabPane>
            <ElTabPane name="trace" label="轨迹记录">
              <ElAlert
                v-if="!rootInput"
                type="info"
                title="请选择具体轮次查看轨迹"
                :closable="false"
              />
              <template v-else>
                <div
                  v-if="appliedSearchQuery"
                  class="actions"
                  role="status"
                  aria-label="轨迹记录筛选"
                >
                  <span
                    >当前仅显示步骤搜索“{{
                      appliedSearchQuery
                    }}”的命中记录；清除搜索后可查看未筛选的轨迹记录。</span
                  >
                  <ElButton :disabled="busy" @click="openTimeline">修改步骤搜索</ElButton>
                  <ElButton :loading="nodesTask.loading.value" :disabled="busy" @click="search('')"
                    >清除步骤搜索</ElButton
                  >
                </div>
                <p v-if="observationPage" class="muted">
                  当前快照覆盖：{{
                    observationPage?.coverage ?? '未记录'
                  }}；事件来源与调用身份保留在节点详情中。
                </p>
                <p
                  v-if="observationPage && (nodesTask.error.value || trajectoryTask.error.value)"
                  class="muted"
                  role="status"
                >
                  轨迹读取失败，以下保留上次读取的记录。
                </p>
                <ElTable
                  :data="observationPage?.items ?? []"
                  row-key="node_id"
                  :current-row-key="focusNode"
                  highlight-current-row
                  table-layout="auto"
                  :empty-text="
                    trajectoryTask.loading.value || nodesTask.loading.value
                      ? '正在读取轨迹记录…'
                      : trajectoryTask.error.value && !observationPage
                        ? '轨迹摘要读取失败，请重新读取轨迹摘要'
                        : nodesTask.error.value && !observationPage
                          ? '轨迹节点读取失败，请重新读取轨迹节点'
                          : '没有轨迹记录'
                  "
                  @row-click="focus"
                >
                  <ElTableColumn prop="sequence" label="序号" width="90" />
                  <ElTableColumn prop="kind" label="类型" width="140" />
                  <ElTableColumn prop="name" label="操作" min-width="180" show-overflow-tooltip />
                  <ElTableColumn prop="status" label="状态" width="120"
                    ><template #default="{ row }">{{
                      statusLabel(row.status)
                    }}</template></ElTableColumn
                  >
                  <ElTableColumn
                    prop="summary"
                    label="说明"
                    min-width="240"
                    show-overflow-tooltip
                  />
                  <ElTableColumn label="开始时间" width="190"
                    ><template #default="{ row }">{{
                      formatDateTime(row.started_at)
                    }}</template></ElTableColumn
                  >
                  <ElTableColumn label="来源事件" min-width="240" show-overflow-tooltip
                    ><template #default="{ row }">{{
                      row.source_event_ids.join('、') || '未记录'
                    }}</template></ElTableColumn
                  >
                </ElTable>
                <ElButton v-if="observationPage?.next_cursor" :loading="busy" @click="moreNodes"
                  >加载更多轨迹</ElButton
                >
              </template>
            </ElTabPane>
            <ElTabPane name="usage" label="Token 用量">
              <template v-if="usage">
                <p v-if="!rootInput" class="admin-field-help">
                  主 Agent 模型调用（选择问答可查看含辅助和成员的完整用量）
                </p>
                <p v-else class="admin-field-help">
                  当前问答的轨迹用量，包含已关联的辅助和成员模型调用。
                </p>
                <ElAlert
                  :type="usage.coverage.status === 'COMPLETE' ? 'success' : 'warning'"
                  :title="
                    '用量覆盖：' +
                    messageState(usage.coverage.status) +
                    '；已报告 ' +
                    usage.coverage.reported_attempts +
                    ' / ' +
                    usage.coverage.total_attempts
                  "
                />
                <ElDescriptions :column="2" border>
                  <ElDescriptionsItem label="输入 Token">{{
                    formatToken(usage.totals?.prompt_tokens)
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="输出 Token">{{
                    formatToken(usage.totals?.completion_tokens)
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="缓存输入">{{
                    formatToken(usage.totals?.cached_input_tokens)
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="推理输出">{{
                    formatToken(usage.totals?.reasoning_output_tokens)
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="缓存可用性">{{
                    cacheAvailabilityLabels[usage.cache?.availability ?? 'UNKNOWN'] ??
                    usage.cache?.availability
                  }}</ElDescriptionsItem>
                  <ElDescriptionsItem label="缓存命中率">{{
                    formatCacheHitRatio(usage.cache?.hit_ratio)
                  }}</ElDescriptionsItem>
                </ElDescriptions>
                <h3>{{ rootInput ? '完整轨迹模型调用明细' : '主 Agent 模型调用明细' }}</h3>
                <ElTable
                  table-layout="auto"
                  :empty-text="
                    busy
                      ? '正在读取模型调用用量…'
                      : error
                        ? '调用用量尚未读取，请查看上方错误'
                        : '当前已读取的范围没有模型调用用量明细'
                  "
                  :data="usage.attempts"
                  row-key="attempt_id"
                  style="width: 100%"
                >
                  <ElTableColumn
                    prop="provider"
                    label="提供方"
                    min-width="160"
                    show-overflow-tooltip
                  >
                  </ElTableColumn>
                  <ElTableColumn prop="model" label="模型" min-width="240" show-overflow-tooltip>
                  </ElTableColumn>
                  <ElTableColumn
                    prop="call_point"
                    label="调用点"
                    min-width="180"
                    show-overflow-tooltip
                  >
                  </ElTableColumn>
                  <ElTableColumn prop="report_status" label="报告状态" width="140"
                    ><template #default="{ row }">{{
                      messageState(row.report_status)
                    }}</template></ElTableColumn
                  >
                  <ElTableColumn prop="input" label="输入" width="120">
                    <template #default="{ row }">{{
                      formatToken(row.totals?.prompt_tokens)
                    }}</template>
                  </ElTableColumn>
                  <ElTableColumn prop="output" label="输出" width="120">
                    <template #default="{ row }">{{
                      formatToken(row.totals?.completion_tokens)
                    }}</template>
                  </ElTableColumn>
                  <ElTableColumn
                    prop="attempt_id"
                    label="Attempt ID"
                    min-width="240"
                    show-overflow-tooltip
                  />
                  <ElTableColumn label="缓存读取" width="140">
                    <template #default="{ row }">{{
                      formatToken(row.totals?.cached_input_tokens)
                    }}</template>
                  </ElTableColumn>
                  <ElTableColumn label="轨迹" width="120">
                    <template #default="{ row }">
                      <ElButton
                        link
                        type="primary"
                        :disabled="!row.trace_node_id || !attemptRoot(row as SessionUsageAttempt)"
                        @click="locateAttempt(row as SessionUsageAttempt)"
                        >定位节点</ElButton
                      >
                    </template>
                  </ElTableColumn>
                  <ElTableColumn label="详情" width="120" fixed="right">
                    <template #default="{ row }">
                      <ElButton
                        v-if="canReadDiagnostics && row.diagnostic_available"
                        link
                        type="primary"
                        :data-attempt-detail-id="row.attempt_id"
                        @click="showAttempt(row as SessionUsageAttempt)"
                        >调用详情</ElButton
                      >
                    </template>
                  </ElTableColumn>
                </ElTable>
                <ElButton v-if="!rootInput && usageCursor" :loading="busy" @click="more('usage')"
                  >加载更多调用</ElButton
                >
              </template>
            </ElTabPane>
          </ElTabs>
        </section>
      </div>
    </div>
    <ElDrawer
      class="admin-drawer"
      direction="rtl"
      append-to-body
      v-model="attemptOpen"
      title="模型调用详情"
      size="min(960px, 100vw)"
      destroy-on-close
    >
      <SessionAttemptDiagnostics
        v-if="canReadDiagnostics && attemptOpen && attemptTarget"
        :key="
          JSON.stringify([
            scopeKey,
            attemptTarget.session_id,
            attemptTarget.root_input_id,
            attemptTarget.attempt_id
          ])
        "
        :tenant-id="tenant"
        :session-id="attemptTarget.session_id"
        :root-input-id="attemptTarget.root_input_id"
        :parent-session-id="rootInput ? session : undefined"
        :parent-root-input-id="rootInput || undefined"
        :attempt-id="attemptTarget.attempt_id"
        :scope-key="scopeKey"
      />
    </ElDrawer>
  </AdminPage>
</template>

<style scoped>
.session-observation {
  container-type: inline-size;
}
.session-overview,
.round-panel,
.session-workspace {
  min-width: 0;
  background: var(--admin-surface);
  border: 1px solid var(--admin-border);
  border-radius: 10px;
}
.panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px;
  border-bottom: 1px solid var(--admin-border);
}
.panel-heading h2 {
  margin: 0;
  color: var(--admin-text);
  font-size: var(--el-font-size-base);
  font-weight: 600;
  line-height: 1.5;
}
.panel-heading p {
  margin: 4px 0 0;
  color: var(--admin-muted);
  font-size: var(--el-font-size-small);
  line-height: 1.5;
}
.panel-heading .el-tag {
  flex-shrink: 0;
}
.session-overview-content {
  padding: 16px 20px 20px;
}
.session-overview-content :deep(.el-descriptions__table) {
  table-layout: fixed;
  overflow-wrap: anywhere;
}
.detail-grid {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  align-items: start;
  gap: 20px;
  margin-top: 24px;
}
.detail-grid > section,
.detail-grid > aside {
  min-width: 0;
}
.workspace-tabs {
  min-width: 0;
}
.workspace-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 20px;
  border-bottom: 1px solid var(--admin-border);
}
.workspace-tabs :deep(.el-tabs__nav-wrap::after) {
  background: transparent;
}
.workspace-tabs :deep(.el-tabs__item) {
  height: 48px;
}
.workspace-tabs :deep(.el-tabs__content) {
  padding: 20px;
}
.round-summary {
  display: grid;
  gap: 4px;
  line-height: 1.5;
  padding: 12px 0;
  min-width: 0;
  width: 100%;
}
.round-menu :deep(.el-menu-item) {
  height: auto;
  min-height: 72px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 6px;
}
.round-menu :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
  box-shadow: inset 3px 0 0 var(--admin-accent);
}
.round-menu {
  display: grid;
  gap: 4px;
  max-height: min(60vh, 640px);
  overflow: auto;
  padding: 8px;
  border-right: 0;
  background: transparent;
}
.round-more {
  margin: 0 16px 16px;
}
.session-identity {
  margin-bottom: 12px;
  color: var(--admin-muted);
  overflow-wrap: anywhere;
}
.session-identity summary {
  cursor: pointer;
  padding: 6px 0;
}
.prewrap {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.7;
}
@container (max-width: 1100px) {
  .detail-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 16px;
  }
  .round-menu {
    display: flex;
    gap: 8px;
    max-height: 176px;
    overflow-x: auto;
  }
  .round-menu :deep(.el-menu-item) {
    flex: 0 0 210px;
  }
}
@container (max-width: 640px) {
  .session-overview-content,
  .workspace-tabs :deep(.el-tabs__content) {
    padding: 16px;
  }
  .workspace-tabs :deep(.el-tabs__header) {
    padding: 0 16px;
  }
}
.round-summary > span,
.round-summary > small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.round-summary > span {
  font-weight: 600;
}
.round-summary > small {
  color: var(--admin-muted);
}
.round-time {
  color: var(--admin-muted);
  font-size: var(--el-font-size-extra-small);
  white-space: normal;
  overflow-wrap: anywhere;
}
</style>
