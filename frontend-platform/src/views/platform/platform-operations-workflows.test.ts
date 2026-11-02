import { computed, defineComponent, reactive, type Component } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElButton, ElForm, ElFormItem, ElPagination, ElSelect } from 'element-plus'
import { EntitySelect } from '@skillforge/shared/admin'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import AgentDeploymentsPage from './AgentDeploymentsPage.vue'
import AgentGrantsPage from './AgentGrantsPage.vue'
import DashboardPage from './DashboardPage.vue'
import OperationsPage from './OperationsPage.vue'

const mocks = vi.hoisted(() => ({
  deploymentList: vi.fn(),
  definitions: vi.fn(),
  deploymentGrants: vi.fn(),
  createDeployment: vi.fn(),
  grantList: vi.fn(),
  createGrant: vi.fn(),
  revokeGrant: vi.fn(),
  health: vi.fn(),
  audit: vi.fn(),
  confirm: vi.fn(),
  auth: undefined as unknown as {
    identityVersion: number
    user: { sub: string; permissions: string[] }
  }
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../../api', () => ({
  api: {
    agentDeployments: {
      list: mocks.deploymentList,
      definitions: mocks.definitions,
      grants: mocks.deploymentGrants,
      create: mocks.createDeployment
    },
    agentGrants: { list: mocks.grantList, create: mocks.createGrant, revoke: mocks.revokeGrant },
    operations: { auditLogs: mocks.audit },
    health: mocks.health
  }
}))
vi.mock('../../api/entityOptions', () => {
  const options = [
    { id: 'A', label: '机构甲' },
    { id: 'B', label: '机构乙' },
    { id: 'definition-1', label: '公共定义' }
  ]
  const load = async () => options
  const resolve = async (id: string) => options.find((item) => item.id === id)
  return {
    createOperationOptions: () => ({ load, resolve }),
    loadTenantOptions: load,
    resolveTenantOption: resolve
  }
})
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm,
  message: { success: vi.fn(), warning: vi.fn() }
}))
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.clearAllMocks()
  mocks.auth = reactive({
    identityVersion: 1,
    user: {
      sub: 'actor',
      permissions: [
        'platform.agent_deployments.read',
        'platform.agent_deployments.create',
        'platform.agent_grants.read',
        'platform.agent_grants.create',
        'platform.agent_grants.revoke',
        'foundation.billing.read',
        'foundation.billing.create',
        'foundation.billing.update'
      ]
    }
  })
  mocks.confirm.mockResolvedValue(true)
  mocks.deploymentList.mockResolvedValue({ items: [] })
  mocks.definitions.mockResolvedValue({
    items: [{ id: 'definition-1', name: '公共定义', version: 'v1' }]
  })
  mocks.deploymentGrants.mockResolvedValue({
    items: [
      {
        definition_id: 'definition-1',
        grant_id: 'grant-1',
        status: 'active',
        target_tenant_id: 'A',
        actions: ['deploy', 'execute'],
        expires_at: null,
        definition_name: '公共定义'
      }
    ]
  })
  mocks.grantList.mockResolvedValue({ items: [] })
  mocks.health.mockResolvedValue({ status: 'ok', deps: { postgres: { ok: true } } })
  mocks.audit.mockResolvedValue({ items: [], total: 60 })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})
