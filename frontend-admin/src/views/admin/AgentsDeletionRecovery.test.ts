import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElTable } from 'element-plus'
import { clearFeedback, confirm } from '@skillforge/shared/feedback'
import AgentsPage from './AgentsPage.vue'

const mocks = vi.hoisted(() => ({ list: vi.fn(), remove: vi.fn() }))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    identityVersion: 1,
    user: { tenant_id: '1', sub: 'actor-1', permissions: [] }
  })
}))
vi.mock('../../api', () => ({ api: { agents: { list: mocks.list, remove: mocks.remove } } }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: vi.fn()
}))

const first = { id: 'a', name: '客服甲', agent_key: 'support-a', status: 'DRAFT' }
const second = { id: 'b', name: '客服乙', agent_key: 'support-b', status: 'DRAFT' }
let wrapper: VueWrapper | undefined
function button(label: string) {
  return wrapper!.findAllComponents(ElButton).find((item) => item.text() === label)!
}
function rows() {
  const props = wrapper!.getComponent(ElTable).props() as { data: unknown }
  return props.data
}
async function open() {
  wrapper = mount(AgentsPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } }
  })
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  vi.mocked(confirm).mockResolvedValue(true)
  mocks.list.mockResolvedValue({ total: 2, items: [first, second] })
  mocks.remove.mockResolvedValue({ status: 'deleted' })
})
afterEach(() => {
  wrapper?.unmount()
  clearFeedback()
  document.body.innerHTML = ''
})

describe('智能体确认删除后的目录恢复', () => {
  it('首次读取失败不显示空目录，刷新后可恢复；再次刷新失败保留旧数据并标注', async () => {
    mocks.list.mockRejectedValueOnce(new Error('智能体目录不可用'))
    await open()
    expect(wrapper!.text()).toContain('智能体列表尚未读取成功，请点击刷新重试')
    expect(wrapper!.text()).not.toContain('尚无智能体，点击“新建智能体”开始配置')
    expect(wrapper!.text()).not.toContain('显示 0 个智能体')
    await button('刷新').trigger('click')
    await flushPromises()
    expect(wrapper!.text()).toContain('显示 2 个智能体')
    mocks.list.mockRejectedValueOnce(new Error('再次读取失败'))
    await button('刷新').trigger('click')
    await flushPromises()
    expect(wrapper!.text()).toContain('智能体列表刷新失败，以下保留上次读取的结果')
    expect(rows()).toEqual([first, second])
    await button('刷新').trigger('click')
    await flushPromises()
    expect(wrapper!.text()).not.toContain('智能体列表刷新失败')
  })
  it('删除成功但刷新失败时移除已删除行，只读刷新可恢复列表', async () => {
    await open()
    mocks.list.mockRejectedValueOnce(new Error('目录暂不可用'))
    await button('删除').trigger('click')
    await flushPromises()
    expect(rows()).toEqual([second])
    expect(wrapper!.text()).toContain('显示 1 个智能体')
    expect(document.body.textContent).toContain('智能体已删除，但列表刷新失败')
    expect(document.body.textContent).not.toContain('目录暂不可用')
    mocks.list.mockResolvedValueOnce({ total: 1, items: [second] })
    await button('刷新').trigger('click')
    await flushPromises()
    expect(rows()).toEqual([second])
    expect(mocks.remove).toHaveBeenCalledExactlyOnceWith('a')
    expect(mocks.list).toHaveBeenCalledTimes(3)
  })

  it('确认删除后旧目录请求迟到不能把已删除行放回', async () => {
    await open()
    let resolveOld!: (value: unknown) => void
    mocks.list.mockImplementationOnce(() => new Promise((resolve) => (resolveOld = resolve)))
    await button('刷新').trigger('click')
    mocks.list.mockRejectedValueOnce(new Error('刷新失败'))
    await button('删除').trigger('click')
    await flushPromises()
    resolveOld({ total: 2, items: [first, second] })
    await flushPromises()
    expect(rows()).toEqual([second])
    expect(mocks.remove).toHaveBeenCalledExactlyOnceWith('a')
  })

  it('删除被拒绝时保留原行，不刷新目录或显示成功', async () => {
    await open()
    mocks.remove.mockRejectedValueOnce(new Error('无删除权限'))
    await button('删除').trigger('click')
    await flushPromises()
    expect(rows()).toEqual([first, second])
    expect(document.body.textContent).toContain('无删除权限')
    expect(document.body.textContent).not.toContain('智能体已删除')
    expect(mocks.list).toHaveBeenCalledTimes(1)
  })

  it('取消删除不发送请求，成功刷新采用服务端新列表', async () => {
    await open()
    vi.mocked(confirm).mockResolvedValueOnce(false)
    await button('删除').trigger('click')
    await flushPromises()
    expect(mocks.remove).not.toHaveBeenCalled()
    mocks.list.mockResolvedValueOnce({ total: 1, items: [second] })
    await button('删除').trigger('click')
    await flushPromises()
    expect(rows()).toEqual([second])
    expect(document.body.textContent).toContain('智能体已删除')
    expect(document.body.textContent).not.toContain('列表刷新失败')
  })

  it('删除导致原行失焦后，将焦点放到可用的列表刷新按钮', async () => {
    await open()
    mocks.list.mockRejectedValueOnce(new Error('目录暂不可用'))
    ;(button('删除').element as HTMLButtonElement).focus()
    await button('删除').trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(button('刷新').element)
  })

  it('删除后的刷新等待期间用户移动焦点时不抢走焦点', async () => {
    await open()
    let resolveRefresh!: (value: unknown) => void
    mocks.list.mockImplementationOnce(() => new Promise((resolve) => (resolveRefresh = resolve)))
    await button('删除').trigger('click')
    await flushPromises()
    const search = wrapper!.get('input[placeholder="搜索名称或标识"]').element as HTMLInputElement
    search.focus()
    resolveRefresh({ total: 1, items: [second] })
    await flushPromises()
    expect(document.activeElement).toBe(search)
  })

  it('删除请求在页面卸载后返回时不刷新或显示旧页面反馈', async () => {
    await open()
    let resolveDelete!: (value: unknown) => void
    mocks.remove.mockImplementationOnce(() => new Promise((resolve) => (resolveDelete = resolve)))
    await button('删除').trigger('click')
    await flushPromises()
    wrapper!.unmount()
    wrapper = undefined
    resolveDelete({ status: 'deleted' })
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).not.toContain('智能体已删除')
  })

  it('删除后刷新在页面卸载后失败时不显示旧页面反馈', async () => {
    await open()
    let rejectRefresh!: (reason: Error) => void
    mocks.list.mockImplementationOnce(
      () => new Promise((_resolve, reject) => (rejectRefresh = reject))
    )
    await button('删除').trigger('click')
    await flushPromises()
    wrapper!.unmount()
    wrapper = undefined
    rejectRefresh(new Error('目录暂不可用'))
    await flushPromises()
    expect(mocks.remove).toHaveBeenCalledExactlyOnceWith('a')
    expect(document.body.textContent).not.toContain('智能体已删除')
    expect(document.body.textContent).not.toContain('目录暂不可用')
  })
})
