<script setup lang="ts">
import { vDescribedBy } from './describedBy'
import { ArrowUp, MessageSquare, Square } from 'lucide-vue-next'

import {
  ChatAlert,
  ChatButton,
  ChatForm,
  ChatFormItem,
  ChatInput,
  ChatOption,
  ChatSelect,
  type FormInstance,
  type FormItemRule
} from './controls'
import { computed, nextTick, reactive, ref, useId } from 'vue'
import { pendingIdentity } from '../projection'
import type { Pending, Schema } from '../protocol'
import Markdown from './Markdown.vue'
import { validateForm } from './panel'
import FieldTip from './FieldTip.vue'
import { schemaFieldHelp } from './fieldHelp'
const props = defineProps<{
  pending: Pending
  busy: boolean
  disabled?: boolean
  cancelDisabled?: boolean
  questionDisplayed?: boolean
}>()
const emit = defineEmits<{ submit: [value: Record<string, unknown>]; cancel: [] }>()
const fields = reactive<Record<string, Record<string, unknown>>>({}),
  feedback = ref(''),
  error = ref('')
const formRef = ref<FormInstance>()
const submitting = ref(false)
const formId = useId()
const dirty = computed(
  () => JSON.stringify(fields) !== initialFields || Boolean(feedback.value.trim())
)
const helpId = (request: string, field: string) =>
  `${formId}-${encodeURIComponent(request)}-${encodeURIComponent(field)}`
defineExpose({ dirty })
const model = computed(() => ({ fields, feedback: feedback.value }))
// 只展示工作流卡明确提供的确认动作；按钮文案不参与恢复动作路由。
const confirmationAction = computed(() => {
  if (
    ['user_input', 'tool_input', 'plan_review'].includes(props.pending.interaction_type ?? '') ||
    !props.pending.suspend_ref ||
    props.pending.card?.type !== 'confirm'
  )
    return undefined
  const actions = props.pending.card.actions
  return Array.isArray(actions)
    ? actions.find(
        (action) => action?.id === 'confirm' || (!action?.id && action?.type === 'confirm')
      )
    : undefined
})
const workflowConfirmation = computed(() => Boolean(confirmationAction.value))
const simpleInput = computed(
  () =>
    !workflowConfirmation.value &&
    !['tool_input', 'plan_review'].includes(props.pending.interaction_type ?? '')
)
const question = computed(() =>
  props.pending.interaction_type === 'user_input'
    ? props.pending.question || props.pending.card?.body
    : props.pending.card?.title || props.pending.question
)
const heading = computed(() =>
  props.pending.interaction_type === 'plan_review'
    ? '确认计划'
    : workflowConfirmation.value
      ? question.value || '确认工作流'
      : '补充信息'
)
const feedbackRequired = computed(
  () => props.pending.interaction_type !== 'plan_review' && !workflowConfirmation.value
)
const submitLabel = computed(() =>
  props.pending.interaction_type === 'plan_review'
    ? '批准计划'
    : workflowConfirmation.value
      ? confirmationAction.value?.label?.trim() || '确认'
      : '提交信息'
)
function fieldRule(schema: Schema, required: boolean): FormItemRule {
  return {
    validator: (_rule, value, callback) => {
      try {
        validate(schema, value, required)
        callback()
      } catch (cause) {
        callback(cause as Error)
      }
    },
    trigger: 'blur'
  }
}
const requests = computed(() => props.pending.card?.requests ?? [])
for (const request of requests.value)
  fields[request.id] = Object.fromEntries(
    Object.entries(request.requested_schema.properties ?? {}).map(([key, value]) => [
      key,
      value.default ?? undefined
    ])
  )
