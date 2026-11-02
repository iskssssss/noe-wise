<script setup lang="ts">
import {
  PanelLeftClose as IconPanelLeftClose,
  Plus as IconPlus,
  Search as IconSearch,
  Monitor as IconMonitor,
  Users as IconUsers,
  Moon as IconMoon,
  PanelLeft as IconPanelLeft,
  SquarePen as IconSquarePen,
  ChevronDown as IconChevronDown,
  LayoutGrid as IconLayoutGrid,
  Ellipsis as IconEllipsis,
  Square as IconSquare,
  ArrowUp as IconArrowUp
} from 'lucide-vue-next'
import { runIsTerminal } from './workPlans'
import { vDescribedBy } from './ui/describedBy'

import FieldTip from './ui/FieldTip.vue'

import {
  ChatAlert,
  ChatMessageNotice,
  ChatButton,
  ChatDrawer,
  ChatForm,
  ChatFormItem,
  ChatInput,
  ChatOption,
  ChatOptionGroup,
  ChatPopover,
  ChatSelect,
  ChatUpload,
  type FormInstance,
  type FormRules
} from './ui/controls'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, useId } from 'vue'
import { useConversation } from './session'
import { command } from './commands'
import { query } from './client'
import type { Collaboration, GoalStatus, Pending, Team } from './protocol'
import ConversationTimeline from './ui/ConversationTimeline.vue'
import ComposerAttachments from './ui/ComposerAttachments.vue'
import ArtifactPanel from './ui/ArtifactPanel.vue'
import BrowserDevices from './ui/BrowserDevices.vue'
import BrowserConnectionCard from './ui/BrowserConnectionCard.vue'
import WorkspaceStatus from './ui/WorkspaceStatus.vue'
import PendingForm from './ui/PendingForm.vue'
import ConversationTools from './ui/ConversationTools.vue'
import TaskDetailsPanel from './ui/TaskDetailsPanel.vue'
import TeamManager from './ui/TeamManager.vue'
import { mayDiscard, validateForm, type ClosablePanel } from './ui/panel'
import { agentChoiceName } from './ui/agentChoice'
const c = reactive(useConversation())
/** 只消除同一执行已展示的提问，其他待办或用户文本不会吞掉当前问题。 */
function pendingQuestionDisplayed(pending: Pending) {
  const question = (pending.card?.title || pending.question || '').trim()
  return Boolean(
    question &&
    c.history.messages.some(
      (message) =>
        message.role === 'assistant' &&
        message.run_id === pending.execution_id &&
        message.content.trim() === question
    )
  )
}
const settings = reactive({
  address: location.origin,
  mode: 'token',
  token: '',
  apiKey: '',
  secret: '',
  name: ''
})
const activePanel = ref<'' | 'tools' | 'teams' | 'artifacts' | 'tasks'>(''),
  toolsRef = ref<ClosablePanel>(),
  teamRef = ref<ClosablePanel>(),
  artifactRef = ref<ClosablePanel>(),
  taskRef = ref<ClosablePanel>(),
  selectedWork = ref('')
const historyOpen = ref(false),
  devicesOpen = ref(false),
  selectedArtifact = ref(''),
  historyQuery = ref(''),
  headerMenuOpen = ref(false)
watch(
  () => [c.client, c.userId, c.target],
  () => {
    historyQuery.value = ''
  }
)
function openDevices() {
  devicesOpen.value = true
  historyOpen.value = false
}
function menuAction(action: () => unknown) {
  headerMenuOpen.value = false
  void action()
}
const timelineRef = ref<HTMLElement>(),
  timelineContentRef = ref<HTMLElement>(),
  followingLatest = ref(true),
  composerDockRef = ref<HTMLElement>(),
  composerClearance = ref(116)
let followFrame: number | undefined
let followTime: number | undefined
let lastFollowTop: number | undefined
let touchStartY: number | undefined
let readingEarlier = false
let timelineDisposed = false
const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)')
const filteredSessions = computed(() => {
  const search = historyQuery.value.trim().toLocaleLowerCase()
  return c.sessions
    .map((session) => ({ ...session, displayTitle: session.title || '无文字或空会话' }))
    .filter((session) => !search || session.displayTitle.toLocaleLowerCase().includes(search))
})
const composerExpanded = computed(() =>
  Boolean(
    c.active ||
    c.inputMode === 'supplement' ||
    c.attachments.length ||
    c.pastedTexts.length ||
    c.images.length ||
    c.references.length ||
    c.composerDraft.includes('\n') ||
    c.composerDraft.length > 55 ||
    c.uploading ||
    c.history.collaboration.selected_active
  )
)
const composerHelp = computed(() =>
  c.uploading
    ? '正在上传，请稍候。'
    : c.sending || commandBusy.value
      ? '正在提交，请稍候。'
      : c.cancelling
        ? '正在请求取消，请等待结果。'
        : c.pendingInput
          ? '请先恢复观察或重试原请求。'
          : c.loading
            ? '正在读取会话，请稍候。'
            : c.history.pending_interactions.length
              ? '上方任务正在等待回复，也可以发送消息启动另一项任务。'
              : c.active
                ? c.inputMode === 'supplement'
                  ? '补充将在下一个可用步骤纳入；附件与引用保存在下一条草稿。'
                  : '草稿将保留，当前运行结束后可发送。'
                : !c.target
                  ? '请先选择对话对象。'
                  : 'Enter 发送 · Shift + Enter 换行'
)
let composerObserver: ResizeObserver | undefined
watch(
  composerDockRef,
  (element) => {
    composerObserver?.disconnect()
    if (!element) return
    const updateClearance = () => {
      composerClearance.value = Math.ceil(element.getBoundingClientRect().height)
      if (followingLatest.value) void followLatestAfterRender()
    }
    updateClearance()
    composerObserver = new ResizeObserver(updateClearance)
    composerObserver.observe(element)
  },
  { flush: 'post' }
)
onBeforeUnmount(() => composerObserver?.disconnect())
let timelineObserver: ResizeObserver | undefined
watch(
  timelineContentRef,
  (element) => {
    timelineObserver?.disconnect()
    if (!element) return
    // 图表、图片和折叠轨迹可在正文快照之后改变高度，只在用户仍跟随底部时移动。
    timelineObserver = new ResizeObserver(() => void followLatestAfterRender())
    timelineObserver.observe(element)
  },
  { flush: 'post' }
)
onBeforeUnmount(() => timelineObserver?.disconnect())
const pendingRef = ref<{ dirty: boolean }[]>([])
const draftDirty = computed(() =>
  Boolean(
    c.draft.trim() ||
    c.supplementDraft.trim() ||
    c.attachments.length ||
    c.pastedTexts.length ||
    c.images.length ||
    c.references.length ||
    pendingRef.value.some((form) => form.dirty)
  )
)
/** 浏览器刷新或关闭同样保护内存草稿，不将正文和凭据写入持久存储。 */
function protectDraft(event: BeforeUnloadEvent) {
  if (!draftDirty.value) return
  event.preventDefault()
  event.returnValue = ''
}
watch(
  draftDirty,
  (dirty) => {
    if (dirty) window.addEventListener('beforeunload', protectDraft)
    else window.removeEventListener('beforeunload', protectDraft)
  },
  { immediate: true }
)
onBeforeUnmount(() => window.removeEventListener('beforeunload', protectDraft))
const targetDisabled = computed(() =>
  c.teams.some((team) => c.target === `team:${team.id}` && team.status === 'disabled')
)
const commandBusy = ref(false),
  commands = ref<{ command: string; title: string; description: string }[]>([])
