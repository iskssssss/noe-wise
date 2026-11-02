import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElInput, ElTable } from 'element-plus'
import ToolsPage from './ToolsPage.vue'
import ToolOnboarding from '../../components/ToolOnboarding.vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { clearFeedback } from '@skillforge/shared/feedback'
import type { AvailableTool, ToolOnboardingItem } from '../../api/tools'

const calls = vi.hoisted(() => ({
  available: vi.fn(),
  onboard: vi.fn(),
  onboarding: vi.fn(),
  connectors: vi.fn(),
  discover: vi.fn(),
  routeConnector: 'server'
}))
vi.mock('../../api', () => ({
  api: { connectors: { instances: calls.connectors, mcpTools: calls.discover } }
}))
vi.mock('../../api/tools', () => ({
  toolsApi: { available: calls.available, onboard: calls.onboard, onboarding: calls.onboarding }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    user: {
      tenant_id: 'tenant-1',
      sub: 'member-1',
      permissions: [
        'foundation.connectors.read',
        'foundation.connectors.update',
        'foundation.capabilities.read',
        'foundation.capabilities.create',
        'foundation.capabilities.publish'
      ]
    }
  })
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { connectorId: calls.routeConnector } }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
const alpha = { name: 'read_alpha', description: '读取资料', input_schema: {} }
const confirmedAlpha = {
  tool_name: 'read_alpha',
  definition_id: '30',
  version_id: '31',
  deployment_key: 'server-alpha',
  environment: 'DEVELOPMENT'
} satisfies ToolOnboardingItem
function success(request_id: string) {
  return {
    request_id,
    connector_id: 'server',
    tool_names: ['read_alpha'],
    status: 'SUCCEEDED',
    items: [confirmedAlpha]
  }
}
let wrapper: VueWrapper | undefined
function button(label: string) {
  return wrapper!.findAllComponents(ElButton).find((item) => item.text() === label)!
}
function toolRows(table: VueWrapper) {
  return (table.props() as { data: AvailableTool[] }).data
}
beforeEach(() => {
  sessionStorage.clear()
  vi.resetAllMocks()
  calls.routeConnector = 'server'
  calls.available.mockResolvedValue({ items: [] })
  calls.connectors.mockResolvedValue({
    items: [{ instance_id: 'server', name: '资料服务', executor_type: 'mcp' }]
  })
  calls.discover.mockResolvedValue({ items: [alpha] })
  calls.onboard.mockImplementation(async (request_id: string) => success(request_id))
})

it('只有一个连接器时自动选择并读取，多个连接器时等待用户选择', async () => {
  calls.routeConnector = ''
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  await button('接入工具').trigger('click')
  await flushPromises()
  expect(wrapper.findComponent(ToolOnboarding).props('connectorId')).toBe('server')
  expect(calls.discover).toHaveBeenCalledExactlyOnceWith('server')
  wrapper.unmount()
  calls.discover.mockClear()
  calls.connectors.mockResolvedValueOnce({
    items: [
      { instance_id: 'server', name: '资料服务', executor_type: 'mcp' },
      { instance_id: 'other', name: '其他服务', executor_type: 'mcp' }
    ]
  })
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  await button('接入工具').trigger('click')
  await flushPromises()
  expect(wrapper.findComponent(ToolOnboarding).props('connectorId')).toBe('')
  expect(calls.discover).not.toHaveBeenCalled()
})
it('选择其他连接器时自动读取其目录，未确认前不接入', async () => {
  calls.routeConnector = ''
  calls.connectors.mockResolvedValue({
    items: [
      { instance_id: 'server', name: '资料服务', executor_type: 'mcp' },
      { instance_id: 'other', name: '其他服务', executor_type: 'mcp' }
    ]
  })
  calls.discover.mockImplementation(async (id: string) => ({ items: [{ name: id + '_tool' }] }))
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  await button('接入工具').trigger('click')
  const onboarding = wrapper.findComponent(ToolOnboarding)
  onboarding.vm.$emit('update:connectorId', 'server')
  await flushPromises()
  expect(calls.discover).toHaveBeenCalledExactlyOnceWith('server')
  expect(onboarding.text()).toContain('server_tool')
  onboarding.vm.$emit('update:connectorId', 'other')
  await flushPromises()
  expect(calls.discover).toHaveBeenLastCalledWith('other')
  expect(onboarding.text()).toContain('other_tool')
  expect(calls.onboard).not.toHaveBeenCalled()
})
it('自动读取失败不重复请求，手动重读成功后可继续', async () => {
  calls.discover.mockRejectedValueOnce(new Error('读取失败'))
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  expect(calls.discover).toHaveBeenCalledTimes(1)
  expect(calls.onboard).not.toHaveBeenCalled()
  await button('读取工具').trigger('click')
  await flushPromises()
  expect(calls.discover).toHaveBeenCalledTimes(2)
  expect(wrapper.text()).toContain('read_alpha')
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  clearFeedback()
  document.body.innerHTML = ''
})
async function choose() {
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  expect(calls.discover).toHaveBeenCalledExactlyOnceWith('server')
  expect(calls.onboard).not.toHaveBeenCalled()
  wrapper.findComponent(ToolOnboarding).findComponent(ElTable).vm.$emit('selection-change', [alpha])
  await flushPromises()
  await button('确认接入并授权调用 1 项工具').trigger('click')
  await flushPromises()
}

