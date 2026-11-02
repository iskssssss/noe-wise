<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { isActive, isStopping, isRecord, readable, runLabel, unwrap } from './desktop'
import MarkdownMessage from './MarkdownMessage.vue'
import { desktopTheme, toggleTheme } from './ui-theme'
import type {
  Confirmation,
  HostStatus,
  ModelInput,
  ModelSettings,
  Session,
  SessionEvent,
  SessionState
} from './desktop'

const api = window.desktop
const host = ref<HostStatus>({ status: 'starting', message: '正在连接本地 Agent…' })
const sessions = ref<Session[]>([])
const selectedId = ref('')
const state = ref<SessionState | null>(null)
const MAX_PREVIEW_CHARACTERS = 262144
const previews = ref<Record<string, { runId: string; attemptId: string; text: string }>>({})
const visiblePreviews = computed(() => Object.values(previews.value).filter((preview) =>
  state.value?.runs.some((run) => run.id === preview.runId && isActive(run) && !['UNCERTAIN', 'WAITING', 'FINISHED'].includes(run.phase ?? '')) &&
  !state.value?.messages.some((message) => message.role === 'assistant' && message.runId === preview.runId)))
const sessionLoading = ref(false)
const sidebarLoading = ref(false)
const error = ref('')
const sidebarError = ref('')
const sessionError = ref('')
const drafts = reactive<Record<string, string>>({})
const submissionIds = new Map<string, { text: string; id: string }>()
const operationIds = new Map<string, string>()
let creatingSession: Session | undefined
const pendingWrites = reactive(new Set<string>())
const workspace = ref('')
const creating = ref(false)
const showSettings = ref(false)
const settingsDialog = ref<HTMLDialogElement>()
const settingsButton = ref<HTMLButtonElement>()
const sidebarDialog = ref<HTMLDialogElement>()
const activityDialog = ref<HTMLDialogElement>()
const sidebarToggle = ref<HTMLButtonElement>()
const detailsToggle = ref<HTMLButtonElement>()
const navigationOpen = ref(false)
const detailsOpen = ref(false)
const compactNavigation = ref(false)
const compactDetails = ref(false)
let settingsTrigger: HTMLElement | null = null
let navigationMedia: MediaQueryList
let detailsMedia: MediaQueryList
const modelSaved = ref<ModelSettings | null>(null)
const modelInput = reactive<ModelInput>({
  providerId: 'deepseek',
  baseUrl: 'https://api.deepseek.com',
  model: 'deepseek-flash',
  apiKey: ''
})
const settingsBaseline = ref('')
const settingsBusy = ref(false)
const settingsError = ref('')
const settingsNotice = ref('')
const messagesElement = ref<HTMLElement>()
const composer = ref<HTMLTextAreaElement>()
const detailTab = ref<'activity' | 'changes'>('activity')
const followingLatest = ref(true)
const hasNewMessages = ref(false)
const readingPositions = new Map<string, { top: number; following: boolean }>()
const refreshInFlight = new Set<string>()
const refreshAgain = new Set<string>()
let selectionGeneration = 0
let unsubscribe: (() => void) | undefined
let destroyed = false

const selectedSession = computed(() =>
  sessions.value.find((session) => session.id === selectedId.value)
)
const draft = computed({
  get: () => drafts[selectedId.value || 'new'] ?? '',
  set: (value) => {
    drafts[selectedId.value || 'new'] = value
  }
})
const activeRuns = computed(() => state.value?.runs.filter(isActive) ?? [])
const stoppingRuns = computed(() => state.value?.runs.filter(isStopping) ?? [])
const workingRuns = computed(() =>
  activeRuns.value.filter(
    (run) => run.phase !== 'UNCERTAIN' && run.status.toUpperCase() !== 'QUEUED'
  )
)
const taskActions = computed(() =>
  activeRuns.value.map((run) => {
    const label = `${run.status.toUpperCase() === 'QUEUED' ? '取消排队' : '停止'}${taskName(run.id)}`
    const input = state.value?.messages.find(
      (item) => item.role === 'user' && item.runId === run.id
    )
    const summary = input?.text.replace(/\s+/g, ' ').trim()
    return { run, label, description: summary ? `${label}：${summary}` : `${label}（${run.id}）` }
  })
)
const submitting = computed(() => pendingWrites.has(`submit:${selectedId.value}`))
const tools = computed(() => state.value?.events.filter((event) => event.type === 'tool') ?? [])
const changes = computed(() =>
  tools.value.filter((event) => isRecord(event.result) && typeof event.result.diff === 'string')
)
const modelDirty = computed(() => JSON.stringify(modelInput) !== settingsBaseline.value)
const canSubmit = computed(
  () =>
    host.value.status === 'ready' &&
    Boolean(selectedSession.value) &&
    Boolean(modelSaved.value?.hasApiKey) &&
    Boolean(draft.value.trim()) &&
    !historyBlocked.value &&
    !submitting.value
)
const conversationStatus = computed(
  () =>
    stoppingRuns.value[0] ??
    activeRuns.value.find(
      (run) =>
        ['RUNNING', 'RESUMING'].includes(run.status.toUpperCase()) && run.phase !== 'UNCERTAIN'
    ) ??
    activeRuns.value.find((run) => run.status.toUpperCase() !== 'QUEUED') ??
    activeRuns.value[0] ??
    state.value?.runs.at(-1)
)
const historyBlocked = computed(() => state.value?.runs.at(-1)?.recoveryBlocked ?? false)
const composerStatus = computed(() => {
  if (host.value.status !== 'ready') return '本地 Agent 尚未就绪，当前草稿会保留。'
  if (historyBlocked.value) return '此历史会话仅可查看，请新建会话开始任务。'
  if (!modelSaved.value?.hasApiKey) return '请先在模型设置中保存模型与 API Key。'
  if (submitting.value) return '正在提交任务，请等待受理结果。'
  return 'Ctrl / ⌘ + Enter 发送，Enter 换行。'
})
const outcomeNotices = computed(() =>
  (state.value?.runs ?? [])
    .filter(
      (run) =>
        ['FAILED', 'TERMINATED', 'CANCELLED'].includes(run.status) || run.phase === 'UNCERTAIN'
    )
    .map((run) => {
      const fact = state.value?.events
        .slice()
        .reverse()
        .find(
          (event) =>
            event.runId === run.id && isRecord(event.data) && typeof event.data.reason === 'string'
        )
      const reason = fact && isRecord(fact.data) ? readable(fact.data.reason) : ''
      const outputTruncated = reason === 'model_output_length'
      const hasToolFacts =
        state.value?.events.some((event) => event.runId === run.id && event.type === 'tool') ??
        false
      return {
        runId: run.id,
        label: outputTruncated ? '模型输出达到长度上限' : runLabel(run),
        reason,
        uncertain: run.phase === 'UNCERTAIN',
        outputTruncated,
        hasToolFacts,
        incompleteProjection: state.value?.projectionTruncated ?? false
      }
    })
)

