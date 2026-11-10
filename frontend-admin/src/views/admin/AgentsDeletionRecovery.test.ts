import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElTable } from 'element-plus'
import { clearFeedback, confirm } from '@skillforge/shared/feedback'
import { useAuthStore } from '../../stores/auth'
import AgentsPage from './AgentsPage.vue'

const mocks = vi.hoisted(() => ({ list: vi.fn(), remove: vi.fn(), update: vi.fn() }))
vi.mock('../../stores/auth', async () => {
  const { reactive } = await import('vue')
  const state = reactive({
    identityVersion: 1,
    user: { tenant_id: '1', sub: 'actor-1', permissions: [] as string[] }
  })
  return { useAuthStore: () => state }
})
vi.mock('../../api', () => ({
  api: { agents: { list: mocks.list, remove: mocks.remove, update: mocks.update } }
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: vi.fn()
}))

const first = {
  id: 'a',
  name: '客服甲',
  agent_key: 'support-a',
  status: 'draft',
  definition_managed: false,
  published_revision_id: null
}
const second = {
  id: 'b',
  name: '客服乙',
  agent_key: 'support-b',
  status: 'draft',
  definition_managed: false,
  published_revision_id: null
}
const published = {
  ...first,
  status: 'published',
  published_revision_id: 101,
  published_revision: 1,
  has_draft_changes: false
}
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
  useAuthStore().identityVersion = 1
  useAuthStore().user!.permissions = ['foundation.agents.read', 'foundation.agents.update']
  vi.mocked(confirm).mockResolvedValue(true)
  mocks.list.mockResolvedValue({ total: 2, items: [first, second] })
  mocks.remove.mockResolvedValue({ status: 'deleted' })
})

describe('智能体停用与服务端状态回读', () => {
  beforeEach(() => {
    mocks.list.mockResolvedValue({ total: 1, items: [published] })
    mocks.update.mockResolvedValue({ ...published, status: 'disabled' })
  })

  it('停用只提交状态，采用服务端完整回读且不额外刷新列表', async () => {
    const response = { ...published, status: 'disabled', name: '服务端名称' }
    mocks.update.mockResolvedValue(response)
    await open()
    expect(button('删除')).toBeUndefined()
    await button('停用').trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledExactlyOnceWith('a', { status: 'disabled' })
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(rows()).toEqual([response])
    expect(wrapper!.text()).toContain('已停用')
    expect(document.body.textContent).toContain('智能体已停用，可在配置页重新发布后恢复使用')
    expect(button('停用')).toBeUndefined()
  })

  it('拒绝停用保留原行并可重新读取，不显示成功', async () => {
    mocks.update.mockRejectedValueOnce(new Error('无停用权限'))
    await open()
    await button('停用').trigger('click')
    await flushPromises()
    expect(rows()).toEqual([published])
    expect(document.body.textContent).toContain('无停用权限')
    expect(document.body.textContent).not.toContain('智能体已停用')
    expect(mocks.list).toHaveBeenCalledTimes(1)
  })

  it('取消停用不提交状态', async () => {
    vi.mocked(confirm).mockResolvedValueOnce(false)
    await open()
    await button('停用').trigger('click')
    await flushPromises()
    expect(mocks.update).not.toHaveBeenCalled()
    expect(rows()).toEqual([published])
  })

  it.each(['success', 'failure'])('身份改变后丢弃迟到的停用%s', async (outcome) => {
    let resolve!: (value: unknown) => void
    let reject!: (reason: Error) => void
    mocks.update.mockImplementationOnce(
      () =>
        new Promise((accept, fail) => {
          resolve = accept
          reject = fail
        })
    )
    await open()
    await button('停用').trigger('click')
    await flushPromises()
    useAuthStore().identityVersion++
    if (outcome === 'success') resolve({ ...published, status: 'disabled' })
    else reject(new Error('旧身份停用失败'))
    await flushPromises()
    expect(rows()).toEqual([published])
    expect(document.body.textContent).not.toContain('智能体已停用')
    expect(document.body.textContent).not.toContain('旧身份停用失败')
    expect(mocks.list).toHaveBeenCalledTimes(1)
  })

  it('卸载后不接收停用成功反馈', async () => {
    let resolve!: (value: unknown) => void
    mocks.update.mockImplementationOnce(
      () =>
        new Promise((accept) => {
          resolve = accept
        })
    )
    await open()
    await button('停用').trigger('click')
    await flushPromises()
    wrapper!.unmount()
    wrapper = undefined
    resolve({ ...published, status: 'disabled' })
    await flushPromises()
    expect(document.body.textContent).not.toContain('智能体已停用')
    expect(mocks.list).toHaveBeenCalledTimes(1)
  })

  it('公共部署实例只展示管理归属，不暴露普通停用和删除', async () => {
    mocks.list.mockResolvedValue({ total: 1, items: [{ ...published, definition_managed: true }] })
    await open()
    expect(button('停用')).toBeUndefined()
    expect(button('删除')).toBeUndefined()
    expect(button('配置')).toBeUndefined()
    expect(button('查看')).toBeDefined()
    expect(wrapper!.text()).toContain('由公共部署管理')
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('没有更新权限不提供停用操作', async () => {
    useAuthStore().user!.permissions = ['foundation.agents.read']
    await open()
    expect(button('停用')).toBeUndefined()
    expect(mocks.update).not.toHaveBeenCalled()
  })
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