const initialFields = JSON.stringify(fields)
async function send(decision: string) {
  if (props.busy || props.disabled || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    if (decision === 'accept' && !(await validateForm(formRef.value))) return
    if (props.busy || props.disabled) return
    const identity = pendingIdentity(props.pending)
    if (props.pending.interaction_type === 'plan_review')
      emit('submit', { ...identity, plan_review: { decision, feedback: feedback.value } })
    else if (props.pending.interaction_type === 'tool_input') {
      if (!requests.value.length) throw new Error('补充信息表单无效，请重新读取会话。')
      const responses = Object.fromEntries(
        requests.value.map((request) => {
          const content: Record<string, unknown> = {}
          if (decision === 'accept')
            for (const [key, schema] of Object.entries(request.requested_schema.properties ?? {})) {
              const value = fields[request.id]?.[key]
              validate(schema, value, request.requested_schema.required?.includes(key) ?? false)
              if (value !== '' && value !== undefined)
                content[key] =
                  schema.type === 'number' || schema.type === 'integer' ? Number(value) : value
            }
          return [
            request.id,
            {
              method: 'elicitation/create',
              result: {
                schema_id: 'application/json',
                canonical_json: JSON.stringify(
                  decision === 'accept' ? { action: 'accept', content } : { action: 'decline' }
                )
              }
            }
          ]
        })
      )
      emit('submit', {
        ...identity,
        tool_input: { wait: { ...props.pending.tool_wait }, responses }
      })
    } else if (props.pending.suspend_ref) {
      emit('submit', {
        ...identity,
        workflow_input: {
          wait: { ...props.pending.tool_wait },
          execution_ref: props.pending.workflow_execution_id,
          suspend_ref: props.pending.suspend_ref,
          execution_revision: props.pending.execution_revision,
          action_key: props.pending.resume_action_key,
          response: {
            schema_id: 'application/json',
            canonical_json: JSON.stringify({ action: 'confirm', response: feedback.value })
          }
        }
      })
    } else if (props.pending.interaction_type === 'user_input') {
      if (!feedback.value.trim()) throw new Error('请填写补充信息。')
      emit('submit', {
        ...identity,
        human_input: { wait: { ...props.pending.tool_wait }, text: feedback.value }
      })
    } else emit('submit', { ...identity, response: feedback.value })
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '请检查输入。'
  } finally {
    submitting.value = false
    await nextTick()
    // 校验期间禁用输入；恢复可编辑后再定位错误，避免浏览器拒绝聚焦禁用控件。
    const invalid = formRef.value?.$el.querySelector<HTMLElement>(
      '[aria-invalid="true"]:not(:disabled)'
    )
    invalid?.focus({ preventScroll: true })
    invalid?.closest('.chat-field')?.scrollIntoView?.({ block: 'center' })
  }
}
function validate(schema: Schema, value: unknown, required: boolean) {
  const label = schema.title ?? '字段'
  if (required && (value === '' || value === null || value === undefined))
    throw new Error(`${label}为必填项。`)
  if (value === '' || value === null || value === undefined) return
  if (schema.enum && !schema.enum.includes(value as string | number | boolean))
    throw new Error(`${label}请选择提供的选项。`)
  if (schema.type === 'integer' && !Number.isInteger(Number(value)))
    throw new Error(`${label}必须是整数。`)
  if (
    ['number', 'integer'].includes(schema.type ?? '') &&
    (!Number.isFinite(Number(value)) ||
      (typeof schema.minimum === 'number' && Number(value) < schema.minimum) ||
      (typeof schema.maximum === 'number' && Number(value) > schema.maximum))
  )
    throw new Error(`${label}超出允许范围。`)
  if (
    schema.type === 'string' &&
    ((typeof schema.minLength === 'number' && String(value).length < schema.minLength) ||
      (typeof schema.maxLength === 'number' && String(value).length > schema.maxLength))
  )
    throw new Error(`${label}长度不符合要求。`)
}
</script>
<template>
  <section class="pending-card" aria-label="需要你的确认" :aria-busy="busy || submitting">
    <header class="pending-header">
      <h3>
        <MessageSquare :size="14" aria-hidden="true" />
        {{ heading }}
      </h3>
      <span v-if="busy || submitting" class="pending-status" role="status">提交中…</span>
      <ChatButton
        text
        class="pending-stop"
        title="停止此等待任务"
        aria-label="停止此等待任务"
        :disabled="cancelDisabled || busy || submitting"
        @click="emit('cancel')"
        ><Square :size="12" aria-hidden="true"
      /></ChatButton>
    </header>
    <p v-if="question && !questionDisplayed && !workflowConfirmation" class="pending-question">
      {{ question }}
    </p>
    <Markdown v-if="workflowConfirmation && pending.card?.body" :text="pending.card.body" />
    <ChatAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <Markdown v-if="pending.interaction_type === 'plan_review'" :text="pending.card?.plan" />
    <ChatForm
      ref="formRef"
      :model="model"
      :disabled="busy || disabled || submitting"
      label-position="top"
      :class="{ 'pending-form--simple': simpleInput }"
      @submit.prevent="send(pending.interaction_type === 'plan_review' ? 'approve' : 'accept')"
    >
      <template v-if="pending.interaction_type === 'tool_input'"
        ><div v-for="request in requests" :key="request.id">
          <p v-if="request.message !== question" class="pending-request">{{ request.message }}</p>
          <ChatFormItem
            v-for="(schema, key) in request.requested_schema.properties"
            :key="key"
            :validate-on-blur="false"
            :label="schema.title || String(key)"
            :prop="['fields', request.id, String(key)]"
            :required="request.requested_schema.required?.includes(String(key))"
            :rules="[
              fieldRule(schema, request.requested_schema.required?.includes(String(key)) ?? false)
            ]"
          >
            <ChatSelect
              v-described-by="helpId(request.id, String(key))"
              v-if="schema.enum || schema.type === 'boolean'"
              :model-value="fields[request.id]![key] as string | number | boolean"
              :clearable="!request.requested_schema.required?.includes(String(key))"
              :value-on-clear="() => undefined"
              placeholder="请选择"
              :aria-describedby="helpId(request.id, String(key))"
              @update:model-value="fields[request.id]![key] = $event"
              ><ChatOption
                v-for="(value, index) in schema.enum ?? [true, false]"
                :key="String(value)"
                :value="value"
                :label="
                  schema.enumNames?.[index] ||
                  (typeof value === 'boolean' ? (value ? '是' : '否') : String(value))
                "
            /></ChatSelect>
            <ChatInput
              v-described-by="helpId(request.id, String(key))"
              v-else
              :model-value="String(fields[request.id]![key] ?? '')"
              @update:model-value="fields[request.id]![key] = $event"
              :type="
                schema.type === 'integer' || schema.type === 'number'
                  ? 'number'
                  : schema.format === 'password'
                    ? 'password'
                    : schema.format === 'email'
                      ? 'email'
                      : (schema.maxLength ?? 0) > 160
                        ? 'textarea'
                        : 'text'
              "
              :rows="3"
              :step="schema.type === 'integer' ? 1 : 'any'"
              :aria-describedby="helpId(request.id, String(key))"
              :min="schema.minimum"
              :max="schema.maximum"
              :maxlength="schema.maxLength"
              :required="request.requested_schema.required?.includes(String(key))"
            /><FieldTip :id="helpId(request.id, String(key))">{{
              schemaFieldHelp(
                schema,
                request.requested_schema.required?.includes(String(key)) ?? false
              )
            }}</FieldTip>
          </ChatFormItem>
        </div></template
      >
      <ChatFormItem
        v-else
        class="pending-feedback"
        :validate-on-blur="false"
        :label="workflowConfirmation ? '补充说明（可选）' : '补充说明'"
        prop="feedback"
        :rules="
          feedbackRequired
            ? [{ required: true, whitespace: true, message: '请填写补充说明', trigger: 'blur' }]
            : []
        "
        ><ChatInput
          type="textarea"
          v-model="feedback"
          :placeholder="workflowConfirmation ? '可填写确认说明' : '补充你的需求'"
          :rows="2"
          autosize
          :required="feedbackRequired"
      /></ChatFormItem>
      <div class="actions">
        <ChatButton
          native-type="submit"
          type="primary"
          :class="{ 'pending-send': simpleInput }"
          :title="simpleInput ? '提交信息' : undefined"
          :aria-label="simpleInput ? '提交信息' : undefined"
          :loading="busy || submitting"
          ><ArrowUp v-if="simpleInput && !busy && !submitting" :size="18" aria-hidden="true" />
          <template v-else-if="!simpleInput">{{ submitLabel }}</template></ChatButton
        ><ChatButton
          v-if="pending.interaction_type === 'plan_review'"
          native-type="button"
          @click="send('keep_planning')"
          >继续规划</ChatButton
        ><ChatButton
          v-if="pending.interaction_type === 'tool_input'"
          native-type="button"
          @click="send('decline')"
          >拒绝提供</ChatButton
        >
      </div>
    </ChatForm>
  </section>
