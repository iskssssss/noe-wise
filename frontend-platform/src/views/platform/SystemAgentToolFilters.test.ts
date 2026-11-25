import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { ElButton, ElSelect } from 'element-plus'
import { clearFeedback } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import SystemAgentToolsPage from './SystemAgentToolsPage.vue'

const mocks = vi.hoisted(() => ({ list: vi.fn(), options: vi.fn(), directory: vi.fn() }))
vi.mock('../../api', () => ({
  api: {
    systemAgentTools: { list: mocks.list, options: mocks.options },
    systemAgents: { mountOptions: mocks.directory }
  }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => reactive({ identityVersion: 1, user: { sub: 'test' } })
}))
vi.mock('../../composables/actionPermissions', () => ({
  useActionPermissions: () => ({ allowed: (p: string) => p.endsWith('.read'), checked: vi.fn() })
}))
let wrapper: VueWrapper
let router: Router
const row = {
  id: '1',
  name: '计算测试部署',
  tool_definition_id: 't1',
  provider_ref: 'calculate',
  environment: 'TEST',
  active: false
}
beforeEach(async () => {
  vi.resetAllMocks()
  mocks.list.mockResolvedValue([row])
  mocks.options.mockResolvedValue({
    environments: [
      { value: 'TEST', label: '测试' },
      { value: 'PRODUCTION', label: '生产' }
    ],
    statuses: [{ value: 'false', label: '停用' }]
  })
  mocks.directory.mockResolvedValue({ tools: [{ id: 't1', name: '计算表达式' }] })
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/platform/system-agent-tools', component: SystemAgentToolsPage },
      { path: '/elsewhere', component: { template: '<p>另一页面</p>' } }
    ]
  })
  await router.push(
    '/platform/system-agent-tools?list_environment=TEST&list_active=false&list_search=计算'
  )
  await router.isReady()
  wrapper = mount(RouterView, {
    attachTo: document.body,
    global: { plugins: [router], directives: { permission: () => {} } }
  })
  await flushPromises()
})
afterEach(() => {
  wrapper.unmount()
  clearFeedback()
  document.body.innerHTML = ''
})
const submit = async () => {
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

it('从地址恢复查询，未提交输入不改变刷新条件，跨页返回和重建保留已应用条件', async () => {
  expect(mocks.list).toHaveBeenLastCalledWith(
    { environment: 'TEST', active: 'false' },
    expect.objectContaining({ signal: expect.any(AbortSignal) })
  )
  expect(wrapper.get('input[aria-label="搜索工具部署"]').element).toHaveProperty('value', '计算')
  await wrapper.get('input[aria-label="搜索工具部署"]').setValue('未提交')
  await wrapper
    .findAllComponents(ElButton)
    .find((b) => b.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('计算测试部署')
  expect(router.currentRoute.value.query.list_search).toBe('计算')
  await router.push('/elsewhere')
  router.back()
  await vi.waitFor(() =>
    expect(router.currentRoute.value.path).toBe('/platform/system-agent-tools')
  )
  await flushPromises()
  expect(wrapper.get('input[aria-label="搜索工具部署"]').element).toHaveProperty('value', '计算')
  wrapper.unmount()
  wrapper = mount(RouterView, {
    global: { plugins: [router], directives: { permission: () => {} } }
  })
  await flushPromises()
  expect(wrapper.get('input[aria-label="搜索工具部署"]').element).toHaveProperty('value', '计算')
})

it('新查询取消旧读取，旧响应不能覆盖新结果，重置清理地址和筛选', async () => {
  let complete!: (rows: (typeof row)[]) => void
  mocks.list.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        complete = resolve
      })
  )
  await wrapper.get('input[aria-label="搜索工具部署"]').setValue('旧查询')
  await submit()
  const signal = mocks.list.mock.calls.at(-1)![1].signal as AbortSignal
  await wrapper.get('input[aria-label="搜索工具部署"]').setValue('计算')
  await submit()
  expect(signal.aborted).toBe(true)
  expect(wrapper.text()).toContain('计算测试部署')
  complete([{ ...row, name: '旧查询迟到结果' }])
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧查询迟到结果')
  await wrapper
    .findAllComponents(ElButton)
    .find((b) => b.text() === '重置')!
    .trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query).toEqual({})
  expect(mocks.list).toHaveBeenLastCalledWith({ environment: '', active: '' }, expect.anything())
})

it('选项读取失败保留列表并可刷新恢复；非法URL条件交由服务端拒绝', async () => {
  mocks.options.mockRejectedValueOnce(new Error('筛选选项不可用'))
  await wrapper
    .findAllComponents(ElButton)
    .find((b) => b.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('计算测试部署')
  expect(wrapper.text()).toContain('部分资料未能刷新')
  await wrapper
    .findAllComponents(ElButton)
    .find((b) => b.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('部分资料未能刷新')
  expect(wrapper.findAllComponents(ElSelect)[0].props('disabled')).toBe(false)
  mocks.list.mockRejectedValueOnce(
    new HttpClientError({
      status: 422,
      code: 'system_tool_filter_invalid',
      userMessage: '提交内容未通过校验，请检查必填项和字段格式'
    })
  )
  await router.replace('/platform/system-agent-tools?list_environment=STAGING')
  await flushPromises()
  expect(mocks.list).toHaveBeenLastCalledWith(
    { environment: 'STAGING', active: '' },
    expect.anything()
  )
  expect(wrapper.text()).not.toContain('计算测试部署')
  expect(wrapper.text()).toContain('资料读取失败')
  expect(document.querySelector('.el-message--error')?.textContent).toContain(
    '工具部署筛选条件无效，请重新选择或重置后查询'
  )
})

it('能力详情嵌入不消费列表地址条件或请求列表筛选选项', async () => {
  wrapper.unmount()
  vi.clearAllMocks()
  wrapper = mount(SystemAgentToolsPage, {
    props: { toolDefinitionId: 't1' },
    global: { plugins: [router], directives: { permission: () => {} } }
  })
  await flushPromises()
  expect(mocks.list).toHaveBeenCalledWith({ environment: '', active: '' }, expect.anything())
  expect(mocks.options).not.toHaveBeenCalled()
  expect(router.currentRoute.value.query.list_environment).toBe('TEST')
})
