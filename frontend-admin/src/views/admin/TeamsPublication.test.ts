import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElInput, ElOption, ElSelect } from 'element-plus'
import { reactive } from 'vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
import TeamsPage from './TeamsPage.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  agents: vi.fn(),
  update: vi.fn(),
  publish: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
  create: vi.fn(),
  saveWithReceipt: vi.fn(),
  saveReceipt: vi.fn(),
  saveAndPublish: vi.fn(),
  saveAndPublishResult: vi.fn(),
  push: vi.fn(),
  session: 'browser-session',
  tenant: 'tenant',
  member: 'user',
  permissions: [
    'foundation.teams.read',
    'foundation.teams.create',
    'foundation.teams.update',
    'foundation.teams.publish',
    'foundation.sessions.read'
  ]
}))
vi.mock('../../api', () => ({
  getSessionId: () => mocks.session,
  api: {
    teams: {
      list: mocks.list,
      create: mocks.create,
      update: mocks.update,
      saveWithReceipt: mocks.saveWithReceipt,
      saveReceipt: mocks.saveReceipt,
      publishWithReceipt: async (requestId: string, id: string, revision: number) => ({
        request_id: requestId,
        status: 'SUCCEEDED',
        team: await mocks.publish(id, revision)
      }),
      saveAndPublish: mocks.saveAndPublish,
      saveAndPublishResult: mocks.saveAndPublishResult
    },
    agents: { list: mocks.agents }
  }
}))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
  useRouter: () => ({ push: mocks.push })
}))
const authState = reactive({
  identityVersion: 1,
  user: { tenant_id: 'tenant', sub: 'user', permissions: [] as string[] }
})
vi.mock('../../stores/auth', () => ({ useAuthStore: () => authState }))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@skillforge/shared/feedback')>()
  return {
    ...actual,
    confirm: mocks.confirm,
    message: { ...actual.message, success: mocks.success }
  }
})
const team = {
  id: 'team-1',
  name: '原团队',
  description: '协作职责',
  status: 'published',
  revision: 4,
  members: [
    { agent_id: 'agent-1', name: '成员甲' },
    { agent_id: 'agent-2', name: '成员乙' }
  ],
  primary_agent_id: 'agent-1'
}
let wrapper: VueWrapper
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.tenant = 'tenant'
  mocks.member = 'user'
  mocks.session = 'browser-session'
  mocks.permissions = [
    'foundation.teams.read',
    'foundation.teams.create',
    'foundation.teams.update',
    'foundation.teams.publish',
    'foundation.sessions.read'
  ]
  mocks.list.mockResolvedValue([{ ...team }])
  mocks.agents.mockResolvedValue({
    items: [
      { id: 'agent-1', name: '成员甲', status: 'published' },
      { id: 'agent-2', name: '成员乙', status: 'published' }
    ],
    total: 2
  })
  mocks.confirm.mockResolvedValue(true)
  mocks.saveWithReceipt.mockImplementation(
    async (requestId: string, id: string | null, payload: { revision: number }) => {
      const result = id ? await mocks.update(id, payload) : await mocks.create(payload)
      return {
        request_id: requestId,
        team_id: id ?? result?.id ?? 'new-team',
        revision: result?.revision ?? payload.revision + 1
      }
    }
  )
  mocks.saveReceipt.mockResolvedValue(undefined)
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function open() {
  authState.user = { tenant_id: mocks.tenant, sub: mocks.member, permissions: mocks.permissions }
  wrapper = mount(TeamsPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
  })
  await flushPromises()
}
function button(text: string) {
  const found = wrapper.findAllComponents(ElButton).find((value) => value.text() === text)
  expect(found, text).toBeDefined()
  return found!
}
async function click(text: string) {
  await button(text).trigger('click')
  await flushPromises()
}

