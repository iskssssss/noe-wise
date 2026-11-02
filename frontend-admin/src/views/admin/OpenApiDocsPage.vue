<template>
  <AdminPage title="开放 API" description="接口参数和示例。凭据使用占位符，不会自动发送请求。"
    ><div class="admin-toolbar admin-card">
      <ElInput
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        clearable
        v-model="query"
        placeholder="搜索接口名称或路径"
      /><ElSelect v-model="method" clearable placeholder="全部请求方法" aria-label="请求方法"
        ><ElOption v-for="value in methods" :key="value" :value="value" :label="value" /></ElSelect
      ><ElInput
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="base"
        placeholder="API 基础地址"
        @blur="validateBase"
      />

      <FieldTip :id="formTipId + '-group-0'"
        >按接口名称或路径搜索；API 基础地址用于生成调用示例，请填写目标服务的实际地址。</FieldTip
      >
    </div>
    <FeedbackNotice v-if="error" :title="error" :closable="false" type="error" />
    <p class="admin-muted" role="status">{{ filtered.length }} 个匹配接口</p>
    <ElEmpty v-if="!filtered.length" description="没有匹配接口，试试其他关键词"
      ><ElButton @click="resetSearch">清空筛选</ElButton></ElEmpty
    >
    <div v-else class="api-docs-layout">
      <ElMenu :default-active="selectedId" @select="selectedId = $event"
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
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { computed, ref, useId, watch } from 'vue'
import { message } from '@skillforge/shared/feedback'
import { AdminPage } from '@skillforge/shared/admin'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import { chatEndpoints } from './apiDocs/chat'
import { agentEndpoints } from './apiDocs/agents'
import { runtimeEndpoints } from './apiDocs/runtime'
import { serviceEndpoints } from './apiDocs/services'
import { buildCurl, normalizeBaseUrl } from './apiDocs/examples'
const endpoints = [...chatEndpoints, ...agentEndpoints, ...runtimeEndpoints, ...serviceEndpoints],
  query = ref(''),
  method = ref(''),
  base = ref(window.location.origin),
  selectedId = ref(endpoints[0]?.id ?? ''),
  error = ref('')
const filtered = computed(() =>
    endpoints.filter(
      (e) =>
        (!method.value || e.method === method.value) &&
        (e.title + e.path).toLowerCase().includes(query.value.trim().toLowerCase())
    )
  ),
  selected = computed(() => endpoints.find((e) => e.id === selectedId.value))
function resetSearch() {
  query.value = ''
  method.value = ''
}
const methods = [...new Set(endpoints.map((endpoint) => endpoint.method))]
watch(filtered, (items) => {
  if (!items.some((item) => item.id === selectedId.value)) selectedId.value = items[0]?.id ?? ''
})
const normalizedBase = computed(() => normalizeBaseUrl(base.value))
const example = computed(() =>
  selected.value && normalizedBase.value ? buildCurl(selected.value, normalizedBase.value) : ''
)
function validateBase() {
  error.value = normalizedBase.value ? '' : '请输入有效 HTTP(S) 基础地址后生成调用示例'
}
async function copy() {
  try {
    if (!normalizeBaseUrl(base.value)) throw Error('请输入有效 HTTP(S) 基础地址')
    await navigator.clipboard.writeText(example.value)
    error.value = ''
    message.success('调用示例已复制')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '复制失败'
  }
}

const formTipId = useId()
</script>

<style scoped>
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
