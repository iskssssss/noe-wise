import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHttpClient } from './http'

const config = {
  base: '/api',
  accessTokenKey: 'test.access',
  refreshTokenKey: 'test.refresh',
  refreshPath: '/refresh',
  authHeader: (token: string) => ({ Authorization: `Bearer ${token}` }),
  isAuthPath: () => false
}
const response = (data: unknown) =>
  new Response(JSON.stringify({ code: 'OK', message: '', trace_id: 'test', data }), { status: 200 })
afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

describe('认证会话与令牌轮换', () => {
  it('禁止自动重发时保留原始 401 拒绝码，不刷新令牌或再次写入', async () => {
    const onUnauthorized = vi.fn()
    const client = createHttpClient({ ...config, onUnauthorized })
    client.saveTokens({ access_token: 'old', refresh_token: 'refresh' })
    const session = client.getSessionId()
    const fetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          code: 'SYSTEM_AGENT_TOOL_WRITE_REJECTED',
          message: 'rejected',
          data: null,
          trace_id: 'original'
        }),
        { status: 401 }
      )
    )
    vi.stubGlobal('fetch', fetch)
    await expect(
      client.post('/write', { value: 1 }, { retryOnUnauthorized: false, silent: true })
    ).rejects.toMatchObject({
      status: 401,
      code: 'SYSTEM_AGENT_TOOL_WRITE_REJECTED',
      traceId: 'original'
    })
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(client.getAccessToken()).toBe('old')
    expect(client.getSessionId()).toBe(session)
    expect(localStorage.getItem(config.refreshTokenKey)).toBe('refresh')
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('默认 401 仍刷新并重发原请求', async () => {
    const client = createHttpClient(config)
    client.saveTokens({ access_token: 'old', refresh_token: 'refresh' })
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(new Response('', { status: 401 }))
      .mockResolvedValueOnce(response({ access_token: 'new', refresh_token: 'next' }))
      .mockResolvedValueOnce(response({ value: 1 }))
    vi.stubGlobal('fetch', fetch)
    await expect(client.get('/read')).resolves.toEqual({ value: 1 })
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(fetch.mock.calls.map((call) => call[0])).toEqual([
      '/api/read',
      '/api/refresh',
      '/api/read'
    ])
    expect(new Headers(fetch.mock.calls[2]![1].headers).get('Authorization')).toBe('Bearer new')
  })

  it('刷新保留会话，重新登录创建新会话并清除旧刷新凭据', async () => {
    const client = createHttpClient(config)
    client.saveTokens({ access_token: 'old', refresh_token: 'refresh' })
    const session = client.getSessionId()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(response({ access_token: 'new', refresh_token: 'next' }))
    )
    expect(await client.refreshAccessToken()).toBe('new')
    expect(client.getSessionId()).toBe(session)
    client.saveTokens({ access_token: 'other' })
    expect(client.getSessionId()).not.toBe(session)
    expect(localStorage.getItem(config.refreshTokenKey)).toBeNull()
    client.clearTokens()
    expect(client.getSessionId()).toBeNull()
  })

  it('另一客户端重新登录后，迟到刷新不能覆盖新登录凭据', async () => {
    const first = createHttpClient(config),
      second = createHttpClient(config)
    first.saveTokens({ access_token: 'old', refresh_token: 'refresh' })
    let finish!: (value: Response) => void
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve
          })
      )
    )
    const pending = first.refreshAccessToken()
    second.saveTokens({ access_token: 'other', refresh_token: 'other-refresh' })
    finish(response({ access_token: 'stale', refresh_token: 'stale-refresh' }))
    expect(await pending).toBeNull()
    expect(first.getAccessToken()).toBe('other')
    expect(localStorage.getItem(config.refreshTokenKey)).toBe('other-refresh')
  })
})
