<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { query, type Client } from '../client'
import type { Work, WorkTask, Process, WorkPending } from '../protocol'
import { mergeProcesses } from '../projection'
import { mergeWorkPlans, taskIsTerminal, taskStatus, workIsTerminal, workPhase } from '../workPlans'
import { ChatButton, ChatInput, ChatMessageNotice } from './controls'
import Markdown from './Markdown.vue'
import TaskDependencyGraph from './TaskDependencyGraph.vue'
import { mayDiscard } from './panel'
const props = withDefaults(
  defineProps<{
    work: Work
    client: Client
    session: string
    locked?: boolean
    mode?: 'compact' | 'detail'
    view?: 'overview' | 'steps' | 'dependencies'
  }>(),
  { mode: 'compact', view: 'overview' }
)
const emit = defineEmits<{
  busy: [value: boolean]
  dirty: [value: boolean]
  open: [workId: string]
  updated: [work: Work]
}>()
const current = ref(props.work)
const cardElement = ref<HTMLElement>()
let returnTaskId: string | undefined
let listScroll: { element: HTMLElement; top: number }[] = []
watch(
  () => props.work,
  (value) => {
    current.value = mergeWorkPlans([current.value], [value])[0]!
  },
  { deep: true }
)
const editable = computed(() => !workIsTerminal(current.value))
const approvalPending = computed(
  () =>
    editable.value &&
    current.value.approval_required &&
    (current.value.approved_revision ?? 0) < current.value.plan_revision
)
const attention = computed(() =>
  current.value.tasks.filter((task) =>
    ['failed', 'outcome_unknown', 'blocked'].includes(task.status)
  )
)
const planOpen = ref(false)
const dependencyPosition = ref({ left: 0, top: 0 })
const selected = ref<WorkTask>(),
  detail = ref<{
    processes: Process[]
    results: Work['results']
    pending?: WorkPending
    has_more?: boolean
    next_cursor?: string
  }>()
const failedRead = ref<{ path: string; previous: typeof detail.value }>()
const text = ref(''),
  error = ref(''),
  busy = ref(false)
const taskInputAllowed = computed(
  () => editable.value && !!selected.value?.run_id && !taskIsTerminal(selected.value)
)
const taskCancelAllowed = computed(
  () => editable.value && !!selected.value && !taskIsTerminal(selected.value)
)
watch(busy, (value) => emit('busy', value))
watch(text, (value) => emit('dirty', !!value.trim()))
let abort = new AbortController(),
  epoch = 0
