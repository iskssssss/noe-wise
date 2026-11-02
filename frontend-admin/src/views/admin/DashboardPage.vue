<template>
  <AdminPage title="运行仪表盘" description="查看最近读取的机构用量与依赖服务状态。"
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
    <ElEmpty v-if="!usageLoading && !usage && !usageError" description="暂无运行概览" />
    <div class="cards" v-loading="usageLoading">
      <ElCard class="admin-card" v-for="(value, key) in usage ?? {}" :key="key"
        ><ElStatistic
          v-if="typeof value === 'number'"
          :title="labels[key] || String(key)"
          :value="value"
          :formatter="() => String(value)"
        /><template v-else
          ><h3>{{ labels[key] || key }}</h3>
          <p v-if="value === null || typeof value !== 'object'">{{ value ?? '—' }}</p>
          <ElDescriptions v-else :column="1" border
            ><template v-for="(part, name) in value" :key="name"
              ><ElDescriptionsItem :label="String(name)">{{ part }}</ElDescriptionsItem></template
            ></ElDescriptions
          ></template
        ></ElCard
      >
    </div>
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
import { AdminPage, useResource } from '@skillforge/shared/admin'
import { api } from '../../api'
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
const labels: Record<string, string> = {
  total_tokens: '累计 Token',
  total_requests: '累计请求',
  total_amount: '累计金额',
  llm_calls: '模型调用',
  tokens: 'Token 用量'
}
</script>