it('没有 MCP 连接器时引导至连接设置，返回后自动读取工具', async () => {
  calls.connectors.mockResolvedValueOnce({
    items: [{ instance_id: 'other', name: '非 MCP', executor_type: 'HTTP' }]
  })
  wrapper = mount(ToolsPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
  })
  await flushPromises()
  expect(wrapper.text()).toContain('当前没有可选的 MCP 连接器')
  expect(wrapper.get('a[href="/admin/connectors"]').attributes('target')).toBe('_blank')
  await wrapper.get('a[href="/admin/connectors"]').trigger('click')
  window.dispatchEvent(new Event('focus'))
  await flushPromises()
  expect(calls.connectors).toHaveBeenCalledTimes(2)
  expect(wrapper.text()).not.toContain('当前没有可选的 MCP 连接器')
  expect(calls.discover).toHaveBeenCalledExactlyOnceWith('server')
  expect(wrapper.text()).toContain('read_alpha')
})

it('只响应同一机构账号的连接器创建通知', async () => {
  const channels: FakeChannel[] = []
  class FakeChannel {
    onmessage?: (event: { data: string }) => void
    close = vi.fn()
    constructor() {
      channels.push(this)
    }
  }
  vi.stubGlobal('BroadcastChannel', FakeChannel)
  try {
    wrapper = mount(ToolsPage, { attachTo: document.body })
    await flushPromises()
    const channel = channels[0]!
    channel.onmessage!({ data: 'tenant-1:other-member' })
    await flushPromises()
    expect(calls.connectors).toHaveBeenCalledTimes(1)
    channel.onmessage!({ data: 'tenant-1:member-1' })
    await flushPromises()
    expect(calls.connectors).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    wrapper = undefined
    expect(channel.close).toHaveBeenCalledOnce()
  } finally {
    vi.unstubAllGlobals()
  }
})
it.each(['成功', '失败'])('修改连接器后重读工具%s，保留选择并防止使用旧目录', async (outcome) => {
  wrapper = mount(ToolsPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
  })
  await flushPromises()
  const onboarding = wrapper.findComponent(ToolOnboarding)
  onboarding.findComponent(ElTable).vm.$emit('selection-change', [alpha])
  await flushPromises()
  if (outcome === '成功')
    calls.discover.mockResolvedValueOnce({ items: [alpha, { name: 'new_tool' }] })
  else calls.discover.mockRejectedValueOnce(new Error('工具目录不可用'))
  await wrapper.get('a[href="/admin/connectors"]').trigger('click')
  window.dispatchEvent(new Event('focus'))
  await flushPromises()
  expect(calls.connectors).toHaveBeenCalledTimes(2)
  expect(calls.discover).toHaveBeenCalledTimes(2)
  expect(button('确认接入并授权调用 1 项工具').props('disabled')).toBe(outcome === '失败')
  if (outcome === '成功') expect(onboarding.text()).toContain('new_tool')
  else {
    expect(onboarding.text()).toContain('工具目录未刷新')
    await button('刷新连接器及工具').trigger('click')
    await flushPromises()
    expect(calls.connectors).toHaveBeenCalledTimes(3)
    expect(calls.discover).toHaveBeenCalledTimes(3)
    expect(button('确认接入并授权调用 1 项工具').props('disabled')).toBe(false)
  }
  expect(calls.onboard).not.toHaveBeenCalled()
})

