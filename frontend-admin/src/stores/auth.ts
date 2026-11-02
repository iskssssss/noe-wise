import { AuthSessionChangedError } from '@skillforge/shared/foundation/security/api'
import { computed, onScopeDispose, ref } from 'vue'
import { defineStore } from 'pinia'
import { clearFeedback } from '@skillforge/shared/feedback'
import {
  api,
  authSessionKey,
  clearTokens,
  getAccessToken,
  getSessionId,
  saveTokens,
  type AuthTokens
} from '../api'

export type AuthUser = import('@skillforge/shared/foundation/security/api').SecurityAuthUser

const USER_KEY = 'skillforge.user'

function loadUser(): AuthUser | null {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) ?? 'null') as AuthUser | null
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(loadUser())
  const accessToken = ref<string | null>(getAccessToken())
  const sessionId = ref(getSessionId())
  const isAuthenticated = computed(
    () => Boolean(accessToken.value) && user.value?.mfa_state === 'full'
  )
  const identityVersion = ref(0)
  // 授权版本只销毁旧页面状态；主体、安全会话或认证状态变化才触发重新导航。
  const sessionIdentity = computed(() =>
    accessToken.value && user.value
      ? `${user.value.tenant_id}:${user.value.sub}:${user.value.session_id}:${sessionId.value}:${user.value.mfa_state}`
      : ''
  )
  function identity(value: AuthUser | null) {
    return value
      ? `${value.tenant_id}:${value.sub}:${value.session_id}:${value.username}:${value.authorization_version}:${value.mfa_state}`
      : ''
  }
  function acceptIdentity(next: AuthUser | null) {
    const changed = identity(user.value) !== identity(next)
    user.value = next
    if (changed) {
      identityVersion.value++
      clearFeedback()
    }
  }

  function applyTokens(tokens: AuthTokens): void {
    saveTokens(tokens)
    accessToken.value = tokens.access_token
    sessionId.value = getSessionId()
    setUser({
      sub: '',
      tenant_id: tokens.tenant_id,
      username: tokens.username,
      roles: tokens.roles,
      permissions: tokens.permissions,
      authorization_version: tokens.authorization_version,
      mfa_state: tokens.mfa_state,
      session_id: tokens.session_id
    })
  }

  function setUser(nextUser: AuthUser): void {
    acceptIdentity(nextUser)
    localStorage.setItem(USER_KEY, JSON.stringify(nextUser))
  }

  async function login(credentials: {
    tenant_slug: string
    username: string
    password: string
  }): Promise<void> {
    const previousSession = getSessionId()
    const previousToken = getAccessToken()
    const ownsAttempt = () =>
      previousSession === getSessionId() &&
      (Boolean(previousSession) || previousToken === getAccessToken())
    let tokens: AuthTokens
    try {
      tokens = await api.auth.login(credentials)
    } catch (reason) {
      if (!ownsAttempt()) throw new AuthSessionChangedError()
      throw reason
    }
    if (!ownsAttempt()) throw new AuthSessionChangedError()
    applyTokens(tokens)
    const requestedSession = getSessionId()
    try {
      await fetchMe()
    } catch (reason) {
      if (requestedSession === getSessionId()) logout()
      throw reason
    }
  }

  async function fetchMe(): Promise<AuthUser> {
    const requestedSession = getSessionId()
    const token = getAccessToken()
    const ownsResponse = () =>
      Boolean(getAccessToken()) &&
      requestedSession === getSessionId() &&
      (Boolean(requestedSession) || token === getAccessToken())
    let current: AuthUser
    try {
      current = await api.auth.me()
    } catch (reason) {
      if (!ownsResponse()) throw new AuthSessionChangedError()
      throw reason
    }
    if (!ownsResponse()) throw new AuthSessionChangedError()
    accessToken.value = getAccessToken()
    sessionId.value = getSessionId()
    setUser(current)
    return current
  }

  function logout(revoke = true): void {
    if (revoke && getAccessToken()) void api.auth.logout().catch(() => undefined)
    clearFeedback()
    clearTokens()
    accessToken.value = null
    sessionId.value = null
    acceptIdentity(null)
    localStorage.removeItem(USER_KEY)
  }

  function syncIdentity(event: StorageEvent) {
    if (event.storageArea && event.storageArea !== localStorage) return
    if (
      event.key !== null &&
      event.key !== USER_KEY &&
      event.key !== authSessionKey &&
      event.key !== 'skillforge.access_token'
    )
      return
    const nextSession = getSessionId()
    const sameSession = Boolean(nextSession) && nextSession === sessionId.value
    accessToken.value = getAccessToken()
    sessionId.value = nextSession
    // 换会话后不得在后续 token storage 事件中重新装入尚未更新的旧用户缓存。
    acceptIdentity(accessToken.value && sameSession && user.value ? loadUser() : null)
  }
  window.addEventListener('storage', syncIdentity)
  onScopeDispose(() => window.removeEventListener('storage', syncIdentity))
  return {
    user,
    accessToken,
    isAuthenticated,
    identityVersion,
    sessionIdentity,
    applyTokens,
    login,
    fetchMe,
    logout
  }
})
