import { defineComponent, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { useUnsavedChanges } from '@skillforge/shared/admin'
import InstitutionWorkspacePage from '../views/platform/InstitutionWorkspacePage.vue'

const fixture = vi.hoisted(() => ({
  auth: undefined as unknown as {
    identityVersion: number
    user: { sub: string; username: string; authorization_version: number; permissions: string[] }
  },
  directory: vi.fn(),
  detail: vi.fn(),
  summary: vi.fn(),
  details: vi.fn(),
  events: vi.fn(),
  confirm: vi.fn(),
  dirty: false
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => fixture.auth }))
vi.mock('./directory', () => ({ loadInstitutionDirectory: fixture.directory }))
vi.mock('../api', () => ({
  api: {
    admin: { getTenant: fixture.detail },
    usage: { summary: fixture.summary, details: fixture.details, events: fixture.events }
  }
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: fixture.confirm,
  message: { warning: vi.fn(), success: vi.fn() }
}))
vi.mock('../views/platform/TenantSettingsPanel.vue', () => ({
  __esModule: true,
  default: {
    name: 'TenantSettingsPanel',
    props: ['tenantId', 'embedded'],
    template: '<p data-settings>{{tenantId}}</p>'
  }
}))
vi.mock('../views/platform/InstitutionIntegrationsPage.vue', () => ({
  __esModule: true,
  default: {
    name: 'InstitutionIntegrationsPage',
    props: ['tenantId', 'embedded'],
    emits: ['provisioned', 'integrationSelected'],
    template: '<p data-mappings>{{tenantId}}</p>'
  }
}))
vi.mock('../views/platform/InstitutionCredentialsPage.vue', () => ({
  __esModule: true,
  default: {
    name: 'InstitutionCredentialsPage',
    props: { tenantId: String, integrationId: String, embedded: Boolean },
    template: '<p data-credentials>{{tenantId}} / {{integrationId}}</p>'
  }
}))
vi.mock('../views/platform/InstitutionHierarchyPanel.vue', () => ({
  __esModule: true,
  default: {
    name: 'InstitutionHierarchyPanel',
    props: ['tenantId', 'embedded'],
    template: '<p data-relations>{{tenantId}}</p>'
  }
}))
vi.mock('../views/platform/AgentGrantsPage.vue', () => ({
  __esModule: true,
  default: {
    name: 'AgentGrantsPage',
    props: ['tenantId', 'embedded'],
    template: '<p data-grants>{{tenantId}}</p>'
  }
}))
vi.mock('../views/platform/AgentDeploymentsPage.vue', () => ({
  __esModule: true,
  default: {
    name: 'AgentDeploymentsPage',
    props: ['tenantId', 'embedded'],
    template: '<p data-deployments>{{tenantId}}</p>'
  }
}))
vi.mock('../views/platform/InstitutionOnboardingFlow.vue', () => ({
  __esModule: true,
  default: {
    name: 'InstitutionOnboardingFlow',
    props: ['tenantId'],
    emits: ['targetSelected'],
    template: '<p data-onboarding>{{tenantId}}</p>'
  }
}))
vi.mock('../views/platform/InstitutionAgentSetup.vue', () => ({
  __esModule: true,
  default: {
    name: 'InstitutionAgentSetup',
    props: ['tenantId', 'integrationId'],
    template: '<p data-setup>{{tenantId}}</p>'
  }
}))
const frame = defineComponent({
  props: ['nodes', 'selectedId', 'sections', 'section', 'actorLabel', 'error'],
  emits: ['select', 'section', 'refresh'],
  template: `<main><p data-target>{{selectedId}}</p><p data-section>{{section}}</p><p data-actor>{{actorLabel}}</p>
    <button @click="$emit('select','10')">选择机构甲</button><button @click="$emit('select','11')">选择机构乙</button>
    <button v-for="item in sections" :key="item.key" :data-nav="item.key" @click="$emit('section',item.key)">{{item.label}}</button>
    <slot name="summary"/><slot/></main>`
})
const wrappers: VueWrapper[] = []
beforeEach(() => {
  vi.clearAllMocks()
  fixture.dirty = false
  fixture.auth = reactive({
    identityVersion: 1,
    user: {
      sub: 'platform-actor',
      username: '平台维护员',
      authorization_version: 1,
      permissions: [
        'foundation.tenants.read',
        'platform.institutions.read',
        'platform.institution_credentials.read',
        'platform.agent_deployments.read',
        'platform.agent_grants.read',
        'platform.institution_relations.read',
        'platform.usage.summary.read',
        'platform.usage.details.read',
        'platform.usage.events.read'
      ]
    }
  })
  fixture.directory.mockResolvedValue({
    nodes: [
      { id: '10', label: '机构甲', status: 'active' },
      { id: '11', label: '机构乙', parentId: '10', status: 'active' }
    ],
    complete: true,
    hierarchy: true
  })
  fixture.detail.mockImplementation(async (id: string) => ({
    id,
    name: id === '10' ? '机构甲' : '机构乙',
    plan_code: `plan-${id}`
  }))
  fixture.summary.mockResolvedValue({
    request_count: 0,
    invocation_count: 0,
    prompt_tokens: 0,
    completion_tokens: 0,
    unknown_usage_count: 0,
    unresolved_cost_count: 0,
    costs: []
  })
  fixture.details.mockResolvedValue({ items: [], next_cursor: null, has_more: false })
  fixture.events.mockResolvedValue({ items: [], next_cursor: null, has_more: false })
  fixture.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

async function open(path: string, guardSettings = false) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/platform/tenants/:id?', component: InstitutionWorkspacePage }]
  })
  await router.push(path)
  await router.isReady()
  const guardedSettings = defineComponent({
    props: ['tenantId', 'embedded'],
    setup() {
      useUnsavedChanges({ dirty: () => fixture.dirty })
      return {}
    },
    template: '<p data-settings>{{tenantId}}</p>'
  })
  const wrapper = mount(defineComponent({ template: '<RouterView />' }), {
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: {
        InstitutionWorkspace: frame,
        ...(guardSettings ? { TenantSettingsPanel: guardedSettings } : {})
      }
    }
  })
  wrappers.push(wrapper)
  await flushPromises()
  await flushPromises()
  return { wrapper, router }
}
async function click(wrapper: VueWrapper, text: string) {
  const found = wrapper.findAll('button').find((button) => button.text() === text)
  if (!found) throw Error(text)
  await found.trigger('click')
  await flushPromises()
}
async function historyBack(router: Router) {
  const navigated = new Promise<void>((resolve) => {
    const stop = router.afterEach(() => {
      stop()
      resolve()
    })
  })
  router.back()
  await navigated
  await flushPromises()
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}

