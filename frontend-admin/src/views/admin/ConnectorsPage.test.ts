import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElFormItem, ElInput, ElOption, ElSelect } from 'element-plus'
import ConnectorsPage from './ConnectorsPage.vue'
import { HttpClientError } from '@skillforge/shared/utils/http'

const calls = vi.hoisted(() => ({
  instances: vi.fn(),
  instanceDetail: vi.fn(),
  mcpTools: vi.fn(),
  updateInstance: vi.fn(),
  createInstanceWithReceipt: vi.fn(),
  creationResult: vi.fn(),
  push: vi.fn(),
  permissions: [] as string[]
}))
vi.mock('../../api', () => ({ api: { connectors: calls } }))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    identityVersion: 1,
    user: { tenant_id: '1', sub: 'member-1', permissions: calls.permissions }
  })
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: calls.push }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
let wrapper: VueWrapper | undefined
const detail = {
  id: '1',
  instance_id: 'server',
  name: 'MCP服务',
  executor_type: 'mcp',
  base_url: 'https://mcp.example/mcp',
  auth_type: 'none',
  capabilities: ['alpha'],
  status: 'active',
  headers: {}
}
const beta = {
  name: 'beta',
  qualified_name: 'server/beta',
  description: '新增工具',
  input_schema: {}
}
function button(text: string) {
  return wrapper!.findAllComponents(ElButton).find((item) => item.text() === text)!
}
function page() {
  wrapper = mount(ConnectorsPage, {
    attachTo: document.body,
    global: { stubs: { ElDrawer: { template: '<section><slot/><slot name="footer"/></section>' } } }
  })
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  calls.permissions = [
    'foundation.connectors.read',
    'foundation.connectors.update',
    'foundation.capabilities.read',
    'foundation.capabilities.create',
    'foundation.capabilities.publish'
  ]
  calls.instances.mockResolvedValue({ items: [detail] })
  calls.instanceDetail.mockResolvedValue(detail)
  calls.mcpTools.mockResolvedValue({ items: [beta] })
  calls.updateInstance.mockResolvedValue(detail)
  sessionStorage.clear()
  calls.createInstanceWithReceipt.mockImplementation(async (request_id: string) => ({
    request_id,
    instance_id: 'new'
  }))
  calls.creationResult.mockResolvedValue(null)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})

it('opens the unified tool onboarding flow with the selected connector', async () => {
  page()
  await flushPromises()
  await button('接入工具').trigger('click')
  expect(calls.push).toHaveBeenCalledWith({
    path: '/admin/tools',
    query: { connectorId: 'server' }
  })
  expect(calls.updateInstance).not.toHaveBeenCalled()
})

it('offers only supported authentication and protocol controls and saves a Runtime-compatible configuration', async () => {
  page()
  await flushPromises()
  await button('新增连接器').trigger('click')
  await flushPromises()
  expect(wrapper!.findAllComponents(ElOption).map((item) => item.props('value'))).not.toContain(
    'api_key'
  )
  expect(wrapper!.findAllComponents(ElFormItem).map((item) => item.props('label'))).not.toContain(
    'Vault Secret 路径'
  )
  for (const [label, value] of [
    ['名称', '新服务'],
    ['服务地址', 'https://new.example/mcp']
  ]) {
    await wrapper!
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === label)!
      .findComponent(ElInput)
      .setValue(value)
  }
  const protocol = wrapper!
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === 'MCP 协议版本')!
    .findComponent(ElSelect)
  protocol.vm.$emit('update:modelValue', '2025-11-25')
  await flushPromises()
  await button('保存').trigger('click')
  await flushPromises()
  expect(calls.createInstanceWithReceipt).toHaveBeenCalledWith(
    expect.any(String),
    expect.objectContaining({
      executor_type: 'mcp',
      auth_type: 'none',
      auth_credential: '',
      vault_secret_path: '',
      headers: { 'MCP-Protocol-Version': '2025-11-25' }
    })
  )
})

it('创建连接器后直接以返回的业务标识进入工具接入', async () => {
  page()
  await flushPromises()
  await button('新增连接器').trigger('click')
  await flushPromises()
  for (const [label, value] of [
    ['名称', '新服务'],
    ['服务地址', 'https://new.example/mcp']
  ])
    await wrapper!
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === label)!
      .findComponent(ElInput)
      .setValue(value)
  await button('保存并接入工具').trigger('click')
  await flushPromises()
  expect(calls.createInstanceWithReceipt).toHaveBeenCalledOnce()
  expect(calls.push).toHaveBeenCalledExactlyOnceWith({
    path: '/admin/tools',
    query: { connectorId: 'new' }
  })
})

