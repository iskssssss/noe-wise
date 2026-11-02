import { afterEach, expect, it, vi } from 'vitest'
import { api } from './index'

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

it.each([
  {
    name: '工作流草稿',
    method: 'PUT',
    path: '/api/v1/builder/workflows/draft',
    send: () => api.workflowDrafts.save('orders', 7, { version: '1.0' })
  },
  {
    name: '工作流布局',
    method: 'PUT',
    path: '/api/v1/builder/workflows/draft/snapshot',
    send: () => api.workflowDrafts.snapshot.save('orders', 7, {}, {})
  },
  {
    name: '工作流测试会话',
    method: 'POST',
    path: '/api/v1/builder/workflows/test-sessions',
    send: () =>
      api.workflowTests.create({
        requestId: 'test-request',
        workflowId: 'orders',
        source: { kind: 'draft', draftRevision: 7 },
        config: {},
        bindings: { capabilities: [], workflows: [], agents: [] },
        userInput: '测试消息',
        parameters: {},
        budget: {
          maxBusinessModelCalls: 1,
          maxToolCalls: 1,
          maxKnowledgeCalls: 1,
          maxTotalTokens: 1024,
          maxWallTimeSeconds: 60
        }
      })
  },
  {
    name: '对话输入',
    method: 'POST',
    path: '/api/v1/chat',
    send: () => api.chat.submit({ session_id: 'session-1', message: '测试消息' })
  }
])('$name 收到 401 后只发送一次且不刷新令牌', async ({ method, path, send }) => {
  localStorage.setItem('skillforge.access_token', 'expired-token')
  localStorage.setItem('skillforge.refresh_token', 'refresh-token')
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ code: 'DENIED', message: '拒绝', trace_id: 'trace' }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' }
    })
  )
  vi.stubGlobal('fetch', fetchMock)

  await expect(send()).rejects.toMatchObject({ status: 401 })
  expect(fetchMock).toHaveBeenCalledTimes(1)
  const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
  expect(new URL(url, 'http://localhost').pathname).toBe(path)
  expect(init.method).toBe(method)
})
