import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElForm, ElInput } from 'element-plus'
import { reactive, ref } from 'vue'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import { HttpClientError } from '@skillforge/shared/utils/http'
import SkillWorkbenchListPage from './SkillWorkbenchListPage.vue'
const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  lookup: vi.fn(),
  oldCreate: vi.fn(),
  oldDraft: vi.fn(),
  push: vi.fn(),
  warning: vi.fn()
}))
const auth = reactive({
  user: { sub: '8', permissions: ['foundation.skills.read'] },
  identityVersion: 1
})
vi.mock('../../stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('../../api', () => ({
  api: {
    capabilities: {
      listSkillCatalog: mocks.list,
      getVersion: vi.fn(),
      createDefinition: mocks.oldCreate
    },
    skillDrafts: {
      createWithReceipt: async (requestId: string, payload: unknown) => {
        const result = await mocks.create(payload)
        return {
          request_id: requestId,
          definition_id: result.definitionId,
          draft_id: result.id,
          revision: result.revision
        }
      },
      creationReceipt: mocks.lookup,
      create: mocks.oldDraft
    }
  }
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/feedback', async (load) => {
  const actual = await load<typeof import('@skillforge/shared/feedback')>()
  return { ...actual, message: { ...actual.message, warning: mocks.warning } }
})
let wrapper: VueWrapper | undefined
const stubLink = { props: ['to'], template: '<a :href="to"><slot /></a>' }
const receipt = {
  id: '9223372036854775806',
  definitionId: '9223372036854775807',
  revision: 1,
  versionKey: '',
  files: {}
}
it('新建技能不要求标识，断连后保留原请求', async () => {
  mocks.create.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '结果未知' }))
  wrapper = mount(SkillWorkbenchListPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: stubLink } }
  })
  await flushPromises()
  await button('新建技能').trigger('click')
  await flushPromises()
  const inputs = wrapper.findComponent(ElDrawer).findAllComponents(ElInput)
  expect(wrapper.findComponent(ElDrawer).text()).not.toContain('技能标识')
  await inputs[0]!.setValue('自动标识技能')
  await create()
  expect(mocks.create).toHaveBeenCalledExactlyOnceWith({
    name: '自动标识技能',
    description: ''
  })
  expect(wrapper.text()).toContain('自动标识技能')
  expect(button('新建技能').props('disabled')).toBe(true)
})
it('刷新后未查到原回执仍锁定，不重新发送创建', async () => {
  mocks.create.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '结果未知' }))
  await open()
  await create()
  wrapper!.unmount()
  mocks.lookup.mockResolvedValue(undefined)
  wrapper = mount(SkillWorkbenchListPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: stubLink } }
  })
  await flushPromises()
  await button('核对创建结果').trigger('click')
  await flushPromises()
  expect(button('新建技能').props('disabled')).toBe(true)
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.lookup).toHaveBeenCalledTimes(2)
})
it('刷新后自动核对成功回执并进入原草稿，不再发起创建', async () => {
  mocks.create.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '结果未知' }))
  await open()
  await create()
  wrapper!.unmount()
  mocks.lookup.mockImplementationOnce(async (requestId: string) => ({
    request_id: requestId,
    definition_id: receipt.definitionId,
    draft_id: receipt.id,
    revision: receipt.revision
  }))
  wrapper = mount(SkillWorkbenchListPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: stubLink } }
  })
  await flushPromises()
  expect(mocks.push).toHaveBeenCalledWith('/platform/skills/' + receipt.definitionId)
  expect(mocks.create).toHaveBeenCalledTimes(1)
  expect(mocks.lookup).toHaveBeenCalledTimes(1)
})
it('没有技能读取权限时不自动查询原回执', async () => {
  mocks.create.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '结果未知' }))
  await open()
  await create()
  wrapper!.unmount()
  auth.user.permissions = []
  wrapper = mount(SkillWorkbenchListPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: stubLink } }
  })
  await flushPromises()
  expect(mocks.lookup).not.toHaveBeenCalled()
  expect(button('新建技能').props('disabled')).toBe(true)
})
it('自动核对失败后仍保留原请求并可手动核对', async () => {
  mocks.create.mockRejectedValueOnce(new HttpClientError({ status: 0, userMessage: '结果未知' }))
  await open()
  await create()
  wrapper!.unmount()
  mocks.lookup.mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce(undefined)
  wrapper = mount(SkillWorkbenchListPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: stubLink } }
  })
  await flushPromises()
  expect(button('新建技能').props('disabled')).toBe(true)
  await button('核对创建结果').trigger('click')
  await flushPromises()
  expect(mocks.lookup).toHaveBeenCalledTimes(2)
  expect(mocks.create).toHaveBeenCalledTimes(1)
})
function button(label: string) {
  return wrapper!.findAllComponents(ElButton).find((item) => item.text() === label)!
}
async function open() {
  wrapper = mount(SkillWorkbenchListPage, {
    attachTo: document.body,
    global: { stubs: { RouterLink: stubLink } }
  })
  await flushPromises()
  await button('新建技能').trigger('click')
  await flushPromises()
  const drawer = wrapper.findComponent(ElDrawer)
  const inputs = drawer.findAllComponents(ElInput)
  await inputs[0]!.setValue(' 客服技能 ')
  await inputs[1]!.setValue('处理客户咨询')
  return drawer
}
async function create() {
  await button('创建并进入工作草稿').trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  auth.user = { sub: '8', permissions: ['foundation.skills.read'] }
  auth.identityVersion = 1
  sessionStorage.clear()
  mocks.list.mockResolvedValue([])
  mocks.create.mockResolvedValue(receipt)
  mocks.push.mockResolvedValue(undefined)
})
it('身份切换后清除原主体创建结果并允许新主体创建', async () => {
  mocks.push.mockRejectedValueOnce(new Error('导航失败'))
  await open()
  await create()
  expect(wrapper!.text()).toContain(receipt.id)
  auth.user = { sub: '10', permissions: ['foundation.skills.read'] }
  auth.identityVersion++
  await flushPromises()
  expect(wrapper!.text()).not.toContain(receipt.id)
  expect(button('新建技能').props('disabled')).toBe(false)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})
