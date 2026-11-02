<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import WorkflowVariableSelect from './WorkflowVariableSelect.vue'
import type { WorkflowVariableOption } from './nodeEditing'
const props = defineProps<{
  modelValue: string
  variables: WorkflowVariableOption[]
  readonly?: boolean
  label: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const mode = ref('fixed')
const kind = ref('string')
let lastEmitted: string | undefined
watch(
  () => props.modelValue,
  (value) => {
    const echoed = value === lastEmitted
    lastEmitted = undefined
    if (!value || echoed) return
    if (/^(?:state|extracted|config|runtime|user)\.[A-Za-z_][\w.]*$/.test(value))
      mode.value = 'variable'
    else if (value.includes('${') || value.startsWith('[') || value.startsWith('{'))
      mode.value = 'expression'
    else mode.value = 'fixed'
    kind.value = ['true', 'false'].includes(value)
      ? 'boolean'
      : value === 'null'
        ? 'null'
        : /^-?(?:\d|$)/.test(value)
          ? 'number'
          : 'string'
  },
  { immediate: true }
)
function update(value: string) {
  lastEmitted = value
  emit('update:modelValue', value)
}
const fixedText = computed(() => {
  try {
    const value: unknown = JSON.parse(props.modelValue)
    return typeof value === 'string' ? value : props.modelValue
  } catch {
    return props.modelValue.startsWith("'") && props.modelValue.endsWith("'")
      ? props.modelValue.slice(1, -1)
      : props.modelValue
  }
})
function changeMode(value: string) {
  mode.value = value
  update('')
}
function changeKind(value: string) {
  kind.value = value
  update(
    value === 'number' ? '0' : value === 'boolean' ? 'false' : value === 'null' ? 'null' : '""'
  )
}
</script>
<template>
  <div class="assign-value">
    <ElRadioGroup
      :model-value="mode"
      :disabled="readonly"
      :aria-label="label + '取值方式'"
      size="small"
      @update:model-value="changeMode(String($event))"
      ><ElRadioButton value="fixed">固定值</ElRadioButton
      ><ElRadioButton value="variable">引用变量</ElRadioButton
      ><ElRadioButton value="expression">专业表达式</ElRadioButton></ElRadioGroup
    >
    <WorkflowVariableSelect
      v-if="mode === 'variable'"
      :model-value="modelValue"
      :options="variables"
      :disabled="readonly"
      :label="label + '来源变量'"
      @update:model-value="update($event)"
    />
    <template v-else-if="mode === 'fixed'"
      ><ElSelect
        :model-value="kind"
        :disabled="readonly"
        :aria-label="label + '固定值类型'"
        @update:model-value="changeKind"
        ><ElOption value="string" label="文本" /><ElOption value="number" label="数字" /><ElOption
          value="boolean"
          label="是/否" /><ElOption value="null" label="空值" /></ElSelect
      ><ElSelect
        v-if="kind === 'boolean'"
        :model-value="modelValue"
        :disabled="readonly"
        :aria-label="label + '固定值'"
        @update:model-value="update($event)"
        ><ElOption value="true" label="是（true）" /><ElOption
          value="false"
          label="否（false）" /></ElSelect
      ><ElInput
        v-else-if="kind !== 'null'"
        :model-value="fixedText"
        :disabled="readonly"
        :aria-label="label + '固定值'"
        :inputmode="kind === 'number' ? 'decimal' : 'text'"
        @update:model-value="update(kind === 'string' ? JSON.stringify($event) : $event)"
      />
      <p v-else class="admin-field-help">null（空值）</p></template
    >
    <template v-else
      ><ElInput
        :model-value="modelValue"
        :disabled="readonly"
        :aria-label="label + '表达式'"
        type="textarea"
        :rows="3"
        @update:model-value="update($event)"
      />
      <p class="admin-field-help">
        直接填写变量路径可保留数字、对象等原值类型；使用 ${…} 拼接后会变成文本。
      </p></template
    >
  </div>
</template>
<style scoped>
.assign-value {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  width: 100%;
}
</style>
