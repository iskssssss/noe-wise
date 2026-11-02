import { describe, expect, it, vi } from 'vitest'
import {
  createConversationTaskApi,
  type ConversationTask,
  type ConversationTaskWire
} from './conversationTasks'

const wire: ConversationTaskWire = {
  task_id: 'task/a',
  tenant_id: { value: 'tenant' },
  end_user_id: { value: '9007199254740993' },
  agent_id: { value: 'agent' },
  revision: 7,
  status: 'WAITING_USER',
  title: '任务',
  summary: '进展',
  state: {
    schema_id: 'state.v1',
    canonical_json: '{"facts":{"时间":{"value":"周五"}},"pending":["地点"]}'
  },
  sources: [],
  updated_at: '2026-09-22T00:00:00Z'
}

describe('任务续接 API', () => {
  it('没有授权来源会话时不查询任务，并保留分页及状态筛选', async () => {
    const get = vi.fn().mockResolvedValue([]),
      post = vi.fn()
    const api = createConversationTaskApi({ get, post })
    expect(() => api.list('')).toThrow('请先选择会话')
    expect(get).not.toHaveBeenCalled()
    await api.list('session/a', '召回', true, 30)
    const query = new URL(String(get.mock.calls[0]![0]), 'https://example.test').searchParams
    expect(Object.fromEntries(query)).toEqual({
      session_id: 'session/a',
      query: '召回',
      include_closed: 'true',
      offset: '30',
      limit: '30'
    })
  })

  it('选择与纠正携带任务修订和调用方持有的操作标识', async () => {
    const task = { taskId: 'task/a', revision: 7 } as ConversationTask
    const get = vi.fn(),
      post = vi.fn().mockResolvedValue(wire)
    const api = createConversationTaskApi({ get, post })
    await api.select('session', task, 'select-operation')
    await api.update('session', task, 'correct', 'correct-operation', '改为周五')
    expect(post.mock.calls).toEqual([
      [
        '/conversation-tasks/select',
        {
          session_id: 'session',
          task_id: 'task/a',
          expected_revision: 7,
          operation_id: 'select-operation'
        }
      ],
      [
        '/conversation-tasks/update',
        {
          session_id: 'session',
          task_id: 'task/a',
          expected_revision: 7,
          operation: 'correct',
          operation_id: 'correct-operation',
          text: '改为周五'
        }
      ]
    ])
  })

  it('只读回执保留原身份，未知与成功分别转换', async () => {
    const get = vi
      .fn()
      .mockResolvedValueOnce({ status: 'UNKNOWN', task: null })
      .mockResolvedValueOnce({ status: 'SUCCEEDED', task: wire })
    const post = vi.fn()
    const api = createConversationTaskApi({ get, post })
    expect(await api.receipt('session/a', 'task/a', 'operation/a')).toEqual({
      status: 'UNKNOWN',
      task: null
    })
    expect((await api.receipt('session/a', 'task/a', 'operation/a')).task?.taskId).toBe('task/a')
    expect(get.mock.calls[0]![0]).toBe(
      '/conversation-tasks/receipt?session_id=session%2Fa&task_id=task%2Fa&operation_id=operation%2Fa'
    )
    expect(post).not.toHaveBeenCalled()
  })

  it('所有接口转换真实蛇形响应并保留用户 ID 精度及任务状态', async () => {
    const get = vi.fn().mockResolvedValueOnce([wire]).mockResolvedValueOnce(wire)
    const post = vi.fn().mockResolvedValue(wire)
    const api = createConversationTaskApi({ get, post })
    const [task] = await api.list('session')
    expect(task).toMatchObject({
      taskId: 'task/a',
      endUserId: { value: '9007199254740993' },
      state: { schemaId: 'state.v1', canonicalJson: wire.state.canonical_json },
      updatedAt: wire.updated_at
    })
    expect(await api.detail('session', task!.taskId)).toEqual(task)
    expect(get.mock.calls[1]![0]).toBe(
      '/conversation-tasks/detail?session_id=session&task_id=task%2Fa'
    )
    expect(await api.select('session', task!, 'select')).toEqual(task)
    expect(await api.update('session', task!, 'complete', 'complete')).toEqual(task)
  })
})
