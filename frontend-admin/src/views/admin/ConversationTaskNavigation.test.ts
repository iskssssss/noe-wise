import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { ElButton, ElCheckbox, ElInput, ElPagination, ElSelect } from 'element-plus'
import { reactive } from 'vue'
import ConversationTasksPage from './ConversationTasksPage.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  current: vi.fn(),
  sessions: vi.fn(),
  user: { tenant_id: 'tenant', sub: 'member', permissions: ['foundation.sessions.read'] }
}))
vi.mock('../../api', () => ({
  getSessionId: () => 'task-navigation-session',
  api: {
    chat: { sessions: mocks.sessions },
    conversationTasks: { current: mocks.current, list: mocks.list }
  }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ identityVersion: 1, user: mocks.user })
}))
const wrappers: VueWrapper[] = []
beforeEach(() => {
  sessionStorage.clear()
  mocks.current.mockReset().mockResolvedValue(null)
  mocks.list.mockReset().mockResolvedValue([])
  mocks.sessions.mockReset().mockResolvedValue([
    { session_id: 'one', agent_id: 1, title: '会话一' },
    { session_id: 'two', agent_id: 2, title: '会话二' }
  ])
  mocks.user = reactive({
    tenant_id: 'tenant',
    sub: 'member',
    permissions: ['foundation.sessions.read']
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

async function render(path = '/admin/conversation-tasks') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/conversation-tasks',
        component: ConversationTasksPage,
        meta: { preserveOnQueryChange: true }
      },
      { path: '/admin/handoff', component: { template: '<p>人工接管</p>' } }
    ]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(
    {
      components: { RouterView },
      template:
        '<RouterView v-slot="{ Component, route }"><component :is="Component" :key="route.meta.preserveOnQueryChange ? route.path : route.fullPath" /></RouterView>'
    },
    { global: { plugins: [router] }, attachTo: document.body }
  )
  wrappers.push(wrapper)
  await flushPromises()
  return { router, wrapper }
}
function button(wrapper: VueWrapper, label: string) {
  return wrapper.findAllComponents(ElButton).find((item) => item.text() === label)!
}
async function choose(wrapper: VueWrapper, id: string) {
  const select = wrapper.findComponent(ElSelect)
  select.vm.$emit('update:modelValue', id)
  select.vm.$emit('change', id)
  await flushPromises()
}

it('成功查询写入地址，菜单返回和重新打开恢复会话、条件及页码', async () => {
  mocks.list.mockResolvedValue(
    Array.from({ length: 30 }, (_, index) => ({
      taskId: `task-${index}`,
      title: '已记录任务',
      status: 'OPEN'
    }))
  )
  const { router, wrapper } = await render()
  await choose(wrapper, 'one')
  await wrapper.findComponent(ElInput).setValue('资料')
  wrapper.findComponent(ElCheckbox).vm.$emit('update:modelValue', true)
  await button(wrapper, '查询').trigger('click')
  await flushPromises()
  wrapper.findComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  const location = router.currentRoute.value.fullPath
  expect(router.currentRoute.value.query).toEqual({
    sessionId: 'one',
    task_query: '资料',
    task_closed: 'true',
    task_page: '2'
  })
  expect(mocks.sessions).toHaveBeenCalledTimes(1)
  await router.push('/admin/handoff')
  await router.push('/admin/conversation-tasks')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe(location)
  expect(mocks.list).toHaveBeenLastCalledWith('one', '资料', true, 30)
  expect(wrapper.findComponent(ElPagination).props('currentPage')).toBe(2)
  const reopened = await render(location)
  expect(reopened.wrapper.findComponent(ElInput).props('modelValue')).toBe('资料')
  expect(mocks.list).toHaveBeenLastCalledWith('one', '资料', true, 30)
})

it('失败查询不覆盖成功位置，空态清空只移除关键词并保留会话和历史范围', async () => {
  const { router, wrapper } = await render(
    '/admin/conversation-tasks?sessionId=one&task_query=原词&task_closed=true&task_page=1'
  )
  const original = router.currentRoute.value.fullPath
  await wrapper.findComponent(ElInput).setValue('失败草稿')
  mocks.list.mockRejectedValueOnce(new Error('读取失败'))
  await button(wrapper, '查询').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe(original)
  expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('失败草稿')
  await router.push('/admin/handoff')
  router.back()
  await flushPromises()
  expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('原词')
  await button(wrapper, '清空任务关键词').trigger('click')
  await flushPromises()
  expect(mocks.list).toHaveBeenLastCalledWith('one', '', true, 0)
  expect(router.currentRoute.value.query.task_query).toBe('')
  expect(router.currentRoute.value.query.sessionId).toBe('one')
  expect(wrapper.text()).toContain('此会话尚无任务记录，可选择其他会话查看')
})

it('显式链接优先于缓存，换主体不恢复另一机构位置，历史入口真实查询', async () => {
  const initial = await render(
    '/admin/conversation-tasks?sessionId=one&task_query=保留&task_page=2'
  )
  initial.wrapper.unmount()
  const explicit = await render('/admin/conversation-tasks?sessionId=two')
  expect(mocks.list).toHaveBeenLastCalledWith('two', '', false, 0)
  await button(explicit.wrapper, '查看已结束任务').trigger('click')
  await flushPromises()
  expect(mocks.list).toHaveBeenLastCalledWith('two', '', true, 0)
  explicit.wrapper.unmount()
  mocks.user = reactive({
    tenant_id: 'other',
    sub: 'member',
    permissions: ['foundation.sessions.read']
  })
  mocks.list.mockClear()
  const other = await render()
  expect(mocks.list).not.toHaveBeenCalled()
  expect(other.wrapper.findComponent(ElSelect).props('modelValue')).toBe('')
})

it('存储不可写时仍更新地址并保留查询能力', async () => {
  const { router, wrapper } = await render()
  const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('quota')
  })
  try {
    await choose(wrapper, 'one')
    expect(router.currentRoute.value.query.sessionId).toBe('one')
    expect(document.body.textContent).toContain('无法保留菜单返回位置')
    expect(button(wrapper, '查询').props('disabled')).toBe(false)
  } finally {
    write.mockRestore()
  }
})

