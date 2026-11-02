<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { AdminPage } from '@skillforge/shared/admin'
import { api, type ModelSummary } from '../../api'
import { useResource } from '@skillforge/shared/admin'
import { useAuthStore } from '../../stores/auth'
import { modelKindLabels } from './model-field-help'
const auth = useAuthStore()
const {
  data,
  loading: busy,
  error,
  reload,
  invalidate
} = useResource((signal) => api.admin.models.summaries({ signal, silent: true }))
const rows = computed<ModelSummary[]>(() => data.value ?? [])
const search = ref(''),
  kind = ref(''),
  enabled = ref(''),
  publication = ref('')
const loaded = computed(() => data.value !== undefined)
const filtered = computed(() =>
  rows.value.filter(
    (m) =>
      (!kind.value || m.kind === kind.value) &&
      (!enabled.value || String(m.enabled) === enabled.value) &&
      (!publication.value ||
        (publication.value === 'pending'
          ? m.has_unpublished_changes
          : !m.has_unpublished_changes)) &&
      `${m.name} ${m.model_id} ${m.provider_id}`.toLowerCase().includes(search.value.toLowerCase())
  )
)
function load() {
  void reload()
}
function resetFilters() {
  search.value = ''
  kind.value = ''
  enabled.value = ''
  publication.value = ''
}
watch(
  () => [auth.sessionIdentity, auth.identityVersion],
  () => {
    invalidate()
    data.value = undefined
    error.value = ''
    resetFilters()
    const identity = auth.identityVersion
    const session = auth.sessionIdentity
    void Promise.resolve().then(() => {
      if (session && auth.identityVersion === identity && auth.sessionIdentity === session) load()
    })
  },
  { flush: 'sync' }
)
onMounted(load)
</script>

<template>
  <AdminPage
    title="模型管理"
    description="配置对话、图像、音视频、向量与重排模型，检查待发布的修改"
  >
    <template #extra>
      <RouterLink v-permission="['foundation.models.create']" to="/platform/models/new">
        <ElButton type="primary">新增模型</ElButton>
      </RouterLink>
    </template>
    <div class="admin-filters filters">
      <ElInput
        v-model="search"
        aria-label="搜索模型"
        placeholder="搜索模型名称、标识或提供方"
        clearable
      />
      <ElSelect v-model="kind" aria-label="模型类型" placeholder="全部类型" clearable>
        <ElOption
          v-for="(label, value) in modelKindLabels"
          :key="value"
          :value="value"
          :label="label"
        />
      </ElSelect>
      <ElSelect v-model="enabled" aria-label="模型状态" placeholder="全部状态" clearable>
        <ElOption label="启用" value="true" />
        <ElOption label="停用" value="false" />
      </ElSelect>
      <ElSelect v-model="publication" aria-label="发布进度" placeholder="全部发布进度" clearable>
        <ElOption label="草稿待发布" value="pending" />
        <ElOption label="与发布一致" value="published" />
      </ElSelect>
      <div class="model-filter-actions">
        <ElButton :loading="busy" @click="load">刷新</ElButton>
        <ElButton @click="resetFilters">重置</ElButton>
      </div>
    </div>
    <FeedbackNotice v-if="error" :closable="false" type="error" :title="error" />
    <p v-if="loaded" class="admin-muted" role="status">
      {{ error ? '上次成功读取' : '当前' }} {{ filtered.length }} 个模型{{
        error ? '，刷新失败，数据可能已变化' : ' · 点击名称配置、测试与发布'
      }}
    </p>
    <ElTable
      table-layout="auto"
      :empty-text="
        busy
          ? '正在读取模型…'
          : error && !loaded
            ? '模型读取失败，请重试刷新'
            : '暂无符合条件的模型'
      "
      :data="filtered"
      v-loading="busy"
      style="width: 100%"
    >
      <ElTableColumn prop="name" label="名称" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <RouterLink :to="'/platform/models/' + row.id">{{ row.name }}</RouterLink>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="kind" label="类型" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ modelKindLabels[row.kind] ?? row.kind }}</template>
      </ElTableColumn>
      <ElTableColumn prop="provider_id" label="提供方" min-width="160" show-overflow-tooltip>
      </ElTableColumn>
      <ElTableColumn prop="model_id" label="模型标识" min-width="240" show-overflow-tooltip>
      </ElTableColumn>
      <ElTableColumn prop="enabled" label="状态" width="100">
        <template #default="{ row }">
          <ElTag :type="row.enabled ? 'success' : 'info'">{{
            row.enabled ? '启用' : '停用'
          }}</ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="发布修订" width="120"
        ><template #default="{ row }">{{
          row.published_revision ?? '尚未发布'
        }}</template></ElTableColumn
      ><ElTableColumn label="工作草稿" width="120"
        ><template #default="{ row }">{{
          row.has_unpublished_changes ? '待发布' : '与发布一致'
        }}</template></ElTableColumn
      >
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
