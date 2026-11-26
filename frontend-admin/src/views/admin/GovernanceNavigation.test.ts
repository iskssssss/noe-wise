import { reactive } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { ElInput, ElSelect } from 'element-plus'
import { clearFeedback } from '@skillforge/shared/feedback'
import GovernancePage from './GovernancePage.vue'
import Layout from './Layout.vue'
import applicationRouter from '../../router'

const fixture = vi.hoisted(() => ({
  auth: {
    isAuthenticated: true,
    identityVersion: 1,
    user: { tenant_id: '10', sub: 'member', authorization_version: 1, permissions: [] as string[] }
  },
  principals: vi.fn(),
  webhooks: vi.fn(),
  proposals: vi.fn(),
  audit: vi.fn(),
  options: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => reactive(fixture.auth) }))
vi.mock('../../api', () => ({
  api: {
    governance: {
      servicePrincipals: { list: fixture.principals },
      webhooks: { list: fixture.webhooks },
      knowledgeProposals: { pending: fixture.proposals },
      auditLogs: fixture.audit,
      auditOptions: fixture.options
    }
  },
  foundationRequest: vi.fn()
}))
vi.mock('@skillforge/shared/feedback', async (load) => ({
  ...(await load<typeof import('@skillforge/shared/feedback')>()),
  confirm: fixture.confirm
}))
vi.mock('@skillforge/shared/foundation/navigation', async (load) => {
  const actual = await load<typeof import('@skillforge/shared/foundation/navigation')>()
  const { ref } = await import('vue')
  return {
    ...actual,
    useFoundationNavigation: () => ({ menus: ref([]), error: ref(''), reload: vi.fn() })
  }
})
vi.mock('@skillforge/shared/foundation/appearance', async (load) => {
  const actual = await load<typeof import('@skillforge/shared/foundation/appearance')>()
  const { ref } = await import('vue')
  return {
    ...actual,
    useFoundationAppearance: () => ({
      personal: ref(null),
      branding: ref(null),
      loading: ref(false),
      saving: ref(false),
      error: ref(''),
      reload: vi.fn(),
      savePersonal: vi.fn()
    })
  }
})
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  fixture.auth.identityVersion++
  fixture.auth.user.permissions = [
    'foundation.service_principals.read',
    'foundation.service_principals.create',
    'foundation.webhooks.read',
    'foundation.audit.read',
    'foundation.knowledge.read'
  ]
  fixture.principals.mockResolvedValue([])
  fixture.webhooks.mockResolvedValue([])
  fixture.proposals.mockResolvedValue([])
  fixture.options.mockResolvedValue({
    actions: [
      { value: 'auth.login', label: '登录' },
      { value: 'auth.logout', label: '退出登录' }
    ],
    actors: []
  })
  fixture.audit.mockImplementation(async ({ action }) => ({
    items: [{ id: '1', action: action || 'auth.login', message: `已读取 ${action || '全部'}` }]
  }))
  fixture.confirm.mockResolvedValue(false)
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  clearFeedback()
  document.body.innerHTML = ''
})
async function render(path = '/admin/governance') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin',
        component: Layout,
        children: [
          {
            path: 'governance',
            component: GovernancePage,
            meta: applicationRouter.getRoutes().find((r) => r.path === '/admin/governance')!.meta
          },
          { path: 'home', component: { template: '<p>工作台</p>' } }
        ]
      }
    ]
  })
  await router.push(path)
  const wrapper = mount(RouterView, { attachTo: document.body, global: { plugins: [router] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
async function click(wrapper: VueWrapper, label: string) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === label)!
    .trigger('click')
  await flushPromises()
}

it('审计筛选写入成功地址，工作台返回和重建恢复；切标签不重复读取或重建页面', async () => {
  const { wrapper, router } = await render('/admin/governance?source=audit')
  const page = wrapper.getComponent(GovernancePage).element
  await wrapper.get('#tab-audit').trigger('click')
  await flushPromises()
  wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'auth.login')
  await flushPromises()
  expect(router.currentRoute.value.query).toEqual({
    source: 'audit',
    tab: 'audit',
    action: 'auth.login'
  })
  expect(wrapper.getComponent(GovernancePage).element).toBe(page)
  expect(fixture.audit).toHaveBeenCalledTimes(2)
  const location = router.currentRoute.value.fullPath
  await router.push('/admin/home')
  await flushPromises()
  router.back()
  await flushPromises()
  expect(wrapper.get('#tab-audit').attributes('aria-selected')).toBe('true')
  expect(wrapper.getComponent(ElSelect).props('modelValue')).toBe('auth.login')
  expect(wrapper.text()).toContain('已读取 auth.login')
  const fresh = await render(location)
  expect(fresh.wrapper.getComponent(ElSelect).props('modelValue')).toBe('auth.login')
  await wrapper.get('#tab-webhooks').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query).toEqual({
    source: 'audit',
    tab: 'webhooks',
    action: 'auth.login'
  })
  expect(fixture.webhooks).toHaveBeenCalledTimes(1)
  await wrapper.get('#tab-proposals').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query.tab).toBe('proposals')
  expect(fixture.proposals).toHaveBeenCalledTimes(1)
})

