<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter, statusLabel } from '@skillforge/shared/admin'
import { computed, onMounted, reactive, ref, useId, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AdminPage, EntitySelect, useResource, useTask } from '@skillforge/shared/admin'
import { formatDateTime, toBeijingDateTime } from '@skillforge/shared/utils/datetime'
import { loadTenantOptions, resolveTenantOption } from '../../api/entityOptions'
import { api, type SessionListQuery } from '../../api'
import { observationAnswerStatus } from '@skillforge/shared/observation/presentation'
import { readSessionListQuery, sessionListQuery } from './sessionListLocation'
const route = useRoute(),
  router = useRouter()
const navigation = useTask()
const query = reactive<SessionListQuery>(readSessionListQuery(route.query))
const range = ref<[string, string] | undefined>(
  query.started_from && query.started_to ? [query.started_from, query.started_to] : undefined
)
const advancedCount = computed(
  () =>
    [
      query.session_id,
      query.user_id,
      query.agent_id,
      query.team_id,
      query.channel,
      range.value
    ].filter(Boolean).length
)
const advancedOpen = ref(advancedCount.value > 0)
// 日期控件只处理北京墙上时间，业务筛选仍保留带偏移的绝对时刻。
const dateRange = computed<[string, string] | undefined>({
  get: (): [string, string] | undefined =>
    range.value && [formatDateTime(range.value[0]), formatDateTime(range.value[1])],
  set: (value: [string, string] | null | undefined) => {
    const from = toBeijingDateTime(value?.[0]),
      to = toBeijingDateTime(value?.[1])
    range.value = from && to ? [from, to] : undefined
  }
})
// 输入中的筛选与当前结果的查询快照分开；翻页只使用已提交条件。
const applied = ref<SessionListQuery>({ ...query })
const listContext = computed(() => sessionListQuery(applied.value))
const unqueried = computed(
  () =>
    JSON.stringify(
      sessionListQuery({
        ...query,
        page: applied.value.page,
        started_from: range.value?.[0],
        started_to: range.value?.[1]
      })
    ) !== JSON.stringify(listContext.value)
)
const load = useResource(async (signal) => {
  const requestedPage = applied.value.page
  const result = await api.sessions.list({ ...applied.value, page: requestedPage }, { signal })
  return { ...result, page: requestedPage }
})
const resultContext = computed(() =>
  sessionListQuery({ ...applied.value, page: load.data.value?.page ?? applied.value.page })
)
function sessionOptionSource(kind: 'session' | 'user' | 'agent' | 'team' | 'channel') {
  return {
    async load(keyword: string, signal?: AbortSignal) {
      return (await api.sessions.options(keyword, { signal }))
        .filter((item) => item.kind === kind)
        .map((item) => ({
          id: item.value,
          label: item.label,
          description: item.description ?? undefined
        }))
    },
    async resolve(id: string) {
      const item = (await api.sessions.options(id)).find(
        (candidate) => candidate.kind === kind && candidate.value === id
      )
      return item
        ? { id: item.value, label: item.label, description: item.description ?? undefined }
        : { id, label: id }
    }
  }
}
const sessionOptions = sessionOptionSource('session')
const userOptions = sessionOptionSource('user')
const agentOptions = sessionOptionSource('agent')
const teamOptions = sessionOptionSource('team')
const channelOptions = sessionOptionSource('channel')

const rows = computed(() => load.data.value?.items ?? []),
  total = computed(() => load.data.value?.total ?? 0)
