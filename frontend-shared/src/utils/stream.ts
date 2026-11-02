/**
 * 会话提交与只读观察分开；恢复和重连只执行 GET，不重提用户输入。
 */
import { readApiResponseError } from './http'
import type { ChatInputReference } from '../components/chatReferences'
import type { ChatCollaborationState, ChatHitlResponse } from '../types/chatCollaboration'
export const CHAT_EVENTS = {
  CONNECTED: 'connected',
  COLLABORATION_UPDATED: 'collaboration.updated',
  RUN_STARTED: 'run.started',
  RUN_RESUMED: 'run.resumed',
  PROCESS_STARTED: 'process.started',
  PROCESS_DELTA: 'process.delta',
  PROCESS_FINISHED: 'process.finished',
  MESSAGE_DELTA: 'message.delta',
  MESSAGE_CARD: 'message.card',
  MESSAGE_CARD_UPDATE: 'message.card.update',
  HITL_REQUIRED: 'hitl.required',
  EXECUTION_COMPLETED: 'execution.completed',
  PING: 'ping',
  ERROR: 'error'
} as const
export const CHAT_CONNECTION_STATES = {
  CONNECTED: 'connected',
  RECONNECTING: 'reconnecting',
  UNAVAILABLE: 'unavailable'
} as const
export const CHAT_OBSERVATION_ERRORS = {
  CURSOR_EXPIRED: 'EVENT_CURSOR_EXPIRED',
  HISTORY_UNSUPPORTED: 'CHAT_HISTORY_UNSUPPORTED'
} as const
const RECONNECT_DELAY_MS = 500
const MAX_RECONNECT_DELAY_MS = 30_000
const MAX_RECENT_EVENT_IDS = 1_024
export const CHAT_TRANSPORT_SILENCE_MS = 45_000

