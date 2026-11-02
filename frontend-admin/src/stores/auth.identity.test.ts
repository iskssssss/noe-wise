import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AuthUser } from './auth'
const state = vi.hoisted(() => ({
  token: null as string | null,
  session: null as string | null,
  login: vi.fn(),
  me: vi.fn(),
  logout: vi.fn(async () => ({ status: 'ok' }))
}))
vi.mock('../api', () => ({
  api: { auth: { login: state.login, me: state.me, logout: state.logout } },
  getAccessToken: () => state.token,
  getSessionId: () => state.session,
  authSessionKey: 'skillforge.access_token.session',
  saveTokens: (tokens: { access_token: string; session_id: string }) => {
    state.token = tokens.access_token
    state.session = 'browser-' + tokens.session_id
  },
  clearTokens: () => {
    state.token = null
    state.session = null
  }
}))
import { useAuthStore } from './auth'
const user = (): AuthUser => ({
  sub: '42',
  tenant_id: '7',
  username: 'member',
  roles: [],
  permissions: [],
  authorization_version: 1,
  mfa_state: 'full',
  session_id: '71'
})
let auth: ReturnType<typeof useAuthStore>
beforeEach(() => {
  localStorage.clear()
  state.token = null
  state.session = null
  vi.clearAllMocks()
  setActivePinia(createPinia())
  auth = useAuthStore()
})
afterEach(() => {
  auth.$dispose()
  localStorage.clear()
})
it('旧登录的资料迟到不能清掉另一个标签刚创建的新会话', async () => {
  state.login.mockResolvedValue({
    access_token: 'old-token',
    token_type: 'Bearer',
    expires_in: 3600,
    ...user()
  })
  let finish!: (value: AuthUser) => void
  state.me.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  const pending = auth.login({
    tenant_slug: 'tenant',
    username: 'member',
    password: 'Password1234'
  })
  await Promise.resolve()
  await Promise.resolve()
  expect(finish).toBeTypeOf('function')
  state.token = 'new-token'
  state.session = 'browser-72'
  window.dispatchEvent(
    new StorageEvent('storage', {
      key: 'skillforge.access_token.session',
      storageArea: localStorage
    })
  )
  finish(user())
  await expect(pending).rejects.toThrow('登录身份已变化，请重新加载')
  expect(state.token).toBe('new-token')
  expect(state.session).toBe('browser-72')
  expect(auth.user).toBeNull()
  expect(state.logout).not.toHaveBeenCalled()
})
it('同登录会话内令牌轮换仍接受当前资料且保持会话身份', async () => {
  auth.applyTokens({
    access_token: 'first-token',
    token_type: 'Bearer',
    expires_in: 3600,
    ...user()
  })
  state.me.mockResolvedValueOnce(user())
  await auth.fetchMe()
  const identity = auth.sessionIdentity
  const version = auth.identityVersion
  let finish!: (value: AuthUser) => void
  state.me.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  const pending = auth.fetchMe()
  state.token = 'refreshed-token'
  finish(user())
  await pending
  expect(auth.accessToken).toBe('refreshed-token')
  expect(auth.sessionIdentity).toBe(identity)
  expect(auth.identityVersion).toBe(version)
})
