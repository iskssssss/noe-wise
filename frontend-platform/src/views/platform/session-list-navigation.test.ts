import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { ElButton, ElPagination } from 'element-plus'
import { clearFeedback } from '@skillforge/shared/feedback'
import SessionsPage from './SessionsPage.vue'
import SessionDetailPage from './SessionDetailPage.vue'
import { readSessionListQuery } from './sessionListLocation'

const mocks = vi.hoisted(() => ({ list: vi.fn(), detail: vi.fn(), rounds: vi.fn() }))
vi.mock('../../api', () => ({
  getSessionId: () => 'actor-session',
  formatToken: (v: string) => v,
  formatCacheHitRatio: () => '—',
  api: {
    sessions: {
      list: mocks.list,
      detail: mocks.detail,
      rounds: mocks.rounds,
      options: async () => [],
      messages: async () => ({ items: [] }),
      usage: async () => ({ attempts: [], coverage: { status: 'COMPLETE' }, cache: null })
    }
  }
}))
vi.mock('../../api/entityOptions', () => ({
  loadTenantOptions: async () => [],
  resolveTenantOption: async (id: string) => ({ id, label: id })
}))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({ allowed: () => false })
}))
let wrapper: VueWrapper
let router: Router
const row = {
  session_id: 'row-session',
  tenant_id: 'row-tenant',
  summary: '菜单巡检',
  user_name: '巡检用户',
  tenant_name: '巡检机构',
  agent_name: '测试助手',
  execution_status: 'COMPLETED'
}
beforeEach(async () => {
  vi.resetAllMocks()
  mocks.list.mockImplementation(async (query) => ({ items: [row], total: 61, page: query.page }))
  mocks.detail.mockResolvedValue({ ...row, message_count: 1 })
  mocks.rounds.mockResolvedValue({ items: [] })
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/platform/sessions', component: SessionsPage },
      { path: '/platform/sessions/detail', component: SessionDetailPage }
    ]
  })
  await router.push(
    '/platform/sessions?list_tenant_id=filter-tenant&list_session_id=filter-session&list_summary=菜单&page=2&size=50'
  )
  await router.isReady()
  wrapper = mount(RouterView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
})
afterEach(() => {
  wrapper.unmount()
  clearFeedback()
  document.body.innerHTML = ''
})

it('详情使用行身份，切换标签再返回仍保留原筛选和页码，重建继续读取相同范围', async () => {
  expect(mocks.list).toHaveBeenLastCalledWith(
    expect.objectContaining({
      tenant_id: 'filter-tenant',
      session_id: 'filter-session',
      summary: '菜单',
      page: 2,
      page_size: 50
    }),
    expect.anything()
  )
  await wrapper.get('a[href*="/platform/sessions/detail"]').trigger('click')
  await flushPromises()
  expect(mocks.detail).toHaveBeenLastCalledWith('row-tenant', 'row-session', expect.anything())
  await wrapper.get('#tab-messages').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query.list_session_id).toBe('filter-session')
  await wrapper
    .findAll('a')
    .find((a) => a.text() === '会话列表')!
    .trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query).toMatchObject({
    list_summary: '菜单',
    page: '2',
    size: '50'
  })
  expect(router.currentRoute.value.query).not.toHaveProperty('sessionId')
  wrapper.unmount()
  wrapper = mount(RouterView, { global: { plugins: [router] } })
  await flushPromises()
  expect(wrapper.get('input[aria-label="摘要关键字"]').element).toHaveProperty('value', '菜单')
  expect(mocks.list).toHaveBeenLastCalledWith(
    expect.objectContaining({ page: 2, page_size: 50, summary: '菜单' }),
    expect.anything()
  )
})

it('未提交输入不污染翻页，翻页失败保留原结果，重试读取原已提交条件', async () => {
  await wrapper.get('input[aria-label="摘要关键字"]').setValue('未提交')
  mocks.list.mockRejectedValueOnce(new Error('读取暂时失败'))
  wrapper.getComponent(ElPagination).vm.$emit('current-change', 3)
  await flushPromises()
  expect(mocks.list).toHaveBeenLastCalledWith(
    expect.objectContaining({ page: 3, summary: '菜单' }),
    expect.anything()
  )
  expect(wrapper.text()).toContain('第 3 页读取失败，当前保留第 2 页的结果')
  expect(wrapper.text()).toContain('菜单巡检')
  expect(wrapper.get('a[href*="/platform/sessions/detail"]').attributes('href')).toContain('page=2')
  await wrapper
    .findAllComponents(ElButton)
    .find((b) => b.text() === '重新读取会话')!
    .trigger('click')
  await flushPromises()
  expect(mocks.list).toHaveBeenLastCalledWith(
    expect.objectContaining({ page: 3, summary: '菜单' }),
    expect.anything()
  )
  expect(wrapper.text()).toContain('筛选条件已修改，点击查询后生效')
})

it('新查询取消旧读取，迟到结果不覆盖；重置清理列表地址与日期', async () => {
  let finish!: (v: unknown) => void
  mocks.list.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  await wrapper.get('input[aria-label="摘要关键字"]').setValue('旧查询')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  const signal = mocks.list.mock.calls.at(-1)![1].signal as AbortSignal
  await wrapper.get('input[aria-label="摘要关键字"]').setValue('新查询')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(signal.aborted).toBe(true)
  finish({ items: [{ ...row, summary: '旧结果' }], total: 1, page: 1 })
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧结果')
  expect(router.currentRoute.value.query.list_summary).toBe('新查询')
  await wrapper
    .findAllComponents(ElButton)
    .find((b) => b.text() === '重置')!
    .trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query).toEqual({})
  expect(mocks.list).toHaveBeenLastCalledWith(
    expect.objectContaining({ summary: '', started_from: undefined, page: 1 }),
    expect.anything()
  )
})

it('地址入口保留字符串身份和带偏移时间，非法分页恢复为默认值', () => {
  expect(
    readSessionListQuery({
      list_tenant_id: '900719925474099312345',
      tenantId: 'detail-tenant',
      page: '-2',
      size: '21',
      list_started_from: '2026-11-20T00:00:00+08:00',
      list_started_to: '2026-11-21T00:00:00+08:00'
    })
  ).toMatchObject({
    tenant_id: '900719925474099312345',
    page: 1,
    page_size: 20,
    started_from: '2026-11-20T00:00:00+08:00',
    started_to: '2026-11-21T00:00:00+08:00'
  })
})
