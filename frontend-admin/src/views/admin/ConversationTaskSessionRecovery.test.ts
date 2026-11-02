import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElCheckbox, ElInput, ElOption, ElPagination, ElSelect } from 'element-plus'
import ConversationTasksPage from './ConversationTasksPage.vue'
import { reactive } from 'vue'

const calls = vi.hoisted(() => ({
  sessions: vi.fn(),
  list: vi.fn(),
  select: vi.fn(),
  update: vi.fn(),
  receipt: vi.fn(),
  auth: undefined as unknown as { user: { tenant_id: string; sub: string } }
}))
vi.mock('../../api', () => ({
  api: {
    chat: { sessions: calls.sessions },
    conversationTasks: {
      list: calls.list,
      select: calls.select,
      update: calls.update,
      receipt: calls.receipt
    }
  }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => calls.auth
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
let wrapper: VueWrapper | undefined
const session = { session_id: 'session-1', summary: '资料会话' }
function button(label: string) {
  return wrapper!.findAllComponents(ElButton).find((item) => item.text() === label)!
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  calls.auth = reactive({ user: { tenant_id: 'tenant-1', sub: 'user-1' } })
  calls.sessions.mockResolvedValue([session])
  calls.list.mockResolvedValue([])
  calls.receipt.mockResolvedValue({ status: 'UNKNOWN', task: null })
})

it('切换机构后清除旧任务和会话，忽略旧机构迟到的列表', async () => {
  let finish!: (value: unknown[]) => void
  calls.list.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  wrapper = mount(ConversationTasksPage, { attachTo: document.body })
  await flushPromises()
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'session-1')
  wrapper.findComponent(ElSelect).vm.$emit('change', 'session-1')
  await flushPromises()
  calls.auth.user = { tenant_id: 'tenant-2', sub: 'user-2' }
  await flushPromises()
  finish([{ taskId: 'old', title: '旧机构任务', summary: '', status: 'OPEN', revision: 1 }])
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧机构任务')
  expect(wrapper.findComponent(ElSelect).props('modelValue')).toBe('')
  expect(wrapper.findAllComponents(ElOption)).toHaveLength(0)
  expect(calls.select).not.toHaveBeenCalled()
})

it('切换主体时仅恢复新主体自己的待核对操作', async () => {
  const owner = JSON.stringify(['tenant-2', 'user-2'])
  const operationId = '1d0bb5bf-0000-4000-8000-000000000001'
  sessionStorage.setItem(
    `skillforge.task-operation.${owner}`,
    JSON.stringify({ sessionId: 'new-session', taskId: 'new-task', operationId })
  )
  wrapper = mount(ConversationTasksPage, { attachTo: document.body })
  await flushPromises()
  calls.auth.user = { tenant_id: 'tenant-2', sub: 'user-2' }
  await flushPromises()
  expect(wrapper.text()).toContain(operationId)
  expect(wrapper.findComponent(ElSelect).props('modelValue')).toBe('new-session')
  expect(button('核对原操作').exists()).toBe(true)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})

it('任务操作断连后重新进入仍保存原标识，只有成功回执解除锁定', async () => {
  const task = {
    taskId: 'task-1',
    title: '核对资料',
    summary: '等待资料',
    status: 'OPEN',
    revision: 1
  }
  calls.list.mockResolvedValue([task])
  calls.select.mockRejectedValueOnce(new Error('连接断开'))
  calls.receipt
    .mockResolvedValueOnce({ status: 'UNKNOWN', task: null })
    .mockResolvedValueOnce({ status: 'SUCCEEDED', task })
  wrapper = mount(ConversationTasksPage, { attachTo: document.body })
  await flushPromises()
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'session-1')
  wrapper.findComponent(ElSelect).vm.$emit('change', 'session-1')
  await flushPromises()
  await button('设为当前任务').trigger('click')
  await flushPromises()
  const operationId = calls.select.mock.calls[0]![2]
  expect(wrapper.text()).toContain(operationId)
  wrapper.unmount()
  wrapper = mount(ConversationTasksPage, { attachTo: document.body })
  await flushPromises()
  expect(wrapper.text()).toContain(operationId)
  expect(wrapper.findComponent(ElSelect).props('modelValue')).toBe('session-1')
  expect(button('设为当前任务').props('disabled')).toBe(true)
  expect(calls.receipt).toHaveBeenCalledTimes(1)
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain(operationId)
  expect(button('设为当前任务').props('disabled')).toBe(false)
  expect(calls.receipt).toHaveBeenNthCalledWith(1, 'session-1', 'task-1', operationId)
  expect(calls.receipt).toHaveBeenNthCalledWith(2, 'session-1', 'task-1', operationId)
  expect(calls.select).toHaveBeenCalledTimes(1)
})

