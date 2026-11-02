import { defineComponent, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import InstitutionWorkspacePage from '../views/admin/InstitutionWorkspacePage.vue'

const fixture = vi.hoisted(() => ({
  auth: {
    identityVersion: 1,
    user: {
      tenant_id: '10',
      sub: 'operator',
      username: '总部管理员',
      authorization_version: 1,
      permissions: [] as string[]
    }
  },
  list: vi.fn(),
  targets: vi.fn()
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => reactive(fixture.auth) }))
vi.mock('../api', () => ({
  api: {
    institutionManagement: {
      subInstitutions: { list: fixture.list },
      targetOptions: fixture.targets
    }
  }
}))
const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
})
beforeEach(() => {
  vi.clearAllMocks()
  fixture.auth.identityVersion++
  fixture.auth.user.permissions = [
    'tenant.sub_institutions.read',
    'tenant.agent_deployments.read',
    'foundation.access.read',
    'foundation.service_principals.read'
  ]
  fixture.list.mockResolvedValue({
    items: [
      { tenant_id: '11', parent_tenant_id: '10', name: '下级机构', status: 'active', revision: 1 }
    ],
    total: 1,
    tree_revision: 1
  })
  fixture.targets.mockResolvedValue({ items: [], total: 0 })
})
const frame = defineComponent({
  props: ['nodes', 'selectedId', 'sections', 'section', 'actorLabel', 'error'],
  emits: ['select', 'section', 'refresh'],
  template:
    "<main><p data-actor>{{actorLabel}}</p><p data-target>{{selectedId}}</p><button @click=\"$emit('select','11')\">选择下级</button><button @click=\"$emit('section','access')\">维护凭证</button><slot name=\"summary\"/><slot/></main>"
})
async function open(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/admin/institutions/:tenantId?', component: InstitutionWorkspacePage }]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(InstitutionWorkspacePage, {
    global: {
      plugins: [router],
      stubs: {
        InstitutionWorkspace: frame,
        DeploymentsPage: defineComponent({
          props: ['tenantId'],
          template: '<p data-deployment>{{tenantId}}</p>'
        }),
        FoundationPage: true,
        GovernancePage: true,
        AgentsPage: true,
        DefinitionsPage: true,
        GrantsPage: true,
        IncomingGrants: true,
        SubInstitutionsPage: true,
        UsagePage: true,
        HeadquartersUsagePage: true
      }
    }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
describe('机构工作台实际路由与身份分离', () => {
  it('无id默认本机构，选择下级只更新目标地址而不修改登录身份', async () => {
    const { wrapper, router } = await open('/admin/institutions')
    expect(wrapper.get('[data-target]').text()).toBe('10')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '选择下级')!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin/institutions/11')
    expect(fixture.auth.user.tenant_id).toBe('10')
    expect(wrapper.get('[data-actor]').text()).toContain('登录机构 10')
  })
  it('下级深链不能借用本机构凭证维护权挂载凭证面板', async () => {
    const { wrapper } = await open('/admin/institutions/11?section=access')
    expect(wrapper.text()).toContain('不能维护此机构的这个分区')
    expect(wrapper.find('governance-page-stub').exists()).toBe(false)
  })
  it('不可见目标深链不挂载任何私有业务面板', async () => {
    const { wrapper } = await open('/admin/institutions/999?section=agents&view=deployments')
    expect(wrapper.text()).toContain('不在可见或可操作目录')
    expect(wrapper.find('[data-deployment]').exists()).toBe(false)
  })
})