const commandKeys = new Map<string, string>()
const controlBodies = new Map<string, { path: string; body: unknown }>()
const commandIndex = ref(0),
  commandDismissed = ref(false)
const commandMatches = computed(() =>
  c.inputMode === 'message' && c.draft.startsWith('/') && c.draft === c.draft.trimEnd()
    ? commands.value.filter((item) => item.command.startsWith(c.draft.trim())).slice(0, 8)
    : []
)
const statusName: Record<string, string> = {
  running: '运行中',
  waiting: '等待中',
  waiting_hitl: '等待你的输入',
  suspended: '已挂起',
  completed: '已完成',
  succeeded: '已完成',
  failed: '失败',
  cancelled: '已取消',
  terminated: '已终止',
  cancelling: '正在取消',
  outcome_unknown: '结果待核实',
  queued: '排队中'
}
// 仅在运行收口或成果发布后刷新工作区摘要，避免过程流逐事件请求。
const workspaceRefreshKey = computed(() =>
  JSON.stringify([
    Object.entries(c.runStates).filter(
      ([, status]) => runIsTerminal(status) || status === 'succeeded'
    ),
    c.artifactsRevision
  ])
)
watch(
  () => [c.client, c.target, c.userId],
  async () => {
    commands.value = []
    const api = c.client,
      target = c.target
    if (!api || !target) return
    try {
      const data = await api.json<{ items: typeof commands.value }>(
        `/chat/commands?${query({ ...c.selectedTarget, end_user_id: c.userId })}`
      )
      if (c.client === api && c.target === target) commands.value = data.items
    } catch {
      /* 命令目录不可用不影响手动输入和会话恢复。 */
    }
  }
)
watch(
  () => c.sessionId,
  () => {
    activePanel.value = ''
    selectedWork.value = ''
    stopFollowingAnimation()
    lastFollowTop = undefined
    readingEarlier = false
    followingLatest.value = true
  }
)
watch(
  () => [
    c.history.messages
      .map((item) => `${item.message_id}:${item.message_version}:${item.content.length}`)
      .join('|'),
    c.history.processes.map((item) => `${item.process_id}:${item.updated_sequence}`).join('|'),
    c.artifactsRevision
  ],
  () => void followLatestAfterRender()
)
/** 合并正文与异步布局更新，逐帧追随最新高度，不重启浏览器的平滑滚动。 */
async function followLatestAfterRender() {
  if (!followingLatest.value || timelineDisposed) return
  const session = c.sessionId
  await nextTick()
  if (
    !followingLatest.value ||
    timelineDisposed ||
    c.sessionId !== session ||
    followFrame !== undefined
  )
    return
  const step = (time: number) => {
    followFrame = undefined
    const element = timelineRef.value
    if (!element || !followingLatest.value || timelineDisposed || c.sessionId !== session) return
    const target = Math.max(0, element.scrollHeight - element.clientHeight)
    const previousTop = element.scrollTop
    const distance = target - previousTop
    const elapsed = followTime === undefined ? 16 : Math.min(32, Math.max(1, time - followTime))
    followTime = time
    // 历史加载直接定位；实时增量用约 200ms 收敛的追随，减少换行时的台阶感。
    const immediate = reducedMotion?.matches || Math.abs(distance) > element.clientHeight
    const nextTop =
      immediate || Math.abs(distance) < 0.75
        ? target
        : previousTop + distance * (1 - Math.exp(-elapsed / 40))
    element.scrollTop = Math.abs(target - nextTop) < 0.75 ? target : nextTop
    lastFollowTop = element.scrollTop
    // 缩放后的实际滚动上限可能有小数；到达物理底部后不空转动画帧。
    if (
      Math.abs(target - element.scrollTop) >= 0.75 &&
      Math.abs(element.scrollTop - previousTop) > 0.01
    ) {
      followFrame = requestAnimationFrame(step)
    } else {
      followTime = undefined
    }
  }
  followFrame = requestAnimationFrame(step)
}
/** 用户滚动、切换会话或销毁组件时撤销尚未执行的跟随。 */
function stopFollowingAnimation() {
  if (followFrame !== undefined) cancelAnimationFrame(followFrame)
  followFrame = undefined
  followTime = undefined
}
/** 上滚意图先于浏览器 scroll 事件处理，动画不能覆盖用户的阅读动作。 */
function readEarlier(event: WheelEvent | KeyboardEvent) {
  const upward =
    event instanceof WheelEvent
      ? event.deltaY < 0
      : ['ArrowUp', 'PageUp', 'Home'].includes(event.key)
  if (!upward) return
  readingEarlier = true
  followingLatest.value = false
  stopFollowingAnimation()
  lastFollowTop = undefined
}
function beginTimelineTouch(event: TouchEvent) {
  touchStartY = event.touches[0]?.clientY
}
/** 向下拖动内容表示回看历史，移动端同样立即停止自动跟随。 */
function moveTimelineTouch(event: TouchEvent) {
  const y = event.touches[0]?.clientY
  if (y !== undefined && touchStartY !== undefined && y > touchStartY) {
    readingEarlier = true
    followingLatest.value = false
    stopFollowingAnimation()
    lastFollowTop = undefined
  }
  touchStartY = y
}
/** 用户主动回到底部后恢复流式跟随；自动更新不得调用此入口重置阅读偏好。 */
function scrollLatest() {
  stopFollowingAnimation()
  readingEarlier = false
  const element = timelineRef.value
  if (element) {
    element.scrollTop = element.scrollHeight
    lastFollowTop = element.scrollTop
  }
  followingLatest.value = true
}
/** 底部 100px 范围内视为继续阅读最新内容，其余位置保持不动。 */
function trackScroll() {
  const element = timelineRef.value
  if (!element) return
  if (lastFollowTop !== undefined && Math.abs(element.scrollTop - lastFollowTop) < 1) return
  const distance = element.scrollHeight - element.scrollTop - element.clientHeight
  if (distance < 2) readingEarlier = false
  followingLatest.value = !readingEarlier && distance < 100
  if (!followingLatest.value) stopFollowingAnimation()
  lastFollowTop = undefined
}
async function loadEarlier() {
  stopFollowingAnimation()
  const element = timelineRef.value
  const session = c.sessionId
  const height = element?.scrollHeight ?? 0
  followingLatest.value = false
  await c.loadEarlier()
  await nextTick()
  if (element && !timelineDisposed && c.sessionId === session && !followingLatest.value)
    element.scrollTop += element.scrollHeight - height
}
onBeforeUnmount(() => {
  timelineDisposed = true
  stopFollowingAnimation()
})
async function closePanel() {
  const panel =
    activePanel.value === 'tools'
      ? toolsRef.value
      : activePanel.value === 'teams'
        ? teamRef.value
        : activePanel.value === 'artifacts'
          ? artifactRef.value
          : activePanel.value === 'tasks'
            ? taskRef.value
            : undefined
  if (panel && !(await panel.requestClose())) return false
  activePanel.value = ''
  return true
}
async function openPanel(value: 'tools' | 'teams' | 'artifacts' | 'tasks') {
  const previous = activePanel.value
  if (await closePanel()) {
    historyOpen.value = false
    activePanel.value = previous === value ? '' : value
  }
}
function panelChanged(name: 'tools' | 'teams' | 'artifacts' | 'tasks', visible: boolean) {
  if (!visible && activePanel.value === name) activePanel.value = ''
}
// 历史读取可取消；导航只阻止尚在改变业务状态或持有未知输入的操作。
const historyNavigationLocked = computed(
  () => c.connecting || c.sending || c.uploading || c.cancelling || Boolean(c.pendingInput)
)
async function navigate(action: () => unknown, locked = c.locked) {
  if (!(await mayDiscard(locked || commandBusy.value, draftDirty.value))) return
  if (await closePanel()) {
    await action()
    historyOpen.value = false
  }
}
async function openArtifact(id: string) {
  if (activePanel.value !== 'artifacts') {
    if (!(await closePanel())) return
    historyOpen.value = false
    activePanel.value = 'artifacts'
  }
  selectedArtifact.value = id
}
async function openTask(id = '') {
  if (activePanel.value === 'tasks' && selectedWork.value === id) return
  if (!(await closePanel())) return
  if (id) selectedWork.value = id
  historyOpen.value = false
  activePanel.value = 'tasks'
}
const connectionRef = ref<FormInstance>(),
  composerRef = ref<FormInstance>()
