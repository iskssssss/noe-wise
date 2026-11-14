<template>
  <AdminPage title="运行仪表盘" description="查看本月用量、费用核对状态与依赖服务。"
    ><template #extra><ElButton :loading="loading" @click="reload">刷新</ElButton></template
    ><FeedbackNotice
      v-if="usageError"
      :title="'机构用量读取失败：' + usageError"
      :closable="false"
      type="error" />
    <div class="admin-toolbar admin-card" v-if="shortcuts.length">
      <div>
        <strong>机构日常工作</strong>
        <p class="admin-muted">从用量核对、任务处理和服务配置开始。</p>
      </div>
      <div class="admin-actions">
        <ElButton v-for="item in shortcuts" :key="item.path" @click="router.push(item.path)">{{
          item.label
        }}</ElButton>
      </div>
    </div>
    <ElEmpty v-if="!usageLoading && !usage && !usageError" description="暂无本月用量与费用概览" />
    <section v-else class="usage-overview" v-loading="usageLoading">
      <div class="summary-grid">
        <ElCard v-for="card in summaryCards" :key="card.label" class="admin-card summary-card">
          <span class="summary-label">{{ card.label }}</span>
          <strong class="summary-value">{{ card.value }}</strong>
          <small v-if="card.help" class="summary-help">{{ card.help }}</small>
        </ElCard>
      </div>
      <ElCard class="admin-card">
        <template #header>
          <div class="section-heading">
            <span>本月事件用量</span>
            <ElTag :type="statusTagType(total?.usage_status)">{{
              usageStatusLabel(total?.usage_status)
            }}</ElTag>
          </div>
        </template>
        <p v-if="total?.missing_cost_count" class="admin-field-help" role="status">
          {{ formatCount(total.missing_cost_count) }}
          项成本待确认。已确认成本只代表已知部分，未核实不视为零费用。
        </p>
        <ElTable
          table-layout="auto"
          :data="eventTypes"
          empty-text="本月暂无用量事件"
          row-key="event_type"
          ><ElTableColumn min-width="230" label="事件与用量"
            ><template #default="{ row }"
              ><div>{{ statusLabel(row.event_type) }}</div>
              <small
                >{{ formatCount(row.quantity) }} 次 · {{ formatCount(row.tokens) }} Token</small
              >
            </template></ElTableColumn
          ><ElTableColumn min-width="180" label="已确认成本（元）"
            ><template #default="{ row }"
              ><div>{{ formatCost(row.confirmed_cost) }}</div>
              <small v-if="row.missing_cost_count"
                >{{ formatCount(row.missing_cost_count) }} 项待确认</small
              >
            </template></ElTableColumn
          ></ElTable
        >
      </ElCard>
      <ElCard v-if="grouped.length" class="admin-card">
        <template #header>按维度分组</template>
        <ElTable table-layout="auto" :data="grouped" empty-text="当前没有分组用量">
          <ElTableColumn min-width="200" label="维度" prop="dimension" />
          <ElTableColumn min-width="220" label="用量">
            <template #default="{ row }"
              >{{ formatCount(row.calls) }} 次 · {{ formatCount(row.tokens) }} Token</template
            >
          </ElTableColumn>
          <ElTableColumn min-width="170" label="已确认成本（元）">
            <template #default="{ row }">{{ formatCost(row.confirmed_cost) }}</template>
          </ElTableColumn>
          <ElTableColumn width="132" label="状态">
            <template #default="{ row }"
              ><ElTag :type="statusTagType(row.usage_status)">{{
                usageStatusLabel(row.usage_status)
              }}</ElTag></template
            >
          </ElTableColumn>
        </ElTable>
      </ElCard>
    </section>
    <ElCard class="admin-card" style="margin-top: 22px"
      ><h3>依赖服务</h3>
      <FeedbackNotice
        v-if="healthError"
        :title="'服务状态读取失败：' + healthError"
        :closable="false"
        type="error" />
      <ElTable
        table-layout="auto"
        :data="dependencies"
        v-loading="healthLoading"
        empty-text="当前未返回依赖服务，可点击“刷新”重新读取"
        ><ElTableColumn
          min-width="200"
          show-overflow-tooltip
          prop="name"
          label="服务" /><ElTableColumn width="128" show-overflow-tooltip label="状态"
          ><template #default="{ row }"
            ><ElTag :type="row.ok ? 'success' : 'danger'">{{
              row.ok ? '正常' : '异常'
            }}</ElTag></template
          ></ElTableColumn
        ><ElTableColumn
          min-width="280"
          show-overflow-tooltip
          prop="error"
          label="说明" /></ElTable></ElCard
  ></AdminPage>
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onMounted } from 'vue'
import { AdminPage, statusLabel, useResource } from '@skillforge/shared/admin'
import { api, type UsageAggregateRow } from '../../api'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { routePermission } from '@skillforge/shared/foundation/navigation'
const router = useRouter(),
  auth = useAuthStore()