export interface StreamEvent {
  event: string
  data: Record<string, unknown>
  id?: string
}
export interface ChatStreamRequest {
  message?: string
  content?: import('../components/chatMedia').ChatContent[]
  session_id?: string
  agent_id?: string
  team_id?: string
  workflow_id?: string
  scope_id?: string
  scope_type?: string
  locale?: string
  hitl_response?: ChatHitlResponse
  client_message_id?: string
  context?: { end_user_id?: string | number; task_id?: string; attachment_ids?: string[] }
  references?: ChatInputReference[]
}
export interface ChatResult {
  status: string
  session_id: string
  execution_id?: string | null
  input_id?: string | null
  replayed?: boolean
  collaboration?: (ChatCollaborationState & { command_completed?: boolean }) | null
  card?: Record<string, unknown> | null
  message?: string | null
}
export interface ChatSubmitOptions {
  signal?: AbortSignal
  onSession?: (sessionId: string) => void
}
export interface ChatSessionResult {
  session_id: string
}
export interface ChatStreamFactoryOptions {
  createSession: (
    body: Pick<ChatStreamRequest, 'agent_id' | 'team_id' | 'scope_id' | 'scope_type'> & {
      end_user_id: string | number
    },
    signal?: AbortSignal
  ) => Promise<ChatSessionResult>
  submit: (body: ChatStreamRequest, signal?: AbortSignal) => Promise<ChatResult>
  fetchEvents: (
    sessionId: string,
    lastEventId: string | undefined,
    signal?: AbortSignal
  ) => Promise<Response>
  fetchHistory: (sessionId: string, signal?: AbortSignal) => Promise<unknown>
}
export interface ChatObservationHandlers {
  onEvent: (event: StreamEvent) => void
  onHistory?: (sessionId: string, history: unknown) => void | Promise<void>
  onCursor?: (eventId: string | null) => void
  onConnection?: (
    state: (typeof CHAT_CONNECTION_STATES)[keyof typeof CHAT_CONNECTION_STATES]
  ) => void
}
export interface ChatObserveRequest {
  sessionId: string
  afterEventId?: string | null
  signal?: AbortSignal
}
function realId(value: unknown): value is string {
  return typeof value === 'string' && value !== '' && value !== 'new' && value !== 'pending'
}
function waitReconnect(delay: number, signal?: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason)
      return
    }
    const abort = () => {
      clearTimeout(timer)
      reject(signal?.reason)
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort)
      resolve()
    }, delay)
    signal?.addEventListener('abort', abort, { once: true })
  })
}
/** Retry-After 可为秒数或 HTTP 日期；请求取消仍能立即中断等待。 */
function retryAfter(response: Response): number {
  const value = response.headers.get('Retry-After')
  if (!value) return 0
  const duration = /^\d+(?:\.\d+)?$/.test(value.trim())
    ? Number(value) * 1000
    : Date.parse(value) - Date.now()
  return Number.isFinite(duration) ? Math.min(2_147_483_647, Math.max(0, duration)) : 0
}
function sessionSequence(value: unknown): bigint | undefined {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return BigInt(value)
  if (typeof value === 'string' && /^\d+$/.test(value) && value.length <= 20) return BigInt(value)
  return undefined
}
export class SsePayloadError extends Error {
  constructor() {
    super('事件响应格式无效，请保留原游标并重新核对。')
  }
}
/** 解析一个 GET 响应。订阅归属固定，事件不能切换当前会话。 */
export async function consumeSse(
  response: Response,
  dispatch: (event: StreamEvent) => void,
  signal?: AbortSignal,
  touch: () => void = () => {},
  strictJson = false
) {
  if (!response.body) throw new Error('读取回复失败，请重新连接。')
  const reader = response.body.getReader(),
    decoder = new TextDecoder()
  let buffer = '',
    eventName = 'message',
    dataLines: string[] = [],
    eventId: string | undefined,
    streamFinished = false
  const send = () => {
    if (dataLines.length) {
      const dataText = dataLines.join('\n')
      let data: Record<string, unknown>
      try {
        data = JSON.parse(dataText)
        if (strictJson && (!data || typeof data !== 'object' || Array.isArray(data)))
          throw new SsePayloadError()
      } catch {
        if (strictJson) throw new SsePayloadError()
        data = {}
      }
      dispatch({ event: eventName, data, id: eventId })
    }
    eventName = 'message'
    dataLines = []
    eventId = undefined
  }
  const abort = () => {
    void reader.cancel().catch(() => undefined)
  }
  signal?.addEventListener('abort', abort, { once: true })
  try {
    while (!signal?.aborted) {
      const { done, value } = await reader.read()
      if (signal?.aborted) break
      if (value?.byteLength) touch()
      buffer += decoder.decode(value, { stream: !done })
      const lines = buffer.split(/\r?\n/)
      buffer = strictJson ? (lines.pop() ?? '') : done ? '' : (lines.pop() ?? '')
      for (const line of lines) {
        if (signal?.aborted) break
        if (!line) send()
        else if (line.startsWith('event:')) eventName = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
        else if (line.startsWith('id:')) eventId = line.slice(3).trim()
      }
      if (done) {
        if (!signal?.aborted && strictJson && (buffer || dataLines.length))
          throw new SsePayloadError()
        if (!signal?.aborted && !strictJson) send()
        streamFinished = true
        break
      }
    }
  } finally {
    signal?.removeEventListener('abort', abort)
    if (signal?.aborted || !streamFinished) await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
export function createChatTransport(options: ChatStreamFactoryOptions) {
  return {
    async submit(payload: ChatStreamRequest, request: ChatSubmitOptions = {}): Promise<ChatResult> {
      const { message, ...wirePayload } = payload
      const body = {
        ...wirePayload,
        content: payload.content ?? [{ type: 'text' as const, text: message ?? '' }],
        client_message_id: payload.client_message_id ?? crypto.randomUUID()
      }
      if (!realId(body.session_id)) {
        const endUserId = body.context?.end_user_id
        if (
          !(typeof endUserId === 'string' && /^[1-9]\d*$/.test(endUserId)) &&
          !(typeof endUserId === 'number' && Number.isSafeInteger(endUserId) && endUserId > 0)
        )
          throw new Error('创建会话缺少合法用户身份。')
        const created = await options.createSession(
          {
            end_user_id: endUserId as string | number,
            ...(body.agent_id ? { agent_id: body.agent_id } : {}),
            ...(body.team_id ? { team_id: body.team_id } : {}),
            ...(body.scope_id ? { scope_id: body.scope_id } : {}),
            ...(body.scope_type ? { scope_type: body.scope_type } : {})
          },
          request.signal
        )
        if (!realId(created.session_id)) throw new Error('创建会话未返回有效身份。')
        body.session_id = created.session_id
        request.onSession?.(created.session_id)
      }
      const result = await options.submit(body, request.signal)
      if (!realId(result.session_id)) throw new Error('提交对话未返回有效会话。')
      request.onSession?.(result.session_id)
      return result
    },
    async observeSession(
      request: ChatObserveRequest,
      handlers: ChatObservationHandlers
    ): Promise<void> {
      if (!realId(request.sessionId)) throw new Error('无法观察未创建的会话。')
      let cursor = request.afterEventId ?? undefined
      const seen = new Set<string>()
      let latestSequence: bigint | undefined,
        failures = 0
      while (!request.signal?.aborted) {
        // 每次 GET 有独立静默期限；心跳字节只延长传输连接，不伪造业务进展。
        const attempt = new AbortController()
        const abort = () => attempt.abort(request.signal?.reason)
        request.signal?.addEventListener('abort', abort, { once: true })
        let silence: ReturnType<typeof setTimeout>
        let serverDelay = 0
        const touch = () => {
          clearTimeout(silence)
          silence = setTimeout(() => attempt.abort(), CHAT_TRANSPORT_SILENCE_MS)
        }
        touch()
        try {
          const response = await options.fetchEvents(request.sessionId, cursor, attempt.signal)
          if (attempt.signal.aborted) throw new Error('chat_observation_aborted')
          serverDelay = retryAfter(response)
          if (response.status === 409) {
            const conflict = await readApiResponseError(response)
            if (conflict.code !== CHAT_OBSERVATION_ERRORS.CURSOR_EXPIRED) throw conflict
            const snapshot = await options.fetchHistory(request.sessionId, attempt.signal)
            if (attempt.signal.aborted) throw new Error('chat_observation_aborted')
            await handlers.onHistory?.(request.sessionId, snapshot)
            const next =
              snapshot && typeof snapshot === 'object'
                ? (snapshot as Record<string, unknown>).event_cursor
                : undefined
            cursor = typeof next === 'string' ? next : undefined
            handlers.onCursor?.(cursor ?? null)
            failures = 0
          } else {
            if (!response.ok) throw await readApiResponseError(response)
            handlers.onConnection?.('connected')
            await consumeSse(
              response,
              (event) => {
                if (
                  typeof event.data.session_id === 'string' &&
                  event.data.session_id !== request.sessionId
                )
                  return
                const sequence = sessionSequence(event.data.session_sequence)
                // 先拒绝重放的旧帧，再推进游标；UUID 形式的 event id 不参与顺序猜测。
                if (
                  sequence !== undefined &&
                  latestSequence !== undefined &&
                  sequence <= latestSequence
                )
                  return
                if (event.id && seen.has(event.id)) return
                if (sequence !== undefined) latestSequence = sequence
                if (event.id) {
                  seen.add(event.id)
                  if (seen.size > MAX_RECENT_EVENT_IDS) seen.delete(seen.values().next().value!)
                  cursor = event.id
                  handlers.onCursor?.(event.id)
                }
                failures = 0
                handlers.onEvent(event)
              },
              attempt.signal,
              () => {
                failures = 0
                touch()
              }
            )
          }
        } catch (error) {
          if (request.signal?.aborted) return
          const failure = error as { status?: number; code?: string }
          if (
            failure.status === 401 ||
            failure.status === 403 ||
            failure.status === 404 ||
            failure.code === CHAT_OBSERVATION_ERRORS.HISTORY_UNSUPPORTED
          ) {
            handlers.onConnection?.('unavailable')
            throw error
          }
        } finally {
          clearTimeout(silence!)
          request.signal?.removeEventListener('abort', abort)
          attempt.abort()
        }
        if (!request.signal?.aborted) {
          handlers.onConnection?.('reconnecting')
          const ceiling = Math.min(
            MAX_RECONNECT_DELAY_MS,
            RECONNECT_DELAY_MS * 2 ** Math.min(failures++, 16)
          )
          const delay = Math.max(serverDelay, Math.round(ceiling * (0.5 + Math.random() * 0.5)))
          try {
            await waitReconnect(delay, request.signal)
          } catch {
            return
          }
        }
      }
    }
  }
}
