<template>
  <AdminPage title="开放 API" description="接口参数和示例。凭据使用占位符，不会自动发送请求。"
    ><ElForm class="admin-card api-docs-filters" label-position="top" @submit.prevent>
      <ElFormItem label="搜索接口">
        <ElInput
          v-described-by="formTipId + '-group-0'"
          :aria-describedby="formTipId + '-group-0'"
          clearable
          v-model="query"
          placeholder="搜索接口名称或路径"
      /></ElFormItem>
      <ElFormItem label="请求方法">
        <ElSelect v-model="method" clearable placeholder="全部请求方法" aria-label="请求方法"
          ><ElOption
            v-for="value in methods"
            :key="value"
            :value="value"
            :label="value" /></ElSelect
      ></ElFormItem>
      <ElFormItem label="API 基础地址" :error="baseError">
        <ElInput
          v-described-by="formTipId + '-group-0'"
          :aria-describedby="formTipId + '-group-0'"
          v-model="base"
          placeholder="API 基础地址"
          @blur="validateBase"
      /></ElFormItem>

      <FieldTip :id="formTipId + '-group-0'"
        >按接口名称或路径搜索；API 基础地址用于生成调用示例，请填写目标服务的实际地址。</FieldTip
      >
    </ElForm>
    <p class="admin-muted" role="status">{{ filtered.length }} 个匹配接口</p>
    <ElEmpty v-if="!filtered.length" description="没有匹配接口，试试其他关键词"
      ><ElButton @click="resetSearch">清空筛选</ElButton></ElEmpty
    >
    <div v-else class="api-docs-layout">
      <ElMenu :default-active="selected?.id" @select="updateQuery({ endpoint: $event })"
        ><ElMenuItem v-for="endpoint in filtered" :key="endpoint.id" :index="endpoint.id">{{
          endpoint.title
        }}</ElMenuItem></ElMenu
      ><ElCard v-if="selected" class="admin-card api-docs-content"
        ><h2>{{ selected.title }}</h2>
        <div class="admin-toolbar">
          <ElTag>{{ selected.method }}</ElTag
          ><code>{{ selected.path }}</code>
        </div>
        <p>{{ selected.summary }}</p>
        <ElDescriptions border :column="1"
          ><ElDescriptionsItem label="认证">{{ selected.auth }}</ElDescriptionsItem
          ><ElDescriptionsItem label="返回类型">{{ selected.responseType }}</ElDescriptionsItem
          ><ElDescriptionsItem v-if="selected.scopes?.length" label="权限">{{
            selected.scopes.join(', ')
          }}</ElDescriptionsItem></ElDescriptions
        >
        <h3>请求参数</h3>
        <ElTable
          table-layout="auto"
          :data="[...(selected.parameters ?? []), ...(selected.body ?? [])]"
          empty-text="此接口没有额外的路径、查询或请求体参数"
          ><ElTableColumn
            min-width="200"
            show-overflow-tooltip
            prop="name"
            label="名称" /><ElTableColumn
            min-width="140"
            show-overflow-tooltip
            prop="type"
            label="类型" /><ElTableColumn width="80" label="必填"
            ><template #default="{ row }">{{ row.required ? '是' : '否' }}</template></ElTableColumn
          ><ElTableColumn min-width="280" show-overflow-tooltip prop="description" label="说明"
        /></ElTable>
        <h3>cURL 示例</h3>
        <CodeEditor :model-value="example" language="shell" readonly height="300px" /><ElButton
          @click="copy"
          :disabled="!normalizedBase"
          >复制示例</ElButton
        >
        <h3>响应示例</h3>
        <CodeEditor
          :model-value="JSON.stringify(selected.responseExample, null, 2)"
          language="json"
          readonly
          height="260px" /><ElAlert
          v-for="note in selected.notes ?? []"
          :key="note"
          :title="note"
          type="info"
          :closable="false"
      /></ElCard></div
  ></AdminPage>
