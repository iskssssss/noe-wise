import { AuthSessionChangedError } from '@skillforge/shared/foundation/security/api'
import { computed, onScopeDispose, ref } from 'vue'
import { defineStore } from 'pinia'
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
const USER_KEY = 'skillforge.platform.user'
export const useAuthStore = defineStore('platform-auth', () => {
  const user = ref<AuthUser | null>(null)
  const accessToken = ref(getAccessToken())
  const sessionId = ref(getSessionId())
  const identityVersion = ref(0)
  const isAuthenticated = computed(
    () => Boolean(accessToken.value) && user.value?.mfa_state === 'full'
  )
  const sessionIdentity = computed(() =>
    accessToken.value && user.value
      ? `${user.value.tenant_id}:${user.value.sub}:${user.value.session_id}:${sessionId.value}:${user.value.mfa_state}`
      : ''
  )
  function applyTokens(tokens: AuthTokens) {
    saveTokens(tokens)
    const nextSession = getSessionId()
    if (nextSession !== sessionId.value && user.value) {
      user.value = null
      identityVersion.value++
    }
    accessToken.value = tokens.access_token
    sessionId.value = nextSession
  }
  async function fetchMe() {
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
    const changed =
      user.value?.sub !== current.sub ||
      user.value?.tenant_id !== current.tenant_id ||
      user.value?.session_id !== current.session_id ||
      user.value?.authorization_version !== current.authorization_version ||
      user.value?.mfa_state !== current.mfa_state
    accessToken.value = getAccessToken()
    sessionId.value = getSessionId()
    user.value = current
    if (changed) identityVersion.value++
    localStorage.setItem(USER_KEY, JSON.stringify(current))
    return current
  }
  async function login(credentials: { username: string; password: string }) {
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
    } catch (e) {
      if (requestedSession === getSessionId()) logout()
      throw e
    }
  }
  function logout(revoke = true) {
    if (revoke && getAccessToken()) void api.auth.logout().catch(() => undefined)
    clearTokens()
    accessToken.value = null
    sessionId.value = null
    user.value = null
    identityVersion.value++
    localStorage.removeItem(USER_KEY)
  }
  function sync(event: StorageEvent) {
    if (event.storageArea && event.storageArea !== localStorage) return
    if (
      event.key !== null &&
      event.key !== authSessionKey &&
      event.key !== 'skillforge.platform.access_token'
    )
      return
    const nextToken = getAccessToken(),
      nextSession = getSessionId()
    const sameSession = Boolean(nextSession) && nextSession === sessionId.value
    accessToken.value = nextToken
    sessionId.value = nextSession
    if (!nextToken || !sameSession) {
      user.value = null
      identityVersion.value++
    }
  }
  window.addEventListener('storage', sync)
  onScopeDispose(() => window.removeEventListener('storage', sync))
  return {
    user,
    accessToken,
    identityVersion,
    sessionIdentity,
    isAuthenticated,
    applyTokens,
    fetchMe,
    login,
    logout
  }
})