const shortcuts = computed(() =>
  [
    { label: '核对用量', path: '/admin/usage' },
    { label: '处理接管工单', path: '/admin/handoff' },
    { label: '查看会话任务', path: '/admin/conversation-tasks' },
    { label: '管理渠道', path: '/admin/channels' }
  ].filter((item) => {
    const permission = routePermission(item.path)
    return !permission || auth.user?.permissions.includes(permission)
  })
)
const {
  data: usage,
  loading: usageLoading,
  error: usageError,
  reload: reloadUsage
} = useResource(() => api.billing.usage())
const {
  data: health,
  loading: healthLoading,
  error: healthError,
  reload: reloadHealth
} = useResource(() => api.health())
const loading = computed(() => usageLoading.value || healthLoading.value)
async function reload() {
  await Promise.all([reloadUsage(), reloadHealth()])
}
onMounted(reload)
const dependencies = computed(() =>
  Object.entries(health.value?.deps ?? {}).map(([name, value]) => ({ name, ...value }))
)
const total = computed(() => usage.value?.total)
const eventTypes = computed(() => usage.value?.event_types ?? [])
const grouped = computed(() => usage.value?.grouped ?? [])
const usageStatusLabels: Record<string, string> = {
  complete: '完整',
  partial: '部分缺失',
  unresolved: '待确认',
  missing: '缺失'
}
function usageStatusLabel(value?: string | null) {
  return value ? (usageStatusLabels[value] ?? value) : '状态未记录'
}
function statusTagType(value?: string | null): 'success' | 'warning' | 'info' {
  if (!value) return 'info'
  return value === 'complete' ? 'success' : 'warning'
}
function formatCount(value?: number | null) {
  return value === null || value === undefined ? '—' : value.toLocaleString('zh-CN')
}
function formatCost(value?: string | number | null) {
  if (value === null || value === undefined || value === '') return '—'
  const text = String(value)
  if (!/^-?\d+(?:\.\d+)?$/.test(text)) return text
  const [whole, fraction] = text.split('.')
  const trimmed = fraction?.replace(/0+$/, '') ?? ''
  return trimmed ? `${whole}.${trimmed}` : whole
}
const summaryCards = computed(() => {
  const value: UsageAggregateRow | undefined = total.value
  return [
    {
      label: '请求数',
      value: formatCount(value?.calls)
    },
    {
      label: 'Token 用量',
      value: formatCount(value?.tokens),
      help: `输入 ${formatCount(value?.prompt_tokens)} · 输出 ${formatCount(value?.completion_tokens)}`
    },
    {
      label: '已确认成本（元）',
      value: formatCost(value?.confirmed_cost)
    },
    {
      label: '待确认成本项',
      value: formatCount(value?.missing_cost_count),
      help: value?.missing_cost_count ? '未核实不代表零费用' : undefined
    }
  ]
})
</script>
<style scoped>
.usage-overview {
  display: flex;
  flex-direction: column;
  gap: 22px;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 16px;
}
.summary-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.summary-label,
.summary-help {
  color: var(--el-text-color-secondary);
}
.summary-value {
  font-size: 28px;
  line-height: 1.2;
  overflow-wrap: anywhere;
}
.summary-help {
  font-size: 12px;
  line-height: 1.5;
}
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
small {
  color: var(--el-text-color-secondary);
}
</style>
