import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElInput, ElSelect, ElPagination, ElCollapse } from 'element-plus'
import { HttpClientError } from '@skillforge/shared/utils/http'
import HandoffPage from './HandoffPage.vue'
import ConnectorsPage from './ConnectorsPage.vue'
import OpenApiDocsPage from './OpenApiDocsPage.vue'
import MarketplacePage from './MarketplacePage.vue'

const mocks = vi.hoisted(() => ({
  routeQuery: {} as Record<string, string>,
  permissions: ['foundation.handoff.read', 'foundation.handoff.update'],
  handoffList: vi.fn(),
  handoffDetail: vi.fn(),
  accept: vi.fn(),
  resolve: vi.fn(),
  actionReceipt: vi.fn(),
  reply: vi.fn(),
  replyReceipt: vi.fn(),
  marketplaceSearch: vi.fn(),
  connectorList: vi.fn(),
  connectorDetail: vi.fn(),
  mcpTools: vi.fn(),
  updateInstance: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  confirm: vi.fn(),
  push: vi.fn()
}))
vi.mock('../../api', () => ({
  api: {
    handoff: {
      list: mocks.handoffList,
      detail: mocks.handoffDetail,
      accept: mocks.accept,
      resolve: mocks.resolve,
      actionReceipt: mocks.actionReceipt,
      reply: mocks.reply,
      replyReceipt: mocks.replyReceipt
    },
    marketplace: { search: mocks.marketplaceSearch },
    connectors: {
      instances: mocks.connectorList,
      instanceDetail: mocks.connectorDetail,
      mcpTools: mocks.mcpTools,
      updateInstance: mocks.updateInstance
    }
  }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    user: { tenant_id: 'tenant', sub: 'member', permissions: mocks.permissions },
    identityVersion: 1
  })
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: mocks.routeQuery }),
  useRouter: () => ({ push: mocks.push }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm,
  message: { success: mocks.success, warning: mocks.warning }
}))
const wrappers: VueWrapper[] = []
const ticket = {
  id: 'ticket-1',
  user: '来访者',
  reason: '需要人工处理',
  priority: 1,
  status: 'pending',
  revision: 1
}
const connector = {
  instance_id: 'mcp-1',
  id: 'mcp-1',
  name: '资料查询',
  base_url: 'https://example.com/mcp',
  auth_type: 'none',
  status: 'active',
  capabilities: ['existing-tool']
}
function button(wrapper: VueWrapper, text: string) {
  const result = wrapper.findAllComponents(ElButton).find((item) => item.text() === text)
  if (!result) throw Error(text)
  return result
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.routeQuery = {}
  mocks.permissions = ['foundation.handoff.read', 'foundation.handoff.update']
  mocks.handoffList.mockResolvedValue({ items: [ticket] })
  mocks.handoffDetail.mockResolvedValue({
    ticket: { ...ticket, status: 'assigned', assigned_to: 'member', revision: 3 },
    messages: [],
    replies: [],
    has_more: false
  })
  mocks.connectorList.mockResolvedValue({ items: [connector] })
  mocks.connectorDetail.mockResolvedValue(connector)
  mocks.mcpTools.mockResolvedValue({
    items: [{ name: 'search', qualified_name: 'mcp-1.search', description: '检索资料' }]
  })
  mocks.accept.mockImplementation(async (id, _revision, command_id) => ({
    command_id,
    ticket_id: id,
    action: 'accept',
    status: 'assigned'
  }))
  mocks.resolve.mockImplementation(async (id, _revision, command_id) => ({
    command_id,
    ticket_id: id,
    action: 'resolve',
    status: 'resolved'
  }))
  mocks.updateInstance.mockResolvedValue({})
  mocks.marketplaceSearch.mockResolvedValue({ items: [], total: 41 })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})
function render(component: typeof HandoffPage) {
  const wrapper = mount(component, {
    attachTo: document.body,
    global: { stubs: { CodeEditor: true } }
  })
  wrappers.push(wrapper)
  return wrapper
}

