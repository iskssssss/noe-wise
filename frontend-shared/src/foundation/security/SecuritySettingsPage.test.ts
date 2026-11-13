import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { clearFeedback, confirm } from '../../feedback'
import SecuritySettingsPage from './SecuritySettingsPage.vue'
import FoundationOptionSelect from '../FoundationOptionSelect.vue'
import type { SecurityApi, SecurityAuthApi, SecurityStatus } from './api'

vi.mock('../../feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../feedback')>()),
  confirm: vi.fn(),
  message: { warning: vi.fn() }
}))

const fullStatus: SecurityStatus = {
  enabled: false,
  required: false,
  mfa_state: 'full',
  recovery_codes_remaining: 0
}
const sessions = [
  {
    id: '9007199254740993',
    device_label:
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36',
    created_at: '2026-10-11T10:03:04.123456Z',
    expires_at: '2026-10-12T10:03:04Z',
    current: true
  },
  {
    id: '9007199254740994',
    device_label: '另一浏览器',
    created_at: '2026-10-10T18:03:04+08:00',
    expires_at: '2026-10-12T10:03:04Z',
    current: false
  }
]
const permissions = [
  'foundation.security.policy.manage',
  'foundation.security.invitation.manage',
  'foundation.security.session.manage',
  'foundation.security.mfa.manage'
]

function clients() {
  const api = {
    status: vi.fn().mockResolvedValue(fullStatus),
    sessions: vi.fn().mockResolvedValue(sessions),
    policy: vi.fn().mockResolvedValue({ require_all_mfa: false }),
    setPolicy: vi.fn().mockResolvedValue({ require_all_mfa: true }),
    revoke: vi.fn().mockResolvedValue({ status: 'revoked' }),
    revokeMember: vi.fn().mockResolvedValue({ status: 'revoked' }),
    resetMemberMfa: vi.fn().mockResolvedValue({ status: 'reset' }),
    invite: vi.fn().mockResolvedValue({ status: 'accepted' }),
    regenerateCodes: vi.fn().mockResolvedValue(['new-recovery-code'])
  } satisfies SecurityApi
  const authApi = {
    status: vi.fn().mockResolvedValue(fullStatus),
    logout: vi.fn().mockResolvedValue({ status: 'logged_out' }),
    enroll: vi.fn(),
    enable: vi.fn(),
    verify: vi.fn(),
    requestRecovery: vi.fn(),
    completeRecovery: vi.fn(),
    disable: vi.fn().mockResolvedValue({ status: 'disabled' }),
    activate: vi.fn()
  } satisfies SecurityAuthApi
  const request = vi.fn().mockResolvedValue({
    items: [
      { id: '9007199254740999', name: '待邀请成员', code: 'pending-member', status: 'pending' }
    ],
    has_more: false
  })
  return { api, authApi, request, onLogout: vi.fn(), onAuthentication: vi.fn() }
}

const mounted: VueWrapper[] = []
async function mountPage(client = clients(), allowed = permissions, realChallenge = false) {
  const route = defineComponent({
    setup: () => () => h(SecuritySettingsPage, { ...client, permissions: allowed })
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/security', component: route },
      { path: '/other', component: { template: '<p>其他页面</p>' } }
    ]
  })
  await router.push('/security')
  await router.isReady()
  const wrapper = mount(
    { setup: () => () => h(RouterView) },
    {
      attachTo: document.body,
      global: {
        plugins: [router, ElementPlus],
        stubs: realChallenge ? {} : { SecurityChallenge: { template: '<p>可自愿绑定验证器</p>' } }
      }
    }
  )
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router, ...client }
}
function button(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text() === text)!
}
function writes(client: ReturnType<typeof clients>) {
  return [
    client.api.setPolicy,
    client.api.revoke,
    client.api.revokeMember,
    client.api.resetMemberMfa,
    client.api.invite,
    client.api.regenerateCodes,
    client.authApi.enroll,
    client.authApi.enable,
    client.authApi.verify,
    client.authApi.disable
  ]
}