function filterStatusLabel(value: string) {
  if (value === 'COMPLETED') return '执行或最终回复已完成'
  if (value === 'FAILED') return '执行失败或未形成最终回复'
  if (value === 'NONE' || value === 'IN_PROGRESS') return observationAnswerStatus(value)
  return statusLabel(value)
}
function reload() {
  void load.reload()
}
async function applyFilters(next: SessionListQuery) {
  const changed =
    JSON.stringify(sessionListQuery({ ...next, page: applied.value.page })) !==
    JSON.stringify(listContext.value)
  applied.value = next
  query.page = next.page
  if (changed) {
    load.invalidate()
    load.data.value = undefined
  }
  const updated = await navigation.run(async () => {
    const target = { path: route.path, query: listContext.value }
    if (router.resolve(target).fullPath !== route.fullPath) {
      const failure = await router.replace(target)
      if (failure) throw Error('查询条件未能更新到地址栏，请重试查询')
    }
    return true
  })
  if (updated) reload()
}
function submitFilters() {
  void applyFilters({
    ...query,
    page: 1,
    started_from: range.value?.[0],
    started_to: range.value?.[1]
  })
}
function resetFilters() {
  Object.assign(query, readSessionListQuery({}))
  range.value = undefined
  query.page = 1
  submitFilters()
}
watch(
  () => route.query,
  (value) => {
    const next = readSessionListQuery(value)
    if (JSON.stringify(sessionListQuery(next)) === JSON.stringify(listContext.value)) return
    applied.value = next
    Object.assign(query, next)
    range.value =
      next.started_from && next.started_to ? [next.started_from, next.started_to] : undefined
    advancedOpen.value = advancedCount.value > 0
    load.invalidate()
    load.data.value = undefined
    reload()
  }
)
onMounted(reload)

const formTipId = useId()
</script>

