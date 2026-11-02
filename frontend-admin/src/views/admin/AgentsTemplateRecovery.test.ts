import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElForm, ElSelect, ElFormItem, ElInput } from 'element-plus'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { confirmPageLeave } from '@skillforge/shared/admin/leave'
import { clearFeedback, confirm } from '@skillforge/shared/feedback'
import AgentsPage from './AgentsPage.vue'
import { useAuthStore } from '../../stores/auth'

const mocks = vi.hoisted(() => ({
  install: vi.fn(),
  result: vi.fn(),
  creationResult: vi.fn(),
  push: vi.fn(),
  templates: vi.fn()
}))
vi.mock('../../stores/auth', async () => {
  const { reactive } = await import('vue')
  const state = reactive({
    identityVersion: 1,
    user: { tenant_id: '1', sub: 'actor-1', permissions: [] as string[] }
  })
  return { useAuthStore: () => state }
})
vi.mock('../../api', () => ({
  api: {
    agents: {
      list: async () => ({ items: [] }),
      templates: mocks.templates,
      templateDetail: async () => ({ name: '客服模板', definition: {} }),
      installTemplate: mocks.install,
      installationResult: mocks.result,
      creationResult: mocks.creationResult
    },
    capabilities: { listDefinitions: async () => [], deployments: async () => [] },
    workflowDrafts: { instances: async () => ({ items: [] }) }
  }
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: vi.fn()
}))
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  useAuthStore().user!.sub = 'actor-1'
  useAuthStore().user!.permissions = []
  useAuthStore().identityVersion = 1
  mocks.templates.mockResolvedValue({
    items: [{ id: 'template-1', name: '客服模板', version: '1' }]
  })
  vi.mocked(confirm).mockResolvedValue(false)
  mocks.install.mockResolvedValue({ agent: { id: 'installed-agent' } })
})
afterEach(() => {
  wrapper?.unmount()
  clearFeedback()
  document.body.innerHTML = ''
})
function button(label: string) {
  return wrapper!.findAllComponents(ElButton).find((item) => item.text() === label)!
}
async function open() {
  wrapper = mount(AgentsPage, { attachTo: document.body, global: { stubs: { RouterLink: true } } })
  await flushPromises()
  await button('从模板安装').trigger('click')
  await flushPromises()
  const drawer = wrapper
    .findAllComponents(ElDrawer)
    .find((item) => item.props('title') === '从模板安装')!
  const template = drawer.getComponent(ElSelect)
  template.vm.$emit('update:modelValue', 'template-1')
  template.vm.$emit('change', 'template-1')
  await flushPromises()
  return drawer
}