const messageInputRef = ref<{ input?: HTMLTextAreaElement; focus: () => void }>()
const messageHasResources = computed(() =>
  Boolean(c.images.length || c.attachments.length || c.pastedTexts.length)
)
watch(
  () => c.composerDraft,
  () => composerRef.value?.clearValidate('composerDraft')
)
const connectionRules: FormRules = {
  address: [
    {
      validator: (_rule, value, callback) => {
        try {
          const url = new URL(String(value))
          if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password)
            throw new Error()
          callback()
        } catch {
          callback(new Error('请输入有效的 HTTP(S) 服务地址'))
        }
      },
      trigger: 'blur'
    }
  ],
  token: [{ required: true, whitespace: true, message: '请填写渠道令牌', trigger: 'blur' }],
  apiKey: [{ required: true, whitespace: true, message: '请填写 API Key', trigger: 'blur' }],
  secret: [{ required: true, whitespace: true, message: '请填写 API Secret', trigger: 'blur' }]
}
const composerRules: FormRules = {
  composerDraft: [
    {
      validator: (_rule, value, callback) =>
        callback(
          String(value ?? '').trim() || (c.inputMode === 'message' && messageHasResources.value)
            ? undefined
            : new Error('请输入内容')
        ),
      trigger: 'blur'
    }
  ]
}
watch(
  () => c.draft,
  () => {
    commandIndex.value = 0
    commandDismissed.value = false
  }
)
const showCommands = computed(() =>
  Boolean(commandMatches.value.length && !commandDismissed.value && !c.locked && !c.active)
)
function pickCommand(index: number) {
  const item = commandMatches.value[index]
  if (item) c.draft = item.command + ' '
  commandDismissed.value = true
}
async function insertCommand() {
  if (c.draft.trim() || c.active || c.locked) return
  c.draft = '/'
  commandDismissed.value = false
  await nextTick()
  document.getElementById('message-input')?.focus()
}
function commandKey(event: Event | KeyboardEvent) {
  if (!(event instanceof KeyboardEvent)) return
  if (event.isComposing || event.keyCode === 229) return
  if (
    event.key === 'Enter' &&
    !event.shiftKey &&
    (!showCommands.value || event.ctrlKey || event.metaKey)
  ) {
    event.preventDefault()
    void send()
    return
  }
  if (!showCommands.value || event.shiftKey) return
  if (event.key === 'Escape') {
    event.preventDefault()
    commandDismissed.value = true
    return
  }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    commandIndex.value =
      (commandIndex.value + (event.key === 'ArrowDown' ? 1 : -1) + commandMatches.value.length) %
      commandMatches.value.length
    void nextTick(() => {
      document
        .getElementById(`command-option-${commandIndex.value}`)
        ?.scrollIntoView({ block: 'nearest' })
    })
  } else if (event.key === 'Enter' || event.key === 'Tab') {
    event.preventDefault()
    pickCommand(commandIndex.value)
  }
}

