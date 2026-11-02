<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'
import { message } from '@skillforge/shared/feedback'
import { computed, onBeforeUnmount, reactive, ref, useId, watch } from 'vue'
import { guardFormEnter, useUnsavedChanges } from '@skillforge/shared/admin'
import { HttpClientError } from '@skillforge/shared/utils/http'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import MarkdownContent from '@skillforge/shared/components/MarkdownContent.vue'
import ToolInputForm from '../ToolInputForm.vue'
import {
  api,
  authSessionKey,
  type ChatHistoryResponse,
  type RuntimePendingInteraction
} from '../../api'
import type { TrajectoryDetail } from '@skillforge/shared/observation/types'
import {
  formatObservationCosts,
  observationCostCoverage
} from '@skillforge/shared/observation/presentation'
import type { ChatHitlResponse } from '@skillforge/shared/types/chatCollaboration'
import type {
  WorkflowTestBudget,
  WorkflowTestOptions,
  WorkflowTestReceipt,
  WorkflowTestSource
} from '../../api/workflowTests'
import { workflowConfigFields, workflowInstallationConfig } from '../../utils/workflow'

const props = defineProps<{
  modelValue: boolean
  workflowId: string
  draftRevision?: number
  dirty: boolean
  disabled?: boolean
  publications: Array<{ id: string; label: string }>
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; save: [] }>()
const rangeId = useId()
const open = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})
const sourceChoice = ref('draft'),
  options = ref<WorkflowTestOptions>(),
  receipt = ref<WorkflowTestReceipt>()
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  readError = ref('')
const userInput = ref(''),
  parameters = reactive<Record<string, unknown>>({}),
  parameterJson = ref('{}'),
  jsonMode = ref(false)
const userInputEdited = ref(false)
const formRequiresJson = ref(false)
const config = reactive<Record<string, unknown>>({}),
  selections = reactive<Record<string, string>>({})
const invalidatedSlots = reactive<Record<string, boolean>>({})
const budget = ref<WorkflowTestBudget>(),
  history = ref<ChatHistoryResponse>(),
  trace = ref<TrajectoryDetail>()
const replies = reactive<Record<string, string>>({}),
  replyUnknown = reactive<Record<string, boolean>>({})
interface Recovery {
  requestId: string
  testSessionId?: string
  initial: 'not_sent' | 'sending' | 'submitted' | 'unknown'
  closing: boolean
  replyAttempts?: string[]
}
const recovery = ref<Recovery>()
let disposed = false,
  epoch = 0,
  controller: AbortController | undefined,
  timer: ReturnType<typeof setTimeout> | undefined
