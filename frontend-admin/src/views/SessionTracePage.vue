<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { AdminPage, statusLabel, useResource } from '@skillforge/shared/admin'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
import ObservationTree from '@skillforge/shared/observation/ObservationTree.vue'
import type {
  ObservationNode,
  ObservationPage,
  TrajectoryDetail,
  TrajectorySummary
} from '@skillforge/shared/observation/types'
import { api, getSessionId } from '../api'

const route = useRoute()
const session = computed(() => String(route.params.sessionId ?? route.query.session_id ?? ''))
const rounds = ref<TrajectorySummary[]>([]),
  listPage = ref(1),
  listTotal = ref(0),
  rootInput = ref(''),
  detail = ref<TrajectoryDetail>(),
  page = ref<ObservationPage>(),
  focusNode = ref(''),
  searchQuery = ref(''),
  appliedSearchQuery = ref(''),
  contentRef = ref('')
const resource = () => ({ session_id: session.value, root_input_id: rootInput.value })
const scopeKey = computed(() => JSON.stringify([getSessionId(), session.value, rootInput.value]))
const listTask = useResource((signal) =>
  api.trajectories.list(
    {
      session_id: session.value,
      started_from: '1970-01-01T00:00:00Z',
      page: listPage.value,
      page_size: 20
    },
    { signal, silent: true }
  )
)
const detailTask = useResource(async (signal) => {
  const target = resource(),
    opts = { signal, silent: true }
  const value = await api.trajectories.detail(target, opts)
  const nodes = await api.trajectories.nodes(
    {
      ...target,
      snapshot: value.snapshot,
      node_id: focusNode.value || undefined,
      query: searchQuery.value || undefined,
      page_size: 100
    },
    opts
  )
  return { detail: value, page: nodes }
})
let appendPage = false
const nodesTask = useResource((signal) =>
  api.trajectories.nodes(
    {
      ...resource(),
      snapshot: page.value?.snapshot,
      cursor: appendPage ? (page.value?.next_cursor ?? undefined) : undefined,
      query: (appendPage ? appliedSearchQuery.value : searchQuery.value) || undefined,
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
const error = computed(() => listTask.error.value)
const busy = computed(() => detailTask.loading.value || nodesTask.loading.value)

function clearContent() {
  contentTask.invalidate()
  contentTask.data.value = undefined
  contentTask.error.value = ''
  contentRef.value = ''
}
function clearTrajectory() {
  detailTask.invalidate()
  nodesTask.invalidate()
  detailTask.error.value = ''
  nodesTask.error.value = ''
  detail.value = undefined
  page.value = undefined
  searchQuery.value = ''
  appliedSearchQuery.value = ''
  clearContent()
}
async function refreshTrajectory() {
  if (!rootInput.value) return
  nodesTask.invalidate()
  nodesTask.error.value = ''
  const value = await detailTask.reload()
  if (!value) return
  clearContent()
  detail.value = value.detail
  page.value = value.page
  appliedSearchQuery.value = searchQuery.value
  const located = [...value.page.ancestors, ...value.page.items].find(
    (node) => node.node_id === focusNode.value || node.source_event_ids.includes(focusNode.value)
  )
  if (located) focusNode.value = located.node_id
}
async function selectRound(id: string, node = '') {
  if (id !== rootInput.value) clearTrajectory()
  rootInput.value = id
  focusNode.value = node
  await refreshTrajectory()
}
async function reload() {
  const previousPage = listPage.value
  listPage.value = 1
  const requestedSession = session.value
  const value = await listTask.reload()
  if (requestedSession !== session.value) return
  const requested = typeof route.query.root_input_id === 'string' ? route.query.root_input_id : ''
  if (!value) {
    listPage.value = previousPage
    if (!rootInput.value && requested)
      await selectRound(requested, typeof route.query.node_id === 'string' ? route.query.node_id : '')
    return
  }
  rounds.value = value.items
  listTotal.value = value.total
  await selectRound(
    rootInput.value || requested || value.items[0]?.root_input_id || '',
    typeof route.query.node_id === 'string' ? route.query.node_id : ''
  )
}
async function moreRounds() {
  if (listTask.loading.value) return
  const previous = listPage.value
  const requestedSession = session.value
  listPage.value++
  const requestedPage = listPage.value
  const value = await listTask.reload()
  if (requestedSession !== session.value || requestedPage !== listPage.value) return
  if (!value) {
    listPage.value = previous
    return
  }
  const merged = new Map(rounds.value.map((round) => [round.root_input_id, round]))
  value.items.forEach((round) => merged.set(round.root_input_id, round))
  rounds.value = [...merged.values()]
  listTotal.value = value.total
}
async function search(query: string) {
  nodesTask.invalidate()
  searchQuery.value = query
  appendPage = false
  const value = await nodesTask.reload()
  if (value) {
    clearContent()
    page.value = value
    appliedSearchQuery.value = query
  }
}
async function more() {
  if (busy.value || !page.value?.next_cursor) return
  appendPage = true
  const value = await nodesTask.reload()
  if (!value || !page.value) return
  const merge = (before: ObservationNode[], incoming: ObservationNode[]) => {
    const nodes = new Map(before.map((node) => [node.node_id, node]))
    incoming.forEach((node) => nodes.set(node.node_id, node))
    return [...nodes.values()]
  }
  page.value = {
    ...value,
    items: merge(page.value.items, value.items),
    ancestors: merge(page.value.ancestors, value.ancestors)
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
}
watch(
  session,
  () => {
    listTask.invalidate()
    clearTrajectory()
    rounds.value = []
    listTotal.value = 0
    rootInput.value = ''
    focusNode.value = ''
    if (session.value) void reload()
  },
  { immediate: true }
)
watch(
  () => [route.query.root_input_id, route.query.node_id],
  () => {
    const root = typeof route.query.root_input_id === 'string' ? route.query.root_input_id : ''
    const node = typeof route.query.node_id === 'string' ? route.query.node_id : ''
    if (root && (root !== rootInput.value || node !== focusNode.value)) void selectRound(root, node)
  }
)
</script>

<template>
  <AdminPage title="Agent 执行轨迹" :description="session || '会话定位信息缺失'">
    <template #extra>
      <RouterLink class="el-button" to="/admin/trajectories">返回轨迹列表</RouterLink>
      <RouterLink class="el-button" to="/admin/sessions">会话记录</RouterLink>
      <ElButton :loading="listTask.loading.value || busy" @click="reload">刷新</ElButton>
    </template>
    <ElAlert
      v-if="!session"
      title="缺少会话 ID，请从轨迹列表重新打开"
      type="error"
      :closable="false"
    />
    <FeedbackNotice v-if="error" :title="error" type="error" :closable="false" />
    <ElSkeleton v-if="listTask.loading.value && !rounds.length && !detail" :rows="7" />
    <div v-else class="trajectory-shell">
      <div class="trajectory-layout">
        <aside class="round-panel" aria-label="业务轮次">
          <header class="panel-heading">
            <div>
              <h2>业务轮次</h2>
              <p>选择一次请求，查看完整执行</p>
            </div>
            <ElTag type="info" size="small">{{ error && !rounds.length ? '尚未读取' : `${listTotal} 轮` }}</ElTag>
          </header>
          <ElMenu class="round-menu" :default-active="rootInput" @select="selectRound($event)">
            <ElMenuItem
              v-for="round in rounds"
              :key="round.root_input_id"
              :index="round.root_input_id"
            >
              <div class="round-summary">
                <span :title="round.input ?? round.root_input_id">{{
                  round.input ?? '输入未记录'
                }}</span>
                <small>{{ statusLabel(round.status) }} · {{ round.model_calls }} 次模型调用</small>
                <small>{{ formatDateTime(round.started_at) }}</small>
              </div>
            </ElMenuItem>
          </ElMenu>
          <ElButton
            v-if="rounds.length < listTotal"
            class="round-more"
            :loading="listTask.loading.value"
            @click="moreRounds"
            >更多轮次</ElButton
          >
          <ElEmpty
            v-if="!rounds.length && !detail && !listTask.loading.value && !error"
            description="本会话尚无轨迹记录"
          />
        </aside>
        <section class="trajectory-main" aria-label="执行树与详情">
          <header class="panel-heading">
            <div>
              <h2>执行详情</h2>
              <p>本轮问答、调用过程与执行结果</p>
            </div>
          </header>
          <div class="trajectory-main-content">
            <ObservationTree
              v-if="rootInput"
              :detail="detail"
              :page="page"
              :applied-query="appliedSearchQuery"
              :busy="busy"
              :error="detailTask.error.value || nodesTask.error.value"
              :scope-key="scopeKey"
              :focus-node="focusNode"
              :content="contentTask.data.value"
              :content-busy="contentTask.loading.value"
              :content-error="contentTask.error.value"
              @search="search"
              @more="more"
              @focus="focus"
              @content="showContent"
              @content-close="clearContent"
              @retry="
                nodesTask.error.value
                  ? appendPage
                    ? more()
                    : search(searchQuery)
                  : refreshTrajectory()
              "
            />
          </div>
        </section>
      </div>
    </div>
  </AdminPage>
</template>

<style scoped>
.trajectory-shell {
  container-type: inline-size;
  margin-top: 16px;
}
.trajectory-layout {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  align-items: start;
  gap: 20px;
}
.trajectory-layout > * {
  min-width: 0;
}
.round-panel,
.trajectory-main {
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
.trajectory-main-content {
  min-width: 0;
  padding: 20px;
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
.round-menu :deep(.el-menu-item) {
  height: auto;
  min-height: 76px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 6px;
}
.round-menu :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
  box-shadow: inset 3px 0 0 var(--admin-accent);
}
.round-more {
  margin: 0 16px 16px;
}
.round-summary {
  display: grid;
  gap: 4px;
  padding: 12px 0;
  line-height: 1.5;
  min-width: 0;
  width: 100%;
}
.round-summary > span,
.round-summary > small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.round-summary > small {
  color: var(--admin-muted);
}
.round-summary > span {
  font-weight: 600;
}
@container (max-width: 1100px) {
  .trajectory-layout {
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
  .trajectory-main-content {
    padding: 16px;
  }
}
</style>
