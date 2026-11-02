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

it('连接器更新收到 401 不自动重发', async () => {
  localStorage.setItem('skillforge.access_token', 'expired-token')
  localStorage.setItem('skillforge.refresh_token', 'refresh-token')
  const fetchMock = vi
    .fn()
    .mockResolvedValue(
      new Response('{}', { status: 401, headers: { 'Content-Type': 'application/json' } })
    )
  vi.stubGlobal('fetch', fetchMock)
  await expect(
    api.connectors.updateInstanceWithReceipt('request', 'connector', { name: '名称' })
  ).rejects.toMatchObject({ status: 401 })
  expect(fetchMock).toHaveBeenCalledTimes(1)
})

it.each(['receipt', 'issue', 'rotate'] as const)(
  '渠道凭据 %s 收到 401 不自动重发',
  async (kind) => {
    localStorage.setItem('skillforge.access_token', 'expired-token')
    localStorage.setItem('skillforge.refresh_token', 'refresh-token')
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response('{}', { status: 401, headers: { 'Content-Type': 'application/json' } })
      )
    vi.stubGlobal('fetch', fetchMock)
    const action =
      kind === 'receipt'
        ? api.channels.issueApiKeyWithReceipt('request', '42')
        : kind === 'issue'
          ? api.channels.issueApiKey('42')
          : api.channels.rotateApiKey('42')
    await expect(action).rejects.toMatchObject({ status: 401 })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  }
)

it.each(['receipt', 'legacy'] as const)('渠道令牌 %s 轮换收到 401 不自动重发', async (kind) => {
  localStorage.setItem('skillforge.access_token', 'expired-token')
  localStorage.setItem('skillforge.refresh_token', 'refresh-token')
  const fetchMock = vi
    .fn()
    .mockResolvedValue(
      new Response('{}', { status: 401, headers: { 'Content-Type': 'application/json' } })
    )
  vi.stubGlobal('fetch', fetchMock)
  await expect(
    kind === 'receipt'
      ? api.channels.rotateTokenWithReceipt('request', '42')
      : api.channels.rotateToken('42')
  ).rejects.toMatchObject({ status: 401 })
  expect(fetchMock).toHaveBeenCalledTimes(1)
})
