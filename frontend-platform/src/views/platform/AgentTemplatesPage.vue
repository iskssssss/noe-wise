<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { guardFormEnter } from '@skillforge/shared/admin'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AdminPage, statusLabel, useResource, useTask } from '@skillforge/shared/admin'
import { api } from '../../api'
const route = useRoute()
const router = useRouter()
const navigation = useTask()
const options = useResource((signal) => api.agentTemplates.options({ signal, silent: true }))
const initialPage = Number(route.query.list_page)
const initialStatus = typeof route.query.list_status === 'string' ? route.query.list_status : ''
const page = ref(Number.isSafeInteger(initialPage) && initialPage > 0 ? initialPage : 1),
  name = ref(typeof route.query.list_name === 'string' ? route.query.list_name : ''),
  status = ref(initialStatus)
// 输入中的筛选与当前结果的查询快照分开；翻页只使用已提交条件。
const applied = ref({ name: name.value, status: status.value })
const listContext = computed(() => ({
  list_name: applied.value.name || undefined,
  list_status: applied.value.status || undefined,
  list_page: String(load.data.value?.page ?? page.value)
}))
const load = useResource(async (signal) => {
  const requestedPage = page.value
  const result = await api.agentTemplates.list(
    { page: requestedPage, limit: 20, ...applied.value },
    { signal }
  )
  return { ...result, page: requestedPage }
})

const rows = computed(() => load.data.value?.items ?? []),
  total = computed(() => load.data.value?.total ?? 0)
// 页面自身同步地址时保留未提交输入；同页导航带来的新查询则重新恢复。
watch(
  () => route.query,
  (query) => {
    const nextPage = Number(query.list_page)
    const next = {
      name: typeof query.list_name === 'string' ? query.list_name : '',
      status: typeof query.list_status === 'string' ? query.list_status : ''
    }
    const normalizedPage = Number.isSafeInteger(nextPage) && nextPage > 0 ? nextPage : 1
    if (
      next.name === applied.value.name &&
      next.status === applied.value.status &&
      normalizedPage === page.value
    )
      return
    name.value = next.name
    status.value = next.status
    applied.value = next
    page.value = normalizedPage
    load.data.value = undefined
    void load.reload()
  }
)
async function reload() {
  const changed = await navigation.run(async () => {
    const target = {
      path: route.path,
      query: { ...route.query, ...listContext.value, list_page: String(page.value) }
    }
    if (router.resolve(target).fullPath !== route.fullPath) {
      const failure = await router.replace(target)
      if (failure) throw Error('查询条件未能更新到地址栏，请重试查询')
    }
    return true
  })
  if (changed) void load.reload()
}
function submitFilters() {
  page.value = 1
  applied.value = { name: name.value, status: status.value }
  load.data.value = undefined
  reload()
}
function resetFilters() {
  name.value = ''
  status.value = ''
  page.value = 1
  submitFilters()
}
onMounted(() => {
  void options.reload()
  void load.reload()
})
</script>

<template>
  <AdminPage title="公共定义" description="编辑、验证与发布可开放安装或授权部署的公共定义">
    <template #extra>
      <RouterLink
        v-permission="['platform.agent_definitions.create']"
        :to="{ path: '/platform/agent-templates/new', query: listContext }"
      >
        <ElButton type="primary">新增公共定义</ElButton>
      </RouterLink>
    </template>
    <ElForm
      @keydown.capture="guardFormEnter"
      class="admin-filters filters"
      @submit.prevent="submitFilters"
    >
      <ElInput v-model="name" aria-label="公共定义名称" placeholder="搜索名称" clearable />
      <ElSelect
        v-model="status"
        aria-label="公共定义状态"
        clearable
        placeholder="全部状态"
        :loading="options.loading.value"
        :disabled="!options.data.value"
      >
        <ElOption
          v-for="option in options.data.value?.statuses ?? []"
          :key="option.value"
          :value="option.value"
          :label="option.label"
        />
      </ElSelect>
      <ElButton native-type="submit" :loading="load.loading.value">查询</ElButton
      ><ElButton @click="resetFilters">重置</ElButton>
    </ElForm>
    <FeedbackNotice
      v-if="options.error.value"
      :title="options.error.value"
      type="error"
      :closable="false"
    />
    <ElButton v-if="options.error.value" :loading="options.loading.value" @click="options.reload()"
      >重新读取筛选选项</ElButton
    >
    <FeedbackNotice
      v-if="load.error.value || navigation.error.value"
      :title="load.error.value || navigation.error.value"
      :closable="false"
      type="error"
    />
    <p class="admin-muted" role="status">
      {{ load.loading.value ? '正在读取公共定义…' : `共 ${total} 个公共定义` }}
      <template v-if="rows.length"> · 点击名称维护定义与发布</template>
    </p>
    <ElTable
      table-layout="auto"
      :empty-text="
        load.loading.value
          ? '正在读取公共定义…'
          : load.error.value
            ? '公共定义列表尚未读取，请查看上方错误'
            : '当前已查询范围没有公共定义，可调整名称或状态后查询'
      "
      :data="rows"
      v-loading="load.loading.value"
      style="width: 100%"
    >
      <ElTableColumn prop="name" label="公共定义" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <RouterLink
            :to="{ path: '/platform/agent-templates/' + row.definition_id, query: listContext }"
            >{{ row.name }}</RouterLink
          >
        </template>
      </ElTableColumn>
      <ElTableColumn prop="category" label="分类" min-width="140" show-overflow-tooltip>
      </ElTableColumn>
      <ElTableColumn label="发布修订" width="110"
        ><template #default="{ row }">{{
          row.current_published_revision ?? '尚未发布'
        }}</template></ElTableColumn
      ><ElTableColumn label="工作草稿" width="120"
        ><template #default="{ row }">{{
          row.has_unpublished_changes ? '待发布' : '与发布一致'
        }}</template></ElTableColumn
      >
      <ElTableColumn prop="status" label="状态" width="110"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      >
      <ElTableColumn prop="install_count" label="安装次数" width="110"> </ElTableColumn>
    </ElTable>
    <ElPagination
      class="admin-actions"
      layout="total, prev, pager, next"
      :current-page="load.data.value?.page ?? page"
      :page-size="20"
      :total="total"
      @current-change="
        ($event: number) => {
          page = $event
          reload()
        }
      "
    />
  </AdminPage>
</template>