it('会话目录读取期间显示加载提示，目录返回后才读取任务并显示空态', async () => {
  let resolveSessions!: (value: unknown[]) => void
  mocks.sessions.mockReturnValueOnce(
    new Promise((resolve) => {
      resolveSessions = resolve
    })
  )
  const { wrapper } = await render(
    '/admin/conversation-tasks?sessionId=one&task_query=资料&task_closed=true'
  )
  expect(wrapper.text()).toContain('正在读取任务')
  expect(wrapper.text()).not.toContain('当前条件没有匹配任务')
  expect(mocks.list).not.toHaveBeenCalled()
  resolveSessions([{ session_id: 'one', agent_id: 1, title: '会话一' }])
  await flushPromises()
  expect(mocks.list).toHaveBeenLastCalledWith('one', '资料', true, 0)
  expect(wrapper.text()).toContain('当前条件没有匹配任务')
})

it('同页地址切换和后退重新读取对应条件，自身更新地址不重复查询', async () => {
  const { router, wrapper } = await render('/admin/conversation-tasks?sessionId=one')
  const first = router.currentRoute.value.fullPath
  mocks.list.mockClear()
  await router.push('/admin/conversation-tasks?sessionId=two&task_query=资料&task_closed=true')
  await flushPromises()
  expect(mocks.list).toHaveBeenCalledTimes(1)
  expect(mocks.list).toHaveBeenLastCalledWith('two', '资料', true, 0)
  expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('资料')
  router.back()
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe(first)
  expect(mocks.list).toHaveBeenCalledTimes(2)
  expect(mocks.list).toHaveBeenLastCalledWith('one', '', false, 0)
})
