<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQuery } from 'vue-router'
import { AdminPage, guardFormEnter, useResource, useTask } from '@skillforge/shared/admin'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { api, type ModelListFilters } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { modelKindLabels } from './model-field-help'
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const navigation = useTask()
const options = useResource((signal) => api.admin.models.options({ signal, silent: true }))
function readFilters(query: LocationQuery): ModelListFilters {
  return {
    search: typeof query.list_search === 'string' ? query.list_search : '',
    kind: typeof query.list_kind === 'string' ? query.list_kind : '',
    enabled: typeof query.list_enabled === 'string' ? query.list_enabled : '',
    publication: typeof query.list_publication === 'string' ? query.list_publication : ''
  }
}
const applied = ref(readFilters(route.query))
const search = ref(applied.value.search),
  kind = ref(applied.value.kind),
  enabled = ref(applied.value.enabled),
  publication = ref(applied.value.publication)
const listContext = computed(() => ({
  list_search: applied.value.search || undefined,
  list_kind: applied.value.kind || undefined,
  list_enabled: applied.value.enabled || undefined,
  list_publication: applied.value.publication || undefined
}))
const {
  data,
  loading: busy,
  error,
  reload,
  invalidate
} = useResource(async (signal) => {
  try {
    return await api.admin.models.summaries(applied.value, { signal, silent: true })
  } catch (reason) {
    if (reason instanceof HttpClientError && reason.code === 'model_filter_invalid')
      throw new HttpClientError({
        ...reason,
        userMessage: '模型筛选条件无效，请重新选择或重置后查询'
      })
    throw reason
  }
})
const rows = computed(() => data.value ?? [])
const loaded = computed(() => data.value !== undefined)
const hasFilters = computed(() => Object.values(applied.value).some(Boolean))
const canCreate = computed(
  () => auth.user?.permissions.includes('foundation.models.create') ?? false
)
const unqueried = computed(
  () =>
    search.value.trim() !== applied.value.search ||
    kind.value !== applied.value.kind ||
    enabled.value !== applied.value.enabled ||
    publication.value !== applied.value.publication
)
function load() {
  if (auth.sessionIdentity) void reload()
}
async function submitFilters() {
  const next = {
    search: search.value.trim(),
    kind: kind.value,
    enabled: enabled.value,
    publication: publication.value
  }
  const changed =
    next.search !== applied.value.search ||
    next.kind !== applied.value.kind ||
    next.enabled !== applied.value.enabled ||
    next.publication !== applied.value.publication
  applied.value = next
  search.value = next.search
  if (changed) {
    invalidate()
    data.value = undefined
  }
  const updated = await navigation.run(async () => {
    const target = { path: route.path, query: { ...route.query, ...listContext.value } }
    if (router.resolve(target).fullPath !== route.fullPath) {
      const failure = await router.replace(target)
      if (failure) throw Error('查询条件未能更新到地址栏，请重试查询')
    }
    return true
  })
  if (updated) load()
}
function resetFilters() {
  search.value = ''
  kind.value = ''
  enabled.value = ''
  publication.value = ''
  void submitFilters()
}
watch(
  () => route.query,
  (query) => {
    const next = readFilters(query)
    if (
      next.search === applied.value.search &&
      next.kind === applied.value.kind &&
      next.enabled === applied.value.enabled &&
      next.publication === applied.value.publication
    )
      return
    applied.value = next
    search.value = next.search
    kind.value = next.kind
    enabled.value = next.enabled
    publication.value = next.publication
    invalidate()
    data.value = undefined
    load()
  }
)
watch(
  () => [auth.sessionIdentity, auth.identityVersion],
  () => {
    invalidate()
    options.invalidate()
    data.value = undefined
    options.data.value = undefined
    error.value = ''
    options.error.value = ''
    search.value = kind.value = enabled.value = publication.value = ''
    applied.value = { search: '', kind: '', enabled: '', publication: '' }
    const identity = auth.identityVersion
    const session = auth.sessionIdentity
    void Promise.resolve().then(() => {
      if (auth.identityVersion !== identity || auth.sessionIdentity !== session) return
      void navigation.run(async () => {
        await router.replace({ path: route.path, query: { ...route.query, ...listContext.value } })
        if (session && auth.identityVersion === identity && auth.sessionIdentity === session) {
          void options.reload()
          load()
        }
      })
    })
  },
  { flush: 'sync' }
)
onMounted(() => {
  if (auth.sessionIdentity) {
    void options.reload()
    load()
  }
})
</script>

