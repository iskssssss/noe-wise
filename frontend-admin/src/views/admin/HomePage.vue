<template>
  <AdminPage title="机构工作台" description="管理机构智能体、知识与服务运行。"
    ><template #extra
      ><ElButton v-if="cards.length" :loading="loading" @click="reload">刷新</ElButton></template
    ><FeedbackNotice v-if="error" :title="error" :closable="false" type="error" />
    <ElEmpty
      v-if="!cards.length"
      description="当前没有可查看的首页统计，请从左侧菜单进入已授权功能。"
    />
    <div v-else class="admin-home-metrics" v-loading="loading">
      <ElCard v-for="item in cards" :key="item.path"
        ><ElStatistic
          v-if="typeof item.value === 'number'"
          :title="item.title"
          :value="item.value"
        /><template v-else
          ><h3>{{ item.title }}</h3>
          <p class="muted">—</p></template
        >
        <FeedbackNotice
          v-if="item.error"
          :title="
            item.title +
            '：' +
            item.error +
            (typeof item.value === 'number' ? '，保留上次成功数据' : '')
          "
          type="error"
        />
        <RouterLink :to="item.path">查看{{ item.title }} →</RouterLink></ElCard
      >
    </div>
    <ElCard v-if="shortcuts.length"
      ><h3>快速开始</h3>
      <div class="admin-home-links">
        <RouterLink
          v-for="entry in shortcuts"
          :key="entry.path"
          :to="entry.path"
          class="admin-home-link"
          ><span>{{ entry.title }} →</span><small>{{ entry.description }}</small></RouterLink
        >
      </div></ElCard
    ></AdminPage
  >
</template>
<script setup lang="ts">
import { HttpClientError } from '@skillforge/shared/utils/http'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, ref, watch } from 'vue'
import { AdminPage, useResource } from '@skillforge/shared/admin'
import { routePermission } from '@skillforge/shared/foundation/navigation'
import { api } from '../../api'
import { useAuthStore } from '../../stores/auth'
const auth = useAuthStore()
function canOpen(path: string) {
  const permission = routePermission(path)
  return Boolean(permission && auth.user?.permissions.includes(permission))
}
const statistics = [
  {
    key: 'agents',
    title: '智能体',
    path: '/admin/agents',
    read: async () => (await api.agents.list()).total
  },
  {
    key: 'knowledge',
    title: '知识库',
    path: '/admin/knowledge',
    read: async () => (await api.knowledge.listBases()).total
  },
  {
    key: 'channels',
    title: '渠道',
    path: '/admin/channels',
    read: async () => (await api.channels.list()).total
  },
  {
    key: 'sessions',
    title: '会话',
    path: '/admin/sessions',
    read: async () => (await api.chat.sessions()).length
  }
] as const
const permittedStatistics = computed(() => statistics.filter((item) => canOpen(item.path)))
type StatisticKey = (typeof statistics)[number]['key']
const values = ref<Partial<Record<StatisticKey, number>>>({})
const failures = ref<Partial<Record<StatisticKey, string>>>({})
const {
  data,
  loading,
  error,
  reload: readStatistics,
  invalidate
} = useResource(async () => {
  const requested = permittedStatistics.value
  const results = await Promise.allSettled(requested.map((item) => item.read()))
  return requested.map((item, index) => ({ key: item.key, result: results[index]! }))
})
async function reload() {
  const result = await readStatistics()
  if (!result) return
  failures.value = {}
  for (const item of result) {
    if (item.result.status === 'fulfilled') values.value[item.key] = item.result.value
    else {
      if (item.result.reason instanceof HttpClientError) item.result.reason.claimFeedback()
      failures.value[item.key] = '本项统计未刷新，可点击“刷新”重新读取'
    }
  }
}
const cards = computed(() =>
  permittedStatistics.value.map((item) => ({
    ...item,
    value: values.value[item.key] ?? '—',
    error: failures.value[item.key]
  }))
)
const shortcuts = computed(() =>
  [
    { title: '配置智能体', path: '/admin/agents', description: '设置模型、指令和可用能力' },
    { title: '管理知识库', path: '/admin/knowledge', description: '上传资料并查看处理结果' },
    { title: '接入渠道', path: '/admin/channels', description: '连接服务入口，配置消息接收' },
    { title: '调试对话', path: '/admin/chat', description: '发起问答并处理待确认事项' }
  ].filter((entry) => canOpen(entry.path))
)
// 撤权或换号立即清除旧统计，并使已经派发的旧读取失去更新权。
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
    invalidate()
    data.value = undefined
    values.value = {}
    failures.value = {}
    error.value = ''
    void reload()
  },
  { immediate: true, flush: 'sync' }
)
</script>