it('返回时连接器读取失败后，一次刷新恢复连接器和原工具目录', async () => {
  wrapper = mount(ToolsPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
  })
  await flushPromises()
  const onboarding = wrapper.findComponent(ToolOnboarding)
  onboarding.findComponent(ElTable).vm.$emit('selection-change', [alpha])
  await flushPromises()
  calls.connectors.mockRejectedValueOnce(new Error('连接器目录不可用'))
  await wrapper.get('a[href="/admin/connectors"]').trigger('click')
  window.dispatchEvent(new Event('focus'))
  await flushPromises()
  expect(button('确认接入并授权调用 1 项工具').props('disabled')).toBe(true)
  expect(calls.discover).toHaveBeenCalledTimes(1)
  await button('刷新连接器及工具').trigger('click')
  await flushPromises()
  expect(calls.connectors).toHaveBeenCalledTimes(3)
  expect(calls.discover).toHaveBeenCalledTimes(2)
  expect(button('确认接入并授权调用 1 项工具').props('disabled')).toBe(false)
  expect(calls.onboard).not.toHaveBeenCalled()
})

it('通知通道不可用时仍可在返回页面后刷新', async () => {
  vi.stubGlobal(
    'BroadcastChannel',
    class {
      constructor() {
        throw Error('通知不可用')
      }
    }
  )
  try {
    wrapper = mount(ToolsPage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
    })
    await flushPromises()
    await wrapper.get('a[href="/admin/connectors"]').trigger('click')
    window.dispatchEvent(new Event('focus'))
    await flushPromises()
    expect(calls.connectors).toHaveBeenCalledTimes(2)
  } finally {
    vi.unstubAllGlobals()
  }
})
it('连接器目录失败时不误报空目录，刷新成功后继续', async () => {
  calls.connectors.mockRejectedValueOnce(new Error('连接器目录不可读'))
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  expect(wrapper.text()).not.toContain('当前没有可选的 MCP 连接器')
  await button('刷新连接器及工具').trigger('click')
  await flushPromises()
  expect(calls.connectors).toHaveBeenCalledTimes(2)
  expect(wrapper.text()).not.toContain('当前没有可选的 MCP 连接器')
})

it('onboards a selected tool with one command and reads the available catalog afterwards', async () => {
  await choose()
  expect(calls.onboard).toHaveBeenCalledExactlyOnceWith(
    expect.any(String),
    'server',
    ['read_alpha'],
    true
  )
  expect(calls.available).toHaveBeenCalledTimes(2)
  expect(wrapper!.text()).not.toContain('接入结果尚未确认')
  expect(button('确认接入并授权调用 0 项工具').props('disabled')).toBe(true)
})

it('可用工具目录读取较慢时仍可接入工具，旧目录迟到不覆盖接入结果', async () => {
  let finish!: (value: { items: AvailableTool[] }) => void
  calls.available.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  expect(calls.connectors).toHaveBeenCalledOnce()
  expect(button('读取工具').props('disabled')).toBe(false)
  expect(calls.discover).toHaveBeenCalledExactlyOnceWith('server')
  expect(wrapper.text()).toContain('read_alpha')
  const added: AvailableTool = {
    definition_id: '30',
    default_version_id: '31',
    name: '资料工具',
    tool_name: 'read_alpha',
    description: '读取资料',
    deployments: []
  }
  calls.available.mockResolvedValueOnce({ items: [added] })
  wrapper.findComponent(ToolOnboarding).findComponent(ElTable).vm.$emit('selection-change', [alpha])
  await flushPromises()
  await button('确认接入并授权调用 1 项工具').trigger('click')
  await flushPromises()
  expect(calls.onboard).toHaveBeenCalledTimes(1)
  expect(toolRows(wrapper.findAllComponents(ElTable).at(-1)!)).toEqual([added])
  finish({ items: [] })
  await flushPromises()
  expect(toolRows(wrapper.findAllComponents(ElTable).at(-1)!)).toEqual([added])
})

