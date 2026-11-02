<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import WorkflowVariableSelect from './WorkflowVariableSelect.vue'
import type { WorkflowVariableOption } from './nodeEditing'
const props = withDefaults(
  defineProps<{
    modelValue?: string
    variables: WorkflowVariableOption[]
    readonly?: boolean
    label?: string
  }>(),
  { label: '连接条件' }
)
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const professional = ref(false)
let lastEmitted: string | undefined
const left = ref(''),
  operator = ref('=='),
  kind = ref('string'),
  right = ref('')
const simple = computed(
  () =>
    !props.modelValue?.trim() ||
    /^\$\{[A-Za-z_][\w.]*\}\s*(?:==|!=|>=|<=|>|<|contains|matches|in)\s*(?:"(?:[^"\\]|\\.)*"|'[^']*'|-?\d+(?:\.\d+)?|true|false|null|\[[^\]{}]*\])$/.test(
      props.modelValue.trim()
    )
)
watch(
  () => props.modelValue,
  (value) => {
    if (value === lastEmitted) return
    if (!value?.trim()) {
      left.value = ''
      right.value = ''
      return
    }
    const match = value
      .trim()
      .match(/^\$\{([^{}]+)\}\s*(==|!=|>=|<=|>|<|contains|matches|in)\s*(.*)$/)
    if (!match || !simple.value) {
      professional.value = true
      return
    }
    left.value = match[1] ?? ''
    operator.value = match[2] ?? '=='
    const raw = match[3] ?? ''
    if (raw.startsWith('"') || raw.startsWith("'")) {
      kind.value = 'string'
      try {
        right.value = JSON.parse(raw) as string
      } catch {
        right.value = raw.slice(1, -1)
      }
    } else {
      kind.value = ['true', 'false'].includes(raw)
        ? 'boolean'
        : raw === 'null'
          ? 'null'
          : raw.startsWith('[')
            ? 'array'
            : 'number'
      right.value = raw
    }
  },
  { immediate: true }
)
function update() {
  if (!left.value) {
    lastEmitted = ''
    emit('update:modelValue', '')
    return
  }
  const literal =
    kind.value === 'string'
      ? JSON.stringify(right.value)
      : kind.value === 'null'
        ? 'null'
        : right.value || (kind.value === 'boolean' ? 'false' : kind.value === 'array' ? '[]' : '0')
  lastEmitted = '${' + left.value + '} ' + operator.value + ' ' + literal
  emit('update:modelValue', lastEmitted)
}
function changeKind() {
  right.value = ''
  update()
}
</script>
<template>
  <section class="condition-editor" :aria-label="label">
    <ElRadioGroup
      v-model="professional"
      :disabled="readonly"
      :aria-label="label + '编辑方式'"
      size="small"
      ><ElRadioButton :value="false" :disabled="!simple">规则</ElRadioButton
      ><ElRadioButton :value="true">专业表达式</ElRadioButton></ElRadioGroup
    >
    <template v-if="!professional">
      <ElFormItem label="比较变量"
        ><WorkflowVariableSelect
          v-model="left"
          :options="variables"
          :disabled="readonly"
          :label="label + '左侧变量'"
          @update:model-value="update"
      /></ElFormItem>
      <ElFormItem label="判断方式"
        ><ElSelect
          v-model="operator"
          :disabled="readonly"
          :aria-label="label + '判断方式'"
          @change="update"
          ><ElOption
            v-for="option in [
              { value: '==', label: '等于' },
              { value: '!=', label: '不等于' },
              { value: '>', label: '大于' },
              { value: '>=', label: '大于等于' },
              { value: '<', label: '小于' },
              { value: '<=', label: '小于等于' },
              { value: 'contains', label: '包含' },
              { value: 'matches', label: '匹配正则' },
              { value: 'in', label: '属于列表' }
            ]"
            :key="option.value"
            :value="option.value"
            :label="option.label" /></ElSelect
      ></ElFormItem>
      <ElFormItem label="比较值"
        ><div class="condition-literal">
          <ElSelect
            v-model="kind"
            :disabled="readonly"
            :aria-label="label + '比较值类型'"
            @change="changeKind"
            ><ElOption
              v-for="option in [
                { value: 'string', label: '文本' },
                { value: 'number', label: '数字' },
                { value: 'boolean', label: '是/否' },
                { value: 'null', label: '空值' },
                { value: 'array', label: '列表字面量' }
              ]"
              :key="option.value"
              :value="option.value"
              :label="option.label" /></ElSelect
          ><ElSelect
            v-if="kind === 'boolean'"
            v-model="right"
            :disabled="readonly"
            :aria-label="label + '比较值'"
            @change="update"
            ><ElOption value="true" label="是（true）" /><ElOption
              value="false"
              label="否（false）" /></ElSelect
          ><ElInput
            v-else-if="kind !== 'null'"
            v-model="right"
            :disabled="readonly"
            :aria-label="label + '比较值'"
            @update:model-value="update"
          /></div
      ></ElFormItem>
      <p class="admin-field-help">
        未设置条件时可直接通过。比较值为固定值；上游分支未执行时，引用的输出可能没有值。
      </p>
    </template>
    <template v-else
      ><CodeEditor
        :model-value="modelValue || ''"
        :label="label"
        height="120px"
        :readonly="readonly"
        @update:model-value="emit('update:modelValue', $event)"
      />
      <p class="admin-field-help">
        可使用比较、contains、matches、in、&&、|| 和开头的 !。比较左侧的变量需写为
        ${变量路径}，右侧填写固定值。
      </p></template
    >
  </section>
</template>
<style scoped>
.condition-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.condition-literal {
  display: grid;
  grid-template-columns: minmax(110px, 1fr) minmax(0, 2fr);
  gap: 8px;
  width: 100%;
}
.condition-editor :deep(.el-form-item) {
  margin-bottom: 0;
}
@media (max-width: 600px) {
  .condition-literal {
    grid-template-columns: 1fr;
  }
}
</style>
