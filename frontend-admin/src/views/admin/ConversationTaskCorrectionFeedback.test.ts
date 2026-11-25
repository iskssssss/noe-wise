import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElInput } from 'element-plus'
import ConversationTasksPage from './ConversationTasksPage.vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { reactive } from 'vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  detail: vi.fn(),
  refresh: vi.fn(),
  fetchMe: vi.fn(),
  sessions: vi.fn(),
  update: vi.fn(),
  receipt: vi.fn(),
  showMessage: vi.fn(),
  success: vi.fn(),
  auth: undefined as unknown as {
    identityVersion: number
    user: { tenant_id: string; sub: string; permissions: string[] }
    fetchMe: () => Promise<void>
  }
}))
vi.mock('../../api', () => ({
  getSessionId: () => 'browser-session',
  refreshAccessToken: mocks.refresh,
  api: {
    chat: { sessions: mocks.sessions },
    conversationTasks: {
      list: mocks.list,
      detail: mocks.detail,
      update: mocks.update,
      receipt: mocks.receipt
    }
  }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => mocks.auth
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { sessionId: 'session-1' } }),
  useRouter: () => ({ replace: vi.fn().mockResolvedValue(undefined) }),
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
  mocks.detail.mockResolvedValue({ ...task, facts: {}, pending: [] })
  mocks.auth = reactive({
    identityVersion: 1,
    fetchMe: mocks.fetchMe,
    user: { tenant_id: 'tenant-1', sub: 'user-1', permissions: ['foundation.sessions.read'] }
  })
  mocks.showMessage.mockReturnValue(vi.fn())
  mocks.refresh.mockResolvedValue('refreshed-access-token')
  mocks.fetchMe.mockResolvedValue(undefined)
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
  it('认证前置拒绝保留更正说明和原修订，刷新登录不重发', async () => {
    mocks.update.mockRejectedValueOnce(
      new HttpClientError({
        status: 401,
        code: 'TASK_OPERATION_REJECTED_BEFORE_MUTATION',
        userMessage: '登录过期'
      })
    )
    const drawer = await openCorrection()
    await submit(drawer)
    expect(drawer.findComponent(ElInput).props('modelValue')).toBe('已收到资料，待人工核对')
    expect(mocks.showMessage).toHaveBeenCalledWith(
      'error',
      expect.stringContaining('本次任务操作未提交'),
      expect.anything(),
      expect.anything()
    )
    expect(mocks.receipt).not.toHaveBeenCalled()
    await drawer
      .findAllComponents(ElButton)
      .find((b) => b.text() === '刷新登录状态')!
      .trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(mocks.detail).toHaveBeenCalledTimes(1)
    expect(drawer.findComponent(ElInput).props('modelValue')).toBe('已收到资料，待人工核对')
    expect(
      drawer
        .findAllComponents(ElButton)
        .find((b) => b.text() === '提交纠正')!
        .props('disabled')
    ).toBe(false)
  })
  it('刷新登录失败保留说明并提供重新登录，不发送业务写入', async () => {
    mocks.update.mockRejectedValueOnce(
      new HttpClientError({
        status: 401,
        code: 'TASK_OPERATION_REJECTED_BEFORE_MUTATION',
        userMessage: '登录过期'
      })
    )
    mocks.refresh.mockResolvedValueOnce(undefined)
    const drawer = await openCorrection()
    await submit(drawer)
    await drawer
      .findAllComponents(ElButton)
      .find((b) => b.text() === '刷新登录状态')!
      .trigger('click')
    await flushPromises()
    expect(drawer.findAllComponents(ElButton).some((b) => b.text() === '重新登录')).toBe(true)
    expect(drawer.findComponent(ElInput).props('modelValue')).toBe('已收到资料，待人工核对')
    expect(mocks.update).toHaveBeenCalledTimes(1)
    expect(mocks.fetchMe).not.toHaveBeenCalled()
  })
  it('登录刷新期间身份变化，不将旧结果应用到新身份', async () => {
    mocks.update.mockRejectedValueOnce(
      new HttpClientError({
        status: 401,
        code: 'TASK_OPERATION_REJECTED_BEFORE_MUTATION',
        userMessage: '登录过期'
      })
    )
    let resolve!: (value: string) => void
    mocks.refresh.mockReturnValueOnce(
      new Promise((r) => {
        resolve = r
      })
    )
    const drawer = await openCorrection()
    await submit(drawer)
    await drawer
      .findAllComponents(ElButton)
      .find((b) => b.text() === '刷新登录状态')!
      .trigger('click')
    await flushPromises()
    mocks.auth.user.sub = 'other-user'
    resolve('new-token')
    await flushPromises()
    expect(mocks.fetchMe).not.toHaveBeenCalled()
    expect(mocks.success).not.toHaveBeenCalled()
    expect(drawer.props('modelValue')).toBe(false)
  })
  it('先读取最新详情，展示完整事实与待办，纠正使用最新修订', async () => {
    mocks.detail.mockResolvedValue({
      ...task,
      revision: 8,
      facts: {
        日期: { value: '周五\n上午', confirmed: true },
        备注: { value: '', confirmed: false }
      },
      pending: ['确认地点']
    })
    mocks.update.mockResolvedValue(task)
    const drawer = await openCorrection()
    expect(document.querySelector('[role=dialog]')?.textContent).toContain('周五\n上午')
    expect(document.querySelector('[role=dialog]')?.textContent).toContain('（空字符串）')
    expect(document.querySelector('[role=dialog]')?.textContent).toContain('用户未确认')
    expect(document.querySelector('[role=dialog]')?.textContent).toContain('确认地点')
    await submit(drawer)
    expect(mocks.update).toHaveBeenCalledWith(
      'session-1',
      expect.objectContaining({ revision: 8 }),
      'correct',
      expect.any(String),
      '已收到资料，待人工核对'
    )
  })
  it('详情读取失败保持重试入口，恢复后空说明不可提交', async () => {
    mocks.detail.mockRejectedValueOnce(new Error('详情读取失败'))
    wrapper = mount(ConversationTasksPage, { attachTo: document.body })
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((b) => b.text() === '纠正')!
      .trigger('click')
    await flushPromises()
    const drawer = wrapper.findComponent(ElDrawer)
    expect(document.querySelector('[role=dialog]')?.textContent).toContain('任务详情尚未读取成功')
    expect(drawer.findComponent(ElInput).exists()).toBe(false)
    expect(mocks.showMessage).toHaveBeenCalledTimes(1)
    await drawer
      .findAllComponents(ElButton)
      .find((b) => b.text() === '重新读取任务')!
      .trigger('click')
    await flushPromises()
    expect(drawer.findComponent(ElInput).props('modelValue')).toBe('')
    expect(
      drawer
        .findAllComponents(ElButton)
        .find((b) => b.text() === '提交纠正')!
        .props('disabled')
    ).toBe(true)
    expect(document.querySelector('[role=dialog]')?.textContent).toContain('尚未记录具体事实')
    expect(document.querySelector('[role=dialog]')?.textContent).toContain('当前没有待补充事项')
    expect(mocks.update).not.toHaveBeenCalled()
  })
  it('已结束任务可看详情，无纠正入口', async () => {
    mocks.detail.mockResolvedValue({ ...task, status: 'COMPLETED', facts: {}, pending: [] })
    wrapper = mount(ConversationTasksPage, { attachTo: document.body })
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((b) => b.text() === task.title)!
      .trigger('click')
    await flushPromises()
    const drawer = wrapper.findComponent(ElDrawer)
    expect(document.querySelector('[role=dialog]')?.textContent).toContain('任务已结束')
    expect(drawer.findAllComponents(ElButton).some((b) => b.text() === '纠正任务')).toBe(false)
  })
  it('关闭后打开另一任务，旧详情的迟到响应不得覆盖新任务', async () => {
    let resolve!: (value: unknown) => void
    mocks.list.mockResolvedValue([task, { ...task, taskId: 'task-2', title: '另一任务' }])
    mocks.detail
      .mockReturnValueOnce(
        new Promise((r) => {
          resolve = r
        })
      )
      .mockResolvedValueOnce({
        ...task,
        taskId: 'task-2',
        title: '另一任务',
        summary: '新任务进展',
        facts: {},
        pending: []
      })
    wrapper = mount(ConversationTasksPage, { attachTo: document.body })
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((b) => b.text() === task.title)!
      .trigger('click')
    await flushPromises()
    const drawer = wrapper.findComponent(ElDrawer)
    expect(document.querySelector('[role=dialog]')?.textContent).toContain('正在读取任务详情')
    await drawer
      .findAllComponents(ElButton)
      .find((b) => b.text() === '关闭')!
      .trigger('click')
    await flushPromises()
    expect(mocks.detail.mock.calls[0]![2].signal.aborted).toBe(true)
    await wrapper
      .findAllComponents(ElButton)
      .find((b) => b.text() === '另一任务')!
      .trigger('click')
    await flushPromises()
    resolve({ ...task, summary: '旧任务迟到', facts: {}, pending: [] })
    await flushPromises()
    expect(document.querySelector('[role=dialog]')?.textContent).toContain('新任务进展')
    expect(document.querySelector('[role=dialog]')?.textContent).not.toContain('旧任务迟到')
  })
  it('身份切换关闭详情并丢弃旧主体响应', async () => {
    let resolve!: (value: unknown) => void
    mocks.detail.mockReturnValueOnce(
      new Promise((r) => {
        resolve = r
      })
    )
    wrapper = mount(ConversationTasksPage, { attachTo: document.body })
    await flushPromises()
    await wrapper
      .findAllComponents(ElButton)
      .find((b) => b.text() === task.title)!
      .trigger('click')
    await flushPromises()
    mocks.auth.user.sub = 'other-user'
    await flushPromises()
    resolve({ ...task, summary: '原身份任务', facts: {}, pending: [] })
    await flushPromises()
    expect(wrapper.findComponent(ElDrawer).props('modelValue')).toBe(false)
    expect(document.body.textContent).not.toContain('原身份任务')
    expect(mocks.detail.mock.calls[0]![2].signal.aborted).toBe(true)
  })
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
