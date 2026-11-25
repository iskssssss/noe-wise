import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElInput, ElSelect } from 'element-plus'
import { h, reactive } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { HttpClientError } from '@skillforge/shared/utils/http'
import type { ModelSummary } from '../../api'
import ModelsPage from './ModelsPage.vue'

const mocks = vi.hoisted(() => ({
  summaries: vi.fn(),
  options: vi.fn(),
  list: vi.fn(),
  detail: vi.fn()
}))
vi.mock('../../api', () => ({ api: { admin: { models: mocks } } }))
const authState = reactive({
  sessionIdentity: 'account-A',
  identityVersion: 1,
  user: { permissions: [] as string[] }
})
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
let router: Router
async function render(path = '/platform/models') {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/platform/models', component: ModelsPage },
      {
        path: '/platform/models/:id',
        component: {
          template:
            '<RouterLink :to="{path: \'/platform/models\', query: $route.query}">返回列表</RouterLink>'
        }
      }
    ]
  })
  await router.push(path)
  await router.isReady()
  wrapper = mount(
    { render: () => h(RouterView) },
    {
      attachTo: document.body,
      global: { plugins: [router], directives: { permission: () => {} } }
    }
  )
  await flushPromises()
}
async function click(text: string) {
  const button = wrapper.findAllComponents(ElButton).find((button) => button.text() === text)!
  expect(button).toBeDefined()
  if (text === '查询') await wrapper.get('form').trigger('submit')
  else await button.trigger('click')
  await flushPromises()
}
const rows = () => wrapper.findComponent({ name: 'ElTable' }).props('data')
beforeEach(() => {
  vi.resetAllMocks()
  authState.sessionIdentity = 'account-A'
  authState.identityVersion = 1
  mocks.summaries.mockResolvedValue(models)
  mocks.options.mockResolvedValue({
    kinds: [
      { value: 'LLM', label: '对话模型' },
      { value: 'EMBEDDING', label: '向量模型' }
    ],
    statuses: [
      { value: 'true', label: '启用' },
      { value: 'false', label: '停用' }
    ],
    publications: [
      { value: 'pending', label: '草稿待发布' },
      { value: 'published', label: '与发布一致' }
    ]
  })
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
  expect(wrapper.findAll('a').map((link) => link.attributes('href'))).toContain(
    '/platform/models/9007199254740993'
  )
  expect(mocks.summaries).toHaveBeenCalledTimes(1)
  expect(mocks.list).not.toHaveBeenCalled()
  expect(mocks.detail).not.toHaveBeenCalled()
})
it('组合筛选查询使用服务端结果，详情往返、同页导航与重置恢复已查询条件', async () => {
  await render()
  await wrapper.findComponent(ElInput).setValue('知识')
  const selects = wrapper.findAllComponents(ElSelect)
  selects[0]!.vm.$emit('update:modelValue', 'EMBEDDING')
  selects[1]!.vm.$emit('update:modelValue', 'false')
  selects[2]!.vm.$emit('update:modelValue', 'pending')
  await flushPromises()
  expect(mocks.summaries).toHaveBeenCalledTimes(1)
  expect(wrapper.text()).toContain('点击查询后生效')
  mocks.summaries.mockResolvedValue([models[1]])
  await click('查询')
  expect(rows()).toEqual([models[1]])
  expect(mocks.summaries).toHaveBeenLastCalledWith(
    { search: '知识', kind: 'EMBEDDING', enabled: 'false', publication: 'pending' },
    expect.objectContaining({ signal: expect.any(AbortSignal) })
  )
  const context = {
    list_search: '知识',
    list_kind: 'EMBEDDING',
    list_enabled: 'false',
    list_publication: 'pending'
  }
  expect(router.currentRoute.value.query).toEqual(context)
  await wrapper
    .findAll('a')
    .find((link) => link.text() === '知识检索')!
    .trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query).toEqual(context)
  await wrapper.get('a').trigger('click')
  await flushPromises()
  expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('知识')
  expect(rows()).toEqual([models[1]])
  await router.push('/platform/models?list_search=另一条件')
  await flushPromises()
  expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('另一条件')
  mocks.summaries.mockResolvedValue(models)
  await click('重置')
  expect(router.currentRoute.value.query).toEqual({})
  await click('刷新')
  expect(rows()).toEqual(models)
  expect(mocks.summaries).toHaveBeenLastCalledWith(
    { search: '', kind: '', enabled: '', publication: '' },
    expect.any(Object)
  )
})
it('failed refresh retains summaries and filters; retry reads only the summary endpoint', async () => {
  mocks.summaries.mockResolvedValue([models[0]])
  await render('/platform/models?list_search=provider-a')
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
  expect(wrapper.text()).toContain('模型读取失败，请调整筛选后查询或重试刷新')
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

it('无结果可重置，初始空目录按权限说明下一步', async () => {
  mocks.summaries.mockResolvedValue([])
  await render('/platform/models?list_search=不存在')
  expect(wrapper.text()).not.toContain('点击名称配置')
  expect(wrapper.text()).toContain('没有符合当前查询条件')
  await click('重置并查看全部模型')
  expect(wrapper.text()).toContain('请联系有配置权限的管理员添加')
  authState.user.permissions = ['foundation.models.create']
  await flushPromises()
  expect(wrapper.text()).toContain('请使用上方“新增模型”')
})
it('选项失败禁用选择并允许重读；非法地址筛选错误不伪装为零条结果', async () => {
  mocks.options.mockRejectedValueOnce(Error('选项暂不可读'))
  mocks.summaries.mockRejectedValueOnce(
    new HttpClientError({ status: 422, code: 'model_filter_invalid', userMessage: '校验失败' })
  )
  await render('/platform/models?list_kind=invalid')
  expect(wrapper.findAllComponents(ElSelect).every((select) => select.props('disabled'))).toBe(true)
  expect(wrapper.findComponent(ModelsPage).text()).not.toContain('没有符合当前查询条件')
  expect(document.body.textContent).toContain('模型筛选条件无效，请重新选择或重置后查询')
  await click('重新读取筛选选项')
  expect(wrapper.findAllComponents(ElSelect).every((select) => !select.props('disabled'))).toBe(
    true
  )
  await click('重置')
  expect(rows()).toEqual(models)
})
