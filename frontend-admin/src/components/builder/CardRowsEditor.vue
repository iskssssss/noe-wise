<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import WorkflowVariableSelect from './WorkflowVariableSelect.vue'
import NodeParameterValue from './NodeParameterValue.vue'
import {
  readArray,
  writeArray,
  objectEntries,
  writeObject,
  jsonString,
  insertAtSelection,
  type WorkflowVariableOption
} from './nodeEditing'
const props = defineProps<{
  modelValue?: string
  kind: 'fields' | 'actions'
  variables: WorkflowVariableOption[]
  readonly?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const items = ref<string[]>([])
const error = ref('')
const professional = ref(false)
watch(
  () => props.modelValue,
  (value) => {
    try {
      const next = readArray(value)
      next.forEach((item) => objectEntries(item))
      items.value = next
      error.value = ''
    } catch {
      error.value = '卡片配置无法按行显示，请切换“专业 JSON”检查格式。原内容已保留。'
      professional.value = true
    }
  },
  { immediate: true }
)
const label = computed(() => (props.kind === 'fields' ? '卡片字段' : '卡片操作'))
function get(index: number, key: string) {
  const raw = objectEntries(items.value[index]).find((entry) => entry.key === key)?.value
  return raw?.startsWith('"') ? jsonString(raw) : (raw ?? '')
}
function rawValue(index: number, key: string) {
  return objectEntries(items.value[index]).find((entry) => entry.key === key)?.value
}
function updateRaw(index: number, key: string, value?: string) {
  const fields = objectEntries(items.value[index]).filter((entry) => entry.key !== key)
  if (value !== undefined) fields.push({ key, value })
  const next = [...items.value]
  next[index] =
    '{' + fields.map((entry) => JSON.stringify(entry.key) + ': ' + entry.value).join(', ') + '}'
  emit('update:modelValue', writeArray(next))
}
function update(index: number, key: string, value: string) {
  const next = [...items.value]
  next[index] = writeObject(next[index], key, JSON.stringify(value))
  emit('update:modelValue', writeArray(next))
}
function add() {
  emit(
    'update:modelValue',
    writeArray([
      ...items.value,
      props.kind === 'fields' ? '{"label":"","value":""}' : '{"id":"","label":"","type":"submit"}'
    ])
  )
}
function remove(index: number) {
  selections.clear()
  emit('update:modelValue', writeArray(items.value.filter((_, row) => row !== index)))
}
const selections = new Map<number, { start: number; end: number; input: HTMLInputElement }>()
function rememberSelection(index: number, event: Event) {
  if (event.target instanceof HTMLInputElement) {
    const input = event.target
    selections.set(index, {
      start: input.selectionStart ?? input.value.length,
      end: input.selectionEnd ?? input.value.length,
      input
    })
  }
}
function insertVariable(index: number, key: string, value: string) {
  if (!value || props.readonly) return
  const selection = selections.get(index)
  const result = insertAtSelection(get(index, key), value, selection)
  update(index, key, result.text)
  void nextTick(() => {
    if (!selection?.input.isConnected) return
    selection.input.focus()
    selection.input.setSelectionRange(result.caret, result.caret)
    selections.set(index, { ...selection, start: result.caret, end: result.caret })
  })
}
</script>
<template>
  <section class="card-rows" :aria-label="label">
    <ElRadioGroup
      v-model="professional"
      :disabled="readonly"
      :aria-label="label + '编辑方式'"
      size="small"
      ><ElRadioButton :value="false" :disabled="!!error">字段编辑</ElRadioButton
      ><ElRadioButton :value="true">专业 JSON</ElRadioButton></ElRadioGroup
    >
    <ElAlert v-if="error" :title="error" type="warning" :closable="false" />
    <template v-if="!professional">
      <div v-for="(_item, index) in items" :key="index" class="card-row">
        <div class="card-row-heading">
          <strong>{{ kind === 'fields' ? '字段' : '操作' }} {{ index + 1 }}</strong
          ><ElButton text type="danger" :disabled="readonly" @click="remove(index)">删除</ElButton>
        </div>
        <ElFormItem v-if="kind === 'actions'" label="操作标识"
          ><ElInput
            :model-value="get(index, 'id')"
            :disabled="readonly"
            :aria-label="'操作' + (index + 1) + '标识'"
            @update:model-value="update(index, 'id', $event)"
        /></ElFormItem>
        <ElFormItem :label="kind === 'fields' ? '字段名称' : '按钮文字'"
          ><ElInput
            :model-value="get(index, 'label')"
            :disabled="readonly"
            :aria-label="label + (index + 1) + '名称'"
            @update:model-value="update(index, 'label', $event)"
        /></ElFormItem>
        <ElFormItem v-if="kind === 'actions'" label="操作类型"
          ><ElSelect
            :model-value="get(index, 'type') || 'submit'"
            :disabled="readonly"
            :aria-label="'操作' + (index + 1) + '类型'"
            @update:model-value="update(index, 'type', $event)"
            ><ElOption value="submit" label="提交响应" /><ElOption
              value="link"
              label="打开链接" /><ElOption
              v-if="get(index, 'type') && !['submit', 'link'].includes(get(index, 'type'))"
              :value="get(index, 'type')"
              :label="get(index, 'type') + '（原扩展类型）'" /></ElSelect
        ></ElFormItem>
        <ElFormItem v-if="kind === 'fields'" label="展示值"
          ><NodeParameterValue
            :model-value="rawValue(index, 'value')"
            :variables="variables"
            :readonly="readonly"
            :label="label + (index + 1) + '内容'"
            @update:model-value="updateRaw(index, 'value', $event)"
        /></ElFormItem>
        <ElFormItem v-else-if="get(index, 'type') === 'link'" label="链接地址"
          ><ElInput
            :model-value="get(index, 'url')"
            @blur="rememberSelection(index, $event)"
            @select="rememberSelection(index, $event)"
            @keyup="rememberSelection(index, $event)"
            @click="rememberSelection(index, $event)"
            :disabled="readonly"
            :aria-label="label + (index + 1) + '内容'"
            @update:model-value="update(index, 'url', $event)" /><WorkflowVariableSelect
            :options="variables"
            :disabled="readonly"
            :label="label + (index + 1) + '插入变量'"
            template
            @update:model-value="insertVariable(index, 'url', $event)"
        /></ElFormItem>
      </div>
      <ElButton :disabled="readonly" @click="add">{{
        kind === 'fields' ? '添加字段' : '添加操作'
      }}</ElButton>
      <p v-if="!items.length" class="admin-field-help">
        {{
          kind === 'fields'
            ? '尚无展示字段，按需添加。'
            : '尚无操作按钮。如需用户确认、提交或打开链接，请添加操作。'
        }}
      </p>
    </template>
    <CodeEditor
      v-else
      :model-value="modelValue || ''"
      :label="label + ' JSON'"
      language="json"
      height="200px"
      :readonly="readonly"
      @update:model-value="emit('update:modelValue', $event)"
    />
    <p class="admin-field-help">复杂值和扩展操作请在“专业 JSON”中编辑；按行修改会保留其他字段。</p>
  </section>
</template>
<style scoped>
.card-rows {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  min-width: 0;
}
.card-row {
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  padding: 12px;
}
.card-row-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.card-row :deep(.el-form-item) {
  margin-bottom: 12px;
}
.card-row :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}
</style>