<template>
  <AdminPage
    title="模型管理"
    description="配置对话、图像、音视频、向量与重排模型，检查待发布的修改"
  >
    <template #extra>
      <RouterLink
        v-permission="['foundation.models.create']"
        :to="{ path: '/platform/models/new', query: listContext }"
      >
        <ElButton type="primary">新增模型</ElButton>
      </RouterLink>
    </template>
    <ElForm
      class="admin-filters filters"
      @keydown.capture="guardFormEnter"
      @submit.prevent="submitFilters"
    >
      <ElInput
        v-model="search"
        aria-label="搜索模型"
        placeholder="搜索模型名称、标识或提供方"
        clearable
      />
      <ElSelect
        v-model="kind"
        aria-label="模型类型"
        placeholder="全部类型"
        clearable
        :loading="options.loading.value"
        :disabled="!options.data.value"
      >
        <ElOption
          v-for="option in options.data.value?.kinds ?? []"
          :key="option.value"
          :value="option.value"
          :label="option.label"
        />
      </ElSelect>
      <ElSelect
        v-model="enabled"
        aria-label="模型状态"
        placeholder="全部状态"
        clearable
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
      <ElSelect
        v-model="publication"
        aria-label="发布进度"
        placeholder="全部发布进度"
        clearable
        :loading="options.loading.value"
        :disabled="!options.data.value"
      >
        <ElOption
          v-for="option in options.data.value?.publications ?? []"
          :key="option.value"
          :value="option.value"
          :label="option.label"
        />
      </ElSelect>
      <div class="model-filter-actions">
        <ElButton native-type="submit" type="primary" :loading="busy || navigation.busy.value"
          >查询</ElButton
        >
        <ElButton @click="resetFilters">重置</ElButton>
        <ElButton :loading="busy" @click="load">刷新</ElButton>
      </div>
    </ElForm>
    <p v-if="unqueried" class="admin-muted">筛选已修改，点击查询后生效；刷新使用上次查询条件。</p>
    <FeedbackNotice v-if="options.error.value" type="error" :title="options.error.value" />
    <ElButton v-if="options.error.value" :loading="options.loading.value" @click="options.reload()"
      >重新读取筛选选项</ElButton
    >
    <FeedbackNotice
      v-if="error || navigation.error.value"
      type="error"
      :title="error || navigation.error.value"
    />
    <p v-if="loaded" class="admin-muted" role="status">
      {{ error ? '上次成功读取' : '当前' }} {{ rows.length }} 个模型<template v-if="error"
        >，刷新失败，数据可能已变化</template
      ><template v-else-if="rows.length"> · 点击名称配置、测试与发布</template>
    </p>
    <ElTable table-layout="auto" :data="rows" v-loading="busy" style="width: 100%">
      <template #empty>
        <p v-if="busy">正在读取模型…</p>
        <p v-else-if="error">模型读取失败，请调整筛选后查询或重试刷新</p>
        <div v-else-if="loaded && hasFilters">
          <p>没有符合当前查询条件的模型，可调整筛选后查询，或重置查看全部模型。</p>
          <ElButton @click="resetFilters">重置并查看全部模型</ElButton>
        </div>
        <p v-else-if="loaded">
          {{
            canCreate
              ? '尚未配置模型，请使用上方“新增模型”开始配置。'
              : '尚未配置模型，请联系有配置权限的管理员添加。'
          }}
        </p>
      </template>
      <ElTableColumn label="模型" min-width="220">
        <template #default="{ row }">
          <RouterLink :to="{ path: '/platform/models/' + row.id, query: listContext }">{{
            row.name
          }}</RouterLink>
          <div class="admin-field-help">
            {{ modelKindLabels[row.kind] ?? row.kind }} · {{ row.provider_id }}
          </div>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="model_id" label="模型标识" min-width="220" show-overflow-tooltip />
      <ElTableColumn label="状态与发布" min-width="170">
        <template #default="{ row }">
          <ElTag :type="row.enabled ? 'success' : 'info'">{{
            row.enabled ? '启用' : '停用'
          }}</ElTag>
          <div class="admin-field-help">
            发布修订 {{ row.published_revision ?? '尚未发布' }} ·
            {{ row.has_unpublished_changes ? '草稿待发布' : '与发布一致' }}
          </div>
        </template>
      </ElTableColumn>
    </ElTable>
  </AdminPage>
</template>

<style scoped>
.model-filter-actions {
  display: flex;
  gap: 12px;
}
.model-filter-actions .el-button + .el-button {
  margin-left: 0;
}
</style>