describe('团队成员目录前台刷新', () => {
  it('返回页面只更新目录，保留草稿与成员选择', async () => {
    mocks.permissions.push('foundation.agents.read')
    await open()
    await click('新增团队')
    const drawer = wrapper.findComponent(ElDrawer)
    await drawer.findAllComponents(ElInput)[0]!.setValue('保留名称')
    const [members, primary] = drawer.findAllComponents(ElSelect)
    members!.vm.$emit('update:modelValue', ['agent-1'])
    members!.vm.$emit('change', ['agent-1'])
    await flushPromises()
    mocks.agents.mockResolvedValueOnce({
      items: [{ id: 'agent-3', name: '新成员', status: 'published' }]
    })
    window.dispatchEvent(new Event('focus'))
    await flushPromises()
    expect(mocks.agents).toHaveBeenCalledTimes(2)
    expect(drawer.findAllComponents(ElInput)[0]!.props('modelValue')).toBe('保留名称')
    expect(members!.props('modelValue')).toEqual(['agent-1'])
    expect(primary!.props('modelValue')).toBe('agent-1')
    expect(members!.findAllComponents(ElOption).map((option) => option.props('value'))).toContain(
      'agent-3'
    )
    window.dispatchEvent(new Event('focus'))
    await flushPromises()
    expect(mocks.agents).toHaveBeenCalledTimes(2)
  })

  it('身份变化后丢弃旧读取且不以新会话自动读取旧抽屉', async () => {
    mocks.permissions.push('foundation.agents.read')
    await open()
    await click('新增团队')
    let finish!: (value: { items: Array<{ id: string; name: string; status: string }> }) => void
    mocks.agents.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    window.dispatchEvent(new Event('focus'))
    await flushPromises()
    mocks.session = 'new-browser-session'
    finish({ items: [{ id: 'old', name: '旧结果', status: 'published' }] })
    await flushPromises()
    window.dispatchEvent(new Event('focus'))
    await flushPromises()
    expect(mocks.agents).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).not.toContain('旧结果')
  })

  it('切换会话后丢弃迟到的读取错误', async () => {
    mocks.permissions.push('foundation.agents.read')
    await open()
    await click('新增团队')
    let fail!: (reason: Error) => void
    mocks.agents.mockImplementationOnce(
      () =>
        new Promise((_resolve, reject) => {
          fail = reject
        })
    )
    window.dispatchEvent(new Event('focus'))
    await flushPromises()
    mocks.session = 'new-browser-session'
    fail(new Error('过期会话错误'))
    await flushPromises()
    expect(document.body.textContent).not.toContain('过期会话错误')
  })

  it('读取权限撤销时丢弃已发出的目录结果', async () => {
    mocks.permissions.push('foundation.agents.read')
    await open()
    await click('新增团队')
    let finish!: (value: { items: Array<{ id: string; name: string; status: string }> }) => void
    mocks.agents.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    window.dispatchEvent(new Event('focus'))
    await flushPromises()
    authState.user.permissions = authState.user.permissions.filter(
      (p) => p !== 'foundation.agents.read'
    )
    finish({ items: [{ id: 'revoked', name: '已撤权结果', status: 'published' }] })
    await flushPromises()
    expect(document.body.textContent).not.toContain('已撤权结果')
  })
})