<template>
  <AdminPage title="会话观察" description="跨机构只读观察会话，详情始终绑定机构与会话身份">
    <ElForm @keydown.capture="guardFormEnter" class="filters" @submit.prevent="submitFilters">
      <EntitySelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="query.tenant_id"
        :load="loadTenantOptions"
        :resolve="resolveTenantOption"
        placeholder="选择机构"
        aria-label="机构"
      />
      <ElInput
        v-model="query.summary"
        aria-label="摘要关键字"
        placeholder="搜索会话摘要"
        clearable
      />
      <ElSelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="query.status"
        clearable
        placeholder="全部回复或执行状态"
        aria-label="回复或执行状态"
      >
        <ElOption
          v-for="s in [
            'IDLE',
            'RUNNING',
            'WAITING',
            'COMPLETED',
            'FAILED',
            'CANCELLED',
            'OUTCOME_UNKNOWN',
            'IN_PROGRESS',
            'NONE'
          ]"
          :key="s"
          :value="s"
          :label="filterStatusLabel(s)"
        />
      </ElSelect>
      <ElButton native-type="submit" type="primary" :loading="load.loading.value">查询</ElButton>
      <ElButton @click="resetFilters">重置</ElButton>
      <ElButton :aria-expanded="advancedOpen" @click="advancedOpen = !advancedOpen">
        精确筛选{{ advancedCount ? `（${advancedCount}）` : '' }}
      </ElButton>
      <div v-show="advancedOpen" class="advanced-filters">
        <EntitySelect
          v-model="query.session_id"
          :load="sessionOptions.load"
          :resolve="sessionOptions.resolve"
          placeholder="选择会话"
          aria-label="会话"
        />
        <EntitySelect
          v-model="query.user_id"
          :load="userOptions.load"
          :resolve="userOptions.resolve"
          placeholder="选择终端用户"
          aria-label="终端用户"
        />
        <EntitySelect
          v-model="query.agent_id"
          :load="agentOptions.load"
          :resolve="agentOptions.resolve"
          placeholder="选择智能体"
          aria-label="智能体"
        />
        <EntitySelect
          v-model="query.team_id"
          :load="teamOptions.load"
          :resolve="teamOptions.resolve"
          placeholder="选择团队"
          aria-label="团队"
        />
        <EntitySelect
          v-model="query.channel"
          :load="channelOptions.load"
          :resolve="channelOptions.resolve"
          placeholder="选择渠道"
          aria-label="渠道"
        />
        <p class="admin-field-help">
          精确筛选支持按名称或近期记录远程搜索；候选来自当前授权范围内的会话事实。
        </p>
        <div v-described-by="formTipId + '-time-range'" class="time-range-filter">
          <ElDatePicker
            :aria-describedby="formTipId + '-time-range'"
            v-model="dateRange"
            type="datetimerange"
            format="YYYY-MM-DD HH:mm:ss"
            date-format="YYYY-MM-DD"
            time-format="HH:mm:ss"
            value-format="YYYY-MM-DD HH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
          />
          <FieldTip :id="formTipId + '-time-range'">
            按北京时间选择会话开始时间范围；清空后不限制开始时间，点击查询后生效。
          </FieldTip>
        </div>
      </div>

      <FieldTip :id="formTipId + '-group-0'"
        >按机构、会话、终端用户、Agent、团队、渠道、摘要关键词、状态和时间范围组合筛选。状态匹配最终回复或执行任一状态；失败或取消说明不代表最终回复完成，执行结果以“执行”状态为准。时间范围按北京时间选择，留空的条件不参与查询。</FieldTip
      >
    </ElForm>
    <p v-if="unqueried" class="admin-field-help" role="status">筛选条件已修改，点击查询后生效。</p>
    <FeedbackNotice
      v-if="load.error.value || navigation.error.value"
      :title="[load.error.value, navigation.error.value].filter(Boolean).join('；')"
      :closable="false"
      type="error"
    />
    <ElButton v-if="load.error.value" :loading="load.loading.value" @click="reload"
      >重新读取会话</ElButton
    >
    <p v-if="load.error.value && load.data.value" class="result-count" role="status">
      {{
        query.page === load.data.value.page
          ? `第 ${query.page} 页刷新失败，当前保留上次读取的结果。`
          : `第 ${query.page} 页读取失败，当前保留第 ${load.data.value.page} 页的结果。`
      }}
    </p>
    <p v-if="load.data.value" class="result-count" role="status">找到 {{ total }} 个会话</p>
    <ElTable
      table-layout="auto"
      :empty-text="
        load.loading.value
          ? '正在读取会话…'
          : load.error.value
            ? '会话列表尚未读取，请查看上方错误'
            : '当前已查询范围没有会话记录，可调整机构、状态或时间范围后查询'
      "
      :data="rows"
      row-key="session_id"
      v-loading="load.loading.value"
      style="width: 100%"
    >
      <ElTableColumn label="会话 / 机构" min-width="320" show-overflow-tooltip>
        <template #default="{ row }">
          <RouterLink
            :to="{
              path: '/platform/sessions/detail',
              query: { ...resultContext, tenantId: row.tenant_id, sessionId: row.session_id }
            }"
            >{{ row.summary || row.session_id }}</RouterLink
          >
          <div class="admin-field-help">{{ row.tenant_name }}</div>
        </template>
      </ElTableColumn>
      <ElTableColumn label="参与方与状态" min-width="280">
        <template #default="{ row }">
          <div>{{ row.user_name }} · {{ row.agent_name }}</div>
          <div class="admin-field-help">
            公开回复：{{ observationAnswerStatus(row.answer_status) }}
            <ElTag
              size="small"
              :type="
                ['FAILED', 'OUTCOME_UNKNOWN'].includes(row.execution_status) ? 'warning' : 'info'
              "
              >执行：{{ statusLabel(row.execution_status) }}</ElTag
            >
          </div>
          <div class="admin-field-help">{{ formatDateTime(row.last_activity_at) }}</div>
        </template>
      </ElTableColumn>
    </ElTable>
    <ElPagination
      class="actions"
      layout="total, prev, pager, next"
      :current-page="load.data.value?.page ?? query.page"
      :page-size="query.page_size"
      :total="total"
      @current-change="
        ($event: number) => {
          applyFilters({ ...applied, page: $event })
        }
      "
    />
  </AdminPage>
</template>

<style scoped>
.filters :deep(.el-input),
.filters :deep(.el-select) {
  width: 220px;
  max-width: 100%;
}
.advanced-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  width: 100%;
  padding-top: 12px;
  border-top: 1px solid var(--admin-border);
}
.result-count {
  color: var(--admin-muted);
  font-size: 13px;
}
</style>
