import { defineComponent, h, reactive } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import ElementPlus, { ElPagination, ElSelect } from 'element-plus'
import OperationsPage from './OperationsPage.vue'
import FoundationOptionSelect from '../FoundationOptionSelect.vue'
import type { OperationsRequest } from './contracts'
import { clearFeedback } from '../../feedback'

const wrappers: VueWrapper[] = []
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
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  clearFeedback()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})
async function render(prefix: string, path: string) {
  const calls: { method: string; path: string; body?: unknown }[] = []
  const control = {
    fail: false,
    delay: undefined as ((value: unknown) => void) | undefined,
    hold: false
  }
  const permissions = reactive(
    ['audit_read', 'audit_export'].map((x) => 'foundation.operations.' + x)
  )
  const request: OperationsRequest = async <T>(method: string, path: string, body?: unknown) => {
    calls.push({ method, path, body })
    if (method === 'POST') throw new Error('受控导出失败，不生成文件')
    if (path.endsWith('/audit/options'))
      return {
        actions: [{ value: 'auth.login', label: '登录' }],
        categories: [{ value: 'authentication', label: '认证' }],
        outcomes: [
          { value: 'success', label: '成功' },
          { value: 'failure', label: '失败' }
        ],
        resource_types: [{ value: 'member', label: '成员' }]
      } as T
    if (path.includes('/options/actors'))
      return {
        items: [{ id: 'actor:001', name: '巡检身份', code: 'actor:001', status: null }],
        has_more: false
      } as T
    if (path.includes('/options/traces')) return { items: [], has_more: false } as T
    if (control.fail) throw new Error('读取中断')
    if (control.hold) {
      control.hold = false
      return (await new Promise((resolve) => {
        control.delay = resolve
      })) as T
    }
    const params = new URL(path, 'http://fixture').searchParams
    const page = Number(params.get('page'))
    const outcome = params.get('outcome') || 'all'
    return {
      items: [
        {
          id: outcome + '-' + page,
          message: outcome + '第' + page + '页',
          actor_id: 'actor:001',
          action: 'auth.login',
          outcome: 'success',
          category: 'authentication',
          resource_type: 'member',
          created_at: '2026-11-26T08:00:00Z'
        }
      ],
      total: 150,
      page,
      page_size: 50
    } as T
  }
  const pageComponent = defineComponent({
    setup: () => () => h(OperationsPage, { request, apiBase: prefix, permissions })
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/operations', component: pageComponent },
      { path: '/away', component: { template: '<p>其他页面</p>' } }
    ]
  })
  await router.push(path)
  const wrapper = mount(RouterView, {
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router, calls, control, permissions }
}
async function click(wrapper: VueWrapper, text: string) {
  await wrapper
    .findAll('button')
    .find((x) => x.text() === text)!
    .trigger('click')
  await flushPromises()
}
async function search(wrapper: VueWrapper) {
  await wrapper.get('form.filters').trigger('submit')
  await flushPromises()
}
function outcome(wrapper: VueWrapper, value: string) {
  wrapper
    .findAllComponents(ElSelect)
    .find(
      (x) =>
        x.findAllComponents(FoundationOptionSelect).length === 0 &&
        x.props('modelValue') === 'success'
    )
    ?.vm.$emit('update:modelValue', value)
}
it.each(['/tenant/foundation/operations', '/admin/foundation/operations'])(
  '宿主%s恢复筛选、页码、时间和身份名称，离开返回及重建保持同一查询',
  async (prefix) => {
    const path =
      '/operations?outcome=success&actor_id=actor%3A001&page=2&advanced=1&from=2026-11-25T00%3A00%3A00.000Z&source=audit'
    const { wrapper, router, calls } = await render(prefix, path)
    expect(wrapper.text()).toContain('success第2页')
    expect(wrapper.text()).toContain('巡检身份')
    expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(2)
    expect(wrapper.findAllComponents(FoundationOptionSelect)[0]!.props('modelValue')).toBe(
      'actor:001'
    )
    expect(
      new Date(
        (wrapper.get('input[type="datetime-local"]').element as HTMLInputElement).value
      ).toISOString()
    ).toBe('2026-11-25T00:00:00.000Z')
    expect(calls.filter((c) => c.path.includes('/audit/summaries?'))).toHaveLength(1)
    await router.push('/away')
    router.back()
    await flushPromises()
    expect(wrapper.text()).toContain('success第2页')
    expect(wrapper.text()).toContain('收起诊断条件')
    const fresh = await render(prefix, router.currentRoute.value.fullPath)
    expect(fresh.wrapper.text()).toContain('success第2页')
    expect(fresh.router.currentRoute.value.query.source).toBe('audit')
  }
)
it('未检索输入不改变翻页或导出范围；失败保留旧页、URL和新输入，重试后才保存新条件', async () => {
  const { wrapper, router, calls, control } = await render(
    '/tenant/foundation/operations',
    '/operations?outcome=success&page=2'
  )
  const select = wrapper.findAllComponents(ElSelect)[1]!
  select.vm.$emit('update:modelValue', 'failure')
  wrapper.getComponent(ElPagination).vm.$emit('current-change', 3)
  await flushPromises()
  expect(wrapper.text()).toContain('success第3页')
  expect(select.props('modelValue')).toBe('failure')
  expect(router.currentRoute.value.query).toEqual({ outcome: 'success', page: '3' })
  control.fail = true
  await search(wrapper)
  expect(wrapper.text()).toContain('success第3页')
  expect(wrapper.text()).toContain('翻页和导出仍使用该范围')
  expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(3)
  expect(router.currentRoute.value.query).toEqual({ outcome: 'success', page: '3' })
  await click(wrapper, '导出当前范围')
  expect(calls.findLast((c) => c.method === 'POST')!.body).toEqual({ outcome: 'success' })
  control.fail = false
  await search(wrapper)
  expect(router.currentRoute.value.query).toEqual({ outcome: 'failure' })
  expect(wrapper.text()).toContain('failure第1页')
  await click(wrapper, '重置')
  expect(router.currentRoute.value.query).toEqual({})
  expect(wrapper.text()).toContain('all第1页')
})
it('高级条件展开保存但不检索，URL变化接管查询，迟到旧结果不覆盖新页', async () => {
  const { wrapper, router, calls, control } = await render(
    '/tenant/foundation/operations',
    '/operations'
  )
  await click(wrapper, '更多条件')
  expect(router.currentRoute.value.query.advanced).toBe('1')
  expect(calls.filter((c) => c.path.includes('/audit/summaries?'))).toHaveLength(1)
  control.hold = true
  await search(wrapper)
  await router.replace('/operations?outcome=failure&page=2&advanced=1')
  await flushPromises()
  control.delay!({
    items: [{ id: 'late', message: '迟到旧结果' }],
    total: 1,
    page: 1,
    page_size: 50
  })
  await flushPromises()
  expect(wrapper.text()).toContain('failure第2页')
  expect(wrapper.text()).not.toContain('迟到旧结果')
  expect(router.currentRoute.value.query.page).toBe('2')
})
it('地址同步等待期间不接受第二次查询；地址不能绕过当前审计权限', async () => {
  const { wrapper, router, calls, permissions } = await render(
    '/tenant/foundation/operations',
    '/operations?outcome=success'
  )
  const initialCalls = calls.length
  permissions.splice(0, permissions.length, ...permissions)
  await flushPromises()
  expect(calls).toHaveLength(initialCalls)
  let resume!: () => void
  router.beforeEach(
    () =>
      new Promise<void>((resolve) => {
        resume = resolve
      })
  )
  outcome(wrapper, 'failure')
  await search(wrapper)
  expect(wrapper.getComponent(ElPagination).props('disabled')).toBe(true)
  const count = calls.filter((c) => c.path.includes('/audit/summaries?')).length
  await search(wrapper)
  expect(calls.filter((c) => c.path.includes('/audit/summaries?'))).toHaveLength(count)
  resume()
  await flushPromises()
  expect(router.currentRoute.value.query.outcome).toBe('failure')
  permissions.splice(0)
  await flushPromises()
  expect(wrapper.text()).toContain('当前账号没有此页面权限')
  expect(wrapper.find('form.filters').exists()).toBe(false)
})
