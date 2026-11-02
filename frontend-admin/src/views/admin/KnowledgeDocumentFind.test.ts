import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElInput, ElSelect, ElPagination, ElTabs, ElUpload } from 'element-plus'
import KnowledgeManagePage from './KnowledgeManagePage.vue'
const mocks = vi.hoisted(() => ({ documents: vi.fn(), upload: vi.fn() }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ user: { tenant_id: 'tenant', sub: 'user', permissions: [] }, identityVersion: 1 }) }))
vi.mock('../../api', () => ({
  api: {
    knowledge: {
      getBase: async () => ({ id: 'kb-1', name: '知识库' }),
      listRevisions: async () => ({ items: [] }),
      listDocuments: mocks.documents,
      uploadFile: mocks.upload
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
let wrapper: VueWrapper | undefined
const result = (title: string, total = 101) => ({
  items: [{ id: 'doc-1', title, status: 'ready', source_version: 'v1' }],
  total
})
function button(name: string) {
  return wrapper!.findAllComponents(ElButton).find((item) => item.text() === name)!
}
function titleInput() {
  return wrapper!
    .findAllComponents(ElInput)
    .find((item) => item.props('placeholder') === '按标题查找当前知识库全部文档')!
}
function statusSelect() {
  return wrapper!.findAllComponents(ElSelect)[0]!
}
function pagination() {
  return wrapper!.findAllComponents(ElPagination)[0]!
}
async function open() {
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
}
async function fill(title: string, status = '') {
  titleInput().vm.$emit('update:modelValue', title)
  statusSelect().vm.$emit('update:modelValue', status)
  await flushPromises()
}
// Submit the real owning form so both button and Enter follow the same native form contract.
async function submit() {
  await wrapper!.find('form').trigger('submit')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.documents.mockResolvedValue(result('原文档'))
  mocks.upload.mockImplementation(async (_kb: string, _file: FormData, requestId: string) => ({
    request_id: requestId, status: 'SUCCEEDED', document: { document_id: 'new', source_version: 'new-v', chunks: 1 }
  }))
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})
it('typing is local until submit, then title and status query the entire library from page one', async () => {
  await open()
  mocks.documents.mockResolvedValueOnce(result('第二页原文档'))
  pagination().vm.$emit('current-change', 2)
  await flushPromises()
  await fill('  付款%_\\  ', 'failed')
  expect(mocks.documents).toHaveBeenCalledTimes(2)
  mocks.documents.mockResolvedValueOnce(result('全库匹配文档'))
  await submit()
  expect(mocks.documents).toHaveBeenLastCalledWith('kb-1', {
    page: 1,
    page_size: 20,
    titleQuery: '付款%_\\',
    status: 'failed'
  })
  expect(pagination().props('currentPage')).toBe(1)
  expect(wrapper!.text()).toContain('全库匹配文档')
})
it('pagination follows the last successful filters even after editing the pending input', async () => {
  await open()
  await fill('订单', 'ready')
  await submit()
  await fill('尚未提交', 'processing')
  pagination().vm.$emit('current-change', 2)
  await flushPromises()
  expect(mocks.documents).toHaveBeenLastCalledWith('kb-1', {
    page: 2,
    page_size: 20,
    titleQuery: '订单',
    status: 'ready'
  })
})
it('failed filtering preserves inputs, previous page and rows, states the old result and retries the requested filter', async () => {
  await open()
  mocks.documents.mockResolvedValueOnce(result('第二页旧文档'))
  pagination().vm.$emit('current-change', 2)
  await flushPromises()
  await fill('新标题', 'processing')
  mocks.documents.mockRejectedValueOnce(new Error('读取失败'))
  await submit()
  expect(pagination().props('currentPage')).toBe(2)
  expect(wrapper!.text()).toContain('第二页旧文档')
  expect(wrapper!.text()).toContain('仍显示上次成功读取的文档和页码')
  expect(titleInput().props('modelValue')).toBe('新标题')
  expect(statusSelect().props('modelValue')).toBe('processing')
  mocks.documents.mockResolvedValueOnce(result('重试结果'))
  await submit()
  expect(mocks.documents).toHaveBeenLastCalledWith('kb-1', {
    page: 1,
    page_size: 20,
    titleQuery: '新标题',
    status: 'processing'
  })
  expect(wrapper!.text()).toContain('重试结果')
  expect(wrapper!.text()).not.toContain('本次筛选尚未更新')
})
it('clearing successfully returns to unfiltered first-page data', async () => {
  await open()
  await fill('订单', 'failed')
  await submit()
  mocks.documents.mockResolvedValueOnce(result('全量恢复'))
  await button('清空筛选').trigger('click')
  await flushPromises()
  expect(mocks.documents).toHaveBeenLastCalledWith('kb-1', { page: 1, page_size: 20 })
  expect(titleInput().props('modelValue')).toBe('')
  expect(statusSelect().props('modelValue')).toBe('')
  expect(wrapper!.text()).toContain('全量恢复')
})
it('failed clearing preserves filtered rows and applied pagination until the clear can succeed', async () => {
  await open()
  await fill('订单', 'failed')
  await submit()
  mocks.documents.mockRejectedValueOnce(new Error('清空读取失败'))
  await button('清空筛选').trigger('click')
  await flushPromises()
  expect(titleInput().props('modelValue')).toBe('')
  expect(wrapper!.text()).toContain('标题包含“订单”')
  pagination().vm.$emit('current-change', 2)
  await flushPromises()
  expect(mocks.documents).toHaveBeenLastCalledWith('kb-1', {
    page: 2,
    page_size: 20,
    titleQuery: '订单',
    status: 'failed'
  })
})
it('server empty matches are distinguished from an unfiltered empty library', async () => {
  await open()
  await fill('不存在')
  mocks.documents.mockResolvedValueOnce({ items: [], total: 0 })
  await submit()
  expect(wrapper!.text()).toContain('没有符合当前筛选的文档')
  mocks.documents.mockResolvedValueOnce({ items: [], total: 0 })
  await button('清空筛选').trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('尚无文档')
})
it('publication scope keeps its independent ready-only pagination and never inherits management filters', async () => {
  await open()
  await fill('订单', 'failed')
  await submit()
  wrapper!.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'versions')
  await flushPromises()
  mocks.documents.mockResolvedValueOnce(result('就绪文档', 1))
  await button('准备知识发布').trigger('click')
  await flushPromises()
  expect(mocks.documents).toHaveBeenLastCalledWith('kb-1', {
    status: 'ready',
    page: 1,
    page_size: 100
  })
})
it('successful upload clears management filters only with the successful unfiltered first-page read', async () => {
  await open()
  await fill('旧标题', 'failed')
  await submit()
  pagination().vm.$emit('current-change', 2)
  await flushPromises()
  mocks.documents.mockRejectedValueOnce(new Error('上传后读取失败'))
  const file = Object.assign(new File(['内容'], '新文件.txt', { type: 'text/plain' }), { uid: 1 })
  wrapper!.findComponent(ElUpload).props('onChange')!(
    { name: file.name, status: 'ready', uid: 1, raw: file },
    []
  )
  await flushPromises()
  expect(pagination().props('currentPage')).toBe(2)
  expect(titleInput().props('modelValue')).toBe('旧标题')
  expect(wrapper!.text()).toContain('标题包含“旧标题”')
  mocks.documents.mockResolvedValueOnce(result('新文件'))
  await button('读取上传后的文档').trigger('click')
  await flushPromises()
  expect(mocks.documents).toHaveBeenLastCalledWith('kb-1', { page: 1, page_size: 20 })
  expect(titleInput().props('modelValue')).toBe('')
  expect(statusSelect().props('modelValue')).toBe('')
  expect(pagination().props('currentPage')).toBe(1)
  expect(mocks.upload).toHaveBeenCalledTimes(1)
})

it('上传后读取失败时顶部刷新恢复未筛选列表，不重复上传', async () => {
  await open()
  await fill('旧标题', 'failed')
  await submit()
  pagination().vm.$emit('current-change', 2)
  await flushPromises()
  mocks.documents.mockRejectedValueOnce(new Error('上传后读取失败'))
  const file = Object.assign(new File(['内容'], '新文件.txt', { type: 'text/plain' }), { uid: 1 })
  wrapper!.findComponent(ElUpload).props('onChange')!(
    { name: file.name, status: 'ready', uid: 1, raw: file },
    []
  )
  await flushPromises()
  expect(wrapper!.text()).toContain('文件已上传，最新文档列表尚未读取')
  mocks.documents.mockRejectedValueOnce(new Error('刷新仍失败'))
  await button('刷新').trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('文件已上传，最新文档列表尚未读取')
  expect(titleInput().props('modelValue')).toBe('旧标题')
  expect(pagination().props('currentPage')).toBe(2)
  mocks.documents.mockResolvedValueOnce(result('新文件'))
  await button('刷新').trigger('click')
  await flushPromises()
  expect(mocks.documents).toHaveBeenLastCalledWith('kb-1', { page: 1, page_size: 20 })
  expect(wrapper!.text()).toContain('新文件')
  expect(wrapper!.text()).not.toContain('文件已上传，最新文档列表尚未读取')
  expect(titleInput().props('modelValue')).toBe('')
  expect(statusSelect().props('modelValue')).toBe('')
  expect(pagination().props('currentPage')).toBe(1)
  expect(wrapper!.findComponent(ElUpload).props('disabled')).toBe(false)
  expect(mocks.upload).toHaveBeenCalledTimes(1)
})

it('labels the status control clearly and scopes the filter layout to this page', async () => {
  await open()
  expect(statusSelect().props('placeholder')).toBe('全部状态')
  expect(wrapper!.find('form.knowledge-document-filters').exists()).toBe(true)
  expect(wrapper!.find('.knowledge-status-filter').exists()).toBe(true)
})