it('按技能用途筛选平台摘要且不逐个读取版本', async () => {
  mocks.list.mockResolvedValue([
    {
      id: '1',
      name: '客服',
      capabilityKey: 'support',
      description: '处理订单售后',
      status: 'PUBLISHED',
      defaultVersionId: '11',
      defaultVersionKey: 'v1'
    },
    {
      id: '2',
      name: '报告',
      capabilityKey: 'report',
      description: null,
      status: 'DRAFT',
      defaultVersionId: null,
      defaultVersionKey: null
    }
  ])
  wrapper = mount(SkillWorkbenchListPage, { global: { stubs: { RouterLink: stubLink } } })
  await flushPromises()
  await wrapper.get('input[aria-label="搜索技能"]').setValue('订单售后')
  expect(wrapper.text()).toContain('客服')
  expect(wrapper.text()).not.toContain('报告')
  expect(mocks.list).toHaveBeenCalledTimes(1)
})
it('creates a skill and draft with one command and navigates using the exact returned definition identity', async () => {
  await open()
  await create()
  expect(mocks.create).toHaveBeenCalledExactlyOnceWith({
    name: '客服技能',
    description: '处理客户咨询'
  })
  expect(mocks.oldCreate).not.toHaveBeenCalled()
  expect(mocks.oldDraft).not.toHaveBeenCalled()
  expect(mocks.push).toHaveBeenCalledExactlyOnceWith('/platform/skills/' + receipt.definitionId)
  expect(wrapper!.text()).toContain(receipt.id)
  expect(wrapper!.find('a[href="/platform/skills/' + receipt.definitionId + '"]').exists()).toBe(
    true
  )
})
it.each(['throw', 'resolved-failure'])(
  'retains the successful receipt and original workbench link when navigation fails (%s)',
  async (kind) => {
    if (kind === 'throw') mocks.push.mockRejectedValueOnce(new Error('导航失败'))
    else mocks.push.mockResolvedValueOnce({ type: 4 })
    await open()
    await create()
    expect(mocks.warning).toHaveBeenCalledWith(expect.stringContaining('技能已创建'))
    expect(wrapper!.text()).toContain(receipt.id)
    expect(wrapper!.find('a[href="/platform/skills/' + receipt.definitionId + '"]').exists()).toBe(
      true
    )
    expect(button('新建技能').props('disabled')).toBe(true)
    await button('新建技能').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledTimes(1)
  }
)
it.each([
  new Error('网络中断'),
  new HttpClientError({ status: 0, userMessage: '网络中断' }),
  new HttpClientError({ status: 408, userMessage: '请求超时' }),
  new HttpClientError({ status: 503, userMessage: '服务不可用' })
])(
  'freezes unknown input and never interprets a missing read-only list as permission to create again (%s)',
  async (failure) => {
    mocks.create.mockRejectedValueOnce(failure)
    const drawer = await open()
    await create()
    expect(drawer.props('modelValue')).toBe(true)
    expect(drawer.findComponent(ElForm).props('disabled')).toBe(true)
    expect(drawer.findAllComponents(ElInput)[0]!.props('modelValue')).toBe(' 客服技能 ')
    expect(button('创建并进入工作草稿').props('disabled')).toBe(true)
    expect(wrapper!.text()).toContain('列表缺失不代表创建失败')
    await button('查看当前技能列表').trigger('click')
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(button('创建并进入工作草稿').props('disabled')).toBe(true)
    await drawer
      .findAllComponents(ElButton)
      .find((item) => item.text() === '取消')!
      .trigger('click')
    await flushPromises()
    await button('查看原创建输入').trigger('click')
    await flushPromises()
    expect(drawer.findAllComponents(ElInput)[0]!.props('modelValue')).toBe(' 客服技能 ')
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(mocks.push).not.toHaveBeenCalled()
  }
)
it('a definite validation rejection keeps editable input and allows correcting the original form', async () => {
  mocks.create.mockRejectedValueOnce(
    new HttpClientError({ status: 422, code: 'SKILL_CREATE_REJECTED', userMessage: '名称不合法' })
  )
  const drawer = await open()
  await create()
  expect(drawer.findComponent(ElForm).props('disabled')).toBe(false)
  await drawer.findAllComponents(ElInput)[0]!.setValue('修正名称')
  await create()
  expect(mocks.create).toHaveBeenCalledTimes(2)
  expect(mocks.create).toHaveBeenLastCalledWith(expect.objectContaining({ name: '修正名称' }))
})
it('all new-skill buttons require both definition and draft creation permissions', async () => {
  const permissions = ref(['foundation.capabilities.create'])
  wrapper = mount(SkillWorkbenchListPage, {
    attachTo: document.body,
    global: {
      directives: { permission: createPermissionDirective(() => permissions) },
      stubs: { RouterLink: stubLink }
    }
  })
  await flushPromises()
  const visibleButtons = () =>
    [...document.querySelectorAll('button')].filter(
      (item) => item.textContent?.trim() === '新建技能'
    )
  expect(visibleButtons()).toHaveLength(0)
  permissions.value = ['foundation.skills.create']
  await flushPromises()
  expect(visibleButtons()).toHaveLength(0)
  permissions.value = ['foundation.capabilities.create', 'foundation.skills.create']
  await flushPromises()
  expect(visibleButtons().length).toBeGreaterThan(0)
  await button('新建技能').trigger('click')
  await flushPromises()
  const visibleSubmit = () =>
    [...document.querySelectorAll('button')].filter(
      (item) => item.textContent?.trim() === '创建并进入工作草稿'
    )
  expect(visibleSubmit()).toHaveLength(1)
  permissions.value = ['foundation.capabilities.create']
  await flushPromises()
  expect(visibleSubmit()).toHaveLength(0)
  permissions.value = ['foundation.capabilities.create', 'foundation.skills.create']
  await flushPromises()
  expect(visibleSubmit()).toHaveLength(1)
  expect(mocks.create).not.toHaveBeenCalled()
})