it('选择 101 项时显示限制并禁止发送接入请求，减少到 100 项后可提交', async () => {
  const tools = Array.from({ length: 101 }, (_, index) => ({
    name: `tool_${index}`,
    input_schema: {}
  }))
  calls.discover.mockResolvedValue({ items: tools })
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  await button('读取工具').trigger('click')
  await flushPromises()
  const table = wrapper.findComponent(ToolOnboarding).findComponent(ElTable)
  table.vm.$emit('selection-change', tools)
  await flushPromises()
  expect(wrapper.text()).toContain('每次请选择 1 至 100 项工具')
  expect(button('确认接入并授权调用 101 项工具').props('disabled')).toBe(true)
  await button('确认接入并授权调用 101 项工具').trigger('click')
  expect(calls.onboard).not.toHaveBeenCalled()
  table.vm.$emit('selection-change', tools.slice(0, 100))
  await flushPromises()
  expect(button('确认接入并授权调用 100 项工具').props('disabled')).toBe(false)
  expect(wrapper.text()).not.toContain('每次请选择 1 至 100 项工具')
})

it('区分首次读取失败、成功空目录和已有目录刷新失败', async () => {
  calls.available.mockRejectedValueOnce(new Error('目录暂不可读'))
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  expect(wrapper.text()).not.toContain('尚无可用工具')
  expect(document.querySelector('.el-message')?.textContent).toContain('目录暂不可读')

  await button('刷新工具').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('尚无可用工具')

  const tool: AvailableTool = {
    definition_id: '30',
    default_version_id: '31',
    name: '资料工具',
    tool_name: 'read_alpha',
    description: '读取资料',
    deployments: []
  }
  calls.available.mockResolvedValueOnce({ items: [tool] })
  await button('刷新工具').trigger('click')
  await flushPromises()
  calls.available.mockRejectedValueOnce(new Error('目录刷新失败'))
  await button('刷新工具').trigger('click')
  await flushPromises()
  expect(toolRows(wrapper.findAllComponents(ElTable).at(-1)!)).toEqual([tool])
  expect(document.querySelector('.el-message')?.textContent).toContain('目录刷新失败')
})

it('工具目录刷新失败时保留旧结果但禁止接入，重读成功后保留有效选择', async () => {
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  const onboarding = wrapper.findComponent(ToolOnboarding)
  onboarding.findComponent(ElTable).vm.$emit('selection-change', [alpha])
  await flushPromises()
  calls.discover.mockRejectedValueOnce(new Error('读取失败'))
  await button('读取工具').trigger('click')
  await flushPromises()
  expect(onboarding.text()).toContain('当前显示的是上次读取结果')
  expect(onboarding.text()).toContain('read_alpha')
  expect(button('确认接入并授权调用 1 项工具').props('disabled')).toBe(true)
  expect(calls.onboard).not.toHaveBeenCalled()
  await button('读取工具').trigger('click')
  await flushPromises()
  expect(onboarding.text()).not.toContain('当前显示的是上次读取结果')
  expect(button('确认接入并授权调用 1 项工具').props('disabled')).toBe(false)
  await button('确认接入并授权调用 1 项工具').trigger('click')
  await flushPromises()
  expect(calls.onboard).toHaveBeenCalledExactlyOnceWith(
    expect.any(String),
    'server',
    ['read_alpha'],
    true
  )
})

