import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElInput } from 'element-plus'
import ConversationTasksPage from './ConversationTasksPage.vue'
import { HttpClientError } from '@skillforge/shared/utils/http'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  sessions: vi.fn(),
  update: vi.fn(),
  receipt: vi.fn(),
  showMessage: vi.fn(),
  success: vi.fn()
}))
vi.mock('../../api', () => ({
  getSessionId: () => 'browser-session',
  api: {
    chat: { sessions: mocks.sessions },
    conversationTasks: { list: mocks.list, update: mocks.update, receipt: mocks.receipt }
  }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    identityVersion: 1,
    user: { tenant_id: 'tenant-1', sub: 'user-1', permissions: ['foundation.sessions.read'] }
  })
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { sessionId: 'session-1' } }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@skillforge/shared/feedback')>()
  return {
    ...actual,
    showMessage: mocks.showMessage,
    message: { ...actual.message, success: mocks.success }
  }
})
const task = {
  taskId: 'task-1',
  tenantId: { value: 'tenant-1' },
  endUserId: { value: 'user-1' },
  agentId: { value: 'agent-1' },
  revision: 3,
  status: 'OPEN',
  title: '核对资料',
  summary: '等待原始资料',
  state: { schemaId: 'task.v1', canonicalJson: '{}' },
  sources: [],
  updatedAt: '2026-10-29T00:00:00Z'
}
let wrapper: VueWrapper
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.sessions.mockResolvedValue([{ session_id: 'session-1', summary: '资料会话' }])
  mocks.list.mockResolvedValue([task])
  mocks.showMessage.mockReturnValue(vi.fn())
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function openCorrection() {
  wrapper = mount(ConversationTasksPage, { attachTo: document.body })
  await flushPromises()
  await wrapper
    .findAllComponents(ElButton)
    .find((button) => button.text() === '纠正')!
    .trigger('click')
  await flushPromises()
  const drawer = wrapper.findComponent(ElDrawer)
  await drawer.findComponent(ElInput).setValue('已收到资料，待人工核对')
  return drawer
}
async function submit(drawer: VueWrapper) {
  await drawer
    .findAllComponents(ElButton)
    .find((button) => button.text() === '提交纠正')!
    .trigger('click')
  await flushPromises()
}

describe('任务纠正失败反馈与显式重试', () => {
  it('明确在修改前拒绝时保留草稿并允许修正', async () => {
    mocks.update.mockRejectedValueOnce(
      new HttpClientError({
        status: 409,
        code: 'TASK_OPERATION_REJECTED_BEFORE_MUTATION',
        userMessage: '任务已更新'
      })
    )
    const drawer = await openCorrection()
    await submit(drawer)
    expect(drawer.props('modelValue')).toBe(true)
    expect(drawer.findComponent(ElInput).props('modelValue')).toBe('已收到资料，待人工核对')
    expect(
      drawer
        .findAllComponents(ElButton)
        .find((item) => item.text() === '提交纠正')!
        .props('disabled')
    ).toBe(false)
    expect(drawer.findAllComponents(ElButton).some((item) => item.text() === '核对原操作')).toBe(
      false
    )
  })
  it('自动核对失败只展示一次顶部错误，保留原草稿且不自动重发', async () => {
    mocks.update.mockRejectedValueOnce(new Error('当前修订已过期'))
    mocks.receipt.mockRejectedValueOnce(new Error('回执暂不可用'))
    const drawer = await openCorrection()
    await submit(drawer)
    expect(mocks.showMessage).toHaveBeenCalledTimes(1)
    expect(mocks.showMessage).toHaveBeenCalledWith(
      'error',
      '回执暂不可用',
      expect.anything(),
      expect.anything()
    )
    expect(drawer.props('modelValue')).toBe(true)
    expect(drawer.findComponent(ElInput).props('modelValue')).toBe('已收到资料，待人工核对')
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(mocks.success).not.toHaveBeenCalled()
  })
  it('结果未知时只读核对原请求，未找到不重发，确认后刷新', async () => {
    mocks.update.mockRejectedValueOnce(new Error('连接断开'))
    mocks.receipt
      .mockResolvedValueOnce({ status: 'UNKNOWN', task: null })
      .mockResolvedValueOnce({ status: 'UNKNOWN', task: null })
      .mockResolvedValueOnce({ status: 'SUCCEEDED', task })
    const drawer = await openCorrection()
    await submit(drawer)
    await submit(drawer)
    expect(mocks.update).toHaveBeenCalledTimes(1)
    const operation = mocks.update.mock.calls[0]![3]
    const check = () =>
      drawer.findAllComponents(ElButton).find((item) => item.text() === '核对原操作')!
    await check().trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(true)
    expect(drawer.findComponent(ElInput).props('modelValue')).toBe('已收到资料，待人工核对')
    await check().trigger('click')
    await flushPromises()
    expect(mocks.receipt).toHaveBeenNthCalledWith(1, 'session-1', 'task-1', operation)
    expect(mocks.receipt).toHaveBeenNthCalledWith(2, 'session-1', 'task-1', operation)
    expect(mocks.receipt).toHaveBeenNthCalledWith(3, 'session-1', 'task-1', operation)
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(drawer.props('modelValue')).toBe(false)
    expect(mocks.success).toHaveBeenCalledExactlyOnceWith('原任务操作已确认')
    expect(mocks.showMessage).not.toHaveBeenCalled()
  })
  it('纠正成功但读取失败时保留已完成状态，只允许刷新列表', async () => {
    mocks.update.mockResolvedValueOnce(task)
    const drawer = await openCorrection()
    mocks.list.mockRejectedValueOnce(new Error('任务列表刷新失败'))
    await submit(drawer)
    expect(drawer.props('modelValue')).toBe(false)
    expect(wrapper.text()).toContain('任务操作已完成，但列表尚未更新')
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((item) => item.text() === '纠正')!
        .props('disabled')
    ).toBe(true)
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(mocks.success).not.toHaveBeenCalled()
    await wrapper
      .findAllComponents(ElButton)
      .find((item) => item.text() === '刷新当前任务')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('任务操作已完成，但列表尚未更新')
    expect(mocks.update).toHaveBeenCalledTimes(1)
  })
})