it('重新进入后自动确认原任务操作并刷新，不重复写入', async () => {
  const owner = JSON.stringify(['tenant-1', 'user-1'])
  const operationId = '1d0bb5bf-0000-4000-8000-000000000001'
  const key = `skillforge.task-operation.${owner}`
  const task = { taskId: 'task-1', title: '核对资料', summary: '', status: 'OPEN', revision: 2 }
  sessionStorage.setItem(
    key,
    JSON.stringify({ sessionId: 'session-1', taskId: task.taskId, operationId })
  )
  calls.list.mockResolvedValue([task])
  calls.receipt.mockResolvedValueOnce({ status: 'SUCCEEDED', task })
  wrapper = mount(ConversationTasksPage, { attachTo: document.body })
  await flushPromises()
  expect(calls.receipt).toHaveBeenCalledExactlyOnceWith('session-1', 'task-1', operationId)
  expect(sessionStorage.getItem(key)).toBeNull()
  expect(button('设为当前任务').props('disabled')).toBe(false)
  expect(calls.list).toHaveBeenCalledTimes(2)
  expect(calls.select).not.toHaveBeenCalled()
  expect(calls.update).not.toHaveBeenCalled()
})

it('自动核对失败时保留原操作与手动核对入口', async () => {
  const owner = JSON.stringify(['tenant-1', 'user-1'])
  const operationId = '1d0bb5bf-0000-4000-8000-000000000001'
  const key = `skillforge.task-operation.${owner}`
  const pending = JSON.stringify({ sessionId: 'session-1', taskId: 'task-1', operationId })
  sessionStorage.setItem(key, pending)
  calls.receipt.mockRejectedValueOnce(new Error('回执暂不可用'))
  wrapper = mount(ConversationTasksPage, { attachTo: document.body })
  await flushPromises()
  expect(document.body.textContent).toContain('回执暂不可用')
  expect(sessionStorage.getItem(key)).toBe(pending)
  expect(button('核对原操作').exists()).toBe(true)
  expect(calls.select).not.toHaveBeenCalled()
  expect(calls.update).not.toHaveBeenCalled()
})

it('recovers the session picker after an initial failure without reloading the page', async () => {
  calls.sessions.mockRejectedValueOnce(new Error('会话读取失败'))
  wrapper = mount(ConversationTasksPage, { attachTo: document.body })
  await flushPromises()
  expect(button('查询').props('disabled')).toBe(true)
  expect(button('刷新会话').props('disabled')).toBe(false)
  await button('刷新会话').trigger('click')
  await flushPromises()
  expect(calls.sessions).toHaveBeenCalledTimes(2)
  expect(wrapper.findAllComponents(ElOption).map((option) => option.props('label'))).toContain(
    '资料会话'
  )
  expect(calls.list).not.toHaveBeenCalled()
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'session-1')
  wrapper.findComponent(ElSelect).vm.$emit('change', 'session-1')
  await flushPromises()
  expect(calls.list).toHaveBeenCalledWith('session-1', '', false, 0)
})

it('retains the selected session and task result when only the directory refresh fails', async () => {
  calls.list.mockResolvedValue([
    { taskId: 'task-1', title: '核对资料', summary: '等待原始资料', status: 'OPEN', revision: 1 }
  ])
  wrapper = mount(ConversationTasksPage, { attachTo: document.body })
  await flushPromises()
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'session-1')
  wrapper.findComponent(ElSelect).vm.$emit('change', 'session-1')
  await flushPromises()
  calls.sessions.mockRejectedValueOnce(new Error('会话目录暂不可用'))
  await button('刷新会话').trigger('click')
  await flushPromises()
  expect(wrapper.findComponent(ElSelect).props('modelValue')).toBe('session-1')
  expect(wrapper.findAllComponents(ElOption).map((option) => option.props('label'))).toContain(
    '资料会话'
  )
  expect(calls.list).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('核对资料')
  expect(wrapper.text()).toContain('等待原始资料')
  expect(button('查询').props('disabled')).toBe(false)
})

