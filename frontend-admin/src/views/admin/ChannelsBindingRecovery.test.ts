import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElOption, ElSelect } from 'element-plus'
import ChannelsPage from './ChannelsPage.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  detail: vi.fn(),
  agents: vi.fn(),
  bind: vi.fn(),
  success: vi.fn(),
  showMessage: vi.fn()
}))
vi.mock('../../api', () => ({
  api: {
    channels: { list: mocks.list, get: mocks.detail, bindAgents: mocks.bind },
    agents: { list: mocks.agents }
  }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ user: { tenant_id: '7', sub: '8', permissions: [] } })
}))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@skillforge/shared/feedback')>()
  return {
    ...actual,
    showMessage: mocks.showMessage,
    message: { ...actual.message, success: mocks.success }
  }
})
const channel = {
  id: 'channel-1',
  name: '当前渠道',
  type: 'openapi',
  status: 'active',
  api_key_issued: false
}
const bindings = [
  { id: 'agent-1', name: '已绑定甲', is_default: true },
  { id: 'agent-2', name: '已绑定乙', is_default: false }
]
let wrapper: VueWrapper
beforeEach(() => {
  vi.resetAllMocks()
  mocks.list.mockResolvedValue({ items: [channel], total: 1 })
  mocks.detail.mockResolvedValue({ ...channel, agent_bindings: bindings })
  mocks.agents.mockResolvedValue({
    items: [
      { id: 'agent-1', name: '候选目录中的新名称', status: 'published' },
      { id: 'agent-3', name: '可新增成员', status: 'published' },
      { id: 'draft-agent', name: '不可新增草稿', status: 'draft' }
    ],
    total: 3
  })
  mocks.bind.mockResolvedValue({ status: 'bound' })
  mocks.showMessage.mockReturnValue(() => {})
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function click(text: string) {
  const button = wrapper.findAllComponents(ElButton).find((value) => value.text() === text)
  expect(button, text).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
async function openBindings() {
  wrapper = mount(ChannelsPage, { attachTo: document.body })
  await flushPromises()
  await click('绑定智能体')
}
function bindingDrawer() {
  return wrapper
    .findAllComponents(ElDrawer)
    .find((value) => String(value.props('title')).startsWith('绑定智能体'))!
}
async function saveBindingDraft() {
  const drawer = bindingDrawer()
  expect(drawer.props('modelValue')).toBe(true)
  const buttons = drawer.findAllComponents(ElButton).filter((button) => button.text() === '保存')
  expect(buttons).toHaveLength(1)
  await buttons[0]!.trigger('click')
  await flushPromises()
}
function selects() {
  return bindingDrawer().findAllComponents(ElSelect)
}

describe('渠道绑定与候选目录独立恢复', () => {
  it('原样保存保留详情中的路由参数，零优先级和零流量不被默认值覆盖', async () => {
    mocks.detail.mockResolvedValue({
      ...channel,
      agent_bindings: [
        { ...bindings[0], priority: 20, traffic_percentage: 25 },
        { ...bindings[1], priority: 0, traffic_percentage: 0 }
      ]
    })
    await openBindings()
    await saveBindingDraft()
    expect(mocks.bind).toHaveBeenCalledExactlyOnceWith('channel-1', [
      { agent_id: 'agent-1', priority: 20, traffic_percentage: 25, is_default: true },
      { agent_id: 'agent-2', priority: 0, traffic_percentage: 0, is_default: false }
    ])
  })
  it('增删成员和调整默认项只改变所选集合，保留旧成员参数并沿用新成员默认行为', async () => {
    mocks.detail.mockResolvedValue({
      ...channel,
      agent_bindings: [
        { ...bindings[0], priority: 20, traffic_percentage: 25 },
        { ...bindings[1], priority: 10, traffic_percentage: 75 }
      ]
    })
    await openBindings()
    selects()[0]!.vm.$emit('update:modelValue', ['agent-2', 'agent-3'])
    await flushPromises()
    selects()[1]!.vm.$emit('update:modelValue', 'agent-3')
    await flushPromises()
    await saveBindingDraft()
    expect(mocks.bind).toHaveBeenCalledExactlyOnceWith('channel-1', [
      { agent_id: 'agent-2', priority: 10, traffic_percentage: 75, is_default: false },
      { agent_id: 'agent-3', priority: 1, is_default: true }
    ])
  })
  it('同名智能体按角色说明或标识查找并保存准确身份', async () => {
    mocks.detail.mockResolvedValue({ ...channel, agent_bindings: [] })
    mocks.agents.mockResolvedValue({
      items: [
        {
          id: 'a',
          name: '客服',
          status: 'published',
          agent_key: 'orders',
          role_description: '查询订单'
        },
        {
          id: 'b',
          name: '客服',
          status: 'published',
          agent_key: 'patients',
          role_description: '查询患者'
        }
      ]
    })
    await openBindings()
    const select = selects()[0]!
    select.props('filterMethod')!('患者')
    await flushPromises()
    expect(select.findAllComponents(ElOption).map((item) => item.props('value'))).toEqual(['b'])
    select.props('filterMethod')!('patients')
    await flushPromises()
    expect(select.findAllComponents(ElOption).map((item) => item.props('value'))).toEqual(['b'])
    select.vm.$emit('update:modelValue', ['b'])
    await flushPromises()
    await saveBindingDraft()
    expect(mocks.bind).toHaveBeenCalledExactlyOnceWith('channel-1', [
      { agent_id: 'b', priority: 0, is_default: true }
    ])
  })
  it('新渠道直接保存单个智能体时提交默认绑定', async () => {
    mocks.detail.mockResolvedValue({ ...channel, agent_bindings: [] })
    await openBindings()
    selects()[0]!.vm.$emit('update:modelValue', ['agent-3'])
    await flushPromises()
    await saveBindingDraft()
    expect(mocks.bind).toHaveBeenCalledExactlyOnceWith('channel-1', [
      { agent_id: 'agent-3', priority: 0, is_default: true }
    ])
  })
  it('新渠道首次绑定单个智能体时预选默认，仍可清除并保存', async () => {
    mocks.detail.mockResolvedValue({ ...channel, agent_bindings: [] })
    await openBindings()
    selects()[0]!.vm.$emit('update:modelValue', ['agent-3'])
    await flushPromises()
    expect(selects()[1]!.props('modelValue')).toBe('agent-3')
    selects()[1]!.vm.$emit('update:modelValue', '')
    await flushPromises()
    await saveBindingDraft()
    expect(mocks.bind).toHaveBeenCalledExactlyOnceWith('channel-1', [
      { agent_id: 'agent-3', priority: 0, is_default: false }
    ])
  })
  it('已有渠道的单个非默认绑定保持原样', async () => {
    mocks.detail.mockResolvedValue({
      ...channel,
      agent_bindings: [{ id: 'agent-1', name: '原成员', is_default: false }]
    })
    await openBindings()
    expect(selects()[1]!.props('modelValue')).toBe('')
    await saveBindingDraft()
    expect(mocks.bind).toHaveBeenCalledExactlyOnceWith('channel-1', [
      { agent_id: 'agent-1', priority: 0, is_default: false }
    ])
  })
  it('候选读取被拒绝仍显示原绑定，清空沿原渠道接口提交而不需要目录权限', async () => {
    mocks.agents.mockRejectedValueOnce(new Error('没有智能体目录读取权限'))
    await openBindings()
    expect(bindingDrawer().props('modelValue')).toBe(true)
    expect(selects()[0]!.props('modelValue')).toEqual(['agent-1', 'agent-2'])
    expect(selects()[1]!.props('modelValue')).toBe('agent-1')
    expect(document.body.textContent).toContain('已绑定甲')
    expect(document.body.textContent).toContain('已绑定乙')
    expect(mocks.showMessage.mock.calls.filter((call) => call[0] === 'error')).toHaveLength(1)
    selects()[0]!.vm.$emit('update:modelValue', [])
    await flushPromises()
    await saveBindingDraft()
    expect(mocks.bind).toHaveBeenCalledExactlyOnceWith('channel-1', [])
    expect(mocks.detail).toHaveBeenCalledTimes(1)
    expect(mocks.agents).toHaveBeenCalledTimes(1)
  })
  it('重读候选不重新读取渠道，也不覆盖用户已改的成员和默认值', async () => {
    mocks.agents.mockRejectedValueOnce(new Error('目录暂时不可读'))
    await openBindings()
    selects()[0]!.vm.$emit('update:modelValue', ['agent-2'])
    await flushPromises()
    selects()[1]!.vm.$emit('update:modelValue', 'agent-2')
    await flushPromises()
    await click('重新读取可选智能体')
    expect(mocks.detail).toHaveBeenCalledTimes(1)
    expect(mocks.agents).toHaveBeenCalledTimes(2)
    expect(selects()[0]!.props('modelValue')).toEqual(['agent-2'])
    expect(selects()[1]!.props('modelValue')).toBe('agent-2')
    expect(document.body.textContent).toContain('已绑定乙')
    await saveBindingDraft()
    expect(mocks.bind).toHaveBeenCalledExactlyOnceWith('channel-1', [
      { agent_id: 'agent-2', priority: 0, is_default: true }
    ])
  })
  it('目录尚未返回时也可查看绑定；成功后保留绑定名称，仅展示已发布新增候选', async () => {
    let resolveDirectory!: (value: unknown) => void
    mocks.agents.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveDirectory = resolve
      })
    )
    await openBindings()
    expect(bindingDrawer().props('modelValue')).toBe(true)
    expect(selects()[0]!.props('disabled')).toBe(false)
    expect(selects()[0]!.props('loading')).toBe(true)
    resolveDirectory({
      items: [
        { id: 'agent-1', name: '新目录名称', status: 'published' },
        { id: 'agent-3', name: '可新增成员', status: 'published' },
        { id: 'draft-agent', name: '不可新增草稿', status: 'draft' }
      ]
    })
    await flushPromises()
    expect(document.body.textContent).toContain('已绑定甲')
    expect(document.body.textContent).not.toContain('不可新增草稿')
    selects()[0]!.vm.$emit('update:modelValue', ['agent-1', 'agent-3'])
    await flushPromises()
    await saveBindingDraft()
    expect(mocks.bind).toHaveBeenCalledExactlyOnceWith('channel-1', [
      { agent_id: 'agent-1', priority: 0, is_default: true },
      { agent_id: 'agent-3', priority: 1, is_default: false }
    ])
  })
  it('绑定保存成功后列表GET失败只允许刷新列表，不再次提交绑定', async () => {
    await openBindings()
    mocks.list.mockRejectedValueOnce(new Error('列表尚未刷新'))
    await saveBindingDraft()
    expect(mocks.bind).toHaveBeenCalledTimes(1)
    expect(mocks.success).toHaveBeenCalledWith('智能体绑定已保存')
    await click('刷新')
    expect(mocks.bind).toHaveBeenCalledTimes(1)
    expect(mocks.list).toHaveBeenCalledTimes(3)
  })
})
