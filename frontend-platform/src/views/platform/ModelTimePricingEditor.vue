<script setup lang="ts">
import { useId, ref, reactive, shallowRef, nextTick, watch, toRaw, computed } from 'vue'
import type { ModelTimePricingPolicy } from '../../api'
import { pricingWeekdays } from './model-time-pricing'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

const props = withDefaults(
  defineProps<{
    modelValue: ModelTimePricingPolicy
    errors?: Record<string, string>
    errorPrefix?: string
  }>(),
  { errors: () => ({}), errorPrefix: 'policy' }
)
const emit = defineEmits<{
  'update:modelValue': [value: ModelTimePricingPolicy]
  'field-change': [field: string]
}>()
const tipId = useId()
const editorRoot = ref<HTMLElement>()
const advancedSections = ref<string[]>([])
watch(
  () => props.errors,
  (errors) => {
    if (
      Object.keys(errors).some((key) => key.startsWith(path('applicable_models'))) &&
      !advancedSections.value.includes('scope')
    )
      advancedSections.value.push('scope')
    if (
      ['source_url', 'source_version', 'calendar_source_url'].some((key) => errors[path(key)]) &&
      !advancedSections.value.includes('sources')
    )
      advancedSections.value.push('sources')
  },
  { deep: true, immediate: true }
)
type ListKey = 'peak_periods' | 'peak_date_ranges' | 'off_peak_date_ranges' | 'applicable_models'
type ListItem = ModelTimePricingPolicy[ListKey][number]
const listKeys: ListKey[] = [
  'peak_periods',
  'peak_date_ranges',
  'off_peak_date_ranges',
  'applicable_models'
]
let nextKey = 0
const rowKeys = reactive<Record<ListKey, number[]>>({
  peak_periods: [],
  peak_date_ranges: [],
  off_peak_date_ranges: [],
  applicable_models: []
})
function resetRowKeys() {
  for (const key of listKeys) rowKeys[key] = props.modelValue[key].map(() => nextKey++)
}
resetRowKeys()
const removed = shallowRef<{
  key: ListKey
  index: number
  value: ListItem
  rowKey: number
  label: string
}>()
let lastEmission: ModelTimePricingPolicy | undefined
watch(
  () => props.modelValue,
  (value) => {
    if (lastEmission && toRaw(value) === toRaw(lastEmission)) return
    removed.value = undefined
    resetRowKeys()
  }
)
function path(field: string) {
  return props.errorPrefix + '.' + field
}
function error(field: string) {
  return props.errors[path(field)]
}
function errorId(field: string) {
  return tipId + '-error-' + field.replaceAll('.', '-')
}
function describe(field: string, help: string) {
  return [help, error(field) ? errorId(field) : ''].filter(Boolean).join(' ')
}
function patch<K extends keyof ModelTimePricingPolicy>(key: K, value: ModelTimePricingPolicy[K]) {
  lastEmission = { ...props.modelValue, [key]: value }
  emit('update:modelValue', lastEmission)
  emit('field-change', key)
}
function focusRow(key: ListKey, index: number) {
  void nextTick(() => {
    const row = editorRoot.value?.querySelector<HTMLElement>(
      `[data-time-pricing-field="${path(key + '.' + index)}"]`
    )
    row?.querySelector<HTMLInputElement>('input:not([disabled])')?.focus()
  })
}
function add(key: ListKey) {
  const value =
    key === 'peak_periods'
      ? { start: '', end: '' }
      : key === 'applicable_models'
        ? { provider_id: null, service_id: null, profile_version: null, model_id: null }
        : { from: '', through: '' }
  const index = props.modelValue[key].length
  rowKeys[key].push(nextKey++)
  patch(key, [...props.modelValue[key], value] as ModelTimePricingPolicy[typeof key])
  focusRow(key, index)
}
function remove(key: ListKey, index: number, label: string) {
  removed.value = {
    key,
    index,
    label,
    value: { ...props.modelValue[key][index]! },
    rowKey: rowKeys[key][index]!
  }
  rowKeys[key].splice(index, 1)
  patch(
    key,
    props.modelValue[key].filter((_, i) => i !== index) as ModelTimePricingPolicy[typeof key]
  )
  void nextTick(() =>
    editorRoot.value?.querySelector<HTMLButtonElement>(`[data-add="${key}"]`)?.focus()
  )
}
function undo() {
  const item = removed.value
  if (!item) return
  const index = Math.min(item.index, props.modelValue[item.key].length)
  const values = [...props.modelValue[item.key]]
  values.splice(index, 0, item.value)
  rowKeys[item.key].splice(index, 0, item.rowKey)
  removed.value = undefined
  patch(item.key, values as ModelTimePricingPolicy[typeof item.key])
  focusRow(item.key, index)
}
const baseFields = [
  {
    key: 'time_zone',
    label: '计价时区',
    type: 'text',
    placeholder: '如 Asia/Shanghai',
    required: true
  },
  { key: 'valid_from', label: '覆盖开始日期', type: 'date', placeholder: '', required: true },
  { key: 'valid_through', label: '覆盖结束日期', type: 'date', placeholder: '', required: true },
  { key: 'peak_multiplier', label: '峰时倍率', type: 'text', placeholder: '如 1', required: false },
  {
    key: 'off_peak_multiplier',
    label: '谷时倍率',
    type: 'text',
    placeholder: '如 0.5',
    required: false
  }
] as const
function setBase(key: (typeof baseFields)[number]['key'], value: string) {
  if (key === 'peak_multiplier' || key === 'off_peak_multiplier') patch(key, value || null)
  else patch(key, value)
}
function helpFor(key: (typeof baseFields)[number]['key']) {
  return (
    tipId +
    (key === 'time_zone' ? '-zone' : key.startsWith('valid_') ? '-coverage' : '-multipliers')
  )
}
function setPeriod(index: number, key: 'start' | 'end', value: string) {
  patch(
    'peak_periods',
    props.modelValue.peak_periods.map((v, i) => (i === index ? { ...v, [key]: value } : v))
  )
}
function setDate(
  kind: 'peak_date_ranges' | 'off_peak_date_ranges',
  index: number,
  key: 'from' | 'through',
  value: string
) {
  patch(
    kind,
    props.modelValue[kind].map((v, i) => (i === index ? { ...v, [key]: value } : v))
  )
}
const dateKinds = [
  { key: 'peak_date_ranges', label: '额外峰日' },
  { key: 'off_peak_date_ranges', label: '整日谷日' }
] as const
const scopeFields = [
  { key: 'provider_id', label: '提供方标识' },
  { key: 'service_id', label: '服务标识' },
  { key: 'profile_version', label: '服务目录版本' },
  { key: 'model_id', label: '模型标识' }
] as const
function setScope(
  index: number,
  key: keyof ModelTimePricingPolicy['applicable_models'][number],
  value: string
) {
  patch(
    'applicable_models',
    props.modelValue.applicable_models.map((v, i) =>
      i === index ? { ...v, [key]: value || null } : v
    )
  )
}
const sourceFields = [
  { key: 'source_url', label: '价格规则来源' },
  { key: 'source_version', label: '来源版本' },
  { key: 'calendar_source_url', label: '日历来源' }
] as const
const sourceCount = computed(
  () => sourceFields.filter((field) => props.modelValue[field.key]).length
)
</script>

