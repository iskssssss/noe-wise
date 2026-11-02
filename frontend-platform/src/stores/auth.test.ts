import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
const state = vi.hoisted(() => ({
  token: null as string | null,
  session: null as string | null,
  login: vi.fn(),
  me: vi.fn(),
  logout: vi.fn().mockResolvedValue({ status: 'ok' })
}))
vi.mock('../api', () => ({
  api: { auth: { login: state.login, me: state.me, logout: state.logout } },
  getAccessToken: () => state.token,
  getSessionId: () => state.session,
  authSessionKey: 'skillforge.platform.access_token.session',
  saveTokens: (tokens: { access_token: string }) => {
    state.token = tokens.access_token
    state.session = crypto.randomUUID()
  },
  clearTokens: () => {
    state.token = null
    state.session = null
  }
}))
import { useAuthStore } from './auth'
let store: ReturnType<typeof useAuthStore>
beforeEach(() => {
  state.token = null
  state.session = null
  vi.clearAllMocks()
  setActivePinia(createPinia())
  store = useAuthStore()
})
afterEach(() => {
  store.$dispose()
  localStorage.clear()
})
describe('平台身份切换', () => {
  it('只有显式启用的挑战会话保持未完成认证', async () => {
    state.login.mockResolvedValue({ access_token: 'restricted' })
    state.me.mockResolvedValue({
      sub: '1',
      username: 'admin',
      roles: ['platform_admin'],
      permissions: [],
      authorization_version: 1,
      mfa_state: 'challenge',
      session_id: '71'
    })
    await store.login({ username: 'admin', password: 'password' })
    expect(store.isAuthenticated).toBe(false)
    expect(store.user?.mfa_state).toBe('challenge')
  })
  it('仅使用服务端确认的账号与角色，退出清理身份', async () => {
    state.login.mockResolvedValue({ access_token: 'a' })
    state.me.mockResolvedValue({
      sub: '1',
      username: '运营员',
      roles: ['platform_admin'],
      permissions: [],
      authorization_version: 1,
      mfa_state: 'full',
      session_id: '71'
    })
    await store.login({ username: 'name', password: 'password' })
    expect(store.user?.roles).toContain('platform_admin')
    expect(store.isAuthenticated).toBe(true)
    store.logout()
    expect(store.user).toBeNull()
    expect(state.token).toBeNull()
    expect(state.logout).toHaveBeenCalledOnce()
  })
  it('登录资料迟返回不能覆盖另一个标签的新账号令牌', async () => {
    state.login.mockResolvedValue({ access_token: 'old' })
    let finish!: (value: unknown) => void
    state.me.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const pending = store.login({ username: 'old', password: 'password' })
    await Promise.resolve()
    await Promise.resolve()
    state.token = 'new'
    state.session = 'other-session'
    finish({
      sub: 'old',
      username: 'old',
      roles: ['platform_admin'],
      permissions: [],
      authorization_version: 1,
      mfa_state: 'full',
      session_id: '71'
    })
    await expect(pending).rejects.toThrow('登录身份已变化')
    expect(state.token).toBe('new')
    expect(store.user).toBeNull()
  })
  it('同一会话跨标签刷新保留身份与编辑器生命周期', async () => {
    state.login.mockResolvedValue({ access_token: 'a' })
    state.me.mockResolvedValue({
      sub: '1',
      username: '运营员',
      roles: ['platform_admin'],
      permissions: [],
      authorization_version: 1,
      mfa_state: 'full',
      session_id: '71'
    })
    await store.login({ username: 'name', password: 'password' })
    const epoch = store.identityVersion
    state.token = 'rotated'
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: 'skillforge.platform.access_token',
        storageArea: localStorage
      })
    )
    expect(store.user?.sub).toBe('1')
    expect(store.accessToken).toBe('rotated')
    expect(store.identityVersion).toBe(epoch)
    state.session = 'different-account'
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: 'skillforge.platform.access_token.session',
        storageArea: localStorage
      })
    )
    expect(store.user).toBeNull()
    expect(store.identityVersion).toBe(epoch + 1)
  })
  it('身份读取期间同会话令牌轮换仍接受服务端资料', async () => {
    store.applyTokens({
      access_token: 'first',
      token_type: 'Bearer',
      expires_in: 3600,
      username: 'admin',
      roles: ['platform_admin'],
      permissions: [],
      authorization_version: 1,
      mfa_state: 'full',
      session_id: '71'
    })
    let finish!: (value: unknown) => void
    state.me.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const pending = store.fetchMe()
    state.token = 'refreshed'
    finish({
      sub: '1',
      username: '运营员',
      roles: ['platform_admin'],
      permissions: [],
      authorization_version: 1,
      mfa_state: 'full',
      session_id: '71'
    })
    await pending
    expect(store.user?.sub).toBe('1')
    expect(store.accessToken).toBe('refreshed')
  })
})