describe('机构日常任务简化', () => {
  it('工单状态切换失败时保留旧列表和对应筛选，重试成功才切换', async () => {
    const wrapper = render(HandoffPage)
    await flushPromises()
    const select = wrapper.getComponent(ElSelect)
    mocks.handoffList.mockRejectedValueOnce(Error('工单列表暂时不可用'))
    select.vm.$emit('update:modelValue', 'resolved')
    select.vm.$emit('change', 'resolved')
    await flushPromises()
    expect(select.props('modelValue')).toBe('pending')
    expect(wrapper.text()).toContain('需要人工处理')
    mocks.handoffList.mockResolvedValueOnce({
      items: [{ ...ticket, status: 'resolved', reason: '已解决工单' }]
    })
    select.vm.$emit('update:modelValue', 'resolved')
    select.vm.$emit('change', 'resolved')
    await flushPromises()
    expect(select.props('modelValue')).toBe('resolved')
    expect(wrapper.text()).toContain('已解决工单')
  })
  it('首次读取工单失败不伪装成没有待处理工单', async () => {
    mocks.handoffList.mockRejectedValueOnce(Error('工单列表暂时不可用'))
    const wrapper = render(HandoffPage)
    await flushPromises()
    expect(wrapper.text()).toContain('工单列表尚未读取，请刷新重试')
    expect(wrapper.text()).not.toContain('暂无待处理工单')
  })
  it.each([
    ['pending', undefined, false],
    ['assigned', 'other', false],
    ['assigned', 'member', true]
  ])('工单 %s 的处理权限决定回复和解决入口', async (status, assignee, allowed) => {
    mocks.handoffDetail.mockResolvedValue({
      ticket: { ...ticket, status, assigned_to: assignee, revision: 3 },
      messages: [],
      replies: [],
      has_more: false
    })
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    const input = wrapper
      .findAllComponents(ElInput)
      .find((item) => item.props('type') === 'textarea')!
    expect(input.props('disabled')).toBe(!allowed)
    wrapper.getComponent(ElCollapse).vm.$emit('update:modelValue', ['resolve'])
    await flushPromises()
    const resolution = wrapper
      .findAllComponents(ElInput)
      .filter((item) => item.props('type') === 'textarea')[1]!
    expect(resolution.props('disabled')).toBe(!allowed)
    if (!allowed) {
      expect(button(wrapper, '发送回复').props('disabled')).toBe(true)
      expect(document.body.textContent).toContain(
        status === 'pending' ? '请先领取工单' : '此工单由其他坐席处理'
      )
    }
    expect(mocks.reply).not.toHaveBeenCalled()
  })
  it('领取后读取新状态并直接开放回复', async () => {
    mocks.handoffDetail
      .mockResolvedValueOnce({
        ticket: { ...ticket, revision: 3 },
        messages: [],
        replies: [],
        has_more: false
      })
      .mockResolvedValueOnce({
        ticket: { ...ticket, status: 'assigned', assigned_to: 'member', revision: 4 },
        messages: [],
        replies: [],
        has_more: false
      })
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    const input = wrapper
      .findAllComponents(ElInput)
      .find((item) => item.props('type') === 'textarea')!
    expect(input.props('disabled')).toBe(true)
    await button(wrapper, '领取工单').trigger('click')
    await flushPromises()
    expect(mocks.accept).toHaveBeenCalledExactlyOnceWith('ticket-1', 3, expect.any(String))
    expect(input.props('disabled')).toBe(false)
  })
  it('管理员可以处理其他坐席接管的工单', async () => {
    mocks.permissions.push('foundation.handoff.manage')
    mocks.handoffDetail.mockResolvedValue({
      ticket: { ...ticket, status: 'assigned', assigned_to: 'other', revision: 3 },
      messages: [],
      replies: [],
      has_more: false
    })
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    expect(
      wrapper
        .findAllComponents(ElInput)
        .find((item) => item.props('type') === 'textarea')!
        .props('disabled')
    ).toBe(false)
  })
  it('接管默认读取待办，领取并处理使用详情中的最新修订', async () => {
    mocks.handoffDetail.mockResolvedValue({
      ticket: { ...ticket, revision: 3 },
      messages: [],
      replies: [],
      has_more: false
    })
    const wrapper = render(HandoffPage)
    await flushPromises()
    expect(mocks.handoffList).toHaveBeenCalledWith('pending')
    await button(wrapper, '领取并处理').trigger('click')
    await flushPromises()
    expect(mocks.handoffDetail).toHaveBeenCalledWith('ticket-1')
    expect(mocks.accept).toHaveBeenCalledExactlyOnceWith('ticket-1', 3, expect.any(String))
  })
  it('工单详情已经解决时，快捷领取不提交旧列表修订', async () => {
    mocks.handoffDetail.mockResolvedValue({
      ticket: { ...ticket, status: 'resolved', revision: 8 },
      messages: [],
      replies: []
    })
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '领取并处理').trigger('click')
    await flushPromises()
    expect(mocks.accept).not.toHaveBeenCalled()
    expect(button(wrapper, '发送回复').props('disabled')).toBe(true)
  })
  it('详情读取失败时不沿用上一次打开的工单执行快捷领取', async () => {
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    mocks.handoffDetail.mockRejectedValueOnce(Error('暂时无法读取工单'))
    await button(wrapper, '领取并处理').trigger('click')
    await flushPromises()
    expect(mocks.accept).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('暂时无法读取工单')
  })
  it('领取响应中断后重进自动核对原命令，不依赖待办列表', async () => {
    mocks.handoffDetail.mockResolvedValue({
      ticket: { ...ticket, revision: 3 },
      messages: [],
      replies: [],
      has_more: false
    })
    mocks.accept.mockRejectedValueOnce(Error('连接中断'))
    const first = render(HandoffPage)
    await flushPromises()
    await button(first, '领取并处理').trigger('click')
    await flushPromises()
    const command = mocks.accept.mock.calls[0]![2]
    expect(document.body.textContent).toContain(command)
    expect(button(first, '领取工单').props('disabled')).toBe(true)
    first.unmount()
    mocks.handoffList.mockResolvedValue({ items: [] })
    mocks.handoffDetail
      .mockResolvedValueOnce({
        ticket: { ...ticket, revision: 3 },
        messages: [],
        replies: [],
        has_more: false
      })
      .mockResolvedValueOnce({
        ticket: { ...ticket, revision: 3 },
        messages: [],
        replies: [],
        has_more: false
      })
      .mockResolvedValue({
        ticket: { ...ticket, status: 'assigned', assigned_to: 'member', revision: 4 },
        messages: [],
        replies: [],
        has_more: false
      })
    mocks.actionReceipt.mockResolvedValueOnce({
      command_id: command,
      ticket_id: 'ticket-1',
      action: 'accept',
      status: 'assigned'
    })
    const second = render(HandoffPage)
    await flushPromises()
    expect(mocks.actionReceipt).toHaveBeenCalledExactlyOnceWith('ticket-1', command)
    expect(mocks.accept).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).not.toContain('工单操作结果尚未确认')
    expect(button(second, '发送回复').exists()).toBe(true)
    expect(
      second
        .findAllComponents(ElInput)
        .find((item) => item.props('type') === 'textarea')!
        .props('disabled')
    ).toBe(false)
    expect(
      sessionStorage.getItem(`skillforge.handoff-action.${JSON.stringify(['tenant', 'member'])}`)
    ).toBeNull()
  })
  it('原工单详情首次读取失败时保留原命令并可原地重读', async () => {
    const commandId = '12345678-1234-4234-8234-123456789abc'
    const key = `skillforge.handoff-action.${JSON.stringify(['tenant', 'member'])}`
    const pending = JSON.stringify({ id: 'ticket-1', commandId, action: 'accept', revision: 3 })
    sessionStorage.setItem(key, pending)
    mocks.handoffDetail.mockRejectedValueOnce(Error('工单暂不可读'))
    mocks.actionReceipt.mockResolvedValueOnce(null)
    const wrapper = render(HandoffPage)
    await flushPromises()
    expect(sessionStorage.getItem(key)).toBe(pending)
    expect(wrapper.text()).toContain(commandId)
    expect(mocks.accept).not.toHaveBeenCalled()
    await button(wrapper, '重新读取原工单').trigger('click')
    await flushPromises()
    expect(mocks.actionReceipt).toHaveBeenCalledExactlyOnceWith('ticket-1', commandId)
    expect(document.body.textContent).toContain('工单操作结果尚未确认')
    expect(sessionStorage.getItem(key)).toBe(pending)
  })
  it('原工单操作没有匹配回执时保持锁定且不重复执行', async () => {
    const commandId = '12345678-1234-4234-8234-123456789abc'
    const key = `skillforge.handoff-action.${JSON.stringify(['tenant', 'member'])}`
    const pending = JSON.stringify({
      id: 'ticket-1',
      commandId,
      action: 'resolve',
      revision: 3,
      digest: 'a'.repeat(64)
    })
    sessionStorage.setItem(key, pending)
    mocks.actionReceipt.mockResolvedValueOnce(null)
    const wrapper = render(HandoffPage)
    await flushPromises()
    expect(mocks.actionReceipt).toHaveBeenCalledExactlyOnceWith('ticket-1', commandId)
    expect(sessionStorage.getItem(key)).toBe(pending)
    expect(document.body.textContent).toContain('工单操作结果尚未确认')
    expect(button(wrapper, '发送回复').props('disabled')).toBe(true)
    expect(mocks.resolve).not.toHaveBeenCalled()
  })
  it('领取的明确未受理回执解除未知锁定并要求刷新工单', async () => {
    mocks.handoffDetail.mockResolvedValue({
      ticket: { ...ticket, revision: 3 },
      messages: [],
      replies: [],
      has_more: false
    })
    mocks.accept.mockRejectedValueOnce(
      new HttpClientError({
        status: 409,
        code: 'HANDOFF_ACTION_NOT_ACCEPTED',
        userMessage: '修订已变化'
      })
    )
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '领取并处理').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('当前工单信息需要更新')
    expect(document.body.textContent).not.toContain('工单操作结果尚未确认')
    expect(
      sessionStorage.getItem(`skillforge.handoff-action.${JSON.stringify(['tenant', 'member'])}`)
    ).toBeNull()
    expect(button(wrapper, '领取工单').props('disabled')).toBe(true)
  })
  it('无法保存原工单操作标识时不派发领取', async () => {
    mocks.handoffDetail.mockResolvedValue({
      ticket: { ...ticket, revision: 3 },
      messages: [],
      replies: [],
      has_more: false
    })
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
      throw Error('blocked')
    })
    await button(wrapper, '领取工单').trigger('click')
    await flushPromises()
    expect(mocks.accept).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('尚未发送请求')
    storage.mockRestore()
  })
  it('解决请求中断后保留说明，确认原回执后刷新状态', async () => {
    mocks.resolve.mockRejectedValueOnce(Error('连接中断'))
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    wrapper.getComponent(ElCollapse).vm.$emit('update:modelValue', ['resolve'])
    await flushPromises()
    const note = wrapper
      .findAllComponents(ElInput)
      .filter((item) => item.props('type') === 'textarea')[1]!
    note.vm.$emit('update:modelValue', '已核实并完成')
    await flushPromises()
    await button(wrapper, '标记已解决').trigger('click')
    await flushPromises()
    const command = mocks.resolve.mock.calls[0]![2]
    expect(mocks.resolve).toHaveBeenCalledExactlyOnceWith('ticket-1', 3, command, '已核实并完成')
    expect(note.props('modelValue')).toBe('已核实并完成')
    expect(button(wrapper, '标记已解决').props('disabled')).toBe(true)
    mocks.actionReceipt.mockResolvedValueOnce({
      command_id: command,
      ticket_id: 'ticket-1',
      action: 'resolve',
      status: 'resolved'
    })
    mocks.handoffDetail.mockResolvedValue({
      ticket: { ...ticket, status: 'resolved', revision: 4 },
      messages: [],
      replies: [],
      has_more: false
    })
    await button(wrapper, '刷新状态').trigger('click')
    await flushPromises()
    expect(mocks.resolve).toHaveBeenCalledTimes(1)
    expect(note.props('modelValue')).toBe('')
    expect(document.body.textContent).not.toContain('工单操作结果尚未确认')
  })
  it('领取未知且无回执时仅在确认后继续同一命令', async () => {
    const commandId = '12345678-1234-4234-8234-123456789abc'
    const key = `skillforge.handoff-action.${JSON.stringify(['tenant', 'member'])}`
    sessionStorage.setItem(
      key,
      JSON.stringify({ id: 'ticket-1', commandId, action: 'accept', revision: 3 })
    )
    mocks.handoffDetail.mockResolvedValue({
      ticket: { ...ticket, revision: 3 },
      messages: [],
      replies: [],
      has_more: false
    })
    mocks.actionReceipt.mockResolvedValueOnce(null)
    mocks.confirm.mockResolvedValueOnce(true)
    const wrapper = render(HandoffPage)
    await flushPromises()
    expect(mocks.accept).not.toHaveBeenCalled()
    await button(wrapper, '继续原工单操作').trigger('click')
    await flushPromises()
    expect(mocks.accept).toHaveBeenCalledExactlyOnceWith('ticket-1', 3, commandId)
    expect(sessionStorage.getItem(key)).toBeNull()
  })
  it('解决未知重进后仅保存摘要，重录一致并确认后继续原命令', async () => {
    mocks.resolve.mockRejectedValueOnce(Error('连接中断'))
    const first = render(HandoffPage)
    await flushPromises()
    await button(first, '查看工单').trigger('click')
    await flushPromises()
    first.getComponent(ElCollapse).vm.$emit('update:modelValue', ['resolve'])
    await flushPromises()
    first
      .findAllComponents(ElInput)
      .filter((item) => item.props('type') === 'textarea')[1]!
      .vm.$emit('update:modelValue', '原解决说明')
    await flushPromises()
    await button(first, '标记已解决').trigger('click')
    await flushPromises()
    const command = mocks.resolve.mock.calls[0]![2]
    const key = `skillforge.handoff-action.${JSON.stringify(['tenant', 'member'])}`
    expect(sessionStorage.getItem(key)).not.toContain('原解决说明')
    first.unmount()
    mocks.actionReceipt.mockResolvedValueOnce(null)
    const second = render(HandoffPage)
    await flushPromises()
    const recovery = second
      .findAllComponents(ElInput)
      .find(
        (item) =>
          item.attributes('aria-label') === '重新输入原解决说明' ||
          item.props('ariaLabel') === '重新输入原解决说明'
      )!
    recovery.vm.$emit('update:modelValue', '错误说明')
    await flushPromises()
    await button(second, '继续原工单操作').trigger('click')
    await flushPromises()
    expect(mocks.resolve).toHaveBeenCalledTimes(1)
    recovery.vm.$emit('update:modelValue', '原解决说明')
    mocks.confirm.mockResolvedValueOnce(true)
    await flushPromises()
    await button(second, '继续原工单操作').trigger('click')
    await flushPromises()
    expect(mocks.resolve).toHaveBeenLastCalledWith('ticket-1', 3, command, '原解决说明')
    expect(sessionStorage.getItem(key)).toBeNull()
  })
  it('人工回复结果未知时冻结原内容，只读核对同一命令回执，不发送第二条回复', async () => {
    mocks.reply.mockRejectedValueOnce(Error('连接中断'))
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    const input = wrapper
      .findAllComponents(ElInput)
      .find((item) => item.props('type') === 'textarea')!
    input.vm.$emit('update:modelValue', '已核对您的问题')
    await flushPromises()
    await button(wrapper, '发送回复').trigger('click')
    await flushPromises()
    const command = mocks.reply.mock.calls[0]![2]
    expect(input.props('modelValue')).toBe('已核对您的问题')
    expect(input.props('disabled')).toBe(true)
    expect(button(wrapper, '发送回复').props('disabled')).toBe(true)
    expect(button(wrapper, '关闭').props('disabled')).toBe(true)
    await button(wrapper, '刷新状态').trigger('click')
    await flushPromises()
    expect(button(wrapper, '发送回复').props('disabled')).toBe(true)
    mocks.replyReceipt.mockResolvedValueOnce({
      ticket_id: 'ticket-1',
      command_id: command,
      status: 'committed',
      content: '已核对您的问题'
    })
    await button(wrapper, '刷新状态').trigger('click')
    await flushPromises()
    expect(input.props('modelValue')).toBe('')
    expect(input.props('disabled')).toBe(false)
    expect(button(wrapper, '关闭').props('disabled')).toBe(false)
    expect(mocks.reply).toHaveBeenCalledTimes(1)
    expect(mocks.success).toHaveBeenCalledWith('人工回复已写入会话')
  })
  it('回复已受理但详情刷新失败时保留成功事实，重新读取不重复写入', async () => {
    mocks.reply.mockImplementation(async (id, _revision, command_id) => ({
      ticket_id: id,
      command_id,
      status: 'accepted'
    }))
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    wrapper
      .findAllComponents(ElInput)
      .find((item) => item.props('type') === 'textarea')!
      .vm.$emit('update:modelValue', '收到')
    mocks.handoffDetail.mockRejectedValueOnce(Error('读取失败'))
    await flushPromises()
    await button(wrapper, '发送回复').trigger('click')
    await flushPromises()
    expect(mocks.success).toHaveBeenCalledWith('人工回复已受理，尚待写入会话')
    expect(document.body.textContent).toContain('当前工单信息需要更新')
    expect(button(wrapper, '领取工单').props('disabled')).toBe(true)
    await button(wrapper, '刷新状态').trigger('click')
    await flushPromises()
    expect(mocks.reply).toHaveBeenCalledTimes(1)
    expect(button(wrapper, '领取工单').props('disabled')).toBe(true)
  })
  it('无专用回执的回复拒绝保留原身份并冻结为未知结果', async () => {
    mocks.reply.mockRejectedValueOnce(
      new HttpClientError({ status: 409, userMessage: '工单修订已变化' })
    )
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    const input = wrapper
      .findAllComponents(ElInput)
      .find((item) => item.props('type') === 'textarea')!
    input.vm.$emit('update:modelValue', '收到')
    await flushPromises()
    await button(wrapper, '发送回复').trigger('click')
    await flushPromises()
    expect(input.props('modelValue')).toBe('收到')
    expect(input.props('disabled')).toBe(true)
    expect(button(wrapper, '关闭').props('disabled')).toBe(true)
  })
  it('已接管筛选使用服务端 assigned 状态', async () => {
    const wrapper = render(HandoffPage)
    await flushPromises()
    const select = wrapper.findComponent(ElSelect)
    select.vm.$emit('update:modelValue', 'assigned')
    select.vm.$emit('change', 'assigned')
    await flushPromises()
    expect(mocks.handoffList).toHaveBeenLastCalledWith('assigned')
  })
  it('过长回复不提交，首次专码拒绝解除锁并要求刷新', async () => {
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    const input = wrapper
      .findAllComponents(ElInput)
      .find((item) => item.props('type') === 'textarea')!
    input.vm.$emit('update:modelValue', 'a'.repeat(8193))
    await flushPromises()
    await button(wrapper, '发送回复').trigger('click')
    await flushPromises()
    expect(mocks.reply).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('不能超过 8192')
    input.vm.$emit('update:modelValue', '有效回复')
    await flushPromises()
    mocks.reply.mockRejectedValueOnce(
      new HttpClientError({
        status: 409,
        code: 'HANDOFF_REPLY_NOT_ACCEPTED',
        userMessage: '工单修订已变化'
      })
    )
    await button(wrapper, '发送回复').trigger('click')
    await flushPromises()
    expect(document.body.textContent).not.toContain('回复发送结果尚未确认')
    expect(document.body.textContent).toContain('当前工单信息需要更新')
    expect(sessionStorage.length).toBe(0)
  })
  it('历史 UNKNOWN 显式继续收到确定未受理专码后解除并要求刷新', async () => {
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    const input = wrapper
      .findAllComponents(ElInput)
      .find((item) => item.props('type') === 'textarea')!
    input.vm.$emit('update:modelValue', '有效回复')
    mocks.reply.mockRejectedValueOnce(Error('断连'))
    await flushPromises()
    await button(wrapper, '发送回复').trigger('click')
    await flushPromises()
    const command = mocks.reply.mock.calls[0]![2]
    mocks.confirm.mockResolvedValueOnce(true)
    mocks.reply.mockRejectedValueOnce(
      new HttpClientError({
        status: 409,
        code: 'HANDOFF_REPLY_NOT_ACCEPTED',
        userMessage: '工单已改变'
      })
    )
    await button(wrapper, '继续原回复').trigger('click')
    await flushPromises()
    expect(mocks.reply).toHaveBeenLastCalledWith('ticket-1', 3, command, '有效回复')
    expect(document.body.textContent).not.toContain('回复发送结果尚未确认')
    expect(document.body.textContent).toContain('当前工单信息需要更新')
    expect(sessionStorage.length).toBe(0)
    expect(mocks.reply).toHaveBeenCalledTimes(2)
  })
  it('历史 UNKNOWN 显式继续收到普通冲突仍保留原命令', async () => {
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    const input = wrapper
      .findAllComponents(ElInput)
      .find((item) => item.props('type') === 'textarea')!
    input.vm.$emit('update:modelValue', '有效回复')
    mocks.reply.mockRejectedValueOnce(Error('断连'))
    await flushPromises()
    await button(wrapper, '发送回复').trigger('click')
    await flushPromises()
    const command = mocks.reply.mock.calls[0]![2]
    mocks.confirm.mockResolvedValueOnce(true)
    mocks.reply.mockRejectedValueOnce(new HttpClientError({ status: 409, userMessage: '普通冲突' }))
    await button(wrapper, '继续原回复').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain(command)
    expect(sessionStorage.length).toBe(1)
  })
  it('按链接工单及会话精确读取，领取前不执行写入', async () => {
    mocks.routeQuery = { ticket_id: 'ticket-1', session_id: 'session-1' }
    mocks.handoffDetail.mockResolvedValueOnce({
      ticket: { ...ticket, session_id: 'session-1', revision: 7 },
      messages: [],
      replies: []
    })
    const wrapper = render(HandoffPage)
    await flushPromises()
    expect(mocks.handoffDetail).toHaveBeenCalledExactlyOnceWith('ticket-1')
    expect(mocks.accept).not.toHaveBeenCalled()
    await button(wrapper, '领取工单').trigger('click')
    await flushPromises()
    expect(mocks.accept).toHaveBeenCalledExactlyOnceWith('ticket-1', 7, expect.any(String))
  })
  it('重新打开工单自动只读核对原回复，确认后无需手动刷新', async () => {
    const owner = JSON.stringify(['tenant', 'member'])
    const key = `skillforge.handoff-reply.${JSON.stringify([owner, 'ticket-1'])}`
    sessionStorage.setItem(
      key,
      JSON.stringify({
        id: 'ticket-1',
        commandId: 'original-command',
        revision: 3,
        digest: 'a'.repeat(64)
      })
    )
    mocks.replyReceipt.mockResolvedValueOnce({
      ticket_id: 'ticket-1',
      command_id: 'original-command',
      status: 'committed'
    })
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    expect(mocks.replyReceipt).toHaveBeenCalledExactlyOnceWith('ticket-1', 'original-command')
    expect(mocks.reply).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(key)).toBeNull()
    expect(button(wrapper, '发送回复').props('disabled')).toBe(true)
    expect(mocks.success).toHaveBeenCalledWith('人工回复已写入会话')
  })
  it('自动核对未得到匹配回执时保留原回复与手动入口', async () => {
    const owner = JSON.stringify(['tenant', 'member'])
    const key = `skillforge.handoff-reply.${JSON.stringify([owner, 'ticket-1'])}`
    const pending = JSON.stringify({
      id: 'ticket-1',
      commandId: 'original-command',
      revision: 3,
      digest: 'a'.repeat(64)
    })
    sessionStorage.setItem(key, pending)
    mocks.replyReceipt.mockResolvedValueOnce({
      ticket_id: 'ticket-1',
      command_id: 'other',
      status: 'committed'
    })
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    expect(sessionStorage.getItem(key)).toBe(pending)
    expect(button(wrapper, '刷新状态').exists()).toBe(true)
    expect(button(wrapper, '发送回复').props('disabled')).toBe(true)
    expect(mocks.reply).not.toHaveBeenCalled()
  })
  it('自动核对连接失败时仍显示工单并保留原回复', async () => {
    const owner = JSON.stringify(['tenant', 'member'])
    const key = `skillforge.handoff-reply.${JSON.stringify([owner, 'ticket-1'])}`
    const pending = JSON.stringify({
      id: 'ticket-1',
      commandId: 'original-command',
      revision: 3,
      digest: 'a'.repeat(64)
    })
    sessionStorage.setItem(key, pending)
    mocks.replyReceipt.mockRejectedValueOnce(Error('回执暂不可用'))
    const wrapper = render(HandoffPage)
    await flushPromises()
    await button(wrapper, '查看工单').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('回执暂不可用')
    expect(sessionStorage.getItem(key)).toBe(pending)
    expect(button(wrapper, '刷新状态').exists()).toBe(true)
    expect(button(wrapper, '发送回复').props('disabled')).toBe(true)
    expect(mocks.reply).not.toHaveBeenCalled()
  })
  it('原回复跨重建只存摘要，重录一致后才能确认同键继续', async () => {
    mocks.reply.mockRejectedValueOnce(Error('断连'))
    const first = render(HandoffPage)
    await flushPromises()
    await button(first, '查看工单').trigger('click')
    await flushPromises()
    const input = first
      .findAllComponents(ElInput)
      .find((item) => item.props('type') === 'textarea')!
    input.vm.$emit('update:modelValue', '原回复内容')
    await flushPromises()
    await button(first, '发送回复').trigger('click')
    await flushPromises()
    const command = mocks.reply.mock.calls[0]![2],
      revision = mocks.reply.mock.calls[0]![1]
    const stored = [...Array(sessionStorage.length).keys()]
      .map((i) => sessionStorage.getItem(sessionStorage.key(i)!) ?? '')
      .join('')
    expect(stored).toContain(command)
    expect(stored).not.toContain('原回复内容')
    first.unmount()
    const second = render(HandoffPage)
    await flushPromises()
    await button(second, '查看工单').trigger('click')
    await flushPromises()
    const recovered = second
      .findAllComponents(ElInput)
      .find(
        (item) =>
          item.props('ariaLabel') === '重新输入原回复' ||
          item.attributes('aria-label') === '重新输入原回复'
      )!
    expect(recovered).toBeDefined()
    recovered.vm.$emit('update:modelValue', '错误内容')
    await flushPromises()
    await button(second, '继续原回复').trigger('click')
    await flushPromises()
    expect(mocks.reply).toHaveBeenCalledTimes(1)
    recovered.vm.$emit('update:modelValue', '原回复内容')
    await flushPromises()
    mocks.confirm.mockResolvedValueOnce(true)
    mocks.reply.mockResolvedValueOnce({
      ticket_id: 'ticket-1',
      command_id: command,
      status: 'accepted'
    })
    await button(second, '继续原回复').trigger('click')
    await flushPromises()
    expect(mocks.reply).toHaveBeenLastCalledWith('ticket-1', revision, command, '原回复内容')
    expect(mocks.reply).toHaveBeenCalledTimes(2)
  })
  it('市场编辑筛选后翻页仍读取上次搜索条件，显式搜索从首页应用新条件', async () => {
    const wrapper = render(MarketplacePage)
    await flushPromises()
    wrapper.getComponent(ElInput).vm.$emit('update:modelValue', '客户服务')
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', '业务')
    wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(mocks.marketplaceSearch).toHaveBeenLastCalledWith('', undefined, undefined, 2)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.marketplaceSearch).toHaveBeenLastCalledWith('客户服务', undefined, '业务', 1)
  })
  it('连接器名称搜索可清除，接入进入完整工具流程而不再仅更新能力名称', async () => {
    const wrapper = render(ConnectorsPage)
    await flushPromises()
    wrapper.getComponent(ElInput).vm.$emit('update:modelValue', '不存在')
    await flushPromises()
    expect(wrapper.text()).toContain('没有匹配的连接器')
    wrapper.getComponent(ElInput).vm.$emit('update:modelValue', '')
    await flushPromises()
    await button(wrapper, '接入工具').trigger('click')
    expect(mocks.push).toHaveBeenCalledWith({
      path: '/admin/tools',
      query: { connectorId: 'mcp-1' }
    })
    expect(mocks.updateInstance).not.toHaveBeenCalled()
  })
  it('API 按请求方法筛选时自动选中匹配接口，无匹配时可清空筛选', async () => {
    const wrapper = render(OpenApiDocsPage)
    await flushPromises()
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'GET')
    await flushPromises()
    expect(wrapper.get('h2').text()).toBeTruthy()
    expect(wrapper.get('.api-docs-content .el-tag').text()).toBe('GET')
    wrapper.findAllComponents(ElInput)[0]!.vm.$emit('update:modelValue', 'no-such-endpoint')
    await flushPromises()
    expect(wrapper.text()).toContain('没有匹配接口')
    await button(wrapper, '清空筛选').trigger('click')
    await flushPromises()
    expect(wrapper.find('h2').exists()).toBe(true)
    expect(wrapper.getComponent(ElSelect).props('modelValue')).toBe('')
  })
  it('API 基础地址无效时不生成误指向当前机构的示例，失焦后说明修正方法', async () => {
    const wrapper = render(OpenApiDocsPage)
    await flushPromises()
    const input = wrapper.findAllComponents(ElInput)[1]!
    input.vm.$emit('update:modelValue', 'invalid address')
    await flushPromises()
    expect(button(wrapper, '复制示例').props('disabled')).toBe(true)
    input.vm.$emit('blur')
    await flushPromises()
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('请输入有效 HTTP(S) 基础地址后生成调用示例')
    input.vm.$emit('update:modelValue', 'https://api.example.com')
    input.vm.$emit('blur')
    await flushPromises()
    expect(button(wrapper, '复制示例').props('disabled')).toBe(false)
  })
})
