import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api, clearTokens, saveTokens } from './index'
import { useAuthStore } from '../stores/auth'

afterEach(() => {
  vi.unstubAllGlobals()
  clearTokens()
  localStorage.clear()
})

describe('平台登录认证请求', () => {
  it.each(['request', 'complete', 'invite'] as const)(
    '公开账号恢复和邀请 %s 不携带旧平台令牌',
    async (action) => {
      saveTokens({ access_token: 'expired-platform-token', refresh_token: 'expired-refresh-token' })
      const fetcher = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: 'INVALID_ACCESS_TOKEN',
            message: '拒绝',
            data: null,
            trace_id: 'test'
          }),
          { status: 401 }
        )
      )
      vi.stubGlobal('fetch', fetcher)
      const command = { token: 'synthetic-token', new_password: 'synthetic-password' }
      const result =
        action === 'request'
          ? api.auth.requestRecovery({ username: 'member' })
          : action === 'complete'
            ? api.auth.completeRecovery(command)
            : api.auth.activate(command)
      await expect(result).rejects.toMatchObject({ status: 401 })
      expect(fetcher).toHaveBeenCalledTimes(1)
      expect(new Headers(fetcher.mock.calls[0]![1].headers).has('X-Platform-Token')).toBe(false)
    }
  )
  it('已有旧令牌时匿名登录，登录后的资料请求携带新平台令牌', async () => {
    setActivePinia(createPinia())
    saveTokens({ access_token: 'expired-platform-token', refresh_token: 'expired-refresh-token' })
    const fetcher = vi.fn(async (input: string, init?: RequestInit) => {
      const headers = new Headers(init?.headers)
      if (input === '/api/v1/platform/auth/login') {
        expect(headers.has('X-Platform-Token')).toBe(false)
        expect(headers.has('Authorization')).toBe(false)
        expect(init?.method).toBe('POST')
        expect(JSON.parse(String(init?.body))).toEqual({
          username: 'platform-admin',
          password: 'new-password'
        })
        return new Response(
          JSON.stringify({
            code: 'OK',
            message: '',
            trace_id: 'login-regression',
            data: {
              access_token: 'new-platform-token',
              refresh_token: 'new-refresh-token',
              token_type: 'Bearer',
              expires_in: 3600
            }
          }),
          { status: 200 }
        )
      }
      expect(input).toBe('/api/v1/platform/auth/me')
      expect(headers.get('X-Platform-Token')).toBe('Bearer new-platform-token')
      return new Response(
        JSON.stringify({
          code: 'OK',
          message: '',
          trace_id: 'profile-regression',
          data: {
            sub: 'platform-operator',
            username: 'platform-admin',
            roles: ['platform_admin'],
            permissions: [],
            authorization_version: 1,
            mfa_state: 'full',
            session_id: '71'
          }
        }),
        { status: 200 }
      )
    })
    vi.stubGlobal('fetch', fetcher)
    const auth = useAuthStore()
    try {
      await auth.login({ username: 'platform-admin', password: 'new-password' })
      expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
        '/api/v1/platform/auth/login',
        '/api/v1/platform/auth/me'
      ])
      expect(auth.user?.roles).toContain('platform_admin')
      expect(auth.isAuthenticated).toBe(true)
      expect(auth.accessToken).toBe('new-platform-token')
    } finally {
      auth.$dispose()
    }
  })
})
