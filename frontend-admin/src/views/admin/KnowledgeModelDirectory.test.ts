import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElOption } from 'element-plus'
import KnowledgeManagePage from './KnowledgeManagePage.vue'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'

const calls = vi.hoisted(() => ({ models: vi.fn(), documents: vi.fn() }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ user: null, identityVersion: 0 }) }))
vi.mock('../../api', () => ({
  api: {
    knowledge: {
      getBase: async () => ({ id: 'kb-1', name: '资料库' }),
      listRevisions: async () => ({ items: [] }),
      listDocuments: calls.documents
    },
    settings: { availableModels: calls.models }
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
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.resetAllMocks()
  calls.documents.mockResolvedValue({
    items: [{ id: 'doc-1', title: '现有文档', status: 'ready', chunk_count: 1 }],
    total: 1
  })
  calls.models.mockResolvedValue([])
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})
async function tab(label: string) {
  await wrapper!
    .findAll('[role="tab"]')
    .find((item) => item.text() === label)!
    .trigger('click')
  await flushPromises()
}

it('reads documents without a model request and recovers a failed model directory independently', async () => {
  calls.models.mockRejectedValueOnce(new Error('模型目录不可用')).mockResolvedValueOnce([
    { id: 'embedding-1', name: '向量模型', kind: 'EMBEDDING' },
    { id: 'chat-1', name: '对话模型', kind: 'CHAT' }
  ])
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
  expect(wrapper.text()).toContain('现有文档')
  expect(calls.models).not.toHaveBeenCalled()
  await tab('索引与模型')
  expect(calls.models).toHaveBeenCalledTimes(1)
  const feedback = wrapper.findAllComponents(FeedbackNotice)
  expect(feedback).toHaveLength(1)
  expect(feedback[0]!.props('title')).toBe('模型目录不可用')
  expect(calls.documents).toHaveBeenCalledTimes(1)
  await wrapper
    .findAllComponents(ElButton)
    .find((item) => item.text() === '刷新模型目录')!
    .trigger('click')
  await flushPromises()
  expect(calls.models).toHaveBeenCalledTimes(2)
  expect(wrapper.findAllComponents(ElOption).map((item) => item.props('label'))).toContain(
    '向量模型'
  )
  expect(wrapper.findAllComponents(ElOption).map((item) => item.props('label'))).not.toContain(
    '对话模型'
  )
  expect(calls.documents).toHaveBeenCalledTimes(1)
  await tab('文档维护')
  expect(wrapper.text()).toContain('现有文档')
})

it('treats a successful empty directory as loaded when switching tabs', async () => {
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
  await tab('索引与模型')
  await tab('文档维护')
  await tab('索引与模型')
  expect(calls.models).toHaveBeenCalledTimes(1)
})
