import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHttpClient } from '@skillforge/shared/utils/http'
function client() {
  return createHttpClient({
    base: '/api/v1',
    accessTokenKey: 'test.access',
    refreshTokenKey: 'test.refresh',
    authHeader: (token) => ({ Authorization: token }),
    refreshPath: '/auth/refresh',
    isAuthPath: (path) => path.startsWith('/auth')
  })
}
function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'OK', message: '', data, trace_id: 'test' }), {
    status: 200
  })
}
afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
describe('令牌刷新与身份切换', () => {
  it('退出期间的刷新成功回包不能复活旧身份', async () => {
    const http = client()
    http.saveTokens({ access_token: 'old', refresh_token: 'refresh-old' })
    let finish!: (response: Response) => void
    vi.stubGlobal(
      'fetch',
      vi.fn().mockReturnValue(
        new Promise((resolve) => {
          finish = resolve
        })
      )
    )
    const pending = http.refreshAccessToken()
    http.clearTokens()
    finish(ok({ access_token: 'late', refresh_token: 'late-refresh' }))
    expect(await pending).toBeNull()
    expect(http.getAccessToken()).toBeNull()
  })
  it('旧账号刷新失败不能清空新账号', async () => {
    const http = client()
    http.saveTokens({ access_token: 'old', refresh_token: 'refresh-old' })
    let fail!: (error: Error) => void
    vi.stubGlobal(
      'fetch',
      vi.fn().mockReturnValue(
        new Promise((_resolve, reject) => {
          fail = reject
        })
      )
    )
    const pending = http.refreshAccessToken()
    http.saveTokens({ access_token: 'new', refresh_token: 'refresh-new' })
    fail(new Error('network'))
    expect(await pending).toBeNull()
    expect(http.getAccessToken()).toBe('new')
  })
  it('多个同身份401共享刷新并分别重试原读取', async () => {
    const http = client()
    http.saveTokens({ access_token: 'old', refresh_token: 'refresh-old' })
    const fetcher = vi.fn().mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/auth/refresh'))
        return Promise.resolve(ok({ access_token: 'renewed', refresh_token: 'refresh-new' }))
      const renewed = new Headers(init.headers).get('Authorization') === 'renewed'
      return Promise.resolve(renewed ? ok({}) : new Response(null, { status: 401 }))
    })
    vi.stubGlobal('fetch', fetcher)
    await expect(Promise.all([http.get('/one'), http.get('/two')])).resolves.toEqual([{}, {}])
    expect(fetcher.mock.calls.filter(([path]) => path === '/api/v1/auth/refresh')).toHaveLength(1)
  })
  it('刷新完成后才到达的旧令牌401仍重试读取，且不会再次刷新', async () => {
    const http = client()
    http.saveTokens({ access_token: 'old', refresh_token: 'refresh-old' })
    let finish!: (response: Response) => void
    const fetcher = vi.fn().mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/auth/refresh'))
        return Promise.resolve(ok({ access_token: 'renewed', refresh_token: 'refresh-new' }))
      if (new Headers(init.headers).get('Authorization') === 'renewed')
        return Promise.resolve(ok({ path }))
      if (path.endsWith('/late'))
        return new Promise<Response>((resolve) => {
          finish = resolve
        })
      return Promise.resolve(new Response(null, { status: 401 }))
    })
    vi.stubGlobal('fetch', fetcher)
    const late = http.get('/late')
    await http.get('/first')
    finish(new Response(null, { status: 401 }))
    await expect(late).resolves.toEqual({ path: '/api/v1/late' })
    expect(fetcher.mock.calls.filter(([path]) => path.endsWith('/auth/refresh'))).toHaveLength(1)
  })
  it('切换账号后迟到401不使用新账号重放旧请求', async () => {
    const http = client()
    http.saveTokens({ access_token: 'old', refresh_token: 'refresh-old' })
    let finish!: (response: Response) => void
    const fetcher = vi.fn().mockReturnValue(
      new Promise<Response>((resolve) => {
        finish = resolve
      })
    )
    vi.stubGlobal('fetch', fetcher)
    const pending = http.post('/write', { value: 'old-account' })
    http.saveTokens({ access_token: 'new', refresh_token: 'refresh-new' })
    finish(new Response(null, { status: 401 }))
    await expect(pending).rejects.toMatchObject({ status: 401 })
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(http.getAccessToken()).toBe('new')
  })
})