describe('模板安装原请求核对', () => {
  it('重新进入时自动核对原安装并进入已创建智能体', async () => {
    useAuthStore().user!.permissions = ['foundation.agents.read']
    const key = `skillforge.agent-template-install:${encodeURIComponent(JSON.stringify(['1', 'actor-1']))}`
    sessionStorage.setItem(key, 'original-request')
    mocks.result.mockResolvedValueOnce({ request_id: 'original-request', agent_id: '123' })
    wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } }
    })
    await flushPromises()
    expect(mocks.result).toHaveBeenCalledExactlyOnceWith('original-request')
    expect(mocks.install).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(mocks.push).toHaveBeenCalledWith('/admin/agents/123')
  })
  it('自动核对未找到回执时保留请求并允许手动核对', async () => {
    useAuthStore().user!.permissions = ['foundation.agents.read']
    const key = `skillforge.agent-template-install:${encodeURIComponent(JSON.stringify(['1', 'actor-1']))}`
    sessionStorage.setItem(key, 'original-request')
    mocks.result.mockResolvedValueOnce(undefined)
    wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } }
    })
    await flushPromises()
    expect(mocks.result).toHaveBeenCalledExactlyOnceWith('original-request')
    expect(sessionStorage.getItem(key)).toBe('original-request')
    expect(button('核对原安装结果').exists()).toBe(true)
    expect(mocks.install).not.toHaveBeenCalled()
  })

  it('同时恢复创建与安装请求时顺序核对两条原回执', async () => {
    useAuthStore().user!.permissions = ['foundation.agents.read']
    const owner = encodeURIComponent(JSON.stringify(['1', 'actor-1']))
    const creationId = '12345678-1234-4234-8234-123456789abc'
    sessionStorage.setItem(
      `skillforge.agent-create:${owner}`,
      JSON.stringify({ requestId: creationId, name: '助手' })
    )
    sessionStorage.setItem(`skillforge.agent-template-install:${owner}`, 'original-request')
    mocks.creationResult.mockResolvedValueOnce(undefined)
    mocks.result.mockResolvedValueOnce(undefined)
    wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } }
    })
    await flushPromises()
    expect(mocks.creationResult).toHaveBeenCalledExactlyOnceWith(creationId)
    expect(mocks.result).toHaveBeenCalledExactlyOnceWith('original-request')
    expect(mocks.creationResult.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.result.mock.invocationCallOrder[0]!
    )
    expect(wrapper.text()).toContain(creationId)
    expect(wrapper.text()).toContain('original-request')
    expect(mocks.install).not.toHaveBeenCalled()
  })
  it('刷新后只读查询原回执，查不到时保持锁定且不重发安装', async () => {
    mocks.install.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '结果未知' }))
    await open()
    await button('安装并配置').trigger('click')
    await flushPromises()
    const requestId = mocks.install.mock.calls[0]![0].request_id
    wrapper!.unmount()
    wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } }
    })
    await flushPromises()
    expect(wrapper.text()).toContain(requestId)
    expect(button('从模板安装').props('disabled')).toBe(true)
    mocks.result
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce({ request_id: requestId, agent_id: '123' })
    await button('核对原安装结果').trigger('click')
    await flushPromises()
    expect(button('从模板安装').props('disabled')).toBe(true)
    await button('核对原安装结果').trigger('click')
    await flushPromises()
    expect(mocks.result).toHaveBeenCalledTimes(2)
    expect(mocks.install).toHaveBeenCalledTimes(1)
    expect(mocks.push).toHaveBeenCalledWith('/admin/agents/123')
    expect(sessionStorage.length).toBe(0)
  })
  it('浏览器存储不可写时不发送安装命令', async () => {
    await open()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
      throw Error('unavailable')
    })
    await button('安装并配置').trigger('click')
    await flushPromises()
    expect(mocks.install).not.toHaveBeenCalled()
    expect(wrapper!.text()).toContain('尚未发送安装请求')
  })
  it('回执已取得但本地记录无法清除时保留原请求并可再次核对', async () => {
    mocks.install.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '结果未知' }))
    await open()
    await button('安装并配置').trigger('click')
    await flushPromises()
    const requestId = mocks.install.mock.calls[0]![0].request_id
    mocks.result.mockResolvedValue({ request_id: requestId, agent_id: '123' })
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementationOnce(() => {
      throw Error('unavailable')
    })
    await button('核对原安装结果').trigger('click')
    await flushPromises()
    expect(wrapper!.text()).toContain('无法清除原安装记录')
    expect(mocks.push).not.toHaveBeenCalled()
    await button('核对原安装结果').trigger('click')
    await flushPromises()
    expect(mocks.push).toHaveBeenCalledWith('/admin/agents/123')
    expect(mocks.install).toHaveBeenCalledTimes(1)
  })
  it('切换主体关闭原表单并按主体恢复原请求', async () => {
    mocks.install.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '结果未知' }))
    const drawer = await open()
    await button('安装并配置').trigger('click')
    await flushPromises()
    const requestId = mocks.install.mock.calls[0]![0].request_id
    useAuthStore().user!.sub = 'actor-2'
    useAuthStore().identityVersion++
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(false)
    expect(wrapper!.text()).not.toContain(requestId)
    useAuthStore().user!.sub = 'actor-1'
    useAuthStore().identityVersion++
    await flushPromises()
    expect(wrapper!.text()).toContain(requestId)
    expect(button('从模板安装').props('disabled')).toBe(true)
    expect(mocks.install).toHaveBeenCalledTimes(1)
  })
  it('模板为空时可直接进入新建，不发起安装请求', async () => {
    mocks.templates.mockResolvedValueOnce({ items: [] })
    wrapper = mount(AgentsPage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } }
    })
    await flushPromises()
    await button('从模板安装').trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((item) => item.props('title') === '从模板安装')!
    expect(document.body.textContent).toContain('暂无可安装模板')
    await drawer
      .findAllComponents(ElButton)
      .find((item) => item.text() === '新建智能体')!
      .trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(false)
    expect(
      wrapper
        .findAllComponents(ElDrawer)
        .find((item) => item.props('title') === '新建智能体')!
        .props('modelValue')
    ).toBe(true)
    expect(mocks.install).not.toHaveBeenCalled()
  })
  it('切换模板时取消保留已编辑的名称，确认后才切换', async () => {
    const drawer = await open()
    const name = drawer
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '智能体名称')!
      .getComponent(ElInput)
    await name.get('input').setValue('自定义客服')
    const select = drawer.getComponent(ElSelect)
    select.vm.$emit('update:modelValue', 'template-2')
    select.vm.$emit('change', 'template-2')
    await flushPromises()
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(select.props('modelValue')).toBe('template-1')
    expect(name.props('modelValue')).toBe('自定义客服')
    vi.mocked(confirm).mockResolvedValueOnce(true)
    select.vm.$emit('update:modelValue', 'template-2')
    select.vm.$emit('change', 'template-2')
    await flushPromises()
    expect(select.props('modelValue')).toBe('template-2')
    expect(name.props('modelValue')).toBe('客服模板')
    expect(mocks.install).not.toHaveBeenCalled()
  })

  it('切换模板确认期间不能安装或关闭原草稿', async () => {
    const drawer = await open()
    const name = drawer
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '智能体名称')!
      .getComponent(ElInput)
    await name.get('input').setValue('自定义客服')
    let complete!: (value: boolean) => void
    vi.mocked(confirm).mockReturnValueOnce(
      new Promise((resolve) => {
        complete = resolve
      })
    )
    const select = drawer.getComponent(ElSelect)
    select.vm.$emit('update:modelValue', 'template-2')
    select.vm.$emit('change', 'template-2')
    await flushPromises()
    expect(button('安装并配置').props('disabled')).toBe(true)
    expect(
      drawer
        .findAllComponents(ElButton)
        .find((item) => item.text() === '取消')!
        .props('disabled')
    ).toBe(true)
    expect(drawer.props('showClose')).toBe(false)
    expect(mocks.install).not.toHaveBeenCalled()
    complete(false)
    await flushPromises()
    expect(select.props('modelValue')).toBe('template-1')
    expect(name.props('modelValue')).toBe('自定义客服')
  })

  it('响应断连后冻结输入与原命令，显式核对返回同一安装结果', async () => {
    mocks.install.mockRejectedValueOnce(
      new HttpClientError({ status: 0, userMessage: '结果尚未确认' })
    )
    const drawer = await open()
    await button('安装并配置').trigger('click')
    await flushPromises()
    const original = structuredClone(mocks.install.mock.calls[0]![0])
    expect(drawer.getComponent(ElForm).props('disabled')).toBe(true)
    expect(button('安装并配置').props('disabled')).toBe(true)
    expect(drawer.props('showClose')).toBe(true)
    expect(document.body.textContent).toContain('安装结果尚未确认')
    expect(mocks.install).toHaveBeenCalledTimes(1)
    await button('继续原安装').trigger('click')
    await flushPromises()
    expect(mocks.install).toHaveBeenCalledTimes(2)
    expect(mocks.install.mock.calls[1]![0]).toEqual(original)
    expect(mocks.push).toHaveBeenCalledWith('/admin/agents/installed-agent')
    expect(wrapper!.text()).not.toContain('安装结果尚未确认')
  })
  it('核对被拒绝不能清除原 UNKNOWN，后续仍核对相同命令', async () => {
    mocks.install
      .mockRejectedValueOnce(new HttpClientError({ status: 503, userMessage: '服务暂不可用' }))
      .mockRejectedValueOnce(new HttpClientError({ status: 403, userMessage: '权限已变化' }))
    await open()
    await button('安装并配置').trigger('click')
    await flushPromises()
    await button('继续原安装').trigger('click')
    await flushPromises()
    expect(button('安装并配置').props('disabled')).toBe(true)
    expect(mocks.push).not.toHaveBeenCalled()
    await button('继续原安装').trigger('click')
    await flushPromises()
    expect(mocks.install.mock.calls.map(([command]) => command.request_id)).toEqual([
      mocks.install.mock.calls[0]![0].request_id,
      mocks.install.mock.calls[0]![0].request_id,
      mocks.install.mock.calls[0]![0].request_id
    ])
  })
  it('初次收到明确校验拒绝时允许修正，不标记为结果未知', async () => {
    mocks.install.mockRejectedValueOnce(
      new HttpClientError({
        status: 422,
        code: 'AGENT_INSTALL_REJECTED',
        userMessage: '请检查配置'
      })
    )
    const drawer = await open()
    await button('安装并配置').trigger('click')
    await flushPromises()
    expect(drawer.getComponent(ElForm).props('disabled')).toBe(false)
    expect(button('安装并配置').props('disabled')).toBe(false)
    expect(wrapper!.text()).not.toContain('继续原安装')
    expect(mocks.install).toHaveBeenCalledTimes(1)
  })
  it('未知结果可取消关闭或明确关闭，重开后继续核对同一请求', async () => {
    mocks.install
      .mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '结果尚未确认' }))
      .mockRejectedValueOnce(new HttpClientError({ status: 503, userMessage: '核对尚未完成' }))
    const drawer = await open()
    await button('安装并配置').trigger('click')
    await flushPromises()
    const original = structuredClone(mocks.install.mock.calls[0]![0])
    await button('关闭核对').trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(true)
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(confirm).toHaveBeenLastCalledWith(
      expect.objectContaining({
        content: expect.stringContaining(original.request_id),
        confirmText: '保留原请求并关闭'
      })
    )
    expect(vi.mocked(confirm).mock.calls[0]![0].content).toContain('离开不会撤销')
    vi.mocked(confirm).mockResolvedValueOnce(true)
    await button('关闭核对').trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(false)
    expect(mocks.install).toHaveBeenCalledTimes(1)
    await button('从模板安装').trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(true)
    expect(button('安装并配置').props('disabled')).toBe(true)
    expect(document.body.textContent).toContain(original.request_id)
    await button('继续原安装').trigger('click')
    await flushPromises()
    expect(mocks.install.mock.calls[1]![0]).toEqual(original)
  })
  it('未知结果的路由离开可取消或确认，确认本身不解除未知结果', async () => {
    mocks.install.mockRejectedValueOnce(
      new HttpClientError({ status: 503, userMessage: '结果尚未确认' })
    )
    const drawer = await open()
    await button('安装并配置').trigger('click')
    await flushPromises()
    const command = mocks.install.mock.calls[0]![0]
    expect(await confirmPageLeave()).toBe(false)
    expect(drawer.props('modelValue')).toBe(true)
    expect(confirm).toHaveBeenLastCalledWith(
      expect.objectContaining({
        content: expect.stringContaining(command.request_id),
        confirmText: '确认离开'
      })
    )
    vi.mocked(confirm).mockResolvedValueOnce(true)
    expect(await confirmPageLeave()).toBe(true)
    expect(button('安装并配置').props('disabled')).toBe(true)
    expect(document.body.textContent).toContain(command.request_id)
    expect(mocks.install).toHaveBeenCalledTimes(1)
  })
  it('真实请求在途时仍阻止关闭和离开，不借用未知结果确认绕过', async () => {
    let reject!: (reason: unknown) => void
    mocks.install.mockReturnValueOnce(
      new Promise((_resolve, fail) => {
        reject = fail
      })
    )
    const drawer = await open()
    await button('安装并配置').trigger('click')
    await flushPromises()
    expect(drawer.props('showClose')).toBe(false)
    expect(button('取消').props('disabled')).toBe(true)
    expect(await confirmPageLeave()).toBe(false)
    expect(confirm).not.toHaveBeenCalled()
    expect(mocks.install).toHaveBeenCalledTimes(1)
    reject(new HttpClientError({ status: 0, userMessage: '结果尚未确认' }))
    await flushPromises()
    expect(drawer.props('showClose')).toBe(true)
    expect(button('关闭核对').props('disabled')).toBe(false)
  })
})
