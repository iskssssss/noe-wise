import { clearFeedback } from '../../feedback'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElRadioGroup } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SecurityChallenge from './SecurityChallenge.vue'
import AccountRecovery from './AccountRecovery.vue'
import type { SecurityAuthApi, SecurityAuthTokens } from './api'

const tokens = { access_token: 'synthetic-test-token', mfa_state: 'full' } as SecurityAuthTokens
function clients() {
  return {
    enroll: vi
      .fn()
      .mockResolvedValue({ secret: 'one-time-secret', otpauth_uri: 'otpauth://totp/test' }),
    enable: vi.fn().mockResolvedValue({ recovery_codes: ['one-use-code'], authentication: tokens }),
    verify: vi.fn().mockResolvedValue(tokens),
    requestRecovery: vi.fn().mockResolvedValue({ status: 'accepted' }),
    completeRecovery: vi.fn().mockResolvedValue({ status: 'completed' }),
    activate: vi.fn().mockResolvedValue({ status: 'activated' }),
    status: vi.fn(),
    logout: vi.fn(),
    disable: vi.fn()
  } satisfies SecurityAuthApi
}
const mounted: VueWrapper[] = []
afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  clearFeedback()
  document.body.innerHTML = ''
})
function button(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text() === text)!
}
function challenge(api = clients(), state = 'enrollment') {
  const wrapper = mount(SecurityChallenge, {
    props: { api, state },
    global: { plugins: [ElementPlus] }
  })
  mounted.push(wrapper)
  return wrapper
}
async function recovery(api = clients(), username = 'member') {
  const wrapper = mount(AccountRecovery, {
    props: { api, username },
    global: { plugins: [ElementPlus] }
  })
  mounted.push(wrapper)
  await wrapper.get('.el-collapse-item__header').trigger('click')
  return wrapper
}
describe('MFA 和邮件凭据的分步交付', () => {
  it('绑定按创建、验证、保存恢复码推进，不自动登录或重复发起', async () => {
    const api = clients()
    const wrapper = challenge(api)
    expect(wrapper.text()).toContain('完成验证码验证后才会绑定账号')
    expect(wrapper.find('form').exists()).toBe(false)
    await button(wrapper, '创建验证器密钥').trigger('click')
    await flushPromises()
    expect(api.enroll).toHaveBeenCalledTimes(1)
    expect(wrapper.get('input[readonly]').element).toHaveProperty('value', 'one-time-secret')
    await wrapper.get('input[autocomplete="one-time-code"]').setValue('123456')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.enable).toHaveBeenCalledExactlyOnceWith('123456')
    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.text()).toContain('one-use-code')
    expect(wrapper.emitted('complete')).toBeUndefined()
    await button(wrapper, '已保存，继续登录').trigger('click')
    expect(wrapper.emitted('complete')).toEqual([[tokens]])
    expect(wrapper.text()).not.toContain('one-use-code')
  })
  it('主体变化时废弃迟到密钥，不覆盖新的验证步骤', async () => {
    let resolve!: (value: { secret: string; otpauth_uri: string }) => void
    const api = clients()
    api.enroll.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      })
    )
    const wrapper = challenge(api)
    await button(wrapper, '创建验证器密钥').trigger('click')
    await wrapper.setProps({ api: clients(), state: 'challenge' })
    resolve({ secret: 'stale-secret', otpauth_uri: 'otpauth://totp/stale' })
    await flushPromises()
    expect(wrapper.text()).not.toContain('stale-secret')
    expect(wrapper.find('input[readonly]').exists()).toBe(false)
    expect(wrapper.find('form').exists()).toBe(true)
  })
  it('卸载后返回的验证凭据不交付给宿主', async () => {
    let resolve!: (value: SecurityAuthTokens) => void
    const api = clients()
    api.verify.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      })
    )
    const wrapper = challenge(api, 'challenge')
    await wrapper.get('input').setValue('recovery-code')
    await wrapper.get('form').trigger('submit')
    wrapper.unmount()
    resolve(tokens)
    await flushPromises()
    expect(wrapper.emitted('complete')).toBeUndefined()
  })
  it('恢复邮件返回统一提示后才展开凭据，换账号清除秘密', async () => {
    const api = clients()
    const wrapper = await recovery(api)
    expect(wrapper.find('form').exists()).toBe(false)
    await button(wrapper, '发送密码恢复邮件').trigger('click')
    await flushPromises()
    expect(api.requestRecovery).toHaveBeenCalledExactlyOnceWith({
      username: 'member',
      tenant_slug: undefined
    })
    expect(document.querySelector('.el-message')?.textContent).toContain('如账号和邮箱有效')
    await wrapper.get('input[autocomplete="off"]').setValue('credential')
    await wrapper.get('input[autocomplete="new-password"]').setValue('Password123456')
    await wrapper.setProps({ username: 'new-account' })
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    expect(document.querySelector('.el-message')?.textContent ?? '').not.toContain(
      '如账号和邮箱有效'
    )
    expect(api.completeRecovery).not.toHaveBeenCalled()
  })
  it('机构账号恢复须填写机构标识和用户名，平台恢复不需要机构标识', async () => {
    const api = clients()
    const wrapper = await recovery(api)
    await wrapper.setProps({ tenantSlug: '' })
    expect(wrapper.text()).toContain('填写上方机构标识和用户名')
    expect(button(wrapper, '发送密码恢复邮件').attributes('disabled')).toBeDefined()
    expect(api.requestRecovery).not.toHaveBeenCalled()
    await wrapper.setProps({ tenantSlug: 'institution' })
    expect(button(wrapper, '发送密码恢复邮件').attributes('disabled')).toBeUndefined()
    await wrapper.setProps({ tenantSlug: undefined })
    expect(wrapper.text()).toContain('填写上方用户名')
    expect(button(wrapper, '发送密码恢复邮件').attributes('disabled')).toBeUndefined()
  })
  it('邀请可直接消费邮件凭据，失败保留当前输入且不会调用恢复接口', async () => {
    const api = clients()
    api.activate.mockRejectedValueOnce(new Error('凭据已过期'))
    const wrapper = await recovery(api, '')
    wrapper.getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'invitation')
    await flushPromises()
    await wrapper.get('input[autocomplete="off"]').setValue('invitation-credential')
    await wrapper.get('input[autocomplete="new-password"]').setValue('Password123456')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.activate).toHaveBeenCalledExactlyOnceWith({
      token: 'invitation-credential',
      new_password: 'Password123456'
    })
    expect(api.completeRecovery).not.toHaveBeenCalled()
    expect(document.querySelector('.el-message')?.textContent).toContain('凭据已过期')
    expect(wrapper.get('input[autocomplete="off"]').element).toHaveProperty(
      'value',
      'invitation-credential'
    )
    wrapper.getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'recovery')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
  })
})