describe('团队保存配置后显式发布新修订', () => {
  it.each(['保存', '发布新修订', '保存并发布'])(
    '%s成功但列表刷新失败时保留结果并阻止重复写入',
    async (operation) => {
      mocks.update.mockResolvedValue({ ...team, revision: 5 })
      mocks.publish.mockResolvedValue({ ...team, revision: 5 })
      mocks.saveAndPublish.mockImplementation(async (requestId: string) => ({
        request_id: requestId,
        status: 'SUCCEEDED',
        team: { ...team, revision: 5 }
      }))
      await open()
      mocks.list.mockRejectedValueOnce(new Error('目录暂不可读'))
      if (operation !== '发布新修订') await click('编辑')
      await click(operation)
      expect(wrapper.text()).toContain('团队操作已完成，但列表尚未更新')
      expect(wrapper.text()).toContain('原团队')
      expect(button('新增团队').props('disabled')).toBe(true)
      expect(button('编辑').props('disabled')).toBe(true)
      expect(button('发布新修订').props('disabled')).toBe(true)
      await click('新增团队')
      await click('发布新修订')
      expect(mocks.update).toHaveBeenCalledTimes(operation === '保存' ? 1 : 0)
      expect(mocks.publish).toHaveBeenCalledTimes(operation === '发布新修订' ? 1 : 0)
      expect(mocks.saveAndPublish).toHaveBeenCalledTimes(operation === '保存并发布' ? 1 : 0)
      await click('刷新')
      expect(wrapper.text()).not.toContain('团队操作已完成，但列表尚未更新')
      expect(button('新增团队').props('disabled')).toBe(false)
    }
  )

  it('首次读取失败不误报空团队，刷新失败保留并标明上次结果', async () => {
    mocks.list.mockRejectedValueOnce(new Error('团队目录不可用'))
    await open()
    expect(wrapper.text()).toContain('团队列表尚未读取成功，请点击刷新重试')
    expect(wrapper.text()).not.toContain('尚无团队，选择已发布智能体组建协作团队')
    expect(wrapper.text()).not.toContain('0 个团队')
    await click('刷新')
    expect(wrapper.text()).toContain('原团队')
    mocks.list.mockRejectedValueOnce(new Error('再次读取失败'))
    await click('刷新')
    expect(wrapper.text()).toContain('团队列表刷新失败，以下保留上次读取的结果')
    expect(wrapper.text()).toContain('原团队')
    await click('刷新')
    expect(wrapper.text()).not.toContain('团队列表刷新失败')
  })
  it('按成员名称查找所属团队，清空后恢复列表', async () => {
    mocks.list.mockResolvedValue([
      team,
      {
        ...team,
        id: 'team-2',
        name: '另一团队',
        members: [{ agent_id: 'other', name: '数据专家' }]
      }
    ])
    await open()
    const search = wrapper.get('input[aria-label="搜索团队"]')
    await search.setValue(' 成员乙 ')
    expect(wrapper.findAll('tbody tr').map((row) => row.text())).toEqual([
      expect.stringContaining('原团队')
    ])
    await search.setValue('')
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
  })
  it('列表可直接试用所选已发布团队，草稿不提供入口', async () => {
    mocks.list.mockResolvedValue([
      team,
      { ...team, id: 'team-2', name: '另一个团队' },
      { ...team, id: 'team-3', name: '团队草稿', status: 'draft' }
    ])
    await open()
    const rows = wrapper.findAll('tbody tr')
    const published = rows.find((row) => row.text().includes('另一个团队'))!
    await published
      .findAll('button')
      .find((value) => value.text() === '试用')!
      .trigger('click')
    expect(mocks.push).toHaveBeenCalledExactlyOnceWith({
      path: '/admin/chat',
      query: { team_id: 'team-2' }
    })
    expect(rows.find((row) => row.text().includes('团队草稿'))!.text()).not.toContain('试用')
  })
  it('没有会话读取权限时不显示列表试用入口', async () => {
    mocks.permissions = mocks.permissions.filter((item) => item !== 'foundation.sessions.read')
    await open()
    expect(wrapper.findAllComponents(ElButton).some((value) => value.text() === '试用')).toBe(false)
  })
  it('已发布团队编辑后status仍published，当前保存配置可以显式发布一次', async () => {
    await open()
    await click('编辑')
    const drawer = wrapper.findComponent(ElDrawer)
    await drawer.findAllComponents(ElInput)[0]!.setValue('更新后的团队')
    drawer.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', 'agent-2')
    await flushPromises()
    const updated = { ...team, name: '更新后的团队', primary_agent_id: 'agent-2', revision: 5 }
    mocks.update.mockResolvedValue(updated)
    mocks.list.mockResolvedValue([updated])
    const save = drawer.findAllComponents(ElButton).find((value) => value.text() === '保存')!
    await save.trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledExactlyOnceWith(
      'team-1',
      expect.objectContaining({
        revision: 4,
        name: '更新后的团队',
        primary_agent_id: 'agent-2',
        members: [{ agent_id: 'agent-1' }, { agent_id: 'agent-2' }]
      })
    )
    expect(button('发布新修订').props('disabled')).toBe(false)
    expect(mocks.publish).not.toHaveBeenCalled()
    const published = { ...updated, revision: 6 }
    mocks.publish.mockResolvedValue(published)
    mocks.list.mockResolvedValue([published])
    await click('发布新修订')
    expect(mocks.confirm).toHaveBeenCalledWith(
      expect.objectContaining({ title: '发布团队新修订', confirmText: '发布新修订' })
    )
    expect(mocks.confirm.mock.calls[0]![0].content).toContain('已经受理的执行保持原冻结配置')
    expect(mocks.publish).toHaveBeenCalledExactlyOnceWith('team-1', 5)
    expect(mocks.success).toHaveBeenCalledWith('团队已发布')
  })
  it('取消已发布团队的新修订确认不提交发布，也不读取成员目录', async () => {
    mocks.confirm.mockResolvedValue(false)
    await open()
    await click('发布新修订')
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(mocks.agents).not.toHaveBeenCalled()
    expect(mocks.list).toHaveBeenCalledTimes(1)
  })
  it('发布结果未知时锁定并只读核对，刷新后不重复发布', async () => {
    mocks.publish.mockRejectedValueOnce(new Error('网络中断'))
    await open()
    await click('发布新修订')
    expect(mocks.publish).toHaveBeenCalledExactlyOnceWith('team-1', 4)
    expect(button('发布新修订').props('disabled')).toBe(true)
    const raw = sessionStorage.getItem(
      'skillforge.team-publication.' + JSON.stringify(['tenant', 'user'])
    )!
    const operation = JSON.parse(raw)
    wrapper.unmount()
    await open()
    expect(wrapper.text()).toContain('团队发布结果尚未确认')
    mocks.saveAndPublishResult.mockResolvedValueOnce(undefined)
    await click('核对原操作')
    expect(mocks.saveAndPublishResult).toHaveBeenCalledWith(operation.requestId)
    expect(mocks.publish).toHaveBeenCalledOnce()
    expect(button('发布新修订').props('disabled')).toBe(true)
    mocks.saveAndPublishResult.mockResolvedValueOnce({
      request_id: operation.requestId,
      status: 'SUCCEEDED',
      team: { ...team, revision: 6 }
    })
    await click('核对原操作')
    expect(button('发布新修订').props('disabled')).toBe(true)
    expect(mocks.publish).toHaveBeenCalledOnce()
    mocks.saveAndPublishResult.mockResolvedValueOnce({
      request_id: operation.requestId,
      status: 'SUCCEEDED',
      team: { ...team, revision: 5 }
    })
    await click('核对原操作')
    expect(mocks.publish).toHaveBeenCalledOnce()
    expect(
      sessionStorage.getItem('skillforge.team-publication.' + JSON.stringify(['tenant', 'user']))
    ).toBeNull()
    expect(mocks.success).toHaveBeenCalledWith('团队已发布')
  })

  it('首次草稿发布仍沿原权限按钮和发布接口', async () => {
    mocks.list.mockResolvedValue([{ ...team, status: 'draft' }])
    mocks.publish.mockResolvedValue({ ...team, revision: 5 })
    await open()
    await click('发布')
    expect(mocks.confirm).toHaveBeenCalledWith(
      expect.objectContaining({ title: '发布团队', confirmText: '发布团队' })
    )
    expect(mocks.publish).toHaveBeenCalledExactlyOnceWith('team-1', 4)
  })
})

