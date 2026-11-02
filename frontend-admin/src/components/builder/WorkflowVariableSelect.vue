<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { customVariableError, type WorkflowVariableOption } from './nodeEditing'
const props = withDefaults(
  defineProps<{
    modelValue?: string
    options: WorkflowVariableOption[]
    label?: string
    disabled?: boolean
    template?: boolean
    clearable?: boolean
  }>(),
  { label: '选择变量', clearable: true }
)
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const path = computed(() => props.modelValue?.replace(/^\$\{(.+)\}$/, '$1') ?? '')
const selectedOption = computed(() => {
  const exact = props.options.find((option) => option.path === path.value)
  if (exact || !path.value || customVariableError(path.value, props.options)) return exact
  return {
    path: path.value,
    type: 'unknown',
    availability: 'conditional',
    description: '此字段未在输出定义中声明，请核对真实结果。'
  }
})
interface VariableTreeNode {
  value: string
  label: string
  searchText: string
  disabled: boolean
  option?: WorkflowVariableOption
  children: VariableTreeNode[]
}
const tree = computed(() => {
  const groups = new Map<string, VariableTreeNode>()
  const entries = new Map<string, VariableTreeNode>()
  for (const option of props.options) {
    if (!groups.has(option.source))
      groups.set(option.source, {
        value: 'group:' + option.source,
        label: option.source,
        searchText: option.source.toLowerCase(),
        disabled: true,
        children: []
      })
    entries.set(option.path, {
      value: option.path,
      label: option.label + ' · ' + option.path,
      searchText: [option.source, option.label, option.path, option.type].join(' ').toLowerCase(),
      disabled: false,
      option,
      children: []
    })
  }
  for (const option of props.options) {
    const item = entries.get(option.path)!
    const parent = option.parentPath ? entries.get(option.parentPath) : undefined
    if (parent && parent.option?.source === option.source) parent.children.push(item)
    else groups.get(option.source)!.children.push(item)
  }
  if (path.value && !entries.has(path.value))
    groups.set('original', {
      value: 'group:original',
      label: '原引用（当前未声明）',
      searchText: path.value.toLowerCase(),
      disabled: true,
      children: [
        {
          value: path.value,
          label: path.value,
          searchText: path.value.toLowerCase(),
          disabled: false,
          children: []
        }
      ]
    })
  const priority = (group: VariableTreeNode) => {
    if (props.options.some((option) => option.source === group.label && option.sourceNodeId))
      return 0
    if (group.label === '工作流输入') return 1
    if (group.label === '工作流配置') return 2
    if (group.label === '运行上下文') return 5
    if (group.label === '用户信息') return 4
    return 3
  }
  return [...groups.values()].sort((left, right) => priority(left) - priority(right))
})
const expandedKeys = computed(() => {
  const upstream = props.options.filter((option) => option.sourceNodeId)
  return [
    ...new Set([
      ...upstream.map((option) => 'group:' + option.source),
      ...upstream.map((option) => option.path)
    ])
  ]
})
function filterNode(query: string, data: Record<string, unknown>): boolean {
  return typeof data.searchText === 'string' && data.searchText.includes(query.trim().toLowerCase())
}
function select(value: string | undefined) {
  if (props.disabled || value?.startsWith('group:')) return
  emit('update:modelValue', value && props.template ? '${' + value + '}' : value || '')
}
const customPath = ref('')
const attempted = ref(false)
const customHelp = useId()
const customError = computed(() => customVariableError(customPath.value.trim(), props.options))
function useCustom() {
  attempted.value = true
  if (!customError.value && !props.disabled) {
    select(customPath.value.trim())
    customPath.value = ''
    attempted.value = false
  }
}
</script>
<template>
  <div class="workflow-variable-select">
    <ElTreeSelect
      :model-value="path || undefined"
      :data="tree"
      :disabled="disabled"
      :clearable="clearable"
      :aria-label="label"
      :filter-node-method="filterNode"
      :render-after-expand="false"
      check-strictly
      filterable
      :default-expanded-keys="expandedKeys"
      placeholder="搜索来源节点、字段或路径"
      popper-class="workflow-variable-popper"
      @update:model-value="select"
    >
      <template #default="{ data }">
        <span class="variable-option">
          <span class="variable-heading"
            ><span>{{ data.option?.label || data.label }}</span
            ><small v-if="data.option"
              >{{ data.option.type
              }}<span v-if="data.option.availability === 'conditional'"> · 可能无值</span></small
            ></span
          >
          <small v-if="data.option" class="variable-path">{{ data.option.path }}</small>
          <small v-if="data.option?.description" class="variable-description">{{
            data.option.description
          }}</small>
        </span>
      </template>
    </ElTreeSelect>
    <p v-if="path && !selectedOption" class="variable-error" role="status">
      未找到此引用的可达来源。请检查上游连接、输出字段或自定义路径；原引用尚未更改。
    </p>
    <p
      v-else-if="selectedOption?.availability === 'conditional'"
      class="admin-field-help"
      role="status"
    >
      此引用可能没有值：{{ selectedOption.description || '取决于实际执行分支或字段是否存在。' }}
    </p>
    <p
      v-if="!options.some((option) => option.sourceNodeId)"
      class="admin-field-help variable-empty"
    >
      尚无可引用的上游输出。可先连接产出结果的节点，或选择工作流输入与上下文变量。
    </p>
    <details class="variable-custom">
      <summary>自定义对象字段路径</summary>
      <p :id="customHelp" class="admin-field-help">
        字段未列出时，可整体引用结果，或填写实际存在的对象字段路径。未执行的分支和可选字段可能没有值；数组请整体引用。
      </p>
      <ElInput
        v-model="customPath"
        :disabled="disabled"
        :aria-label="label + '自定义路径'"
        :aria-describedby="customHelp"
        placeholder="例如 state.查询结果.订单.编号"
        @keydown.enter.stop.prevent="useCustom"
      />
      <p v-if="attempted && customError" class="variable-error" role="status">{{ customError }}</p>
      <ElButton :disabled="disabled || !customPath.trim()" size="small" @click="useCustom"
        >使用此路径</ElButton
      >
    </details>
  </div>
</template>
<style scoped>
.workflow-variable-select {
  display: flex;
  flex-direction: column;
  width: 100%;
  min-width: 0;
  gap: 6px;
}
.variable-custom {
  font-size: 12px;
  color: var(--admin-muted);
}
.variable-custom summary {
  cursor: pointer;
  width: fit-content;
}
.variable-custom .el-input,
.variable-custom .el-button {
  margin-top: 6px;
}
.variable-option {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
  padding-block: 5px;
  line-height: 1.4;
}
.variable-heading {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.variable-heading small,
.variable-path,
.variable-description {
  color: var(--admin-muted);
  white-space: normal;
  overflow-wrap: anywhere;
}
.variable-error {
  color: var(--el-color-danger);
}
</style>
<style>
.workflow-variable-popper {
  max-width: min(640px, calc(100vw - 32px));
}
.workflow-variable-popper .el-tree-node__content {
  height: auto;
  min-height: 36px;
  align-items: baseline;
}
.workflow-variable-popper .el-select-dropdown__item {
  height: auto;
  min-height: 36px;
  line-height: 1.4;
}
</style>