it('跨标签页通知不可用时保留已确认创建结果', async () => {
  vi.stubGlobal(
    'BroadcastChannel',
    class {
      constructor() {
        throw Error('通知不可用')
      }
    }
  )
  try {
    page()
    await flushPromises()
    await button('新增连接器').trigger('click')
    await flushPromises()
    for (const [label, value] of [
      ['名称', '新服务'],
      ['服务地址', 'https://new.example/mcp']
    ])
      await wrapper!
        .findAllComponents(ElFormItem)
        .find((item) => item.props('label') === label)!
        .findComponent(ElInput)
        .setValue(value)
    await button('保存并接入工具').trigger('click')
    await flushPromises()
    expect(calls.createInstanceWithReceipt).toHaveBeenCalledOnce()
    expect(calls.push).toHaveBeenCalledExactlyOnceWith({
      path: '/admin/tools',
      query: { connectorId: 'new' }
    })
    expect(document.body.textContent).not.toContain('创建结果尚未确认')
  } finally {
    vi.unstubAllGlobals()
  }
})

it('缺少工具接入权限时仍可单独创建连接器', async () => {
  calls.permissions = ['foundation.connectors.create']
  page()
  await flushPromises()
  await button('新增连接器').trigger('click')
  await flushPromises()
  expect(
    wrapper!.findAllComponents(ElButton).some((item) => item.text() === '保存并接入工具')
  ).toBe(false)
  expect(button('保存').exists()).toBe(true)
})

it('跳转失败时保留已创建结果并提示从列表继续，不重复创建', async () => {
  calls.push.mockRejectedValueOnce(Error('路由不可用'))
  page()
  await flushPromises()
  await button('新增连接器').trigger('click')
  await flushPromises()
  for (const [label, value] of [
    ['名称', '新服务'],
    ['服务地址', 'https://new.example/mcp']
  ])
    await wrapper!
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === label)!
      .findComponent(ElInput)
      .setValue(value)
  await button('保存并接入工具').trigger('click')
  await flushPromises()
  expect(calls.createInstanceWithReceipt).toHaveBeenCalledOnce()
  expect(document.body.textContent).toContain('连接器已保存，未能进入工具接入')
})

it('创建结果未知时保留原请求且不持久化凭据，核对后继续接入', async () => {
  calls.createInstanceWithReceipt.mockRejectedValueOnce(new Error('连接中断'))
  page()
  await flushPromises()
  await button('新增连接器').trigger('click')
  await flushPromises()
  for (const [label, value] of [
    ['名称', '新服务'],
    ['服务地址', 'https://new.example/mcp']
  ])
    await wrapper!
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === label)!
      .findComponent(ElInput)
      .setValue(value)
  wrapper!
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '认证方式')!
    .findComponent(ElSelect)
    .vm.$emit('update:modelValue', 'bearer')
  await flushPromises()
  await wrapper!
    .findAllComponents(ElFormItem)
    .find((item) => item.props('label') === '访问凭据')!
    .findComponent(ElInput)
    .setValue('test-secret-never-stored')
  await button('保存并接入工具').trigger('click')
  await flushPromises()
  const requestId = calls.createInstanceWithReceipt.mock.calls[0]![0]
  const stored = sessionStorage.getItem('skillforge.connector-create:1:member-1')!
  expect(stored).toContain(requestId)
  expect(stored).not.toContain('test-secret-never-stored')
  expect(wrapper!.text()).toContain('创建结果尚未确认')
  await button('核对创建结果').trigger('click')
  await flushPromises()
  expect(calls.createInstanceWithReceipt).toHaveBeenCalledOnce()
  expect(wrapper!.text()).toContain('创建结果尚未确认')
  calls.creationResult.mockResolvedValueOnce({ request_id: requestId, instance_id: 'new' })
  await button('核对创建结果').trigger('click')
  await flushPromises()
  expect(calls.push).toHaveBeenCalledExactlyOnceWith({
    path: '/admin/tools',
    query: { connectorId: 'new' }
  })
  expect(calls.createInstanceWithReceipt).toHaveBeenCalledOnce()
  expect(sessionStorage.getItem('skillforge.connector-create:1:member-1')).toBeNull()
})

it('明确发生在保存前的发现失败解除待核对并保留输入', async () => {
  calls.createInstanceWithReceipt.mockRejectedValueOnce(
    new HttpClientError({
      status: 502,
      code: 'CONNECTOR_CREATE_REJECTED',
      userMessage: '目录发现失败'
    })
  )
  page()
  await flushPromises()
  await button('新增连接器').trigger('click')
  await flushPromises()
  for (const [label, value] of [
    ['名称', '失败后保留'],
    ['服务地址', 'https://new.example/mcp']
  ])
    await wrapper!
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === label)!
      .findComponent(ElInput)
      .setValue(value)
  await button('保存').trigger('click')
  await flushPromises()
  expect(wrapper!.text()).not.toContain('创建结果尚未确认')
  expect(sessionStorage.getItem('skillforge.connector-create:1:member-1')).toBeNull()
  expect(
    wrapper!
      .findAllComponents(ElFormItem)
      .find((item) => item.props('label') === '名称')!
      .findComponent(ElInput)
      .props('modelValue')
  ).toBe('失败后保留')
})
