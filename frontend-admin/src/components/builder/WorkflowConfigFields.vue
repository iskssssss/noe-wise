<template>
  <section aria-label="安装参数声明">
    <h3>安装参数</h3>
    <p class="admin-field-help">
      设置安装时需要填写的参数、默认值和约束。节点可通过变量选择器引用
      config.参数名。切换参数类型会清空原默认值。
    </p>
    <ElAlert v-if="parseError" :title="parseError" type="error" :closable="false" />
    <template v-if="!parseError">
      <div v-for="(field, index) in fields" :key="index" class="config-field">
        <div class="admin-form-grid">
          <ElFormItem label="参数标识"
            ><ElInput
              :model-value="field.key"
              :disabled="readonly"
              @update:model-value="patch(index, 'key', $event)"
          /></ElFormItem>
          <ElFormItem label="展示名称"
            ><ElInput
              :model-value="field.label"
              :disabled="readonly"
              @update:model-value="patch(index, 'label', $event)"
          /></ElFormItem>
          <ElFormItem label="类型"
            ><ElSelect
              :model-value="field.type"
              :disabled="readonly"
              @update:model-value="changeType(index, $event)"
              ><ElOption v-for="type in types" :key="type" :label="type" :value="type" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="必填"
            ><ElSwitch
              :model-value="field.required === true"
              :disabled="readonly"
              @update:model-value="patch(index, 'required', $event)"
          /></ElFormItem>
          <ElFormItem label="默认值">
            <ElSelect
              v-if="field.type === 'boolean'"
              :model-value="typeof field.default === 'boolean' ? field.default : undefined"
              clearable
              :disabled="readonly"
              @update:model-value="patch(index, 'default', $event === '' ? undefined : $event)"
              ><ElOption label="是" :value="true" /><ElOption label="否" :value="false"
            /></ElSelect>
            <ElInputNumber
              v-else-if="['integer', 'number'].includes(field.type)"
              :model-value="typeof field.default === 'number' ? field.default : undefined"
              :disabled="readonly"
              :precision="field.type === 'integer' ? 0 : undefined"
              @update:model-value="patch(index, 'default', $event)"
            />
            <ElInput
              v-else
              :model-value="
                typeof field.default === 'string' || typeof field.default === 'number'
                  ? field.default
                  : ''
              "
              :disabled="readonly"
              @update:model-value="patch(index, 'default', $event)"
            />
          </ElFormItem>
          <ElFormItem v-if="field.type === 'enum'" label="可选值（每行一项）"
            ><ElInput
              :model-value="field.enum_values?.join('\n')"
              type="textarea"
              :disabled="readonly"
              @update:model-value="patch(index, 'enum_values', String($event).split('\n'))"
          /></ElFormItem>
          <template v-if="['integer', 'number'].includes(field.type)"
            ><ElFormItem label="最小值"
              ><ElInputNumber
                :model-value="field.min"
                :disabled="readonly"
                @update:model-value="patch(index, 'min', $event)" /></ElFormItem
            ><ElFormItem label="最大值"
              ><ElInputNumber
                :model-value="field.max"
                :disabled="readonly"
                @update:model-value="patch(index, 'max', $event)" /></ElFormItem
          ></template>
          <ElFormItem label="说明"
            ><ElInput
              :model-value="field.description"
              :disabled="readonly"
              @update:model-value="patch(index, 'description', $event)"
          /></ElFormItem>
        </div>
        <ElButton :disabled="readonly" type="danger" link @click="remove(index)"
          >删除参数 {{ field.key || index + 1 }}</ElButton
        >
      </div>
      <ElButton :disabled="readonly" @click="add">添加安装参数</ElButton>
    </template>
    <details>
      <summary>高级 JSON 编辑</summary>
      <ElInput
        :model-value="modelValue"
        type="textarea"
        :rows="8"
        :disabled="readonly"
        aria-label="安装参数声明 JSON"
        @update:model-value="emit('update:modelValue', $event)"
      />
    </details>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { WorkflowConfigField } from '../../utils/workflow'
const props = defineProps<{ modelValue: string; readonly?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const types = ['string', 'textarea', 'boolean', 'integer', 'number', 'enum']
const parsed = computed(() => {
  try {
    const data: unknown = JSON.parse(props.modelValue || '[]')
    if (
      !Array.isArray(data) ||
      data.some(
        (v) =>
          !v ||
          typeof v !== 'object' ||
          Array.isArray(v) ||
          (v.enum_values !== undefined &&
            (!Array.isArray(v.enum_values) ||
              v.enum_values.some((value: unknown) => typeof value !== 'string')))
      )
    )
      throw Error()
    return { fields: data as WorkflowConfigField[], error: '' }
  } catch {
    return {
      fields: [],
      error:
        '安装参数格式不正确。请展开“高级 JSON 编辑”，将参数对象放在数组中；可选值须为字符串数组。'
    }
  }
})
const fields = computed(() => parsed.value.fields),
  parseError = computed(() => parsed.value.error)
function write(value: WorkflowConfigField[]) {
  emit('update:modelValue', JSON.stringify(value, null, 2))
}
function patch(index: number, key: string, value: unknown) {
  const next = fields.value.map((field) => ({ ...field }))
  if (value === undefined || value === null) delete next[index][key]
  else next[index][key] = value
  write(next)
}
function changeType(index: number, type: WorkflowConfigField['type']) {
  const next = fields.value.map((field) => ({ ...field }))
  next[index].type = type
  delete next[index].default
  write(next)
}
function add() {
  write([...fields.value, { key: '', label: '', type: 'string', required: false }])
}
function remove(index: number) {
  write(fields.value.filter((_, i) => i !== index))
}
</script>
<style scoped>
.config-field {
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color);
}
details {
  margin-top: 16px;
}
summary {
  cursor: pointer;
}
</style>