function message(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败，请重试'
}
async function openExternal(url: string) {
  if (!api) return
  try {
    await unwrap(api.openExternal(url))
  } catch (cause) {
    error.value = message(cause)
  }
}
function showWelcome() {
  saveReadingPosition()
  closeNavigation()
  selectedId.value = ''
  previews.value = {}
  state.value = null
  selectionGeneration++
}
function operationIdentity(key: string): string {
  const existing = operationIds.get(key)
  if (existing) return existing
  const created = crypto.randomUUID()
  operationIds.set(key, created)
  return created
}
function shortPath(path: string): string {
  return path.split(/[\\/]/).filter(Boolean).at(-1) ?? path
}
function taskName(runId: string): string {
  const index = state.value?.runs.findIndex((run) => run.id === runId) ?? -1
  return index >= 0 ? `任务 ${index + 1}` : `任务 ${runId}`
}
function resultField(event: SessionEvent, field: string): string {
  return isRecord(event.result) ? readable(event.result[field]) : ''
}
function eventArguments(event: SessionEvent): string {
  return readable(event.arguments)
}
function toolOutcome(event: SessionEvent): { label: string; kind: string } {
  if (!isRecord(event.result)) return { label: '等待结果', kind: 'pending' }
  if (event.result.status === 'UNKNOWN') return { label: '结果未知', kind: 'unknown' }
  if (
    event.result.status === 'FAILED' ||
    event.result.isError === true ||
    (typeof event.result.exitCode === 'number' && event.result.exitCode !== 0)
  )
    return { label: '失败', kind: 'failed' }
  if (event.result.status === 'SUCCEEDED' || event.result.exitCode === 0)
    return { label: '已完成', kind: 'completed' }
  return { label: '已返回', kind: 'completed' }
}
function toolTruncated(event: SessionEvent): boolean {
  return (
    Boolean(event.fieldsTruncated?.length) ||
    (isRecord(event.result) &&
      Object.entries(event.result).some(
        ([key, value]) => key.endsWith('Truncated') && value === true
      ))
  )
}
function formatTime(value?: string): string {
  if (!value) return '本地会话'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? '本地会话'
    : date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}
async function scrollToEnd(sessionId = selectedId.value) {
  await nextTick()
  if (selectedId.value !== sessionId) return
  messagesElement.value?.scrollTo({ top: messagesElement.value.scrollHeight, behavior: 'instant' })
  followingLatest.value = true
  hasNewMessages.value = false
}

function saveReadingPosition() {
  if (selectedId.value && state.value?.session.id === selectedId.value && messagesElement.value)
    readingPositions.set(selectedId.value, {
      top: messagesElement.value.scrollTop,
      following: followingLatest.value
    })
}
function trackReadingPosition() {
  if (sessionLoading.value) return
  const element = messagesElement.value
  if (!element) return
  followingLatest.value = element.scrollHeight - element.scrollTop - element.clientHeight <= 48
  if (followingLatest.value) hasNewMessages.value = false
  saveReadingPosition()
}
function closeNavigation() {
  if (compactNavigation.value) sidebarDialog.value?.close()
}
function toggleNavigation() {
  if (sidebarDialog.value?.open && !sidebarDialog.value.matches(':modal'))
    sidebarDialog.value.close()
  if (sidebarDialog.value?.open) sidebarDialog.value.close()
  else sidebarDialog.value?.showModal()
  navigationOpen.value = Boolean(sidebarDialog.value?.open)
}
function toggleDetails() {
  const compact = detailsMedia.matches
  if (compact && activityDialog.value?.open && !activityDialog.value.matches(':modal'))
    activityDialog.value.close()
  if (activityDialog.value?.open) activityDialog.value.close()
  else if (compact) activityDialog.value?.showModal()
  else activityDialog.value?.show()
  detailsOpen.value = Boolean(activityDialog.value?.open)
}
function syncPanels() {
  const focused = document.activeElement instanceof HTMLElement ? document.activeElement : null
  const wasInNavigation = focused ? sidebarDialog.value?.contains(focused) : false
  const wasInDetails = focused ? activityDialog.value?.contains(focused) : false
  compactNavigation.value = navigationMedia.matches
  compactDetails.value = detailsMedia.matches
  // 媒体查询通知可能晚于点击；已经打开的窄屏模态不能被迟到的通知关闭。
  for (const [dialog, compact] of [
    [sidebarDialog.value, compactNavigation.value],
    [activityDialog.value, compactDetails.value]
  ] as const) {
    if (!dialog) continue
    if (compact) {
      if (dialog.open && !dialog.matches(':modal')) dialog.close()
    } else {
      if (dialog.matches(':modal')) dialog.close()
      if (!dialog.open) dialog.show()
    }
  }
  navigationOpen.value = Boolean(sidebarDialog.value?.open)
  detailsOpen.value = Boolean(activityDialog.value?.open)
  if (focused === document.body && document.activeElement instanceof HTMLElement)
    document.activeElement.blur()
  else if (focused?.isConnected && focused.getClientRects().length)
    focused.focus({ preventScroll: true })
  else if (wasInNavigation) sidebarToggle.value?.focus({ preventScroll: true })
  else if (wasInDetails) detailsToggle.value?.focus({ preventScroll: true })
}
function selectDetailTab(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  detailTab.value =
    event.key === 'Home'
      ? 'activity'
      : event.key === 'End'
        ? 'changes'
        : detailTab.value === 'activity'
          ? 'changes'
          : 'activity'
  void nextTick(() => document.getElementById(`tab-${detailTab.value}`)?.focus())
}

