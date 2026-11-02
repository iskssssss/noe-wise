import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton } from 'element-plus'
import { HttpClientError } from '@skillforge/shared/utils/http'
import HandoffCreation from './HandoffCreation.vue'
const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  receipt: vi.fn(),
  confirm: vi.fn(),
  member: 'member',
  permissions: ['foundation.handoff.create', 'foundation.handoff.read']
}))
vi.mock('../stores/auth', () => ({
  useAuthStore: () => ({
    user: { tenant_id: 'tenant', sub: mocks.member, permissions: mocks.permissions },
    identityVersion: 1
  })
}))
vi.mock('../api', () => ({
  api: { handoff: { create: mocks.create, createReceipt: mocks.receipt } }
}))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('@skillforge/shared/feedback', async (original) => ({
  ...(await original<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm
}))
let wrapper: VueWrapper
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.member = 'member'
  mocks.permissions = ['foundation.handoff.create', 'foundation.handoff.read']
  mocks.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})
async function open(sessionId = 'session-1') {
  wrapper = mount(HandoffCreation, {
    props: { sessionId, disabled: false },
    attachTo: document.body,
    global: { stubs: { RouterLink: { props: ['to'], template: '<a><slot /></a>' } } }
  })
  await flushPromises()
}
async function click(name: string) {
  const item = wrapper.findAllComponents(ElButton).find((button) => button.text() === name)
  expect(item, name).toBeDefined()
  await item!.trigger('click')
  await flushPromises()
}
it('创建后使用精确工单与会话身份，用户可显式开启下一次', async () => {
  mocks.create.mockImplementation(
    async (session_id: string, _reason: string, command_id: string) => ({
      id: 'ticket-1',
      status: 'pending',
      session_id,
      command_id
    })
  )
  await open()
  await click('创建人工接管工单')
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(
    wrapper.findComponent({ name: 'RouterLink' }).exists() || wrapper.text().includes('打开工单')
  ).toBe(true)
  expect(wrapper.text()).toContain('工单 ticket-1 已创建')
  await click('创建另一工单')
  await click('创建人工接管工单')
  expect(mocks.create).toHaveBeenCalledTimes(2)
  expect(mocks.create.mock.calls[0]![2]).not.toBe(mocks.create.mock.calls[1]![2])
})
it('UNKNOWN 跨主体与会话隔离，404 不重发，用户确认后使用原 UUID 继续', async () => {
  mocks.create.mockRejectedValueOnce(Error('断连'))
  await open()
  await click('创建人工接管工单')
  const id = mocks.create.mock.calls[0]![2]
  wrapper.unmount()
  await open('session-2')
  expect(wrapper.text()).not.toContain(id)
  wrapper.unmount()
  mocks.member = 'other'
  await open()
  expect(wrapper.text()).not.toContain(id)
  wrapper.unmount()
  mocks.member = 'member'
  await open()
  expect(wrapper.text()).toContain(id)
  mocks.receipt.mockRejectedValueOnce(new HttpClientError({ status: 404, userMessage: '未找到' }))
  await click('核对原工单')
  expect(wrapper.text()).toContain(id)
  expect(mocks.create).toHaveBeenCalledTimes(1)
  mocks.create.mockImplementationOnce(
    async (session_id: string, _reason: string, command_id: string) => ({
      id: 'ticket-1',
      status: 'pending',
      session_id,
      command_id
    })
  )
  await click('继续原创建')
  expect(mocks.create.mock.calls[1]![2]).toBe(id)
  expect(mocks.confirm.mock.calls.at(-1)![0].content).toContain('此操作会创建工单')
})
it('缺少回执读权或存储不可用时不创建', async () => {
  mocks.permissions = ['foundation.handoff.create']
  await open()
  expect(wrapper.text()).toContain('读取权限')
  expect(wrapper.findComponent(ElButton).props('disabled')).toBe(true)
  wrapper.unmount()
  mocks.permissions.push('foundation.handoff.read')
  await open()
  const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw Error('blocked')
  })
  await click('创建人工接管工单')
  expect(mocks.create).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('未发送')
  spy.mockRestore()
})

it('回执写入曾失败后核对成功会恢复后续操作', async () => {
  mocks.create.mockImplementation(
    async (session_id: string, _reason: string, command_id: string) => ({
      id: 'ticket-1',
      status: 'pending',
      session_id,
      command_id
    })
  )
  await open()
  const original = Storage.prototype.setItem
  let count = 0
  const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
    this: Storage,
    key: string,
    value: string
  ) {
    if (++count === 2) throw Error('blocked')
    return original.call(this, key, value)
  })
  await click('创建人工接管工单')
  expect(wrapper.text()).toContain('无法保存记录')
  const command = mocks.create.mock.calls[0]![2]
  mocks.receipt.mockResolvedValueOnce({
    id: 'ticket-1',
    status: 'pending',
    session_id: 'session-1',
    command_id: command
  })
  await click('核对原工单')
  expect(wrapper.text()).not.toContain('无法保存记录')
  await click('创建另一工单')
  expect(
    wrapper
      .findAllComponents(ElButton)
      .find((item) => item.text() === '创建人工接管工单')!
      .props('disabled')
  ).toBe(false)
  spy.mockRestore()
})
