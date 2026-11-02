import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { computed, reactive } from 'vue'
import { ElForm, ElFormItem, ElInput, ElMessageBox, ElSelect } from 'element-plus'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import TenantSettingsPanel from './TenantSettingsPanel.vue'

const mocks = vi.hoisted(() => ({
  auth: undefined as unknown as {
    identityVersion: number
    user: { sub: string; permissions: string[] }
  },
  createMember: vi.fn(),
  updateMember: vi.fn(),
  resetPassword: vi.fn(),
  updateTenant: vi.fn(),
  suspendTenant: vi.fn(),
  activateTenant: vi.fn()
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => mocks.auth }))
vi.mock('../../api', () => ({
  api: {
    admin: {
      getTenant: async () => ({
        id: 'tenant-1',
        name: '机构甲',
        slug: 'school',
        plan_code: 'free',
        status: 'active',
        deployment_mode: 'cloud'
      }),
      plans: async () => [{ plan_code: 'free', display_name: '免费套餐' }],
      listMembers: async () => ({
        items: [
          {
            id: 'member-7',
            username: 'researcher',
            roles: ['research_manager', 'auditor'],
            email: 'old@example.test',
            status: 'active'
          }
        ],
        total: 1
      }),
      createMember: mocks.createMember,
      updateMember: mocks.updateMember,
      resetPassword: mocks.resetPassword,
      updateTenant: mocks.updateTenant,
      suspendTenant: mocks.suspendTenant,
      activateTenant: mocks.activateTenant
    }
  }
}))
let wrapper: VueWrapper
beforeEach(() => {
  vi.clearAllMocks()
  mocks.auth = reactive({
    identityVersion: 1,
    user: {
      sub: 'platform-actor',
      permissions: ['foundation.tenants.create', 'foundation.tenants.update']
    }
  })
  mocks.createMember.mockResolvedValue({})
  mocks.updateMember.mockResolvedValue({})
  mocks.resetPassword.mockResolvedValue(undefined)
  mocks.suspendTenant.mockResolvedValue({
    id: 'tenant-1',
    name: '机构甲',
    slug: 'school',
    plan_code: 'free',
    status: 'suspended',
    deployment_mode: 'cloud'
  })
})
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})
async function open() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/platform/tenants/:id', component: TenantSettingsPanel }]
  })
  await router.push('/platform/tenants/tenant-1')
  await router.isReady()
  wrapper = mount(TenantSettingsPanel, {
    props: { tenantId: 'tenant-1', embedded: true },
    attachTo: document.body,
    global: {
      plugins: [router],
      directives: {
        permission: createPermissionDirective(() => computed(() => mocks.auth.user.permissions))
      }
    }
  })
  await flushPromises()
}
async function click(label: string) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === label)!
    .trigger('click')
  await flushPromises()
}
function field(label: string) {
  return wrapper.findAllComponents(ElFormItem).find((item) => item.props('label') === label)!
}
async function input(label: string, value: string) {
  await field(label).getComponent(ElInput).get('input').setValue(value)
}
function form(id: string) {
  // 抽屉表单Teleport到body，组件实例仍属于当前页面。
  return wrapper.findAllComponents(ElForm).find((item) => item.attributes('id') === id)!
}

describe('平台机构账号动作契约', () => {
  it.each(['confirm', 'cancel', 'failure'])('未保存机构配置时%s暂停机构', async (outcome) => {
    await open()
    await input('机构名称', '机构未保存名称')
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    if (outcome === 'cancel') confirm.mockRejectedValueOnce('cancel')
    else
      confirm.mockResolvedValueOnce('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
    if (outcome === 'failure') mocks.suspendTenant.mockRejectedValueOnce(Error('暂停失败'))
    await click('暂停机构')
    expect(mocks.suspendTenant).toHaveBeenCalledTimes(outcome === 'cancel' ? 0 : 1)
    expect(mocks.updateTenant).not.toHaveBeenCalled()
    expect(field('机构名称').getComponent(ElInput).props('modelValue')).toBe('机构未保存名称')
    expect(wrapper.text()).toContain(outcome === 'confirm' ? '恢复机构' : '暂停机构')
    const leave = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leave)
    expect(leave.defaultPrevented).toBe(true)
  })
  it('编辑成员邮箱保留全部已分配的自定义角色', async () => {
    await open()
    await click('编辑')
    expect(field('角色').getComponent(ElSelect).props('modelValue')).toEqual([
      'research_manager',
      'auditor'
    ])
    expect(document.body.textContent).toContain('research_manager')
    await input('邮箱', 'new@example.test')
    await form('member-form').trigger('submit')
    await flushPromises()
    expect(mocks.updateMember).toHaveBeenCalledWith('tenant-1', 'member-7', {
      roles: ['research_manager', 'auditor'],
      email: 'new@example.test',
      status: 'active'
    })
  })
  it('密码按Unicode字符及UTF-8字节限制验证，并原样提交72字节密码', async () => {
    await open()
    await click('重置密码')
    const password = 'Aa1' + '🙂'.repeat(8)
    await input('新密码', password)
    await form('password-form').trigger('submit')
    await flushPromises()
    expect(mocks.resetPassword).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('密码至少 12 个字符')
    await input('新密码', 'Aa1' + '界'.repeat(24))
    await form('password-form').trigger('submit')
    await flushPromises()
    expect(mocks.resetPassword).not.toHaveBeenCalled()
    const exactBoundary = 'Aa1' + '界'.repeat(23)
    await input('新密码', exactBoundary)
    await form('password-form').trigger('submit')
    await flushPromises()
    expect(mocks.resetPassword).toHaveBeenCalledWith('tenant-1', 'member-7', exactBoundary)
  })
  it('只读机构页面直接提交配置表单也不调用更新', async () => {
    mocks.auth.user.permissions = ['foundation.tenants.read']
    await open()
    expect(
      wrapper
        .findAll('button')
        .some((button) => button.text() === '新增成员' || button.text() === '编辑')
    ).toBe(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.updateTenant).not.toHaveBeenCalled()
  })
  it('成员编辑期间撤权清除密码且旧表单提交不写入', async () => {
    await open()
    await click('新增成员')
    await input('账号', 'new-member')
    await input('初始密码', 'ValidPassword123')
    const previousForm = form('member-form')
    mocks.auth.user.permissions = ['foundation.tenants.read']
    await flushPromises()
    const passwordInput = field('初始密码').getComponent(ElInput)
    expect(passwordInput.props('modelValue')).toBe('')
    await previousForm.trigger('submit')
    await flushPromises()
    expect(mocks.createMember).not.toHaveBeenCalled()
  })
})