it.each([new Error('连接中断'), new HttpClientError({ status: 408, userMessage: '接入请求超时' })])(
  '未知结果只按原请求查询，空结果、错误和不匹配不解除，成功回执才继续 (%s)',
  async (failure) => {
    calls.onboard.mockRejectedValueOnce(failure)
    await choose()
    const requestId = calls.onboard.mock.calls[0]![0]
    expect(requestId).toMatch(/^[0-9a-f-]{36}$/)
    calls.onboarding
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('查询失败'))
      .mockResolvedValueOnce(success('wrong'))
      .mockResolvedValueOnce({ ...success(requestId), items: [] })
      .mockResolvedValueOnce({ ...success(requestId), status: 'PROCESSING' })
      .mockResolvedValueOnce(success(requestId))
    for (let i = 0; i < 5; i++) {
      await button('核对接入结果').trigger('click')
      await flushPromises()
      expect(wrapper!.text()).toContain('接入结果尚未确认')
      expect(calls.onboarding).toHaveBeenLastCalledWith(requestId)
      expect(calls.onboard).toHaveBeenCalledTimes(1)
    }
    await button('核对接入结果').trigger('click')
    await flushPromises()
    expect(wrapper!.text()).not.toContain('接入结果尚未确认')
    expect(calls.available).toHaveBeenCalledTimes(2)
    expect(calls.onboard).toHaveBeenCalledTimes(1)
  }
)

it('重新进入自动核对原接入，成功后清除记录而不重复写入', async () => {
  calls.onboard.mockRejectedValueOnce(new Error('连接中断'))
  await choose()
  const requestId = calls.onboard.mock.calls[0]![0]
  const key = `skillforge.tool-onboarding.${JSON.stringify(['tenant-1', 'member-1'])}`
  expect(JSON.parse(sessionStorage.getItem(key)!)).toEqual({
    requestId,
    connectorId: 'server',
    names: ['read_alpha']
  })
  wrapper!.unmount()
  calls.discover.mockClear()
  calls.onboarding.mockResolvedValueOnce(success(requestId))
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  expect(calls.onboarding).toHaveBeenCalledExactlyOnceWith(requestId)
  expect(calls.onboard).toHaveBeenCalledTimes(1)
  expect(sessionStorage.getItem(key)).toBeNull()
  expect(wrapper.text()).not.toContain('接入结果尚未确认')
  expect(calls.discover).toHaveBeenCalledExactlyOnceWith('server')
})

it('自动核对未找到回执时保留原记录和手动入口', async () => {
  const requestId = 'ee2debea-46c1-4dd1-a508-193f53e27291'
  const key = `skillforge.tool-onboarding.${JSON.stringify(['tenant-1', 'member-1'])}`
  const pending = JSON.stringify({ requestId, connectorId: 'server', names: ['read_alpha'] })
  sessionStorage.setItem(key, pending)
  calls.onboarding.mockResolvedValueOnce(undefined)
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  expect(calls.onboarding).toHaveBeenCalledExactlyOnceWith(requestId)
  expect(sessionStorage.getItem(key)).toBe(pending)
  expect(button('核对接入结果').exists()).toBe(true)
  expect(calls.onboard).not.toHaveBeenCalled()
})

it('不能保存原接入请求时不发送工具接入', async () => {
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  wrapper.findComponent(ToolOnboarding).findComponent(ElTable).vm.$emit('selection-change', [alpha])
  await flushPromises()
  const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
    throw Error('blocked')
  })
  await button('确认接入并授权调用 1 项工具').trigger('click')
  await flushPromises()
  expect(calls.onboard).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('尚未发送请求')
  storage.mockRestore()
})

it('回执已确认但存储清除失败时保留记录，恢复后只读核对', async () => {
  const storage = vi.spyOn(Storage.prototype, 'removeItem').mockImplementationOnce(() => {
    throw Error('blocked')
  })
  await choose()
  const requestId = calls.onboard.mock.calls[0]![0]
  const key = `skillforge.tool-onboarding.${JSON.stringify(['tenant-1', 'member-1'])}`
  expect(sessionStorage.getItem(key)).not.toBeNull()
  expect(wrapper!.text()).toContain('无法清除原工具接入记录')
  storage.mockRestore()
  calls.onboarding.mockResolvedValueOnce(success(requestId))
  await button('核对接入结果').trigger('click')
  await flushPromises()
  expect(sessionStorage.getItem(key)).toBeNull()
  expect(calls.onboard).toHaveBeenCalledTimes(1)
  expect(calls.onboarding).toHaveBeenCalledExactlyOnceWith(requestId)
})

