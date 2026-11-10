import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElInput } from 'element-plus'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { useAuthStore } from '../../stores/auth'
import AgentsPage from './AgentsPage.vue'

const mocks = vi.hoisted(() => ({ create: vi.fn(), result: vi.fn(), push: vi.fn() }))
vi.mock('../../stores/auth', async () => {
  const { reactive } = await import('vue')
  const state = reactive({
    identityVersion: 1,
    user: { tenant_id: '1', sub: 'actor-1', permissions: ['foundation.agents.read'] }
  })
  return { useAuthStore: () => state }
})
vi.mock('../../api', () => ({
  api: {
    agents: {
      list: async () => ({ items: [], total: 0 }),
      createWithReceipt: mocks.create,
      creationResult: mocks.result
    }
  }
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
let wrapper: VueWrapper
function button(label: string) {
  return wrapper.findAllComponents(ElButton).find((value) => value.text() === label)!
}
async function render() {
  wrapper = mount(AgentsPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
  })
  await flushPromises()
}
async function create() {
  await button('新建智能体').trigger('click')
  await flushPromises()
  const drawer = wrapper
    .findAllComponents(ElDrawer)
    .find((value) => value.props('title') === '新建智能体')!
  await drawer.findComponent(ElInput).get('input').setValue('客服助手')
  await button('创建并配置').trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  useAuthStore().user!.sub = 'actor-1'
  useAuthStore().identityVersion = 1
  useAuthStore().user!.permissions = ['foundation.agents.read']
  mocks.push.mockResolvedValue(undefined)
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

it('创建成功直接进入原智能体', async () => {
  mocks.create.mockImplementation(async (id: string) => ({ request_id: id, agent_id: '123' }))
  await render()
  await create()
  expect(mocks.create).toHaveBeenCalledOnce()
  expect(mocks.create.mock.calls[0]![1]).toMatchObject({ name: '客服助手' })
  expect(mocks.create.mock.calls[0]![1]).not.toHaveProperty('agent_key')
  expect(wrapper.text()).not.toContain('唯一标识')
  expect(mocks.push).toHaveBeenCalledWith('/admin/agents/123')
  expect(sessionStorage.length).toBe(0)
})

it('名称超限在创建前保留输入，修正为128个码点后提交', async () => {
  mocks.create.mockImplementation(async (id: string) => ({ request_id: id, agent_id: '123' }))
  await render()
  await button('新建智能体').trigger('click')
  await flushPromises()
  const drawer = wrapper
    .findAllComponents(ElDrawer)
    .find((item) => item.props('title') === '新建智能体')!
  const input = drawer.findComponent(ElInput).get('input')
  await input.setValue('😀'.repeat(129))
  await button('创建并配置').trigger('click')
  await flushPromises()
  expect(mocks.create).not.toHaveBeenCalled()
  expect(sessionStorage.length).toBe(0)
  expect((input.element as HTMLInputElement).value).toBe('😀'.repeat(129))
  await vi.waitFor(() => {
    expect(document.body.querySelector('.el-form-item__error')?.textContent).toContain('128')
  })
  await input.setValue('😀'.repeat(128))
  await button('创建并配置').trigger('click')
  await flushPromises()
  expect(mocks.create).toHaveBeenCalledOnce()
  expect(mocks.create.mock.calls[0]![1]).toMatchObject({ name: '😀'.repeat(128) })
  expect(mocks.push).toHaveBeenCalledWith('/admin/agents/123')
})
it('断连刷新后自动只读核对原请求并进入原智能体', async () => {
  mocks.create.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '断连' }))
  await render()
  await create()
  const requestId = mocks.create.mock.calls[0]![0]
  expect(sessionStorage.getItem(sessionStorage.key(0)!)!).not.toContain('system_prompt')
  wrapper.unmount()
  mocks.result.mockResolvedValueOnce({ request_id: requestId, agent_id: '123' })
  await render()
  expect(mocks.result).toHaveBeenCalledExactlyOnceWith(requestId)
  expect(mocks.push).toHaveBeenCalledWith('/admin/agents/123')
  expect(mocks.create).toHaveBeenCalledTimes(1)
})
it('未查到回执继续锁定，切换主体不显示原请求', async () => {
  mocks.create.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '断连' }))
  await render()
  await create()
  const requestId = mocks.create.mock.calls[0]![0]
  wrapper.unmount()
  mocks.result.mockResolvedValue(undefined)
  await render()
  expect(button('新建智能体').props('disabled')).toBe(true)
  expect(wrapper.text()).toContain(requestId)
  useAuthStore().user!.sub = 'actor-2'
  useAuthStore().identityVersion++
  await flushPromises()
  expect(wrapper.text()).not.toContain(requestId)
  useAuthStore().user!.sub = 'actor-1'
  useAuthStore().identityVersion++
  await flushPromises()
  expect(wrapper.text()).toContain(requestId)
  expect(mocks.create).toHaveBeenCalledTimes(1)
})
it('存储不可写时不派发创建', async () => {
  await render()
  vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
    throw Error('unavailable')
  })
  await create()
  expect(mocks.create).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('尚未发送创建请求')
})
it('回执确定但本地记录无法清除时保留原请求并可重新核对', async () => {
  mocks.create.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '断连' }))
  await render()
  await create()
  const requestId = mocks.create.mock.calls[0]![0]
  mocks.result.mockResolvedValue({ request_id: requestId, agent_id: '123' })
  vi.spyOn(Storage.prototype, 'removeItem').mockImplementationOnce(() => {
    throw Error('unavailable')
  })
  await button('核对创建结果').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('无法清除原创建记录')
  expect(mocks.push).not.toHaveBeenCalled()
  await button('核对创建结果').trigger('click')
  await flushPromises()
  expect(mocks.push).toHaveBeenCalledWith('/admin/agents/123')
  expect(mocks.create).toHaveBeenCalledOnce()
})
it('明确拒绝后可修改重试，请求冲突仍保留原请求', async () => {
  mocks.create
    .mockRejectedValueOnce(
      new HttpClientError({ status: 422, code: 'AGENT_CREATE_REJECTED', userMessage: '拒绝' })
    )
    .mockRejectedValueOnce(
      new HttpClientError({
        status: 409,
        code: 'AGENT_CREATE_IDENTITY_CONFLICT',
        userMessage: '冲突'
      })
    )
  await render()
  await create()
  expect(button('创建并配置').props('disabled')).toBe(false)
  await button('创建并配置').trigger('click')
  await flushPromises()
  expect(button('创建并配置').props('disabled')).toBe(true)
  expect(wrapper.text()).toContain('创建结果尚未确认')
})
it('没有读取权限时不会自动查询，原请求仍保留', async () => {
  mocks.create.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '断连' }))
  await render()
  await create()
  wrapper.unmount()
  useAuthStore().user!.permissions = []
  await render()
  expect(mocks.result).not.toHaveBeenCalled()
  expect(button('新建智能体').props('disabled')).toBe(true)
})
it('创建已成功但跳转失败时保留原入口并阻止再次创建', async () => {
  mocks.create.mockImplementation(async (id: string) => ({ request_id: id, agent_id: '123' }))
  mocks.push.mockRejectedValueOnce(new Error('导航失败'))
  await render()
  await create()
  expect(wrapper.text()).toContain('进入原智能体配置')
  expect(button('新建智能体').props('disabled')).toBe(true)
  expect(mocks.create).toHaveBeenCalledOnce()
})
