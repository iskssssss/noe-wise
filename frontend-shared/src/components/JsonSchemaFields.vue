<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { ElAlert, ElButton, ElCheckbox, ElInput, ElOption, ElSelect } from 'element-plus'
import CodeEditor from './CodeEditor.vue'

const props = withDefaults(
  defineProps<{ modelValue: string; label: string; readonly?: boolean; describedBy?: string }>(),
  { readonly: false, describedBy: '' }
)
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
type Schema = Record<string, unknown>
const rawMode = ref(false)
const newName = ref('')
const error = ref('')
const nameRevision = ref(0)
const id = useId()
const types = [
  { value: 'string', label: '文本' },
  { value: 'integer', label: '整数' },
  { value: 'number', label: '数值' },
  { value: 'boolean', label: '布尔值' },
  { value: 'null', label: '空值' }
]
const complexKeywords = [
  '$ref',
  '$dynamicRef',
  'allOf',
  'anyOf',
  'oneOf',
  'not',
  'if',
  'then',
  'else',
  'dependencies',
  'dependentRequired',
  'dependentSchemas',
  'patternProperties',
  'propertyNames',
  'unevaluatedProperties'
]
function object(value: unknown): value is Schema {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
const parsed = computed(() => {
  try {
    const value: unknown = JSON.parse(props.modelValue)
    return object(value)
      ? { schema: value, reason: '' }
      : { schema: undefined, reason: '当前内容不是 JSON 对象，请在原始 JSON 中修正。' }
  } catch {
    return { schema: undefined, reason: 'JSON 格式不正确，请在原始 JSON 中修正。' }
  }
})
const structureReason = computed(() => {
  const { schema, reason } = parsed.value
  if (!schema) return reason
  if (Object.keys(schema).length === 0) return ''
  if (schema.type !== 'object')
    return '字段表单仅支持对象类型。请使用“原始 JSON（专业）”编辑当前结构。'
  if (complexKeywords.some((key) => key in schema))
    return '当前包含引用、组合或关联字段约束，请使用原始 JSON 编辑完整结构。'
  if (schema.properties !== undefined && !object(schema.properties))
    return 'properties 不是字段对象，请在原始 JSON 中维护。'
  if (
    schema.required !== undefined &&
    (!Array.isArray(schema.required) || schema.required.some((value) => typeof value !== 'string'))
  )
    return 'required 不是字段名数组，请在原始 JSON 中维护。'
  return ''
})
function simple(value: unknown): value is Schema {
  return (
    object(value) &&
    types.some((type) => type.value === value.type) &&
    !complexKeywords.some((key) => key in value) &&
    !('properties' in value) &&
    !('items' in value) &&
    (value.description === undefined || typeof value.description === 'string')
  )
}
const fields = computed(() => {
  const schema = parsed.value.schema
  const required = Array.isArray(schema?.required) ? schema.required : []
  return object(schema?.properties)
    ? Object.entries(schema.properties).map(([name, value]) => ({
        name,
        schema: value,
        editable: simple(value),
        typeLocked:
          object(value) && Object.keys(value).some((key) => !['type', 'description'].includes(key)),
        required: required.includes(name)
      }))
    : []
})
const showRaw = computed(() => rawMode.value || !!structureReason.value)
watch(
  structureReason,
  (reason) => {
    if (reason) rawMode.value = true
  },
  { immediate: true }
)
function change(operation: (schema: Schema, properties: Schema) => Schema | void) {
  if (props.readonly || structureReason.value) return
  // 始终在完整原 Schema 上补丁；不从界面字段重建根或复杂字段。
  const schema = parsed.value.schema!
  const properties = object(schema.properties)
    ? Object.fromEntries(Object.entries(schema.properties))
    : {}
  schema.properties = operation(schema, properties) ?? properties
  error.value = ''
  emit('update:modelValue', JSON.stringify(schema, null, 2))
}
function add() {
  if (props.readonly || structureReason.value) return
  const name = newName.value.trim()
  if (!name || fields.value.some((field) => field.name === name)) {
    error.value = name ? '字段名已存在，请使用不同名称。' : '请先填写字段名。'
    return
  }
  change((schema, properties) => {
    if (Object.keys(schema).length === 0) schema.type = 'object'
    Object.defineProperty(properties, name, {
      value: { type: 'string' },
      enumerable: true,
      writable: true,
      configurable: true
    })
  })
  newName.value = ''
}
function editable(name: string) {
  return fields.value.some((field) => field.name === name && field.editable)
}
function rename(previous: string, value: string) {
  if (props.readonly || !editable(previous)) return
  const name = value.trim()
  if (name === previous) return
  if (!name || fields.value.some((field) => field.name === name)) {
    error.value = name
      ? '未更名：字段名已存在，原字段名已保留。'
      : '未更名：字段名不能为空，原字段名已保留。'
    nameRevision.value++
    return
  }
  change((schema, properties) => {
    if (Array.isArray(schema.required))
      schema.required = schema.required.map((key) => (key === previous ? name : key))
    return Object.fromEntries(
      Object.entries(properties).map(([key, field]) => [key === previous ? name : key, field])
    )
  })
}
function update(name: string, key: 'type' | 'description', value: string) {
  if (!editable(name) || (key === 'type' && !types.some((type) => type.value === value))) return
  if (key === 'type' && fields.value.find((field) => field.name === name)?.typeLocked) return
  change((_schema, properties) => {
    const current = properties[name] as Schema
    properties[name] = { ...current, [key]: value }
  })
}
function setRequired(name: string, value: unknown) {
  if (!editable(name)) return
  change((schema) => {
    const required = Array.isArray(schema.required) ? schema.required : []
    schema.required = value
      ? [...new Set([...required, name])]
      : required.filter((key) => key !== name)
  })
}
function remove(name: string) {
  if (!editable(name)) return
  change((schema, properties) => {
    delete properties[name]
    if (Array.isArray(schema.required))
      schema.required = schema.required.filter((key) => key !== name)
  })
}
function rawChange(value: string) {
  if (props.readonly) return
  error.value = ''
  emit('update:modelValue', value)
}
</script>

<template>
  <section class="json-schema-fields" :aria-label="label">
    <div class="admin-actions schema-modes">
      <ElButton :aria-pressed="!showRaw" :disabled="!!structureReason" @click="rawMode = false"
        >字段编辑</ElButton
      >
      <ElButton :aria-pressed="showRaw" @click="rawMode = true">原始 JSON（专业）</ElButton>
    </div>
    <p :id="id + '-help'" class="admin-field-help">
      可编辑普通字段，复杂类型和约束请使用“原始
      JSON（专业）”。更改字段名后，请检查调用参数及其他引用；此处只同步字段定义与必填列表。
    </p>
    <p v-if="structureReason" class="admin-field-help" role="status">{{ structureReason }}</p>
    <CodeEditor
      v-if="showRaw"
      :model-value="modelValue"
      :label="label"
      language="json"
      :readonly="readonly"
      :described-by="describedBy || id + '-help'"
      height="240px"
      @update:model-value="rawChange"
    />
    <template v-else>
      <p v-if="!Object.keys(parsed.schema ?? {}).length" class="admin-field-help">
        当前未限制类型，添加字段会明确设为对象。
      </p>
      <p v-if="!fields.length" class="admin-muted">尚未定义字段。</p>
      <div
        v-for="(field, index) in fields"
        :key="field.name"
        class="schema-field"
        role="group"
        :aria-label="label + '字段 ' + field.name"
      >
        <div class="schema-field-heading">
          <strong>{{ field.name }}</strong>
          <ElButton
            v-if="field.editable"
            link
            type="danger"
            :disabled="readonly"
            :aria-label="'移除字段 ' + field.name"
            @click="remove(field.name)"
            >移除字段</ElButton
          >
        </div>
        <template v-if="field.editable">
          <div class="schema-field-grid">
            <div>
              <label :for="id + '-name-' + index">字段名</label
              ><ElInput
                :id="id + '-name-' + index"
                :key="nameRevision"
                :model-value="field.name"
                :disabled="readonly"
                @change="rename(field.name, String($event))"
              />
            </div>
            <div>
              <label :for="id + '-type-' + index">类型</label
              ><ElSelect
                :id="id + '-type-' + index"
                :model-value="(field.schema as Schema).type as string"
                :disabled="readonly || field.typeLocked"
                :aria-describedby="field.typeLocked ? id + '-constraints-' + index : undefined"
                @update:model-value="update(field.name, 'type', String($event))"
                ><ElOption
                  v-for="type in types"
                  :key="type.value"
                  :value="type.value"
                  :label="type.label + ' · ' + type.value"
              /></ElSelect>
            </div>
            <ElCheckbox
              :model-value="field.required"
              :disabled="readonly"
              @update:model-value="setRequired(field.name, $event)"
              >必填</ElCheckbox
            >
          </div>
          <label :for="id + '-description-' + index">说明</label
          ><ElInput
            :id="id + '-description-' + index"
            :model-value="String((field.schema as Schema).description ?? '')"
            type="textarea"
            :rows="2"
            :disabled="readonly"
            @update:model-value="update(field.name, 'description', String($event))"
          />
          <p v-if="field.typeLocked" :id="id + '-constraints-' + index" class="admin-field-help">
            此字段有额外约束，修改类型时请在原始 JSON 中一并调整。名称、说明与必填仍可在此编辑。
          </p>
        </template>
        <p v-else class="admin-field-help">
          此字段包含复杂结构，请切换“原始 JSON（专业）”查看或修改。
        </p>
      </div>
      <div v-if="!readonly" class="schema-add admin-actions">
        <ElInput v-model="newName" placeholder="新字段名" :aria-label="label + '新字段名'" />
        <ElButton @click="add">添加字段</ElButton>
      </div>
    </template>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
  </section>
</template>

<style scoped>
.json-schema-fields {
  width: 100%;
  min-width: 0;
}
.schema-modes {
  margin-bottom: 8px;
}
.schema-field {
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}
.schema-field-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  overflow-wrap: anywhere;
}
.schema-field-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  align-items: end;
  gap: 12px;
  margin: 12px 0;
}
.schema-field label {
  display: block;
  font-size: 13px;
  margin-bottom: 4px;
}
.schema-add .el-input {
  flex: 1 1 180px;
}
@media (max-width: 640px) {
  .schema-field-grid {
    grid-template-columns: minmax(0, 1fr);
    align-items: start;
  }
}
</style>
