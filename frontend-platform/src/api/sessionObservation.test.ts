import { beforeEach, describe, expect, it, vi } from 'vitest'
import { routePermission } from '@skillforge/shared/foundation/navigation'

const identity = vi.hoisted(() => ({
  accessToken: 'authenticated-token' as string | null,
  isAuthenticated: true,
  user: { roles: ['platform_admin'], permissions: [] as string[] },
  fetchMe: vi.fn().mockResolvedValue(undefined)
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => identity }))
import { createSessionObservationApi, formatToken } from './sessionObservation'
import router from '../router'

beforeEach(async () => {
  identity.accessToken = 'authenticated-token'
  identity.isAuthenticated = true
  identity.user = { roles: ['platform_admin'], permissions: [] }
  identity.fetchMe.mockReset().mockResolvedValue(undefined)
  await router.push('/login')
})

describe('platform session observation contract', () => {
  it('reads administrator attempt details with exact scope and propagates cancellation', async () => {
    const requests: Array<{ path: string; signal?: AbortSignal }> = []
    const client = createSessionObservationApi(async (path, opts) => {
      requests.push({ path, signal: opts?.signal })
      return {} as never
    })
    const controller = new AbortController()
    await client.modelAttemptDetail('tenant A', 'session&A', 'attempt:1', 'root/A', {
      signal: controller.signal
    })
    const url = new URL(requests[0].path, 'https://local.invalid')
    expect(url.pathname).toBe('/platform/sessions/model-attempt/detail')
    expect(Object.fromEntries(url.searchParams)).toEqual({
      tenant_id: 'tenant A',
      session_id: 'session&A',
      attempt_id: 'attempt:1',
      root_input_id: 'root/A'
    })
    expect(requests[0].signal).toBe(controller.signal)
    await client.modelAttemptDetail('tenant A', 'session&A', 'unassociated-attempt')
    expect(requests[1].path).not.toContain('root_input_id=')
  })
  it('keeps exact decimal token strings and unknown values distinct', () => {
    expect(formatToken('9007199254740993123456789')).toBe('9,007,199,254,740,993,123,456,789')
    expect(formatToken(null)).toBe('—')
    expect(formatToken('unknown')).toBe('—')
  })

  it('keeps child attempt identity while passing its authorized parent trajectory separately', async () => {
    const requests: string[] = []
    const client = createSessionObservationApi(async (path) => {
      requests.push(path)
      return {} as never
    })
    await client.modelAttemptDetail(
      'tenant-A',
      'delegation:child',
      'attempt-1',
      'child-root',
      undefined,
      {
        session_id: 'parent-session',
        root_input_id: 'parent-root'
      }
    )
    expect(Object.fromEntries(new URL(requests[0], 'https://local.invalid').searchParams)).toEqual({
      tenant_id: 'tenant-A',
      session_id: 'delegation:child',
      attempt_id: 'attempt-1',
      root_input_id: 'child-root',
      parent_session_id: 'parent-session',
      parent_root_input_id: 'parent-root'
    })
  })

  it('uses the stable root input and node deep-link query for trace windows', async () => {
    const calls: string[] = []
    const client = createSessionObservationApi(async (path) => {
      calls.push(path)
      return {} as never
    })
    await client.trace('tenant-A', 'session-A', 'input-7', undefined, 100, 'node-9')
    expect(calls[0]).toContain('tenant_id=tenant-A')
    expect(calls[0]).toContain('root_input_id=input-7')
    expect(calls[0]).toContain('node_id=node-9')
  })

  it('passes the usage continuation cursor without changing the root window', async () => {
    const calls: string[] = []
    const client = createSessionObservationApi(async (path) => {
      calls.push(path)
      return {} as never
    })
    await client.usage('tenant-A', 'session-A', 'input-7', 'usage-cursor-9', 100)
    expect(calls[0]).toContain('root_input_id=input-7')
    expect(calls[0]).toContain('cursor=usage-cursor-9')
    expect(calls[0]).toContain('page_size=100')
  })

  it('registers list and deep-link routes under authenticated platform navigation', () => {
    expect(router.resolve('/platform/sessions/detail').meta.requiresAuth).toBe(true)
    expect(routePermission('/platform/sessions')).toBe('foundation.sessions.read')
    expect(routePermission('/platform/sessions/detail')).toBe('foundation.sessions.read')
    expect(router.resolve('/platform/sessions').matched.at(-1)?.meta.title).toBe('会话观察')
    expect(
      router
        .resolve({
          path: '/platform/sessions/detail',
          query: {
            tenant_id: 'tenant-A',
            session_id: 'session-A',
            root_input_id: 'input-7',
            node_id: 'node-9',
            tab: 'trace'
          }
        })
        .matched.at(-1)?.meta.title
    ).toBe('会话详情')
  })

  it('rejects a platform administrator role without the explicit session permission', async () => {
    await router.push('/platform/sessions/detail?tenant_id=tenant-A&session_id=session-A')
    expect(identity.fetchMe).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.path).toBe('/403')
    expect(router.currentRoute.value.meta.requiresAuth).toBeUndefined()
  })

  it('allows a custom role only with the explicit permission and preserves the full trace deep link', async () => {
    identity.user = { roles: ['custom-session-auditor'], permissions: ['foundation.sessions.read'] }
    const target =
      '/platform/sessions/detail?tenant_id=tenant-A&session_id=session-A&root_input_id=input-7&node_id=node-9&tab=trace'
    await router.push(target)
    expect(identity.fetchMe).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.fullPath).toBe(target)
    expect(router.currentRoute.value.meta.requiresAuth).toBe(true)
  })

  it('keeps the exact return URL when the token, authentication proof, or identity lookup fails', async () => {
    const target =
      '/platform/sessions/detail?tenant_id=tenant-B&session_id=9223372036854775806&tab=usage'
    identity.accessToken = null
    await router.push(target)
    expect(identity.fetchMe).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe(target)

    identity.accessToken = 'restricted-token'
    identity.isAuthenticated = false
    await router.push(target)
    expect(identity.fetchMe).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe(target)

    identity.isAuthenticated = true
    identity.fetchMe.mockRejectedValueOnce(new Error('expired session'))
    await router.push(target)
    expect(identity.fetchMe).toHaveBeenCalledTimes(2)
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe(target)
  })
})