async function open(component: Component) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component }]
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(defineComponent({ template: '<RouterView />' }), {
    attachTo: document.body,
    global: {
      plugins: [router],
      directives: {
        permission: createPermissionDirective(() => computed(() => mocks.auth.user.permissions))
      }
    }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function field(wrapper: VueWrapper, label: string) {
  return wrapper.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
}
async function click(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAllComponents(ElButton).find((item) => item.text() === text)
  if (!button) throw Error(text)
  await button.trigger('click')
  await flushPromises()
}
async function select(wrapper: VueWrapper, label: string, value: string) {
  const control = field(wrapper, label).findComponent(EntitySelect)
  ;(control.exists() ? control : field(wrapper, label).getComponent(ElSelect)).vm.$emit(
    'update:modelValue',
    value
  )
  await flushPromises()
}
describe('平台操作上下文与原操作恢复', () => {
  it('授权未查询、有效空结果与读取失败分别提示，不把读取失败显示为无授权', async () => {
    const wrapper = await open(AgentGrantsPage)
    expect(wrapper.text()).toContain('选择目标机构后，查看直接授权')
    expect(mocks.grantList).not.toHaveBeenCalled()
    await select(wrapper, '目标机构', 'A')
    expect(mocks.grantList.mock.lastCall?.[0]).toBe('A')
    expect(wrapper.text()).toContain('该机构当前没有直接授权')
    mocks.grantList.mockRejectedValueOnce(
      new HttpClientError({ status: 503, userMessage: '授权目录暂不可读' })
    )
    await click(wrapper, '刷新授权事实')
    expect(document.body.querySelector('.el-message--error')?.textContent).toContain(
      '授权目录暂不可读'
    )
    expect(wrapper.text()).toContain('授权目录尚未读取')
    expect(wrapper.text()).not.toContain('该机构当前没有直接授权')
    expect(mocks.createGrant).not.toHaveBeenCalled()
    expect(mocks.revokeGrant).not.toHaveBeenCalled()
  })
  it('选定机构自动读取授权；有草稿时取消切换保留原目标与输入', async () => {
    const wrapper = await open(AgentGrantsPage)
    await select(wrapper, '目标机构', 'A')
    expect(mocks.grantList.mock.lastCall?.[0]).toBe('A')
    await select(wrapper, '公共定义', 'definition-1')
    await select(wrapper, '目标机构', 'B')
    expect(mocks.grantList.mock.lastCall?.[0]).toBe('A')
    mocks.confirm.mockResolvedValueOnce(false)
    await click(wrapper, '查询授权')
    expect(field(wrapper, '公共定义').getComponent(EntitySelect).props('modelValue')).toBe(
      'definition-1'
    )
    expect(mocks.grantList.mock.lastCall?.[0]).toBe('A')
    await click(wrapper, '查询授权')
    expect(mocks.grantList.mock.lastCall?.[0]).toBe('B')
    expect(field(wrapper, '公共定义').getComponent(EntitySelect).props('modelValue')).toBe('')
  })
  it('选择机构直接读取部署和配置，无草稿切换时清除旧机构结果', async () => {
    const wrapper = await open(AgentDeploymentsPage)
    expect(mocks.deploymentList).not.toHaveBeenCalled()
    await select(wrapper, '目标机构', 'A')
    expect(mocks.deploymentList.mock.lastCall?.[1]).toBe('A')
    expect(mocks.definitions.mock.lastCall?.[1]).toBe('A')
    expect(mocks.deploymentGrants.mock.lastCall?.[1]).toBe('A')
    await select(wrapper, '目标机构', 'B')
    expect(mocks.deploymentList.mock.lastCall?.[1]).toBe('B')
    expect(mocks.definitions.mock.lastCall?.[1]).toBe('B')
    expect(mocks.deploymentGrants.mock.lastCall?.[1]).toBe('B')
    expect(wrapper.text()).toContain('当前查询与操作目标机构：B')
  })
  it('同机构重查保留部署草稿；换机构清掉完整部署输入，唯一有效授权自动预选', async () => {
    const wrapper = await open(AgentDeploymentsPage)
    await select(wrapper, '目标机构', 'A')
    await click(wrapper, '查询机构部署')
    await select(wrapper, '公共定义', 'definition-1')
    expect(field(wrapper, '直接授权').getComponent(ElSelect).props('modelValue')).toBe('grant-1')
    await field(wrapper, '外部智能体标识').get('input').setValue('external-agent')
    await field(wrapper, '实例名称').get('input').setValue('机构甲助手')
    await click(wrapper, '查询机构部署')
    expect(field(wrapper, '直接授权').getComponent(ElSelect).props('modelValue')).toBe('grant-1')
    expect(field(wrapper, '实例名称').get<HTMLInputElement>('input').element.value).toBe(
      '机构甲助手'
    )
    await select(wrapper, '目标机构', 'B')
    expect(wrapper.text()).toContain('机构选择已变化')
    await wrapper.findAllComponents(ElForm)[1]!.trigger('submit')
    expect(mocks.createDeployment).not.toHaveBeenCalled()
    mocks.confirm.mockResolvedValueOnce(false)
    await click(wrapper, '查询机构部署')
    expect(field(wrapper, '实例名称').get<HTMLInputElement>('input').element.value).toBe(
      '机构甲助手'
    )
    expect(mocks.deploymentList.mock.lastCall?.[1]).toBe('A')
    await click(wrapper, '查询机构部署')
    expect(field(wrapper, '公共定义').getComponent(ElSelect).props('modelValue')).toBe('')
    expect(field(wrapper, '外部智能体标识').get<HTMLInputElement>('input').element.value).toBe('')
    expect(field(wrapper, '实例名称').get<HTMLInputElement>('input').element.value).toBe('')
    expect(mocks.deploymentList.mock.lastCall?.[1]).toBe('B')
  })

  it('授权未知结果必须先核对原机构；确认后切换机构清理定义和期限', async () => {
    const wrapper = await open(AgentGrantsPage)
    await select(wrapper, '目标机构', 'A')
    await click(wrapper, '查询授权')
    await select(wrapper, '公共定义', 'definition-1')
    mocks.createGrant.mockRejectedValueOnce(
      new HttpClientError({ status: 0, userMessage: '连接中断' })
    )
    await click(wrapper, '授予部署与执行权限')
    expect(mocks.createGrant.mock.lastCall?.[0].target_tenant_id).toBe('A')
    await select(wrapper, '目标机构', 'B')
    const reads = mocks.grantList.mock.calls.length
    await click(wrapper, '查询授权')
    expect(mocks.grantList).toHaveBeenCalledTimes(reads)
    await click(wrapper, '刷新授权事实')
    expect(mocks.grantList.mock.lastCall?.[0]).toBe('A')
    expect(wrapper.text()).toContain('核对原请求')
    const original = mocks.createGrant.mock.lastCall
    mocks.createGrant.mockResolvedValueOnce({ grant_id: 'confirmed-grant' })
    await click(wrapper, '核对原请求')
    expect(mocks.createGrant.mock.lastCall).toEqual(original)
    await click(wrapper, '查询授权')
    expect(mocks.grantList.mock.lastCall?.[0]).toBe('B')
    expect(field(wrapper, '公共定义').getComponent(EntitySelect).props('modelValue')).toBe('')
  })

  it('系统状态刷新失败保留上次结果，同时明确其尚未代表当前状态', async () => {
    const wrapper = await open(DashboardPage)
    expect(wrapper.text()).toContain('依赖服务正常')
    mocks.health.mockRejectedValueOnce(new Error('网络不可用'))
    await click(wrapper, '刷新')
    expect(wrapper.text()).toContain('postgres')
    expect(document.body.querySelector('.el-message--error')?.textContent).toContain(
      '上次成功读取的结果，当前状态尚未确认'
    )
  })

  it('没有机构目录权限仍能按历史机构标识审计，编辑筛选不改变翻页范围', async () => {
    mocks.auth.user.permissions = ['foundation.audit.read']
    const wrapper = await open(OperationsPage)
    expect(wrapper.findComponent(EntitySelect).exists()).toBe(false)
    const input = wrapper.get('input[placeholder="历史机构标识"]')
    await input.setValue('deleted-tenant')
    await wrapper.get('form.admin-filters').trigger('submit')
    await flushPromises()
    expect(mocks.audit.mock.lastCall?.[0]).toMatchObject({ page: 1, tenant_id: 'deleted-tenant' })
    await input.setValue('other-tenant')
    expect(wrapper.text()).toContain('筛选已修改，查询后生效')
    wrapper.getComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(mocks.audit.mock.lastCall?.[0]).toMatchObject({ page: 2, tenant_id: 'deleted-tenant' })
    expect(wrapper.text()).not.toContain('账单状态扫描 · 平台运维操作')
  })
})
