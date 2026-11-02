import type { RequestOptions } from '@skillforge/shared/utils/http'

export interface ConversationTask {
  taskId: string
  tenantId: { value: string }
  endUserId: { value: string }
  agentId: { value: string }
  revision: number
  status: 'OPEN' | 'WAITING_USER' | 'COMPLETED' | 'CANCELLED'
  title: string
  summary: string
  state: { schemaId: string; canonicalJson: string }
  sources: unknown[]
  updatedAt: string
}
export type TaskOperation = 'correct' | 'complete' | 'cancel' | 'reopen'

/** HTTP DTO 使用宿主的蛇形字段；页面模型统一在边界转换。 */
export interface ConversationTaskWire extends Omit<
  ConversationTask,
  'taskId' | 'tenantId' | 'endUserId' | 'agentId' | 'updatedAt' | 'state'
> {
  task_id: string
  tenant_id: { value: string }
  end_user_id: { value: string }
  agent_id: { value: string }
  updated_at: string
  state: { schema_id: string; canonical_json: string }
}

function fromWire(task: ConversationTaskWire): ConversationTask {
  return {
    taskId: task.task_id,
    tenantId: task.tenant_id,
    endUserId: task.end_user_id,
    agentId: task.agent_id,
    revision: task.revision,
    status: task.status,
    title: task.title,
    summary: task.summary,
    state: { schemaId: task.state.schema_id, canonicalJson: task.state.canonical_json },
    sources: task.sources,
    updatedAt: task.updated_at
  }
}

export function createConversationTaskApi(http: {
  get: <T>(url: string, options?: RequestOptions) => Promise<T>
  post: <T>(url: string, body: unknown, options?: RequestOptions) => Promise<T>
}) {
  const root = '/conversation-tasks'
  const params = (sessionId: string, values: Record<string, string>) => {
    if (!sessionId.trim()) throw new Error('请先选择会话')
    return new URLSearchParams({ session_id: sessionId, ...values }).toString()
  }
  return {
    list: (sessionId: string, query = '', includeClosed = false, offset = 0) =>
      http
        .get<ConversationTaskWire[]>(
          `${root}/list?${params(sessionId, { query, include_closed: String(includeClosed), offset: String(offset), limit: '30' })}`
        )
        .then((tasks) => tasks.map(fromWire)),
    detail: (sessionId: string, taskId: string) =>
      http
        .get<ConversationTaskWire>(`${root}/detail?${params(sessionId, { task_id: taskId })}`)
        .then(fromWire),
    receipt: (sessionId: string, taskId: string, operationId: string) =>
      http
        .get<{ status: 'SUCCEEDED' | 'UNKNOWN'; task: ConversationTaskWire | null }>(
          `${root}/receipt?${params(sessionId, { task_id: taskId, operation_id: operationId })}`
        )
        .then((result) => ({ ...result, task: result.task ? fromWire(result.task) : null })),
    select: (sessionId: string, task: ConversationTask, operationId: string) =>
      http
        .post<ConversationTaskWire>(`${root}/select`, {
          session_id: sessionId,
          task_id: task.taskId,
          expected_revision: task.revision,
          operation_id: operationId
        })
        .then(fromWire),
    update: (
      sessionId: string,
      task: ConversationTask,
      operation: TaskOperation,
      operationId: string,
      text?: string
    ) =>
      http
        .post<ConversationTaskWire>(`${root}/update`, {
          session_id: sessionId,
          task_id: task.taskId,
          expected_revision: task.revision,
          operation,
          operation_id: operationId,
          ...(text === undefined ? {} : { text })
        })
        .then(fromWire)
  }
}
