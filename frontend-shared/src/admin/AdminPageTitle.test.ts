import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { isNavigationFailure, NavigationFailureType } from 'vue-router'
import institutionRouter from '../../../frontend-admin/src/router'
import platformRouter from '../../../frontend-platform/src/router'
import AdminLayout from './AdminLayout.vue'

vi.mock('vue-router', async (importOriginal) => {
  const original = await importOriginal<typeof import('vue-router')>()
  return { ...original, createWebHistory: original.createMemoryHistory }
})
const { auth } = vi.hoisted(() => ({
  auth: {
    accessToken: 'test-session',
    isAuthenticated: true,
    fetchMe: async () => {},
    user: { permissions: ['foundation.preferences.manage', 'foundation.files.read'] }
  }
}))
vi.mock('../../../frontend-admin/src/stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('../../../frontend-platform/src/stores/auth', () => ({ useAuthStore: () => auth }))

let wrapper: VueWrapper | undefined
let removeGuard: (() => void) | undefined
beforeEach(() => {
  vi.stubGlobal('matchMedia', (media: string) => ({
    media,
    matches: false,
    addEventListener() {},
    removeEventListener() {}
  }))
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
})
afterEach(() => {
  removeGuard?.()
  removeGuard = undefined
  wrapper?.unmount()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

describe.each([
  { label: '机构', router: institutionRouter, root: '/admin', brand: '巡检机构品牌' },
  { label: '平台', router: platformRouter, root: '/platform', brand: '灵知 平台运营' }
])('$label真实路由与共享布局标题', ({ router, root, brand }) => {
  it('查询变化和取消导航不覆盖当前标题，成功导航及品牌变化更新标题，独立页仍有标题', async () => {
    // 保留真实路由元数据与导航钩子，业务页替身避免触发与标题无关的服务请求。
    for (const record of router.getRoutes()) {
      if (record.components) record.components.default = { template: '<div />' }
    }
    const catalogPath = `${root}/foundation/catalog`
    await router.push(catalogPath)
    await router.isReady()
    wrapper = mount(AdminLayout, {
      attachTo: document.body,
      props: {
        title: brand,
        userName: '验收账号',
        home: `${root}/home`,
        menus: [
          { title: '基础目录与设置', path: catalogPath },
          { title: '文件与管理任务', path: `${root}/foundation/assets` }
        ]
      },
      global: { plugins: [router, ElementPlus] }
    })
    await flushPromises()
    const catalogTitle = `${router.currentRoute.value.meta.title} · ${brand}`
    expect(document.title).toBe(catalogTitle)
    await router.push(`${catalogPath}?tab=preferences`)
    await flushPromises()
    expect(document.title).toBe(catalogTitle)
    removeGuard = router.beforeEach(() => false)
    for (const path of [`${root}/foundation/assets`, '/login']) {
      expect(isNavigationFailure(await router.push(path), NavigationFailureType.aborted)).toBe(true)
      expect(router.currentRoute.value.fullPath).toBe(`${catalogPath}?tab=preferences`)
      expect(document.title).toBe(catalogTitle)
    }
    removeGuard()
    removeGuard = undefined
    await router.push(`${root}/foundation/assets`)
    await flushPromises()
    expect(document.title).toBe(`文件与管理任务 · ${brand}`)
    await wrapper.setProps({ title: '已更新的品牌' })
    expect(document.title).toBe('文件与管理任务 · 已更新的品牌')
    wrapper.unmount()
    wrapper = undefined
    await router.push('/login')
    expect(document.title).toMatch(/ · 灵知$/)
    expect(document.title).not.toContain('文件与管理任务')
  })
})