it('重新进入后可显式按原请求 ID 查询，不调用接入写入', async () => {
  const requestId = 'ee2debea-46c1-4dd1-a508-193f53e27291'
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  calls.onboarding.mockResolvedValueOnce(undefined).mockResolvedValueOnce(success(requestId))
  const input = wrapper
    .findAllComponents(ElInput)
    .find((item) => item.props('placeholder') === '输入原请求 ID')!
  input.vm.$emit('update:modelValue', requestId)
  await flushPromises()
  await button('查询原接入回执').trigger('click')
  await flushPromises()
  expect(calls.onboarding).toHaveBeenCalledWith(requestId)
  expect(calls.available).toHaveBeenCalledTimes(1)
  await button('查询原接入回执').trigger('click')
  await flushPromises()
  expect(calls.available).toHaveBeenCalledTimes(2)
  expect(wrapper.text()).not.toContain('接入结果尚未确认')
  expect(calls.onboard).not.toHaveBeenCalled()
})

it('手动查询未查到或失败时允许更正请求 ID，处理中才保留原身份', async () => {
  const first = 'ee2debea-46c1-4dd1-a508-193f53e27291'
  const second = 'ee2debea-46c1-4dd1-a508-193f53e27292'
  wrapper = mount(ToolsPage, { attachTo: document.body })
  await flushPromises()
  const input = wrapper
    .findAllComponents(ElInput)
    .find((item) => item.props('placeholder') === '输入原请求 ID')!
  input.vm.$emit('update:modelValue', first)
  calls.onboarding
    .mockResolvedValueOnce(undefined)
    .mockRejectedValueOnce(new Error('查询失败'))
    .mockResolvedValueOnce({ ...success(second), status: 'PROCESSING' })
  await flushPromises()
  await button('查询原接入回执').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('接入结果尚未确认')
  await button('查询原接入回执').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('接入结果尚未确认')
  input.vm.$emit('update:modelValue', second)
  await flushPromises()
  await button('查询原接入回执').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('接入结果尚未确认')
  expect(wrapper.text()).toContain(second)
  expect(calls.onboard).not.toHaveBeenCalled()
})

it.each(['confirm', 'inspect'] as Array<'confirm' | 'inspect'>)(
  '%s正式回执先保留可用工具及其它实例，刷新失败只提示一次且不重接入',
  async (action) => {
    const previous: AvailableTool = {
      definition_id: '30',
      default_version_id: 'old-version',
      name: '旧名称',
      description: '旧说明',
      tool_name: 'read_alpha',
      deployments: [
        {
          deployment_key: 'other-alpha',
          provider_type: 'MCP',
          provider_ref: 'other',
          provider_label: '另一个服务',
          environment: 'TEST'
        },
        {
          deployment_key: 'server-alpha',
          provider_type: 'MCP',
          provider_ref: 'server',
          provider_label: '旧服务名称',
          environment: 'DEVELOPMENT'
        }
      ]
    }
    const other: AvailableTool = {
      ...previous,
      definition_id: '40',
      name: '另一个工具',
      deployments: []
    }
    calls.available
      .mockResolvedValueOnce({ items: [previous, other] })
      .mockRejectedValueOnce(new Error('目录暂不可读'))
    if (action === 'inspect') {
      calls.onboard.mockRejectedValueOnce(
        new HttpClientError({ status: 408, userMessage: '接入超时' })
      )
      calls.onboarding.mockImplementation(async (request_id: string) => success(request_id))
    }
    await choose()
    if (action === 'inspect') {
      await button('核对接入结果').trigger('click')
      await flushPromises()
    }
    const table = wrapper!.findAllComponents(ElTable).at(-1)!
    expect(toolRows(table)).toEqual([previous, other])
    expect(wrapper!.text()).toContain('工具接入已确认，目录尚未刷新')
    expect(document.querySelectorAll('.el-message')).toHaveLength(1)
    expect(document.querySelector('.el-message')?.classList.contains('el-message--warning')).toBe(
      true
    )
    expect(document.querySelector('.el-message')?.textContent).toContain('无需重复接入')
    expect(calls.onboard).toHaveBeenCalledTimes(1)
    calls.available.mockResolvedValueOnce({ items: [other] })
    await button('刷新工具').trigger('click')
    await flushPromises()
    expect(toolRows(table)).toEqual([other])
    expect(document.querySelectorAll('.el-message')).toHaveLength(0)
    expect(calls.onboard).toHaveBeenCalledTimes(1)
  }
)

