import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElPagination } from 'element-plus'
import KnowledgeManagePage from './KnowledgeManagePage.vue'
const mocks = vi.hoisted(() => ({ documents: vi.fn() }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ user: null, identityVersion: 0 }) }))
vi.mock('../../api', () => ({
  api: {
    knowledge: {
      getBase: async () => ({ id: 'kb-1', name: '知识库' }),
      listRevisions: async () => ({ items: [] }),
      listDocuments: mocks.documents
    },
    settings: { availableModels: async () => [] }
  },
  get: async () => ({ status: 'IDLE' }),
  getSessionId: () => 'test-session',
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
const result = (title: string) => ({ items: [{ id: 'doc-1', title, status: 'READY' }], total: 101 })
beforeEach(() => {
  vi.resetAllMocks()
  mocks.documents.mockResolvedValueOnce(result('第一页文档'))
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function open() {
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
  return wrapper.findAllComponents(ElPagination)[0]!
}
describe('知识文档分页失败保留原状态', () => {
  it('下一页读取失败时保留原文档与页码', async () => {
    const pagination = await open()
    mocks.documents.mockRejectedValueOnce(new Error('文档读取失败'))
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(pagination.props('currentPage')).toBe(1)
    expect(wrapper.text()).toContain('第一页文档')
    expect(mocks.documents).toHaveBeenLastCalledWith('kb-1', { page: 2, page_size: 20 })
  })
  it('切换每页条数失败时同时恢复原页码和条数，之后可成功重试', async () => {
    const pagination = await open()
    mocks.documents.mockResolvedValueOnce(result('第二页文档'))
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    mocks.documents.mockRejectedValueOnce(new Error('文档读取失败'))
    pagination.vm.$emit('size-change', 50)
    await flushPromises()
    expect(pagination.props('currentPage')).toBe(2)
    expect(pagination.props('pageSize')).toBe(20)
    expect(wrapper.text()).toContain('第二页文档')
    mocks.documents.mockResolvedValueOnce(result('五十条第一页'))
    pagination.vm.$emit('size-change', 50)
    await flushPromises()
    expect(pagination.props('currentPage')).toBe(1)
    expect(pagination.props('pageSize')).toBe(50)
    expect(wrapper.text()).toContain('五十条第一页')
    expect(mocks.documents).toHaveBeenLastCalledWith('kb-1', { page: 1, page_size: 50 })
  })
})
