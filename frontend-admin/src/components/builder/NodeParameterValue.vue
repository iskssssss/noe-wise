<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import WorkflowVariableSelect from './WorkflowVariableSelect.vue'
import { jsonString, schemaType, type NodeSchema, type WorkflowVariableOption } from './nodeEditing'
const props = defineProps<{
  modelValue?: string
  schema?: NodeSchema
  variables: WorkflowVariableOption[]
  label: string
  readonly?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string | undefined] }>()
const mode = ref('fixed')
watch(
  () => props.modelValue,
  (value) => {
    if (value !== undefined) mode.value = /^"\$\{[^{}]+}"$/.test(value) ? 'variable' : 'fixed'
  },
  { immediate: true }
)
const type = computed(() => {
  if (props.schema) return schemaType(props.schema)
  const raw = props.modelValue?.trim() ?? ''
  if (raw === 'null') return 'null'
  if (['true', 'false'].includes(raw)) return 'boolean'
  if (raw.startsWith('{')) return 'object'
  if (raw.startsWith('[')) return 'array'
  if (/^-?\d/.test(raw)) return 'number'
  return 'string'
})
function changeType(value: string) {
  const defaults: Record<string, string> = {
    string: '""',
    number: '0',
    boolean: 'false',
    object: '{}',
    array: '[]',
    null: 'null'
  }
  emit('update:modelValue', defaults[value])
}
const valueText = computed(() =>
  type.value === 'string' && props.modelValue?.trim().startsWith('"')
    ? jsonString(props.modelValue)
    : (props.modelValue ?? '')
)
const variable = computed(() => jsonString(props.modelValue))
const variableTypeHelp = computed(() => {
  if (mode.value !== 'variable' || !variable.value) return ''
  const path = variable.value.replace(/^\$\{(.+)\}$/, '$1')
  const selected = props.variables.find((option) => option.path === path)
  if (!selected || selected.type === 'unknown')
    return '变量类型尚未确定，请核对实际结果是否符合参数要求。'
  const expected = schemaType(props.schema)
  if (!props.schema || expected === 'unknown') return ''
  const allowed = Array.isArray(props.schema.type) ? props.schema.type : [expected]
  const actual = selected.type.split(' | ')
  const compatible = actual.every(
    (kind) => allowed.includes(kind) || (kind === 'integer' && allowed.includes('number'))
  )
  return compatible
    ? ''
    : '类型可能不匹配：参数要求 ' + allowed.join(' | ') + '，变量声明为 ' + selected.type + '。'
})
const needsJson = computed(() => ['object', 'array', 'unknown'].includes(type.value))
function changeMode(value: string) {
  mode.value = value
  emit('update:modelValue', undefined)
}
function fixed(value: string) {
  emit('update:modelValue', type.value === 'string' ? JSON.stringify(value) : value || undefined)
}
</script>
<template>
  <div class="parameter-value">
    <ElRadioGroup
      :model-value="mode"
      :disabled="readonly"
      :aria-label="label + '取值方式'"
      size="small"
      @update:model-value="changeMode(String($event))"
      ><ElRadioButton value="fixed">固定值</ElRadioButton
      ><ElRadioButton value="variable">引用变量</ElRadioButton></ElRadioGroup
    >
    <ElSelect
      v-if="!schema && mode === 'fixed'"
      :model-value="type"
      :disabled="readonly"
      :aria-label="label + '值类型'"
      @update:model-value="changeType"
      ><ElOption
        v-for="kind in [
          { value: 'string', label: '文本' },
          { value: 'number', label: '数字' },
          { value: 'boolean', label: '是/否' },
          { value: 'object', label: '对象' },
          { value: 'array', label: '数组' },
          { value: 'null', label: '空值' }
        ]"
        :key="kind.value"
        :value="kind.value"
        :label="kind.label"
    /></ElSelect>
    <WorkflowVariableSelect
      v-if="mode === 'variable'"
      :model-value="variable"
      :options="variables"
      :disabled="readonly"
      :label="label + '变量'"
      template
      @update:model-value="emit('update:modelValue', $event ? JSON.stringify($event) : undefined)"
    />
    <ElSelect
      v-else-if="schema?.enum"
      :model-value="modelValue"
      :disabled="readonly"
      :aria-label="label"
      clearable
      @update:model-value="emit('update:modelValue', $event || undefined)"
      ><ElOption
        v-for="value in schema.enum"
        :key="JSON.stringify(value)"
        :value="JSON.stringify(value)"
        :label="typeof value === 'string' ? value : JSON.stringify(value)"
    /></ElSelect>
    <ElSelect
      v-else-if="type === 'boolean'"
      :model-value="modelValue"
      :disabled="readonly"
      :aria-label="label"
      clearable
      @update:model-value="emit('update:modelValue', $event || undefined)"
      ><ElOption value="true" label="是（true）" /><ElOption value="false" label="否（false）"
    /></ElSelect>
    <p v-else-if="type === 'null'" class="admin-field-help">null（空值）</p>
    <CodeEditor
      v-else-if="needsJson"
      :model-value="modelValue || ''"
      :label="label"
      language="json"
      height="120px"
      :readonly="readonly"
      @update:model-value="emit('update:modelValue', $event || undefined)"
    />
    <ElInput
      v-else
      :model-value="valueText"
      :disabled="readonly"
      :aria-label="label"
      :inputmode="['integer', 'number'].includes(type) ? 'decimal' : 'text'"
      @update:model-value="fixed"
    />
    <p v-if="variableTypeHelp" class="parameter-type-help" role="status">{{ variableTypeHelp }}</p>
    <p v-if="schema?.description" class="admin-field-help">{{ schema.description }}</p>
  </div>
</template>
<style scoped>
.parameter-value {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  width: 100%;
}
.parameter-value .admin-field-help {
  margin: 0;
}
.parameter-type-help {
  color: var(--el-color-warning-dark-2);
  font-size: 12px;
  line-height: 1.5;
  margin: 0;
}
</style>
