<script setup lang="ts">
import { vDescribedBy } from './describedBy'

import FieldTip from './FieldTip.vue'

import {
  ChatMessageNotice,
  ChatButton,
  ChatDescriptions,
  ChatDescriptionsItem,
  ChatDrawer,
  ChatForm,
  ChatFormItem,
  ChatInput,
  ChatInputNumber,
  ChatSelect,
  ChatTabs,
  ChatTabPane,
  type FormInstance
} from './controls'
import { computed, onBeforeUnmount, ref, watch, useId } from 'vue'
import { query, type Client } from '../client'
import { GOAL_DEFAULT_MAX_ROUNDS, GOAL_MAX_ROUNDS } from '../commands'
import type { GoalStatus, Reference, Work } from '../protocol'
import { mayDiscard, validateForm } from './panel'
import TaskPlanCard from './TaskPlanCard.vue'
import RunBudgetPanel from './RunBudgetPanel.vue'
import { mergeWorkPlans } from '../workPlans'
const props = defineProps<{
  modelValue: boolean
  client: Client
  session: string
  run: string
  clientRef: string
  workPlans?: Work[]
}>()
const emit = defineEmits<{
  reference: [value: Reference]
  'update:modelValue': [value: boolean]
  task: [id: string]
}>()
const section = ref('goal'),
  busy = ref(false),
  error = ref(''),
  feedback = ref('')
let abort = new AbortController()
let disposed = false
let deliveryRequest: AbortController | undefined
const goal = ref<GoalStatus>(),
  objective = ref(''),
  rounds = ref(GOAL_DEFAULT_MAX_ROUNDS),
  delivery = ref<Record<string, unknown>>()
const goalStateLabels: Record<string, string> = {
  ACTIVE: '进行中',
  PAUSED: '已暂停',
  COMPLETE: '已完成',
  BLOCKED: '受阻'
}
/** 宿主停止原因在界面使用业务文案，保留模型或用户提供的具体业务说明。 */
const goalReasonLabels: Record<string, string> = {
  run_cancelled: '本次执行已取消。',
  run_failed: '本次执行失败。',
  run_terminated: '本次执行已结束。',
  run_completed: '本次执行未形成可发布的成果。',
  publication_validation_failed: '成果文件校验未通过，本次交付未发布。',
  publication_reservation_lost: '成果版本已发生变化，本次交付未发布。',
  completed_result_reference_missing: '成果交付确认缺失，本次交付未发布。',
  budget_closing: '当前预算仅够收尾，无法继续自动执行。',
  driver_error: '目标暂时无法继续，请检查当前执行情况。',
  round_launch_failed: '下一轮未能启动。',
  round_limit: '已达到自动续行轮数上限。'
}
const goalReason = computed(() => {
  const reason = goal.value?.goal?.reason ?? ''
  return goalReasonLabels[reason] ?? reason
})
const works = ref<Work[]>([]),
  work = ref<Work>(),
  workText = ref('')
