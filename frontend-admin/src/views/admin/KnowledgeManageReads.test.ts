import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElTabs } from 'element-plus'
import KnowledgeManagePage from './KnowledgeManagePage.vue'

const mocks = vi.hoisted(() => ({
  base: vi.fn(),
  revisions: vi.fn(),
  models: vi.fn(),
  documents: vi.fn(),
  document: vi.fn(),
  chunks: vi.fn(),
  updateDocument: vi.fn(),
  reindex: vi.fn(),
  removeDocument: vi.fn(),
  rebuild: vi.fn(),
  post: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
  showMessage: vi.fn()
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ user: null, identityVersion: 0 }) }))
vi.mock('../../api', () => ({
  api: {
    knowledge: {
      getBase: mocks.base,
      listRevisions: mocks.revisions,
      listDocuments: mocks.documents,
      getDocument: mocks.document,
      listChunks: mocks.chunks,
      reindexDocument: mocks.reindex,
      removeDocument: mocks.removeDocument,
      updateDocument: mocks.updateDocument
    },
    settings: { availableModels: mocks.models }
  },
  get: mocks.rebuild,
  post: mocks.post
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'kb-1' } }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: { template: '<div />' }
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@skillforge/shared/feedback')>()
  return {
    ...actual,
    confirm: mocks.confirm,
    showMessage: mocks.showMessage,
    message: { ...actual.message, success: mocks.success }
  }
})
let wrapper: VueWrapper
const documentFixture = {
  id: 'doc-1',
  kb_id: 'kb-1',
  title: '原文标题',
  content: '原文正文',
  source_version: 'v1'
}
const job = (status = 'IDLE') => ({
  job_id: status === 'IDLE' ? null : 'job-1',
  kb_id: 'kb-1',
  status,
  revision: 2,
  base_revision: 3,
  document_count: 1,
  ready_documents: status === 'READY' ? 1 : 0,
  unknown_batches: 0,
  write_locked: ['RUNNING', 'READY'].includes(status)
})
beforeEach(() => {
  vi.resetAllMocks()
  mocks.base.mockResolvedValue({ id: 'kb-1', name: '知识库', indexing_mode: 'high_quality' })
  mocks.revisions.mockResolvedValue({ items: [] })
  mocks.models.mockResolvedValue([])
  mocks.documents.mockResolvedValue({
    items: [{ ...documentFixture, status: 'ready', chunk_count: 1 }],
    total: 1
  })
  mocks.document.mockResolvedValue(documentFixture)
  mocks.updateDocument.mockResolvedValue({ ...documentFixture, source_version: 'v2' })
  mocks.chunks.mockResolvedValue({
    items: [
      {
        id: 'chunk-1',
        chunk_index: 0,
        content: '分块正文',
        source_version: 'v1',
        has_embedding: true
      }
    ],
    total: 1
  })
  mocks.rebuild.mockResolvedValue(job())
  mocks.confirm.mockResolvedValue(true)
  mocks.showMessage.mockReturnValue(() => {})
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function open() {
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
}
async function click(text: string) {
  const button = wrapper.findAllComponents(ElButton).find((value) => value.text() === text)
  expect(button, text).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
async function drawerTab(value: string) {
  wrapper.findAllComponents(ElTabs).at(-1)!.vm.$emit('update:modelValue', value)
  await flushPromises()
}
async function rebuildTab() {
  wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'rebuild')
  await flushPromises()
}

describe('知识详情按需读取与已发布状态恢复', () => {
  it.each(['重新索引', '删除'])(
    '%s成功但读取失败时保留旧目录并只允许读取恢复',
    async (operation) => {
      mocks.reindex.mockResolvedValue({})
      mocks.removeDocument.mockResolvedValue({})
      await open()
      mocks.documents.mockRejectedValueOnce(new Error('文档列表暂不可读'))
      await click(operation)
      expect(mocks.success).toHaveBeenCalledWith(
        operation === '删除' ? '文档已删除' : '文档重建已提交'
      )
      expect(wrapper.text()).toContain('文档操作已完成，但列表尚未更新')
      expect(wrapper.text()).toContain('原文标题')
      const action = (label: string) =>
        wrapper.findAllComponents(ElButton).find((value) => value.text() === label)!
      expect(action('重新索引').props('disabled')).toBe(true)
      expect(action('删除').props('disabled')).toBe(true)
      expect(action('编辑 / 分块').props('disabled')).toBe(true)
      await click('重新索引')
      await click('删除')
      expect(mocks.reindex).toHaveBeenCalledTimes(operation === '重新索引' ? 1 : 0)
      expect(mocks.removeDocument).toHaveBeenCalledTimes(operation === '删除' ? 1 : 0)
      await click('读取最新文档')
      expect(wrapper.text()).not.toContain('文档操作已完成，但列表尚未更新')
      expect(action('重新索引').props('disabled')).toBe(false)
    }
  )

  it('文档首次读取失败时不显示空知识库，刷新成功后显示真实结果', async () => {
    mocks.documents.mockRejectedValueOnce(new Error('文档列表暂时不可读'))
    await open()
    const table = wrapper.findAllComponents({ name: 'ElTable' })[0]!
    expect(table.props('emptyText')).toBe('文档列表尚未读取成功，请点击刷新重试')
    expect(wrapper.text()).not.toContain('尚无文档')
    await click('刷新')
    expect(wrapper.text()).toContain('原文标题')
    expect(mocks.documents).toHaveBeenCalledTimes(2)
  })
  it('知识库基本信息读取失败时不显示空文档提示', async () => {
    mocks.base.mockRejectedValueOnce(new Error('知识库暂时不可读'))
    await open()
    expect(wrapper.findAllComponents({ name: 'ElTable' })[0]!.props('emptyText')).toBe(
      '文档列表尚未读取成功，请点击刷新重试'
    )
    expect(mocks.documents).not.toHaveBeenCalled()
    await click('刷新')
    expect(wrapper.text()).toContain('原文标题')
  })
  it('打开和保存原文不预读分块，首次查看分块读取一次，切回不重复读取', async () => {
    await open()
    await click('编辑 / 分块')
    expect(mocks.document).toHaveBeenCalledExactlyOnceWith('doc-1')
    expect(mocks.chunks).not.toHaveBeenCalled()
    await click('保存原文')
    expect(mocks.updateDocument).toHaveBeenCalledWith('doc-1', 'v1', {
      title: '原文标题',
      content: '原文正文'
    })
    expect(mocks.chunks).not.toHaveBeenCalled()
    await drawerTab('chunks')
    expect(mocks.chunks).toHaveBeenCalledExactlyOnceWith('doc-1', { page: 1, page_size: 20 })
    await drawerTab('source')
    await drawerTab('chunks')
    expect(mocks.chunks).toHaveBeenCalledTimes(1)
  })
  it('分块首次读取失败仍保留已打开的原文，重试只读取当前文档分块', async () => {
    await open()
    await click('编辑 / 分块')
    mocks.chunks.mockRejectedValueOnce(new Error('分块暂时不可读'))
    await drawerTab('chunks')
    expect(wrapper.text()).toContain('原文标题')
    await click('读取分块')
    expect(mocks.document).toHaveBeenCalledTimes(1)
    expect(mocks.chunks).toHaveBeenCalledTimes(2)
    expect(mocks.chunks).toHaveBeenLastCalledWith('doc-1', { page: 1, page_size: 20 })
  })
  it.each(['RUNNING', 'UNKNOWN'])(
    '刷新%s重建进展只读取任务状态，不重复查询文档、模型和修订',
    async (status) => {
      mocks.rebuild.mockResolvedValue(job(status))
      await open()
      await rebuildTab()
      await click('刷新重建进展')
      expect(mocks.rebuild).toHaveBeenCalledTimes(2)
      expect(mocks.base).toHaveBeenCalledTimes(1)
      expect(mocks.revisions).toHaveBeenCalledTimes(1)
      expect(mocks.models).toHaveBeenCalledTimes(1)
      expect(mocks.documents).toHaveBeenCalledTimes(1)
      expect(mocks.post).not.toHaveBeenCalled()
    }
  )
  it('发布后文档GET失败只反馈一次，保留成功回执并仅重试读取', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.post.mockResolvedValue(job('PUBLISHED'))
    await open()
    await rebuildTab()
    mocks.documents.mockRejectedValueOnce(new Error('文档列表暂时不可读'))
    await click('发布重建结果')
    expect(mocks.success).toHaveBeenCalledWith('重建结果已发布')
    expect(wrapper.text()).toContain('重建结果已发布，知识库与文档信息尚未刷新')
    const errors = mocks.showMessage.mock.calls.filter((call) => call[0] === 'error')
    expect(errors).toHaveLength(1)
    expect(errors[0]![1]).toBe('文档列表暂时不可读')
    expect(mocks.documents).toHaveBeenCalledTimes(2)
    await click('重新读取发布信息')
    expect(mocks.post).toHaveBeenCalledTimes(1)
    expect(mocks.documents).toHaveBeenCalledTimes(3)
    expect(mocks.models).toHaveBeenCalledTimes(1)
    expect(mocks.rebuild).toHaveBeenCalledTimes(1)
    expect(mocks.showMessage.mock.calls.filter((call) => call[0] === 'error')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('重建结果已发布，知识库与文档信息尚未刷新')
  })
  it('发布回执后定向刷新来源版本，刷新失败可只重试GET而不再次发布', async () => {
    mocks.rebuild.mockResolvedValue(job('READY'))
    mocks.post.mockResolvedValue(job('PUBLISHED'))
    await open()
    await rebuildTab()
    mocks.base.mockRejectedValueOnce(new Error('发布信息暂时不可读'))
    await click('发布重建结果')
    expect(mocks.success).toHaveBeenCalledWith('重建结果已发布')
    expect(wrapper.text()).toContain('重建结果已发布，知识库与文档信息尚未刷新')
    expect(mocks.post).toHaveBeenCalledTimes(1)
    expect(mocks.post.mock.calls[0]![0]).toBe('/knowledge/bases/rebuilds/publish')
    await click('重新读取发布信息')
    expect(mocks.post).toHaveBeenCalledTimes(1)
    expect(mocks.models).toHaveBeenCalledTimes(1)
    expect(mocks.rebuild).toHaveBeenCalledTimes(1)
    expect(mocks.base).toHaveBeenCalledTimes(3)
    expect(mocks.documents).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('重建结果已发布，知识库与文档信息尚未刷新')
  })
})
