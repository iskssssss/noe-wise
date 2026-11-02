import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
import CitationDetail from './CitationDetail.vue'

const mocks = vi.hoisted(() => ({ get: vi.fn(), error: vi.fn() }))
vi.mock('../api', () => ({ get: mocks.get }))
vi.mock('@skillforge/shared/feedback', () => ({ message: { error: mocks.error } }))
const auth = reactive({ identityVersion: 0 })
vi.mock('../stores/auth', () => ({ useAuthStore: () => auth }))
const selection = { session: 'session/A', message: 'message 1', citation: 'k1234567890abcdef' }
const options = {
  global: {
    stubs: {
      ElDrawer: { template: '<div><slot /><button @click="$emit(\'close\')">关闭</button></div>' },
      ElButton: { template: '<button><slot /></button>' }
    }
  }
}
const detail = (text: string) => ({ label: '知识依据', source_version: 'v1', text })
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((ok, fail) => {
    resolve = ok
    reject = fail
  })
  return { promise, resolve, reject }
}
beforeEach(() => {
  mocks.get.mockReset()
  mocks.error.mockReset()
  auth.identityVersion = 0
})
describe('知识依据详情', () => {
  it('按会话、消息和引用读取原片段', async () => {
    mocks.get.mockResolvedValue(detail('原片段'))
    const wrapper = mount(CitationDetail, { props: { selection }, ...options })
    await flushPromises()
    expect(mocks.get.mock.calls[0]![0]).toBe(
      '/chat/citations/content?session_id=session%2FA&message_id=message+1&citation_id=k1234567890abcdef'
    )
    expect(wrapper.text()).toContain('原片段')
    expect(wrapper.text()).toContain('来源版本：v1')
    wrapper.unmount()
  })
  it.each([403, 404])('拒绝 %s 不显示正文或重复错误', async (status) => {
    mocks.get.mockRejectedValue(new HttpClientError({ status, userMessage: 'private error' }))
    const wrapper = mount(CitationDetail, { props: { selection }, ...options })
    await flushPromises()
    expect(mocks.error).toHaveBeenCalledOnce()
    expect(mocks.error).toHaveBeenCalledWith('来源不可用或无权查看')
    expect(wrapper.text()).not.toContain('private error')
    expect(wrapper.text()).not.toContain('重试读取')
    wrapper.unmount()
  })
  it('切换引用时清除旧正文，迟到成功与失败不覆盖新请求', async () => {
    const first = deferred<ReturnType<typeof detail>>()
    const second = deferred<ReturnType<typeof detail>>()
    mocks.get.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const wrapper = mount(CitationDetail, { props: { selection }, ...options })
    await wrapper.setProps({ selection: { ...selection, citation: 'k0000000000000000' } })
    expect((mocks.get.mock.calls[0]![1] as { signal: AbortSignal }).signal.aborted).toBe(true)
    second.resolve(detail('新片段'))
    await flushPromises()
    first.resolve(detail('旧片段'))
    await flushPromises()
    expect(wrapper.text()).toContain('新片段')
    expect(wrapper.text()).not.toContain('旧片段')
    wrapper.unmount()
  })
  it('切换后旧请求迟到失败不会提示或清除当前片段', async () => {
    const first = deferred<ReturnType<typeof detail>>()
    mocks.get.mockReturnValueOnce(first.promise).mockResolvedValueOnce(detail('新片段'))
    const wrapper = mount(CitationDetail, { props: { selection }, ...options })
    await wrapper.setProps({ selection: { ...selection, session: 'B' } })
    await flushPromises()
    first.reject(new Error('late failure'))
    await flushPromises()
    expect(wrapper.text()).toContain('新片段')
    expect(mocks.error).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('关闭或身份变化立即清除并阻断迟到响应', async () => {
    const first = deferred<ReturnType<typeof detail>>()
    mocks.get.mockReturnValue(first.promise)
    const wrapper = mount(CitationDetail, { props: { selection }, ...options })
    auth.identityVersion++
    expect(wrapper.emitted('close')).toBeTruthy()
    expect((mocks.get.mock.calls[0]![1] as { signal: AbortSignal }).signal.aborted).toBe(true)
    first.reject(new Error('late'))
    await flushPromises()
    expect(mocks.error).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('late')
    wrapper.unmount()
  })
  it('关闭时立即清除已加载的片段', async () => {
    mocks.get.mockResolvedValue(detail('已加载片段'))
    const wrapper = mount(CitationDetail, { props: { selection }, ...options })
    await flushPromises()
    await wrapper.get('button').trigger('click')
    expect(wrapper.text()).not.toContain('已加载片段')
    expect(wrapper.emitted('close')).toBeTruthy()
    wrapper.unmount()
  })
  it('网络失败可重试，关闭后重新打开重新授权读取', async () => {
    mocks.get.mockRejectedValueOnce(new Error('network')).mockResolvedValue(detail('新授权片段'))
    const wrapper = mount(CitationDetail, { props: { selection }, ...options })
    await flushPromises()
    expect(mocks.error).toHaveBeenCalledWith('读取知识依据失败，请重试')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('新授权片段')
    wrapper.unmount()
    const reopened = mount(CitationDetail, { props: { selection }, ...options })
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(3)
    reopened.unmount()
  })
})