it('切换主体立即清除团队和成员草稿，丢弃旧目录迟到结果', async () => {
  let finish!: (value: unknown) => void
  mocks.agents.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  await open()
  await click('编辑')
  const drawer = wrapper.findComponent(ElDrawer)
  await drawer.findAllComponents(ElInput)[0]!.setValue('旧账号未保存草稿')
  mocks.list.mockResolvedValueOnce([{ ...team, id: 'team-new', name: '新机构团队', members: [] }])
  authState.user = { tenant_id: 'other-tenant', sub: 'other-user', permissions: mocks.permissions }
  authState.identityVersion++
  finish({ items: [{ id: 'old-agent', name: '旧机构成员', status: 'published' }] })
  await flushPromises()
  expect(drawer.props('modelValue')).toBe(false)
  expect(wrapper.text()).not.toContain('原团队')
  expect(document.body.textContent).not.toContain('旧账号未保存草稿')
  expect(document.body.textContent).not.toContain('旧机构成员')
  expect(wrapper.text()).toContain('新机构团队')
})

it('保存请求迟到时不关闭新主体抽屉或展示旧结果', async () => {
  let finish!: (value: unknown) => void
  mocks.update.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  await open()
  await click('编辑')
  await button('保存').trigger('click')
  await flushPromises()
  authState.user = { tenant_id: 'other-tenant', sub: 'other-user', permissions: mocks.permissions }
  authState.identityVersion++
  finish({ ...team, name: '旧账号结果' })
  await flushPromises()
  expect(document.body.textContent).not.toContain('旧账号结果')
  expect(mocks.success).not.toHaveBeenCalledWith('团队已保存')
})

it('同一主体授权版本变化后忽略迟到发布回执，保留原请求以供重新核对', async () => {
  let finish!: (value: unknown) => void
  mocks.saveAndPublish.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    })
  )
  await open()
  await click('编辑')
  await button('保存并发布').trigger('click')
  await flushPromises()
  const requestId = mocks.saveAndPublish.mock.calls[0]![0]
  authState.identityVersion++
  finish({ request_id: requestId, status: 'SUCCEEDED', team: { ...team, revision: 5 } })
  await flushPromises()
  expect(document.body.textContent).toContain(requestId)
  expect(document.body.textContent).not.toContain('团队“原团队”已发布')
  expect(
    sessionStorage.getItem(`skillforge.team-publication.${JSON.stringify(['tenant', 'user'])}`)
  ).toContain(requestId)
})