interface FormSnapshot {
  userInput: string
  parameters: Record<string, unknown> | string
  config: Record<string, unknown>
  selections: Record<string, string>
  budget?: WorkflowTestBudget
}
const formDrafts = new Map<string, string>()
const formBaselines = new Map<string, FormSnapshot>()
const cachedForms = new Map<string, string>()
let submittedForm: { requestId: string; key: string; snapshot: FormSnapshot } | undefined
let currentForm = ''
let optionsKey = ''
const POLL_MS = 3000
const budgetFields: Array<{ key: keyof WorkflowTestBudget; label: string }> = [
  { key: 'maxBusinessModelCalls', label: '模型调用上限' },
  { key: 'maxToolCalls', label: '工具调用上限' },
  { key: 'maxKnowledgeCalls', label: '知识检索上限' },
  { key: 'maxTotalTokens', label: 'Token 总量上限' },
  { key: 'maxWallTimeSeconds', label: '总时长上限（秒）' }
]
const source = computed<WorkflowTestSource | undefined>(() =>
  sourceChoice.value === 'draft'
    ? props.draftRevision === undefined
      ? undefined
      : { kind: 'draft', draftRevision: props.draftRevision }
    : { kind: 'publication', definitionVersionId: sourceChoice.value }
)
const sourceKey = computed(() => JSON.stringify([props.workflowId, source.value]))
const sourceOptions = computed(() => [
  ...(props.draftRevision === undefined
    ? []
    : [{ value: 'draft', label: '已保存草稿 · 修订 ' + props.draftRevision }]),
  ...props.publications.map((item) => ({ value: item.id, label: item.label }))
])
const needsSave = computed(
  () => sourceChoice.value === 'draft' && (props.dirty || props.draftRevision === undefined)
)
const sourceLabel = computed(() => {
  const value = receipt.value?.source ?? options.value?.source
  return value
    ? value.kind === 'draft'
      ? `已保存草稿 · 修订 ${value.draftRevision}`
      : `发布版本 ${value.definitionVersionId}`
    : '请选择已保存的测试目标'
})
const closed = computed(() => receipt.value?.closeStatus === 'closed')
const locked = computed(() => busy.value || !!recovery.value || !!props.disabled)
const statusLabels: Record<string, string> = {
  preparing: '准备中',
  ready: '资源已创建',
  running: '运行中',
  waiting: '等待回复',
  completed: '已完成',
  failed: '失败',
  cancelling: '正在关闭',
  closed: '已关闭'
}
const configFields = computed(() =>
  options.value ? workflowConfigFields(JSON.stringify(options.value.configSchema ?? [])) : []
)
interface InputField {
  key: string
  label: string
  type: string
  required: boolean
  choices?: Array<string | number | boolean>
  minimum?: number
  maximum?: number
}
const inputFields = computed<InputField[] | undefined>(() => {
  const schema = options.value?.inputSchema
  if (
    !schema ||
    schema.type !== 'object' ||
    schema.$ref ||
    schema.anyOf ||
    schema.oneOf ||
    schema.allOf
  )
    return undefined
  const fields = schema.properties
  if (fields !== undefined && (!fields || typeof fields !== 'object' || Array.isArray(fields)))
    return undefined
  const result: InputField[] = []
  for (const [key, raw] of Object.entries(fields ?? {})) {
    if (key === 'user_input') continue
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined
    const field = raw as Record<string, unknown>
    if (
      !['string', 'boolean', 'number', 'integer'].includes(String(field.type)) ||
      field.$ref ||
      field.anyOf ||
      field.oneOf
    )
      return undefined
    const choices = Array.isArray(field.enum) ? field.enum : undefined
    if (choices?.some((value) => !['string', 'number', 'boolean'].includes(typeof value)))
      return undefined
    result.push({
      key,
      label: typeof field.title === 'string' ? field.title : key,
      type: String(field.type),
      required: Array.isArray(schema.required) && schema.required.includes(key),
      choices: choices as InputField['choices'],
      minimum: typeof field.minimum === 'number' ? field.minimum : undefined,
      maximum: typeof field.maximum === 'number' ? field.maximum : undefined
    })
  }
  return result
})
const useJson = computed(
  () => jsonMode.value || (options.value ? inputFields.value === undefined : formRequiresJson.value)
)
const pending = computed(() =>
  (history.value?.pending_interactions ?? []).filter(
    (item): item is RuntimePendingInteraction => item.interaction_type !== 'plan_review'
  )
)
const costSummary = computed(() => {
  return formatObservationCosts(trace.value?.usage_summary.amount_by_currency)
})
const costCoverage = computed(() =>
  observationCostCoverage(trace.value?.usage_summary.cost_coverage)
)
const initialUnknown = computed(() =>
  ['sending', 'unknown'].includes(recovery.value?.initial ?? '')
)
const canStartExisting = computed(
  () =>
    receipt.value?.status === 'ready' &&
    recovery.value?.initial === 'not_sent' &&
    !recovery.value.closing &&
    receipt.value.closeStatus === 'open'
)
function storageKey() {
  return (
    'noewise:workflow-test:' +
    JSON.stringify([localStorage.getItem(authSessionKey), props.workflowId])
  )
}
function persist(value: Recovery | undefined) {
  if (value) sessionStorage.setItem(storageKey(), JSON.stringify(value))
  else sessionStorage.removeItem(storageKey())
  recovery.value = value
}
function restoreRecovery() {
  const text = sessionStorage.getItem(storageKey())
  if (!text) return
  const value: unknown = JSON.parse(text)
  if (
    value &&
    typeof value === 'object' &&
    'requestId' in value &&
    typeof value.requestId === 'string' &&
    'initial' in value &&
    ['not_sent', 'sending', 'submitted', 'unknown'].includes(String(value.initial))
  )
    recovery.value = value as Recovery
  for (const key of recovery.value?.replyAttempts ?? []) replyUnknown[key] = true
}
function errorText(reason: unknown, fallback: string) {
  if (reason instanceof HttpClientError) {
    reason.claimFeedback()
    return reason.message
  }
  return reason instanceof Error &&
    !(reason instanceof TypeError) &&
    !(reason instanceof SyntaxError)
    ? reason.message
    : fallback
}
function definiteRejection(reason: unknown) {
  return reason instanceof HttpClientError && [400, 401, 403, 404, 422].includes(reason.status)
}
function claim(reason: unknown) {
  if (reason instanceof HttpClientError) reason.claimFeedback()
}
function ownedRead<T>(promise: Promise<T>): Promise<T> {
  return promise.catch((reason: unknown) => {
    claim(reason)
    throw reason
  })
}
function stopReads() {
  epoch++
  controller?.abort()
  if (timer) clearTimeout(timer)
  timer = undefined
}
function candidateKey(
  candidate: WorkflowTestOptions['capabilitySlots'][number]['candidates'][number]
) {
  return JSON.stringify([
    candidate.capability,
    candidate.toolDeploymentId,
    candidate.toolDeploymentRevision,
    candidate.knowledgeRevision
  ])
}
function matchesSource(value: WorkflowTestOptions['source'], target: WorkflowTestSource) {
  return (
    value.workflowId === props.workflowId &&
    value.kind === target.kind &&
    (target.kind === 'draft'
      ? value.draftRevision === target.draftRevision && value.definitionVersionId === null
      : value.definitionVersionId === target.definitionVersionId && value.draftRevision === null)
  )
}
/** 比较实际提交内容，JSON 的缩进、键顺序和编辑追踪位不属于用户修改。 */
function formFingerprint(value: FormSnapshot): string {
  return JSON.stringify(value, (_key, entry: unknown) =>
    entry && typeof entry === 'object' && !Array.isArray(entry)
      ? Object.fromEntries(
          Object.entries(entry).sort(([left], [right]) => left.localeCompare(right))
        )
      : entry
  )
}
function snapshotForm(): FormSnapshot {
  let inputs: Record<string, unknown> | string = parameters
  if (useJson.value) {
    try {
      inputs = objectInput(parameterJson.value)
    } catch {
      // 尚未修好的 JSON 原文也需要离页保护，但不在读取 dirty 时触发反馈。
      inputs = parameterJson.value
    }
  }
  return JSON.parse(
    JSON.stringify({
      userInput: userInput.value,
      parameters: inputs,
      config,
      selections,
      budget: budget.value
    })
  ) as FormSnapshot
}
function formDirty() {
  if (
    currentForm &&
    formBaselines.has(currentForm) &&
    formFingerprint(snapshotForm()) !== formFingerprint(formBaselines.get(currentForm)!)
  )
    return true
  return [...cachedForms].some(
    ([key, value]) =>
      key !== currentForm &&
      formBaselines.has(key) &&
      value !== formFingerprint(formBaselines.get(key)!)
  )
}
function activateForm() {
  const key = JSON.stringify([props.workflowId, sourceChoice.value])
  if (key === currentForm) return
  if (currentForm) cachedForms.set(currentForm, formFingerprint(snapshotForm()))
  if (currentForm)
    formDrafts.set(
      currentForm,
      JSON.stringify({
        userInput: userInput.value,
        edited: userInputEdited.value,
        parameters,
        parameterJson: parameterJson.value,
        config,
        selections,
        invalidatedSlots,
        budget: budget.value,
        jsonMode: jsonMode.value,
        requiresJson: formRequiresJson.value
      })
    )
  currentForm = key
  for (const map of [parameters, config, selections, invalidatedSlots])
    Object.keys(map).forEach((key) => delete map[key])
  const value = formDrafts.get(key)
  const draft = value
    ? (JSON.parse(value) as {
        userInput: string
        edited: boolean
        parameters: Record<string, unknown>
        parameterJson: string
        config: Record<string, unknown>
        selections: Record<string, string>
        invalidatedSlots?: Record<string, boolean>
        budget?: WorkflowTestBudget
        jsonMode: boolean
        requiresJson: boolean
      })
    : undefined
  userInput.value = draft?.userInput ?? ''
  userInputEdited.value = draft?.edited ?? false
  Object.assign(parameters, draft?.parameters)
  Object.assign(config, draft?.config)
  Object.assign(selections, draft?.selections)
  Object.assign(invalidatedSlots, draft?.invalidatedSlots)
  parameterJson.value = draft?.parameterJson ?? '{}'
  budget.value = draft?.budget
  jsonMode.value = draft?.jsonMode ?? false
  formRequiresJson.value = draft?.requiresJson ?? false
  options.value = undefined
}
function setUserInput(value: string) {
  userInput.value = value
  userInputEdited.value = true
}
function numberValue(value: unknown) {
  return typeof value === 'number' ? value : undefined
}
function selectValue(value: unknown) {
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
    ? value
    : undefined
}
function textValue(value: unknown) {
  return typeof value === 'string' || typeof value === 'number' ? value : ''
}
function objectInput(text: string): Record<string, unknown> {
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    throw Error('测试参数格式不正确，请填写完整的 JSON 对象')
  }
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw Error('测试参数必须为 JSON 对象')
  const check = (entry: unknown) => {
    if (
      typeof entry === 'number' &&
      (!Number.isFinite(entry) || (Number.isInteger(entry) && !Number.isSafeInteger(entry)))
    )
      throw Error('测试参数包含无法精确提交的数值，请检查数值范围')
    if (entry && typeof entry === 'object') Object.values(entry).forEach(check)
  }
  check(value)
  return value as Record<string, unknown>
}
function toggleJson(value: boolean) {
  if (value) parameterJson.value = JSON.stringify(parameters, null, 2)
  else {
    try {
      const parsed = objectInput(parameterJson.value)
      for (const [key, entry] of Object.entries(parsed)) {
        const field = inputFields.value?.find((item) => item.key === key)
        if (
          !field ||
          typeof entry !== (field.type === 'integer' ? 'number' : field.type) ||
          (field.type === 'integer' && !Number.isInteger(entry)) ||
          (field.choices && !field.choices.includes(entry as string | number | boolean))
        )
          throw Error(`参数“${field?.label ?? key}”无法在表单中表示，请在 JSON 中调整后再切换`)
      }
      Object.keys(parameters).forEach((key) => delete parameters[key])
      Object.assign(parameters, parsed)
    } catch (reason) {
      error.value = errorText(reason, '无法读取测试参数')
      return
    }
  }
  error.value = ''
  jsonMode.value = value
}
async function loadOptions() {
  if (!props.modelValue || recovery.value || !props.workflowId || !source.value) return
  stopReads()
  const version = epoch,
    workflow = props.workflowId,
    target = source.value
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const value = await api.workflowTests.options(workflow, target, {
      signal: controller.signal,
      silent: true
    })
    if (disposed || version !== epoch || workflow !== props.workflowId) return
    if (!matchesSource(value.source, target))
      throw Error('测试选项与所选版本不一致，请重新读取测试选项')
    options.value = value
    formRequiresJson.value = inputFields.value === undefined
    optionsKey = sourceKey.value
    const baseline = formBaselines.get(currentForm)
    if (!budget.value) {
      budget.value = { ...value.budget.recommended }
      if (baseline) baseline.budget = { ...value.budget.recommended }
    }
    for (const field of configFields.value)
      if (!Object.hasOwn(config, field.key) && field.default !== undefined) {
        config[field.key] = field.default
        if (baseline) baseline.config[field.key] = field.default
      }
    for (const [key, raw] of Object.entries(value.inputSchema.properties ?? {})) {
      if (key === 'user_input') {
        if (
          !userInputEdited.value &&
          raw &&
          typeof raw === 'object' &&
          'default' in raw &&
          typeof raw.default === 'string'
        ) {
          userInput.value = raw.default
          if (baseline) baseline.userInput = raw.default
        }
      } else if (
        raw &&
        typeof raw === 'object' &&
        'default' in raw &&
        !Object.hasOwn(parameters, key)
      ) {
        parameters[key] = raw.default
        if (baseline && typeof baseline.parameters !== 'string')
          baseline.parameters[key] = JSON.parse(JSON.stringify(raw.default)) as unknown
      }
    }
    if (parameterJson.value === '{}') parameterJson.value = JSON.stringify(parameters, null, 2)
    for (const slot of value.capabilitySlots) {
      const hadSelection = !!selections[slot.slot]
      if (
        selections[slot.slot] &&
        !slot.candidates.some((candidate) => candidateKey(candidate) === selections[slot.slot])
      ) {
        delete selections[slot.slot]
        invalidatedSlots[slot.slot] = true
        if (baseline) delete baseline.selections[slot.slot]
      }
      if (
        !hadSelection &&
        !invalidatedSlots[slot.slot] &&
        slot.required &&
        slot.candidates.length === 1
      ) {
        selections[slot.slot] = candidateKey(slot.candidates[0]!)
        if (baseline) baseline.selections[slot.slot] = selections[slot.slot]!
      }
    }
    // 默认值和候选自动选择完成后才建立基线；重读只同步上面的自动变化。
    if (!baseline) formBaselines.set(currentForm, snapshotForm())
  } catch (reason) {
    claim(reason)
    if (!disposed && version === epoch) {
      options.value = undefined
      error.value = errorText(reason, '无法读取测试选项，请重试')
    }
  } finally {
    if (!disposed && version === epoch) loading.value = false
  }
}
function applyReceipt(value: WorkflowTestReceipt) {
  if (
    !recovery.value ||
    value.requestId !== recovery.value.requestId ||
    value.source.workflowId !== props.workflowId ||
    (recovery.value.testSessionId && value.testSessionId !== recovery.value.testSessionId)
  )
    throw Error('测试回执身份不匹配，请保留原请求并重新查询')
  receipt.value = value
  if (submittedForm?.requestId === value.requestId) {
    formBaselines.set(submittedForm.key, submittedForm.snapshot)
    submittedForm = undefined
  }
  persist({
    ...recovery.value,
    testSessionId: value.testSessionId,
    initial: value.runId ? 'submitted' : recovery.value.initial,
    closing: recovery.value.closing || value.closeStatus !== 'open'
  })
}
function mergeMessages(
  earlier: ChatHistoryResponse['messages'],
  later: ChatHistoryResponse['messages']
) {
  return [
    ...new Map([...earlier, ...later].map((message) => [message.message_id, message])).values()
  ]
}
function scheduleRefresh() {
  if (timer) clearTimeout(timer)
  timer = undefined
  if (
    props.modelValue &&
    recovery.value &&
    !closed.value &&
    !['completed', 'failed'].includes(receipt.value?.status ?? '')
  )
    timer = setTimeout(() => void refresh(), POLL_MS)
}
async function refresh() {
  if (!props.modelValue || !recovery.value || loading.value) return
  const version = epoch,
    request = recovery.value.requestId
  loading.value = true
  readError.value = ''
  controller = new AbortController()
  try {
    const value = recovery.value.testSessionId
      ? await api.workflowTests.get(recovery.value.testSessionId, {
          signal: controller.signal,
          silent: true
        })
      : await api.workflowTests.byRequest(request, { signal: controller.signal, silent: true })
    if (disposed || version !== epoch || request !== recovery.value?.requestId) return
    applyReceipt(value)
    const results = await Promise.allSettled([
      ownedRead(api.chat.history(value.sessionId)),
      ownedRead(
        api.trajectories.detail(
          { session_id: value.sessionId, root_input_id: value.initialInputId },
          { signal: controller.signal, silent: true }
        )
      )
    ])
    if (disposed || version !== epoch || request !== recovery.value?.requestId) return
    const messages = results[0],
      details = results[1]
    if (messages.status === 'fulfilled' && messages.value.session_id === value.sessionId) {
      const previous = history.value?.session_id === value.sessionId ? history.value : undefined
      const overlaps = previous?.messages.some((old) =>
        messages.value.messages.some((latest) => latest.message_id === old.message_id)
      )
      if (previous && messages.value.messages.length && !overlaps) {
        history.value = messages.value
        if (previous.messages.length) message.info('已更新为最新消息。')
      } else
        history.value = {
          ...messages.value,
          messages: mergeMessages(previous?.messages ?? [], messages.value.messages),
          has_more: previous?.has_more ?? messages.value.has_more,
          next_before_message_id: previous
            ? previous.next_before_message_id
            : messages.value.next_before_message_id
        }
    } else if (messages.status === 'rejected')
      readError.value = errorText(messages.reason, '无法读取测试消息，请重新查询')
    if (details.status === 'fulfilled' && details.value.summary.session_id === value.sessionId)
      trace.value = details.value
    else if (details.status === 'rejected')
      readError.value = errorText(details.reason, '无法读取测试用量，请重新查询')
  } catch (reason) {
    claim(reason)
    if (!disposed && version === epoch)
      readError.value =
        reason instanceof HttpClientError && reason.status === 404
          ? '暂未查询到原请求的测试资源，结果仍未确认。请继续按原请求查询，不要重复创建。'
          : errorText(reason, '查询未完成，请继续查询原测试')
    if (reason instanceof HttpClientError) reason.claimFeedback()
  } finally {
    if (!disposed && version === epoch) {
      loading.value = false
      scheduleRefresh()
    }
  }
}
async function olderMessages() {
  const previous = history.value
  if (
    !props.modelValue ||
    !recovery.value ||
    loading.value ||
    busy.value ||
    !previous?.has_more ||
    !previous.next_before_message_id
  )
    return
  const version = epoch,
    request = recovery.value.requestId,
    session = previous.session_id
  loading.value = true
  readError.value = ''
  try {
    const page = await ownedRead(api.chat.history(session, previous.next_before_message_id))
    if (disposed || version !== epoch || request !== recovery.value?.requestId) return
    if (page.session_id !== session) throw Error('较早测试消息与当前会话不一致，请重新读取')
    history.value = {
      ...previous,
      messages: mergeMessages(page.messages, previous.messages),
      has_more: page.has_more,
      next_before_message_id: page.next_before_message_id
    }
  } catch (reason) {
    claim(reason)
    if (!disposed && version === epoch)
      readError.value = errorText(reason, '无法读取较早测试消息，请再次点击“加载较早测试消息”')
  } finally {
    if (!disposed && version === epoch) {
      loading.value = false
      scheduleRefresh()
    }
  }
}
async function startInitial() {
  const value = receipt.value
  if (!value || !canStartExisting.value || busy.value || props.disabled) return
  busy.value = true
  error.value = ''
  persist({ ...recovery.value!, initial: 'sending' })
  try {
    await api.chat.submit({
      session_id: value.sessionId,
      agent_id: value.agentId,
      content: [{ type: 'text', text: value.initialMessage }],
      client_message_id: value.initialInputId,
      context: { end_user_id: value.endUserId },
      references: []
    })
    if (receipt.value?.testSessionId === value.testSessionId)
      persist({ ...recovery.value!, initial: 'submitted' })
  } catch (reason) {
    persist({ ...recovery.value!, initial: 'unknown' })
    error.value = errorText(reason, '初始输入的受理结果尚未确认，请查询原测试状态')
  } finally {
    busy.value = false
    if (props.modelValue) void refresh()
  }
}
async function createAndTest() {
  if (
    !options.value ||
    !source.value ||
    !budget.value ||
    needsSave.value ||
    props.disabled ||
    busy.value ||
    loading.value ||
    recovery.value
  )
    return
  error.value = ''
  try {
    const parametersValue = useJson.value ? objectInput(parameterJson.value) : { ...parameters }
    if (Object.hasOwn(parametersValue, 'user_input')) {
      const message = parametersValue.user_input
      if (typeof message !== 'string')
        throw Error('JSON 中的测试消息必须为文本，请在上方“测试消息”中填写')
      if (userInputEdited.value && userInput.value !== message)
        throw Error(
          'JSON 中的测试消息与上方内容不同，请统一在“测试消息”中填写，并从 JSON 参数中移除重复消息'
        )
      userInput.value = message
      delete parametersValue.user_input
      parameterJson.value = JSON.stringify(parametersValue, null, 2)
    }
    if (!userInput.value.trim()) throw Error('请填写测试消息')
    if (optionsKey !== sourceKey.value) throw Error('测试目标已变化，请重新读取测试选项')
    for (const field of inputFields.value ?? []) {
      const value = parametersValue[field.key]
      if (field.required && (value === undefined || value === null || value === ''))
        throw Error('请填写测试参数：' + field.label)
      if (typeof value === 'number') {
        if (field.minimum !== undefined && value < field.minimum)
          throw Error(`${field.label}不能小于 ${field.minimum}`)
        if (field.maximum !== undefined && value > field.maximum)
          throw Error(`${field.label}不能大于 ${field.maximum}`)
      }
    }
    for (const field of budgetFields)
      if (
        !Number.isSafeInteger(budget.value[field.key]) ||
        budget.value[field.key] < 1 ||
        budget.value[field.key] > options.value.budget.maximum[field.key]
      )
        throw Error(field.label + ' 必须在服务端允许的范围内')
    const capabilities = options.value.capabilitySlots.flatMap((slot) => {
      const selection = selections[slot.slot]
      if (selection === undefined || selection === '') {
        if (slot.required) throw Error('请选择测试能力：' + slot.slot)
        return []
      }
      const candidate = slot.candidates.find((candidate) => candidateKey(candidate) === selection)
      if (!candidate) throw Error('测试候选已变化，请重新选择：' + slot.slot)
      return [
        {
          slot: slot.slot,
          capability: { ...candidate.capability },
          ...(candidate.toolDeploymentId !== null
            ? { tool_deployment_id: candidate.toolDeploymentId }
            : {}),
          ...(candidate.toolDeploymentRevision !== null
            ? { tool_deployment_revision: candidate.toolDeploymentRevision }
            : {}),
          ...(candidate.knowledgeRevision !== null
            ? { knowledge_revision: candidate.knowledgeRevision }
            : {})
        }
      ]
    })
    const input = {
      requestId: crypto.randomUUID(),
      workflowId: props.workflowId,
      source: source.value,
      config: workflowInstallationConfig(configFields.value, config),
      bindings: { capabilities, workflows: [], agents: [] },
      userInput: userInput.value,
      parameters: parametersValue,
      budget: { ...budget.value }
    }
    const expectedHash = options.value.source.hash
    submittedForm = { requestId: input.requestId, key: currentForm, snapshot: snapshotForm() }
    persist({ requestId: input.requestId, initial: 'not_sent', closing: false })
    busy.value = true
    const value = await api.workflowTests.create(input, { silent: true })
    if (!matchesSource(value.source, input.source) || value.source.hash !== expectedHash)
      throw Error('测试回执目标与所选版本不一致，请查询原请求，勿重复创建')
    applyReceipt(value)
    busy.value = false
    await startInitial()
  } catch (reason) {
    if (definiteRejection(reason) && !receipt.value) persist(undefined)
    error.value = errorText(
      reason,
      recovery.value ? '创建结果尚未确认，请按原请求查询测试状态' : '无法创建测试，请检查输入'
    )
  } finally {
    busy.value = false
  }
}
async function closeTest() {
  if (!receipt.value || busy.value || closed.value) return
  busy.value = true
  error.value = ''
  persist({ ...recovery.value!, closing: true })
  try {
    applyReceipt(await api.workflowTests.close(receipt.value.testSessionId, { silent: true }))
  } catch (reason) {
    error.value = errorText(reason, '关闭结果尚未确认，请查询原测试状态')
  } finally {
    busy.value = false
    if (props.modelValue) void refresh()
  }
}
function waitKey(item: RuntimePendingInteraction) {
  return JSON.stringify([
    item.execution_id,
    item.run_revision,
    item.tool_wait,
    item.workflow_execution_id,
    item.suspend_ref,
    item.execution_revision,
    item.resume_action_key,
    item.checkpoint_revision
  ])
}
function actions(item: RuntimePendingInteraction) {
  return (item.card?.actions ?? []).filter(
    (action) => action.type !== 'link' && typeof action.id === 'string' && action.id
  )
}
const canRespond = computed(
  () =>
    !!receipt.value &&
    receipt.value.closeStatus === 'open' &&
    !recovery.value?.closing &&
    !busy.value &&
    !closed.value
)
async function submitPending(response: ChatHitlResponse, key: string) {
  if (!canRespond.value || replyUnknown[key]) return
  const value = receipt.value!
  busy.value = true
  error.value = ''
  replyUnknown[key] = true
  persist({
    ...recovery.value!,
    replyAttempts: [...new Set([...(recovery.value!.replyAttempts ?? []), key])]
  })
  try {
    await api.chat.submit({
      session_id: value.sessionId,
      hitl_response: response,
      context: { end_user_id: value.endUserId }
    })
    delete replies[key]
  } catch (reason) {
    if (definiteRejection(reason)) {
      delete replyUnknown[key]
      persist({
        ...recovery.value!,
        replyAttempts: recovery.value!.replyAttempts?.filter((value) => value !== key)
      })
      error.value = errorText(reason, '此回复未被受理，请检查输入后重新提交')
    } else {
      claim(reason)
    }
  } finally {
    busy.value = false
    if (props.modelValue) void refresh()
  }
}
function respond(item: RuntimePendingInteraction, action?: string) {
  const key = waitKey(item)
  try {
    if (
      typeof item.run_revision !== 'string' ||
      !/^[1-9][0-9]*$/.test(item.run_revision) ||
      !item.tool_wait?.action_id ||
      !/^[1-9][0-9]*$/.test(item.tool_wait.generation)
    )
      throw Error('待办身份不完整，请重新查询测试状态')
    if (item.interaction_type === 'user_input') {
      if (!replies[key]?.trim()) throw Error('请先填写回复')
      void submitPending(
        {
          execution_id: item.execution_id,
          run_revision: item.run_revision,
          human_input: { wait: { ...item.tool_wait }, text: replies[key]! }
        },
        key
      )
    } else {
      if (
        !item.workflow_execution_id ||
        !item.suspend_ref ||
        !item.execution_revision ||
        !/^[1-9][0-9]*$/.test(item.execution_revision) ||
        !item.resume_action_key
      )
        throw Error('工作流待办身份不完整，请重新查询测试状态')
      void submitPending(
        {
          execution_id: item.execution_id,
          run_revision: item.run_revision,
          workflow_input: {
            wait: { ...item.tool_wait },
            execution_ref: item.workflow_execution_id,
            suspend_ref: item.suspend_ref,
            execution_revision: item.execution_revision,
            action_key: item.resume_action_key,
            response: {
              schema_id: 'application/json',
              canonical_json: JSON.stringify({
                ...(action ? { action } : {}),
                response: replies[key] ?? ''
              })
            }
          }
        },
        key
      )
    }
  } catch (reason) {
    error.value = errorText(reason, '无法提交此待办，请重新查询')
  }
}
function newTest() {
  if (!closed.value) return
  stopReads()
  persist(undefined)
  receipt.value = undefined
  history.value = undefined
  trace.value = undefined
  error.value = ''
  readError.value = ''
  options.value = undefined
  void loadOptions()
}
watch(
  [() => props.modelValue, sourceKey],
  () => {
    stopReads()
    loading.value = false
    if (!props.modelValue) {
      loading.value = false
      return
    }
    activateForm()
    try {
      if (!recovery.value) restoreRecovery()
    } catch {
      error.value = '无法读取原测试请求，请检查站点存储后重试'
      return
    }
    if (recovery.value) void refresh()
    else void loadOptions()
  },
  { immediate: true }
)
useUnsavedChanges({
  dirty: () => formDirty() || Object.values(replies).some((value) => !!value.trim()),
  busy: () => busy.value
})
onBeforeUnmount(() => {
  disposed = true
  stopReads()
})
</script>
<template>
  <ElDrawer
    v-model="open"
    title="工作流测试"
    size="min(800px, 100vw)"
    append-to-body
    :destroy-on-close="false"
  >
    <div class="workflow-test-panel">
      <FeedbackNotice
        v-if="error && !(recovery && (!receipt || initialUnknown))"
        :title="error"
        type="error"
        :closable="false"
      />
      <FeedbackNotice
        v-if="readError && !(recovery && (!receipt || initialUnknown))"
        :title="readError"
        type="warning"
        :closable="false"
      />
      <template v-if="!recovery">
        <ElForm
          label-position="top"
          @submit.prevent="createAndTest"
          @keydown.capture="guardFormEnter"
          :disabled="locked"
        >
          <ElFormItem label="测试目标">
            <ElSelect v-model="sourceChoice" :options="sourceOptions" aria-label="测试目标" />
          </ElFormItem>
          <p v-if="needsSave" class="test-warning" role="status">
            {{
              draftRevision === undefined
                ? '请先保存工作流草稿，再配置测试。'
                : '当前草稿有未保存修改，请先保存后测试。测试消息与参数会保留。'
            }}
          </p>
          <ElButton
            v-if="needsSave"
            v-permission="
              draftRevision === undefined
                ? ['foundation.workflows.create']
                : ['foundation.workflows.update']
            "
            :disabled="disabled || busy"
            @click="emit('save')"
            >先保存草稿</ElButton
          >
          <p v-else-if="dirty" class="admin-field-help">
            本次测试所选发布版本，不包含未保存的草稿修改。
          </p>
          <ElAlert
            v-if="!source && !needsSave"
            title="请先保存工作流草稿，再创建测试。"
            type="info"
            :closable="false"
          />
          <p class="admin-field-help">
            仅测试已保存的确定版本。本阶段不支持脚本、异步任务、并行、子工作流及 Agentic 节点。
          </p>
          <ElSkeleton v-if="loading && !options" :rows="4" animated />
          <ElButton v-if="!options && source && !loading" @click="loadOptions"
            >重新读取测试选项</ElButton
          >
          <template v-if="options">
            <p class="test-target">{{ sourceLabel }}</p>
            <ElFormItem label="测试消息" required>
              <ElInput
                :model-value="userInput"
                @update:model-value="setUserInput"
                type="textarea"
                :rows="3"
                aria-label="测试消息"
                placeholder="填写希望此工作流处理的测试消息"
              />
            </ElFormItem>
            <section aria-label="测试参数">
              <div class="test-section-heading">
                <h3>测试参数</h3>
                <ElButton
                  v-if="inputFields"
                  text
                  :aria-pressed="useJson"
                  @click="toggleJson(!jsonMode)"
                  >{{ useJson ? '按字段填写' : 'JSON 输入' }}</ElButton
                >
              </div>
              <CodeEditor
                v-if="useJson"
                v-model="parameterJson"
                label="测试参数 JSON"
                language="json"
                height="180px"
                :readonly="locked"
              />
              <template v-else>
                <p v-if="!inputFields?.length" class="admin-field-help">
                  此版本没有声明输入字段，可直接填写测试消息。
                </p>
                <ElFormItem
                  v-for="(field, index) in inputFields"
                  :key="field.key"
                  :label="field.label"
                  :required="field.required"
                >
                  <ElSelect
                    v-if="field.choices || field.type === 'boolean'"
                    v-described-by="
                      ['number', 'integer'].includes(field.type) &&
                      (field.minimum !== undefined || field.maximum !== undefined)
                        ? `${rangeId}-${index}`
                        : undefined
                    "
                    :model-value="selectValue(parameters[field.key])"
                    @update:model-value="parameters[field.key] = $event"
                    :aria-label="field.label"
                    clearable
                    :value-on-clear="() => undefined"
                  >
                    <ElOption
                      v-for="value in field.choices ?? [true, false]"
                      :key="String(value)"
                      :value="value"
                      :label="typeof value === 'boolean' ? (value ? '是' : '否') : String(value)"
                    />
                  </ElSelect>
                  <ElInputNumber
                    v-else-if="['number', 'integer'].includes(field.type)"
                    v-described-by="
                      field.minimum !== undefined || field.maximum !== undefined
                        ? `${rangeId}-${index}`
                        : undefined
                    "
                    :model-value="numberValue(parameters[field.key])"
                    :precision="field.type === 'integer' ? 0 : undefined"
                    :aria-label="field.label"
                    :aria-describedby="
                      field.minimum !== undefined || field.maximum !== undefined
                        ? `${rangeId}-${index}`
                        : undefined
                    "
                    @update:model-value="parameters[field.key] = $event ?? undefined"
                  />
                  <ElInput
                    v-else
                    :model-value="textValue(parameters[field.key])"
                    :aria-label="field.label"
                    @update:model-value="parameters[field.key] = $event"
                  />
                  <p
                    v-if="
                      ['number', 'integer'].includes(field.type) &&
                      (field.minimum !== undefined || field.maximum !== undefined)
                    "
                    :id="`${rangeId}-${index}`"
                    class="admin-field-help"
                  >
                    {{ field.minimum !== undefined ? `最小值 ${field.minimum}` : ''
                    }}{{ field.minimum !== undefined && field.maximum !== undefined ? ' · ' : ''
                    }}{{ field.maximum !== undefined ? `最大值 ${field.maximum}` : '' }}
                  </p>
                </ElFormItem>
              </template>
            </section>
            <section v-if="configFields.length" aria-label="测试安装参数">
              <h3>安装参数</h3>
              <ElFormItem
                v-for="field in configFields"
                :key="field.key"
                :label="field.label || field.key"
                :required="field.required"
              >
                <ElSelect
                  v-if="field.type === 'boolean' || field.type === 'enum'"
                  :model-value="selectValue(config[field.key])"
                  @update:model-value="config[field.key] = $event"
                  :aria-label="'安装参数 ' + (field.label || field.key)"
                  clearable
                  :value-on-clear="() => undefined"
                >
                  <ElOption
                    v-for="value in field.type === 'boolean'
                      ? [true, false]
                      : (field.enum_values ?? [])"
                    :key="String(value)"
                    :value="value"
                    :label="typeof value === 'boolean' ? (value ? '是' : '否') : value"
                  />
                </ElSelect>
                <ElInputNumber
                  v-else-if="['integer', 'number'].includes(field.type)"
                  :model-value="numberValue(config[field.key])"
                  :min="field.min"
                  :max="field.max"
                  :precision="field.type === 'integer' ? 0 : undefined"
                  :aria-label="'安装参数 ' + (field.label || field.key)"
                  @update:model-value="config[field.key] = $event ?? undefined"
                />
                <ElInput
                  v-else
                  :model-value="textValue(config[field.key])"
                  :type="field.type === 'textarea' ? 'textarea' : 'text'"
                  :aria-label="'安装参数 ' + (field.label || field.key)"
                  @update:model-value="config[field.key] = $event"
                />
              </ElFormItem>
            </section>
            <section v-if="options.capabilitySlots.length" aria-label="测试能力绑定">
              <h3>测试能力</h3>
              <ElFormItem
                v-for="slot in options.capabilitySlots"
                :key="slot.slot"
                :label="slot.slot"
                :required="slot.required"
              >
                <ElSelect
                  v-model="selections[slot.slot]"
                  filterable
                  :aria-label="'测试能力 ' + slot.slot"
                  :clearable="!slot.required"
                  placeholder="选择服务端允许的测试候选"
                  @change="delete invalidatedSlots[slot.slot]"
                >
                  <ElOption
                    v-for="candidate in slot.candidates"
                    :key="candidateKey(candidate)"
                    :value="candidateKey(candidate)"
                    :label="candidate.label"
                  />
                </ElSelect>
                <p
                  v-if="invalidatedSlots[slot.slot] && !selections[slot.slot]"
                  class="test-warning"
                  role="status"
                >
                  之前选择的测试候选已不可用，请重新选择。
                </p>
                <p v-if="!slot.candidates.length" class="test-warning">
                  暂无可用测试候选，请联系管理员检查测试部署或知识发布版本。
                </p>
              </ElFormItem>
            </section>
            <details class="test-budget">
              <summary>测试额度与限制</summary>
              <p class="admin-field-help">已采用服务端推荐额度，可在允许的范围内调整。</p>
              <div class="admin-form-grid">
                <ElFormItem v-for="field in budgetFields" :key="field.key" :label="field.label">
                  <ElInputNumber
                    v-if="budget"
                    v-model="budget[field.key]"
                    :min="1"
                    :max="options.budget.maximum[field.key]"
                    :precision="0"
                    :aria-label="field.label"
                  />
                </ElFormItem>
              </div>
              <ul>
                <li v-for="restriction in options.restrictions" :key="restriction">
                  {{ restriction }}
                </li>
              </ul>
            </details>
            <p class="test-cost" role="note">{{ options.costNotice }}</p>
            <ElButton
              type="primary"
              native-type="submit"
              :loading="busy"
              :disabled="disabled || needsSave || loading || !source"
              >创建并测试</ElButton
            >
          </template>
        </ElForm>
      </template>
      <section v-else aria-label="测试结果">
        <p v-if="receipt" class="test-target">
          {{ sourceLabel }} · {{ statusLabels[receipt.status] || receipt.status }}
        </p>
        <ElAlert
          v-if="!receipt || initialUnknown"
          title="结果尚未确认"
          type="warning"
          :closable="false"
        >
          <p v-if="readError || error">{{ readError || error }}</p>
          <p>请查询原测试状态。系统会保留原请求，不会重新创建测试或重复发送初始输入。</p>
        </ElAlert>
        <p v-if="receipt?.blockingReason" class="test-warning" role="status">
          {{ receipt.blockingReason }}
        </p>
        <div class="admin-actions">
          <ElButton :loading="loading" :disabled="busy" @click="refresh">查询测试状态</ElButton>
          <ElButton v-if="canStartExisting" type="primary" :loading="busy" @click="startInitial"
            >开始已创建的测试</ElButton
          >
          <ElButton v-if="receipt && !closed" :loading="busy" @click="closeTest"
            >取消并关闭测试</ElButton
          >
          <ElButton v-if="closed" @click="newTest">开始新测试</ElButton>
        </div>
        <p v-if="recovery.closing && !closed" class="test-warning" role="status">
          已请求关闭，正在确认运行是否结束；确认前不会接受新的输入。
        </p>
        <p v-if="receipt && !closed" class="admin-field-help">
          关闭面板后测试仍会继续。可重新打开查询，或选择“取消并关闭测试”。
        </p>
        <template v-if="receipt">
          <ElDescriptions v-if="trace" :column="2" border>
            <ElDescriptionsItem label="模型调用">{{
              trace.usage_summary.calls
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="Token">{{
              trace.usage_summary.tokens ?? '未知'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="费用">
              {{ costSummary }}
              <p class="admin-field-help" role="status">{{ costCoverage }}</p>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="用量未知调用">{{
              trace.usage_summary.unknown_usage_calls
            }}</ElDescriptionsItem>
          </ElDescriptions>
          <RouterLink
            class="test-trace-link"
            :to="{
              path: '/admin/chat/sessions/' + encodeURIComponent(receipt.sessionId) + '/trace',
              query: { root_input_id: receipt.initialInputId }
            }"
            target="_blank"
            rel="noopener noreferrer"
            >查看完整执行追踪与用量（新标签页）</RouterLink
          >
          <details>
            <summary>测试回执</summary>
            <dl>
              <dt>测试会话</dt>
              <dd>{{ receipt.testSessionId }}</dd>
              <dt>源摘要</dt>
              <dd>{{ receipt.source.hash }}</dd>
              <dt>到期时间</dt>
              <dd>{{ receipt.expiresAt }}</dd>
              <dt>运行状态</dt>
              <dd>{{ receipt.runStatus ?? '尚无运行回执' }}</dd>
            </dl>
          </details>
          <section v-for="item in pending" :key="waitKey(item)" class="test-pending">
            <h3>{{ item.card?.title || item.question || '需要你的回复' }}</h3>
            <p v-if="replyUnknown[waitKey(item)]" class="test-warning" role="status">
              此回复已提交，请查询当前待办确认处理结果；不会重复提交。
            </p>
            <ToolInputForm
              v-if="item.interaction_type === 'tool_input'"
              :pending="item"
              :busy="!canRespond || !!replyUnknown[waitKey(item)]"
              @respond="submitPending($event, waitKey(item))"
            />
            <ElForm
              v-else
              label-position="top"
              :disabled="!canRespond || !!replyUnknown[waitKey(item)]"
              @submit.prevent="respond(item)"
              @keydown.capture="guardFormEnter"
            >
              <p>{{ item.card?.body || item.card?.message || item.question }}</p>
              <ElFormItem label="待办回复"
                ><ElInput
                  v-model="replies[waitKey(item)]"
                  type="textarea"
                  :rows="3"
                  :aria-label="'待办回复 ' + item.execution_id"
              /></ElFormItem>
              <ElButton v-if="!actions(item).length" native-type="submit" type="primary"
                >提交回复</ElButton
              >
              <ElButton
                v-for="action in actions(item)"
                :key="action.id"
                @click="respond(item, action.id)"
                >{{ action.label }}</ElButton
              >
            </ElForm>
          </section>
          <section aria-label="测试消息记录">
            <h3>消息</h3>
            <ElButton
              v-if="history?.has_more"
              :loading="loading"
              :disabled="loading || busy"
              @click="olderMessages"
            >
              加载较早测试消息
            </ElButton>
            <p v-if="!history?.messages.length" class="admin-field-help">
              尚无公开消息记录，可查询测试状态更新。
            </p>
            <article v-for="item in history?.messages ?? []" :key="item.message_id">
              <strong>{{
                item.role === 'user' ? '测试输入' : item.role === 'assistant' ? '回复' : item.role
              }}</strong
              ><MarkdownContent :content="item.content" />
            </article>
          </section>
        </template>
      </section>
    </div>
  </ElDrawer>
</template>
<style scoped>
.workflow-test-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
.workflow-test-panel h3 {
  font-size: 15px;
  margin-block: 12px;
}
.test-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.test-target {
  font-weight: 600;
  overflow-wrap: anywhere;
}
.test-warning {
  color: var(--el-color-warning-dark-2);
  line-height: 1.5;
}
.test-cost {
  line-height: 1.6;
  color: var(--admin-text);
}
.test-trace-link {
  color: var(--el-color-primary);
  text-underline-offset: 3px;
}
.test-trace-link:hover {
  color: var(--el-color-primary-light-3);
}
.test-trace-link:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 3px;
  border-radius: 2px;
}
.test-budget summary,
details summary {
  cursor: pointer;
  min-height: 28px;
}
.test-budget ul {
  padding-left: 20px;
  line-height: 1.5;
}
.test-pending {
  padding: 12px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
article {
  margin-block: 12px;
  padding-block: 8px;
  border-bottom: 1px solid var(--admin-border);
}
dd {
  margin: 4px 0 12px;
  overflow-wrap: anywhere;
}
</style>