async function loadSessions() {
  if (!api || sidebarLoading.value) return
  sidebarLoading.value = true
  sidebarError.value = ''
  try {
    const result = await unwrap(api.listSessions())
    if (!destroyed) sessions.value = result
  } catch (cause) {
    if (!destroyed) sidebarError.value = message(cause)
  } finally {
    if (!destroyed) sidebarLoading.value = false
  }
}

async function selectSession(sessionId: string) {
  if (!api || (sessionId === selectedId.value && state.value)) return
  saveReadingPosition()
  // 切换时内容短暂清空会产生滚动事件，不能让它覆盖待恢复的位置。
  const reading = readingPositions.get(sessionId)
  followingLatest.value = reading?.following ?? true
  closeNavigation()
  const generation = ++selectionGeneration
  selectedId.value = sessionId
  previews.value = {}
  state.value = null
  sessionLoading.value = true
  error.value = ''
  sessionError.value = ''
  hasNewMessages.value = false
  try {
    const result = await unwrap(api.getSession(sessionId))
    if (destroyed || generation !== selectionGeneration) return
    state.value = result
    sessionLoading.value = false
    await nextTick()
    syncPanels()
    if (reading && !reading.following) {
      if (messagesElement.value) messagesElement.value.scrollTop = reading.top
    } else await scrollToEnd()
  } catch (cause) {
    if (!destroyed && generation === selectionGeneration) sessionError.value = message(cause)
  } finally {
    if (!destroyed && generation === selectionGeneration) sessionLoading.value = false
  }
}

async function refreshSession(sessionId: string) {
  if (!api) return
  if (refreshInFlight.has(sessionId)) {
    refreshAgain.add(sessionId)
    return
  }
  refreshInFlight.add(sessionId)
  const generation = selectionGeneration
  try {
    const result = await unwrap(api.getSession(sessionId))
    if (destroyed || selectedId.value !== sessionId || generation !== selectionGeneration) return
    const previousCount = state.value?.messages.length ?? 0
    const shouldFollow = followingLatest.value
    state.value = result
    for (const preview of Object.values(previews.value)) {
      const run = result.runs.find((item) => item.id === preview.runId)
      if (run && (!isActive(run) || ['UNCERTAIN', 'WAITING', 'FINISHED'].includes(run.phase ?? '')))
        delete previews.value[preview.runId]
    }
    sessionError.value = ''
    if (shouldFollow) await scrollToEnd()
    else if (result.messages.length > previousCount) hasNewMessages.value = true
  } catch (cause) {
    if (!destroyed && selectedId.value === sessionId && generation === selectionGeneration)
      sessionError.value = message(cause)
  } finally {
    refreshInFlight.delete(sessionId)
    if (refreshAgain.delete(sessionId) && !destroyed && selectedId.value === sessionId)
      void refreshSession(sessionId)
  }
}

async function chooseWorkspace() {
  if (!api || creating.value) return
  try {
    const selected = await unwrap(api.chooseWorkspace())
    if (selected) workspace.value = selected
  } catch (cause) {
    error.value = message(cause)
  }
}

async function createSession() {
  if (!api || creating.value || host.value.status !== 'ready') return
  if (!workspace.value) await chooseWorkspace()
  if (!workspace.value) return
  creating.value = true
  error.value = ''
  const creation =
    creatingSession?.workspace === workspace.value
      ? creatingSession
      : { id: crypto.randomUUID(), title: shortPath(workspace.value), workspace: workspace.value }
  creatingSession = creation
  const sessionId = creation.id
  try {
    await unwrap(api.createSession(creation))
    creatingSession = undefined
    await loadSessions()
    await selectSession(sessionId)
    await nextTick()
    composer.value?.focus()
  } catch (cause) {
    error.value = message(cause)
  } finally {
    creating.value = false
  }
}

async function submit() {
  if (!api || !canSubmit.value) return
  const sessionId = selectedId.value
  const text = draft.value.trim()
  const previous = submissionIds.get(sessionId)
  const inputId = previous?.text === text ? previous.id : crypto.randomUUID()
  submissionIds.set(sessionId, { text, id: inputId })
  pendingWrites.add(`submit:${sessionId}`)
  error.value = ''
  try {
    await unwrap(api.submitTask({ sessionId, text, inputId }))
    // 受理回执到达后才清空原草稿，迟到响应不能清掉随后编辑的输入。
    if (drafts[sessionId]?.trim() === text) drafts[sessionId] = ''
    submissionIds.delete(sessionId)
    await refreshSession(sessionId)
    if (selectedId.value === sessionId) await scrollToEnd(sessionId)
  } catch (cause) {
    if (selectedId.value === sessionId) error.value = message(cause)
  } finally {
    pendingWrites.delete(`submit:${sessionId}`)
  }
}

async function cancelRun(runId: string) {
  if (!api || pendingWrites.has(runId)) return
  const sessionId = selectedId.value
  pendingWrites.add(runId)
  try {
    await unwrap(api.cancelTask({ runId, inputId: operationIdentity(`cancel:${runId}`) }))
    await refreshSession(sessionId)
  } catch (cause) {
    if (!destroyed && selectedId.value === sessionId) error.value = message(cause)
  } finally {
    pendingWrites.delete(runId)
  }
}