const requests = new Map<string, { input_id: string; [key: string]: unknown }>()
watch(
  () => props.session,
  () => {
    abort.abort()
    abort = new AbortController()
    epoch++
    selected.value = undefined
    planOpen.value = false
    detail.value = undefined
    error.value = ''
    failedRead.value = undefined
    text.value = ''
    busy.value = false
  }
)
onBeforeUnmount(() => {
  epoch++
  abort.abort()
})
watch(
  () => current.value.tasks,
  (tasks) => {
    if (!selected.value) return
    const next = tasks.find((task) => task.task_id === selected.value!.task_id)
    if (next) {
      selected.value = next
      if (detail.value) detail.value.pending = next.pending
    }
  },
  { deep: true }
)
async function load(task: WorkTask, more = false) {
  if (busy.value) return
  if (!more && text.value.trim() && !(await mayDiscard(false, true))) return
  if (!more) {
    returnTaskId = task.task_id
    listScroll = []
    let element = cardElement.value?.parentElement
    while (element) {
      if (element.scrollTop || element.scrollHeight > element.clientHeight)
        listScroll.push({ element, top: element.scrollTop })
      element = element.parentElement
    }
    text.value = ''
  }
  selected.value = task
  error.value = ''
  failedRead.value = undefined
  const previous = more ? detail.value : undefined
  if (!more) detail.value = undefined
  if (task.details_access !== 'available') {
    return
  }
  await readDetail({
    path: `/chat/work/tasks/detail?${query({ session_id: props.session, work_id: current.value.work_id, task_id: task.task_id, task_kind: task.task_kind, limit: 50, cursor: previous?.next_cursor })}`,
    previous
  })
}
async function readDetail(request: { path: string; previous: typeof detail.value }) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  failedRead.value = undefined
  const token = ++epoch,
    previous = request.previous
  try {
    const value = await props.client.json<NonNullable<typeof detail.value>>(
      request.path,
      'GET',
      undefined,
      abort.signal
    )
    if (token === epoch)
      detail.value = previous
        ? { ...value, processes: mergeProcesses(previous.processes, value.processes) }
        : value
  } catch (cause) {
    if (token === epoch && !abort.signal.aborted) {
      failedRead.value = request
      error.value = cause instanceof Error ? cause.message : '未能读取步骤详情，请重新读取。'
    }
  } finally {
    if (token === epoch) busy.value = false
  }
}
async function control(operation: string, task?: WorkTask) {
  if (busy.value || props.locked || !editable.value) return
  failedRead.value = undefined
  if (task) {
    // 控件发出旧事件时仍以当前步骤事实判定；终态和未开始步骤不能接收执行输入。
    const requestedTask = task
    task = current.value.tasks.find(
      (item) => item.task_id === requestedTask.task_id && item.task_kind === requestedTask.task_kind
    )
    if (!task || taskIsTerminal(task) || (operation !== 'cancel' && !task.run_id)) return
  }
  if (
    ['supplement', 'reply_input', 'workflow_response'].includes(operation) &&
    !text.value.trim()
  ) {
    error.value = '请填写补充要求或回答'
    return
  }
  const token = epoch,
    submitted = text.value,
    pending = detail.value?.pending ?? task?.pending ?? current.value.pending
  const pendingIdentity = [
    'approve_operation',
    'reject_operation',
    'reply_input',
    'workflow_response'
  ].includes(operation)
    ? `${pending?.pending_id ?? ''}:${pending?.parameters_version ?? ''}`
    : ''
  const key = `${current.value.work_id}:${task?.task_id ?? ''}:${operation}:${current.value.plan_revision}:${pendingIdentity}:${submitted}`
  const body = requests.get(key) ?? {
    input_id: crypto.randomUUID(),
    session_id: props.session,
    work_id: current.value.work_id,
    task_id: task?.task_id,
    operation,
    content: submitted,
    ...(operation === 'approve_plan' ? { plan_revision: current.value.plan_revision } : {}),
    ...(['approve_operation', 'reject_operation', 'reply_input', 'workflow_response'].includes(
      operation
    ) && pending
      ? { pending_id: pending.pending_id, parameters_version: pending.parameters_version }
      : {})
  }
  requests.set(key, body)
  busy.value = true
  error.value = ''
  try {
    const value = await props.client.json<Work>('/chat/work/controls', 'POST', body, abort.signal)
    if (token !== epoch) return
    current.value = mergeWorkPlans([current.value], [value])[0]!
    emit('updated', current.value)
    const receipt = value.control_inputs?.find((input) => input.input_id === body.input_id)
    // HTTP 成功只确认取得回执；拒绝保留草稿，处理中继续复用原请求身份。
    if (receipt?.state === 'REJECTED') {
      error.value = receipt.detail?.trim() || '操作未被受理，草稿已保留。请核对当前任务状态后调整。'
      return
    }
    if (!receipt || receipt.state === 'APPLIED') requests.delete(key)
    if (text.value === submitted) text.value = ''
  } catch (cause) {
    if (token === epoch && !abort.signal.aborted)
      error.value =
        cause instanceof Error
          ? cause.message
          : '操作结果尚未确认，请先查看最新任务状态；仍需重试时，保留原内容并再次执行同一操作。'
  } finally {
    if (token === epoch) busy.value = false
  }
}
/** 返回同层任务视图或关闭所属面板前，保护在途请求和未提交草稿。 */
async function closeDetail() {
  if (!(await mayDiscard(busy.value, !!text.value.trim()))) return false
  failedRead.value = undefined
  error.value = ''
  selected.value = undefined
  planOpen.value = false
  detail.value = undefined
  text.value = ''
  return true
}
/** 只有显式返回步骤列表时恢复原位置和触发按钮；外层关闭或切换不移动焦点。 */
async function returnToTasks() {
  if (!(await closeDetail())) return
  await nextTick()
  const button = [
    ...(cardElement.value?.querySelectorAll<HTMLElement>('[data-task-id]') ?? [])
  ].find((element) => element.dataset.taskId === returnTaskId)
  button?.focus({ preventScroll: true })
  for (const { element, top } of listScroll) element.scrollTop = top
}
defineExpose({ requestClose: closeDetail })
</script>
<template>
  <section ref="cardElement" class="task-plan-card" :data-work-id="current.work_id">
    <div class="plan-heading">
      <strong>任务计划 · {{ current.title || '当前任务' }}</strong>
      <small
        >{{ workPhase(current) }} · 已完成
        {{ current.tasks.filter((item) => item.status === 'completed').length }} /
        {{ current.tasks.length }} 项</small
      >
    </div>
    <p v-if="approvalPending" class="plan-approval" role="status">
      <strong>待批准第 {{ current.plan_revision }} 版计划</strong>
    </p>
    <ChatButton v-if="mode === 'compact'" @click="emit('open', current.work_id)"
      >查看任务</ChatButton
    >
    <p v-if="current.notice" role="status">{{ current.notice }}</p>
    <p v-for="task in attention" :key="task.task_id" role="status">
      {{ task.title }}：{{ taskStatus(task.status)
      }}{{ task.wait_reason ? ' · ' + task.wait_reason : '' }}
    </p>
    <template v-if="mode === 'detail' && !selected && !planOpen">
      <template v-if="view === 'steps'">
        <ol>
          <li v-for="task in current.tasks" :key="task.task_id">
            <ChatButton text :data-task-id="task.task_id" :disabled="busy" @click="load(task)">{{
              task.title
            }}</ChatButton>
            <span
              >{{ task.executor_type === 'PRIMARY' ? '主 Agent' : task.agent_name }} ·
              {{ taskStatus(task.status) }}</span
            >
          </li>
        </ol>
      </template>
      <template v-else-if="view === 'dependencies'">
        <TaskDependencyGraph
          v-if="current.tasks.length"
          :tasks="current.tasks"
          :disabled="busy"
          :position="dependencyPosition"
          @viewport="dependencyPosition = $event"
          @select="load"
        />
        <p v-if="!current.tasks.some((task) => task.depends_on?.length)">暂无步骤依赖</p>
        <ul aria-label="步骤依赖">
          <li
            v-for="task in current.tasks.filter((item) => item.depends_on?.length)"
            :key="task.task_id"
          >
            <ChatButton text :data-task-id="task.task_id" :disabled="busy" @click="load(task)">{{
              task.title
            }}</ChatButton>
            <span>依赖：</span>
            <template v-for="id in task.depends_on" :key="id">
              <ChatButton
                v-if="current.tasks.find((item) => item.task_id === id)"
                text
                :disabled="busy"
                @click="load(current.tasks.find((item) => item.task_id === id)!)"
                >{{ current.tasks.find((item) => item.task_id === id)?.title }}</ChatButton
              >
              <span v-else>{{ id }}</span>
            </template>
          </li>
        </ul>
      </template>
      <template v-else>
        <p>当前计划版本：第 {{ current.plan_revision }} 版</p>
        <p v-if="current.change_reason?.trim()" class="plan-change-reason">
          本版调整说明：{{ current.change_reason }}
        </p>
        <ChatButton v-if="editable" :disabled="busy || locked" @click="planOpen = true"
          >补充计划要求</ChatButton
        >
        <ChatButton
          v-if="approvalPending"
          :disabled="busy || locked"
          @click="control('approve_plan')"
          >批准第 {{ current.plan_revision }} 版计划</ChatButton
        >
        <ChatButton v-if="editable" :disabled="busy || locked" @click="control('cancel')"
          >取消计划</ChatButton
        >
      </template>
    </template>
    <ChatMessageNotice v-if="error" :title="error" type="error">
      <ChatButton v-if="failedRead" text :disabled="busy" @click="readDetail(failedRead)"
        >重新读取</ChatButton
      >
    </ChatMessageNotice>
    <div v-if="mode === 'detail' && (selected || planOpen)" class="task-detail-screen">
      <ChatButton :disabled="busy" @click="returnToTasks">返回任务</ChatButton>
      <h3>{{ selected?.title || '补充计划要求' }}</h3>
      <template v-if="selected">
        <p>
          {{ selected.executor_type === 'PRIMARY' ? '主 Agent' : selected.agent_name }} ·
          {{ taskStatus(selected.status) }}
        </p>
        <Markdown :text="selected.goal" />
        <p v-if="selected.expected_output">预期输出：{{ selected.expected_output }}</p>
        <p v-if="selected.depends_on?.length">
          依赖：{{
            selected.depends_on
              .map((id) => current.tasks.find((item) => item.task_id === id)?.title || id)
              .join('、')
          }}
        </p>
        <p v-for="constraint in selected.constraints" :key="constraint">{{ constraint }}</p>
        <p v-if="selected.wait_reason">{{ selected.wait_reason }}</p>
        <details v-if="detail?.processes.length" class="task-process-details">
          <summary>公开过程（{{ detail.processes.length }}）</summary>
          <article v-for="process in detail?.processes" :key="process.process_id">
            <strong>{{ process.presentation.title }}</strong
            ><Markdown :text="process.presentation.summary" />
          </article>
        </details>
        <p v-else-if="detail?.has_more" class="muted">当前页没有此步骤的公开过程，可继续查看。</p>
        <ChatButton v-if="detail?.has_more" :disabled="busy" @click="load(selected, true)"
          >加载更多过程</ChatButton
        >
        <article v-for="result in detail?.results" :key="result.result_id">
          <strong>{{ result.title }}</strong
          ><Markdown :text="result.summary" />
          <p v-for="limitation in result.limitations" :key="limitation">{{ limitation }}</p>
        </article>
        <template v-if="taskInputAllowed">
          <p v-if="detail?.pending || selected.pending">
            {{ (detail?.pending || selected.pending)?.prompt }}
            <small>参数版本：{{ (detail?.pending || selected.pending)?.parameters_version }}</small>
          </p>
          <label :for="'plan-input-' + current.work_id">补充要求或回答</label>
          <ChatInput
            :id="'plan-input-' + current.work_id"
            v-model="text"
            type="textarea"
            :rows="4"
            :disabled="busy || locked"
          />
          <p class="muted">关闭详情前请提交或清空输入。</p>
          <ChatButton
            :disabled="busy || locked || !text.trim()"
            @click="control('supplement', selected)"
            >提交补充</ChatButton
          >
          <ChatButton
            v-if="(detail?.pending || selected.pending)?.kind === 'operation'"
            :disabled="busy || locked"
            @click="control('approve_operation', selected)"
            >批准操作</ChatButton
          >
          <ChatButton
            v-if="(detail?.pending || selected.pending)?.kind === 'operation'"
            :disabled="busy || locked"
            @click="control('reject_operation', selected)"
            >拒绝操作</ChatButton
          >
          <ChatButton
            v-else-if="detail?.pending || selected.pending"
            :disabled="busy || locked"
            @click="
              control(
                (detail?.pending || selected.pending)?.kind === 'workflow'
                  ? 'workflow_response'
                  : 'reply_input',
                selected
              )
            "
            >回答问题</ChatButton
          >
        </template>
        <ChatButton
          v-if="taskCancelAllowed"
          :disabled="busy || locked"
          @click="control('cancel', selected)"
          >取消步骤</ChatButton
        >
      </template>
      <template v-else-if="planOpen && editable">
        <p>{{ current.title || '当前任务计划' }}</p>
        <label :for="'plan-work-input-' + current.work_id">补充计划要求</label>
        <ChatInput
          :id="'plan-work-input-' + current.work_id"
          v-model="text"
          type="textarea"
          :rows="4"
          :disabled="busy || locked"
        />
        <ChatButton :disabled="busy || locked || !text.trim()" @click="control('supplement')"
          >提交补充</ChatButton
        >
      </template>
    </div>
  </section>
</template>
<style scoped>
.task-plan-card {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 12px;
  margin: 12px 0;
  background: var(--soft);
}
.plan-approval {
  padding: 8px 10px;
  border-left: 3px solid var(--accent);
  background: var(--soft);
}
.plan-heading {
  display: flex;
  flex-wrap: wrap;
  text-align: left;
  gap: 8px;
  width: 100%;
}
small,
li > span {
  color: var(--muted);
  font-size: 12px;
}
li {
  padding: 4px 0;
}
li > small {
  display: block;
}
</style>