describe('账号安全页确认、草稿与读取状态', () => {
  it('真实绑定验证码和策略复选框关联唯一可见帮助且不自动派发写入', async () => {
    const client = clients()
    client.authApi.enroll.mockResolvedValue({
      secret: 'one-time-secret',
      otpauth_uri: 'otpauth://totp/noewise'
    })
    const page = await mountPage(client, permissions, true)
    expect(page.wrapper.find('section[aria-label="二次验证"] input').exists()).toBe(false)
    for (const write of writes(client)) expect(write).not.toHaveBeenCalled()
    await button(page.wrapper, '创建验证器密钥').trigger('click')
    await flushPromises()
    for (const selector of ['section[aria-label="二次验证"] input', 'input[type="checkbox"]']) {
      const control = page.wrapper.get(selector)
      const ids = control.attributes('aria-describedby')?.split(/\s+/).filter(Boolean) ?? []
      expect(ids.length).toBeGreaterThan(0)
      for (const id of ids) {
        const targets = page.wrapper.findAll(`[id="${id}"]`)
        expect(targets).toHaveLength(1)
        expect(targets[0]!.text().trim()).not.toBe('')
        expect(targets[0]!.isVisible()).toBe(true)
      }
    }
    expect(client.authApi.enroll).toHaveBeenCalledTimes(1)
    expect(client.authApi.enable).not.toHaveBeenCalled()
  })

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(confirm).mockResolvedValue(false)
  })
  afterEach(() => {
    clearFeedback()
    mounted.splice(0).forEach((wrapper) => wrapper.unmount())
    document.body.innerHTML = ''
  })

  it('默认关闭时只读取状态，设备标识和北京时间来自服务器', async () => {
    const page = await mountPage()
    expect(page.wrapper.text()).toContain('验证器：未绑定；可自愿启用；剩余恢复码 0 个')
    expect(page.wrapper.text()).toContain('已保存策略：已关闭全员验证器要求')
    expect(button(page.wrapper, '保存策略').attributes('disabled')).toBeDefined()
    const rows = page.wrapper.findAll('.el-table__row')
    expect(rows[0]!.text()).toContain('当前设备')
    expect(rows[1]!.text()).toContain('其他设备')
    expect(rows[0]!.text()).toContain('Windows · Chrome')
    expect(rows[0]!.text()).toContain('2026-10-11 18:03:04')
    const titledSpans = rows[0]!.findAll('span[title]')
    expect(titledSpans.some((span) => span.attributes('title') === sessions[0]!.created_at)).toBe(
      true
    )
    expect(titledSpans.some((span) => span.attributes('title') === sessions[0]!.device_label)).toBe(
      true
    )
    writes(page).forEach((write) => expect(write).not.toHaveBeenCalled())
  })

  it('无政策管理权限不加载或展示机构策略', async () => {
    const page = await mountPage(clients(), [])
    expect(page.api.policy).not.toHaveBeenCalled()
    expect(page.wrapper.text()).not.toContain('机构安全策略')
    expect(page.wrapper.text()).not.toContain('成员安全管理')
  })

  it('取消设备、成员和策略确认不会发送任何写请求', async () => {
    const page = await mountPage()
    await button(page.wrapper, '撤销').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenLastCalledWith(
      expect.objectContaining({
        title: '撤销登录设备',
        content: expect.stringContaining('这是当前设备')
      })
    )
    page.wrapper
      .getComponent(FoundationOptionSelect)
      .vm.$emit('update:modelValue', '9007199254740999')
    page.wrapper.getComponent(FoundationOptionSelect).vm.$emit('selected', {
      id: '9007199254740999',
      name: '待邀请成员',
      code: 'pending-member',
      status: 'pending'
    })
    await flushPromises()
    for (const text of ['发送登记邮箱邀请', '撤销成员全部设备', '重置遗失验证器']) {
      await button(page.wrapper, text).trigger('click')
      await flushPromises()
      expect(confirm).toHaveBeenLastCalledWith(
        expect.objectContaining({
          content: expect.stringContaining('9007199254740999')
        })
      )
    }
    await page.wrapper.get('input[type="checkbox"]').setValue(true)
    await button(page.wrapper, '保存策略').trigger('click')
    await flushPromises()
    writes(page).forEach((write) => expect(write).not.toHaveBeenCalled())
    expect(page.onLogout).not.toHaveBeenCalled()
    expect(page.wrapper.text()).toContain('有未保存修改')
  })

  it('本地政策草稿可取消，取消导航保留草稿，确认丢弃才离开', async () => {
    const page = await mountPage()
    await page.wrapper.get('input[type="checkbox"]').setValue(true)
    expect(page.wrapper.text()).toContain('有未保存修改')
    await button(page.wrapper, '取消修改').trigger('click')
    expect((page.wrapper.get('input[type="checkbox"]').element as HTMLInputElement).checked).toBe(
      false
    )
    expect(page.wrapper.text()).not.toContain('有未保存修改')
    await page.wrapper.get('input[type="checkbox"]').setValue(true)
    await page.router.push('/other')
    expect(page.router.currentRoute.value.path).toBe('/security')
    expect(page.wrapper.text()).toContain('有未保存修改')
    expect(confirm).toHaveBeenLastCalledWith(expect.objectContaining({ title: '离开页面' }))
    vi.mocked(confirm).mockResolvedValueOnce(true)
    await page.router.push('/other')
    expect(page.router.currentRoute.value.path).toBe('/other')
    expect(page.api.setPolicy).not.toHaveBeenCalled()
  })

  it('设备和策略初读失败保持未读状态，刷新后才能显示真实空列表和策略', async () => {
    const client = clients()
    client.api.sessions.mockRejectedValueOnce(new Error('设备查询暂不可用')).mockResolvedValue([])
    client.api.policy.mockRejectedValueOnce(new Error('策略查询暂不可用'))
    const page = await mountPage(client)
    expect(document.body.textContent).toContain('设备查询暂不可用')
    expect(document.body.textContent).toContain('策略查询暂不可用')
    expect(page.wrapper.text()).not.toContain('暂无登录设备')
    expect(page.wrapper.find('input[type="checkbox"]').exists()).toBe(false)
    await button(page.wrapper, '刷新安全状态').trigger('click')
    await flushPromises()
    expect(page.wrapper.text()).toContain('暂无登录设备')
    expect(page.wrapper.text()).toContain('已保存策略：已关闭全员验证器要求')
    expect(
      document.querySelectorAll('.el-message').length
        ? [...document.querySelectorAll('.el-message')].map((item) => item.textContent).join('')
        : ''
    ).not.toContain('查询暂不可用')
    writes(page).forEach((write) => expect(write).not.toHaveBeenCalled())
  })

  it('安全状态初读失败不查询管理资源，允许通过刷新恢复', async () => {
    const client = clients()
    client.authApi.status.mockRejectedValueOnce(new Error('安全状态暂不可用'))
    const page = await mountPage(client)
    expect(document.body.textContent).toContain('安全状态暂不可用')
    expect(page.api.sessions).not.toHaveBeenCalled()
    expect(page.api.policy).not.toHaveBeenCalled()
    await button(page.wrapper, '刷新安全状态').trigger('click')
    await flushPromises()
    expect(page.wrapper.text()).toContain('当前设备')
    expect(
      document.querySelectorAll('.el-message').length
        ? [...document.querySelectorAll('.el-message')].map((item) => item.textContent).join('')
        : ''
    ).not.toContain('安全状态暂不可用')
  })

  it('写成功后查询失败保留成功与已有数据，刷新只读不会重放撤销', async () => {
    const page = await mountPage()
    vi.mocked(confirm).mockResolvedValueOnce(true)
    page.api.sessions.mockRejectedValueOnce(new Error('设备刷新失败'))
    await page.wrapper.findAll('.el-table__row')[1]!.get('button').trigger('click')
    await flushPromises()
    expect(page.api.revoke).toHaveBeenCalledExactlyOnceWith(sessions[1]!.id)
    expect(page.onLogout).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('设备“另一浏览器”的登录授权已撤销')
    expect(document.body.textContent).toContain('设备刷新失败')
    expect(page.wrapper.text()).toContain('不要重复提交该操作')
    expect(page.wrapper.findAll('.el-table__row')).toHaveLength(2)
    await button(page.wrapper, '刷新安全状态').trigger('click')
    await flushPromises()
    expect(page.api.revoke).toHaveBeenCalledTimes(1)
    expect(
      document.querySelectorAll('.el-message').length
        ? [...document.querySelectorAll('.el-message')].map((item) => item.textContent).join('')
        : ''
    ).not.toContain('设备刷新失败')
  })

  it('撤销当前设备后退出，不再查询已失效会话的状态', async () => {
    const page = await mountPage()
    vi.mocked(confirm).mockResolvedValueOnce(true)
    await page.wrapper.findAll('.el-table__row')[0]!.get('button').trigger('click')
    await flushPromises()
    expect(page.api.revoke).toHaveBeenCalledExactlyOnceWith(sessions[0]!.id)
    expect(page.onLogout).toHaveBeenCalledTimes(1)
    expect(page.authApi.status).toHaveBeenCalledTimes(1)
    expect(page.api.sessions).toHaveBeenCalledTimes(1)
  })

  it('刷新不覆盖未保存策略，保存失败保留草稿和公开错误', async () => {
    const page = await mountPage()
    await page.wrapper.get('input[type="checkbox"]').setValue(true)
    await button(page.wrapper, '刷新安全状态').trigger('click')
    await flushPromises()
    expect((page.wrapper.get('input[type="checkbox"]').element as HTMLInputElement).checked).toBe(
      true
    )
    vi.mocked(confirm).mockResolvedValueOnce(true)
    page.api.setPolicy.mockRejectedValueOnce(new Error('保存策略被拒绝'))
    await button(page.wrapper, '保存策略').trigger('click')
    await flushPromises()
    expect(page.api.setPolicy).toHaveBeenCalledExactlyOnceWith(true)
    expect(document.body.textContent).toContain('保存策略被拒绝')
    expect(page.wrapper.text()).toContain('已保存策略：已关闭全员验证器要求；有未保存修改')
  })

  it('确认未结束时销毁页面，迟到的接受结果不会派发撤销', async () => {
    let accept!: (accepted: boolean) => void
    vi.mocked(confirm).mockReturnValueOnce(
      new Promise<boolean>((resolve) => {
        accept = resolve
      })
    )
    const page = await mountPage()
    await button(page.wrapper, '撤销').trigger('click')
    page.wrapper.unmount()
    accept(true)
    await flushPromises()
    expect(page.api.revoke).not.toHaveBeenCalled()
    expect(page.onLogout).not.toHaveBeenCalled()
  })

  it('确认期间禁用触发按钮，取消后重新启用并恢复原控件焦点且不写入', async () => {
    let cancel!: (accepted: boolean) => void
    vi.mocked(confirm).mockReturnValueOnce(
      new Promise<boolean>((resolve) => {
        cancel = resolve
      })
    )
    const page = await mountPage()
    const trigger = button(page.wrapper, '撤销')
    ;(trigger.element as HTMLButtonElement).focus()
    expect(document.activeElement).toBe(trigger.element)
    await trigger.trigger('click')
    expect(trigger.attributes('disabled')).toBeDefined()
    const dialog = document.createElement('div')
    dialog.tabIndex = -1
    document.body.append(dialog)
    dialog.focus()
    expect(document.activeElement).toBe(dialog)
    cancel(false)
    await flushPromises()
    expect(trigger.attributes('disabled')).toBeUndefined()
    expect(document.activeElement).toBe(trigger.element)
    expect(page.api.revoke).not.toHaveBeenCalled()
    expect(page.onLogout).not.toHaveBeenCalled()
  })

  it('敏感验证返回前销毁页面，迟到凭据不会覆盖宿主当前身份', async () => {
    const client = clients()
    client.authApi.status.mockResolvedValue({ ...fullStatus, enabled: true })
    let complete!: (tokens: unknown) => void
    client.authApi.verify.mockReturnValueOnce(
      new Promise((resolve) => {
        complete = resolve
      })
    )
    const page = await mountPage(client)
    await page.wrapper.get('input[autocomplete="one-time-code"]').setValue('123456')
    await page.wrapper.get('form').trigger('submit')
    expect(page.authApi.verify).toHaveBeenCalledExactlyOnceWith('123456')
    page.wrapper.unmount()
    complete({ access_token: 'stale-identity-token' })
    await flushPromises()
    expect(page.onAuthentication).not.toHaveBeenCalled()
    expect(page.authApi.status).toHaveBeenCalledTimes(1)
  })
})
