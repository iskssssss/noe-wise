export interface ModelSettings {
  providerId: 'deepseek' | 'custom'
  baseUrl: string
  model: string
  hasApiKey: boolean
  keySaved: boolean
  configurationSaved: boolean
  encryptionAvailable: boolean
  outputTokens?: number
}
export interface ModelInput {
  providerId: ModelSettings['providerId']
  baseUrl: string
  model: string
  apiKey: string
  outputTokens?: number
}
export interface Session {
  id: string
  title: string
  workspace: string
  createdAt?: string
}
export interface Run {
  id: string
  status: string
  phase?: string
  active?: boolean
  createdAt?: string
  updatedAt?: string
  recoveryBlocked?: boolean
  recoveryReason?: string
}
export interface SessionEvent {
  sequence: number
  type: string
  runId?: string
  text?: string
  data?: unknown
  toolName?: string
  arguments?: unknown
  result?: unknown
  fieldsTruncated?: string[]
}
export interface Confirmation {
  runId: string
  waitingId: string
  command: string
  workspace: string
}
export interface SessionState {
  session: Session
  messages: { role: string; text: string; runId?: string }[]
  runs: Run[]
  events: SessionEvent[]
  pendingConfirmations: Confirmation[]
  projectionTruncated?: boolean
}
export interface HostStatus {
  status: 'starting' | 'ready' | 'failed'
  message: string
  setupError?: string
  clientVersion?: string
}
export interface DesktopEvent {
  attemptId?: string
  delta?: string
  event: string
  sessionId?: string
  runId?: string
  data?: unknown
}
type Reply<T> = { ok: true; value: T } | { ok: false; error: { message: string } }
interface DesktopApi {
  getStatus(): Promise<Reply<HostStatus>>
  getModel(): Promise<Reply<ModelSettings>>
  saveModel(input: ModelInput): Promise<Reply<ModelSettings>>
  testModel(): Promise<Reply<unknown>>
  openExternal(url: string): Promise<Reply<void>>
  chooseWorkspace(): Promise<Reply<string | null>>
  listSessions(): Promise<Reply<Session[]>>
  createSession(input: Session): Promise<Reply<Session>>
  getSession(sessionId: string): Promise<Reply<SessionState>>
  submitTask(input: {
    sessionId: string
    text: string
    inputId: string
  }): Promise<Reply<{ runId: string; created: boolean }>>
  cancelTask(input: { runId: string; inputId: string }): Promise<Reply<unknown>>
  confirmTool(input: {
    runId: string
    waitingId: string
    approved: boolean
    inputId: string
  }): Promise<Reply<unknown>>
  onEvent(listener: (event: DesktopEvent) => void): () => void
}
declare global {
  interface Window {
    desktop?: DesktopApi
  }
}

export async function unwrap<T>(reply: Promise<Reply<T>>): Promise<T> {
  const result = await reply
  if (!result.ok) throw new Error(result.error.message)
  return result.value
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

export function readable(value: unknown): string {
  if (typeof value === 'string') return value
  return value === undefined || value === null ? '' : JSON.stringify(value, null, 2)
}

const STATUS_NAMES: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUSPENDED: '已挂起',
  RESUMING: '恢复中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  TERMINATED: '已终止'
}
export function runLabel(run: Run): string {
  if (run.recoveryBlocked) return '历史任务仅可查看'
  if (run.phase === 'UNCERTAIN') return '结果未知，需核对'
  if (run.phase === 'WAITING') return '等待确认'
  return STATUS_NAMES[run.status.toUpperCase()] ?? run.status
}
export function isStopping(run: Run): boolean {
  return run.active === true && ['COMPLETED', 'FAILED', 'CANCELLED', 'TERMINATED'].includes(run.status.toUpperCase())
}
export function isActive(run: Run): boolean {
  return (
    !run.recoveryBlocked &&
    ['QUEUED', 'RUNNING', 'SUSPENDED', 'RESUMING'].includes(run.status.toUpperCase())
  )
}