describe('团队成员候选独立恢复', () => {
  it('新团队只有一个已发布智能体时预选成员与主智能体，不自动写入', async () => {
    mocks.agents.mockResolvedValueOnce({
      items: [
        { id: 'agent-1', name: '唯一成员', status: 'published' },
        { id: 'agent-2', name: '未发布成员', status: 'draft' }
      ]
    })
    await open()
    await click('新增团队')
    const drawer = wrapper.findComponent(ElDrawer)
    const [members, primary] = drawer.findAllComponents(ElSelect)
    expect(members!.props('modelValue')).toEqual(['agent-1'])
    expect(primary!.props('modelValue')).toBe('agent-1')
    expect(document.body.textContent).toContain('已预选唯一可用成员')
    expect(window.dispatchEvent(new Event('beforeunload', { cancelable: true }))).toBe(true)
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.saveAndPublish).not.toHaveBeenCalled()
  })
  it('迟到的唯一成员不覆盖名称草稿，撤销名称后不产生虚假的未保存提示', async () => {
    let finish!: (value: { items: Array<{ id: string; name: string; status: string }> }) => void
    mocks.agents.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    await open()
    await click('新增团队')
    const drawer = wrapper.findComponent(ElDrawer)
    const name = drawer.findAllComponents(ElInput)[0]!
    await name.setValue('用户正在编辑')
    finish({ items: [{ id: 'agent-1', name: '唯一成员', status: 'published' }] })
    await flushPromises()
    expect(name.props('modelValue')).toBe('用户正在编辑')
    expect(drawer.findAllComponents(ElSelect)[0]!.props('modelValue')).toEqual(['agent-1'])
    expect(window.dispatchEvent(new Event('beforeunload', { cancelable: true }))).toBe(false)
    await name.setValue('')
    expect(window.dispatchEvent(new Event('beforeunload', { cancelable: true }))).toBe(true)
  })
  it('刷新出现其他成员时保留选择并移除唯一成员提示', async () => {
    mocks.agents.mockResolvedValueOnce({
      items: [{ id: 'agent-1', name: '唯一成员', status: 'published' }]
    })
    await open()
    await click('新增团队')
    expect(document.body.textContent).toContain('已预选唯一可用成员')
    mocks.agents.mockResolvedValueOnce({
      items: [
        { id: 'agent-1', name: '原成员', status: 'published' },
        { id: 'agent-2', name: '新成员', status: 'published' }
      ]
    })
    await click('刷新成员选项')
    expect(
      wrapper.findComponent(ElDrawer).findAllComponents(ElSelect)[0]!.props('modelValue')
    ).toEqual(['agent-1'])
    expect(document.body.textContent).not.toContain('已预选唯一可用成员')
  })
  it('多个候选或读取失败时不预选团队成员', async () => {
    await open()
    await click('新增团队')
    expect(
      wrapper.findComponent(ElDrawer).findAllComponents(ElSelect)[0]!.props('modelValue')
    ).toEqual([])
    wrapper.unmount()
    mocks.agents.mockRejectedValueOnce(new Error('目录不可读'))
    await open()
    await click('新增团队')
    expect(
      wrapper.findComponent(ElDrawer).findAllComponents(ElSelect)[0]!.props('modelValue')
    ).toEqual([])
    expect(document.body.textContent).toContain('成员选项读取失败')
  })
  it('首次读取失败后重试成功且只有一位成员时完成预选', async () => {
    mocks.agents.mockRejectedValueOnce(new Error('目录不可读'))
    await open()
    await click('新增团队')
    expect(
      wrapper.findComponent(ElDrawer).findAllComponents(ElSelect)[0]!.props('modelValue')
    ).toEqual([])
    mocks.agents.mockResolvedValueOnce({
      items: [{ id: 'agent-1', name: '唯一成员', status: 'published' }]
    })
    await click('重新读取成员选项')
    const [members, primary] = wrapper.findComponent(ElDrawer).findAllComponents(ElSelect)
    expect(members!.props('modelValue')).toEqual(['agent-1'])
    expect(primary!.props('modelValue')).toBe('agent-1')
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('候选迟到时保留用户已清空的选择和未保存名称', async () => {
    let finish!: (value: { items: Array<{ id: string; name: string; status: string }> }) => void
    mocks.agents.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    await open()
    await click('新增团队')
    const drawer = wrapper.findComponent(ElDrawer)
    await drawer.findAllComponents(ElInput)[0]!.setValue('保留的名称')
    const members = drawer.findAllComponents(ElSelect)[0]!
    members.vm.$emit('change', [])
    finish({ items: [{ id: 'agent-1', name: '唯一成员', status: 'published' }] })
    await flushPromises()
    expect(members.props('modelValue')).toEqual([])
    expect(drawer.findAllComponents(ElInput)[0]!.props('modelValue')).toBe('保留的名称')
    expect(window.dispatchEvent(new Event('beforeunload', { cancelable: true }))).toBe(false)
  })

  it('按职责或标识区分同名成员，并以原身份选择主智能体', async () => {
    mocks.agents.mockResolvedValue({
      items: [
        { id: 'agent-1', name: '客服', role_description: '查询订单', status: 'published' },
        { id: 'agent-2', name: '客服', role_description: '查询患者', status: 'published' }
      ]
    })
    await open()
    await click('新增团队')
    const drawer = wrapper.findComponent(ElDrawer)
    const [members, primary] = drawer.findAllComponents(ElSelect)
    ;(members!.props('filterMethod') as (query: string) => void)('患者')
    await flushPromises()
    expect(members!.findAllComponents(ElOption).map((option) => option.props('value'))).toEqual([
      'agent-2'
    ])
    members!.vm.$emit('update:modelValue', ['agent-2'])
    await flushPromises()
    expect(primary!.props('modelValue')).toBe('agent-2')
    expect(primary!.findAllComponents(ElOption)[0]!.props('label')).toBe('客服 · agent-2')
    ;(members!.props('filterMethod') as (query: string) => void)('agent-1')
    await flushPromises()
    expect(members!.findAllComponents(ElOption).map((option) => option.props('value'))).toEqual([
      'agent-1'
    ])
    expect(members!.props('modelValue')).toEqual(['agent-2'])
  })
  it('目录仍在读取时立即打开原团队，保留原成员名称和主智能体', async () => {
    let finish!: (value: { items: Array<{ id: string; name: string; status: string }> }) => void
    mocks.agents.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    await open()
    await click('编辑')
    const drawer = wrapper.findComponent(ElDrawer)
    expect(drawer.props('modelValue')).toBe(true)
    expect(drawer.findAllComponents(ElInput)[0]!.props('modelValue')).toBe('原团队')
    const selectors = drawer.findAllComponents(ElSelect)
    expect(selectors[0]!.props('modelValue')).toEqual(['agent-1', 'agent-2'])
    expect(selectors[0]!.props('loading')).toBe(true)
    expect(
      selectors[0]!.findAllComponents(ElOption).map((option) => option.props('label'))
    ).toEqual(['成员甲', '成员乙'])
    expect(selectors[1]!.props('modelValue')).toBe('agent-1')
    finish({ items: [] })
    await flushPromises()
  })
  it('已有候选时刷新成员，保留团队草稿、成员和主智能体', async () => {
    mocks.permissions.push('foundation.agents.read')
    await open()
    await click('编辑')
    const drawer = wrapper.findComponent(ElDrawer)
    const inputs = drawer.findAllComponents(ElInput)
    await inputs[0]!.setValue('保留的团队草稿')
    const selectors = drawer.findAllComponents(ElSelect)
    expect(document.body.querySelector('a[href="/admin/agents"]')?.getAttribute('target')).toBe(
      '_blank'
    )
    mocks.agents.mockResolvedValueOnce({
      items: [
        { id: 'agent-1', name: '成员甲', status: 'published' },
        { id: 'agent-2', name: '成员乙', status: 'published' },
        { id: 'agent-3', name: '新成员', status: 'published' }
      ]
    })
    await click('刷新成员选项')
    expect(inputs[0]!.props('modelValue')).toBe('保留的团队草稿')
    expect(selectors[0]!.props('modelValue')).toEqual(['agent-1', 'agent-2'])
    expect(selectors[1]!.props('modelValue')).toBe('agent-1')
    expect(
      selectors[0]!.findAllComponents(ElOption).map((option) => option.props('value'))
    ).toContain('agent-3')
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.update).not.toHaveBeenCalled()
  })
  it('已有候选刷新失败时保留成员与草稿，重读可恢复', async () => {
    await open()
    await click('编辑')
    const drawer = wrapper.findComponent(ElDrawer)
    await drawer.findAllComponents(ElInput)[0]!.setValue('团队草稿')
    mocks.agents.mockRejectedValueOnce(new Error('成员目录暂不可用'))
    await click('刷新成员选项')
    expect(drawer.findAllComponents(ElInput)[0]!.props('modelValue')).toBe('团队草稿')
    expect(drawer.findAllComponents(ElSelect)[0]!.props('modelValue')).toEqual([
      'agent-1',
      'agent-2'
    ])
    expect(drawer.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe('agent-1')
    expect(document.body.textContent).toContain('成员目录暂不可用')
    await click('重新读取成员选项')
    expect(button('刷新成员选项').exists()).toBe(true)
    expect(mocks.update).not.toHaveBeenCalled()
  })
  it('目录失败仍可编辑原团队，重读只更新候选，不覆盖名称成员和主智能体草稿', async () => {
    mocks.agents.mockRejectedValueOnce(new Error('候选读取失败'))
    await open()
    await click('编辑')
    const drawer = wrapper.findComponent(ElDrawer)
    const inputs = drawer.findAllComponents(ElInput)
    await inputs[0]!.setValue('本地团队草稿')
    await inputs[1]!.setValue('本地职责')
    const selectors = drawer.findAllComponents(ElSelect)
    selectors[0]!.vm.$emit('update:modelValue', ['agent-2'])
    await flushPromises()
    expect(selectors[1]!.props('modelValue')).toBe('agent-2')
    expect(drawer.props('modelValue')).toBe(true)
    expect(document.querySelectorAll('.el-alert')).toHaveLength(1)
    expect(document.body.textContent).toContain('成员选项读取失败：候选读取失败')
    mocks.agents.mockResolvedValueOnce({
      items: [
        { id: 'agent-3', name: '已发布成员丙', status: 'published' },
        { id: 'draft-agent', name: '未发布成员', status: 'draft' }
      ]
    })
    await click('重新读取成员选项')
    expect(mocks.agents).toHaveBeenCalledTimes(2)
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(inputs[0]!.props('modelValue')).toBe('本地团队草稿')
    expect(inputs[1]!.props('modelValue')).toBe('本地职责')
    expect(selectors[0]!.props('modelValue')).toEqual(['agent-2'])
    expect(selectors[1]!.props('modelValue')).toBe('agent-2')
    expect(
      selectors[0]!.findAllComponents(ElOption).map((option) => option.props('value'))
    ).toEqual(['agent-3', 'agent-2'])
    expect(
      selectors[0]!.findAllComponents(ElOption).map((option) => option.props('label'))
    ).toEqual(['已发布成员丙', '成员乙'])
    expect(mocks.update).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('成员目录失败时仍按原revision和成员身份保存已有团队', async () => {
    mocks.agents.mockRejectedValueOnce(new Error('目录暂不可用'))
    await open()
    await click('编辑')
    const drawer = wrapper.findComponent(ElDrawer)
    await drawer.findAllComponents(ElInput)[0]!.setValue('只改名称')
    mocks.update.mockResolvedValue({ ...team, name: '只改名称', revision: 5 })
    const save = drawer.findAllComponents(ElButton).find((item) => item.text() === '保存')!
    await save.trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledExactlyOnceWith(
      'team-1',
      expect.objectContaining({
        revision: 4,
        name: '只改名称',
        members: [{ agent_id: 'agent-1' }, { agent_id: 'agent-2' }],
        primary_agent_id: 'agent-1'
      })
    )
    expect(mocks.publish).not.toHaveBeenCalled()
  })
  it('新增目录失败不伪造成员，恢复后预选唯一已发布候选且保留名称', async () => {
    mocks.agents.mockRejectedValueOnce(new Error('目录暂不可用'))
    await open()
    await click('新增团队')
    const drawer = wrapper.findComponent(ElDrawer)
    const select = drawer.findAllComponents(ElSelect)[0]!
    expect(drawer.props('modelValue')).toBe(true)
    expect(select.props('modelValue')).toEqual([])
    expect(select.findAllComponents(ElOption)).toHaveLength(0)
    const save = drawer.findAllComponents(ElButton).find((item) => item.text() === '保存')!
    expect(save.props('disabled')).toBe(true)
    await drawer.findAllComponents(ElInput)[0]!.setValue('新团队草稿')
    mocks.agents.mockResolvedValueOnce({
      items: [
        { id: 'published', name: '可选成员', status: 'published' },
        { id: 'draft', name: '草稿成员', status: 'draft' }
      ]
    })
    await click('重新读取成员选项')
    expect(select.findAllComponents(ElOption).map((option) => option.props('value'))).toEqual([
      'published'
    ])
    expect(drawer.findAllComponents(ElInput)[0]!.props('modelValue')).toBe('新团队草稿')
    expect(select.props('modelValue')).toEqual(['published'])
    expect(drawer.findAllComponents(ElSelect)[1]!.props('modelValue')).toBe('published')
  })
  it('成功空目录给出准备成员指引，原成员仍按原名称显示', async () => {
    mocks.agents.mockResolvedValue({ items: [] })
    await open()
    await click('新增团队')
    expect(document.body.textContent).toContain('暂无可选的已发布智能体')
    const drawer = wrapper.findComponent(ElDrawer)
    await drawer
      .findAllComponents(ElButton)
      .find((item) => item.text() === '取消')!
      .trigger('click')
    await flushPromises()
    await click('编辑')
    const select = drawer.findAllComponents(ElSelect)[0]!
    expect(select.findAllComponents(ElOption).map((option) => option.props('label'))).toEqual([
      '成员甲',
      '成员乙'
    ])
    expect(select.props('modelValue')).toEqual(['agent-1', 'agent-2'])
  })
  it('无成员时打开管理并重新读取，保留团队草稿', async () => {
    mocks.permissions.push('foundation.agents.read')
    mocks.agents.mockResolvedValueOnce({ items: [] })
    await open()
    await click('新增团队')
    const drawer = wrapper.findComponent(ElDrawer)
    await drawer.findAllComponents(ElInput)[0]!.setValue('待配置团队')
    expect(document.body.querySelector('a[href="/admin/agents"]')?.getAttribute('target')).toBe(
      '_blank'
    )
    mocks.agents.mockResolvedValueOnce({
      items: [{ id: 'new', name: '新成员', status: 'published' }]
    })
    await click('重新读取成员选项')
    expect(drawer.findAllComponents(ElInput)[0]!.props('modelValue')).toBe('待配置团队')
    expect(
      drawer
        .findAllComponents(ElSelect)[0]!
        .findAllComponents(ElOption)
        .map((item) => item.props('value'))
    ).toEqual(['new'])
    expect(mocks.create).not.toHaveBeenCalled()
  })
})

describe('团队保存并发布', () => {
  it('一次组合请求成功后展示修订并可进入团队试用', async () => {
    mocks.saveAndPublish.mockImplementation(async (request_id: string) => ({
      request_id,
      status: 'SUCCEEDED',
      team: { ...team, revision: 5 }
    }))
    await open()
    await click('编辑')
    await click('保存并发布')
    expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
    expect(mocks.saveAndPublish).toHaveBeenCalledWith(
      expect.any(String),
      'team-1',
      expect.objectContaining({ revision: 4 })
    )
    expect(mocks.update).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('修订 5')
    await click('试用当前发布版本')
    expect(mocks.push).toHaveBeenCalledWith({ path: '/admin/chat', query: { team_id: 'team-1' } })
  })
  it('网络失败保留原请求号，查询无回执不重发，成功回执后解锁', async () => {
    mocks.saveAndPublish.mockRejectedValueOnce(new Error('连接中断'))
    mocks.saveAndPublishResult.mockResolvedValueOnce(undefined)
    await open()
    await click('编辑')
    await click('保存并发布')
    const requestId = mocks.saveAndPublish.mock.calls[0]![0]
    expect(document.body.textContent).toContain(requestId)
    expect(document.querySelector('.el-drawer')?.textContent).toContain('核对原操作')
    await click('核对原操作')
    expect(mocks.saveAndPublishResult).toHaveBeenCalledWith(requestId)
    expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain(requestId)
    mocks.saveAndPublishResult.mockResolvedValueOnce({
      request_id: requestId,
      status: 'SUCCEEDED',
      team: { ...team, revision: 5 }
    })
    await click('核对原操作')
    expect(document.body.textContent).not.toContain('写入已锁定')
    expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
  })
})

it('缺少团队读取权限时提示原因且不提供组合写入口', async () => {
  mocks.permissions = mocks.permissions.filter((item) => item !== 'foundation.teams.read')
  await open()
  await click('编辑')
  expect(document.querySelector('.el-drawer')?.textContent).toContain('保存并发布需要团队读取权限')
  expect(wrapper.findAllComponents(ElButton).some((item) => item.text() === '保存并发布')).toBe(
    false
  )
  expect(mocks.saveAndPublish).not.toHaveBeenCalled()
})

describe('发布原请求跨页面重建', () => {
  it('按机构和成员恢复 UNKNOWN，仅原主体能查询原请求', async () => {
    mocks.saveAndPublish.mockRejectedValueOnce(new Error('断连'))
    await open()
    await click('编辑')
    await click('保存并发布')
    const requestId = mocks.saveAndPublish.mock.calls[0]![0]
    wrapper.unmount()
    mocks.member = 'other'
    await open()
    expect(document.body.textContent).not.toContain(requestId)
    wrapper.unmount()
    mocks.member = 'user'
    await open()
    expect(document.body.textContent).toContain(requestId)
    expect(button('新增团队').props('disabled')).toBe(true)
    mocks.saveAndPublishResult.mockResolvedValueOnce(undefined)
    await click('核对原操作')
    expect(mocks.saveAndPublishResult).toHaveBeenCalledExactlyOnceWith(requestId)
    expect(document.body.textContent).toContain(requestId)
    expect(mocks.saveAndPublish).toHaveBeenCalledTimes(1)
  })
  it('无法持久化原请求时不发送组合写', async () => {
    await open()
    await click('编辑')
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw Error('blocked')
    })
    await click('保存并发布')
    expect(mocks.saveAndPublish).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('未发送发布请求')
    storage.mockRestore()
  })
  it('读取浏览器存储失败时阻止新的写入', async () => {
    const storage = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw Error('blocked')
    })
    await open()
    expect(button('新增团队').props('disabled')).toBe(true)
    expect(document.body.textContent).toContain('无法读取原发布请求记录')
    expect(mocks.saveAndPublish).not.toHaveBeenCalled()
    storage.mockRestore()
  })
  it('首次派发收到专用前置拒绝码才解除锁', async () => {
    mocks.saveAndPublish.mockRejectedValueOnce(
      new HttpClientError({
        status: 403,
        code: 'TEAM_PUBLISH_PRE_DISPATCH_REJECTED',
        userMessage: '权限不足'
      })
    )
    await open()
    await click('编辑')
    await click('保存并发布')
    expect(document.body.textContent).not.toContain('写入已锁定')
    expect(button('保存并发布').props('disabled')).toBe(false)
    wrapper.unmount()
    await open()
    expect(document.body.textContent).not.toContain('写入已锁定')
  })
  it('已有 UNKNOWN 的回执查询收到相同错误码也不解锁', async () => {
    mocks.saveAndPublish.mockRejectedValueOnce(new Error('断连'))
    mocks.saveAndPublishResult.mockRejectedValueOnce(
      new HttpClientError({
        status: 403,
        code: 'TEAM_PUBLISH_PRE_DISPATCH_REJECTED',
        userMessage: '权限不足'
      })
    )
    await open()
    await click('编辑')
    await click('保存并发布')
    const requestId = mocks.saveAndPublish.mock.calls[0]![0]
    await click('核对原操作')
    expect(document.body.textContent).toContain(requestId)
    wrapper.unmount()
    await open()
    expect(document.body.textContent).toContain(requestId)
  })
})