it('成功回执不伪造目录，刷新完成后使用目录结果', async () => {
  let finish!: (value: { items: AvailableTool[] }) => void
  calls.available.mockResolvedValueOnce({ items: [] }).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  await choose()
  const table = wrapper!.findAllComponents(ElTable).at(-1)!
  expect(toolRows(table)).toEqual([])
  expect(wrapper!.text()).toContain('工具接入已确认，目录尚未刷新')
  finish({ items: [] })
  await flushPromises()
  expect(toolRows(table)).toEqual([])
  expect(calls.onboard).toHaveBeenCalledTimes(1)
})

it('明确拒绝保留选择并允许使用新请求 ID 修正后提交', async () => {
  calls.onboard.mockImplementationOnce(async (request_id: string) => ({
    request_id,
    connector_id: 'server',
    tool_names: ['read_alpha'],
    status: 'REJECTED',
    error_code: 'UNAVAILABLE',
    error_message: 'MCP 工具目录不可用'
  }))
  await choose()
  const firstId = calls.onboard.mock.calls[0]![0]
  expect(wrapper!.text()).not.toContain('接入结果尚未确认')
  expect(document.body.textContent).toContain('MCP 工具目录不可用')
  await button('确认接入并授权调用 1 项工具').trigger('click')
  await flushPromises()
  expect(calls.onboard).toHaveBeenCalledTimes(2)
  expect(calls.onboard.mock.calls[1]![0]).not.toBe(firstId)
})

it('searches available names, original names, descriptions and service sources and distinguishes empty states', async () => {
  const tool: AvailableTool = {
    definition_id: '30',
    default_version_id: '31',
    name: '资料工具',
    tool_name: 'read_alpha',
    description: '查找订单',
    deployments: [
      {
        deployment_key: 'server-alpha',
        provider_type: 'MCP',
        provider_ref: 'server',
        provider_label: '业务服务',
        environment: 'DEVELOPMENT'
      }
    ]
  }
  calls.available.mockResolvedValue({ items: [tool] })
  wrapper = mount(ToolsPage)
  await flushPromises()
  const search = wrapper
    .findAllComponents(ElInput)
    .find((item) => item.props('placeholder') === '搜索工具名称、说明或服务来源')!
  const table = wrapper.findAll('.el-table').at(-1)!
  for (const query of ['资料', 'READ_ALPHA', '订单', '业务服务']) {
    search.vm.$emit('update:modelValue', query)
    await flushPromises()
    expect(table.findAll('tbody tr').map((row) => row.text())).toEqual([
      expect.stringContaining('资料工具')
    ])
  }
  search.vm.$emit('update:modelValue', 'missing')
  await flushPromises()
  expect(table.findAll('tbody tr')).toHaveLength(0)
  expect(table.text()).toContain('没有匹配')
  search.vm.$emit('update:modelValue', '')
  await flushPromises()
  expect(table.findAll('tbody tr').map((row) => row.text())).toEqual([
    expect.stringContaining('资料工具')
  ])
  calls.available.mockResolvedValue({ items: [] })
  await button('刷新工具').trigger('click')
  await flushPromises()
  expect(table.text()).toContain('尚无可用工具')
})