</template>

<style scoped>
.pending-card {
  margin: 16px 0;
  padding: 0 0 0 16px;
  border-left: 2px solid var(--border-strong);
  overflow-wrap: anywhere;
}
.pending-header {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 28px;
  margin-bottom: 8px;
}
.pending-header h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.6;
  color: var(--muted);
}
.pending-header h3 svg {
  flex: none;
}
.pending-status {
  margin-left: auto;
  color: var(--muted);
  font-size: 12px;
}
.pending-stop {
  width: 28px;
  height: 28px;
  min-height: 28px;
  margin-left: auto;
  padding: 0;
  color: var(--muted);
}
.pending-status + .pending-stop {
  margin-left: 0;
}
.pending-question,
.pending-request {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
}
.pending-card :deep(.chat-alert) {
  margin-bottom: 12px;
}
.pending-card :deep(.chat-field) {
  margin-bottom: 12px;
}
.pending-card :deep(.chat-textarea__inner) {
  min-height: 56px;
  max-height: 160px;
  padding: 10px 12px;
}
.pending-form--simple {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 36px;
  align-items: end;
  gap: 8px;
}
.pending-form--simple :deep(.chat-field) {
  margin-bottom: 0;
}
/* 视觉上省去重复标签，真实 label 仍用于读屏和字段错误定位。 */
.pending-form--simple :deep(.chat-field__label) {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.pending-send {
  width: 36px;
  height: 36px;
  min-height: 36px;
  padding: 0;
}
@media (max-width: 640px) {
  .pending-card {
    padding-left: 12px;
  }
}
@media (pointer: coarse) {
  .pending-form--simple {
    grid-template-columns: minmax(0, 1fr) 44px;
  }
  .pending-send,
  .pending-stop {
    width: 44px;
    height: 44px;
  }
}
</style>