it('筛选失败保留成功地址、旧记录和新输入；手动刷新成功后保存新条件，清空条件移除地址参数', async () => {
  const { wrapper, router } = await render('/admin/governance?tab=audit&action=auth.login')
  fixture.audit.mockRejectedValueOnce(new Error('读取失败'))
  wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'auth.logout')
  await flushPromises()
  expect(router.currentRoute.value.query.action).toBe('auth.login')
  expect(wrapper.getComponent(ElSelect).props('modelValue')).toBe('auth.logout')
  expect(wrapper.text()).toContain('已读取 auth.login')
  expect(wrapper.text()).toContain('当前保留上次成功读取的审计记录')
  await click(wrapper, '刷新')
  expect(router.currentRoute.value.query.action).toBe('auth.logout')
  expect(wrapper.text()).toContain('已读取 auth.logout')
  await wrapper.get('.el-select').trigger('mouseenter')
  await wrapper.get('.el-select__clear').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query).toEqual({ tab: 'audit' })
  expect(fixture.audit).toHaveBeenLastCalledWith({ action: undefined, limit: 100 })
})

it('同路径新地址更新筛选；旧读取迟到不会替换当前记录或地址', async () => {
  const { wrapper, router } = await render('/admin/governance?tab=audit&action=auth.login')
  let finish!: (value: { items: { id: string; action: string; message: string }[] }) => void
  fixture.audit.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'auth.logout')
  await flushPromises()
  await router.push('/admin/governance?tab=audit')
  await flushPromises()
  finish({ items: [{ id: 'late', action: 'auth.logout', message: '迟到旧记录' }] })
  await flushPromises()
  expect(router.currentRoute.value.query).toEqual({ tab: 'audit' })
  expect(wrapper.text()).toContain('已读取 全部')
  expect(wrapper.text()).not.toContain('迟到旧记录')
})

it('等待地址同步时禁用下一次查询，避免清空筛选后切标签被旧地址拉回', async () => {
  const { wrapper, router } = await render('/admin/governance?tab=audit&action=auth.login')
  let continueNavigation!: () => void
  router.beforeEach(
    () =>
      new Promise<void>((resolve) => {
        continueNavigation = resolve
      })
  )
  await wrapper.get('.el-select').trigger('mouseenter')
  await wrapper.get('.el-select__clear').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query.action).toBe('auth.login')
  expect(wrapper.getComponent(ElSelect).props('disabled')).toBe(true)
  await wrapper.get('#tab-webhooks').trigger('click')
  await flushPromises()
  expect(wrapper.get('#tab-audit').attributes('aria-selected')).toBe('true')
  expect(fixture.webhooks).not.toHaveBeenCalled()
  expect(
    wrapper
      .findAll('button')
      .find((button) => button.text() === '刷新')!
      .attributes('disabled')
  ).toBeDefined()
  continueNavigation()
  await flushPromises()
  expect(router.currentRoute.value.query).toEqual({ tab: 'audit' })
  expect(wrapper.getComponent(ElSelect).props('disabled')).toBe(false)
  expect(fixture.audit).toHaveBeenCalledTimes(2)
  await wrapper.get('#tab-webhooks').trigger('click')
  await flushPromises()
  expect(wrapper.get('#tab-webhooks').attributes('aria-selected')).toBe('true')
  expect(fixture.webhooks).toHaveBeenCalledTimes(1)
})

it.each(['webhooks', 'invalid'])(
  '受限身份不按地址读取不可见标签 %s，而回到可用审计入口',
  async (tab) => {
    fixture.auth.user.permissions = ['foundation.audit.read']
    const { wrapper } = await render(`/admin/governance?tab=${tab}&action=auth.login`)
    expect(wrapper.get('#tab-audit').attributes('aria-selected')).toBe('true')
    expect(wrapper.find('#tab-webhooks').exists()).toBe(false)
    expect(fixture.webhooks).not.toHaveBeenCalled()
    expect(fixture.principals).not.toHaveBeenCalled()
    expect(fixture.audit).toHaveBeenCalledWith({ action: 'auth.login', limit: 100 })
  }
)

it('同路径切换位置受未保存表单保护，拒绝离开后保持原标签和输入', async () => {
  const { wrapper, router } = await render()
  await click(wrapper, '创建服务身份')
  wrapper
    .getComponent(GovernancePage)
    .getComponent(ElInput)
    .vm.$emit('update:modelValue', '导航保护草稿')
  await flushPromises()
  await router.push('/admin/governance?tab=audit')
  await flushPromises()
  expect(fixture.confirm).toHaveBeenCalled()
  expect(router.currentRoute.value.fullPath).toBe('/admin/governance')
  expect(wrapper.get('#tab-principals').attributes('aria-selected')).toBe('true')
  expect(wrapper.getComponent(GovernancePage).getComponent(ElInput).props('modelValue')).toBe(
    '导航保护草稿'
  )
  expect(fixture.audit).not.toHaveBeenCalled()
})