<template>
  <section ref="editorRoot" class="time-pricing-editor" aria-label="峰谷规则配置">
    <div class="admin-form-grid time-pricing-basics">
      <ElFormItem
        v-for="field in baseFields"
        :key="field.key"
        :label="field.label"
        :required="field.required"
        :error="error(field.key)"
        :data-time-pricing-field="path(field.key)"
        v-described-by="describe(field.key, helpFor(field.key))"
      >
        <ElInput
          :aria-invalid="!!error(field.key)"
          :type="field.type"
          :model-value="modelValue[field.key]"
          :placeholder="field.placeholder"
          :inputmode="field.key.endsWith('multiplier') ? 'decimal' : undefined"
          @update:model-value="setBase(field.key, $event)"
        />
        <template #error
          ><div :id="errorId(field.key)" class="el-form-item__error">
            {{ error(field.key) }}
          </div></template
        >
      </ElFormItem>
    </div>
    <p :id="tipId + '-zone'" class="admin-field-help">
      按 IANA 时区名称解释日期和时段，例如 Asia/Shanghai。
    </p>
    <p :id="tipId + '-coverage'" class="admin-field-help">
      起止日期均包含当天；日期例外须位于覆盖范围内，覆盖范围外费用保留待核实。
    </p>
    <p :id="tipId + '-multipliers'" class="admin-field-help">
      基准单价乘以时段倍率；1 表示原价，0 表示明确零费用，留空表示未知。整数、小数部分分别最多 12
      位。
    </p>
    <ElFormItem
      label="常规峰日"
      :error="error('peak_weekdays')"
      :data-time-pricing-field="path('peak_weekdays')"
      v-described-by="describe('peak_weekdays', tipId + '-weekdays')"
    >
      <ElCheckboxGroup
        :aria-invalid="!!error('peak_weekdays')"
        :aria-describedby="describe('peak_weekdays', tipId + '-weekdays')"
        :model-value="modelValue.peak_weekdays"
        @update:model-value="
          patch('peak_weekdays', $event as ModelTimePricingPolicy['peak_weekdays'])
        "
      >
        <ElCheckbox v-for="day in pricingWeekdays" :key="day.value" :value="day.value">{{
          day.label
        }}</ElCheckbox>
      </ElCheckboxGroup>
      <p :id="tipId + '-weekdays'" class="admin-field-help">
        仅所选星期和额外峰日中的指定时段为峰时；未选择星期表示无常规峰日，整日谷日不进入峰时。
      </p>
      <template #error
        ><div :id="errorId('peak_weekdays')" class="el-form-item__error">
          {{ error('peak_weekdays') }}
        </div></template
      >
    </ElFormItem>
    <section aria-label="峰时段">
      <h4>峰时段</h4>
      <p :id="tipId + '-periods'" class="admin-field-help">
        填写 HH:mm，可包含秒及最多 9
        位小数。开始包含、结束不包含；跨午夜请拆为同日时段，未配置的时段为谷时。
      </p>
      <ElFormItem
        v-for="(period, index) in modelValue.peak_periods"
        :key="rowKeys.peak_periods[index]"
        :label="`峰时段 ${index + 1}`"
        :error="error('peak_periods.' + index)"
        :data-time-pricing-field="path('peak_periods.' + index)"
        v-described-by="describe('peak_periods.' + index, tipId + '-periods')"
      >
        <div class="time-pricing-row">
          <ElInput
            :aria-invalid="!!error('peak_periods.' + index)"
            placeholder="如 09:00"
            :aria-label="`峰时段 ${index + 1} 开始`"
            :model-value="period.start"
            @update:model-value="setPeriod(index, 'start', $event)"
          />
          <span>至</span>
          <ElInput
            :aria-invalid="!!error('peak_periods.' + index)"
            placeholder="如 12:00"
            :aria-label="`峰时段 ${index + 1} 结束`"
            :model-value="period.end"
            @update:model-value="setPeriod(index, 'end', $event)"
          />
          <ElButton
            :aria-label="`删除峰时段 ${index + 1}`"
            @click="remove('peak_periods', index, `峰时段 ${index + 1}`)"
            >删除</ElButton
          >
        </div>
        <template #error
          ><div :id="errorId('peak_periods.' + index)" class="el-form-item__error">
            {{ error('peak_periods.' + index) }}
          </div></template
        >
      </ElFormItem>
      <ElButton data-add="peak_periods" @click="add('peak_periods')">添加峰时段</ElButton>
      <p v-if="removed?.key === 'peak_periods'" class="admin-field-help" role="status">
        已移除{{ removed.label }}，尚未保存。<ElButton text @click="undo">撤销删除</ElButton>
      </p>
    </section>
    <div class="time-pricing-date-groups">
      <section v-for="kind in dateKinds" :key="kind.key" :aria-label="kind.label">
        <h4>{{ kind.label }}</h4>
        <p :id="tipId + '-' + kind.key" class="admin-field-help">
          起止日期均包含当天，须位于覆盖范围内；同类范围不可重叠，同一天不能同时是额外峰日和整日谷日。
        </p>
        <ElFormItem
          v-for="(range, index) in modelValue[kind.key]"
          :key="rowKeys[kind.key][index]"
          :label="`${kind.label} ${index + 1}`"
          :error="error(kind.key + '.' + index)"
          :data-time-pricing-field="path(kind.key + '.' + index)"
          v-described-by="describe(kind.key + '.' + index, tipId + '-' + kind.key)"
        >
          <div class="time-pricing-row time-pricing-date-row">
            <ElInput
              :aria-invalid="!!error(kind.key + '.' + index)"
              type="date"
              :aria-label="`${kind.label} ${index + 1} 开始日期`"
              :model-value="range.from"
              @update:model-value="setDate(kind.key, index, 'from', $event)"
            />
            <span>至</span>
            <ElInput
              :aria-invalid="!!error(kind.key + '.' + index)"
              type="date"
              :aria-label="`${kind.label} ${index + 1} 结束日期`"
              :model-value="range.through"
              @update:model-value="setDate(kind.key, index, 'through', $event)"
            />
            <ElButton
              :aria-label="`删除${kind.label} ${index + 1}`"
              @click="remove(kind.key, index, `${kind.label} ${index + 1}`)"
              >删除</ElButton
            >
          </div>
          <template #error
            ><div :id="errorId(kind.key + '.' + index)" class="el-form-item__error">
              {{ error(kind.key + '.' + index) }}
            </div></template
          >
        </ElFormItem>
        <ElButton :data-add="kind.key" @click="add(kind.key)">添加{{ kind.label }}</ElButton>
        <p v-if="removed?.key === kind.key" class="admin-field-help" role="status">
          已移除{{ removed.label }}，尚未保存。<ElButton text @click="undo">撤销删除</ElButton>
        </p>
      </section>
    </div>
    <ElCollapse v-model="advancedSections" class="time-pricing-advanced">
      <ElCollapseItem
        name="scope"
        :title="`适用模型范围 · ${modelValue.applicable_models.length ? modelValue.applicable_models.length + ' 组条件' : '全部语言模型'}`"
      >
        <section
          aria-label="适用模型范围"
          :aria-invalid="!!error('applicable_models')"
          :data-time-pricing-field="path('applicable_models')"
          v-described-by="describe('applicable_models', tipId + '-scopes')"
        >
          <p :id="tipId + '-scopes'" class="admin-field-help">
            无条目表示适用于所有语言模型。条目内条件须同时满足，多个条目满足其一即可；字段留空表示不限。提供方、服务和目录版本标识最多
            128 个字符。
          </p>
          <p
            v-if="error('applicable_models')"
            :id="errorId('applicable_models')"
            class="scope-error"
          >
            {{ error('applicable_models') }}
          </p>
          <div
            v-for="(scope, index) in modelValue.applicable_models"
            :key="rowKeys.applicable_models[index]"
            class="time-pricing-scope"
            :data-time-pricing-field="path('applicable_models.' + index)"
          >
            <div class="admin-form-grid time-pricing-scope-fields">
              <ElFormItem
                v-for="field in scopeFields"
                :key="field.key"
                :label="`范围 ${index + 1} · ${field.label}`"
                :error="error(`applicable_models.${index}.${field.key}`)"
                :data-time-pricing-field="path(`applicable_models.${index}.${field.key}`)"
                v-described-by="
                  describe(`applicable_models.${index}.${field.key}`, tipId + '-scopes')
                "
              >
                <ElInput
                  :aria-invalid="
                    !!(
                      error(`applicable_models.${index}.${field.key}`) || error('applicable_models')
                    )
                  "
                  :model-value="scope[field.key]"
                  placeholder="不限"
                  @update:model-value="setScope(index, field.key, $event)"
                />
                <template #error
                  ><div
                    :id="errorId(`applicable_models.${index}.${field.key}`)"
                    class="el-form-item__error"
                  >
                    {{ error(`applicable_models.${index}.${field.key}`) }}
                  </div></template
                >
              </ElFormItem>
            </div>
            <ElButton @click="remove('applicable_models', index, `范围 ${index + 1}`)"
              >删除范围 {{ index + 1 }}</ElButton
            >
          </div>
          <ElButton data-add="applicable_models" @click="add('applicable_models')"
            >添加适用范围</ElButton
          >
          <p v-if="removed?.key === 'applicable_models'" class="admin-field-help" role="status">
            已移除{{ removed.label }}，尚未保存。<ElButton text @click="undo">撤销删除</ElButton>
          </p>
        </section>
      </ElCollapseItem>
      <ElCollapseItem
        name="sources"
        :title="`规则来源 · ${sourceCount ? '已记录 ' + sourceCount + ' 项' : '未记录'}`"
      >
        <div class="admin-form-grid time-pricing-sources">
          <ElFormItem
            v-for="field in sourceFields"
            :key="field.key"
            :label="field.label"
            :error="error(field.key)"
            :data-time-pricing-field="path(field.key)"
            v-described-by="describe(field.key, tipId + '-sources')"
          >
            <ElInput
              :aria-invalid="!!error(field.key)"
              :model-value="modelValue[field.key]"
              @update:model-value="patch(field.key, $event || null)"
            />
            <template #error
              ><div :id="errorId(field.key)" class="el-form-item__error">
                {{ error(field.key) }}
              </div></template
            >
          </ElFormItem>
        </div>
        <p :id="tipId + '-sources'" class="admin-field-help">
          来源字段选填，留空表示未记录；来源版本最多 256
          个字符。规则版本与来源随本次配置保存，供费用依据追溯。
        </p>
      </ElCollapseItem>
    </ElCollapse>
  </section>
