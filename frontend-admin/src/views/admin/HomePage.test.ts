import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, RouterLinkStub, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import { ElCard, ElStatistic } from 'element-plus'
import { routePermission } from '@skillforge/shared/foundation/navigation'
import HomePage from './HomePage.vue'

const mocks = vi.hoisted(() => ({
  auth: undefined as unknown as {
    identityVersion: number
    user: { sub: string; authorization_version: number; permissions: string[] }
  },
  agents: vi.fn(),
  knowledge: vi.fn(),
  channels: vi.fn(),
  sessions: vi.fn(),
  push: vi.fn()
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../../api', () => ({
  api: {
    agents: { list: mocks.agents },
    knowledge: { listBases: mocks.knowledge },
    channels: { list: mocks.channels },
    chat: { sessions: mocks.sessions }
  }
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
let wrapper: VueWrapper
beforeEach(() => {
  vi.resetAllMocks()
  mocks.auth = reactive({
    identityVersion: 1,
    user: { sub: 'member-a', authorization_version: 1, permissions: [] }
  })
  mocks.agents.mockResolvedValue({ total: 0 })
  mocks.knowledge.mockResolvedValue({ total: 3 })
  mocks.channels.mockResolvedValue({ total: 0 })
  mocks.sessions.mockResolvedValue([])
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function open() {
  wrapper = mount(HomePage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: RouterLinkStub } }
  })
  await flushPromises()
}

describe('机构首页当前领域授权', () => {
  it('局部统计失败不影响其它卡片，刷新保留该项旧数据', async () => {
    mocks.auth.user.permissions = ['foundation.agents.read', 'foundation.knowledge.read']
    mocks.agents.mockResolvedValueOnce({ total: 7 }).mockRejectedValueOnce(new Error('failed'))
    await open()
    expect(wrapper.findAllComponents(ElStatistic).map((item) => item.props('value'))).toEqual([
      7, 3
    ])
    mocks.knowledge.mockResolvedValueOnce({ total: 5 })
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.findAllComponents(ElStatistic).map((item) => item.props('value'))).toEqual([
      7, 5
    ])
    expect(
      Array.from(document.body.querySelectorAll('.el-message__content')).at(-1)?.textContent
    ).toContain('智能体：本项统计未刷新，可点击“刷新”重新读取，保留上次成功数据')
  })
  it('只有jobs权限不发送领域统计请求，也不显示未授权卡片与快捷入口', async () => {
    mocks.auth.user.permissions = ['foundation.jobs.read']
    await open()
    expect(mocks.agents).not.toHaveBeenCalled()
    expect(mocks.knowledge).not.toHaveBeenCalled()
    expect(mocks.channels).not.toHaveBeenCalled()
    expect(mocks.sessions).not.toHaveBeenCalled()
    expect(wrapper.findAllComponents(ElCard)).toHaveLength(0)
    expect(wrapper.findAllComponents(ElStatistic)).toHaveLength(0)
    expect(wrapper.findAllComponents(RouterLinkStub)).toHaveLength(0)
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.text()).toContain('请从左侧菜单进入已授权功能')
  })
  it('只请求允许的知识统计，卡片与快捷入口均采用精确路由权限', async () => {
    mocks.auth.user.permissions = ['foundation.knowledge.read']
    await open()
    expect(mocks.knowledge).toHaveBeenCalledTimes(1)
    expect(mocks.agents).not.toHaveBeenCalled()
    expect(mocks.channels).not.toHaveBeenCalled()
    expect(mocks.sessions).not.toHaveBeenCalled()
    expect(wrapper.findAllComponents(ElStatistic).map((item) => item.props('value'))).toEqual([3])
    const paths = wrapper.findAllComponents(RouterLinkStub).map((item) => String(item.props('to')))
    expect(paths).toEqual(['/admin/knowledge', '/admin/knowledge'])
    expect(
      paths.every((path) => mocks.auth.user.permissions.includes(routePermission(path)!))
    ).toBe(true)
    expect(wrapper.text()).toContain('管理知识库')
    expect(wrapper.text()).not.toContain('配置智能体')
    expect(wrapper.text()).not.toContain('接入渠道')
    expect(wrapper.text()).not.toContain('调试对话')
    expect(wrapper.find('.admin-home-link').text()).toContain('管理知识库')
  })
  it('换号清除旧统计，旧账号的迟到响应不能覆盖新账号结果', async () => {
    mocks.auth.user.permissions = ['foundation.agents.read']
    let finishOld!: (value: { total: number }) => void
    mocks.agents
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishOld = resolve
          })
      )
      .mockResolvedValueOnce({ total: 2 })
    await open()
    expect(wrapper.findAllComponents(ElStatistic)).toHaveLength(0)
    mocks.auth.user = {
      sub: 'member-b',
      authorization_version: 2,
      permissions: ['foundation.agents.read']
    }
    await flushPromises()
    expect(wrapper.findAllComponents(ElStatistic).map((item) => item.props('value'))).toEqual([2])
    finishOld({ total: 88 })
    await flushPromises()
    expect(wrapper.findAllComponents(ElStatistic).map((item) => item.props('value'))).toEqual([2])
    mocks.auth.user.permissions = ['foundation.jobs.read']
    await flushPromises()
    expect(mocks.agents).toHaveBeenCalledTimes(2)
    expect(wrapper.findAllComponents(ElStatistic)).toHaveLength(0)
    expect(wrapper.text()).toContain('请从左侧菜单进入已授权功能')
  })
})
