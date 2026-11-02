import { afterEach, expect, it, vi } from 'vitest'
import { api } from './index'
afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
it.each(['connector', 'session'] as const)('%s 创建收到 401 不自动重发', async (kind) => {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ code: 'DENIED', message: '拒绝', trace_id: 'trace' }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' }
    })
  )
  vi.stubGlobal('fetch', fetchMock)
  localStorage.setItem('skillforge.access_token', 'old')
  localStorage.setItem('skillforge.refresh_token', 'refresh')
  const action =
    kind === 'connector'
      ? api.connectors.createInstanceWithReceipt('request', {
          name: 'test',
          base_url: 'https://example.com/mcp',
          executor_type: 'mcp',
          auth_type: 'none',
          auth_credential: '',
          vault_secret_path: '',
          headers: {}
        })
      : api.chat.createSessionWithReceipt('request', '42', { agent_id: '101' })
  await expect(action).rejects.toBeTruthy()
  expect(fetchMock).toHaveBeenCalledTimes(1)
})
