<script setup lang="ts">
import FeedbackNotice from '../feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { nextTick, ref, watch, useId, toRaw } from 'vue'
import { ElButton, ElFormItem, ElOption, ElSelect } from 'element-plus'
import EntitySelect from './EntitySelect.vue'
import { statusLabel } from './presentation'
import type { SkillRequirement } from '../capabilities/skillDrafts'
import type { CapabilityType, CapabilityRequirementOption } from '../capabilities/types'
import { HttpClientError } from '../utils/http'
const props = defineProps<{
  modelValue: SkillRequirement[]
  disabled?: boolean
  loadOptions: (type: CapabilityType) => Promise<CapabilityRequirementOption[]>
}>()
const emit = defineEmits<{ 'update:modelValue': [value: SkillRequirement[]] }>()
const options = ref<Record<string, CapabilityRequirementOption[]>>({}),
  error = ref('')
const types: CapabilityType[] = ['SKILL', 'TOOL', 'WORKFLOW', 'KNOWLEDGE_SOURCE']
const pending = new Map<string, Promise<void>>()
const rowIds = new WeakMap<SkillRequirement, string>()
const rowErrors = ref<Record<string, string>>({})
const host = ref<HTMLElement>()
let rowSequence = 0
function rowId(row: SkillRequirement) {
  const identity = toRaw(row)
  let id = rowIds.get(identity)
  if (!id) {
    id = `requirement-${++rowSequence}`
    rowIds.set(identity, id)
  }
  return id
}
function key(row: SkillRequirement) {
  return `${row.type}:${row.capabilityId}`
}
async function load(force = false) {
  error.value = ''
  await Promise.all(
    props.modelValue.map(async (row) => {
      try {
        if (force || !options.value[row.type]) {
          if (!pending.has(row.type))
            pending.set(
              row.type,
              props
                .loadOptions(row.type)
                .then((value) => {
                  options.value[row.type] = value
                })
                .finally(() => pending.delete(row.type))
            )
          await pending.get(row.type)
        }
      } catch (reason) {
        if (reason instanceof HttpClientError) reason.claimFeedback()
        error.value =
          reason instanceof Error &&
          !(reason instanceof TypeError) &&
          !(reason instanceof SyntaxError)
            ? reason.message
            : '未能加载依赖能力，请点击“重新加载选项”重试'
      }
    })
  )
}
watch(
  () => props.modelValue.map((row) => key(row)).join('|'),
  () => load(),
  { immediate: true }
)
function update(index: number, patch: Partial<SkillRequirement>) {
  if (props.disabled) return
  emit(
    'update:modelValue',
    props.modelValue.map((row, position) => {
      if (position !== index) return row
      const next = { ...row, ...patch }
      rowIds.set(next, rowId(row))
      delete rowErrors.value[rowId(row)]
      return next
    })
  )
}
async function validate() {
  rowErrors.value = {}
  const seen = new Set<string>()
  for (const row of props.modelValue) {
    if (seen.has(key(row))) rowErrors.value[rowId(row)] = '已添加此能力，请移除重复依赖'
    else if (!row.capabilityId) rowErrors.value[rowId(row)] = '请选择能力'
    seen.add(key(row))
  }
  if (Object.keys(rowErrors.value).length) {
    await nextTick()
    const first = Object.keys(rowErrors.value)[0]
    const field = host.value?.querySelector<HTMLElement>(`[data-row-id="${first}"] input`)
    field?.focus()
    field?.scrollIntoView?.({ block: 'nearest' })
    throw new Error('请修正标出的重复依赖或未选择的能力')
  }
}
defineExpose({ validate })

const formTipId = useId()
</script>
<template>
  <section ref="host" aria-label="能力依赖">
    <p class="admin-field-help">
      每次新执行使用所选能力的当前发布版本。切换能力类型后需重新选择能力；保存时会检查发布状态。
    </p>
    <FeedbackNotice v-if="error" :title="error" type="error" :closable="false" /><ElButton
      v-if="error"
      link
      @click="load(true)"
      >重新加载选项</ElButton
    >
    <ElButton v-if="!error && modelValue.length" link :disabled="disabled" @click="load(true)"
      >刷新依赖选项</ElButton
    >
    <div
      v-for="(row, index) in modelValue"
      :key="rowId(row)"
      :data-row-id="rowId(row)"
      class="requirement-row"
    >
      <ElFormItem label="能力类型"
        ><ElSelect
          v-described-by="formTipId + '-field-0' + '-' + encodeURIComponent(String(index))"
          :aria-describedby="formTipId + '-field-0' + '-' + encodeURIComponent(String(index))"
          :model-value="row.type"
          :disabled="disabled"
          @update:model-value="update(index, { type: $event, capabilityId: '', versionId: '' })"
          ><ElOption v-for="type in types" :key="type" :value="type" :label="statusLabel(type)"
        /></ElSelect>
        <FieldTip :id="formTipId + '-field-0' + '-' + encodeURIComponent(String(index))"
          >选择依赖的能力类别；切换后需要重新选择能力。</FieldTip
        >
      </ElFormItem>
      <ElFormItem label="能力名称" required :error="rowErrors[rowId(row)]"
        ><EntitySelect
          v-described-by="formTipId + '-field-1' + '-' + encodeURIComponent(String(index))"
          :aria-describedby="formTipId + '-field-1' + '-' + encodeURIComponent(String(index))"
          :model-value="row.capabilityId"
          :options="
            (options[row.type] ?? []).map((item) => ({
              id: item.id,
              label: item.name,
              description: item.versionKey
            }))
          "
          :disabled="disabled"
          placeholder="选择能力"
          @update:model-value="update(index, { capabilityId: $event, versionId: '' })"
        />
        <FieldTip :id="formTipId + '-field-1' + '-' + encodeURIComponent(String(index))"
          >能力需具备已发布版本，保存时由服务端确认当前发布版本。</FieldTip
        >
      </ElFormItem>
      <ElButton
        :disabled="disabled"
        type="danger"
        link
        @click="
          emit(
            'update:modelValue',
            modelValue.filter((_, position) => position !== index)
          )
        "
        >移除依赖</ElButton
      >
    </div>
    <ElButton
      :disabled="disabled"
      @click="
        emit('update:modelValue', [
          ...modelValue,
          { type: 'TOOL', capabilityId: '', versionId: '' }
        ])
      "
      >添加依赖</ElButton
    >
  </section>
</template>
<style scoped>
.requirement-row {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.requirement-row :deep(.el-form-item) {
  min-width: 180px;
  flex: 1;
}
</style>
