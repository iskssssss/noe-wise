<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { guardFormEnter } from '@skillforge/shared/admin'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { AdminPage, statusLabel, useResource } from '@skillforge/shared/admin'
import { api } from '../../api'
const route = useRoute()
const initialPage = Number(route.query.list_page)
const initialStatus =
  typeof route.query.list_status === 'string' &&
  ['draft', 'published', 'deprecated', 'archived'].includes(route.query.list_status)
    ? route.query.list_status
    : ''
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
function reload() {
  void load.reload()
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
onMounted(reload)
</script>

<template>
  <AdminPage title="智能体模板" description="编辑、验证与发布可开放安装或授权部署的公共定义">
    <template #extra>
      <RouterLink
        v-permission="['platform.agent_definitions.create']"
        :to="{ path: '/platform/agent-templates/new', query: listContext }"
      >
        <ElButton type="primary">新增模板</ElButton>
      </RouterLink>
    </template>
    <ElForm
      @keydown.capture="guardFormEnter"
      class="admin-filters filters"
      @submit.prevent="submitFilters"
    >
      <ElInput v-model="name" aria-label="模板名称" placeholder="搜索名称" clearable />
      <ElSelect v-model="status" aria-label="模板状态" clearable placeholder="全部状态">
        <ElOption
          v-for="s in ['draft', 'published', 'deprecated', 'archived']"
          :key="s"
          :value="s"
          :label="statusLabel(s)"
        />
      </ElSelect>
      <ElButton native-type="submit" :loading="load.loading.value">查询</ElButton
      ><ElButton @click="resetFilters">重置</ElButton>
    </ElForm>
    <FeedbackNotice
      v-if="load.error.value"
      :title="load.error.value"
      :closable="false"
      type="error"
    />
    <p class="admin-muted" role="status">共 {{ total }} 个模板 · 点击名称维护定义与发布</p>
    <ElTable
      table-layout="auto"
      :empty-text="
        load.loading.value
          ? '正在读取模板…'
          : load.error.value
            ? '模板列表尚未读取，请查看上方错误'
            : '当前已查询范围没有模板，可调整名称或状态后查询'
      "
      :data="rows"
      v-loading="load.loading.value"
      style="width: 100%"
    >
      <ElTableColumn prop="name" label="模板" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <RouterLink
            :to="{ path: '/platform/agent-templates/' + row.definition_id, query: listContext }"
            >{{ row.name }}</RouterLink
          >
        </template>
      </ElTableColumn>
      <ElTableColumn prop="category" label="分类" min-width="140" show-overflow-tooltip>
      </ElTableColumn>
      <ElTableColumn prop="version" label="发布修订" width="110"> </ElTableColumn
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
