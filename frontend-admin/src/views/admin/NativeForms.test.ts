import type { Component } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElInput, ElButton, ElForm, ElInputNumber, ElCollapseItem } from 'element-plus'
import ChannelsPage from './ChannelsPage.vue'
import KnowledgePage from './KnowledgePage.vue'
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    user: {
      tenant_id: '7',
      sub: '8',
      permissions: ['foundation.knowledge.create', 'foundation.knowledge.update']
    }
  })
}))
const mocks = vi.hoisted(() => ({
  listChannels: vi.fn(),
  createChannel: vi.fn(),
  listBases: vi.fn(),
  createBase: vi.fn(),
  updateBase: vi.fn(),
  confirm: vi.fn(),
  removeChannel: vi.fn(),
  success: vi.fn()
}))
vi.mock('../../api', () => ({
  api: {
    channels: {
      list: mocks.listChannels,
      onboard: async (requestId: string, data: unknown) => ({
        request_id: requestId,
        channel_id: '1',
        channel: await mocks.createChannel(data)
      }),
      remove: mocks.removeChannel
    },
    knowledge: {
      listBases: mocks.listBases,
      createBase: mocks.createBase,
      createWithReceipt: async (requestId: string, payload: unknown) => {
        const result = await mocks.createBase(payload)
        return { request_id: requestId, kb_id: result?.id }
      },
      updateBase: mocks.updateBase
    }
  }
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm,
  message: { success: mocks.success }
}))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
  useRouter: () => ({ push: vi.fn() })
}))
const wrappers: VueWrapper[] = []
function render(component: Component) {
  const wrapper = mount(component, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } }
  })
  wrappers.push(wrapper)
  return wrapper
}
function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAllComponents(ElButton).find((b) => b.text() === label)
  if (!found) throw Error('未找到按钮 ' + label)
  return found
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.confirm.mockResolvedValue(false)
  mocks.listChannels.mockResolvedValue({ total: 0, items: [] })
  mocks.listBases.mockResolvedValue({ total: 0, items: [] })
  mocks.createChannel.mockResolvedValue({
    id: '1',
    name: '网页客服',
    type: 'web_widget',
    status: 'active',
    channel_token: 'test-token'
  })
  mocks.createBase.mockResolvedValue({ id: 'kb-1', name: '帮助' })
  mocks.updateBase.mockResolvedValue({ id: 'kb-1', name: '帮助' })
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  document.body.innerHTML = ''
})
describe('直接 Element Plus 表单交互', () => {
  it('渠道新增第一次点击立即呈现表单，并只提交一次', async () => {
    const wrapper = render(ChannelsPage)
    await flushPromises()
    await button(wrapper, '新增渠道').trigger('click')
    await flushPromises()
    const name = wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('modelValue') === '')
    const drawer = wrapper.findComponent({ name: 'ElDrawer' })
    expect(drawer.props('modelValue')).toBe(true)
    const fields = drawer.findAllComponents(ElInput)
    expect(fields.length).toBeGreaterThan(0)
    await fields[0]!.find('input').setValue('网页客服')
    await button(wrapper, '保存').trigger('click')
    await flushPromises()
    expect(mocks.createChannel).toHaveBeenCalledTimes(1)
    expect(mocks.createChannel).toHaveBeenCalledWith(
      expect.objectContaining({ name: '网页客服', channel_type: 'web_widget' })
    )
    expect(drawer.props('modelValue')).toBe(false)
    void name
  })
  it('创建知识库时一次提交分块配置', async () => {
    const wrapper = render(KnowledgePage)
    await flushPromises()
    await button(wrapper, '新建知识库').trigger('click')
    await flushPromises()
    const drawer = wrapper.findComponent({ name: 'ElDrawer' })
    await drawer.findAllComponents(ElInput)[0]!.find('input').setValue('帮助')
    await drawer.findComponent(ElCollapseItem).get('.el-collapse-item__header').trigger('click')
    await drawer.findAllComponents(ElInputNumber)[0]!.get('input').setValue('640')
    await button(wrapper, '保存').trigger('click')
    await flushPromises()
    expect(mocks.createBase).toHaveBeenCalledTimes(1)
    expect(mocks.createBase).toHaveBeenCalledWith(
      expect.objectContaining({ name: '帮助', chunk_size: 640 })
    )
    expect(mocks.updateBase).not.toHaveBeenCalled()
    expect(drawer.props('modelValue')).toBe(false)
  })
  it('编辑抽屉挂到body且提交中取消无效、重复保存不重复写入', async () => {
    let finish!: (value: unknown) => void
    mocks.createChannel.mockReturnValue(
      new Promise((resolve) => {
        finish = resolve
      })
    )
    const wrapper = render(ChannelsPage)
    await flushPromises()
    await button(wrapper, '新增渠道').trigger('click')
    await flushPromises()
    const drawer = wrapper.findComponent({ name: 'ElDrawer' })
    expect(drawer.props('appendToBody')).toBe(true)
    expect(drawer.props('direction')).toBe('rtl')
    expect(drawer.props('closeOnClickModal')).toBe(false)
    await drawer.findAllComponents(ElInput)[0]!.find('input').setValue('网页客服')
    await button(wrapper, '保存').trigger('click')
    await flushPromises()
    expect(drawer.findComponent(ElForm).props('disabled')).toBe(true)
    await button(wrapper, '保存').trigger('click')
    await button(wrapper, '取消').trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(true)
    expect(mocks.createChannel).toHaveBeenCalledTimes(1)
    finish({ id: '1', channel_token: 'test-token' })
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(false)
    expect(mocks.success).toHaveBeenCalledWith('渠道已创建，请保存首次令牌')
  })
  it('拒绝丢弃修改保留输入，确认删除取消不发送请求', async () => {
    mocks.listChannels.mockResolvedValue({
      total: 1,
      items: [{ id: '1', name: '网页客服', type: 'web_widget', status: 'active' }]
    })
    const wrapper = render(ChannelsPage)
    await flushPromises()
    await button(wrapper, '删除').trigger('click')
    await flushPromises()
    expect(mocks.removeChannel).not.toHaveBeenCalled()
    await button(wrapper, '新增渠道').trigger('click')
    await flushPromises()
    const drawer = wrapper.findComponent({ name: 'ElDrawer' })
    await drawer.findAllComponents(ElInput)[0]!.find('input').setValue('保留内容')
    await button(wrapper, '取消').trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(true)
    expect(drawer.findAllComponents(ElInput)[0]!.props('modelValue')).toBe('保留内容')
  })
  it('保存失败的错误在抽屉内可见且保留所有编辑内容', async () => {
    mocks.createChannel.mockRejectedValueOnce(new Error('渠道名称重复'))
    const wrapper = render(ChannelsPage)
    await flushPromises()
    await button(wrapper, '新增渠道').trigger('click')
    await flushPromises()
    const drawer = wrapper.findComponent({ name: 'ElDrawer' })
    await drawer.findAllComponents(ElInput)[0]!.find('input').setValue('网页客服')
    await button(wrapper, '保存').trigger('click')
    await flushPromises()
    expect(drawer.props('modelValue')).toBe(true)
    expect(document.body.textContent).toContain('渠道名称重复')
    expect(drawer.findAllComponents(ElInput)[0]!.props('modelValue')).toBe('网页客服')
    expect(mocks.success).not.toHaveBeenCalled()
  })
})