</template>
<script setup lang="ts">
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { computed, ref, useId, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { message } from '@skillforge/shared/feedback'
import { AdminPage } from '@skillforge/shared/admin'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import { chatEndpoints } from './apiDocs/chat'
import { agentEndpoints } from './apiDocs/agents'
import { runtimeEndpoints } from './apiDocs/runtime'
import { serviceEndpoints } from './apiDocs/services'
import { buildCurl, normalizeBaseUrl } from './apiDocs/examples'
const endpoints = [...chatEndpoints, ...agentEndpoints, ...runtimeEndpoints, ...serviceEndpoints]
const route = useRoute(),
  router = useRouter()
const query = computed({
  get: () => (typeof route.query.q === 'string' ? route.query.q : ''),
  set: (value: string) => updateQuery({ q: value || undefined })
})
const methods = [...new Set(endpoints.map((endpoint) => endpoint.method))]
const method = computed({
  get: () => methods.find((value) => value === route.query.method) ?? '',
  set: (value: string) => updateQuery({ method: value || undefined })
})
const base = ref(typeof route.query.base === 'string' ? route.query.base : window.location.origin)
const baseTouched = ref(false)
watch(
  () => route.query.base,
  (value) => {
    base.value = typeof value === 'string' ? value : window.location.origin
    baseTouched.value = false
  }
)
let pendingQuery: LocationQueryRaw | undefined
function updateQuery(changes: LocationQueryRaw) {
  // 连续选择接口和编辑地址时，后一个动作仍须带上尚未完成导航的前一个选择。
  const next = { ...(pendingQuery ?? route.query), ...changes }
  pendingQuery = next
  void router.replace({ query: next }).then(() => {
    if (pendingQuery === next) pendingQuery = undefined
  })
}
const filtered = computed(() =>
    endpoints.filter(
      (e) =>
        (!method.value || e.method === method.value) &&
        (e.title + e.path).toLowerCase().includes(query.value.trim().toLowerCase())
    )
  ),
  selected = computed(
    () => filtered.value.find((e) => e.id === route.query.endpoint) ?? filtered.value[0]
  )
function resetSearch() {
  updateQuery({ q: undefined, method: undefined, endpoint: undefined })
}
const normalizedBase = computed(() => normalizeBaseUrl(base.value))
const baseError = computed(() =>
  baseTouched.value && !normalizedBase.value ? '请输入有效 HTTP(S) 基础地址后生成调用示例' : ''
)
const example = computed(() =>
  selected.value && normalizedBase.value ? buildCurl(selected.value, normalizedBase.value) : ''
)
function validateBase() {
  baseTouched.value = true
  // 只将已校验的地址写入导航上下文，凭据、查询串和无效输入留在当前字段。
  if (normalizedBase.value) updateQuery({ base: normalizedBase.value })
}
async function copy() {
  try {
    await navigator.clipboard.writeText(example.value)
    message.success('调用示例已复制')
  } catch {
    message.error('复制失败，请选中调用示例后手动复制')
  }
}

const formTipId = useId()
</script>

<style scoped>
.api-docs-filters {
  padding: 20px;
}
.api-docs-layout {
  display: grid;
  grid-template-columns: minmax(180px, 240px) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
.api-docs-layout > .el-menu {
  max-height: 70vh;
  overflow: auto;
  border: 1px solid var(--admin-border);
  border-radius: 12px;
}
.api-docs-layout :deep(.el-menu-item) {
  white-space: normal;
  height: auto;
  min-height: 44px;
  line-height: 1.5;
  padding-block: 10px;
}
.api-docs-content {
  min-width: 0;
}
.api-docs-content code {
  overflow-wrap: anywhere;
}
@media (max-width: 900px) {
  .api-docs-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .api-docs-layout > .el-menu {
    max-height: 240px;
  }
}
</style>
