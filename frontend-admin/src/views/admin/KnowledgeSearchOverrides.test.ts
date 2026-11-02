import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElInput, ElInputNumber, ElSelect, ElSwitch } from 'element-plus'
import KnowledgeManagePage from './KnowledgeManagePage.vue'

const mocks = vi.hoisted(() => ({
  search: vi.fn(),
  base: vi.fn(),
  document: vi.fn(),
  user: null as null | { permissions: string[] }
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ user: mocks.user, identityVersion: 0 })
}))
vi.mock('../../api', () => ({
  api: {
    knowledge: {
      getBase: mocks.base,
      listRevisions: async () => ({ items: [] }),
      listDocuments: async () => ({ items: [], total: 0 }),
      getDocument: mocks.document,
      search: mocks.search
    },
    settings: { availableModels: async () => [] }
  },
  get: async () => ({ status: 'IDLE' }),
  post: vi.fn()
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'kb-1' } }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: { template: '<div />' }
}))
let wrapper: VueWrapper
beforeEach(() => {
  vi.resetAllMocks()
  mocks.user = null
  mocks.base.mockResolvedValue({
    id: 'kb-1',
    name: '知识库',
    indexing_mode: 'high_quality',
    retrieval_mode: 'hybrid',
    score_threshold_enabled: true
  })
  mocks.search.mockResolvedValue({
    items: [{ chunk_id: 'c1', document_id: 'd1', content: '检索结果', score: 0.8 }]
  })
  mocks.document.mockResolvedValue({
    id: 'd1',
    title: '当前来源文档',
    content: '当前原文',
    source_version: 'v2'
  })
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function open() {
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
  await wrapper
    .findAll('[role="tab"]')
    .find((tab) => tab.text() === '检索验证')!
    .trigger('click')
  await wrapper
    .findAllComponents(ElInput)
    .find((input) => input.props('type') === 'textarea')!
    .setValue('测试问题')
}
async function submit() {
  await wrapper
    .findAll('form')
    .find((form) => form.find('textarea').exists())!
    .trigger('submit')
  await flushPromises()
}
function select(label: string) {
  return wrapper
    .findAllComponents(ElSelect)
    .find(
      (control) =>
        control.props('ariaLabel') === label || control.attributes('aria-label') === label
    )!
}
function number(label: string) {
  return wrapper
    .findAllComponents(ElInputNumber)
    .find(
      (control) =>
        control.props('ariaLabel') === label || control.attributes('aria-label') === label
    )!
}

describe('知识检索仅本次参数覆盖', () => {
  it('从检索结果打开当前来源文档，保留查询与召回结果', async () => {
    mocks.user = { permissions: ['foundation.knowledge.read'] }
    await open()
    await submit()
    mocks.document.mockRejectedValueOnce(new Error('来源读取失败'))
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '查看当前来源文档')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('检索结果')
    await wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '查看当前来源文档')!
      .trigger('click')
    await flushPromises()
    expect(mocks.document).toHaveBeenCalledTimes(2)
    expect(mocks.document).toHaveBeenLastCalledWith('d1')
    expect(document.body.textContent).toContain('当前来源文档')
    expect(wrapper.text()).toContain('检索结果')
    expect(mocks.search).toHaveBeenCalledTimes(1)
  })
  it('默认不提交覆盖项，不保存知识库设置', async () => {
    await open()
    await submit()
    expect(mocks.search).toHaveBeenCalledExactlyOnceWith({ kb_id: 'kb-1', query: '测试问题' })
    expect(wrapper.text()).toContain('本次已提交参数 · 沿用知识库设置')
    expect(wrapper.text()).toContain('检索结果')
  })
  it('提交混合检索覆盖，关闭覆盖后恢复继承，结果参数不随表单编辑改变', async () => {
    await open()
    await wrapper.findComponent(ElSwitch).setValue(true)
    select('本次检索方式').vm.$emit('update:modelValue', 'hybrid')
    select('本次相关度阈值').vm.$emit('update:modelValue', true)
    select('本次重排').vm.$emit('update:modelValue', false)
    await flushPromises()
    number('本次返回数量').vm.$emit('update:modelValue', 8)
    number('本次阈值数值').vm.$emit('update:modelValue', 0.6)
    number('本次向量权重').vm.$emit('update:modelValue', 0.7)
    await wrapper
      .findAllComponents(ElInput)
      .find(
        (control) =>
          control.props('ariaLabel') === '本次来源版本' ||
          control.attributes('aria-label') === '本次来源版本'
      )!
      .setValue(' v7 ')
    await submit()
    expect(mocks.search).toHaveBeenLastCalledWith({
      kb_id: 'kb-1',
      query: '测试问题',
      top_k: 8,
      retrieval_mode: 'hybrid',
      score_threshold_enabled: true,
      score_threshold: 0.6,
      vector_weight: 0.7,
      rerank_enabled: false,
      source_version: 'v7'
    })
    select('本次检索方式').vm.$emit('update:modelValue', 'full_text')
    await flushPromises()
    expect(wrapper.find('details').text()).toContain('hybrid')
    await submit()
    expect(mocks.search).toHaveBeenLastCalledWith({
      kb_id: 'kb-1',
      query: '测试问题',
      top_k: 8,
      retrieval_mode: 'full_text',
      rerank_enabled: false,
      source_version: 'v7'
    })
    await wrapper.findComponent(ElSwitch).setValue(false)
    await submit()
    expect(mocks.search).toHaveBeenLastCalledWith({ kb_id: 'kb-1', query: '测试问题' })
  })
  it('经济索引不提交模式、阈值与向量覆盖；失败保留上次结果和参数', async () => {
    mocks.base.mockResolvedValue({
      id: 'kb-1',
      name: '经济知识库',
      indexing_mode: 'economical',
      retrieval_mode: 'hybrid'
    })
    await open()
    await wrapper.findComponent(ElSwitch).setValue(true)
    expect(select('本次检索方式').props('disabled')).toBe(true)
    expect(select('本次相关度阈值').props('disabled')).toBe(true)
    expect(number('本次向量权重').props('disabled')).toBe(true)
    await submit()
    expect(mocks.search).toHaveBeenLastCalledWith({ kb_id: 'kb-1', query: '测试问题' })
    await wrapper
      .findAllComponents(ElInput)
      .find((input) => input.props('type') === 'textarea')!
      .setValue('新问题')
    mocks.search.mockRejectedValueOnce(new Error('检索失败'))
    await submit()
    expect(wrapper.text()).toContain('检索结果')
    expect(wrapper.text()).toContain('上次成功检索的问题：测试问题')
    expect(wrapper.find('details').text()).toContain('全文检索（经济索引）')
    await submit()
    expect(wrapper.text()).toContain('上次成功检索的问题：新问题')
  })
})
