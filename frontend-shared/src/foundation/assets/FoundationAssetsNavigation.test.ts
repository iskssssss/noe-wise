import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElPagination, ElSelect } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import FoundationPage from '../FoundationPage.vue'
import { confirm } from '../../feedback'
import { HttpClientError } from '../../utils/http'
import type { SecurityApi, SecurityAuthApi } from '../security/api'
import type { FoundationJob } from './contracts'

vi.mock('../../feedback', async (original) => ({
  ...(await original<typeof import('../../feedback')>()),
  confirm: vi.fn()
}))

const jobs: FoundationJob[] = Array.from({ length: 27 }, (_, index) => ({
  id: `job-${index}`,
  handler: 'members',
  operation: 'export',
  state: 'succeeded',
  total_rows: 2,
  completed_rows: 2,
  error_rows: 0,
  error_message: null,
  result_file_id: null,
  created_at: '2026-11-25T08:00:00Z',
  expires_at: null
}))
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.clearAllMocks()
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
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})
function read(_method: string, path: string): unknown {
  const url = new URL(path, 'http://fixture')
  if (path.endsWith('/handlers'))
    return [
      {
        code: 'members',
        title: '成员资料',
        columns: ['name'],
        can_import: false,
        can_export: true,
        option_fields: [{ code: 'department', label: '部门', hint: '部门范围', required: true }]
      }
    ]
  if (url.pathname.endsWith('/errors')) return []
  if (url.pathname.endsWith('/detail')) {
    const job = jobs.find((item) => item.id === url.searchParams.get('job_id'))
    if (!job) throw new Error('任务不存在或不可访问')
    return job
  }
  const page = Number(url.searchParams.get('page') || 1)
  const size = Number(url.searchParams.get('page_size') || 20)
  const items = url.pathname.endsWith('/files') ? [] : jobs
  return {
    items: items.slice((page - 1) * size, page * size),
    total: items.length,
    page,
    page_size: size
  }
}
async function render(
  initial = '/assets',
  permissions = ['foundation.jobs.read', 'foundation.jobs.create', 'foundation.files.read'],
  request = vi.fn(async (method: string, path: string) => read(method, path))
) {
  const props = {
    request,
    platform: false,
    permissions,
    securityApi: {} as SecurityApi,
    securityAuthApi: {} as SecurityAuthApi,
    onAuthentication: async () => {},
    onLogout: () => {}
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/assets', component: FoundationPage, props: { ...props, area: 'assets' } },
      { path: '/jobs', component: FoundationPage, props: { ...props, area: 'jobs' } },
      { path: '/home', component: { template: '<h1>工作台</h1>' } }
    ]
  })
  await router.push(initial)
  await router.isReady()
  const wrapper = mount(RouterView, {
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router, request }
}
function button(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find((item) => item.text() === label)!
}
function tab(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('[role="tab"]').find((item) => item.text() === label)!
}

