import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { ElMenu, ElSelect } from 'element-plus'
import OpenApiDocsPage from './OpenApiDocsPage.vue'

const feedback = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@skillforge/shared/feedback', () => ({ message: feedback }))
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: { props: ['modelValue'], template: '<pre>{{ modelValue }}</pre>' }
}))
const wrappers: VueWrapper[] = []
const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
  vi.clearAllMocks()
  if (clipboardDescriptor) Object.defineProperty(navigator, 'clipboard', clipboardDescriptor)
  else Reflect.deleteProperty(navigator, 'clipboard')
})

async function render(path = '/admin/api-docs') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/api-docs', component: OpenApiDocsPage },
      { path: '/admin/channels', component: { template: '<p>渠道</p>' } }
    ]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(RouterView, { global: { plugins: [router] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { router, wrapper }
}

describe('开放 API 文档导航上下文', () => {
  it('从文档离开再返回或刷新，保留搜索、方法、所选接口和合法基础地址', async () => {
    const { router, wrapper } = await render()
    await wrapper.get('input[placeholder="搜索接口名称或路径"]').setValue('/chat/sessions')
    await flushPromises()
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 'GET')
    await flushPromises()
    const item = wrapper.findAll('.el-menu-item').find((item) => item.text() === '查询会话列表')!
    await item.trigger('click')
    await wrapper.get('input[placeholder="API 基础地址"]').setValue('https://api.example.test/')
    await wrapper.get('input[placeholder="API 基础地址"]').trigger('blur')
    await flushPromises()
    const location = router.currentRoute.value.fullPath
    await router.push('/admin/channels')
    router.back()
    await flushPromises()
    expect(wrapper.get('h2').text()).toBe('查询会话列表')
    expect(
      (wrapper.get('input[placeholder="搜索接口名称或路径"]').element as HTMLInputElement).value
    ).toBe('/chat/sessions')
    expect(wrapper.getComponent(ElSelect).props('modelValue')).toBe('GET')
    expect(wrapper.findAll('pre')[0]!.text()).toContain(
      'https://api.example.test/api/v1/chat/sessions?end_user_id=123'
    )
    const refreshed = await render(location)
    expect(refreshed.wrapper.get('h2').text()).toBe('查询会话列表')
    expect(refreshed.wrapper.getComponent(ElMenu).props('defaultActive')).toBe(
      router.currentRoute.value.query.endpoint
    )
    expect(refreshed.wrapper.findAll('pre')[0]!.text()).toBe(wrapper.findAll('pre')[0]!.text())
  })

  it('凭据地址被拒绝且不写入导航，字段错误保留到地址修正', async () => {
    const { router, wrapper } = await render()
    const input = wrapper.get('input[placeholder="API 基础地址"]')
    await input.setValue('https://user:secret@api.example.test')
    await input.trigger('blur')
    await flushPromises()
    await vi.waitFor(() =>
      expect(wrapper.get('.el-form-item__error').text()).toContain('请输入有效 HTTP(S) 基础地址')
    )
    expect(router.currentRoute.value.query.base).toBeUndefined()
    expect(wrapper.findAll('pre')[0]!.text()).toBe('')
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    await input.setValue('https://api.example.test')
    await input.trigger('blur')
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find('.el-form-item__error').exists()).toBe(false))
    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
    expect(router.currentRoute.value.query.base).toBe('https://api.example.test')
  })

  it('复制失败只显示可操作的失败提示，成功复制与当前示例一致', async () => {
    const writeText = vi
      .fn()
      .mockRejectedValueOnce(new Error('clipboard unavailable'))
      .mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    const { wrapper } = await render()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(feedback.error).toHaveBeenCalledWith('复制失败，请选中调用示例后手动复制')
    expect(feedback.success).not.toHaveBeenCalled()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith(wrapper.findAll('pre')[0]!.text())
    expect(feedback.success).toHaveBeenCalledWith('调用示例已复制')
  })
})