async function confirmTool(confirmation: Confirmation, approved: boolean) {
  if (!api || pendingWrites.has(confirmation.waitingId)) return
  const sessionId = selectedId.value
  pendingWrites.add(confirmation.waitingId)
  error.value = ''
  try {
    await unwrap(
      api.confirmTool({
        runId: confirmation.runId,
        waitingId: confirmation.waitingId,
        approved,
        inputId: operationIdentity(`confirm:${confirmation.waitingId}:${approved}`)
      })
    )
    await refreshSession(sessionId)
  } catch (cause) {
    if (selectedId.value === sessionId) error.value = message(cause)
  } finally {
    pendingWrites.delete(confirmation.waitingId)
  }
}

async function openSettings() {
  if (!api) return
  settingsTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  closeNavigation()
  settingsError.value = ''
  settingsNotice.value = ''
  try {
    const saved = await unwrap(api.getModel())
    modelSaved.value = saved
    if (saved.hasApiKey && saved.configurationSaved === false)
      settingsError.value = '当前配置已在本次运行中应用，但尚未保存到磁盘。请再次保存；重启会使用磁盘中的原配置。'
    delete modelInput.outputTokens
    Object.assign(modelInput, {
      providerId: saved.providerId,
      baseUrl: saved.baseUrl,
      model: saved.model,
      apiKey: '',
      ...(saved.outputTokens !== undefined ? { outputTokens: saved.outputTokens } : {})
    })
    settingsBaseline.value = JSON.stringify(modelInput)
    showSettings.value = true
    await nextTick()
    settingsDialog.value?.showModal()
  } catch (cause) {
    error.value = message(cause)
  }
}
function closeSettings() {
  if (settingsBusy.value) return
  if (modelDirty.value && !window.confirm('模型设置尚未保存，确定放弃修改？')) return
  settingsDialog.value?.close()
  showSettings.value = false
  modelInput.apiKey = ''
  if (settingsTrigger?.isConnected && settingsTrigger.getClientRects().length)
    settingsTrigger.focus()
  else if (compactNavigation.value) sidebarToggle.value?.focus()
  else settingsButton.value?.focus()
}
function editOutputTokens(event: Event) {
  if (!(event.target instanceof HTMLInputElement)) return
  if (event.target.value === '') delete modelInput.outputTokens
  else modelInput.outputTokens = event.target.valueAsNumber
}
async function saveModel() {
  if (!api || settingsBusy.value) return
  settingsBusy.value = true
  settingsError.value = ''
  settingsNotice.value = ''
  try {
    modelSaved.value = await unwrap(api.saveModel({ ...modelInput }))
    modelInput.apiKey = ''
    settingsBaseline.value = JSON.stringify(modelInput)
    if (modelSaved.value.configurationSaved === false) {
      settingsError.value = '配置已在本次运行中应用，但未能保存到磁盘。请检查数据目录权限后再次保存；重启会使用磁盘中的原配置。'
      return
    }
    settingsNotice.value = modelSaved.value.keySaved
      ? '配置已保存，API Key 已使用系统加密。'
      : '配置已保存；API Key 仅在本次运行中保留，退出后需要重新输入。'
  } catch (cause) {
    settingsError.value = message(cause)
  } finally {
    settingsBusy.value = false
  }
}
async function testModel() {
  if (!api || settingsBusy.value || modelDirty.value) return
  settingsBusy.value = true
  settingsError.value = ''
  settingsNotice.value = ''
  try {
    const result = await unwrap(api.testModel())
    if (!isRecord(result) || typeof result.connected !== 'boolean')
      throw new Error('模型连接测试返回了无效响应')
    if (!result.connected) {
      const detail = typeof result.message === 'string' ? result.message : '模型连接尚未成功确认'
      const code = typeof result.code === 'string' ? `${result.code}: ` : ''
      throw new Error(`${code}${detail}`)
    }
    settingsNotice.value = '连接成功，模型返回了有效响应。'
  } catch (cause) {
    settingsError.value = message(cause)
  } finally {
    settingsBusy.value = false
  }
}
function selectProvider() {
  if (modelInput.providerId === 'deepseek') {
    const saved = modelSaved.value?.providerId === 'deepseek' ? modelSaved.value : undefined
    modelInput.baseUrl = saved?.baseUrl ?? 'https://api.deepseek.com'
    modelInput.model = saved?.model ?? 'deepseek-flash'
  }
}
function composerKey(event: KeyboardEvent) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) {
    event.preventDefault()
    void submit()
  }
}

onMounted(async () => {
  navigationMedia = window.matchMedia('(width < 760px)')
  detailsMedia = window.matchMedia('(width < 1180px)')
  navigationMedia.addEventListener('change', syncPanels)
  detailsMedia.addEventListener('change', syncPanels)
  syncPanels()
  if (!api) {
    host.value = { status: 'failed', message: '请从 Electron 桌面应用打开此界面' }
    return
  }
  unsubscribe = api.onEvent((event) => {
    if (
      event.event === 'host-status' &&
      isRecord(event.data) &&
      typeof event.data.status === 'string' &&
      typeof event.data.message === 'string'
    ) {
      host.value = {
        ...host.value,
        status: event.data.status as HostStatus['status'],
        message: event.data.message
      }
      if (host.value.status === 'ready') void loadSessions()
    } else if (event.event === 'modelPreview' && event.sessionId === selectedId.value &&
      event.runId && event.attemptId && typeof event.delta === 'string') {
      const previous = previews.value[event.runId]
      previews.value[event.runId] = { runId: event.runId, attemptId: event.attemptId,
        text: ((previous?.attemptId === event.attemptId ? previous.text : '') + event.delta).slice(0, MAX_PREVIEW_CHARACTERS) }
      if (followingLatest.value) void scrollToEnd()
    } else if (event.event === 'state' && event.sessionId && event.sessionId === selectedId.value) {
      void refreshSession(event.sessionId)
    } else if (
      ['configuration-error', 'hostError', 'fatal'].includes(event.event) &&
      isRecord(event.data) &&
      typeof event.data.message === 'string'
    ) {
      error.value = event.data.message
      if (event.event === 'fatal')
        host.value = { ...host.value, status: 'failed', message: event.data.message }
    }
  })
  try {
    host.value = await unwrap(api.getStatus())
    modelSaved.value = await unwrap(api.getModel())
    if (host.value.setupError) error.value = host.value.setupError
    if (host.value.status === 'ready') await loadSessions()
  } catch (cause) {
    error.value = message(cause)
  }
})
onBeforeUnmount(() => {
  destroyed = true
  selectionGeneration++
  unsubscribe?.()
  navigationMedia?.removeEventListener('change', syncPanels)
  detailsMedia?.removeEventListener('change', syncPanels)
})
</script>