const workOptions = computed(() =>
  works.value.map((item, index) => ({
    value: item.work_id,
    label: `${item.title || '任务计划 ' + (index + 1)} · ${item.status}`
  }))
)
const keys = new Map<string, string>()
const frozen = new Map<string, { path: string; body: unknown }>()
async function command<T>(scope: string, path: string, body: unknown) {
  const request = frozen.get(scope) ?? { path, body }
  frozen.set(scope, request)
  const value = await props.client.json<T>(request.path, 'POST', request.body, abort.signal)
  frozen.delete(scope)
  keys.delete(scope)
  return value
}
const goalRef = ref<FormInstance>()
const goalModel = computed(() => ({ objective: objective.value, rounds: rounds.value }))
const required = { required: true, whitespace: true, message: '请填写内容', trigger: 'blur' }
const dirty = computed(() =>
  Boolean(
    objective.value.trim() || rounds.value !== GOAL_DEFAULT_MAX_ROUNDS || workText.value.trim()
  )
)
async function requestClose() {
  if (!(await mayDiscard(busy.value, dirty.value))) return false
  objective.value = ''
  rounds.value = GOAL_DEFAULT_MAX_ROUNDS
  workText.value = ''
  emit('update:modelValue', false)
  return true
}
async function beforeClose(done: () => void) {
  if (await requestClose()) done()
}
async function selectWork(id: string) {
  if (busy.value || id === work.value?.work_id) return
  if (!(await mayDiscard(false, Boolean(workText.value.trim())))) return
  workText.value = ''
  work.value = works.value.find((item) => item.work_id === id)
}
function beforeTabLeave() {
  return !busy.value
}
const deliveryState = (key: string) => {
  const value = delivery.value?.[key]
  return value && typeof value === 'object' && 'status' in value
    ? String(value.status)
    : value === null || value === undefined
      ? '—'
      : String(value)
}
defineExpose({ requestClose })
const key = (scope: string) => {
  let value = keys.get(scope)
  if (!value) {
    value = crypto.randomUUID()
    keys.set(scope, value)
  }
  return value
}
async function operation(action: () => Promise<void>) {
  if (busy.value) return
  const token = abort.signal
  deliveryRequest = undefined
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await action()
  } catch (cause) {
    if (!disposed && !token.aborted)
      error.value =
        cause instanceof Error ? cause.message : '未能确认操作结果，请先核对当前会话状态。'
  } finally {
    if (!disposed && !token.aborted) busy.value = false
  }
}
async function read() {
  const signal = abort.signal
  await operation(async () => {
    if (section.value === 'goal')
      goal.value = await props.client.json<GoalStatus>(
        `/chat/goals?${query({ session_id: props.session })}`,
        'GET',
        undefined,
        abort.signal
      )
    if (section.value === 'work') {
      const list = await props.client.json<{ items: Work[] }>(
        `/chat/work/history?${query({ session_id: props.session })}`,
        'GET',
        undefined,
        abort.signal
      )
      if (signal.aborted) return
      works.value = mergeWorkPlans(works.value, list.items)
      work.value =
        works.value.find((item) => item.work_id === work.value?.work_id) ?? works.value[0]
    }
  })
}
async function choose() {
  await read()
}
async function goalCommand(action: 'create' | 'pause' | 'resume') {
  if (busy.value || (action === 'create' && !(await validateForm(goalRef.value)))) return
  await operation(async () => {
    const submitted = objective.value,
      scope = `goal:${action}:${submitted}:${rounds.value}:${goal.value?.goal?.revision ?? 0}`
    const value = await command<GoalStatus>(scope, `/chat/goals/${action}`, {
      session_id: props.session,
      command_id: key(scope),
      ...(action === 'create'
        ? { objective: submitted.trim(), max_rounds: rounds.value }
        : { expected_revision: goal.value?.goal?.revision })
    })
    if (disposed) return
    goal.value = value
    if (action === 'create' && objective.value === submitted) {
      objective.value = ''
      rounds.value = GOAL_DEFAULT_MAX_ROUNDS
    }
    feedback.value =
      action === 'create'
        ? '持续目标已创建。'
        : action === 'pause'
          ? '持续目标已暂停。'
          : '持续目标已继续。'
  })
}
async function readDelivery() {
  if (busy.value || !props.run) return
  const session = props.session,
    run = props.run,
    client = props.client,
    request = new AbortController()
  deliveryRequest = request
  const current = () =>
    !disposed &&
    !request.signal.aborted &&
    deliveryRequest === request &&
    session === props.session &&
    run === props.run &&
    client === props.client
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    const value = await client.json<Record<string, unknown>>(
      `/chat/runs/delivery?${query({ session_id: session, run_id: run })}`,
      'GET',
      undefined,
      request.signal
    )
    if (current()) delivery.value = value
  } catch (cause) {
    if (current())
      error.value = cause instanceof Error ? cause.message : '未能读取送达状态，请再次查看。'
  } finally {
    if (current()) busy.value = false
  }
}
watch(
  () => [props.client, props.session, props.run] as const,
  () => {
    delivery.value = undefined
    if (!deliveryRequest) return
    deliveryRequest.abort()
    deliveryRequest = undefined
    busy.value = false
    error.value = ''
    feedback.value = ''
  }
)
watch(
  () => props.session,
  () => {
    abort.abort()
    abort = new AbortController()
    works.value = []
    work.value = undefined
    busy.value = false
    if (props.modelValue) void read()
  }
)
watch(
  () => props.workPlans,
  (incoming) => {
    works.value = mergeWorkPlans(works.value, incoming ?? [])
    if (work.value) work.value = works.value.find((item) => item.work_id === work.value!.work_id)
  },
  { deep: true }
)
watch(
  () => props.modelValue,
  (value) => {
    if (value) void read()
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  disposed = true
  deliveryRequest?.abort()
  abort.abort()
})