async function connect() {
  if (c.connecting || !(await validateForm(connectionRef.value))) return
  if (c.connecting) return
  const snapshot = { ...settings }
  await c.connect(
    snapshot.address,
    snapshot.mode === 'token'
      ? { mode: 'token', token: snapshot.token.trim() }
      : { mode: 'hmac', apiKey: snapshot.apiKey.trim(), secret: snapshot.secret },
    snapshot.name
  )
  if (c.client) {
    try {
      sessionStorage.setItem('skillforge.chat.connection', JSON.stringify(snapshot))
    } catch {}
  }
}
async function disconnect() {
  if (!(await mayDiscard(c.locked || commandBusy.value, draftDirty.value))) return
  if (!(await closePanel())) return
  try {
    sessionStorage.removeItem('skillforge.chat.connection')
    sessionStorage.removeItem('skillforge.chat.selection')
  } catch {}
  c.disconnect()
  settings.token = ''
  settings.secret = ''
}
onMounted(async () => {
  try {
    const saved = sessionStorage.getItem('skillforge.chat.connection'),
      selection = sessionStorage.getItem('skillforge.chat.selection')
    if (!saved) return
    const value = JSON.parse(saved)
    if (!value || typeof value.address !== 'string' || !['token', 'hmac'].includes(value.mode))
      return
    Object.assign(settings, value)
    await connect()
    if (!c.client || !selection) return
    const selected = JSON.parse(selection)
    if (selected.clientRef !== c.clientRef) return
    const allowed =
      c.agents.some((item) => `agent:${item.id}` === selected.target) ||
      c.teams.some((item) => `team:${item.id}` === selected.target && item.status !== 'disabled')
    if (!allowed) return
    await c.changeTarget(selected.target)
    if (c.sessions.some((item) => item.session_id === selected.session))
      await c.openSession(selected.session)
  } catch (error) {
    c.fail(error)
  }
})
watch(
  () => [c.target, c.sessionId, c.clientRef],
  () => {
    if (!c.client) return
    try {
      sessionStorage.setItem(
        'skillforge.chat.selection',
        JSON.stringify({ clientRef: c.clientRef, target: c.target, session: c.sessionId })
      )
    } catch {}
  }
)
watch(
  () => c.client,
  (value) => {
    if (!value)
      try {
        sessionStorage.removeItem('skillforge.chat.connection')
      } catch {}
  }
)
/** 一次选择保持为同一批次，统一校验后再上传。 */
async function files(batch: File[]) {
  await c.upload(batch)
}
/** 短文本保持原生粘贴；长文本转换成功才拦截，保留已有正文和选区。 */
function pasteMessage(event: ClipboardEvent) {
  if (c.inputMode !== 'message' || c.locked || commandBusy.value) return
  const clipboard = event.clipboardData
  if (!clipboard) return
  const selectedFiles = Array.from(clipboard.files)
  const text = clipboard.getData('text/plain')
  if (text) {
    const start = messageInputRef.value?.input?.selectionStart ?? c.draft.length,
      end = messageInputRef.value?.input?.selectionEnd ?? start,
      pasted = c.draft.slice(0, start) + text + c.draft.slice(end)
    if (
      !/^\/(?:goal|plan|delivery)(?:\s|$)/.test(pasted.trimStart()) &&
      c.stagePastedText(text, start, end)
    ) {
      event.preventDefault()
      composerRef.value?.clearValidate('composerDraft')
    }
  } else if (selectedFiles.length) event.preventDefault()
  if (selectedFiles.length) void files(selectedFiles)
}
/** 按粘贴时记录的选区还原原文，textarea 更新完成后恢复插入末尾的光标。 */
async function restoreText(id: string, confirmed: boolean) {
  if (c.locked || commandBusy.value || c.inputMode !== 'message') return
  const position = confirmed ? c.restoreAttachmentText(id) : c.restorePastedText(id)
  if (position === undefined) return
  // textarea 会统一 CRLF/CR 为 LF，同步草稿和光标坐标；附件预览与上传原文保持不变。
  const caret = c.composerDraft.slice(0, position).replace(/\r\n?/g, '\n').length
  c.composerDraft = c.composerDraft.replace(/\r\n?/g, '\n')
  await nextTick()
  messageInputRef.value?.focus()
  messageInputRef.value?.input?.setSelectionRange(caret, caret)
}
function saveTeam(team: Team) {
  const index = c.teams.findIndex((item) => item.id === team.id)
  if (index >= 0) c.teams[index] = team
  else c.teams.push(team)
  if (team.status === 'disabled' && c.target === `team:${team.id}`)
    c.notice = '当前团队已停用，已保留当前会话与草稿。发起新会话前，请选择其他对话对象。'
}
async function send() {
  if (c.inputMode === 'supplement') {
    if (!c.canSteer || c.steeringSending || c.pendingSteering || !c.supplementDraft.trim()) return
    await c.submitSupplement()
    return
  }
  if (
    commandBusy.value ||
    c.locked ||
    c.historyReadFailed ||
    c.active ||
    (targetDisabled.value && !c.sessionId) ||
    !(await validateForm(composerRef.value))
  )
    return
  if (commandBusy.value || c.locked || c.historyReadFailed || c.active) return
  let parsed
  try {
    parsed = command(c.draft)
  } catch (error) {
    c.fail(error)
    return
  }
  if (!parsed) {
    await c.submit()
    return
  }
  if (c.history.pending_interactions.length) {
    c.error = '请先处理当前确认或补充信息。'
    return
  }
  const api = c.client,
    selected = c.target
  if (!api) return
  commandBusy.value = true
  c.error = ''
  const source = c.draft
  let id = commandKeys.get(source)
  if (!id) {
    id = crypto.randomUUID()
    commandKeys.set(source, id)
  }
  try {
    if (parsed.kind === 'delivery' && !c.runId) {
      c.notice = '当前没有可查询的运行。'
      return
    }
    if (parsed.kind !== 'goal-create' && parsed.kind !== 'plan' && !c.sessionId) {
      c.notice = '当前尚未创建会话。'
      return
    }
    const session = await c.ensureSession()
    if (api !== c.client || selected !== c.target) return
    async function control<T>(path: string, body: unknown): Promise<T> {
      const scope = `${c.clientRef}:${session}:${source}`,
        frozen = controlBodies.get(scope) ?? { path, body }
      controlBodies.set(scope, frozen)
      const result = await api!.json<T>(frozen.path, 'POST', frozen.body)
      controlBodies.delete(scope)
      return result
    }
    if (parsed.kind === 'plan') {
      const value = await control<Collaboration>('/chat/runs/plan-mode', {
        session_id: session,
        command_id: id,
        active: parsed.active
      })
      if (api !== c.client || session !== c.sessionId) return
      c.history.collaboration = value
      c.notice = parsed.active ? '已选择规划模式。' : '已选择普通模式。'
    } else if (parsed.kind === 'delivery') {
      const result = await api.json<{
        runtime_status: string
        sse: { status: string }
        webhook: { status: string }
      }>(`/chat/runs/delivery?${query({ session_id: session, run_id: c.runId })}`)
      if (api !== c.client || session !== c.sessionId) return
      c.notice = `运行：${result.runtime_status}；事件回放：${result.sse.status}；Webhook：${result.webhook.status}。送达状态不表示用户已读。`
    } else {
      let value: GoalStatus
      if (parsed.kind === 'goal-create')
        value = await control<GoalStatus>('/chat/goals/create', {
          session_id: session,
          command_id: id,
          objective: parsed.objective,
          max_rounds: parsed.rounds
        })
      else {
        value = await api.json<GoalStatus>(`/chat/goals?${query({ session_id: session })}`)
        if (parsed.kind !== 'goal-status') {
          if (!value.goal) throw new Error('当前没有持续目标。')
          value = await control<GoalStatus>(
            `/chat/goals/${parsed.kind === 'goal-pause' ? 'pause' : 'resume'}`,
            { session_id: session, command_id: id, expected_revision: value.goal.revision }
          )
        }
      }
      if (api !== c.client || session !== c.sessionId) return
      const stateLabels: Record<string, string> = {
        ACTIVE: '进行中',
        PAUSED: '已暂停',
        COMPLETE: '已完成',
        BLOCKED: '受阻'
      }
      c.notice = value.goal
        ? `持续目标：${stateLabels[value.goal.state] ?? '状态待核实'} · ${value.goal.rounds_started}/${value.goal.max_rounds} 轮。完整目标可在“会话工具”中查看。`
        : '当前没有持续目标。'
    }
    commandKeys.delete(source)
    if (c.draft === source) c.draft = ''
  } catch (error) {
    if (api === c.client && selected === c.target) c.fail(error)
  } finally {
    commandBusy.value = false
  }
}
function theme() {
  const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'
  document.documentElement.dataset.theme = next
  document.documentElement.classList.toggle('dark', next === 'dark')
  try {
    localStorage.setItem('skillforge-chat:theme', next)
  } catch {}
}

