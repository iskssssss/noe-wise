<template>
  <section aria-label="工作流能力治理">
    <h3>能力治理</h3>
    <ElAlert
      v-if="!modelValue"
      title="尚未配置工作流的调用与输出规则。可先采用推荐值，再按实际用途调整。"
      type="warning"
      :closable="false"
    />
    <ElButton :disabled="readonly" @click="adopt">{{
      modelValue ? '重置为推荐配置' : '采用推荐配置'
    }}</ElButton>
    <p v-if="modelValue" class="admin-field-help">
      重置会替换当前的调用、输入和输出设置，请核对后再保存草稿。
    </p>
    <div v-if="modelValue" class="admin-form-grid">
      <ElFormItem v-for="field in booleanFields" :key="field.key" :label="field.label">
        <ElSelect
          :model-value="
            typeof modelValue[field.key] === 'boolean' ? Boolean(modelValue[field.key]) : undefined
          "
          :disabled="readonly"
          placeholder="未声明"
          @update:model-value="set(field.key, $event)"
        >
          <ElOption label="是" :value="true" /><ElOption label="否" :value="false" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="执行超时（毫秒）"
        ><ElInputNumber
          :model-value="
            typeof modelValue.timeout_ms === 'number' ? modelValue.timeout_ms : undefined
          "
          :disabled="readonly"
          :min="1"
          :precision="0"
          @update:model-value="set('timeout_ms', $event)"
      /></ElFormItem>
      <ElFormItem label="导出字段（逗号分隔）"
        ><ElInput
          :model-value="
            Array.isArray(modelValue.export_fields) ? modelValue.export_fields.join(',') : ''
          "
          :disabled="readonly"
          @update:model-value="
            set(
              'export_fields',
              String($event)
                .split(/[,，]/)
                .map((v) => v.trim())
                .filter(Boolean)
            )
          "
      /></ElFormItem>
    </div>
    <template v-if="modelValue">
      <ElFormItem
        v-for="field in ['input_schema', 'output_schema']"
        :key="field"
        :label="field === 'input_schema' ? '工作流输入 Schema' : '工作流输出 Schema'"
      >
        <ElInput
          :model-value="schemaText[field]"
          type="textarea"
          :rows="7"
          :disabled="readonly"
          :aria-label="field"
          @update:model-value="updateSchema(field, $event)"
        />
        <p v-if="errors[field]" role="alert" class="schema-error">{{ errors[field] }}</p>
      </ElFormItem>
    </template>
    <p class="admin-field-help">
      输入字段通过 state.ctx_参数名 引用；输出 Schema
      与导出字段定义对外返回的结果。完成配置后请校验工作流。
    </p>
  </section>
</template>
<script setup lang="ts">
import { reactive, watch } from 'vue'
import { recommendedWorkflowCapability } from '../../utils/workflow'
const props = defineProps<{ modelValue?: Record<string, unknown> | null; readonly?: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
  'validity-change': [valid: boolean]
}>()
const schemaText = reactive<Record<string, string>>({}),
  errors = reactive<Record<string, string>>({})
const booleanFields = [
  { key: 'model_invocable', label: '模型可调用' },
  { key: 'user_invocable', label: '用户可调用' },
  { key: 'supports_hitl', label: '包含人工介入' },
  { key: 'concurrency_safe', label: '可安全并发' }
]
watch(
  () => props.modelValue,
  (value) => {
    for (const key of ['input_schema', 'output_schema']) {
      if (!errors[key])
        schemaText[key] = value?.[key] === undefined ? '' : JSON.stringify(value[key], null, 2)
    }
  },
  { immediate: true, deep: true }
)
function set(key: string, value: unknown) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
function adopt() {
  for (const key of Object.keys(errors)) delete errors[key]
  emit('update:modelValue', recommendedWorkflowCapability())
  emit('validity-change', true)
}
function updateSchema(key: string, text: string) {
  schemaText[key] = text
  try {
    const value: unknown = JSON.parse(text)
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('必须为 JSON 对象')
    delete errors[key]
    set(key, value)
  } catch {
    errors[key] = '格式不正确，请填写 JSON 对象。修正前仍使用上一次有效配置。'
  }
  emit('validity-change', !Object.values(errors).some(Boolean))
}
</script>
<style scoped>
.schema-error {
  color: var(--el-color-danger);
}
</style>
