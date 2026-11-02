import { afterEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'
import type { HttpClient } from '../utils/http'
import { routePermission, useFoundationNavigation, type NavigationItem } from './navigation'

const stop: Array<() => void> = []
it('工具入口沿用当前拥有的能力或连接器读取权限，不改变高级路由权限', () => {
  expect(routePermission('/admin/tools', ['foundation.capabilities.read'])).toBe(
    'foundation.capabilities.read'
  )
  expect(routePermission('/admin/tools', ['foundation.connectors.read'])).toBe(
    'foundation.connectors.read'
  )
  expect(routePermission('/admin/tools', ['foundation.agents.read'])).toBe(
    'foundation.capabilities.read'
  )
  expect(routePermission('/admin/tool-deployments', ['foundation.connectors.read'])).toBe(
    'foundation.capabilities.read'
  )
  expect(routePermission('/admin/connectors', ['foundation.capabilities.read'])).toBe(
    'foundation.connectors.read'
  )
})
afterEach(() => {
  stop.splice(0).forEach((dispose) => dispose())
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const item = (title: string, path: string): NavigationItem => ({
  title,
  path,
  permission: null,
  children: []
})

describe('current account navigation', () => {
  it.each(['admin', 'platform'])('轨迹列表和详情在%s宿主都使用会话读取权限', (host) => {
    expect(routePermission(`/${host}/trajectories`)).toBe('foundation.sessions.read')
    expect(routePermission(`/${host}/trajectories/detail`)).toBe('foundation.sessions.read')
  })
  it.each(['admin', 'platform'])(
    '消息聚合页在%s宿主接受任一真实页动作，普通路由不因此扩大权限',
    (host) => {
      const path = `/${host}/foundation/messaging`
      for (const action of ['inbox', 'read', 'publish', 'configure', 'operate']) {
        const granted = [`foundation.messaging.${action}`]
        const required = routePermission(path, granted)
        expect(required).toBe(granted[0])
        expect(granted.includes(required!)).toBe(true)
      }
      const unrelated = ['foundation.access.read', 'foundation.messaging.unknown']
      expect(unrelated.includes(routePermission(path, unrelated)!)).toBe(false)
      expect(routePermission(`/${host}/foundation/access`, ['foundation.messaging.publish'])).toBe(
        'foundation.access.read'
      )
    }
  )
  it('discards an old account response even when its transport ignores abort', async () => {
    const old = deferred<NavigationItem[]>(),
      current = deferred<NavigationItem[]>()
    const request = vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const identity = ref('account-a'),
      scope = effectScope()
    stop.push(() => scope.stop())
    const nav = scope.run(() =>
      useFoundationNavigation(request as HttpClient['request'], '/tenant', () => identity.value)
    )!
    identity.value = 'account-b'
    await nextTick()
    current.resolve([item('新账号', '/admin/foundation/security')])
    await current.promise
    old.resolve([item('旧账号管理权限', '/admin/foundation/access')])
    await old.promise
    expect(nav.menus.value).toEqual([{ title: '新账号', path: '/admin/foundation/security' }])
    expect(request.mock.calls[0]![4].signal.aborted).toBe(true)
  })

  it('clears navigation on logout and keeps failed loads empty', async () => {
    const pending = deferred<NavigationItem[]>()
    const identity = ref('account-a'),
      scope = effectScope()
    stop.push(() => scope.stop())
    const request = vi
      .fn()
      .mockResolvedValueOnce([item('访问控制', '/admin/foundation/access')])
      .mockReturnValueOnce(pending.promise)
    const nav = scope.run(() =>
      useFoundationNavigation(request as HttpClient['request'], '/tenant', () => identity.value)
    )!
    await nextTick()
    expect(nav.menus.value).toHaveLength(1)
    const reload = nav.reload()
    expect(nav.menus.value).toEqual([])
    pending.reject(new Error('权限已撤销'))
    await reload
    expect(nav.error.value).toBe('权限已撤销')
    identity.value = ''
    await nextTick()
    expect(nav.menus.value).toEqual([])
    expect(nav.error.value).toBe('')
    expect(request).toHaveBeenCalledTimes(2)
  })

  it('rejects links for another host and gates deep links by compiled action permissions', async () => {
    const scope = effectScope()
    stop.push(() => scope.stop())
    const request = vi
      .fn()
      .mockResolvedValue([
        item('跨后台', '/admin/foundation/access'),
        item('安全设置', '/platform/foundation/security')
      ])
    const nav = scope.run(() =>
      useFoundationNavigation(request as HttpClient['request'], '/admin', () => 'platform-user')
    )!
    await nextTick()
    expect(nav.menus.value).toEqual([{ title: '安全设置', path: '/platform/foundation/security' }])
    expect(routePermission('/platform/tenants/123')).toBe('foundation.tenants.read')
    expect(routePermission('/admin/agents/new')).toBe('foundation.agents.create')
    expect(routePermission('/admin/foundation/security')).toBe('foundation.security.self')
    expect(routePermission('/platform/foundation/jobs')).toBe('foundation.jobs.read')
  })
})

it('治理入口仅接受实际分区读取权限，不能扩大其他页面权限', () => {
  for (const permission of [
    'foundation.audit.read',
    'foundation.service_principals.read',
    'foundation.webhooks.read',
    'foundation.knowledge.read'
  ]) {
    expect(routePermission('/admin/governance', [permission])).toBe(permission)
  }
  expect(routePermission('/admin/governance', ['foundation.webhooks.create'])).toBe(
    'foundation.audit.read'
  )
  expect(routePermission('/admin/api-docs', ['foundation.webhooks.read'])).toBe(
    'foundation.service_principals.read'
  )
  expect(routePermission('/platform/governance', ['foundation.webhooks.read'])).toBe(
    'foundation.audit.read'
  )
})
