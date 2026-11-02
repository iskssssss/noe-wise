import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, RouterLinkStub, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElInput, ElSelect } from 'element-plus'
import { reactive } from 'vue'
import type { ModelSummary } from '../../api'
import ModelsPage from './ModelsPage.vue'

const mocks = vi.hoisted(() => ({ summaries: vi.fn(), list: vi.fn(), detail: vi.fn() }))
vi.mock('../../api', () => ({ api: { admin: { models: mocks } } }))
const authState = reactive({ sessionIdentity: 'account-A', identityVersion: 1 })
vi.mock('../../stores/auth', () => ({ useAuthStore: () => authState }))
let wrapper: VueWrapper
const models: ModelSummary[] = [
  {
    id: '9007199254740993',
    name: '业务对话',
    kind: 'LLM',
    provider_id: 'provider-a',
    model_id: 'model-a',
    enabled: true,
    published_revision: 4,
    has_unpublished_changes: false
  },
  {
    id: '9007199254740995',
    name: '知识检索',
    kind: 'EMBEDDING',
    provider_id: 'provider-b',
    model_id: 'embed-b',
    enabled: false,
    published_revision: null,
    has_unpublished_changes: true
  }
]
async function render() {
  wrapper = mount(ModelsPage, {
    attachTo: document.body,
    global: {
      stubs: { RouterLink: RouterLinkStub },
      directives: { permission: () => {} }
    }
  })
  await flushPromises()
}
async function click(text: string) {
  const button = wrapper.findAllComponents(ElButton).find((button) => button.text() === text)!
  expect(button).toBeDefined()
  await button.trigger('click')
  await flushPromises()
}
const rows = () => wrapper.findComponent({ name: 'ElTable' }).props('data')
beforeEach(() => {
  vi.resetAllMocks()
  authState.sessionIdentity = 'account-A'
  authState.identityVersion = 1
  mocks.summaries.mockResolvedValue(models)
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
it('eight-field summaries render publication state and exact detail navigation without reading configuration', async () => {
  await render()
  expect(rows()).toEqual(models)
  expect(wrapper.text()).toContain('尚未发布')
  expect(wrapper.text()).toContain('待发布')
  expect(wrapper.findAllComponents(RouterLinkStub).map((link) => link.props('to'))).toContain(
    '/platform/models/9007199254740993'
  )
  expect(mocks.summaries).toHaveBeenCalledTimes(1)
  expect(mocks.list).not.toHaveBeenCalled()
  expect(mocks.detail).not.toHaveBeenCalled()
})
it('search and combined kind/status/publication filters stay local to summaries', async () => {
  await render()
  await wrapper.findComponent(ElInput).setValue('embed-b')
  expect(rows()).toEqual([models[1]])
  await wrapper.findComponent(ElInput).setValue('provider-a')
  expect(rows()).toEqual([models[0]])
  await wrapper.findComponent(ElInput).setValue('知识')
  const selects = wrapper.findAllComponents(ElSelect)
  selects[0]!.vm.$emit('update:modelValue', 'EMBEDDING')
  selects[1]!.vm.$emit('update:modelValue', 'false')
  selects[2]!.vm.$emit('update:modelValue', 'pending')
  await flushPromises()
  expect(rows()).toEqual([models[1]])
  await click('重置')
  expect(rows()).toEqual(models)
  expect(mocks.summaries).toHaveBeenCalledTimes(1)
  expect(mocks.list).not.toHaveBeenCalled()
  expect(mocks.detail).not.toHaveBeenCalled()
})
it('failed refresh retains summaries and filters; retry reads only the summary endpoint', async () => {
  await render()
  await wrapper.findComponent(ElInput).setValue('provider-a')
  mocks.summaries.mockRejectedValueOnce(Error('摘要目录暂时不可读'))
  await click('刷新')
  expect(rows()).toEqual([models[0]])
  expect(wrapper.get('[role=status]').text()).toContain('上次成功读取 1 个模型，刷新失败')
  expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('provider-a')
  mocks.summaries.mockResolvedValueOnce([{ ...models[0]!, name: '刷新后的对话模型' }])
  await click('刷新')
  expect(rows()).toEqual([{ ...models[0]!, name: '刷新后的对话模型' }])
  expect(wrapper.get('[role=status]').text()).toContain('当前 1 个模型')
  expect(mocks.summaries).toHaveBeenCalledTimes(3)
  expect(mocks.list).not.toHaveBeenCalled()
  expect(mocks.detail).not.toHaveBeenCalled()
})
it('an initial summary read failure is recoverable without fetching full models', async () => {
  mocks.summaries.mockRejectedValueOnce(Error('没有目录读取权限'))
  await render()
  expect(wrapper.findComponent({ name: 'ElTable' }).props('emptyText')).toBe(
    '模型读取失败，请重试刷新'
  )
  expect(rows()).toEqual([])
  await click('刷新')
  expect(rows()).toEqual(models)
  expect(mocks.list).not.toHaveBeenCalled()
  expect(mocks.detail).not.toHaveBeenCalled()
})

it('身份切换立即清除旧模型和筛选，迟到结果不能覆盖新目录', async () => {
  let complete!: (value: ModelSummary[]) => void
  await render()
  await wrapper.findComponent(ElInput).setValue('provider-a')
  mocks.summaries.mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    })
  )
  await wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  const next = { ...models[1]!, id: 'new-model', name: '新账号模型' }
  let finishNew!: (value: ModelSummary[]) => void
  mocks.summaries.mockReturnValueOnce(
    new Promise((resolve) => {
      finishNew = resolve
    })
  )
  authState.sessionIdentity = 'account-B'
  authState.identityVersion++
  await flushPromises()
  expect(rows()).toEqual([])
  complete([{ ...models[0]!, name: '迟到的旧账号模型' }])
  await flushPromises()
  expect(rows()).toEqual([])
  finishNew([next])
  await flushPromises()
  expect(rows()).toEqual([next])
  expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('')
  expect(wrapper.text()).not.toContain('迟到的旧账号模型')
})

it('退出登录清除目录并停止新读取，权限版本变化重新读取', async () => {
  await render()
  authState.identityVersion++
  await flushPromises()
  expect(mocks.summaries).toHaveBeenCalledTimes(2)
  authState.sessionIdentity = ''
  authState.identityVersion++
  await flushPromises()
  expect(rows()).toEqual([])
  expect(mocks.summaries).toHaveBeenCalledTimes(2)
})