</template>

<style scoped>
.time-pricing-editor {
  grid-column: 1 / -1;
  min-width: 0;
  container: time-pricing / inline-size;
}
.time-pricing-editor section {
  margin-block: 20px;
}
.time-pricing-editor h4 {
  margin: 0 0 12px;
  font-size: var(--el-font-size-base);
}
.time-pricing-editor :deep(.el-checkbox.is-checked:not(.is-disabled) .el-checkbox__label) {
  color: var(--admin-text);
}
.time-pricing-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.time-pricing-row .el-input {
  min-width: 0;
}
.time-pricing-row :deep(input) {
  min-width: 0;
}
.time-pricing-basics {
  grid-template-columns: repeat(6, minmax(0, 1fr));
}
.time-pricing-basics > .el-form-item {
  grid-column: span 2;
}
.time-pricing-basics > .el-form-item:nth-last-child(-n + 2) {
  grid-column: span 3;
}
.time-pricing-scope-fields {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}
.time-pricing-sources {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.time-pricing-date-groups {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}
.time-pricing-advanced section {
  margin-block: 0;
}
@container time-pricing (max-width: 720px) {
  .time-pricing-date-groups {
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
  }
  .time-pricing-scope-fields,
  .time-pricing-sources {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@container time-pricing (max-width: 520px) {
  .time-pricing-basics > .el-form-item:nth-child(-n + 3) {
    grid-column: span 6;
  }
  .time-pricing-basics > .el-form-item {
    grid-column: span 3;
  }
  .time-pricing-scope-fields,
  .time-pricing-sources {
    grid-template-columns: minmax(0, 1fr);
  }
  .time-pricing-date-row {
    grid-template-columns: minmax(0, 1fr);
  }
  .time-pricing-date-row > .el-button {
    justify-self: end;
  }
}
.time-pricing-scope {
  padding-block: 12px;
  border-bottom: 1px solid var(--el-border-color);
}
.scope-error {
  color: var(--el-color-danger);
  font-size: var(--el-font-size-small);
  margin-block: 8px;
}
[data-time-pricing-field] {
  scroll-margin-block: 24px;
}
</style>
