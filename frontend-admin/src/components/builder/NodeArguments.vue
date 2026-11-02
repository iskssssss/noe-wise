<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import NodeParameterValue from './NodeParameterValue.vue'
import {
  objectEntries,
  schemaType,
  type NodeSchema,
  type RawJsonEntry,
  type WorkflowVariableOption
} from './nodeEditing'
const props = defineProps<{
  modelValue?: string
  schema?: NodeSchema
  variables: WorkflowVariableOption[]
  label: string
  readonly?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const entries = ref<RawJsonEntry[]>([])
const parseError = ref('')
const advanced = ref(false)
const newKey = ref('')
const keyError = ref('')
watch(
  () => props.modelValue,
  (value) => {
    try {
      entries.value = objectEntries(value)
      parseError.value = ''
    } catch {
      parseError.value = '参数格式不正确。请展开“专业 JSON 编辑”补齐对象格式，再保存。'
      advanced.value = true
    }
  },
  { immediate: true }
)
const names = computed(() => [
  ...new Set([
    ...Object.keys(props.schema?.properties ?? {}),
    ...entries.value.map((entry) => entry.key)
  ])
])
const canAdd = computed(() => props.schema?.additionalProperties !== false)
function update(key: string, value?: string) {
  const next = entries.value.filter((entry) => entry.key !== key)
  if (value !== undefined) next.push({ key, value })
  entries.value = next
  emit(
    'update:modelValue',
    '{' + next.map((entry) => JSON.stringify(entry.key) + ': ' + entry.value).join(', ') + '}'
  )
}
function add() {
  const key = newKey.value.trim()
  if (!key || names.value.includes(key)) {
    keyError.value = '请输入不重复的参数名'
    return
  }
  update(key, '""')
  newKey.value = ''
  keyError.value = ''
}
</script>
<template>
  <section class="node-arguments" :aria-label="label">
    <ElAlert v-if="parseError" :title="parseError" type="error" :closable="false" />
    <p v-if="!schema" class="admin-field-help">
      尚未取得输入字段定义，请按工具或脚本要求填写。对象和数组可用 JSON 编辑。
    </p>
    <template v-if="!parseError">
      <div v-for="name in names" :key="name" class="argument-row">
        <div class="argument-heading">
          <label
            >{{ schema?.properties?.[name]?.title || name
            }}<span v-if="schema?.required?.includes(name)" class="argument-required">
              · 必填</span
            ></label
          ><span class="admin-field-help"
            >{{ name }} ·
            {{
              schema?.properties?.[name] ? schemaType(schema.properties[name]) : '未声明类型'
            }}</span
          ><ElButton
            v-if="!schema?.properties?.[name]"
            text
            type="danger"
            :disabled="readonly"
            :aria-label="'删除参数 ' + name"
            @click="update(name)"
            >删除</ElButton
          >
        </div>
        <NodeParameterValue
          :model-value="entries.find((entry) => entry.key === name)?.value"
          :schema="schema?.properties?.[name]"
          :variables="variables"
          :readonly="readonly"
          :label="label + ' · ' + name"
          @update:model-value="update(name, $event)"
        />
      </div>
      <div v-if="canAdd && !readonly" class="argument-add">
        <ElInput
          v-model="newKey"
          :aria-label="label + '新参数名'"
          placeholder="参数名"
          @keydown.enter.prevent="add"
        /><ElButton @click="add">添加参数</ElButton>
      </div>
      <p v-if="keyError" class="admin-field-help" role="alert">{{ keyError }}</p>
      <p v-if="!names.length && !canAdd" class="admin-field-help">输入定义未开放参数，无需填写。</p>
    </template>
    <details :open="advanced" @toggle="advanced = ($event.target as HTMLDetailsElement).open">
      <summary>专业 JSON 编辑</summary>
      <CodeEditor
        :model-value="modelValue || ''"
        :label="label + ' JSON'"
        language="json"
        height="180px"
        :readonly="readonly"
        @update:model-value="emit('update:modelValue', $event)"
      />
      <p class="admin-field-help">
        只保存你已填写的参数；字段定义中的默认值不会自动填入。已有扩展参数可在上方继续编辑。
      </p>
    </details>
  </section>
</template>
<style scoped>
.node-arguments {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
  min-width: 0;
}
.argument-row {
  padding: 12px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
.argument-heading {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 8px;
  margin-bottom: 8px;
}
.argument-heading label {
  font-weight: 600;
  font-size: 13px;
}
.argument-heading .el-button {
  margin-left: auto;
}
.argument-required {
  color: var(--el-color-danger);
  font-weight: 400;
}
.argument-add {
  display: flex;
  gap: 8px;
}
.node-arguments summary {
  cursor: pointer;
  font-size: 13px;
  min-height: 28px;
}
.node-arguments summary:focus-visible {
  outline: 2px solid var(--admin-accent);
  outline-offset: 3px;
}
</style>
