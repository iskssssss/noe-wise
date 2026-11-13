import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import AdminNavigation from './AdminNavigation.vue'
import {
  expandedNavigation,
  filterNavigation,
  navigationKeys,
  navigationLeaves
} from './navigationTree'
import type { AdminMenu } from './types'

const menus: AdminMenu[] = [
  { title: '工作台', path: '/admin/home' },
  {
    title: '资源构建',
    children: [
      { title: '智能体', path: '/admin/agents' },
      {
        title: '知识与记忆',
        children: [
          { title: '知识库', path: '/admin/knowledge' },
          { title: '记忆管理', path: '/admin/memory' }
        ]
      }
    ]
  },
  { title: '运营管理', children: [{ title: '账单记录', path: '/admin/billing-records' }] }
]
let wrapper: VueWrapper | undefined
beforeEach(() => {
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
  wrapper?.unmount()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

describe('任务导航', () => {
  it('搜索嵌套入口保留祖先和真实路由，命中组名显示该组，未授权入口不会产生', () => {
    const found = filterNavigation(menus, '知识库')
    expect(navigationLeaves(found).map((item) => item.path)).toEqual(['/admin/knowledge'])
    expect(found[0]?.title).toBe('资源构建')
    expect(expandedNavigation(found, undefined, true)).toEqual([
      'section/资源构建',
      'section/资源构建/知识与记忆'
    ])
    expect(navigationKeys(found)[0]).toBe(navigationKeys(menus)[1])
    expect(navigationLeaves(filterNavigation(menus, '运营管理')).map((item) => item.path)).toEqual([
      '/admin/billing-records'
    ])
    expect(filterNavigation(menus, '管理身份')).toEqual([])
    expect(navigationLeaves(menus)).toHaveLength(5)
  })
  it('深层当前页自动展开，搜索与清空保持当前页面，取消离开继续保留高亮', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: navigationLeaves(menus).map((item) => ({
        path: item.path!,
        component: { template: '<div />' }
      }))
    })
    await router.push('/admin/knowledge')
    await router.isReady()
    wrapper = mount(AdminNavigation, {
      attachTo: document.body,
      props: {
        title: '机构后台',
        home: '/admin/home',
        menus,
        collapsed: false,
        activePath: '/admin/knowledge'
      },
      global: { plugins: [router, ElementPlus] }
    })
    await flushPromises()
    expect(wrapper.findAll('.el-sub-menu.is-opened')).toHaveLength(2)
    expect(wrapper.get('a[aria-current="page"]').attributes('href')).toBe('/admin/knowledge')
    await wrapper.get('input[aria-label="查找功能"]').setValue('账单')
    await flushPromises()
    expect(wrapper.findAll('.admin-menu-link').map((link) => link.attributes('href'))).toEqual([
      '/admin/billing-records'
    ])
    expect(wrapper.text()).toContain('找到 1 个入口')
    router.beforeEach(() => false)
    await wrapper.get('.admin-menu-link').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin/knowledge')
    expect(wrapper.emitted('navigated')).toBeUndefined()
    await wrapper.get('input').trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(wrapper.get('a[aria-current="page"]').attributes('href')).toBe('/admin/knowledge')
    expect(wrapper.findAll('.el-sub-menu.is-opened').length).toBeGreaterThanOrEqual(2)
  })
})