it('草稿保存响应未知时保留原请求并在刷新后自动核对', async () => {
  mocks.saveWithReceipt.mockRejectedValueOnce(new Error('连接中断'))
  await open()
  await click('编辑')
  const drawer = wrapper.findComponent(ElDrawer)
  await drawer.findAllComponents(ElInput)[0]!.setValue('保留的草稿')
  await button('保存').trigger('click')
  await flushPromises()
  const requestId = mocks.saveWithReceipt.mock.calls[0]![0]
  expect(document.body.textContent).toContain('草稿保存结果尚未确认')
  const key = `skillforge.team-draft.${JSON.stringify(['tenant', 'user'])}`
  expect(sessionStorage.getItem(key)).toContain(requestId)
  expect(sessionStorage.getItem(key)).not.toContain('保留的草稿')
  wrapper.unmount()
  mocks.saveReceipt.mockResolvedValueOnce({ request_id: requestId, team_id: 'team-1', revision: 5 })
  await open()
  expect(mocks.saveReceipt).toHaveBeenCalledExactlyOnceWith(requestId)
  expect(sessionStorage.getItem(key)).toBeNull()
  expect(mocks.saveWithReceipt).toHaveBeenCalledOnce()
  expect(document.body.textContent).not.toContain('草稿保存结果尚未确认')
})

it('未查到草稿回执时保持未知并阻止再次写入', async () => {
  const requestId = '70bd8fd8-4ce2-401c-80ac-39195e0678c2'
  const key = `skillforge.team-draft.${JSON.stringify(['tenant', 'user'])}`
  sessionStorage.setItem(key, JSON.stringify({ requestId, teamId: 'team-1', expectedRevision: 4 }))
  await open()
  expect(mocks.saveReceipt).toHaveBeenCalledExactlyOnceWith(requestId)
  expect(wrapper.text()).toContain('草稿保存结果尚未确认')
  expect(button('新增团队').props('disabled')).toBe(true)
  await click('核对草稿保存')
  expect(sessionStorage.getItem(key)).not.toBeNull()
  expect(mocks.saveWithReceipt).not.toHaveBeenCalled()
})