const formTipId = useId()
</script>
<template>
  <main v-if="!c.client" class="connect-page">
    <ChatForm
      ref="connectionRef"
      :model="settings"
      :rules="connectionRules"
      :disabled="c.connecting"
      label-position="top"
      class="connect-card"
      @submit.prevent="connect"
      ><div class="brand-mark">灵</div>
      <h1>灵知</h1>
      <p class="muted">填写机构提供的连接信息，选择智能体开始对话。</p>
      <ChatMessageNotice
        v-if="c.error"
        :title="c.error"
        type="error"
        :closable="false"
        show-icon
      /><ChatFormItem label="服务地址" prop="address"
        ><ChatInput
          v-described-by="'connection-help' + ' ' + (formTipId + '-field-0')"
          v-model="settings.address"
          :aria-describedby="'connection-help' + ' ' + (formTipId + '-field-0')"
          placeholder="http://localhost:8000"
        />
        <FieldTip :id="formTipId + '-field-0'"
          >填写可访问的 灵知 服务地址，连接前核对地址和认证方式。</FieldTip
        > </ChatFormItem
      ><ChatFormItem label="你的称呼" prop="name"
        ><ChatInput
          v-described-by="formTipId + '-field-1'"
          :aria-describedby="formTipId + '-field-1'"
          v-model="settings.name"
          maxlength="100"
          placeholder="如何称呼你"
        />
        <FieldTip :id="formTipId + '-field-1'"
          >选填，用于会话中的称呼；最多 100 个字符。</FieldTip
        > </ChatFormItem
      ><ChatFormItem label="认证方式" prop="mode"
        ><ChatSelect
          v-described-by="formTipId + '-field-2'"
          :aria-describedby="formTipId + '-field-2'"
          v-model="settings.mode"
          ><ChatOption value="token" label="渠道令牌" /><ChatOption value="hmac" label="API 签名"
        /></ChatSelect>
        <FieldTip :id="formTipId + '-field-2'"
          >选择渠道令牌或 API 凭据认证，填写对应认证信息。</FieldTip
        > </ChatFormItem
      ><ChatFormItem v-if="settings.mode === 'token'" label="渠道令牌" prop="token"
        ><ChatInput
          v-described-by="'connection-help' + ' ' + (formTipId + '-field-3')"
          v-model="settings.token"
          :aria-describedby="'connection-help' + ' ' + (formTipId + '-field-3')"
          type="password"
          autocomplete="off"
        />
        <FieldTip :id="formTipId + '-field-3'"
          >填写管理员提供的渠道访问令牌，用于连接所选服务。</FieldTip
        > </ChatFormItem
      ><template v-else
        ><ChatFormItem label="API Key" prop="apiKey"
          ><ChatInput
            v-described-by="'connection-help' + ' ' + (formTipId + '-field-4')"
            v-model="settings.apiKey"
            :aria-describedby="'connection-help' + ' ' + (formTipId + '-field-4')"
            autocomplete="off"
          />
          <FieldTip :id="formTipId + '-field-4'"
            >填写管理员提供的 API Key，与 API Secret 配套使用。</FieldTip
          > </ChatFormItem
        ><ChatFormItem label="API Secret" prop="secret"
          ><ChatInput
            v-described-by="'connection-help' + ' ' + (formTipId + '-field-5')"
            v-model="settings.secret"
            :aria-describedby="'connection-help' + ' ' + (formTipId + '-field-5')"
            type="password"
            autocomplete="off"
          />
          <FieldTip :id="formTipId + '-field-5'">填写与 API Key 配套的 API Secret。</FieldTip>
        </ChatFormItem></template
      ><ChatButton native-type="submit" type="primary" class="full" :loading="c.connecting"
        >进入会话</ChatButton
      >
      <p id="connection-help" class="muted small">
        服务地址与认证信息由机构管理员提供。认证信息仅保存在当前浏览器标签页，关闭标签页后清除。
      </p></ChatForm
    >
  </main>
  <main
    v-else
    class="workspace"
    :class="{ 'has-artifacts': activePanel === 'artifacts', 'has-tasks': activePanel === 'tasks' }"
  >
    <ChatDrawer
      v-model="historyOpen"
      direction="ltr"
      :size="'min(268px, 92vw)'"
      :with-header="false"
      class="history-drawer"
      aria-label="会话导航"
    >
      <aside class="sidebar">
        <header>
          <div class="sidebar-brand">
            <span class="brand-mark" aria-hidden="true">灵</span>
            <span class="brand-wordmark"
              ><strong>灵知</strong><small>THINK. DO. CREATE.</small></span
            >
          </div>
          <ChatButton
            text
            class="icon-button"
            native-type="button"
            title="收起会话导航"
            aria-label="收起会话导航"
            @click="historyOpen = false"
            ><IconPanelLeftClose class="ui-icon" aria-hidden="true"
          /></ChatButton>
        </header>
        <ChatForm class="target-picker" label-position="top" :disabled="c.locked"
          ><ChatFormItem label="对话对象"
            ><ChatSelect
              v-described-by="formTipId + '-target'"
              :model-value="c.target"
              :aria-describedby="formTipId + '-target'"
              filterable
              :disabled="c.locked"
              @change="navigate(() => c.changeTarget($event))"
              ><ChatOptionGroup label="智能体"
                ><ChatOption
                  v-for="agent in c.agents"
                  :key="agent.id"
                  :value="`agent:${agent.id}`"
                  :label="agentChoiceName(agent, c.agents)"
                  :search-text="agent.role_description"
                  class="agent-choice-option"
                  ><span class="agent-choice-content"
                    ><span>{{ agentChoiceName(agent, c.agents) }}</span
                    ><small v-if="agent.role_description">{{ agent.role_description }}</small></span
                  ></ChatOption
                ></ChatOptionGroup
              ><ChatOptionGroup v-if="c.teams.length" label="团队"
                ><ChatOption
                  v-for="team in c.teams.filter(
                    (item) => item.status !== 'disabled' || c.target === `team:${item.id}`
                  )"
                  :key="team.id"
                  :value="`team:${team.id}`"
                  :label="team.status === 'disabled' ? `${team.name}（已停用）` : team.name"
                  :disabled="team.status === 'disabled'" /></ChatOptionGroup
            ></ChatSelect>
            <FieldTip :id="formTipId + '-target'"
              >按名称或角色说明查找智能体；团队由负责人协调成员协作。</FieldTip
            >
          </ChatFormItem></ChatForm
        ><ChatButton
          native-type="button"
          class="primary new-conversation"
          :disabled="c.locked || !c.target || targetDisabled"
          :title="targetDisabled ? '团队已停用，请选择其他对话对象' : '新建会话'"
          @click="navigate(c.newSession)"
          ><IconPlus class="ui-icon" aria-hidden="true" /><span>新对话</span></ChatButton
        >
        <ChatInput
          v-described-by="formTipId + '-group-0'"
          :aria-describedby="formTipId + '-group-0'"
          v-model="historyQuery"
          class="session-search"
          placeholder="搜索会话"
          aria-label="搜索会话"
          clearable
        >
          <template #prefix><IconSearch class="ui-icon" aria-hidden="true" /></template>
        </ChatInput>
        <FieldTip :id="formTipId + '-group-0'"
          >输入关键词筛选会话标题；清空恢复当前会话列表。</FieldTip
        >

        <div class="session-caption">
          <span>最近会话</span>
          <ChatButton
            text
            native-type="button"
            :disabled="!c.target || c.sessionsLoading"
            @click="c.refreshSessions"
            >刷新会话</ChatButton
          >
        </div>
        <nav class="sessions" aria-label="历史会话">
          <p v-if="c.sessionsLoading" class="muted" role="status">正在读取历史会话…</p>
          <div v-else-if="c.sessionsError">
            <p class="muted" role="status">
              {{
                c.sessions.length
                  ? '历史会话列表尚未刷新，已有会话仍可查看。'
                  : '历史会话列表尚未读取成功。'
              }}
            </p>
          </div>
          <p v-else-if="!c.sessions.length" class="muted">
            还没有历史会话，发送首条消息后会显示在这里
          </p>
          <p v-if="c.sessions.length && !filteredSessions.length" class="muted">
            当前已读取的列表中没有匹配的会话，可清空关键词查看全部会话
          </p>
          <ChatButton
            v-for="session in filteredSessions"
            :key="session.session_id"
            native-type="button"
            :class="{ selected: c.sessionId === session.session_id }"
            :disabled="historyNavigationLocked || commandBusy"
            @click="
              session.session_id !== c.sessionId &&
              navigate(() => c.openSession(session.session_id), historyNavigationLocked)
            "
            >{{ session.displayTitle }}<small>{{ session.message_count }} 条消息</small></ChatButton
          >
        </nav>
        <footer class="sidebar-global-actions">
          <ChatButton text native-type="button" @click="openDevices"
            ><IconMonitor class="ui-icon" aria-hidden="true" /><span>浏览器设备</span
            ><small>全局</small></ChatButton
          >
          <ChatButton text native-type="button" @click="openPanel('teams')"
            ><IconUsers class="ui-icon" aria-hidden="true" /><span>团队管理</span></ChatButton
          >
          <div class="sidebar-account">
            <span>当前工作空间</span>
            <ChatButton
              text
              class="icon-button"
              native-type="button"
              title="切换明暗主题"
              aria-label="切换明暗主题"
              @click="theme"
              ><IconMoon class="ui-icon" aria-hidden="true"
            /></ChatButton>
            <ChatButton text native-type="button" @click="disconnect">断开连接</ChatButton>
          </div>
        </footer>
      </aside>
    </ChatDrawer>
    <section class="conversation" :style="{ '--composer-clearance': `${composerClearance}px` }">
      <header class="conversation-header">
        <div class="conversation-heading">
          <ChatButton
            text
            class="history-toggle icon-button"
            title="打开会话导航"
            aria-label="打开会话导航"
            @click="historyOpen = true"
            ><IconPanelLeft class="ui-icon" aria-hidden="true"
          /></ChatButton>
          <ChatButton
            text
            class="icon-button header-new"
            aria-label="新建会话"
            :disabled="c.locked || !c.target || targetDisabled"
            :title="targetDisabled ? '团队已停用，请选择其他对话对象' : '新建会话'"
            @click="navigate(c.newSession)"
            ><IconSquarePen class="ui-icon" aria-hidden="true"
          /></ChatButton>
          <div class="conversation-title">
            <ChatButton
              text
              class="header-target"
              aria-label="选择对话对象"
              @click="historyOpen = true"
              ><h1>{{ c.targetName || '选择对话对象' }}</h1>
              <IconChevronDown class="ui-icon" aria-hidden="true"
            /></ChatButton>
            <span class="connection-status muted small" role="status"
              ><span
                class="connection-dot"
                :class="{ 'is-active': c.active }"
                aria-hidden="true"
              />{{ c.connection
              }}{{ c.runStatus ? ` · ${statusName[c.runStatus] || c.runStatus}` : '' }}</span
            >
          </div>
        </div>
        <div class="actions header-actions">
          <ChatButton
            text
            class="header-action"
            :class="{ selected: activePanel === 'tasks' }"
            :disabled="!c.sessionId"
            @click="activePanel === 'tasks' ? closePanel() : openTask()"
            >任务</ChatButton
          >
          <ChatButton
            text
            class="header-action"
            :class="{ selected: activePanel === 'artifacts' }"
            :disabled="!c.sessionId"
            @click="openPanel('artifacts')"
            ><IconLayoutGrid class="ui-icon" aria-hidden="true" /><span>成果</span
            ><span v-if="c.artifacts.length" class="artifact-count">{{
              c.artifacts.length
            }}</span></ChatButton
          >
          <ChatPopover
            v-model:visible="headerMenuOpen"
            trigger="click"
            placement="bottom-end"
            :width="180"
            :show-arrow="false"
            popper-class="header-menu-popper"
          >
            <div class="header-menu" role="group" aria-label="更多会话操作">
              <ChatButton
                text
                :disabled="!c.sessionId"
                :title="!c.sessionId ? '发送首条消息后可使用会话工具' : '打开会话工具'"
                @click="menuAction(() => openPanel('tools'))"
                >会话工具</ChatButton
              >
              <ChatButton
                text
                :disabled="!c.sessionId || c.loading"
                @click="menuAction(c.reconnect)"
                >重新连接</ChatButton
              >
              <ChatButton text @click="menuAction(theme)">切换明暗主题</ChatButton>
            </div>
            <template #reference
              ><ChatButton text class="icon-button" aria-label="更多会话操作" title="更多会话操作"
                ><IconEllipsis class="ui-icon" aria-hidden="true" /></ChatButton
            ></template>
          </ChatPopover>
        </div>
      </header>
      <WorkspaceStatus
        :client="c.client!"
        :session="c.sessionId"
        :refresh-key="workspaceRefreshKey"
      />
      <ChatMessageNotice
        v-if="c.error"
        :title="c.error"
        :closable="false"
        type="error"
        class="banner"
        show-icon
        @close="c.error = ''"
      /><ChatMessageNotice
        v-if="c.notice"
        :title="c.notice"
        type="info"
        class="banner"
        show-icon
        @close="c.notice = ''"
      />
      <div
        v-for="item in c.currentUnconfirmedUploads"
        :key="item.uploadId"
        role="status"
        class="banner"
      >
        {{ item.kind === 'media' ? '图片' : '附件' }}
        {{ item.name }}
        {{ c.isUploadInFlight(item) ? '正在上传' : '的上传结果尚未确认' }}。上传标识：{{
          item.uploadId
        }}
        <ChatButton
          text
          :disabled="c.isUploadInFlight(item)"
          @click="c.checkUnconfirmedUpload(item.uploadId)"
          >{{ item.kind === 'media' ? '核对图片' : '核对附件' }}</ChatButton
        >
      </div>
      <div
        v-for="item in c.currentConfirmedUploads"
        :key="item.uploadId"
        role="status"
        class="banner"
      >
        {{ item.kind === 'media' ? '图片' : '附件' }} {{ item.name }} 已上传。
        <ChatButton
          text
          :disabled="c.locked || c.inputMode !== 'message'"
          @click="c.claimConfirmedUpload(item.uploadId)"
          >加入草稿</ChatButton
        >
        <span v-if="c.locked || c.inputMode !== 'message'">请在普通消息可编辑时加入草稿。</span>
      </div>
      <div v-if="c.uploadStorageUnavailable" role="status" class="banner">
        浏览器存储异常，暂不能上传新文件；请恢复存储并刷新页面，未确认记录仍需核对。
      </div>
      <div
        ref="timelineRef"
        class="timeline"
        role="region"
        aria-label="会话消息"
        tabindex="0"
        @scroll.passive="trackScroll"
        @wheel.passive="readEarlier"
        @keydown="readEarlier"
        @touchstart.passive="beginTimelineTouch"
        @touchmove.passive="moveTimelineTouch"
      >
        <div ref="timelineContentRef" class="timeline-content">
          <ChatButton
            v-if="c.history.has_more"
            native-type="button"
            :disabled="c.loading"
            @click="loadEarlier"
            >加载更早消息</ChatButton
          >
          <p v-if="c.loading" class="muted">正在读取会话…</p>
          <div v-if="c.historyReadFailed && !c.loading">
            <p class="muted" role="status">
              会话记录尚未读取成功，已有内容和草稿仍保留。请重新读取后发送。
            </p>
            <ChatButton native-type="button" @click="c.reconnect">重新读取会话记录</ChatButton>
          </div>
          <section
            v-if="
              !c.history.messages.length &&
              !c.history.processes.length &&
              !c.history.pending_interactions.length &&
              !c.history.work_plans?.length &&
              !c.active &&
              !c.loading &&
              !c.historyReadFailed
            "
            class="empty-state"
          >
            <div class="brand-mark">灵</div>
            <h2>今天想完成什么？</h2>
            <p>向{{ c.targetName || '智能体' }}描述需求，也可以附上资料和图片。</p>
            <ChatButton v-if="!c.target" @click="historyOpen = true">选择对话对象</ChatButton>
          </section>
          <ConversationTimeline
            :history="c.history"
            :client="c.client!"
            :session="c.sessionId"
            :target-name="c.targetName"
            :locked="c.locked"
            :run-states="c.runStates"
            :connection="c.connection"
            :artifacts="c.artifacts"
            @reference="c.reference"
            @artifact="openArtifact"
            @task="openTask"
          />

          <section
            v-for="pending in c.history.pending_interactions"
            :key="
              JSON.stringify([
                c.sessionId,
                pending.execution_id,
                pending.tool_wait?.action_id,
                pending.tool_wait?.generation ?? pending.checkpoint_revision
              ])
            "
          >
            <BrowserConnectionCard
              v-if="
                pending.interaction_type === 'tool_input' &&
                pending.card?.type === 'browser_connection' &&
                c.client
              "
              :client="c.client"
              :client-ref="c.clientRef"
              :pending="pending"
              :disabled="c.historyReadFailed"
            />
            <PendingForm
              v-else
              ref="pendingRef"
              :pending="pending"
              :busy="c.sending || Boolean(c.pendingInput) || c.cancelling"
              :disabled="c.historyReadFailed"
              :cancel-disabled="c.locked"
              :question-displayed="pendingQuestionDisplayed(pending)"
              @submit="c.submit($event)"
              @cancel="c.cancel(pending.execution_id)"
            />
          </section>
        </div>
      </div>
      <div ref="composerDockRef" class="composer-dock">
        <ChatButton v-if="!followingLatest" class="latest-button" round @click="scrollLatest"
          >↓ 回到最新</ChatButton
        >
        <div class="composer-context">
          <div
            v-if="c.active || c.inputMode === 'supplement'"
            class="composer-modes"
            aria-label="输入用途"
          >
            <ChatButton
              text
              :class="{ selected: c.inputMode === 'supplement' }"
              :aria-pressed="c.inputMode === 'supplement'"
              @click="c.setInputMode('supplement')"
              >补充本轮</ChatButton
            >
            <ChatButton
              text
              :class="{ selected: c.inputMode === 'message' }"
              :aria-pressed="c.inputMode === 'message'"
              @click="c.setInputMode('message')"
              >下一条草稿</ChatButton
            >
            <span v-if="c.active" class="muted small"
              ><span class="activity-spinner" aria-hidden="true" /> 正在处理</span
            >
          </div>
          <div v-if="c.steering?.items.length" class="steering-status" aria-label="本轮补充状态">
            <span v-for="item in c.steering.items" :key="item.command_id" :title="item.text">
              {{
                item.status === 'CONSUMED'
                  ? '✓ 已消费'
                  : item.status === 'INCLUDED'
                    ? '已纳入请求'
                    : '等待纳入'
              }}
              · {{ item.text }}
            </span>
          </div>
          <ChatMessageNotice
            v-if="c.steeringError"
            :title="c.steeringError"
            type="warning"
            :closable="false"
          />
          <ChatAlert
            v-if="c.pendingSteering && !c.steeringSending"
            title="补充请求的结果尚未确认。请先查询原补充；仍需重试时，使用下方的原请求入口。"
            type="warning"
            :closable="false"
          >
            <ChatButton
              :disabled="c.steeringSending || c.steeringLoading"
              @click="c.refreshSteering()"
              >查询状态</ChatButton
            >
            <ChatButton
              :disabled="c.steeringSending || c.steeringLoading || c.loading || c.historyReadFailed"
              @click="c.submitSupplement(true)"
              >重试原补充</ChatButton
            >
          </ChatAlert>
          <ChatAlert
            v-if="c.pendingInput && !c.sending"
            title="上次提交结果尚未确认。请先恢复观察；仍需重试时，使用下方的原请求入口。"
            type="warning"
            :closable="false"
            ><ChatButton text :disabled="c.loading" @click="c.reconnect()">恢复观察</ChatButton
            ><ChatButton
              :disabled="c.loading || c.historyReadFailed"
              @click="c.submit(undefined, true)"
              >重试原请求</ChatButton
            ></ChatAlert
          >
        </div>
        <section
          class="composer"
          :class="{ 'is-running': c.active, 'is-expanded': composerExpanded }"
        >
          <ChatForm
            ref="composerRef"
            class="composer-form"
            :model="c"
            :rules="composerRules"
            :disabled="c.locked || commandBusy || c.steeringSending"
            @submit.prevent="send"
          >
            <ComposerAttachments
              v-if="c.inputMode === 'message' && (messageHasResources || c.references.length)"
              :pasted-texts="c.pastedTexts"
              :attachments="c.attachments"
              :images="c.images"
              :references="c.references"
              :attachment-files="c.attachmentFiles"
              :attachment-texts="c.attachmentTexts"
              :client="c.client!"
              :session="c.sessionId"
              :locked="c.locked || commandBusy"
              @remove-pasted="c.removePastedText"
              @remove-attachment="c.removeAttachment"
              @remove-image="c.images.splice($event, 1)"
              @remove-reference="c.references.splice($event, 1)"
              @restore="restoreText"
            />
            <ChatFormItem prop="composerDraft" class="composer-message" :validate-on-blur="false">
              <ChatPopover
                :visible="showCommands"
                placement="top-start"
                :width="'min(440px, calc(100vw - 32px))'"
                :teleported="true"
                :show-arrow="false"
                :persistent="false"
              >
                <div id="command-options" class="command-menu" role="listbox" aria-label="会话命令">
                  <ChatButton
                    v-for="(item, index) in commandMatches"
                    :id="`command-option-${index}`"
                    :key="item.command"
                    role="option"
                    :aria-selected="index === commandIndex"
                    :class="{ selected: index === commandIndex }"
                    tabindex="-1"
                    @mousedown.prevent
                    @click="pickCommand(index)"
                    ><strong>{{ item.command }}</strong> {{ item.title
                    }}<small>{{ item.description }}</small></ChatButton
                  >
                </div>
                <template #reference
                  ><span class="composer-input-reference"
                    ><ChatInput
                      ref="messageInputRef"
                      v-described-by="'composer-help'"
                      type="textarea"
                      id="message-input"
                      :validate-event="false"
                      aria-label="消息"
                      aria-describedby="composer-help"
                      aria-autocomplete="list"
                      aria-haspopup="listbox"
                      :aria-controls="showCommands ? 'command-options' : undefined"
                      :aria-activedescendant="
                        showCommands ? `command-option-${commandIndex}` : undefined
                      "
                      v-model="c.composerDraft"
                      :autosize="{ minRows: 1, maxRows: 7 }"
                      :placeholder="
                        c.inputMode === 'supplement'
                          ? '补充当前任务的要求…'
                          : c.history.pending_interactions.length
                            ? '输入另一项任务，或回复上方待办'
                            : c.active
                              ? '先写好下一条消息…'
                              : '继续提问…'
                      "
                      @keydown="commandKey"
                      @paste="pasteMessage" /></span
                ></template>
              </ChatPopover>
            </ChatFormItem>
            <div class="composer-actions">
              <div class="composer-tools">
                <ChatUpload
                  v-described-by="formTipId + '-upload'"
                  :aria-describedby="formTipId + '-upload'"
                  v-if="c.inputMode === 'message'"
                  class="composer-upload"
                  :disabled="c.locked || commandBusy"
                  multiple
                  @files="files"
                  ><ChatButton
                    text
                    class="composer-attach"
                    aria-label="添加文件或图片"
                    title="添加文件或图片"
                    ><IconPlus class="ui-icon" aria-hidden="true" /><span
                      class="composer-attach-label"
                      >添加文件</span
                    ></ChatButton
                  ></ChatUpload
                >
                <FieldTip v-if="c.inputMode === 'message'" :id="formTipId + '-upload'"
                  >文件上传后随消息发送；图片需要所选模型支持视觉。</FieldTip
                >
                <ChatButton
                  v-if="c.inputMode === 'message'"
                  text
                  class="composer-command"
                  :disabled="c.active || Boolean(c.draft.trim())"
                  title="输入 / 查看命令"
                  @click="insertCommand"
                  ><span aria-hidden="true">/</span> 命令</ChatButton
                >
                <span v-if="c.uploading" class="muted small">上传中…</span
                ><span v-if="c.history.collaboration.selected_active" class="badge">规划模式</span>
              </div>
              <div class="composer-send-group">
                <button
                  v-if="c.active || c.cancelling"
                  type="button"
                  class="stop-run composer-stop"
                  :disabled="c.cancelling"
                  :aria-label="c.cancelling ? '正在取消运行' : '停止运行'"
                  :title="c.cancelling ? '正在取消…' : '停止运行'"
                  @click="c.cancel()"
                >
                  <span
                    v-if="c.cancelling"
                    class="activity-spinner"
                    aria-hidden="true"
                  /><IconSquare v-else class="ui-icon" aria-hidden="true" />
                </button>
                <ChatButton
                  v-if="!c.cancelling && (!c.active || c.inputMode === 'supplement')"
                  native-type="submit"
                  type="primary"
                  class="composer-send"
                  :class="{ 'is-supplement': c.inputMode === 'supplement' }"
                  :aria-label="c.inputMode === 'supplement' ? '发送本轮补充' : '发送消息'"
                  :disabled="
                    c.inputMode === 'supplement'
                      ? !c.canSteer || !c.supplementDraft.trim() || Boolean(c.pendingSteering)
                      : c.historyReadFailed ||
                        !c.target ||
                        (targetDisabled && !c.sessionId) ||
                        (!c.draft.trim() && !messageHasResources)
                  "
                  :loading="c.sending || commandBusy || c.steeringSending"
                  :title="
                    c.inputMode === 'supplement'
                      ? '发送本轮补充'
                      : !c.target
                        ? '请先选择对话对象'
                        : targetDisabled && !c.sessionId
                          ? '团队已停用，请选择其他对话对象'
                          : !c.draft.trim() && !messageHasResources
                            ? '请输入消息或添加附件'
                            : '发送消息'
                  "
                  ><span v-if="c.inputMode === 'supplement'">发送补充</span
                  ><IconArrowUp class="ui-icon" aria-hidden="true"
                /></ChatButton>
              </div>
            </div>
          </ChatForm>
        </section>
        <p id="composer-help" class="composer-foot muted" role="status">{{ composerHelp }}</p>
      </div>
    </section>
    <ArtifactPanel
      ref="artifactRef"
      :model-value="activePanel === 'artifacts'"
      :client="c.client!"
      :session="c.sessionId"
      :artifacts="c.artifacts"
      :loading="c.artifactsLoading"
      :error="c.artifactsError"
      :selected-id="selectedArtifact"
      @update:selected-id="selectedArtifact = $event"
      @reference="c.reference"
      @refresh="c.refreshArtifacts()"
      @update:model-value="panelChanged('artifacts', $event)"
    />
    <BrowserDevices v-model="devicesOpen" :client="c.client!" :client-ref="c.clientRef" />
    <TaskDetailsPanel
      v-if="c.sessionId"
      ref="taskRef"
      :model-value="activePanel === 'tasks'"
      :client="c.client!"
      :session="c.sessionId"
      :selected-id="selectedWork"
      :work-plans="c.history.work_plans"
      :locked="c.locked"
      @selected="selectedWork = $event"
      @updated="c.updateWork"
      @update:model-value="panelChanged('tasks', $event)"
    />
    <ConversationTools
      v-if="c.sessionId"
      ref="toolsRef"
      :model-value="activePanel === 'tools'"
      :key="`${c.clientRef}:${c.sessionId}`"
      :client="c.client!"
      :session="c.sessionId"
      :run="c.runId"
      :client-ref="c.clientRef"
      :work-plans="c.history.work_plans"
      @reference="c.reference"
      @task="openTask"
      @update:model-value="panelChanged('tools', $event)"
    />
    <TeamManager
      ref="teamRef"
      :model-value="activePanel === 'teams'"
      :key="c.clientRef"
      :client="c.client!"
      :client-ref="c.clientRef"
      :agents="c.agents"
      :teams="c.teams"
      @saved="saveTeam"
      @update:model-value="panelChanged('teams', $event)"
    />
  </main>
</template>
