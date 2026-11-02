import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from './App.vue'
import LoginPage from './views/LoginPage.vue'
import { useAuthStore } from './stores/auth'
import { clearTokens, getAccessToken } from './api'
import { clearFeedback } from '@skillforge/shared/feedback'

let wrapper: VueWrapper | undefined
let auth: ReturnType<typeof useAuthStore> | undefined
afterEach(() => {
  wrapper?.unmount()
  auth?.$dispose()
  clearTokens()
  clearFeedback()
  localStorage.clear()
  vi.unstubAllGlobals()
})
async function setup(state: 'full' | 'challenge' | 'enrollment', loginRejected = false) {
  const server: {
    state: typeof state
    profileFails: boolean
    verifyRejected: boolean
    profileWait?: Promise<void>
  } = { state, profileFails: false, verifyRejected: false }
  function user() {
    return {
      sub: '42',
      tenant_id: '7',
      username: 'member',
      roles: [],
      permissions: [],
      authorization_version: 1,
      mfa_state: server.state,
      session_id: 'review-session'
    }
  }
  function tokens() {
    return {
      ...user(),
      access_token: 'test-access-' + server.state,
      refresh_token: 'test-refresh',
      token_type: 'Bearer',
      expires_in: 3600
    }
  }
  const fetcher = vi.fn(async (input: string) => {
    let status = 200
    let data: unknown
    if (input.endsWith('/login')) {
      status = loginRejected ? 401 : 200
      data = tokens()
    } else if (input.endsWith('/me')) {
      await server.profileWait
      status = server.profileFails ? 503 : 200
      data = user()
    } else if (input.endsWith('/mfa/enroll'))
      data = { secret: 'test-enrollment-secret', otpauth_uri: 'otpauth://totp/test' }
    else if (input.endsWith('/mfa/verify') || input.endsWith('/mfa/enable')) {
      status = server.verifyRejected ? 401 : 200
      if (!server.verifyRejected) server.state = 'full'
      data = input.endsWith('/enable')
        ? { recovery_codes: ['one-time-recovery-code'], authentication: tokens() }
        : tokens()
    } else if (input.endsWith('/logout')) data = { status: 'ok' }
    else throw Error('Unexpected request: ' + input)
    return new Response(
      JSON.stringify({
        code: status === 200 ? 'OK' : 'REJECTED',
        message: 'safe diagnostic',
        trace_id: 'mfa-regression',
        data
      }),
      { status }
    )
  })
  vi.stubGlobal('fetch', fetcher)
  const pinia = createPinia()
  auth = useAuthStore(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginPage },
      {
        path: '/admin/home',
        component: { template: '<p data-test="home">首页</p>' },
        meta: { requiresAuth: true }
      }
    ]
  })
  await router.push('/login?redirect=/admin/home')
  wrapper = mount(App, { attachTo: document.body, global: { plugins: [pinia, router] } })
  await flushPromises()
  await wrapper.get('input[autocomplete="organization"]').setValue('tenant')
  await wrapper.get('input[autocomplete="username"]').setValue('member')
  await wrapper.get('input[autocomplete="current-password"]').setValue('Password123456')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  return { router, server, fetcher }
}
function action(text: string) {
  return wrapper!.findAll('button').find((button) => button.text() === text)!
}
describe('真实 App 身份重建后的登录流程', () => {
  it.each(['challenge', 'enrollment'] as const)(
    '%s 随认证状态恢复，不丢失验证阶段',
    async (state) => {
      await setup(state)
      expect(auth!.identityVersion).toBeGreaterThan(0)
      expect(auth!.user?.mfa_state).toBe(state)
      expect(wrapper!.get('section[aria-label="二次验证"]').text()).toContain(
        state === 'challenge' ? '验证码或恢复码' : '创建密钥'
      )
      expect(wrapper!.find('input[autocomplete="current-password"]').exists()).toBe(false)
    }
  )
  it('验证码通过后进入原站内目标，不再次提交密码登录', async () => {
    const { router, fetcher } = await setup('challenge')
    await wrapper!.get('input[autocomplete="one-time-code"]').setValue('123456')
    await wrapper!.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin/home')
    expect(auth!.isAuthenticated).toBe(true)
    expect(fetcher.mock.calls.filter(([path]) => path.endsWith('/login'))).toHaveLength(1)
    expect(fetcher.mock.calls.filter(([path]) => path.endsWith('/mfa/verify'))).toHaveLength(1)
  })
  it('新绑定先交付恢复码，确认保存后才进入后台', async () => {
    const { router } = await setup('enrollment')
    await action('创建验证器密钥').trigger('click')
    await flushPromises()
    await wrapper!.get('input[autocomplete="one-time-code"]').setValue('123456')
    await wrapper!.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(wrapper!.text()).toContain('one-time-recovery-code')
    await action('已保存，继续登录').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin/home')
    expect(wrapper!.text()).not.toContain('one-time-recovery-code')
  })
  it('验证码拒绝保留输入与当前阶段，只出现一条顶部消息', async () => {
    const { server } = await setup('challenge')
    server.verifyRejected = true
    await wrapper!.get('input[autocomplete="one-time-code"]').setValue('654321')
    await wrapper!.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper!.get('input[autocomplete="one-time-code"]').element).toHaveProperty(
      'value',
      '654321'
    )
    expect(auth!.user?.mfa_state).toBe('challenge')
    expect(document.querySelectorAll('.el-message')).toHaveLength(1)
    expect(document.querySelector('.el-message')?.textContent).toContain('验证码或恢复码无效')
  })
  it('MFA 已通过但资料读取失败时，重建后的页面仍能看到真实结果', async () => {
    const { server, router } = await setup('challenge')
    server.profileFails = true
    await wrapper!.get('input[autocomplete="one-time-code"]').setValue('123456')
    await wrapper!.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(document.querySelector('.el-message')?.textContent).toContain('二次验证已通过')
  })
  it.each([false, true])('旧MFA资料%s失败结果迟到时不污染另一登录会话', async (failure) => {
    const { server } = await setup('challenge')
    server.profileFails = failure
    let finish!: () => void
    server.profileWait = new Promise<void>((resolve) => {
      finish = resolve
    })
    await wrapper!.get('input[autocomplete="one-time-code"]').setValue('123456')
    await wrapper!.get('form').trigger('submit')
    await flushPromises()
    auth!.applyTokens({
      access_token: 'other-account-token',
      refresh_token: 'other-refresh',
      token_type: 'Bearer',
      expires_in: 3600,
      username: 'other-account',
      roles: [],
      permissions: [],
      authorization_version: 1,
      mfa_state: 'full',
      session_id: 'other-session'
    })
    finish()
    await flushPromises()
    expect(getAccessToken()).toBe('other-account-token')
    expect(document.querySelectorAll('.el-message')).toHaveLength(0)
  })
  it('密码拒绝保持登录表单及密码，不产生已认证状态', async () => {
    await setup('full', true)
    expect(auth!.isAuthenticated).toBe(false)
    expect(wrapper!.get('input[autocomplete="current-password"]').element).toHaveProperty(
      'value',
      'Password123456'
    )
    expect(document.querySelector('.el-message')?.textContent).toContain('账号或密码不正确')
  })
  it('无需MFA的完整会话正常进入后台', async () => {
    const { router } = await setup('full')
    expect(router.currentRoute.value.path).toBe('/admin/home')
    expect(auth!.isAuthenticated).toBe(true)
  })
})
