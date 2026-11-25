import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import TeamsPage from './TeamsPage.vue'

const mocks = vi.hoisted(() => ({ list: vi.fn() }))
vi.mock('../../api', () => ({
  getSessionId: () => 'teams-navigation-session',
  api: { teams: { list: mocks.list } }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    identityVersion: 1,
    user: {
      tenant_id: 'tenant',
      sub: 'member',
      permissions: ['foundation.teams.read', 'foundation.sessions.read']
    }
  })
}))

const wrappers: VueWrapper[] = []
beforeEach(() => {
  sessionStorage.clear()
  mocks.list.mockReset().mockResolvedValue([
    {
      id: 'team-1',
      name: '算术团队',
      description: '结果复核',
      status: 'published',
      revision: 2,
      members: [{ agent_id: 'agent-1', name: '复核助手' }],
      primary_agent_id: 'agent-1'
    },
    { id: 'team-2', name: '资料整理', description: '', status: 'draft', revision: 1, members: [] }
  ])
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
})

async function render(path = '/admin/teams') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/teams', component: TeamsPage },
      { path: '/admin/chat', component: { template: '<p>团队试用</p>' } }
    ]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(RouterView, { global: { plugins: [router] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { router, wrapper }
}

describe('团队搜索与试用导航', () => {
  it('搜索成员后试用，返回及重新打开地址都保留关键词和对应团队', async () => {
    const { router, wrapper } = await render()
    await wrapper.get('input[aria-label="搜索团队"]').setValue('复核助手')
    await flushPromises()
    const location = router.currentRoute.value.fullPath
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '试用')!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin/chat')
    expect(router.currentRoute.value.query.team_id).toBe('team-1')
    router.back()
    await flushPromises()
    expect((wrapper.get('input[aria-label="搜索团队"]').element as HTMLInputElement).value).toBe(
      '复核助手'
    )
    expect(wrapper.findAll('tbody tr').map((row) => row.text())).toEqual([
      expect.stringContaining('算术团队')
    ])
    const refreshed = await render(location)
    expect(refreshed.wrapper.findAll('tbody tr').map((row) => row.text())).toEqual([
      expect.stringContaining('算术团队')
    ])
  })

  it('连续输入保留完整关键词，不重复读目录；无结果可清空且不增加返回步骤', async () => {
    const { router, wrapper } = await render()
    const search = wrapper.get('input[aria-label="搜索团队"]')
    await search.setValue('未')
    await search.setValue('未匹配')
    await flushPromises()
    expect(router.currentRoute.value.query.q).toBe('未匹配')
    expect(wrapper.text()).toContain('没有匹配的团队，请调整关键词')
    expect(mocks.list).toHaveBeenCalledTimes(1)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '清空搜索')!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.q).toBeUndefined()
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    expect(mocks.list).toHaveBeenCalledTimes(1)
    await router.push('/admin/chat')
    router.back()
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/admin/teams')
  })
})