const formTipId = useId()
</script>
<template>
  <ChatDrawer
    :model-value="modelValue"
    title="会话工具"
    size="min(520px, 100vw)"
    class="chat-tools-drawer"
    :before-close="beforeClose"
    :close-on-click-modal="false"
    :show-close="!busy"
    :close-on-press-escape="!busy"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <ChatMessageNotice
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    /><ChatMessageNotice
      v-if="feedback"
      :title="feedback"
      type="success"
      :closable="false"
      show-icon
    />
    <p v-if="busy" role="status">正在处理，请稍候；完成后可关闭或切换面板。</p>
    <ChatButton v-if="section !== 'run'" :loading="busy" :disabled="busy" @click="read"
      >刷新</ChatButton
    >
    <ChatTabs v-model="section" :before-leave="beforeTabLeave" @tab-change="choose">
      <ChatTabPane lazy name="goal" label="目标"
        ><p v-if="!goal?.goal && !busy && !error" class="muted">暂无持续目标，可在下方创建。</p>
        <article v-if="goal?.goal" class="panel-card">
          <h3>{{ goal.goal.objective }}</h3>
          <p>
            {{ goalStateLabels[goal.goal.state] || '状态待核实' }} ·
            {{ goal.goal.rounds_started }} / {{ goal.goal.max_rounds }} 轮
          </p>
          <p v-if="goalReason">{{ goalReason }}</p>
          <ChatButton
            v-if="goal.goal.state === 'ACTIVE'"
            native-type="button"
            :disabled="busy"
            @click="goalCommand('pause')"
            >暂停目标</ChatButton
          ><ChatButton
            v-if="goal.goal.state === 'PAUSED' || goal.goal.state === 'BLOCKED'"
            native-type="button"
            :disabled="busy"
            @click="goalCommand('resume')"
            >继续目标</ChatButton
          >
        </article>
        <details class="new-goal" :open="!goal?.goal">
          <summary>创建持续目标</summary>
          <ChatForm
            ref="goalRef"
            :model="goalModel"
            :disabled="busy"
            label-position="top"
            @submit.prevent="goalCommand('create')"
            ><ChatFormItem label="持续目标" prop="objective" :rules="[required]"
              ><ChatInput
                v-described-by="'goal-objective-help'"
                type="textarea"
                v-model="objective"
                aria-describedby="goal-objective-help"
                required
                maxlength="8000"
                :rows="4"
              /><small id="goal-objective-help"
                >描述希望持续推进的结果，最多 8000 字。Enter 换行，点击创建目标提交。</small
              ></ChatFormItem
            ><ChatFormItem
              label="最大轮数"
              prop="rounds"
              :rules="[
                {
                  type: 'number',
                  required: true,
                  min: 1,
                  max: GOAL_MAX_ROUNDS,
                  message: `轮数必须为 1 至 ${GOAL_MAX_ROUNDS}`,
                  trigger: 'change'
                }
              ]"
              ><ChatInputNumber
                v-described-by="'goal-rounds-help'"
                v-model.number="rounds"
                required
                :min="1"
                :max="GOAL_MAX_ROUNDS"
                aria-describedby="goal-rounds-help"
              /><small id="goal-rounds-help"
                >允许 1 至 {{ GOAL_MAX_ROUNDS }} 轮；达到轮数上限后不再自动继续。</small
              ></ChatFormItem
            ><ChatButton native-type="submit" :disabled="busy" type="primary"
              >创建目标</ChatButton
            ></ChatForm
          >
        </details></ChatTabPane
      >
      <ChatTabPane lazy name="run" label="运行"
        ><RunBudgetPanel
          :client="client"
          :session="session"
          :run="run"
          :active="modelValue && section === 'run'"
        />
        <template v-if="run"
          ><ChatButton native-type="button" :disabled="busy" @click="readDelivery"
            >查看送达状态</ChatButton
          ><ChatDescriptions v-if="delivery" :column="1" border title="送达状态"
            ><ChatDescriptionsItem label="运行状态">{{
              deliveryState('runtime_status')
            }}</ChatDescriptionsItem
            ><ChatDescriptionsItem label="事件回放">{{ deliveryState('sse') }}</ChatDescriptionsItem
            ><ChatDescriptionsItem label="Webhook">{{
              deliveryState('webhook')
            }}</ChatDescriptionsItem></ChatDescriptions
          >
          <p v-if="delivery" class="muted">送达状态不表示用户已读。</p></template
        ></ChatTabPane
      >
      <ChatTabPane lazy name="work" label="任务计划"
        ><ChatForm :disabled="busy" label-position="top"
          ><ChatFormItem label="历史任务计划"
            ><ChatSelect
              v-described-by="formTipId + '-field-2'"
              :aria-describedby="formTipId + '-field-2'"
              :model-value="work?.work_id ?? ''"
              :options="workOptions"
              @change="selectWork"
            />
            <FieldTip :id="formTipId + '-field-2'"
              >选择要查看或补充的协作记录，目标任务随记录变化。</FieldTip
            >
          </ChatFormItem></ChatForm
        ><TaskPlanCard
          v-if="work"
          :key="work.work_id"
          :work="work"
          :client="client"
          :session="session"
          @busy="busy = $event"
          @dirty="workText = $event ? 'unsaved' : ''"
          @open="emit('task', $event)"
        />
        <p v-else-if="!busy && !error">
          当前会话尚未生成任务计划，生成后会显示在这里。
        </p></ChatTabPane
      > </ChatTabs
    ><template #footer
      ><ChatButton :disabled="busy" @click="requestClose">关闭</ChatButton></template
    >
  </ChatDrawer>
</template>

<style scoped>
.new-goal summary {
  cursor: pointer;
  padding: 10px 0;
}
.new-goal form {
  margin-top: 12px;
}
</style>