describe('文件和任务真实路由装配', () => {
  it('返回和重新挂载恢复任务页签、分页与页外详情，全程只读', async () => {
    const { wrapper, router, request } = await render()
    await tab(wrapper, '导入导出与任务').trigger('click')
    await flushPromises()
    wrapper.getComponent(ElPagination).vm.$emit('size-change', 10)
    await flushPromises()
    wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    await button(wrapper, '详情').trigger('click')
    await flushPromises()
    expect(wrapper.get('.el-descriptions').text()).toContain('job-10')
    wrapper.getComponent(ElPagination).vm.$emit('current-change', 3)
    await flushPromises()
    const location = router.currentRoute.value.fullPath
    expect(router.currentRoute.value.query).toEqual({
      tab: 'jobs',
      jobs_page: '3',
      jobs_page_size: '10',
      job_id: 'job-10'
    })
    await router.push('/home')
    router.back()
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('文件与管理任务')
    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toBe('导入导出与任务')
    expect(wrapper.getComponent(ElPagination).props()).toMatchObject({
      currentPage: 3,
      pageSize: 10
    })
    expect(wrapper.get('.el-descriptions').text()).toContain('job-10')
    expect(request.mock.calls.some(([, path]) => path.includes('/detail?job_id=job-10'))).toBe(true)
    wrapper.unmount()
    const restored = await render(location, undefined, request)
    expect(restored.wrapper.get('.el-descriptions').text()).toContain('job-10')
    expect(restored.wrapper.find('form')?.text()).not.toContain('查看已受理任务')
    expect(request.mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })

  it('不可读任务保留重试说明，恢复后重读；选择变化立即清旧详情', async () => {
    let denied = true
    const request = vi.fn(async (method: string, path: string) => {
      if (path.includes('/detail?job_id=job-0') && denied) throw new Error('任务不可访问')
      return read(method, path)
    })
    const { wrapper, router } = await render(
      '/assets?tab=jobs&jobs_page=2&job_id=job-0',
      undefined,
      request
    )
    expect(wrapper.find('.el-descriptions').exists()).toBe(false)
    expect(wrapper.text()).toContain('任务详情尚未读取成功，请刷新任务后重试')
    expect(document.querySelector('.el-message--error')?.textContent).toContain('任务不可访问')
    expect(document.querySelectorAll('.el-message--error')).toHaveLength(1)
    expect(request.mock.calls.some(([, path]) => path.includes('/errors?'))).toBe(false)
    denied = false
    await button(wrapper, '刷新任务').trigger('click')
    await flushPromises()
    expect(wrapper.get('.el-descriptions').text()).toContain('job-0')
    await router.replace({ query: { ...router.currentRoute.value.query, job_id: 'missing' } })
    await flushPromises()
    expect(wrapper.find('.el-descriptions').exists()).toBe(false)
    expect(wrapper.text()).toContain('任务详情尚未读取成功')
    expect(request.mock.calls.every(([method]) => method === 'GET')).toBe(true)
  })

  it('草稿与未知提交在历史详情和分页时保留，取消切页签不改变地址或输入', async () => {
    const request = vi.fn(async (method: string, path: string) => {
      if (method !== 'GET') throw new HttpClientError({ status: 0, userMessage: '原回执未知' })
      return read(method, path)
    })
    const { wrapper, router } = await render('/assets?tab=jobs', undefined, request)
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'members')
    await flushPromises()
    await wrapper.get('input[placeholder="部门范围"]').setValue('draft-department')
    await button(wrapper, '详情').trigger('click')
    await flushPromises()
    expect(confirm).not.toHaveBeenCalled()
    expect((wrapper.get('input[placeholder="部门范围"]').element as HTMLInputElement).value).toBe(
      'draft-department'
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('原提交结果尚未确认')
    wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(confirm).not.toHaveBeenCalled()
    const location = router.currentRoute.value.fullPath
    vi.mocked(confirm).mockResolvedValueOnce(false)
    await tab(wrapper, '公共文件').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe(location)
    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toBe('导入导出与任务')
    expect(wrapper.text()).toContain('原提交结果尚未确认')
    expect(confirm).toHaveBeenLastCalledWith(
      expect.objectContaining({ content: expect.stringContaining('原提交结果尚未确认') })
    )
    expect(location).not.toContain('draft-department')
    expect(request.mock.calls.filter(([method]) => method !== 'GET')).toHaveLength(1)
  })

  it('入口标题固定，地址不能绕过页签读取权限', async () => {
    const { wrapper, request } = await render('/assets?tab=jobs&job_id=private', [
      'foundation.files.read'
    ])
    expect(wrapper.get('h1').text()).toBe('文件与管理任务')
    expect(wrapper.get('[role="tab"][aria-selected="true"]').text()).toBe('公共文件')
    expect(request.mock.calls.some(([, path]) => path.includes('/jobs'))).toBe(false)
    wrapper.unmount()
    const legacy = await render('/jobs', ['foundation.jobs.read'])
    expect(legacy.wrapper.get('h1').text()).toBe('管理任务')
    expect(legacy.wrapper.find('form').exists()).toBe(false)
    expect(legacy.wrapper.findAll('[role="tab"]')).toHaveLength(1)
  })
})
