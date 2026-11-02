<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { TableInstance } from 'element-plus'

const props = defineProps<{
  connectors: { instance_id: string; name: string }[]
  connectorId: string
  tools: { name: string; description?: string; unsupported_reason?: string | null }[]
  loading: boolean
  busy: boolean
  stale: boolean
  hasCatalog: boolean
}>()
const emit = defineEmits<{
  'update:connectorId': [id: string]
  discover: []
  confirm: [names: string[]]
}>()
const search = ref('')
const connectorSearch = ref('')
const connectorAvailable = computed(() =>
  props.connectors.some((connector) => connector.instance_id === props.connectorId)
)
const filteredConnectors = computed(() => {
  const keyword = connectorSearch.value.trim().toLocaleLowerCase()
  return props.connectors.filter((connector) =>
    [connector.name, connector.instance_id].some((value) =>
      value.toLocaleLowerCase().includes(keyword)
    )
  )
})
const catalogRevision = ref(0)
const filteredTools = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase()
  return props.tools.filter((tool) =>
    [tool.name, tool.description].some((value) => value?.toLocaleLowerCase().includes(keyword))
  )
})
const selected = ref<{ name: string }[]>([])
const table = ref<TableInstance>()
const removedSelection = ref<string[]>([])
let restoring = false
const names = computed(() => selected.value.map((tool) => tool.name))
watch([() => props.connectorId, () => props.tools], async ([connector, tools], [previous]) => {
  const saved = connector === previous ? names.value : []
  const retained = tools.filter((tool) => saved.includes(tool.name) && !tool.unsupported_reason)
  removedSelection.value =
    connector === previous
      ? saved.filter((name) => !retained.some((tool) => tool.name === name))
      : []
  if (connector !== previous) {
    search.value = ''
    connectorSearch.value = ''
  }
  selected.value = retained
  restoring = true
  catalogRevision.value++
  const revision = catalogRevision.value
  await nextTick()
  if (revision !== catalogRevision.value) return
  retained.forEach((tool) => table.value?.toggleRowSelection(tool, true))
  selected.value = retained
  restoring = false
})
</script>

<template>
  <div class="tool-onboarding">
    <ElForm label-position="top" @submit.prevent :disabled="busy || loading">
      <ElFormItem label="1. 选择连接器">
        <ElSelect
          :model-value="connectorId"
          filterable
          :filter-method="(query: string) => (connectorSearch = query)"
          popper-class="tool-onboarding-connectors"
          aria-label="工具来源连接器"
          placeholder="选择已配置的连接器"
          @update:model-value="emit('update:connectorId', $event)"
        >
          <ElOption
            v-for="connector in filteredConnectors"
            :key="connector.instance_id"
            :value="connector.instance_id"
            :label="connector.name"
            ><div>{{ connector.name }}</div>
            <small class="admin-field-help">标识：{{ connector.instance_id }}</small></ElOption
          >
        </ElSelect>
        <ElButton
          :disabled="!connectorAvailable || busy"
          :loading="loading"
          @click="emit('discover')"
        >
          读取工具
        </ElButton>
      </ElFormItem>
      <p
        v-if="connectorId && connectors.length && !connectorAvailable && !loading"
        class="admin-field-help"
        role="status"
      >
        所选连接器已不在当前目录中，请重新选择。
      </p>
    </ElForm>
    <h3>2. 勾选需要接入的工具</h3>
    <p v-if="stale && hasCatalog" class="admin-field-help" role="status">
      工具目录未刷新，当前显示的是上次读取结果。请重新读取工具后再确认接入。
    </p>
    <p v-if="removedSelection.length" class="admin-field-help" role="status">
      以下已选工具已不可接入，已移出选择：{{ removedSelection.join('、') }}。
    </p>
    <ElInput
      v-model="search"
      clearable
      aria-label="搜索待接入工具"
      placeholder="搜索工具名称或说明"
      :disabled="busy || loading"
    />
    <ElTable
      ref="table"
      :key="catalogRevision"
      :data="filteredTools"
      row-key="name"
      v-loading="loading"
      :empty-text="
        loading || busy
          ? '正在读取工具…'
          : tools.length
            ? '没有匹配的工具，请调整搜索词'
            : hasCatalog && !stale
              ? '此连接器未返回工具，请检查服务提供的工具目录'
              : stale
                ? '工具读取失败，请重新读取'
                : '选择连接器并读取工具后，在这里勾选需要接入的工具'
      "
      @selection-change="!restoring && (selected = $event)"
    >
      <ElTableColumn
        type="selection"
        reserve-selection
        width="48"
        :selectable="(row) => !busy && !loading && !stale && !row.unsupported_reason"
      />
      <ElTableColumn prop="name" label="工具" min-width="180" show-overflow-tooltip />
      <ElTableColumn prop="description" label="说明" min-width="240" show-overflow-tooltip />
      <ElTableColumn label="接入状态" min-width="260">
        <template #default="{ row }">{{ row.unsupported_reason || '可接入' }}</template>
      </ElTableColumn>
    </ElTable>
    <p v-if="names.length > 100" role="status" class="selection-error">
      每次请选择 1 至 100 项工具，请减少选择后再接入。
    </p>
    <p class="admin-field-help">
      已配置使用这些工具的智能体可自动调用；工具可能读写外部数据，请确认连接器与所选工具可信。
    </p>
    <ElButton
      type="primary"
      :disabled="!connectorAvailable || !names.length || names.length > 100 || loading || stale"
      :loading="busy"
      @click="emit('confirm', names)"
    >
      确认接入并授权调用 {{ names.length }} 项工具
    </ElButton>
  </div>
</template>

<style scoped>
.selection-error {
  color: var(--el-color-danger);
}

.tool-onboarding :deep(.el-form-item__content) {
  gap: 12px;
}
.tool-onboarding :deep(.el-select) {
  width: min(360px, 100%);
}
:global(.tool-onboarding-connectors .el-select-dropdown__item) {
  height: auto;
  line-height: 1.5;
  padding-block: 6px;
}
</style>
