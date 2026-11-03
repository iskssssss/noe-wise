import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElDrawer, ElInput, ElSelect } from 'element-plus'
import SkillWorkbenchListPage from './SkillWorkbenchListPage.vue'
import ToolDeploymentsPage from './ToolDeploymentsPage.vue'
import { EntitySelect } from '@skillforge/shared/admin'
import { ref } from 'vue'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
const mocks = vi.hoisted(() => ({
  definitions: vi.fn(),
  createSkill: vi.fn(),
  deployments: vi.fn(),
  versions: vi.fn(),
  platformTools: vi.fn(),
  instances: vi.fn(),
  createDeployment: vi.fn(),
  push: vi.fn()
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    user: {
      tenant_id: '7',
      sub: '8',
      permissions: ['foundation.capabilities.read', 'foundation.capabilities.create']
    },
    identityVersion: 1
  })
}))
vi.mock('../../api', () => ({
  getSessionId: () => 'test-session',
  api: {
    capabilities: {
      definitions: mocks.definitions,
      deployments: mocks.deployments,
      versions: mocks.versions,
      platformTools: mocks.platformTools,
      createDeployment: mocks.createDeployment,
      createDeploymentWithReceipt: async (
        requestId: string,
        payload: { deploymentKey: string }
      ) => {
        await mocks.createDeployment(payload)
        return {
          status: 'SUCCEEDED',
          request_id: requestId,
          deployment_id: '123',
          deployment_key: payload.deploymentKey
        }
      },
      deploymentCreateReceipt: vi.fn()
    },
    connectors: { instances: mocks.instances },
    skillDrafts: {
      createWithReceipt: async (requestId: string, payload: unknown) => {
        const result = await mocks.createSkill(payload)
        return {
          request_id: requestId,
          definition_id: result.definitionId,
          draft_id: result.id,
          revision: result.revision
        }
      },
      creationReceipt: vi.fn()
    }
  }
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
const wrappers: VueWrapper[] = []
function button(wrapper: VueWrapper, text: string) {
  return wrapper.findAllComponents(ElButton).find((item) => item.text() === text)!
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.definitions.mockResolvedValue([{ id: '10', name: '查询工具' }])
  mocks.deployments.mockResolvedValue([])
  mocks.versions.mockResolvedValue([{ id: '20', name: '查询工具', versionKey: '1.0' }])
  mocks.platformTools.mockResolvedValue([{ name: 'search', description: '搜索' }])
  mocks.instances.mockResolvedValue({
    items: [{ instance_id: 'mcp-1', name: '客户系统', executor_type: 'mcp' }]
  })
  mocks.createSkill.mockResolvedValue({ id: '20', definitionId: '10', revision: 1 })
  mocks.createDeployment.mockResolvedValue({
    deploymentKey: 'customer-search',
    toolVersionId: '20',
    providerType: 'PLATFORM',
    providerRef: 'search',
    environment: 'DEVELOPMENT',
    status: 'ACTIVE',
    revision: 1
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})
describe('关联表单校验', () => {
  it('技能空目录提供真实目录与创建入口，筛选和读取失败不冒充首次空态', async () => {
    mocks.definitions.mockResolvedValue([])
    const wrapper = mount(SkillWorkbenchListPage, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('新建技能后在工作台导入 ZIP 包')
    expect(
      wrapper.findAll('a').some((link) => link.attributes('to') === '/admin/capabilities')
    ).toBe(true)
    expect(wrapper.text()).toContain('查看已发布技能')
    expect(wrapper.text()).not.toContain('技能市场')
    await wrapper.findComponent(ElInput).find('input').setValue('不存在')
    expect(wrapper.text()).toContain('没有匹配的技能')
    expect(wrapper.text()).not.toContain('查看已发布技能')
    await button(wrapper, '清空筛选').trigger('click')
    mocks.definitions.mockRejectedValueOnce(new Error('技能读取失败'))
    await button(wrapper, '刷新').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('技能目录读取失败')
    expect(wrapper.text()).not.toContain('查看已发布技能')
    expect(button(wrapper, '重新加载').exists()).toBe(true)
  })
  it('无创建和目录读取权限时不显示技能空态操作', async () => {
    mocks.definitions.mockResolvedValue([])
    const wrapper = mount(SkillWorkbenchListPage, {
      global: {
        directives: {
          permission: createPermissionDirective(() => ref(['foundation.skills.create']))
        },
        stubs: { RouterLink: { template: '<a><slot /></a>' } }
      }
    })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.findAll('a').some((link) => link.text() === '查看已发布技能')).toBe(false)
    expect(wrapper.findAll('button').some((item) => item.text() === '新建技能')).toBe(false)
  })
  it('技能名称为空时不提交，填写名称即可创建', async () => {
    const wrapper = mount(SkillWorkbenchListPage, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } }
    })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '新建技能').trigger('click')
    await flushPromises()
    const drawer = wrapper.findComponent(ElDrawer)
    await drawer.findAllComponents(ElInput)[0]!.find('input').setValue('')
    await button(wrapper, '创建并进入工作草稿').trigger('click')
    await flushPromises()
    await vi.waitFor(() => expect(document.body.textContent).toContain('请输入名称'))
    expect(mocks.createSkill).not.toHaveBeenCalled()
    const inputs = drawer.findAllComponents(ElInput)
    await inputs[0]!.find('input').setValue('客户服务')
    await button(wrapper, '创建并进入工作草稿').trigger('click')
    await flushPromises()
    expect(mocks.createSkill).toHaveBeenCalledExactlyOnceWith({
      name: '客户服务',
      description: ''
    })
    expect(mocks.push).toHaveBeenCalledWith('/admin/skills/10')
  })
  it('切换工具提供方清除旧实例且只能提交目录内的引用', async () => {
    const wrapper = mount(ToolDeploymentsPage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '新增部署').trigger('click')
    await flushPromises()
    const drawer = wrapper.findComponent(ElDrawer)
    const selects = drawer.findAllComponents(ElSelect)
    selects[0]!.vm.$emit('update:modelValue', '20')
    const provider = selects[1]!
    const reference = drawer.findComponent(EntitySelect)
    reference.vm.$emit('update:modelValue', 'mcp-1')
    await flushPromises()
    provider.vm.$emit('update:modelValue', 'PLATFORM')
    await flushPromises()
    expect(reference.props('modelValue')).toBe('')
    expect(reference.props('options')).toEqual([
      { id: 'search', label: 'search', description: '搜索' }
    ])
    await button(wrapper, '创建').trigger('click')
    await flushPromises()
    expect(mocks.createDeployment).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(document.body.textContent).toContain('请选择提供方引用'))
    reference.vm.$emit('update:modelValue', 'search')
    await flushPromises()
    await button(wrapper, '创建').trigger('click')
    await flushPromises()
    expect(mocks.createDeployment).toHaveBeenCalledWith(
      expect.objectContaining({
        providerType: 'PLATFORM',
        providerRef: 'search',
        toolVersionId: '20'
      })
    )
  })
})