it.each(['设为当前任务', '完成'])(
  '%s成功但刷新失败时锁定旧任务，原地刷新后恢复',
  async (operation) => {
    const task = {
      taskId: 'task-1',
      title: '核对资料',
      summary: '等待资料',
      status: 'OPEN',
      revision: 1
    }
    calls.list.mockResolvedValue([task])
    calls.select.mockResolvedValue(task)
    calls.update.mockResolvedValue(task)
    wrapper = mount(ConversationTasksPage, { attachTo: document.body })
    await flushPromises()
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'session-1')
    wrapper.findComponent(ElSelect).vm.$emit('change', 'session-1')
    await flushPromises()
    calls.list.mockRejectedValueOnce(new Error('任务列表刷新失败'))
    await button(operation).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('任务操作已完成，但列表尚未更新')
    expect(wrapper.text()).toContain('核对资料')
    expect(button('设为当前任务').props('disabled')).toBe(true)
    expect(button('纠正').props('disabled')).toBe(true)
    expect(button('完成').props('disabled')).toBe(true)
    expect(wrapper.findComponent(ElPagination).props('disabled')).toBe(true)
    await button('刷新当前任务').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('任务操作已完成，但列表尚未更新')
    expect(button('完成').props('disabled')).toBe(false)
    expect(calls.select).toHaveBeenCalledTimes(operation === '设为当前任务' ? 1 : 0)
    expect(calls.update).toHaveBeenCalledTimes(operation === '完成' ? 1 : 0)
    expect(calls.list).toHaveBeenLastCalledWith('session-1', '', false, 0)
  }
)

it('keeps the last successful result page and filters after a search fails, then retries the draft from page one', async () => {
  const rows = Array.from({ length: 30 }, (_, index) => ({
    taskId: `task-${index}`,
    title: `原条件任务${index}`,
    summary: '原结果',
    status: 'OPEN',
    revision: 1
  }))
  calls.list.mockResolvedValue(rows)
  wrapper = mount(ConversationTasksPage, { attachTo: document.body })
  await flushPromises()
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'session-1')
  wrapper.findComponent(ElSelect).vm.$emit('change', 'session-1')
  await flushPromises()
  wrapper.findComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  expect(calls.list).toHaveBeenLastCalledWith('session-1', '', false, 30)
  await wrapper.findComponent(ElInput).setValue('新的筛选词')
  wrapper.findComponent(ElCheckbox).vm.$emit('update:modelValue', true)
  calls.list.mockRejectedValueOnce(new Error('筛选读取失败'))
  await button('查询').trigger('click')
  await flushPromises()
  expect(calls.list).toHaveBeenLastCalledWith('session-1', '新的筛选词', true, 0)
  expect(wrapper.findComponent(ElPagination).props('currentPage')).toBe(2)
  expect(wrapper.text()).toContain('原条件任务0')
  expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('新的筛选词')
  expect(wrapper.findComponent(ElCheckbox).props('modelValue')).toBe(true)
  wrapper.findComponent(ElPagination).vm.$emit('current-change', 3)
  await flushPromises()
  expect(calls.list).toHaveBeenLastCalledWith('session-1', '', false, 60)
  await button('查询').trigger('click')
  await flushPromises()
  expect(calls.list).toHaveBeenLastCalledWith('session-1', '新的筛选词', true, 0)
  expect(wrapper.findComponent(ElPagination).props('currentPage')).toBe(1)
  expect(calls.select).not.toHaveBeenCalled()
  expect(calls.update).not.toHaveBeenCalled()
})

it('does not treat a previous session result as a page of the newly selected session after failure', async () => {
  calls.sessions.mockResolvedValue([session, { session_id: 'session-2', summary: '另一会话' }])
  calls.list.mockResolvedValue(
    Array.from({ length: 30 }, (_, index) => ({
      taskId: `old-${index}`,
      title: '原会话任务',
      summary: '只属于原会话',
      status: 'OPEN',
      revision: 1
    }))
  )
  wrapper = mount(ConversationTasksPage, { attachTo: document.body })
  await flushPromises()
  const select = wrapper.findComponent(ElSelect)
  select.vm.$emit('update:modelValue', 'session-1')
  select.vm.$emit('change', 'session-1')
  await flushPromises()
  wrapper.findComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  calls.list.mockRejectedValueOnce(new Error('新会话读取失败'))
  select.vm.$emit('update:modelValue', 'session-2')
  select.vm.$emit('change', 'session-2')
  await flushPromises()
  expect(wrapper.text()).not.toContain('原会话任务')
  expect(wrapper.findComponent(ElPagination).props('currentPage')).toBe(1)
  expect(wrapper.findComponent(ElPagination).props('disabled')).toBe(true)
  const reads = calls.list.mock.calls.length
  wrapper.findComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  expect(calls.list).toHaveBeenCalledTimes(reads)
  calls.list.mockResolvedValue([
    { taskId: 'new', title: '新会话任务', summary: '新的结果', status: 'OPEN', revision: 1 }
  ])
  await button('查询').trigger('click')
  await flushPromises()
  expect(calls.list).toHaveBeenLastCalledWith('session-2', '', false, 0)
  expect(wrapper.text()).toContain('新会话任务')
  expect(calls.select).not.toHaveBeenCalled()
  expect(calls.update).not.toHaveBeenCalled()
})