describe('平台机构工作台的路由、当前目标和权限装配', () => {
  it('深链固定映射/凭证面板目标与接入，切换不修改平台登录身份', async () => {
    const { wrapper, router } = await open(
      '/platform/tenants/10?section=access&view=credentials&integration_id=stable-integration'
    )
    expect(wrapper.get('[data-credentials]').text()).toBe('10 / stable-integration')
    expect(wrapper.findComponent({ name: 'InstitutionCredentialsPage' }).props()).toMatchObject({
      tenantId: '10',
      integrationId: 'stable-integration',
      embedded: true
    })
    await click(wrapper, '选择机构乙')
    expect(router.currentRoute.value.params.id).toBe('11')
    expect(wrapper.get('[data-mappings]').text()).toBe('11')
    expect(fixture.auth.user.sub).toBe('platform-actor')
    expect(wrapper.get('[data-actor]').text()).toContain('平台维护员')
  })

  it.each([
    ['platform.agent_deployments.read', 'deployments', 'data-deployments'],
    ['platform.agent_grants.read', 'grants', 'data-grants']
  ])('仅有%s的角色不读取机构详情，仍挂载对应目标面板', async (permission, view, selector) => {
    fixture.auth.user.permissions = [permission]
    const { wrapper } = await open(`/platform/tenants/10?section=agents&view=${view}`)
    expect(fixture.detail).not.toHaveBeenCalled()
    expect(wrapper.get(`[${selector}]`).text()).toBe('10')
    expect(wrapper.find('[data-nav="settings"]').exists()).toBe(false)
    expect(wrapper.find('[data-nav="relations"]').exists()).toBe(false)
  })

  it('显式指定无权view不回退到其他有权面板', async () => {
    fixture.auth.user.permissions = ['platform.agent_deployments.read']
    const { wrapper } = await open('/platform/tenants/10?section=agents&view=grants')
    expect(wrapper.text()).toContain('没有所选智能体分区的读取权限')
    expect(wrapper.find('[data-grants]').exists()).toBe(false)
    expect(wrapper.find('[data-deployments]').exists()).toBe(false)
  })

  it('明确非法section不回退到概览或挂载维护面板', async () => {
    const { wrapper } = await open('/platform/tenants/10?section=not-a-section')
    expect(wrapper.text()).toContain('没有该分区的维护权限')
    expect(wrapper.find('.institution-overview').exists()).toBe(false)
    expect(wrapper.find('[data-settings]').exists()).toBe(false)
  })

  it('A到B的迟到详情不能污染新目标，旧请求被取消', async () => {
    const a = deferred<{ id: string; name: string; plan_code: string }>()
    fixture.detail.mockImplementation((id: string) =>
      id === '10' ? a.promise : Promise.resolve({ id, name: '机构乙', plan_code: '乙套餐' })
    )
    const { wrapper } = await open('/platform/tenants/10')
    const signal = fixture.detail.mock.calls[0]![1].signal as AbortSignal
    await click(wrapper, '选择机构乙')
    expect(signal.aborted).toBe(true)
    a.resolve({ id: '10', name: '迟到机构甲', plan_code: '禁止回填的甲套餐' })
    await flushPromises()
    expect(wrapper.get('[data-target]').text()).toBe('11')
    expect(wrapper.text()).toContain('乙套餐')
    expect(wrapper.text()).not.toContain('禁止回填的甲套餐')
    expect(wrapper.text()).not.toContain('机构读取目标已变化')
  })

  it('页签和机构进入URL，浏览器返回恢复原机构及分区', async () => {
    const { wrapper, router } = await open('/platform/tenants/10?section=agents&view=grants')
    await click(wrapper, '机构设置')
    expect(router.currentRoute.value.query.section).toBe('settings')
    await click(wrapper, '选择机构乙')
    expect(wrapper.get('[data-settings]').text()).toBe('11')
    await historyBack(router)
    expect(router.currentRoute.value.params.id).toBe('10')
    expect(router.currentRoute.value.query.section).toBe('settings')
    await historyBack(router)
    expect(router.currentRoute.value.query).toMatchObject({ section: 'agents', view: 'grants' })
    expect(wrapper.get('[data-grants]').text()).toBe('10')
  })

  it('真实用量面板重置后，summary/details/events仍固定目标机构', async () => {
    const { wrapper } = await open('/platform/tenants/10?section=usage')
    await click(wrapper, '重置')
    expect(fixture.summary).toHaveBeenCalled()
    expect(fixture.details).toHaveBeenCalled()
    for (const call of [
      ...fixture.summary.mock.calls,
      ...fixture.details.mock.calls,
      ...fixture.events.mock.calls
    ])
      expect(call[0].tenant_id).toBe('10')
    const panel = wrapper.findComponent({ name: 'UsagePanel' })
    const bound = panel.props('api')
    await bound.events({ tenant_id: '999' })
    await bound.details({})
    await bound.summary({ tenant_id: '999' })
    expect(fixture.events.mock.calls.at(-1)![0].tenant_id).toBe('10')
    expect(fixture.details.mock.calls.at(-1)![0].tenant_id).toBe('10')
    expect(fixture.summary.mock.calls.at(-1)![0].tenant_id).toBe('10')
  })

  it('子面板未保存保护拒绝切换，目标和当前页签保持不变', async () => {
    const { wrapper, router } = await open('/platform/tenants/10?section=settings', true)
    expect(wrapper.get('[data-settings]').text()).toBe('10')
    fixture.dirty = true
    fixture.confirm.mockResolvedValue(false)
    await click(wrapper, '选择机构乙')
    expect(router.currentRoute.value.params.id).toBe('10')
    expect(wrapper.get('[data-target]').text()).toBe('10')
    expect(wrapper.get('[data-settings]').text()).toBe('10')
    expect(fixture.confirm).toHaveBeenCalledWith(expect.objectContaining({ title: '离开页面' }))
    expect(fixture.detail.mock.calls.some((call) => call[0] === '11')).toBe(false)
  })
  it('向导选定机构只同步目标摘要，不重建流程或变更原命令身份', async () => {
    fixture.auth.user.permissions.push('foundation.tenants.create')
    const { wrapper, router } = await open('/platform/tenants?section=onboarding')
    const flow = wrapper.findComponent({ name: 'InstitutionOnboardingFlow' })
    const original = flow.vm
    flow.vm.$emit('targetSelected', { tenantId: '11', integrationId: 'integration-11' })
    await flushPromises()
    expect(wrapper.get('[data-target]').text()).toBe('11')
    expect(flow.props('tenantId')).toBeUndefined()
    expect(wrapper.findComponent({ name: 'InstitutionOnboardingFlow' }).vm).toBe(original)
    expect(router.currentRoute.value.params.id ?? '').toBe('')
    expect(fixture.auth.user.sub).toBe('platform-actor')
  })
})
