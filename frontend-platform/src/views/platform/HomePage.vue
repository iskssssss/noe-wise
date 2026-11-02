<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, ref, watch } from 'vue'
import { AdminPage, useResource } from '@skillforge/shared/admin'
import { routePermission } from '@skillforge/shared/foundation/navigation'
import { api } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
import { HttpClientError } from '@skillforge/shared/utils/http'
const auth = useAuthStore()
const updatedAt = ref('')
const partialErrors = ref<string[]>([])
const tenants = ref<number>(),
  reviews = ref<number>(),
  models = ref<number>()
function canOpen(path: string) {
  const permission = routePermission(path)
  return Boolean(permission && auth.user?.permissions.includes(permission))
}
const statistics = [
  {
    key: 'tenants',
    path: '/platform/tenants',
    failure: '机构统计加载失败',
    read: async () => (await api.admin.listTenants({ limit: 1 }, { silent: true })).total
  },
  {
    key: 'reviews',
    path: '/platform/review',
    failure: '审核统计加载失败',
    read: async () =>
      (await api.marketplace.reviews({ limit: 1, status: 'pending' }, { silent: true })).total
  },
  {
    key: 'models',
    path: '/platform/models',
    failure: '模型统计加载失败',
    read: async () => (await api.admin.models.list({ silent: true })).length
  }
] as const
const permittedStatistics = computed(() => statistics.filter((item) => canOpen(item.path)))
const resource = useResource(async () => {
  const requested = permittedStatistics.value
  const results = await Promise.allSettled(requested.map((item) => item.read()))
  return requested.map((item, index) => ({ item, result: results[index]! }))
})
const busy = resource.loading
const error = resource.error
async function load() {
  const current = await resource.reload()
  if (!current) return
  partialErrors.value = []
  for (const { item, result } of current) {
    if (result.status === 'rejected') {
      if (result.reason instanceof HttpClientError) result.reason.claimFeedback()
      partialErrors.value.push(item.failure)
    } else if (item.key === 'tenants') tenants.value = result.value
    else if (item.key === 'reviews') reviews.value = result.value
    else models.value = result.value
  }
  updatedAt.value = current.length ? formatDateTime(new Date()) : ''
}
const entries = computed(() =>
  [
    {
      title: '系统智能体',
      path: '/platform/system-agents',
      description: '配置能力、验证草稿并发布'
    },
    { title: 'Skill 工作台', path: '/platform/skills', description: '编写技能文件，定位校验问题' },
    { title: '会话观察', path: '/platform/sessions', description: '查看问答与调用，定位运行问题' },
    { title: '模型账单', path: '/platform/billing', description: '按月查看模型调用费用与用量' },
    { title: '运维审计', path: '/platform/operations', description: '查询操作记录与处理运行事务' }
  ].filter((entry) => canOpen(entry.path))
)
watch(
  () =>
    JSON.stringify([
      auth.identityVersion,
      auth.user?.tenant_id,
      auth.user?.sub,
      auth.user?.session_id,
      auth.user?.authorization_version,
      auth.user?.mfa_state,
      auth.user?.permissions
    ]),
  () => {
    resource.invalidate()
    resource.data.value = undefined
    resource.error.value = ''
    tenants.value = undefined
    reviews.value = undefined
    models.value = undefined
    partialErrors.value = []
    updatedAt.value = ''
    void load()
  },
  { immediate: true, flush: 'sync' }
)
</script>

<template>
  <AdminPage title="工作台" description="机构、模型与运营事务概览">
    <template #extra>
      <ElButton v-if="permittedStatistics.length" :loading="busy" @click="load">刷新</ElButton>
    </template>
    <FeedbackNotice v-if="error" :title="error" type="error" :closable="false" />
    <FeedbackNotice
      v-if="partialErrors.length"
      :title="partialErrors.join('；')"
      type="warning"
      :closable="false"
    />
    <p v-if="updatedAt" class="admin-field-help" role="status">
      最近刷新：{{ updatedAt }}；失败项保留上次成功数据。
    </p>
    <ElEmpty
      v-if="!permittedStatistics.length"
      description="当前没有可查看的首页统计，请从左侧菜单进入已授权功能。"
    />
    <div v-else class="admin-home-metrics" v-loading="busy">
      <RouterLink v-if="canOpen('/platform/tenants')" to="/platform/tenants">
        <ElCard>
          <ElStatistic
            title="全部机构"
            :value="tenants ?? 0"
            :formatter="() => (tenants === undefined ? '—' : tenants.toLocaleString('zh-CN'))"
          />
        </ElCard>
      </RouterLink>
      <RouterLink v-if="canOpen('/platform/models')" to="/platform/models">
        <ElCard>
          <ElStatistic
            title="模型配置"
            :value="models ?? 0"
            :formatter="() => (models === undefined ? '—' : models.toLocaleString('zh-CN'))"
          />
        </ElCard>
      </RouterLink>
      <RouterLink v-if="canOpen('/platform/review')" to="/platform/review">
        <ElCard>
          <ElStatistic
            title="待审核工作流"
            :value="reviews ?? 0"
            :formatter="() => (reviews === undefined ? '—' : reviews.toLocaleString('zh-CN'))"
          />
        </ElCard>
      </RouterLink>
    </div>
    <ElCard v-if="entries.length">
      <h2>运营入口</h2>
      <div class="admin-home-links">
        <RouterLink
          v-for="entry in entries"
          :key="entry.path"
          :to="entry.path"
          class="admin-home-link"
        >
          <span>{{ entry.title }} →</span><small>{{ entry.description }}</small>
        </RouterLink>
      </div>
    </ElCard>
  </AdminPage>
</template>
