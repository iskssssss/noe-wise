import { computed, onBeforeUnmount, reactive, ref, shallowRef, toRaw, watch } from 'vue'
import {
  createClient,
  delay,
  observe,
  query,
  RequestError,
  serviceBase,
  type Client
} from './client'
import {
  createProjection,
  emptyHistory,
  mergeMessages,
  mergeProcesses,
  provisionalMessage
} from './projection'
import { mergeWorkPlans, runIsTerminal, workIsTerminal } from './workPlans'
import type { Work } from './protocol'
import type { PastedTextAttachment } from './pastedText'
import type {
  Agent,
  Attachment,
  ChatInput,
  ChatResult,
  Credentials,
  Deliverable,
  History,
  Limits,
  Media,
  Reference,
  Session,
  SteeringInput,
  SteeringState,
  Target,
  Team
} from './protocol'

// 仅完整业务错误响应可证明未受理；超时、服务端异常和损坏响应仍保留原请求。
const rejectedInputStatuses = new Set([400, 403, 404, 405, 409, 410, 413, 415, 422, 429])
class UploadUnconfirmedError extends Error {}
export interface ConfirmedUpload {
  file: File
  value: Attachment | Media
}
export function useConversation() {
  const client = shallowRef<Client>(),
    agents = ref<Agent[]>([]),
    teams = ref<Team[]>([]),
    sessions = ref<Session[]>([]),
    target = ref(''),
    sessionId = ref('')
  const userId = ref(''),
    clientRef = ref(''),
    history = ref<History>(emptyHistory()),
    error = ref(''),
    notice = ref(''),
    connection = ref('未连接')
  const connecting = ref(false),
    loading = ref(false),
    sessionsLoading = ref(false),
    sessionsError = ref(''),
    historyReadFailed = ref(false),
    sending = ref(false),
    uploading = ref(false),
    cancelling = ref(false),
    draft = ref('')
  const runId = ref(''),
    runStatus = ref(''),
    limits = ref<Limits>(),
    attachments = ref<Attachment[]>([]),
    images = ref<Media[]>([]),
    references = ref<Reference[]>([])
  // 上传请求已派发后结果未知时保留原会话与上传标识；仅服务端明确回执能移除。
  type UnconfirmedUpload = {
    base: string
    clientRef: string
    session: string
    uploadId: string
    name: string
    kind: 'attachment' | 'media'
    receipt?: Attachment | Media
    pasted?: boolean
    pastedWithdrawn?: boolean
  }
  const uploadStorageUnavailable = ref(false)
  let savedUploads: UnconfirmedUpload[] = []
  try {
    savedUploads = JSON.parse(sessionStorage.getItem('skillforge.chat.unconfirmedUploads') || '[]')
  } catch {
    uploadStorageUnavailable.value = true
  }
  const unconfirmedUploads = ref<UnconfirmedUpload[]>(savedUploads)
  watch(
    unconfirmedUploads,
    (value) => {
      try {
        if (value.length)
          sessionStorage.setItem('skillforge.chat.unconfirmedUploads', JSON.stringify(value))
        else sessionStorage.removeItem('skillforge.chat.unconfirmedUploads')
      } catch {
        uploadStorageUnavailable.value = true
      }
    },
    { deep: true, flush: 'sync' }
  )
  const retainedPastedUploads = new Map<string, PastedTextAttachment>()
  const inFlightUploads = reactive(new Set<string>())
  const uploadKey = (item: UnconfirmedUpload) =>
    JSON.stringify([item.base, item.clientRef, item.session, item.uploadId])
  const isUploadInFlight = (item: UnconfirmedUpload) => inFlightUploads.has(uploadKey(item))
  const currentUnconfirmedUploads = computed(() =>
    unconfirmedUploads.value.filter(
      (item) =>
        item.base === client.value?.base &&
        item.clientRef === clientRef.value &&
        item.session === sessionId.value &&
        !item.receipt
    )
  )
  const currentConfirmedUploads = computed(() =>
    unconfirmedUploads.value.filter(
      (item) =>
        item.base === client.value?.base &&
        item.clientRef === clientRef.value &&
        item.session === sessionId.value &&
        item.receipt
    )
  )
  const pastedTexts = ref<PastedTextAttachment[]>([]),
    attachmentFiles = shallowRef<Record<string, File>>({}),
    attachmentTexts = ref<Record<string, string>>({})
  const attachmentTextRanges = new Map<
    string,
    { start?: number; end?: number; selectedText?: string; sourceDraft?: string }
  >()
  let pastedTextOrdinal = 0
  const cancellingRun = ref('')
  const pendingInput = shallowRef<ChatInput>()
  const supplementDraft = ref(''),
    inputMode = ref<'message' | 'supplement'>('message'),
    steering = ref<SteeringState>(),
    steeringSending = ref(false),
    steeringLoading = ref(false),
    steeringError = ref(''),
    pendingSteering = shallowRef<SteeringInput>()
  const artifacts = ref<Deliverable[]>([]),
    artifactsRevision = ref(0),
    artifactsLoading = ref(false),
    artifactsError = ref('')
  const runStates = reactive<Record<string, string>>({})
  // 成员执行通过工作投影归属父计划，其过程可见但不成为父会话控制目标。
  const isMemberRun = (run: string) =>
    (history.value.work_plans ?? []).some((work) =>
      work.tasks.some((task) => task.executor_type === 'MEMBER' && task.run_id === run)
    )
  let identity = 0,
    selection = 0,
    identityAbort = new AbortController(),
    sessionAbort = new AbortController(),
    streamAbort = new AbortController()
  const profileRefs = new Map<string, { client_ref: string; identity_token: string }>()
  let createPromise: Promise<string> | undefined,
    cancelPoll: ReturnType<typeof setTimeout> | undefined,
    steeringPoll: ReturnType<typeof setTimeout> | undefined,
    submissionRequest = 0,
    steeringRequest = 0,
    artifactsRequest = 0,
    sessionsRequest = 0,
    historyLoaded = false
  const active = computed(() =>
    Object.values(runStates).some((status) => !runIsTerminal(status) && status !== 'waiting_hitl')
  )
  const steeringRun = computed(() => {
    const running = (run: string) =>
      !isMemberRun(run) &&
      Boolean(runStates[run]) &&
      !runIsTerminal(runStates[run]!) &&
      runStates[run] !== 'waiting_hitl'
    const work = history.value.work_plans?.find((work) => running(work.run_id))
    if (work) return work.run_id
    return running(runId.value) ? runId.value : (Object.keys(runStates).find(running) ?? '')
  })
  const composerDraft = computed({
    get: () => (inputMode.value === 'supplement' ? supplementDraft.value : draft.value),
    set: (value: string) => {
      if (inputMode.value === 'supplement') supplementDraft.value = value
      else draft.value = value
    }
  })
  const canSteer = computed(() =>
    Boolean(
      steeringRun.value &&
      steering.value?.run_id === steeringRun.value &&
      steering.value.accepting &&
      !locked.value &&
      !historyReadFailed.value &&
      !steeringSending.value &&
      !pendingSteering.value
    )
  )
  const selectedTarget = computed<Target>(() =>
    target.value.startsWith('team:')
      ? { team_id: target.value.slice(5) }
      : { agent_id: target.value.slice(6) }
  )
  const targetName = computed(() =>
    target.value.startsWith('team:')
      ? teams.value.find((item) => item.id === target.value.slice(5))?.name
      : agents.value.find((item) => item.id === target.value.slice(6))?.name
  )
  const locked = computed(
    () =>
      connecting.value ||
      loading.value ||
      sending.value ||
      uploading.value ||
      cancelling.value ||
      Boolean(pendingInput.value)
  )
  const fail = (cause: unknown) => {
    error.value =
      cause instanceof Error ? cause.message : '未能确认操作结果，请先核对当前会话状态。'
  }
  /** 合并已上传与本地待上传资源校验数量和 UTF-8 总字节，选中待上传项不重复计数。 */
  function checkAttachmentCapacity(
    files: { size: number }[],
    excludedLocalIds = new Set<string>()
  ) {
    const cap = limits.value?.attachments
    if (!cap) return
    const staged = pastedTexts.value.filter((item) => !excludedLocalIds.has(item.id))
    if (
      attachments.value.length + staged.length + files.length > cap.max_files ||
      attachments.value.reduce((sum, file) => sum + file.size, 0) +
        staged.reduce((sum, file) => sum + file.size, 0) +
        files.reduce((sum, file) => sum + file.size, 0) >
        cap.max_total_bytes
    )
      throw new Error('附件数量或总大小超过当前会话限制。')
  }
  /** 仅为正常消息草稿暂存原文，未知限额留到发送时验证，失败返回 false 供输入框保留粘贴。 */
  function stagePastedText(text: string, selectionStart?: number, selectionEnd?: number): boolean {
    if (text.length < 2000 && (text.length < 800 || text.split(/\r\n|\r|\n/).length < 30))
      return false
    if (inputMode.value !== 'message' || locked.value) {
      notice.value = '请在可编辑的消息草稿中添加文本附件。'
      return false
    }
    const ordinal = pastedTextOrdinal + 1,
      name = ordinal === 1 ? '粘贴文本.txt' : `粘贴文本 ${ordinal}.txt`,
      size = new TextEncoder().encode(text).byteLength,
      cap = limits.value?.attachments
    try {
      if (cap && (!cap.extensions.includes('txt') || size > cap.max_file_bytes))
        throw new Error('粘贴文本的类型或大小不符合当前附件限制，原文已保留。')
      checkAttachmentCapacity([{ size }])
      const { from, to } = draftRange(selectionStart, selectionEnd)
      pastedTexts.value.push({
        id: crypto.randomUUID(),
        name,
        text,
        size,
        selectionStart: from,
        selectionEnd: to,
        selectedText: draft.value.slice(from, to),
        sourceDraft: draft.value
      })
      pastedTextOrdinal = ordinal
      error.value = ''
      return true
    } catch (cause) {
      fail(cause)
      return false
    }
  }
  /** 本地草稿项只在可编辑消息模式移除，冻结输入与补充模式不操作下一条消息资源。 */
  function withdrawPastedUpload(id: string) {
    const record = currentUnconfirmedUploads.value.find(
      (item) => item.uploadId === id && item.pasted
    )
    if (!record) return
    retainedPastedUploads.delete(uploadKey(record))
    record.pastedWithdrawn = true
  }
  function removePastedText(id: string): boolean {
    if (inputMode.value !== 'message' || locked.value) return false
    const item = pastedTexts.value.find((value) => value.id === id)
    if (!item) return false
    withdrawPastedUpload(id)
    pastedTexts.value = pastedTexts.value.filter((value) => value.id !== id)
    return true
  }
  /** textarea 的 UTF-16 选区收敛到当前草稿边界，无选区时使用末尾。 */
  function draftRange(start?: number, end?: number) {
    const length = draft.value.length,
      from = Number.isFinite(start) ? Math.max(0, Math.min(length, Math.trunc(start!))) : length,
      to = Number.isFinite(end) ? Math.max(from, Math.min(length, Math.trunc(end!))) : from
    return { from, to }
  }
  /** 保持原粘贴位置；草稿变化后仅插入，避免相同前缀误匹配导致原文被截断。 */
  function insertDraftText(
    text: string,
    start?: number,
    end?: number,
    selectedText?: string,
    sourceDraft?: string
  ) {
    const current = draft.value,
      { from, to: originalEnd } = draftRange(start, end),
      to =
        current === sourceDraft &&
        selectedText !== undefined &&
        current.slice(from, originalEnd) === selectedText
          ? originalEnd
          : from
    draft.value = `${current.slice(0, from)}${text}${current.slice(to)}`
    return from + text.length
  }
  /** 本地粘贴附件还原进当前消息选区后移除，原文从不进入补充草稿。 */
  function restorePastedText(id: string): number | undefined {
    if (inputMode.value !== 'message' || locked.value) return
    const item = pastedTexts.value.find((value) => value.id === id)
    if (!item) return
    const caret = insertDraftText(
      item.text,
      item.selectionStart,
      item.selectionEnd,
      item.selectedText,
      item.sourceDraft
    )
    withdrawPastedUpload(id)
    pastedTexts.value = pastedTexts.value.filter((value) => value.id !== id)
    return caret
  }
  /** 移除已上传草稿资源及本地预览缓存，不删除任何服务端历史或成果。 */
  function removeAttachment(id: string): boolean {
    if (inputMode.value !== 'message' || locked.value) return false
    if (!attachments.value.some((item) => item.id === id)) return false
    attachments.value = attachments.value.filter((item) => item.id !== id)
    const { [id]: _file, ...remainingFiles } = attachmentFiles.value,
      { [id]: _text, ...remainingTexts } = attachmentTexts.value
    attachmentFiles.value = remainingFiles
    attachmentTexts.value = remainingTexts
    attachmentTextRanges.delete(id)
    return true
  }
  /** 已确认上传的粘贴原文可还原，随后移除对应附件 ID 和缓存，防止再次随消息发送。 */
  function restoreAttachmentText(id: string): number | undefined {
    if (inputMode.value !== 'message' || locked.value) return
    if (!Object.hasOwn(attachmentTexts.value, id)) return
    const text = attachmentTexts.value[id]
    if (text === undefined || !attachments.value.some((item) => item.id === id)) return
    const range = attachmentTextRanges.get(id),
      caret = insertDraftText(
        text,
        range?.start,
        range?.end,
        range?.selectedText,
        range?.sourceDraft
      )
    removeAttachment(id)
    return caret
  }
  function setInputMode(mode: 'message' | 'supplement') {
    if (mode === 'message' || steeringRun.value) inputMode.value = mode
  }
  function preserveSupplement() {
    // 仅未提交文本能成为下一条草稿；结果未知的命令必须保留原身份供查询或重试。
    if (supplementDraft.value.trim() && !pendingSteering.value) {
      draft.value = [draft.value, supplementDraft.value].filter(Boolean).join('\n\n')
      supplementDraft.value = ''
    }
    inputMode.value = 'message'
  }
  function applySteering(value: SteeringState) {
    steering.value = value
    const pending = pendingSteering.value
    if (
      pending?.run_id === value.run_id &&
      value.items.some((item) => item.command_id === pending.command_id)
    ) {
      pendingSteering.value = undefined
      if (supplementDraft.value === pending.text) supplementDraft.value = ''
      if (!steeringRun.value) preserveSupplement()
    }
  }
  async function refreshSteering(
    run = pendingSteering.value?.run_id || steeringRun.value || steering.value?.run_id
  ) {
    const api = client.value,
      id = sessionId.value,
      version = selection
    if (!api || !id || !run || steeringSending.value) return
    if (steeringPoll) clearTimeout(steeringPoll)
    const request = ++steeringRequest
    steeringLoading.value = true
    try {
      const value = await api.json<SteeringState>(
        `/chat/runs/steering?${query({ session_id: id, run_id: run })}`,
        'GET',
        undefined,
        sessionAbort.signal
      )
      if (version !== selection || request !== steeringRequest) return
      applySteering(value)
      steeringError.value = ''
    } catch (cause) {
      if (version === selection && request === steeringRequest)
        steeringError.value = cause instanceof Error ? cause.message : '补充状态读取失败。'
    } finally {
      if (version === selection && request === steeringRequest) {
        steeringLoading.value = false
        if (steeringRun.value === run)
          steeringPoll = setTimeout(() => void refreshSteering(run), 2500)
      }
    }
  }
  async function submitSupplement(retry = false) {
    const api = client.value,
      version = selection
    if (
      !api ||
      historyReadFailed.value ||
      steeringSending.value ||
      cancelling.value ||
      (retry && (loading.value || steeringLoading.value)) ||
      (!retry && !canSteer.value)
    )
      return
    const body = retry
      ? pendingSteering.value
      : {
          session_id: sessionId.value,
          run_id: steeringRun.value,
          command_id: crypto.randomUUID(),
          text: supplementDraft.value
        }
    if (!body || !body.text.trim()) return
    pendingSteering.value = body
    steeringSending.value = true
    steeringError.value = ''
    ++steeringRequest
    steeringLoading.value = false
    if (steeringPoll) clearTimeout(steeringPoll)
    try {
      const value = await api.json<SteeringState>(
        '/chat/runs/steer',
        'POST',
        body,
        sessionAbort.signal
      )
      if (version !== selection) return
      applySteering(value)
    } catch (cause) {
      if (version === selection) {
        if (
          !retry &&
          cause instanceof RequestError &&
          cause.status === 400 &&
          cause.code === 'VALIDATION_ERROR'
        ) {
          // 首次请求的绑定校验拒绝能证明未写入；重试拒绝不能证明原请求未被受理。
          pendingSteering.value = undefined
          if (!steeringRun.value) preserveSupplement()
        }
        steeringError.value = cause instanceof Error ? cause.message : '补充提交失败。'
      }
    } finally {
      if (version === selection) {
        steeringSending.value = false
        // 读取真实消费状态；不将已受理但尚未消费的补充重新提交为新任务。
        void refreshSteering(body.run_id)
      }
    }
  }
  async function refreshArtifacts() {
    const api = client.value,
      id = sessionId.value,
      version = selection
    if (!api || !id) return
    const request = ++artifactsRequest
    artifactsLoading.value = true
    artifactsError.value = ''
    try {
      const value = await api.json<Deliverable[]>(
        `/chat/sessions/deliverables?${query({ sessionId: id, includeHistory: 'true' })}`,
        'GET',
        undefined,
        sessionAbort.signal
      )
      if (version === selection && request === artifactsRequest) artifacts.value = value
    } catch (cause) {
      if (version === selection && request === artifactsRequest)
        artifactsError.value = cause instanceof Error ? cause.message : '成果读取失败。'
    } finally {
      if (version === selection && request === artifactsRequest) artifactsLoading.value = false
    }
  }
  watch([sessionId, steeringRun], ([id, run], [previousId, previous]) => {
    if (!id) return
    if (steeringPoll) clearTimeout(steeringPoll)
    if (previous && id === previousId) preserveSupplement()
    if (run) {
      inputMode.value = 'supplement'
      steering.value = undefined
      void refreshSteering(run)
    } else if (previous && id === previousId) void refreshSteering(previous)
  })
  function clearSession() {
    selection++
    ++submissionRequest
    sessionAbort.abort()
    sessionAbort = new AbortController()
    streamAbort.abort()
    if (cancelPoll) clearTimeout(cancelPoll)
    if (steeringPoll) clearTimeout(steeringPoll)
    ++steeringRequest
    ++artifactsRequest
    sessionId.value = ''
    historyLoaded = false
    history.value = emptyHistory()
    runId.value = ''
    runStatus.value = ''
    Object.keys(runStates).forEach((key) => delete runStates[key])
    draft.value = ''
    supplementDraft.value = ''
    inputMode.value = 'message'
    steering.value = undefined
    pendingSteering.value = undefined
    steeringSending.value = false
    steeringLoading.value = false
    steeringError.value = ''
    artifacts.value = []
    artifactsRevision.value = 0
    artifactsLoading.value = false
    artifactsError.value = ''
    attachments.value = []
    pastedTexts.value = []
    attachmentFiles.value = {}
    attachmentTexts.value = {}
    attachmentTextRanges.clear()
    pastedTextOrdinal = 0
    images.value = []
    references.value = []
    pendingInput.value = undefined
    limits.value = undefined
    loading.value = false
    historyReadFailed.value = false
    sessionsLoading.value = false
    sessionsRequest++
    sending.value = false
    uploading.value = false
    cancelling.value = false
    cancellingRun.value = ''
    createPromise = undefined
    connection.value = '未观察会话'
    error.value = ''
    notice.value = ''
  }
  function disconnect() {
    identity++
    identityAbort.abort()
    identityAbort = new AbortController()
    clearSession()
    client.value = undefined
    agents.value = []
    teams.value = []
    sessions.value = []
    sessionsError.value = ''
    target.value = ''
    userId.value = ''
    connecting.value = false
  }
  async function connect(address: string, credentials: Credentials, name: string) {
    disconnect()
    const version = identity
    connecting.value = true
    let identityStorageKey = ''
    try {
      const api = createClient(serviceBase(address), credentials, (code) => {
        if (version === identity) {
          disconnect()
          if (code === 'CHAT_USER_IDENTITY_REQUIRED') {
            profileRefs.delete(identityStorageKey)
            try {
              localStorage.removeItem(identityStorageKey)
            } catch {}
            error.value = '聊天身份已失效。再次连接将登记新访客，无法凭客户端编号恢复原会话。'
          } else error.value = '渠道凭证已失效，请重新连接。'
        }
      })
      const catalog = await api.json<{ channel_id: string; items: Agent[] }>(
        '/chat/agents',
        'GET',
        undefined,
        identityAbort.signal
      )
      if (version !== identity) return
      const storageKey = `skillforge.chat.profile:${api.base}:${catalog.channel_id}`
      identityStorageKey = storageKey
      let stored = profileRefs.get(storageKey)
      try {
        const raw = localStorage.getItem(storageKey)
        if (raw) {
          const value = JSON.parse(raw)
          if (typeof value.client_ref === 'string' && typeof value.identity_token === 'string')
            stored = value
        }
      } catch {}
      // 旧版裸UUID没有身份凭证，不可用于恢复已有用户。
      const profile = stored?.client_ref ?? crypto.randomUUID()
      if (stored?.identity_token) api.setIdentityToken(stored.identity_token)
      clientRef.value = profile
      const person = await api.json<{ end_user_id: string; identity_token: string }>(
        '/chat/client-profile',
        'POST',
        { client_ref: profile, display_name: name.trim() || '访客' },
        identityAbort.signal
      )
      if (version !== identity) return
      if (!person.identity_token) throw new Error('服务未返回有效聊天身份凭证。')
      const identityProfile = { client_ref: profile, identity_token: person.identity_token }
      profileRefs.set(storageKey, identityProfile)
      try {
        localStorage.setItem(storageKey, JSON.stringify(identityProfile))
      } catch {}
      api.setIdentityToken(person.identity_token)
      client.value = api
      userId.value = person.end_user_id
      agents.value = catalog.items
      try {
        const values = await api.json<Team[]>(
          `/chat/teams?${query({ client_ref: profile })}`,
          'GET',
          undefined,
          identityAbort.signal
        )
        if (version !== identity) return
        teams.value = values
      } catch (cause) {
        if (version === identity) fail(cause)
      }
      const first = catalog.items.find((item) => item.is_default) ?? catalog.items[0]
      if (first) await changeTarget(`agent:${first.id}`)
      else {
        const availableTeams = teams.value.filter((team) => team.status !== 'disabled')
        if (availableTeams.length === 1) await changeTarget(`team:${availableTeams[0]!.id}`)
      }
    } catch (cause) {
      if (version === identity) fail(cause)
    } finally {
      if (version === identity) connecting.value = false
    }
  }
  async function refreshSessions() {
    const api = client.value,
      version = selection
    if (!api || !target.value) return
    const request = ++sessionsRequest
    sessionsLoading.value = true
    if (sessionsError.value && error.value === sessionsError.value) error.value = ''
    sessionsError.value = ''
    try {
      const values = await api.json<Session[]>(
        `/chat/sessions?${query({ end_user_id: userId.value, ...(selectedTarget.value.team_id ? { team_id: selectedTarget.value.team_id } : { agentId: selectedTarget.value.agent_id }) })}`,
        'GET',
        undefined,
        sessionAbort.signal
      )
      if (version === selection && request === sessionsRequest) sessions.value = values
    } catch (cause) {
      if (version === selection && request === sessionsRequest) {
        fail(cause)
        sessionsError.value = error.value
      }
    } finally {
      if (version === selection && request === sessionsRequest) sessionsLoading.value = false
    }
  }
  async function changeTarget(value: string) {
    clearSession()
    target.value = value
    sessions.value = []
    sessionsError.value = ''
    await refreshSessions()
  }
  function updateWork(work: Work) {
    if (work.session_id !== sessionId.value) return
    const previous = history.value.work_plans?.find((item) => item.work_id === work.work_id)
    if (!previous || previous.run_id !== work.run_id || previous.input_id !== work.input_id) return
    history.value.work_plans = mergeWorkPlans(history.value.work_plans ?? [], [work])
  }
  function rememberRun(run: string, status: string) {
    if (!run || isMemberRun(run)) return
    // 受理回执可能晚于终态到达，不能把已完成的执行重新标为运行中。
    if (runIsTerminal(runStates[run] ?? '') && !runIsTerminal(status)) return
    // Runtime 挂起也包含内部依赖等待；计划审批必须由同一根输入的真实工作事实确认。
    const planApproval = history.value.work_plans?.some((work) => {
      const activity = history.value.activities?.find((item) => item.render?.root_run_id === run)
      const render = activity?.render as { root_input_id?: string; reason?: string } | undefined
      const rootInput = render?.root_input_id
      return (
        work.session_id === history.value.session_id &&
        work.run_id === run &&
        !!rootInput &&
        work.input_id === rootInput &&
        !workIsTerminal(work) &&
        ['active', 'queued'].includes(work.status) &&
        work.run_status === 'suspended' &&
        render?.reason === 'plan_approval_required' &&
        work.approval_required &&
        work.plan_revision > 0 &&
        (work.approved_revision ?? 0) < work.plan_revision
      )
    })
    if (
      status === 'suspended' &&
      (planApproval || history.value.pending_interactions.some((item) => item.execution_id === run))
    )
      status = 'waiting_hitl'
    runStates[run] = status
    const activeWork = history.value.work_plans?.find(
      (work) =>
        work.run_id !== run &&
        runStates[work.run_id] &&
        runStates[work.run_id] !== 'waiting_hitl' &&
        !runIsTerminal(runStates[work.run_id]!)
    )
    runId.value = activeWork?.run_id ?? run
    runStatus.value = activeWork ? runStates[activeWork.run_id]! : status
    if (runIsTerminal(status)) {
      history.value.pending_interactions = history.value.pending_interactions.filter(
        (item) => item.execution_id !== run
      )
      if (cancellingRun.value === run) {
        if (cancelPoll) clearTimeout(cancelPoll)
        cancelPoll = undefined
        cancelling.value = false
        cancellingRun.value = ''
      }
      const other = Object.entries(runStates).find(
        ([id, state]) => !isMemberRun(id) && !runIsTerminal(state)
      )
      if (other) {
        runId.value = other[0]
        runStatus.value = other[1]
      }
      void refreshSessions()
    }
  }
  watch(
    () => history.value.work_plans,
    (works) => {
      for (const run of Object.keys(runStates)) if (isMemberRun(run)) delete runStates[run]
      for (const work of works ?? []) if (work.run_status) rememberRun(work.run_id, work.run_status)
    },
    { flush: 'sync' }
  )
  /** 只释放当前冻结输入；事件确认后，旧 HTTP 响应失去写入下一条草稿的权限。 */
  function confirmInput(body: ChatInput, supersedeRequest = true, run = '') {
    if (pendingInput.value !== body) return
    if (supersedeRequest) ++submissionRequest
    pendingInput.value = undefined
    sending.value = false
    error.value = ''
    const hitl = body.hitl_response
    if (hitl) {
      const waitingInput = (hitl.tool_input ?? hitl.human_input ?? hitl.workflow_input) as
        { wait?: { action_id?: string; generation?: string } } | undefined
      history.value.pending_interactions = history.value.pending_interactions.filter(
        (item) =>
          item.execution_id !== hitl.execution_id ||
          (waitingInput
            ? item.run_revision !== hitl.run_revision ||
              item.tool_wait?.action_id !== waitingInput.wait?.action_id ||
              item.tool_wait?.generation !== waitingInput.wait?.generation
            : item.checkpoint_revision !== hitl.checkpoint_revision)
      )
      return
    }
    const submittedText = body.content
      .filter((part) => part.type === 'text')
      .map((part) => part.text)
      .join('')
    // 回执可早于 HTTP 和产品历史落库；已受理的公开输入先按同一消息身份展示，不重复创建。
    if (
      (body.content.length || body.context.attachment_ids?.length) &&
      !history.value.messages.some((message) => message.message_id === body.client_message_id)
    )
      history.value.messages = mergeMessages(history.value.messages, [
        {
          message_id: body.client_message_id,
          [provisionalMessage]: true,
          message_version: 1,
          role: 'user',
          content: submittedText,
          input_id: body.client_message_id,
          root_input_id: body.client_message_id,
          ...(run ? { root_run_id: run } : {}),
          metadata: {
            content_parts: body.content,
            attachment_ids: body.context.attachment_ids ?? []
          }
        }
      ])
    acknowledgeDraft(body, submittedText)
  }
  /** 只清理已确认输入的精确资源身份，迟到回执不能删掉下一条草稿或未上传粘贴原文。 */
  function acknowledgeDraft(body: ChatInput, submittedText: string) {
    if (draft.value === submittedText) draft.value = ''
    const sentAttachments = new Set(body.context.attachment_ids ?? [])
    attachments.value = attachments.value.filter((file) => !sentAttachments.has(file.id))
    attachmentFiles.value = Object.fromEntries(
      Object.entries(attachmentFiles.value).filter(([id]) => !sentAttachments.has(id))
    )
    attachmentTexts.value = Object.fromEntries(
      Object.entries(attachmentTexts.value).filter(([id]) => !sentAttachments.has(id))
    )
    for (const id of sentAttachments) attachmentTextRanges.delete(id)
    images.value = images.value.filter(
      (image) =>
        !body.content.some((part) => part.type === 'image' && part.media_id === image.media_id)
    )
    references.value = references.value.filter(
      (reference) =>
        !body.references?.some(
          (source) => JSON.stringify(source) === JSON.stringify(reference.source)
        )
    )
  }
  /** 恢复只查询服务端持久事实，文本相同或其他执行完成都不能证明本次输入已受理。 */
  function reconcileInput(value: History) {
    const body = pendingInput.value
    if (!body || value.session_id !== body.session_id) return
    const input = body.client_message_id
    const confirmed =
      value.processes?.some((process) => process.input_id === input) ||
      value.messages.some(
        (message) =>
          message.role === 'user' &&
          (message.message_id === input || message.metadata?.client_message_id === input)
      )
    if (confirmed)
      confirmInput(
        body,
        true,
        value.processes?.find((process) => process.input_id === input)?.root_run_id
      )
  }
  function startObservation(api: Client, id: string, version: number) {
    streamAbort.abort()
    streamAbort = new AbortController()
    const signal = streamAbort.signal
    // 同一 work 的推送合并为单个读取；读取过程中再来事件时补读一次。
    const refreshing = new Set<string>(),
      dirty = new Set<string>()
    const checkedWaits = new Set<string>()
    function waitKey(run: string) {
      const activity = history.value.activities?.find((item) => item.render?.root_run_id === run)
      const render = activity?.render as
        { updated_sequence?: number; root_input_id?: string } | undefined
      const work = history.value.work_plans?.find(
        (item) => item.run_id === run && item.session_id === id
      )
      return {
        key: `${run}:${render?.updated_sequence ?? 0}:${work?.plan_revision ?? 0}`,
        render,
        work
      }
    }
    async function checkProgress(run: string, sourceSequence: number, rootInput: string) {
      if (!['waiting_hitl', 'suspended'].includes(runStates[run] ?? '')) return
      const waiting = waitKey(run)
      if (
        !waiting.work ||
        waiting.work.input_id !== rootInput ||
        waiting.render?.root_input_id !== rootInput ||
        sourceSequence <= (waiting.render.updated_sequence ?? 0) ||
        checkedWaits.has(waiting.key)
      )
        return
      checkedWaits.add(waiting.key)
      try {
        const value = await api.json<{ status: string }>(
          `/chat/runs/status?${query({ session_id: id, run_id: run })}`,
          'GET',
          undefined,
          signal
        )
        if (
          version !== selection ||
          signal.aborted ||
          waitKey(run).key !== waiting.key ||
          !['waiting_hitl', 'suspended'].includes(runStates[run] ?? '')
        )
          return
        rememberRun(run, value.status)
        if (value.status !== 'suspended' && value.status !== 'waiting_hitl')
          void refreshWork(waiting.work.work_id)
      } catch (cause) {
        if (version === selection && !signal.aborted) fail(cause)
      }
    }
    async function refreshWork(workId: string) {
      dirty.add(workId)
      if (refreshing.has(workId)) return
      refreshing.add(workId)
      try {
        while (dirty.delete(workId) && version === selection && !signal.aborted) {
          const work = await api.json<Work | null>(
            `/chat/work?${query({ session_id: id, work_id: workId })}`,
            'GET',
            undefined,
            signal
          )
          if (
            work &&
            work.session_id === id &&
            work.work_id === workId &&
            version === selection &&
            !signal.aborted
          )
            history.value.work_plans = mergeWorkPlans(history.value.work_plans ?? [], [work])
        }
      } catch (cause) {
        if (version === selection && !signal.aborted) fail(cause)
      } finally {
        refreshing.delete(workId)
      }
    }
    const receive = createProjection(
      id,
      history.value,
      (run, status) => {
        rememberRun(run, status)
        // 根 Run 与 Work 独立推进；终态事件不保证另发 work.updated，重新读取宿主事实。
        if (runIsTerminal(status)) {
          for (const work of history.value.work_plans ?? [])
            if (work.session_id === id && work.run_id === run) void refreshWork(work.work_id)
        }
      },
      () => {
        artifactsRevision.value++
        void refreshArtifacts()
      },
      (input, run) => {
        const body = pendingInput.value
        if (body?.session_id === id && body.client_message_id === input)
          confirmInput(body, true, run)
      },
      (work) => {
        void refreshWork(work)
      },
      (run, sourceSequence, rootInput) => {
        void checkProgress(run, sourceSequence, rootInput)
      }
    )
    void observe(
      api,
      id,
      history.value.event_cursor ?? '',
      signal,
      (event) => {
        if (version === selection && !signal.aborted) receive(event)
      },
      (value) => {
        if (version === selection && !signal.aborted) connection.value = value
      },
      async () => {
        const value = await readHistorySnapshot(api, id, version, signal)
        if (!value || version !== selection || signal.aborted) return ''
        applyHistory(value, true)
        return value.event_cursor ?? ''
      }
    ).catch((cause) => {
      if (version === selection && !signal.aborted) {
        connection.value = '连接已断开'
        fail(cause)
      }
    })
  }
  function applyHistory(value: History, merge = false) {
    const normalized = {
      ...value,
      processes: value.processes ?? [],
      messages: (value.messages ?? []).map((message) => ({
        ...message,
        ...(value.activities?.find((activity) => activity.message_id === message.message_id)?.card
          ? {
              card: value.activities.find((activity) => activity.message_id === message.message_id)!
                .card
            }
          : {})
      }))
    }
    if (merge) {
      Object.assign(history.value, normalized, {
        messages: mergeMessages(history.value.messages, normalized.messages),
        processes: mergeProcesses(history.value.processes, normalized.processes),
        work_plans: mergeWorkPlans(history.value.work_plans ?? [], normalized.work_plans ?? []),
        has_more: history.value.has_more,
        next_before_message_id: history.value.next_before_message_id
      })
    } else history.value = normalized
    reconcileInput(history.value)
    for (const activity of value.activities ?? []) {
      const run = activity.render?.root_run_id
      if (activity.node_type === 'agent_runtime' && run && !isMemberRun(run) && activity.status) {
        runStates[run] = activity.status
        runId.value = run
        runStatus.value = activity.status
      }
    }
    for (const item of history.value.processes)
      if (
        (item.status === 'running' || item.status === 'waiting') &&
        !isMemberRun(item.root_run_id || item.run_id) &&
        !runStates[item.root_run_id || item.run_id]
      )
        runStates[item.root_run_id || item.run_id] = 'running'
    // 重连可能先读到已持久的流式段，过程投影尚未出现；只借此发现待查询的 Run，不推断完成。
    for (const message of history.value.messages) {
      const run = message.root_run_id || message.run_id || message.metadata?.root_run_id
      if (
        run &&
        !isMemberRun(run) &&
        message.role === 'assistant' &&
        message.state === 'streaming' &&
        !runStates[run]
      )
        runStates[run] = 'running'
    }
    for (const pending of value.pending_interactions)
      rememberRun(pending.execution_id, 'waiting_hitl')
  }
  async function readHistorySnapshot(
    api: Client,
    id: string,
    version: number,
    signal: AbortSignal
  ) {
    let value: History | undefined
    // 仅等待历史发布完成，最多五次 GET；不重放提交，也不将普通故障当作发布延迟。
    for (let attempt = 0; attempt < 5; attempt++) {
      if (signal.aborted || version !== selection) return
      try {
        value = await api.json<History>(
          `/chat/history?${query({ session_id: id, limit: 30 })}`,
          'GET',
          undefined,
          signal
        )
        break
      } catch (cause) {
        if (signal.aborted || version !== selection) return
        if (
          !(cause instanceof RequestError) ||
          cause.status !== 503 ||
          cause.code !== 'TASK_OUTPUT_PUBLICATION_PENDING' ||
          attempt === 4
        ) {
          historyReadFailed.value = true
          connection.value = '历史读取未完成，请重新读取会话记录'
          throw cause
        }
        connection.value = '结果发布中，正在等待'
        await delay(1000 * 2 ** attempt, signal)
      }
    }
    return value
  }
  async function readHistory(id: string, version: number, merge = false) {
    const api = client.value!,
      signal = sessionAbort.signal,
      value = await readHistorySnapshot(api, id, version, signal)
    if (!value || signal.aborted || version !== selection) return
    applyHistory(value, merge && historyLoaded)
    historyLoaded = true
    historyReadFailed.value = false
    for (const run of Object.keys(runStates).filter((key) => !runIsTerminal(runStates[key]!))) {
      try {
        const state = await api.json<{ status: string }>(
          `/chat/runs/status?${query({ session_id: id, run_id: run })}`,
          'GET',
          undefined,
          sessionAbort.signal
        )
        if (version !== selection) return
        rememberRun(run, state.status)
      } catch (cause) {
        if (version === selection) fail(cause)
      }
    }
    if (signal.aborted || version !== selection) return
    startObservation(api, id, version)
    void refreshArtifacts()
    try {
      const value = await api.json<Limits>(
        `/chat/limits?${query({ session_id: id })}`,
        'GET',
        undefined,
        sessionAbort.signal
      )
      if (version === selection) limits.value = value
    } catch (cause) {
      if (version === selection) fail(cause)
    }
  }
  async function openSession(id: string) {
    clearSession()
    sessionId.value = id
    const version = selection
    loading.value = true
    try {
      await readHistory(id, version)
    } catch (cause) {
      if (version === selection) fail(cause)
    } finally {
      if (version === selection) loading.value = false
    }
  }
  async function ensureSession(): Promise<string> {
    if (sessionId.value) return sessionId.value
    if (createPromise) return createPromise
    const api = client.value,
      version = selection
    if (!api || !target.value) throw new Error('请先连接并选择智能体或团队。')
    createPromise = (async () => {
      const result = await api.json<{ session_id: string }>(
        '/chat/sessions',
        'POST',
        { end_user_id: userId.value, ...selectedTarget.value },
        sessionAbort.signal
      )
      if (version !== selection) throw new Error('会话已切换。')
      sessionId.value = result.session_id
      await readHistory(result.session_id, version)
      await refreshSessions()
      return result.session_id
    })()
    try {
      return await createPromise
    } finally {
      if (version === selection) createPromise = undefined
    }
  }
  async function loadEarlier() {
    if (loading.value || !history.value.has_more) return
    const api = client.value,
      id = sessionId.value,
      version = selection
    if (!api || !id) return
    loading.value = true
    try {
      const older = await api.json<History>(
        `/chat/history?${query({ session_id: id, limit: 30, before_message_id: history.value.next_before_message_id })}`,
        'GET',
        undefined,
        sessionAbort.signal
      )
      if (version !== selection) return
      history.value.messages = mergeMessages(history.value.messages, older.messages, true)
      history.value.processes = mergeProcesses(history.value.processes, older.processes ?? [])
      history.value.work_plans = mergeWorkPlans(
        history.value.work_plans ?? [],
        older.work_plans ?? []
      )
      history.value.has_more = older.has_more
      history.value.next_before_message_id = older.next_before_message_id
      history.value.history_retention = older.history_retention ?? history.value.history_retention
    } catch (cause) {
      if (version === selection) fail(cause)
    } finally {
      if (version === selection) loading.value = false
    }
  }
  /** HTTP 迟到时只补齐持久公开历史，不重新拥有提交状态、草稿或运行状态。 */
  async function refreshMessageHistory(id: string, version: number) {
    const fresh = await client.value!.json<History>(
      `/chat/history?${query({ session_id: id, limit: 30 })}`,
      'GET',
      undefined,
      sessionAbort.signal
    )
    if (version !== selection) return
    history.value.messages = mergeMessages(history.value.messages, fresh.messages)
    history.value.processes = mergeProcesses(history.value.processes, fresh.processes ?? [])
    history.value.work_plans = mergeWorkPlans(
      history.value.work_plans ?? [],
      fresh.work_plans ?? []
    )
    history.value.history_retention = fresh.history_retention ?? history.value.history_retention
  }
  async function submit(hitl?: Record<string, unknown>, retry = false) {
    if (
      historyReadFailed.value ||
      loading.value ||
      sending.value ||
      uploading.value ||
      cancelling.value ||
      (!retry && pendingInput.value) ||
      (retry && !pendingInput.value)
    )
      return
    if (!retry && !hitl && (active.value || inputMode.value !== 'message')) return
    if (
      !retry &&
      !hitl &&
      !draft.value.trim() &&
      !images.value.length &&
      !attachments.value.length &&
      !pastedTexts.value.length
    )
      return
    const version = selection
    const request = ++submissionRequest
    // 发送前截取草稿和资源身份，异步上传期间的编辑不能混入这次输入。
    const message =
      !retry && !hitl
        ? {
            text: draft.value,
            images: images.value.map((image) => image.media_id),
            attachments: attachments.value.map((file) => file.id),
            references: references.value.map((reference) =>
              structuredClone(toRaw(reference.source))
            ),
            pastedTexts: [...pastedTexts.value]
          }
        : undefined
    let body: ChatInput | undefined
    sending.value = true
    error.value = ''
    try {
      if (uploadStorageUnavailable.value)
        throw new Error('浏览器存储不可用，尚未上传；请恢复存储并刷新页面后重试。')
      const id = await ensureSession()
      if (version !== selection || request !== submissionRequest) return
      let uploaded: ConfirmedUpload[] = []
      if (message) {
        if (active.value || inputMode.value !== 'message') return
        if (message.pastedTexts.length) {
          const staged = new Map(
            message.pastedTexts.map(
              (item) =>
                [
                  new File([item.text], item.name, { type: 'text/plain;charset=utf-8' }),
                  item
                ] as const
            )
          )
          uploaded = await uploadFiles([...staged.keys()], version, staged)
          if (uploaded.length !== staged.size) return
        }
        if (
          version !== selection ||
          request !== submissionRequest ||
          active.value ||
          inputMode.value !== 'message' ||
          pendingInput.value
        )
          return
      }
      body = retry
        ? pendingInput.value!
        : {
            session_id: id,
            ...selectedTarget.value,
            client_message_id: crypto.randomUUID(),
            content: hitl
              ? []
              : [
                  ...(message!.text.trim() ? [{ type: 'text' as const, text: message!.text }] : []),
                  ...message!.images.map((mediaId) => ({
                    type: 'image' as const,
                    media_id: mediaId
                  }))
                ],
            context: {
              end_user_id: userId.value,
              attachment_ids: hitl
                ? []
                : [
                    ...message!.attachments,
                    ...uploaded.map((item) => (item.value as Attachment).id)
                  ]
            },
            ...(hitl ? { hitl_response: hitl } : {}),
            references: hitl ? [] : message!.references
          }
      // 在发送前冻结一次；传输结果未知时只允许用户显式重试相同身份和相同正文。
      pendingInput.value = body
      const result = await client.value!.json<ChatResult>(
        '/chat',
        'POST',
        body,
        sessionAbort.signal
      )
      if (version !== selection) return
      if (request !== submissionRequest) {
        await refreshMessageHistory(id, version)
        return
      }
      if (result.status === 'quota_exhausted') {
        pendingInput.value = undefined
        notice.value = result.card?.body ?? result.message ?? '本月额度已用尽。'
        return
      }
      if (result.execution_id)
        rememberRun(
          result.execution_id,
          runIsTerminal(result.status) || result.status === 'waiting_hitl'
            ? result.status
            : 'running'
        )
      if (result.collaboration) history.value.collaboration = result.collaboration
      confirmInput(body, false, result.execution_id)
      notice.value = result.message ?? ''
      await refreshMessageHistory(id, version)
      if (version !== selection || request !== submissionRequest) return
      await refreshSessions()
    } catch (cause) {
      if (version === selection && request === submissionRequest) {
        const rejected = pendingInput.value === body ? body : undefined
        if (
          rejected &&
          cause instanceof RequestError &&
          cause.code &&
          cause.code !== 'OK' &&
          rejectedInputStatuses.has(cause.status)
        ) {
          pendingInput.value = undefined
          // 等待身份已失效时读取服务端当前卡片，避免让用户再次提交旧版本。
          if (rejected.hitl_response && [409, 410, 422].includes(cause.status)) {
            try {
              await readHistory(rejected.session_id, version)
            } catch {
              /* 保留本次拒绝原因，仍可手动恢复观察。 */
            }
          }
        }
        if (version === selection && request === submissionRequest) fail(cause)
      }
    } finally {
      if (version === selection && request === submissionRequest) sending.value = false
    }
  }
  async function reconnect() {
    const api = client.value,
      id = sessionId.value,
      version = selection
    if (!api || !id) return
    error.value = ''
    loading.value = true
    streamAbort.abort()
    try {
      await readHistory(id, version, true)
    } catch (cause) {
      if (version === selection) fail(cause)
    } finally {
      if (version === selection) loading.value = false
    }
  }
  async function cancel(targetRun?: string) {
    const api = client.value,
      id = sessionId.value,
      run =
        targetRun ??
        Object.keys(runStates).find(
          (key) => !runIsTerminal(runStates[key]!) && runStates[key] !== 'waiting_hitl'
        ) ??
        runId.value,
      version = selection
    if (!api || !id || !run || cancelling.value) return
    cancelling.value = true
    cancellingRun.value = run
    error.value = ''
    const poll = async () => {
      if (version !== selection || runIsTerminal(runStates[run] ?? '')) return
      try {
        const result = await api.json<{ status: string }>(
          `/chat/runs/status?${query({ session_id: id, run_id: run })}`,
          'GET',
          undefined,
          sessionAbort.signal
        )
        if (version !== selection || runIsTerminal(runStates[run] ?? '')) return
        rememberRun(run, result.status)
        if (!runIsTerminal(runStates[run] ?? '')) cancelPoll = setTimeout(() => void poll(), 1500)
      } catch (cause) {
        if (version === selection && !runIsTerminal(runStates[run] ?? '')) {
          fail(cause)
          cancelPoll = setTimeout(() => void poll(), 5000)
        }
      }
    }
    try {
      const result = await api.json<{ status: string }>(
        '/chat/runs/cancel',
        'POST',
        { session_id: id, run_id: run },
        sessionAbort.signal
      )
      if (version !== selection || runIsTerminal(runStates[run] ?? '')) return
      rememberRun(run, result.status)
      if (!runIsTerminal(runStates[run] ?? '')) cancelPoll = setTimeout(() => void poll(), 1000)
    } catch (cause) {
      if (version === selection && !runIsTerminal(runStates[run] ?? '')) {
        if (
          cause instanceof RequestError &&
          cause.status === 403 &&
          cause.code === 'foundation.permission.denied'
        ) {
          cancelling.value = false
          cancellingRun.value = ''
          error.value = '取消请求未获授权，运行状态以实际结果为准。'
          return
        }
        fail(cause)
        void poll()
      }
    }
  }
  /** 上传只准备下一条消息草稿；运行中仍可添加资料，不会自动发送。 */
  async function upload(files: File[]): Promise<ConfirmedUpload[]> {
    if (locked.value || inputMode.value !== 'message') return []
    return uploadFiles(files, selection)
  }
  /** 按批次校验并逐项上传，首个失败即停止；已确认项转入草稿。 */
  async function uploadFiles(
    files: File[],
    version: number,
    staged = new Map<File, PastedTextAttachment>()
  ): Promise<ConfirmedUpload[]> {
    const confirmed: ConfirmedUpload[] = []
    uploading.value = true
    error.value = ''
    try {
      const id = await ensureSession()
      if (version !== selection || inputMode.value !== 'message') return confirmed
      const documents = files.filter((file) => !file.type.startsWith('image/'))
      const imageLimits = files.some((file) => file.type.startsWith('image/'))
        ? await client.value!.json<{ max_file_bytes: number; mime_types: string[] }>(
            '/chat/sessions/media/limits',
            'GET',
            undefined,
            sessionAbort.signal
          )
        : undefined
      if (version !== selection || inputMode.value !== 'message') return confirmed
      const cap = limits.value?.attachments
      if (documents.length && !cap) throw new Error('尚未取得附件限额，请恢复连接后上传。')
      if (documents.length)
        checkAttachmentCapacity(documents, new Set([...staged.values()].map((item) => item.id)))
      // 批次内的类型和大小在任何上传POST前统一校验，避免可预见的尾项错误留下部分上传。
      for (const file of files) {
        const image = file.type.startsWith('image/')
        if (
          image &&
          imageLimits &&
          (!imageLimits.mime_types.includes(file.type) ||
            file.size === 0 ||
            file.size > imageLimits.max_file_bytes)
        )
          throw new Error(`图片 ${file.name} 的类型或大小不符合限制。`)
        if (
          !image &&
          cap &&
          (file.size === 0 ||
            file.size > cap.max_file_bytes ||
            file.name.length > 128 ||
            Array.from(file.name).some(
              (char) =>
                char.charCodeAt(0) < 32 ||
                char.charCodeAt(0) === 127 ||
                char === '/' ||
                char === '\\'
            ) ||
            !cap.extensions.includes(file.name.split('.').pop()?.toLowerCase() ?? ''))
        )
          throw new Error(`附件 ${file.name} 的类型或大小不符合限制。`)
      }
      for (const file of files) {
        if (version !== selection || inputMode.value !== 'message') return confirmed
        const image = file.type.startsWith('image/')
        const body = new FormData()
        body.append('session_id', id)
        const pasted = staged.get(file)
        const uploadId = pasted?.id ?? crypto.randomUUID()
        body.append('upload_id', uploadId)
        body.append('file', file, file.name)
        const pending = {
          base: client.value!.base,
          clientRef: clientRef.value,
          session: id,
          uploadId,
          name: file.name,
          kind: image ? ('media' as const) : ('attachment' as const),
          pasted: Boolean(pasted)
        }
        if (
          unconfirmedUploads.value.some(
            (item) =>
              item.session === id &&
              item.base === pending.base &&
              item.clientRef === pending.clientRef &&
              (item.uploadId === uploadId ||
                (!item.receipt && item.name === file.name && item.kind === pending.kind))
          )
        )
          throw new Error(`文件 ${file.name} 的原上传结果尚未确认，请先核对原上传。`)
        if (pasted) retainedPastedUploads.set(uploadKey(pending), pasted)
        unconfirmedUploads.value.push(pending)
        if (uploadStorageUnavailable.value) {
          unconfirmedUploads.value = unconfirmedUploads.value.filter(
            (item) => uploadKey(item) !== uploadKey(pending)
          )
          retainedPastedUploads.delete(uploadKey(pending))
          throw new Error(
            `无法保存 ${file.name} 的上传标识，尚未上传；请恢复浏览器存储并刷新页面。`
          )
        }
        inFlightUploads.add(uploadKey(pending))
        let value: Media | Attachment
        try {
          value = await client.value!.json<Media | Attachment>(
            `/chat/sessions/${image ? 'media' : 'attachments'}/upload`,
            'POST',
            body,
            sessionAbort.signal
          )
        } catch (cause) {
          if (
            cause instanceof RequestError &&
            cause.code ===
              (image ? 'SESSION_MEDIA_UPLOAD_REJECTED' : 'SESSION_ATTACHMENT_UPLOAD_REJECTED')
          ) {
            const previous = unconfirmedUploads.value
            unconfirmedUploads.value = previous.filter(
              (item) => uploadKey(item) !== uploadKey(pending)
            )
            if (uploadStorageUnavailable.value) unconfirmedUploads.value = previous
            else retainedPastedUploads.delete(uploadKey(pending))
            throw cause
          }
          throw new UploadUnconfirmedError(
            `文件 ${file.name} 的上传结果尚未确认，后续文件未上传；当前无法自动确认。`,
            { cause }
          )
        } finally {
          inFlightUploads.delete(uploadKey(pending))
        }
        if (image) {
          const media = value as Media | null
          if (
            !media ||
            typeof media.media_id !== 'string' ||
            !media.media_id ||
            typeof media.mime_type !== 'string' ||
            !media.mime_type ||
            !Number.isSafeInteger(media.byte_length) ||
            media.byte_length <= 0 ||
            !Number.isSafeInteger(media.width) ||
            media.width <= 0 ||
            !Number.isSafeInteger(media.height) ||
            media.height <= 0
          )
            throw new UploadUnconfirmedError(`图片 ${file.name} 的上传回执不完整，请核对原上传。`)
        } else {
          const attachment = value as Attachment | null
          if (
            !attachment ||
            attachment.id !== uploadId ||
            attachment.name !== file.name ||
            typeof attachment.input_path !== 'string' ||
            !attachment.input_path ||
            !Number.isSafeInteger(attachment.size) ||
            attachment.size <= 0 ||
            typeof attachment.sha256 !== 'string' ||
            !attachment.sha256 ||
            typeof attachment.expires_at !== 'string' ||
            !attachment.expires_at
          )
            throw new UploadUnconfirmedError(`附件 ${file.name} 的上传回执不完整，请核对原上传。`)
        }
        if (version !== selection) {
          const record = unconfirmedUploads.value.find(
            (item) => uploadKey(item) === uploadKey(pending)
          )
          if (record) record.receipt = value
          return confirmed
        }
        unconfirmedUploads.value = unconfirmedUploads.value.filter(
          (item) => uploadKey(item) !== uploadKey(pending)
        )
        if (image) images.value.push(value as Media)
        else {
          const attachment = value as Attachment
          if (!attachments.value.some((item) => item.id === attachment.id))
            attachments.value.push(attachment)
          attachmentFiles.value = { ...attachmentFiles.value, [attachment.id]: file }
          if (pasted) {
            pastedTexts.value = pastedTexts.value.filter((item) => item.id !== pasted.id)
            attachmentTexts.value = { ...attachmentTexts.value, [attachment.id]: pasted.text }
            attachmentTextRanges.set(attachment.id, {
              start: pasted.selectionStart,
              end: pasted.selectionEnd,
              selectedText: pasted.selectedText,
              sourceDraft: pasted.sourceDraft
            })
          }
        }
        retainedPastedUploads.delete(uploadKey(pending))
        confirmed.push({ file, value })
      }
    } catch (cause) {
      if (version === selection && !(cause instanceof UploadUnconfirmedError)) fail(cause)
    } finally {
      if (version === selection) uploading.value = false
    }
    return confirmed
  }
  async function claimConfirmedUpload(uploadId: string) {
    if (locked.value || inputMode.value !== 'message') return
    const record = currentConfirmedUploads.value.find((item) => item.uploadId === uploadId)
    const api = client.value,
      id = sessionId.value,
      version = selection
    if (!record?.receipt || !api || !id) return
    try {
      if (record.kind === 'media') {
        const media = record.receipt as Media
        if (!images.value.some((item) => item.media_id === media.media_id)) images.value.push(media)
      } else {
        const items = await api.json<Attachment[]>(
          `/chat/sessions/attachments?${query({ session_id: id })}`
        )
        if (version !== selection || locked.value || inputMode.value !== 'message') return
        const attachment = items.find((item) => item.id === uploadId)
        if (!attachment) {
          notice.value = `附件 ${record.name} 已不可用，无法加入草稿。`
          return
        }
        if (!attachments.value.some((item) => item.id === attachment.id)) {
          if (!limits.value?.attachments) throw new Error('尚未取得附件限额，请恢复连接后领取。')
          checkAttachmentCapacity([attachment], new Set([uploadId]))
          attachments.value.push(attachment)
        }
        const pasted = retainedPastedUploads.get(uploadKey(record))
        if (pasted) {
          pastedTexts.value = pastedTexts.value.filter((item) => item.id !== uploadId)
          attachmentTexts.value = { ...attachmentTexts.value, [attachment.id]: pasted.text }
          attachmentTextRanges.set(attachment.id, {
            start: pasted.selectionStart,
            end: pasted.selectionEnd,
            selectedText: pasted.selectedText,
            sourceDraft: pasted.sourceDraft
          })
        }
      }
      retainedPastedUploads.delete(uploadKey(record))
      unconfirmedUploads.value = unconfirmedUploads.value.filter((item) => item !== record)
      notice.value =
        record.pasted && !attachmentTexts.value[uploadId]
          ? `已将 ${record.name} 加入当前草稿；刷新后原文恢复不可用。`
          : `已将 ${record.name} 加入当前草稿。`
    } catch (cause) {
      if (version === selection) fail(cause)
    }
  }
  /** 只读核对原上传；缺失不能证明原上传未完成。 */
  async function checkUnconfirmedUpload(uploadId: string) {
    const api = client.value,
      id = sessionId.value,
      version = selection
    const pending = currentUnconfirmedUploads.value.find((item) => item.uploadId === uploadId)
    if (!api || !id || !pending || isUploadInFlight(pending)) return
    try {
      if (pending.kind === 'media') {
        const receipt = await api.json<{ status: 'FOUND' | 'UNKNOWN'; media: Media | null }>(
          `/chat/sessions/media/upload-receipt?${query({ session_id: id, upload_id: uploadId })}`
        )
        if (version !== selection) return
        if (
          receipt.status !== 'FOUND' ||
          !receipt.media?.media_id ||
          !receipt.media.mime_type ||
          !Number.isSafeInteger(receipt.media.byte_length) ||
          receipt.media.byte_length <= 0 ||
          !Number.isSafeInteger(receipt.media.width) ||
          receipt.media.width <= 0 ||
          !Number.isSafeInteger(receipt.media.height) ||
          receipt.media.height <= 0
        ) {
          notice.value = `未查到图片 ${pending.name}，原上传结果仍未确认。`
          return
        }
        pending.receipt = receipt.media
        if (!locked.value && inputMode.value === 'message') await claimConfirmedUpload(uploadId)
        else notice.value = `已确认图片 ${pending.name}，可加入当前草稿。`
        return
      }
      const items = await api.json<Attachment[]>(
        `/chat/sessions/attachments?${query({ session_id: id })}`
      )
      if (version !== selection) return
      const found = items.find((item) => item.id === uploadId)
      if (!found) {
        notice.value = `未查到附件 ${pending.name}，原上传结果仍未确认。`
        return
      }
      if (pending.pastedWithdrawn) {
        pending.receipt = found
        notice.value = `已确认附件 ${found.name}；原粘贴项已移除，未自动加入草稿。`
        return
      }
      if (locked.value || inputMode.value !== 'message') {
        pending.receipt = found
        notice.value = `已确认附件 ${found.name}，可加入当前草稿。`
        return
      }
      if (!attachments.value.some((item) => item.id === found.id)) {
        if (!limits.value?.attachments) throw new Error('尚未取得附件限额，请恢复连接后核对。')
        checkAttachmentCapacity([found], new Set([uploadId]))
        attachments.value.push(found)
      }
      const pasted =
        pastedTexts.value.find((item) => item.id === uploadId) ??
        retainedPastedUploads.get(uploadKey(pending))
      if (pasted) {
        pastedTexts.value = pastedTexts.value.filter((item) => item.id !== uploadId)
        attachmentTexts.value = { ...attachmentTexts.value, [found.id]: pasted.text }
        attachmentTextRanges.set(found.id, {
          start: pasted.selectionStart,
          end: pasted.selectionEnd,
          selectedText: pasted.selectedText,
          sourceDraft: pasted.sourceDraft
        })
      }
      retainedPastedUploads.delete(uploadKey(pending))
      unconfirmedUploads.value = unconfirmedUploads.value.filter((item) => item !== pending)
      notice.value =
        pending.pasted && !pasted
          ? `已确认附件 ${found.name} 并加入草稿；刷新后原文恢复不可用。`
          : `已确认附件 ${found.name}，并加入当前草稿。`
    } catch (cause) {
      if (version === selection) fail(cause)
    }
  }
  function reference(value: Reference) {
    if (pendingInput.value) {
      notice.value = '请先恢复观察或重试原请求，再添加引用。'
      return
    }
    const max = limits.value?.references.max_items ?? 64
    if (references.value.length >= max) {
      error.value = '引用数量已达上限。'
      return
    }
    if (
      !references.value.some((item) => JSON.stringify(item.source) === JSON.stringify(value.source))
    ) {
      references.value.push(value)
      notice.value = '已加入下一条消息的引用，请确认后发送。'
    } else notice.value = '该内容已在引用列表中。'
  }
  onBeforeUnmount(disconnect)
  return {
    client,
    agents,
    teams,
    sessions,
    target,
    sessionId,
    userId,
    clientRef,
    history,
    error,
    notice,
    currentUnconfirmedUploads,
    currentConfirmedUploads,
    claimConfirmedUpload,
    isUploadInFlight,
    checkUnconfirmedUpload,
    uploadStorageUnavailable,
    connection,
    connecting,
    loading,
    sessionsLoading,
    sessionsError,
    historyReadFailed,
    sending,
    uploading,
    cancelling,
    draft,
    supplementDraft,
    inputMode,
    composerDraft,
    steering,
    steeringSending,
    steeringLoading,
    steeringError,
    pendingSteering,
    canSteer,
    setInputMode,
    submitSupplement,
    refreshSteering,
    artifacts,
    artifactsRevision,
    artifactsLoading,
    artifactsError,
    refreshArtifacts,
    runStates,
    runId,
    runStatus,
    updateWork,
    limits,
    attachments,
    pastedTexts,
    attachmentFiles,
    attachmentTexts,
    stagePastedText,
    removePastedText,
    restorePastedText,
    removeAttachment,
    restoreAttachmentText,
    images,
    references,
    pendingInput,
    active,
    locked,
    targetName,
    selectedTarget,
    connect,
    disconnect,
    changeTarget,
    openSession,
    newSession: clearSession,
    refreshSessions,
    ensureSession,
    loadEarlier,
    submit,
    reconnect,
    cancel,
    upload,
    reference,
    fail
  }
}