<template>
  <div class="desktop-shell">
    <dialog
      ref="sidebarDialog"
      class="sidebar-dialog"
      aria-label="会话导航"
      @close="navigationOpen = Boolean(sidebarDialog?.open)"
      @click="
        (event: MouseEvent) => {
          if (event.target === sidebarDialog && compactNavigation) sidebarDialog?.close()
        }
      "
    >
      <aside class="sidebar" aria-label="会话导航">
        <button
          class="icon-button sidebar-close"
          aria-label="关闭会话导航"
          @click="closeNavigation"
        >
          ×
        </button>
        <a class="brand" href="#" @click.prevent="showWelcome">
          <span class="brand-mark" aria-hidden="true">n<span>·</span></span
          ><span>灵知 <b>NoeWise</b><small>DESKTOP / 本地工作台</small></span>
        </a>
        <button
          class="new-session"
          :disabled="host.status !== 'ready' || creating"
          @click="createSession"
        >
          <span aria-hidden="true">＋</span>{{ creating ? '正在创建…' : '新建会话' }}<kbd>本地</kbd>
        </button>
        <div class="section-label">
          最近会话 <span>{{ sessions.length.toString().padStart(2, '0') }}</span>
        </div>
        <nav class="session-list">
          <p v-if="sidebarLoading" class="sidebar-hint" role="status">正在读取会话…</p>
          <div v-if="sidebarError" class="retry-panel" role="alert">
            <p>会话列表读取失败：{{ sidebarError }}</p>
            <button class="secondary" :disabled="sidebarLoading" @click="loadSessions">
              重新读取会话
            </button>
          </div>
          <p v-else-if="!sidebarLoading && sessions.length === 0" class="sidebar-hint">
            从一个本地目录开始。<br />你的会话将保存在这台电脑。
          </p>
          <button
            v-for="session in sessions"
            :key="session.id"
            class="session-item"
            :class="{ selected: selectedId === session.id }"
            :aria-current="selectedId === session.id ? 'page' : undefined"
            @click="selectSession(session.id)"
          >
            <span class="session-icon" aria-hidden="true">▤</span
            ><span class="session-name"
              >{{ session.title
              }}<small :title="session.workspace"
                >{{ shortPath(session.workspace) }} · {{ formatTime(session.createdAt) }}</small
              ></span
            >
            <span v-if="drafts[session.id]" class="draft-dot" title="有未发送输入">●</span>
          </button>
        </nav>
        <div class="sidebar-bottom">
          <div class="local-note">
            <span aria-hidden="true">◈</span>
            <div>在你的电脑上执行<small>本地 Agent · 云端模型</small></div>
          </div>
          <button ref="settingsButton" class="settings-link" @click="openSettings">
            <span aria-hidden="true">⚙</span> 模型设置<span
              class="model-dot"
              :class="{ configured: modelSaved?.hasApiKey }"
            ></span>
          </button>
          <div class="sidebar-version">
            NoeWise Desktop <span>原型 v{{ host.clientVersion ?? '…' }}</span>
          </div>
        </div>
      </aside>
    </dialog>

    <main class="main-area">
      <header class="topbar">
        <button
          ref="sidebarToggle"
          class="secondary sidebar-toggle"
          aria-label="打开会话导航"
          :aria-expanded="navigationOpen"
          @click="toggleNavigation"
        >
          会话
        </button>
        <div class="breadcrumb">
          工作台<span>/</span><strong>{{ selectedSession?.title ?? '开始工作' }}</strong>
        </div>
        <div class="topbar-actions">
          <button
            v-if="selectedSession"
            ref="detailsToggle"
            class="secondary details-toggle"
            :aria-expanded="detailsOpen"
            aria-controls="execution-details"
            @click="toggleDetails"
          >
            执行记录与变更
          </button>
          <button
            class="secondary theme-toggle"
            @click="toggleTheme"
            :aria-label="desktopTheme === 'dark' ? '切换浅色主题' : '切换深色主题'"
          >
            {{ desktopTheme === 'dark' ? '浅色' : '深色' }}
          </button>
          <div class="host-indicator" :class="host.status" role="status">
            <span></span
            >{{
              host.status === 'ready'
                ? '本地 Agent 已就绪'
                : host.status === 'starting'
                  ? '正在启动 Agent'
                  : 'Agent 连接中断'
            }}
          </div>
        </div>
      </header>
      <div v-if="host.status === 'failed'" class="banner error-banner" role="alert">
        {{ host.message }}<span>输入仍保留在当前窗口，请核对任务记录后重启应用。</span>
      </div>
      <div v-if="error" class="banner error-banner" role="alert">
        {{ error }}<button aria-label="关闭错误提示" @click="error = ''">×</button>
      </div>
      <div v-if="!modelSaved?.hasApiKey && host.status !== 'failed'" class="banner model-banner">
        <span>连接云端模型，开始你的第一个任务。</span
        ><button @click="openSettings">配置模型 <span aria-hidden="true">↗</span></button>
      </div>

      <template v-if="!selectedSession">
        <section class="welcome">
          <div class="welcome-eyebrow"><span></span> YOUR LOCAL AGENT WORKSPACE</div>
          <h1>想法，从这里<br /><span>变成实际行动。</span></h1>
          <p class="welcome-copy">
            让 Agent 读取代码、修改文件、运行命令。<br />工作发生在本机，推理由你选择的云端模型完成。
          </p>
          <div class="workspace-picker">
            <div class="folder-art" aria-hidden="true">▱</div>
            <div>
              <strong>{{ workspace ? shortPath(workspace) : '选择一个工作目录' }}</strong>
              <p :title="workspace">{{ workspace || '将你正在做的项目带进来。' }}</p>
            </div>
            <button class="secondary" :disabled="host.status !== 'ready'" @click="chooseWorkspace">
              {{ workspace ? '更换目录' : '选择目录' }} <span aria-hidden="true">↗</span>
            </button>
          </div>
          <button
            class="primary welcome-start"
            :disabled="host.status !== 'ready' || creating"
            @click="createSession"
          >
            {{ creating ? '正在创建…' : '创建会话，开始工作' }} <span aria-hidden="true">→</span>
          </button>
          <div class="capability-grid">
            <div>
              <span aria-hidden="true">⌘</span><strong>理解你的项目</strong>
              <p>读取文件、搜索内容，<br />从真实上下文开始。</p>
            </div>
            <div>
              <span aria-hidden="true">±</span><strong>把修改留在本地</strong>
              <p>查看文件差异，<br />保留每一步的执行记录。</p>
            </div>
            <div>
              <span aria-hidden="true">›_</span><strong>命令由你确认</strong>
              <p>运行前查看完整命令，<br />随时停止当前任务。</p>
            </div>
          </div>
        </section>
        <footer class="welcome-footer">
          <span>◇</span> 工作目录与会话保存在本机 · 模型调用会发送任务上下文到所配置的供应商
        </footer>
      </template>

      <template v-else>
        <div class="session-heading">
          <details class="workspace-location">
            <summary>
              <span aria-hidden="true">▱</span> {{ shortPath(selectedSession.workspace) }} ·
              查看工作目录
            </summary>
            <code>{{ selectedSession.workspace }}</code>
          </details>
          <span v-if="conversationStatus" class="run-badge" role="status">{{
            isStopping(conversationStatus) ? `${runLabel(conversationStatus)} · 正在清理本地任务` : runLabel(conversationStatus)
          }}</span>
        </div>
        <div class="work-columns">
          <section class="conversation" aria-label="任务对话">
            <div
              ref="messagesElement"
              class="messages"
              :aria-busy="sessionLoading"
              @scroll="trackReadingPosition"
            >
              <div v-if="sessionError" class="retry-panel" role="alert">
                <p>会话记录读取失败，已有内容与草稿仍保留。{{ sessionError }}</p>
                <button
                  class="secondary"
                  :disabled="sessionLoading"
                  @click="state ? refreshSession(selectedId) : selectSession(selectedId)"
                >
                  重新读取记录
                </button>
              </div>
              <p v-if="state?.projectionTruncated" class="projection-notice" role="status">
                部分历史与输出已省略展示，完整记录仍保存在本机。
              </p>
              <div v-if="historyBlocked" class="outcome-notice" role="status">
                <strong>历史会话仅可查看</strong>
                <p>此会话使用旧版执行协议，历史记录和文件变更仍保留。请新建会话开始任务。</p>
              </div>
              <p v-if="sessionLoading" class="muted" role="status">正在读取会话记录…</p>
              <div v-else-if="state?.messages.length === 0" class="conversation-empty">
                <div class="empty-mark">n<span>·</span></div>
                <h2>从一个具体任务开始</h2>
                <p>例如：解释这个项目的入口；修复一个缺陷并运行测试。</p>
                <div class="suggestions">
                  <button
                    @click="draft = '请先阅读这个项目，说明目录结构、主要入口和如何运行测试。'"
                  >
                    了解项目结构 <span>↗</span></button
                  ><button
                    @click="
                      draft =
                        '请检查当前项目，找出一个可以明确复现的问题，提出修改方案后实施并运行相关测试。'
                    "
                  >
                    检查并修复问题 <span>↗</span>
                  </button>
                </div>
              </div>
              <article
                v-for="(item, index) in state?.messages"
                :key="index"
                class="message"
                :class="item.role"
              >
                <div class="message-avatar">{{ item.role === 'user' ? '你' : 'n·' }}</div>
                <div class="message-body">
                  <div class="message-author">
                    {{
                      item.role === 'user'
                        ? '你'
                        : item.role === 'assistant'
                          ? 'NoeWise'
                          : item.role
                    }}
                    <span v-if="item.runId" class="message-task-name">{{
                      taskName(item.runId)
                    }}</span>
                  </div>
                  <MarkdownMessage
                    v-if="item.role === 'assistant'"
                    class="message-text"
                    :text="item.text"
                    @open-link="openExternal"
                  />
                  <div v-else class="message-text">{{ item.text }}</div>
                </div>
              </article>
              <article v-for="preview in visiblePreviews" :key="preview.attemptId" class="message assistant">
                <div class="message-avatar">n·</div>
                <div class="message-body">
                  <div class="message-author">NoeWise <span class="message-task-name">生成中 · 未完成预览</span></div>
                  <MarkdownMessage class="message-text" :text="preview.text" @open-link="openExternal" />
                </div>
              </article>
              <div
                v-for="confirmation in state?.pendingConfirmations"
                :key="confirmation.waitingId"
                class="confirmation-card"
              >
                <div class="confirmation-title">
                  <span aria-hidden="true">›_</span><strong>确认执行命令</strong
                  ><span class="run-badge">等待你确认</span>
                </div>
                <p>命令将在本机执行，可能修改文件或访问网络。</p>
                <pre>{{ confirmation.command }}</pre>
                <div class="confirmation-path">工作目录：{{ confirmation.workspace }}</div>
                <div class="confirmation-actions">
                  <button
                    class="secondary"
                    :disabled="pendingWrites.has(confirmation.waitingId) || host.status !== 'ready'"
                    @click="confirmTool(confirmation, false)"
                  >
                    拒绝</button
                  ><button
                    class="primary"
                    :disabled="pendingWrites.has(confirmation.waitingId) || host.status !== 'ready'"
                    @click="confirmTool(confirmation, true)"
                  >
                    {{ pendingWrites.has(confirmation.waitingId) ? '正在处理…' : '允许这次执行' }}
                  </button>
                </div>
              </div>
              <div
                v-for="notice in outcomeNotices"
                :key="notice.runId"
                class="outcome-notice"
                role="status"
              >
                <strong>{{ notice.label }}</strong>
                <template v-if="notice.outputTruncated">
                  <p>
                    本次模型生成达到长度上限，输出被截断；这次生成的内容没有用于修改文件或执行命令。
                  </p>
                  <p v-if="notice.hasToolFacts">
                    该任务此前已有工具调用，已发生的操作仍保留，请先核对执行记录和文件。
                  </p>
                  <p v-else-if="notice.incompleteProjection">
                    部分历史已省略，请核对已有执行记录与工作目录。
                  </p>
                  <p v-else>此任务未记录本地工具调用。</p>
                  <p>
                    可以按文件或功能拆成小任务，或在模型设置中调整单次最大输出
                    token，再明确提交下一步。应用不会自动重试。
                  </p>
                  <details class="outcome-diagnostics">
                    <summary>诊断详情</summary>
                    <code>{{ notice.reason }}</code>
                  </details>
                </template>
                <span v-else-if="notice.reason">{{ notice.reason }}</span>
                <p v-if="notice.uncertain">
                  此任务没有已确认的最终结果。请先核对文件与外部操作，应用不会自动重放。
                </p>
              </div>
              <div
                v-if="stoppingRuns.length || (workingRuns.length && !state?.pendingConfirmations.length)"
                class="working-indicator"
                role="status"
              >
                <span class="spinner"></span>{{ stoppingRuns.length ? '正在清理本地任务' : 'Agent 正在工作' }}<span>
                  {{ stoppingRuns.length ? '正在等待本地执行与进程资源退出。业务结果保持原状态，可以继续追加任务。' : '可以继续追加任务，或停止当前任务。' }}</span>
              </div>
            </div>
            <button v-if="!followingLatest" class="secondary jump-to-latest" @click="scrollToEnd()">
              {{ hasNewMessages ? '有新回复 · 回到最新' : '回到最新消息' }}
              <span aria-hidden="true">↓</span>
            </button>
            <div class="composer-area">
              <div class="composer" :class="{ focused: Boolean(draft) }">
                <textarea
                  ref="composer"
                  v-model="draft"
                  maxlength="64000"
                  rows="3"
                  aria-label="任务内容"
                  aria-describedby="composer-status"
                  placeholder="描述你想完成的任务…"
                  @keydown="composerKey"
                ></textarea>
                <div class="composer-toolbar">
                  <span class="composer-model"
                    ><span aria-hidden="true">◇</span>{{ modelSaved?.model ?? '未配置模型' }}</span
                  >
                  <div class="composer-actions">
                    <button
                      v-for="action in taskActions"
                      :key="action.run.id"
                      class="stop-button"
                      :title="action.description"
                      :aria-label="action.description"
                      :disabled="pendingWrites.has(action.run.id) || host.status !== 'ready'"
                      @click="cancelRun(action.run.id)"
                    >
                      <span aria-hidden="true">■</span>{{ action.label }}</button
                    ><button
                      class="send-button"
                      aria-label="发送任务"
                      :disabled="!canSubmit"
                      @click="submit"
                    >
                      {{ submitting ? '…' : '↑' }}
                    </button>
                  </div>
                </div>
              </div>
              <p id="composer-status" class="composer-status" role="status">{{ composerStatus }}</p>
              <div class="composer-hint">
                <span>文件修改直接写入所选目录</span
                ><span>{{
                  draft.length ? `${draft.length.toLocaleString()} / 64,000` : '关键结果请自行核对'
                }}</span>
              </div>
            </div>
          </section>
          <dialog
            id="execution-details"
            ref="activityDialog"
            class="activity-dialog"
            aria-label="执行记录与文件变更"
            @close="detailsOpen = Boolean(activityDialog?.open)"
            @click="
              (event: MouseEvent) => {
                if (event.target === activityDialog && compactDetails) activityDialog?.close()
              }
            "
          >
            <aside class="activity-panel" aria-label="执行记录">
              <button
                class="icon-button details-close"
                aria-label="关闭执行记录"
                @click="toggleDetails"
              >
                ×
              </button>
              <div
                class="activity-tabs"
                role="tablist"
                aria-label="执行详情"
                @keydown="selectDetailTab"
              >
                <button
                  id="tab-activity"
                  role="tab"
                  aria-controls="detail-content"
                  :tabindex="detailTab === 'activity' ? 0 : -1"
                  :aria-selected="detailTab === 'activity'"
                  :class="{ active: detailTab === 'activity' }"
                  @click="detailTab = 'activity'"
                >
                  执行记录 <span>{{ tools.length }}</span></button
                ><button
                  id="tab-changes"
                  role="tab"
                  aria-controls="detail-content"
                  :tabindex="detailTab === 'changes' ? 0 : -1"
                  :aria-selected="detailTab === 'changes'"
                  :class="{ active: detailTab === 'changes' }"
                  @click="detailTab = 'changes'"
                >
                  文件变更 <span>{{ changes.length }}</span>
                </button>
              </div>
              <div
                id="detail-content"
                class="activity-content"
                role="tabpanel"
                :aria-labelledby="`tab-${detailTab}`"
                tabindex="0"
              >
                <div
                  v-if="(detailTab === 'activity' ? tools : changes).length === 0"
                  class="activity-empty"
                >
                  <span aria-hidden="true">{{ detailTab === 'activity' ? '☷' : '±' }}</span
                  ><strong>{{
                    detailTab === 'activity' ? '每一步，都有记录' : '还没有文件变更'
                  }}</strong>
                  <p>
                    {{
                      detailTab === 'activity'
                        ? '工具调用、命令输出和执行结果会出现在这里。'
                        : 'Agent 修改文件后，可在这里查看差异。'
                    }}
                  </p>
                </div>
                <details
                  v-for="tool in detailTab === 'activity' ? tools : changes"
                  :key="`${tool.runId}:${tool.sequence}`"
                  class="tool-card"
                  :open="detailTab === 'changes'"
                >
                  <summary>
                    <span class="tool-symbol">{{ resultField(tool, 'diff') ? '±' : '›_' }}</span
                    ><span
                      >{{ tool.toolName ?? tool.text ?? '工具调用'
                      }}<small>#{{ tool.sequence }}</small></span
                    ><span class="tool-status" :class="toolOutcome(tool).kind">{{
                      toolOutcome(tool).label
                    }}</span
                    ><span class="disclosure">⌄</span>
                  </summary>
                  <div class="tool-content">
                    <p v-if="toolTruncated(tool)" class="projection-notice">
                      此工具的部分展示内容已省略，完整持久记录未被修改。
                    </p>
                    <div v-if="eventArguments(tool)" class="output-section">
                      <span>输入</span>
                      <pre>{{ eventArguments(tool) }}</pre>
                    </div>
                    <div v-if="resultField(tool, 'diff')" class="output-section">
                      <span>文件差异</span>
                      <pre class="diff">{{ resultField(tool, 'diff') }}</pre>
                    </div>
                    <div v-if="resultField(tool, 'stdout')" class="output-section">
                      <span>标准输出</span>
                      <pre>{{ resultField(tool, 'stdout') }}</pre>
                    </div>
                    <div v-if="resultField(tool, 'stderr')" class="output-section">
                      <span>标准错误</span>
                      <pre class="stderr">{{ resultField(tool, 'stderr') }}</pre>
                    </div>
                    <div
                      v-if="isRecord(tool.result) && 'exitCode' in tool.result"
                      class="exit-code"
                    >
                      退出码 {{ tool.result.exitCode }}
                    </div>
                    <details v-if="tool.result !== undefined" class="raw-result">
                      <summary>结构化结果</summary>
                      <pre>{{ readable(tool.result) }}</pre>
                    </details>
                    <p v-else class="muted">尚未持久化结果</p>
                  </div>
                </details>
              </div>
              <div class="activity-footer">
                <span aria-hidden="true">◈</span> 本地执行 · 过程可追踪
              </div>
            </aside>
          </dialog>
        </div>
      </template>
    </main>

    <dialog
      v-if="showSettings"
      ref="settingsDialog"
      class="settings-dialog"
      aria-labelledby="settings-title"
      @cancel.prevent="closeSettings"
    >
      <form :aria-busy="settingsBusy" @submit.prevent="saveModel">
        <div class="dialog-header">
          <div>
            <span class="dialog-eyebrow">MODEL CONNECTION</span>
            <h2 id="settings-title">连接你的模型</h2>
          </div>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭模型设置"
            :disabled="settingsBusy"
            @click="closeSettings"
          >
            ×
          </button>
        </div>
        <p class="dialog-description">客户端直接连接云端 LLM。密钥不会写入会话记录。</p>
        <label
          >供应商<select
            autofocus
            v-model="modelInput.providerId"
            :disabled="settingsBusy"
            @change="selectProvider"
          >
            <option value="deepseek">DeepSeek</option>
            <option value="custom">自定义 OpenAI 兼容服务</option>
          </select></label
        ><label
          >服务地址<input
            v-model="modelInput.baseUrl"
            aria-describedby="base-url-help"
            type="url"
            required
            :disabled="settingsBusy"
            placeholder="https://api.deepseek.com"
          /><small id="base-url-help">填写 API 根地址，具体接口路径由 Provider 处理。</small></label
        ><label
          >模型名称<input
            v-model="modelInput.model"
            required
            maxlength="256"
            :disabled="settingsBusy"
            placeholder="deepseek-flash" /></label
        ><label
          >单次最大输出 token（可选）<input
            :value="modelInput.outputTokens ?? ''"
            type="number"
            min="1"
            :max="Number.MAX_SAFE_INTEGER"
            step="1"
            aria-label="单次最大输出 token"
            aria-describedby="output-tokens-help"
            placeholder="留空使用宿主默认值"
            :disabled="settingsBusy"
            @input="editOutputTokens"
          /><small id="output-tokens-help"
            >这是单次生成上限，不是整任务预算；供应商可能限制更低。保存后对新任务生效。</small
          ></label
        ><label
          >API Key<input
            v-model="modelInput.apiKey"
            aria-label="API Key"
            aria-describedby="api-key-help"
            type="password"
            autocomplete="off"
            spellcheck="false"
            maxlength="8192"
            :disabled="settingsBusy"
            :placeholder="modelSaved?.hasApiKey ? '已设置 · 留空保留现有密钥' : '输入你的 API Key'"
          /><small id="api-key-help">{{
            modelSaved?.encryptionAvailable
              ? '使用操作系统加密后保存在本机。'
              : '系统加密不可用，密钥仅在本次运行中保留。'
          }}</small></label
        >
        <div v-if="settingsError" class="form-feedback error-banner" role="alert">
          {{ settingsError }}
        </div>
        <div v-if="settingsNotice" class="form-feedback success-feedback" role="status">
          {{ settingsNotice }}
        </div>
        <div class="model-disclosure">
          <span aria-hidden="true">◇</span>
          连接测试会向所配置的模型发送一条短请求，可能产生少量费用。
        </div>
        <p v-if="modelDirty || !modelSaved?.hasApiKey" class="settings-help">
          先应用完整配置，再测试当前连接。
        </p>
        <div class="dialog-footer">
          <button
            type="button"
            class="secondary"
            :disabled="
              settingsBusy || modelDirty || !modelSaved?.hasApiKey || host.status !== 'ready'
            "
            @click="testModel"
          >
            测试当前配置</button
          ><button
            type="submit"
            class="primary"
            :disabled="settingsBusy || host.status !== 'ready'"
          >
            {{ settingsBusy ? '正在处理…' : '保存配置' }}
          </button>
        </div>
      </form>
    </dialog>
  </div>
</template>
