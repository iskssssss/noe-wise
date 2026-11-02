<template>
  <ElForm
    v-if="card"
    label-position="top"
    :disabled="busy"
    @submit.prevent="submit('accept')"
    scroll-to-error
    @keydown.capture="guardFormEnter"
    ><FeedbackNotice v-if="error" :title="error" :closable="false" type="error" />
    <section v-for="request in card.requests" :key="request.id">
      <h4>{{ request.message }}</h4>
      <ElFormItem
        v-for="(field, key) in properties(request.requestedSchema)"
        :key="key"
        :label="String(field.title || key)"
        :required="required(request.requestedSchema, String(key))"
        :error="fieldErrors[request.id + ':' + key]"
        ><ElSelect
          v-described-by="helpId(request.id, String(key))"
          :aria-describedby="helpId(request.id, String(key))"
          v-if="Array.isArray(field.enum) || field.type === 'boolean'"
          :clearable="!required(request.requestedSchema, String(key))"
          :value-on-clear="() => undefined"
          placeholder="请选择"
          v-model="values[request.id]![key]"
          ><ElOption
            v-for="(value, index) in field.enum ?? [true, false]"
            :key="String(value)"
            :label="
              field.enumNames?.[index] ||
              (typeof value === 'boolean' ? (value ? '是' : '否') : String(value))
            "
            :value="value as string | number | boolean" /></ElSelect
        ><ElInputNumber
          v-described-by="helpId(request.id, String(key))"
          v-else-if="field.type === 'number' || field.type === 'integer'"
          :aria-describedby="helpId(request.id, String(key))"
          v-model="values[request.id]![key] as number | undefined"
          :precision="field.type === 'integer' ? 0 : undefined"
          :min="field.minimum"
          :max="field.maximum"
        /><ElInput
          v-described-by="helpId(request.id, String(key))"
          v-else
          v-model="values[request.id]![key] as string | number | undefined"
          :type="
            field.format === 'password'
              ? 'password'
              : field.format === 'email'
                ? 'email'
                : (field.maxLength ?? 0) > 160
                  ? 'textarea'
                  : 'text'
          "
          :rows="3"
          :aria-describedby="helpId(request.id, String(key))"
        />
        <FieldTip :id="helpId(request.id, String(key))">
          {{ fieldHelp(field, required(request.requestedSchema, String(key))) }}
        </FieldTip></ElFormItem
      >
    </section>
    <div class="toolbar">
      <ElButton type="primary" native-type="submit" :loading="busy">确认补充</ElButton
      ><ElButton :disabled="busy" @click="submit('decline')">拒绝提供</ElButton>
    </div></ElForm
  >
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import { guardFormEnter } from '@skillforge/shared/admin'
import FieldTip from '@skillforge/shared/components/FieldTip.vue'
import { schemaFieldHelp } from '@skillforge/shared/utils/formHelp'
import { computed, reactive, ref, watch, useId } from 'vue'
import {
  toolInputCardOf,
  buildToolInputResponse
} from '@skillforge/shared/components/chat/toolInputCard'
import type { ChatHitlResponse } from '@skillforge/shared/types/chatCollaboration'
import type { RuntimePendingInteraction } from '../api'
const props = defineProps<{ pending: RuntimePendingInteraction; busy: boolean }>(),
  emit = defineEmits<{ respond: [response: ChatHitlResponse] }>(),
  card = computed(() => toolInputCardOf(props.pending.card)),
  values = reactive<Record<string, Record<string, string | number | boolean | undefined>>>({}),
  initialValues = reactive<Record<string, Record<string, string | number | boolean | undefined>>>(
    {}
  ),
  error = ref('')
const fieldErrors = reactive<Record<string, string>>({})
const dirty = computed(() => JSON.stringify(values) !== JSON.stringify(initialValues))
const formTipId = useId()
const helpId = (request: string, key: string) =>
  `${formTipId}-${encodeURIComponent(request)}-${encodeURIComponent(key)}`
defineExpose({ dirty })
type Field = {
  title?: string
  description?: string
  type?: string
  format?: string
  enum?: unknown[]
  enumNames?: string[]
  default?: string | number | boolean
  minimum?: number
  maximum?: number
  minLength?: number
  maxLength?: number
}
function properties(schema: Record<string, unknown>) {
  return (schema.properties ?? {}) as Record<string, Field>
}
function required(schema: Record<string, unknown>, key: string) {
  return Array.isArray(schema.required) && schema.required.includes(key)
}
watch(
  card,
  (next) => {
    if (!next) return
    for (const request of next.requests)
      if (!values[request.id]) {
        values[request.id] = Object.fromEntries(
          Object.entries(properties(request.requestedSchema)).map(([key, field]) => [
            key,
            field.default ?? undefined
          ])
        )
        initialValues[request.id] = { ...values[request.id] }
      }
  },
  { immediate: true }
)
function fieldHelp(field: Field, mandatory: boolean) {
  const help = schemaFieldHelp(field, mandatory)
  return field.default !== undefined && field.format !== 'password'
    ? `${help} 默认值：${String(field.default)}。`
    : help
}
function validateField(
  field: Field,
  value: string | number | boolean | undefined,
  mandatory: boolean
) {
  const label = field.title || '此项'
  if (value === undefined || value === '' || (typeof value === 'string' && !value.trim())) {
    if (mandatory) throw Error(`请填写${label}`)
    return
  }
  if (field.enum && !field.enum.includes(value)) throw Error(`请从提供的选项中选择${label}`)
  if (field.type === 'number' || field.type === 'integer') {
    if (
      typeof value !== 'number' ||
      !Number.isFinite(value) ||
      (field.type === 'integer' && !Number.isInteger(value))
    )
      throw Error(`${label}必须是${field.type === 'integer' ? '整数' : '数值'}`)
    if (
      (field.minimum !== undefined && value < field.minimum) ||
      (field.maximum !== undefined && value > field.maximum)
    )
      throw Error(`${label}超出允许范围`)
  }
  if (
    typeof value === 'string' &&
    ((field.minLength !== undefined && Array.from(value).length < field.minLength) ||
      (field.maxLength !== undefined && Array.from(value).length > field.maxLength))
  )
    throw Error(`${label}长度不符合要求`)
}
function submit(action: 'accept' | 'decline') {
  if (props.busy) return
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key]
  try {
    if (!card.value) return
    const content: typeof values = {}
    for (const request of card.value.requests) {
      content[request.id] = {}
      for (const [key, field] of Object.entries(properties(request.requestedSchema))) {
        const value = values[request.id]?.[key]
        if (action === 'accept') {
          try {
            validateField(field, value, required(request.requestedSchema, key))
          } catch (cause) {
            fieldErrors[request.id + ':' + key] =
              cause instanceof Error ? cause.message : '请检查此项'
          }
        }
        if (value !== undefined && value !== '') content[request.id][key] = value
      }
    }
    if (Object.keys(fieldErrors).length) throw Error('请检查标记的字段，修正后重新提交。')
    emit('respond', buildToolInputResponse(props.pending, card.value, action, content))
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '无法提交补充输入'
  }
}
</script>
