import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElCheckboxGroup, ElDrawer, ElPagination, ElTabs, ElUpload } from 'element-plus'
import KnowledgeManagePage from './KnowledgeManagePage.vue'
import { reactive, ref } from 'vue'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import { HttpClientError } from '@skillforge/shared/utils/http'
const mocks = vi.hoisted(() => ({
  base: vi.fn(),
  documents: vi.fn(),
  latestSources: vi.fn(),
  revisions: vi.fn(),
  prepare: vi.fn(),
  upload: vi.fn(),
  uploadResult: vi.fn(),
  publish: vi.fn(),
  combine: vi.fn(),
  result: vi.fn(),
  member: 'user',
  kb: 'kb-1',
  permissions: [
    'foundation.knowledge.read',
    'foundation.knowledge.create',
    'foundation.knowledge.update',
    'foundation.knowledge.publish'
  ],
  confirm: vi.fn()
}))
vi.mock('../../api', () => ({
  api: {
    knowledge: {
      getBase: mocks.base,
      listRevisions: mocks.revisions,
      listDocuments: mocks.documents,
      latestReadySources: mocks.latestSources,
      prepareRevision: mocks.prepare,
      uploadFile: mocks.upload,
      uploadResult: mocks.uploadResult,
      publishRevision: mocks.publish,
      prepareAndPublish: mocks.combine,
      prepareAndPublishResult: mocks.result
    },
    settings: { availableModels: async () => [] }
  },
  get: async () => ({ status: 'IDLE' }),
  post: vi.fn()
}))
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    user: { tenant_id: 'tenant', sub: mocks.member, permissions: mocks.permissions },
    identityVersion: 1
  })
}))
const routeState = reactive({ params: { id: 'kb-1' } })
vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: { template: '<div />' }
}))
vi.mock('@skillforge/shared/feedback', async (load) => ({
  ...(await load<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm
}))
let wrapper: VueWrapper | undefined
const doc = (index: number) => ({
  id: 'doc-' + index,
  title: '文档' + index,
  status: 'ready',
  source_version: 'v' + index
})
const ready = { id: 'revision-1', revision: 1, status: 'ready', source_versions: ['v1', 'v2'] }
function button(label: string) {
  return wrapper!.findAllComponents(ElButton).find((item) => item.text() === label)!
}
async function open() {
  routeState.params.id = mocks.kb
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
  wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'versions')
  await flushPromises()
  await button('准备知识发布').trigger('click')
  await flushPromises()
  return wrapper.findAllComponents(ElDrawer).find((item) => item.props('title') === '准备知识发布')!
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  mocks.member = 'user'
  mocks.kb = 'kb-1'
  mocks.permissions = [
    'foundation.knowledge.read',
    'foundation.knowledge.create',
    'foundation.knowledge.update',
    'foundation.knowledge.publish'
  ]
  mocks.base.mockResolvedValue({ id: 'kb-1', name: '知识库', indexing_mode: 'high_quality' })
  mocks.documents.mockResolvedValue({ items: [doc(1), doc(2)], total: 2 })
  mocks.revisions.mockResolvedValue({ items: [] })
  mocks.prepare.mockResolvedValue(ready)
  mocks.upload.mockImplementation(async (_kb: string, _file: FormData, requestId: string) => ({
    request_id: requestId,
    status: 'SUCCEEDED',
    document: { document_id: 'new-doc', chunks: 1, source_version: 'new-v' }
  }))
  mocks.publish.mockResolvedValue({ ...ready, status: 'published' })
  mocks.confirm.mockResolvedValue(true)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})
it('将历史发布回执查询收起，展开后仍可按原请求只读查询', async () => {
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
  const details = wrapper.get('details[aria-label="查询知识发布回执"]')
  expect(details.attributes('open')).toBeUndefined()
  await details.get('summary').trigger('click')
  expect((details.element as HTMLDetailsElement).open).toBe(true)
  const request = '12345678-1234-4234-8234-123456789abc'
  await details.get('input[aria-label="原请求 ID"]').setValue(request)
  mocks.result.mockResolvedValueOnce({ request_id: request, status: 'PROCESSING' })
  await button('查询发布回执').trigger('click')
  await flushPromises()
  expect(mocks.result).toHaveBeenCalledExactlyOnceWith(request)
  expect(mocks.combine).not.toHaveBeenCalled()
  expect(mocks.prepare).not.toHaveBeenCalled()
  expect(mocks.publish).not.toHaveBeenCalled()
})
it('reads all ready document pages, defaults all selected and prepares exactly the adjusted scope in one command', async () => {
  mocks.documents.mockImplementation(async (_id, params) =>
    params.status === 'ready'
      ? {
          items: params.page === 1 ? Array.from({ length: 100 }, (_, i) => doc(i + 1)) : [doc(101)],
          total: 101
        }
      : { items: [doc(1)], total: 101 }
  )
  const drawer = await open()
  expect(mocks.documents).toHaveBeenCalledWith('kb-1', { status: 'ready', page: 2, page_size: 100 })
  expect(mocks.prepare).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('文档101')
  expect(document.body.textContent).toContain('v101')
  expect(drawer.findComponent(ElCheckboxGroup).props('modelValue')).toHaveLength(101)
  drawer.findComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['v1', 'v101'])
  await flushPromises()
  await button('确认范围并准备').trigger('click')
  await flushPromises()
  expect(mocks.prepare).toHaveBeenCalledExactlyOnceWith(
    'kb-1',
    ['v1', 'v101'],
    expect.objectContaining({ user_invocable: true, allowed_operators: ['VECTOR', 'KEYWORD'] })
  )
  expect(mocks.publish).not.toHaveBeenCalled()
})
it('已发布知识库可从文档页直接选择更新范围', async () => {
  mocks.base.mockResolvedValue({
    id: 'kb-1',
    name: '知识库',
    indexing_mode: 'high_quality',
    active_revision_id: '123'
  })
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
  await button('更新知识发布').trigger('click')
  await flushPromises()
  const drawer = wrapper
    .findAllComponents(ElDrawer)
    .find((item) => item.props('title') === '准备知识发布')!
  expect(drawer.props('modelValue')).toBe(true)
  expect(drawer.findComponent(ElCheckboxGroup).props('modelValue')).toEqual(['v1', 'v2'])
  expect(mocks.combine).not.toHaveBeenCalled()
  expect(mocks.prepare).not.toHaveBeenCalled()
})
it('closing a selection without confirming performs no write', async () => {
  const drawer = await open()
  await drawer
    .findAllComponents(ElButton)
    .find((item) => item.text() === '关闭')!
    .trigger('click')
  await flushPromises()
  expect(mocks.prepare).not.toHaveBeenCalled()
  expect(mocks.publish).not.toHaveBeenCalled()
})
it('disables empty scope and explains when no ready documents exist', async () => {
  mocks.documents.mockResolvedValue({ items: [], total: 0 })
  await open()
  expect(document.body.textContent).toContain('暂无就绪文档')
  expect(button('确认范围并准备').props('disabled')).toBe(true)
})
it('large libraries load only requested pages, preserve cross-page choices and prepare a bounded subset', async () => {
  mocks.documents.mockImplementation(async (_id, params) => ({
    items: [doc(params.page === 2 ? 101 : 1)],
    total: 2001
  }))
  const drawer = await open()
  const group = drawer.findComponent(ElCheckboxGroup)
  expect(group.props('modelValue')).toEqual([])
  expect(mocks.documents.mock.calls.filter((call) => call[1]?.status === 'ready')).toHaveLength(1)
  group.vm.$emit('update:modelValue', ['v1'])
  await flushPromises()
  const pagination = drawer.findComponent(ElPagination)
  pagination.vm.$emit('current-change', 2)
  await flushPromises()
  expect(document.body.textContent).toContain('文档101')
  expect(group.props('modelValue')).toEqual(['v1'])
  group.vm.$emit('update:modelValue', ['v1', 'v101'])
  await flushPromises()
  mocks.documents.mockRejectedValueOnce(new Error('下一页读取失败'))
  pagination.vm.$emit('current-change', 3)
  await flushPromises()
  expect(pagination.props('currentPage')).toBe(2)
  expect(document.body.textContent).toContain('文档101')
  expect(group.props('modelValue')).toEqual(['v1', 'v101'])
  await button('确认范围并准备').trigger('click')
  await flushPromises()
  expect(mocks.prepare).toHaveBeenCalledExactlyOnceWith('kb-1', ['v1', 'v101'], expect.any(Object))
  expect(mocks.documents.mock.calls.filter((call) => call[1]?.status === 'ready')).toHaveLength(3)
})
it('读取期间总数超过 2000 时分页第一页只展示第一页文档', async () => {
  mocks.documents.mockImplementation(async (_id, params) => ({
    items:
      params.status === 'ready'
        ? Array.from({ length: 100 }, (_, index) => doc((params.page - 1) * 100 + index + 1))
        : [doc(1)],
    total: params.status === 'ready' && params.page > 1 ? 2001 : 2000
  }))
  const drawer = await open()
  expect(drawer.findComponent(ElPagination).props('currentPage')).toBe(1)
  expect(document.body.textContent).toContain('文档100')
  expect(document.body.textContent).not.toContain('文档101')
  await button('选择当前显示文档').trigger('click')
  expect(drawer.findComponent(ElCheckboxGroup).props('modelValue')).toHaveLength(100)
  drawer.findComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  expect(document.body.textContent).toContain('文档101')
  expect(drawer.findComponent(ElCheckboxGroup).props('modelValue')).toHaveLength(100)
})
it('分页范围的总数降到 2000 以下仍能浏览其他页并保留选择', async () => {
  mocks.documents.mockImplementation(async (_id, params) => ({
    items: [doc(params.page === 2 ? 101 : 1)],
    total: params.status === 'ready' && params.page === 1 ? 2001 : 1999
  }))
  const drawer = await open()
  const group = drawer.findComponent(ElCheckboxGroup)
  group.vm.$emit('update:modelValue', ['v1'])
  const pagination = drawer.findComponent(ElPagination)
  pagination.vm.$emit('current-change', 2)
  await flushPromises()
  expect(drawer.findComponent(ElPagination).exists()).toBe(true)
  expect(pagination.props('total')).toBe(1999)
  expect(group.props('modelValue')).toEqual(['v1'])
  pagination.vm.$emit('current-change', 1)
  await flushPromises()
  expect(mocks.documents).toHaveBeenLastCalledWith('kb-1', {
    status: 'ready',
    page: 1,
    page_size: 100
  })
  expect(group.props('modelValue')).toEqual(['v1'])
})
it('批量选择只影响当前显示文档，翻页后保留并可取消当前页', async () => {
  mocks.documents.mockImplementation(async (_id, params) => ({
    items: params.page === 2 ? [doc(101), doc(102)] : [doc(1), doc(2)],
    total: 2001
  }))
  const drawer = await open()
  const group = drawer.findComponent(ElCheckboxGroup)
  await button('选择当前显示文档').trigger('click')
  expect(group.props('modelValue')).toEqual(['v1', 'v2'])
  drawer.findComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  await button('选择当前显示文档').trigger('click')
  expect(group.props('modelValue')).toEqual(['v1', 'v2', 'v101', 'v102'])
  await button('取消选择当前显示文档').trigger('click')
  expect(group.props('modelValue')).toEqual(['v1', 'v2'])
  await button('确认范围并准备').trigger('click')
  await flushPromises()
  expect(mocks.prepare).toHaveBeenCalledExactlyOnceWith('kb-1', ['v1', 'v2'], expect.any(Object))
})
it('大知识库可一次选择最新 2000 项，并保留逐页调整能力', async () => {
  mocks.documents.mockImplementation(async (_id, params) => ({
    items: params.page === 2 ? [doc(101), doc(102)] : [doc(1), doc(2)],
    total: 2001
  }))
  mocks.latestSources.mockResolvedValue(Array.from({ length: 2000 }, (_, i) => 'v' + (i + 1)))
  const drawer = await open()
  const group = drawer.findComponent(ElCheckboxGroup)
  await button('选择最新 2000 项').trigger('click')
  await flushPromises()
  expect(mocks.latestSources).toHaveBeenCalledExactlyOnceWith('kb-1')
  expect(group.props('modelValue')).toHaveLength(2000)
  expect(mocks.documents.mock.calls.filter((call) => call[1]?.status === 'ready')).toHaveLength(1)
  drawer.findComponent(ElPagination).vm.$emit('current-change', 2)
  await flushPromises()
  await button('取消选择当前显示文档').trigger('click')
  expect(group.props('modelValue')).toHaveLength(1998)
  expect(group.props('modelValue')).not.toContain('v101')
  expect(mocks.prepare).not.toHaveBeenCalled()
  expect(mocks.combine).not.toHaveBeenCalled()
})
it('替换已选范围前允许取消，读取失败保留原选择', async () => {
  mocks.documents.mockResolvedValue({ items: [doc(1), doc(2)], total: 2001 })
  const drawer = await open()
  const group = drawer.findComponent(ElCheckboxGroup)
  group.vm.$emit('update:modelValue', ['v1'])
  await flushPromises()
  mocks.confirm.mockResolvedValueOnce(false)
  await button('选择最新 2000 项').trigger('click')
  await flushPromises()
  expect(mocks.latestSources).not.toHaveBeenCalled()
  mocks.latestSources.mockRejectedValueOnce(Error('读取失败'))
  await button('选择最新 2000 项').trigger('click')
  await flushPromises()
  expect(group.props('modelValue')).toEqual(['v1'])
})
it('more than 2000 selected versions are explained and cannot be submitted', async () => {
  const drawer = await open()
  drawer.findComponent(ElCheckboxGroup).vm.$emit(
    'update:modelValue',
    Array.from({ length: 2001 }, (_, i) => 'v' + i)
  )
  await flushPromises()
  expect(button('确认范围并准备').props('disabled')).toBe(true)
  expect(document.body.textContent).toContain('已选超过 2000 项')
  expect(mocks.prepare).not.toHaveBeenCalled()
})
it('an ordinary rejection preserves the selected scope for correction', async () => {
  mocks.prepare.mockRejectedValueOnce(
    new HttpClientError({ status: 422, userMessage: '文档尚未就绪' })
  )
  const drawer = await open()
  drawer.findComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['v2'])
  await flushPromises()
  await button('确认范围并准备').trigger('click')
  await flushPromises()
  expect(drawer.findComponent(ElCheckboxGroup).props('modelValue')).toEqual(['v2'])
  expect(button('确认范围并准备').props('disabled')).toBe(false)
})
it.each([
  new Error('连接中断'),
  new HttpClientError({ status: 408, userMessage: '请求超时' }),
  new HttpClientError({ status: 503, userMessage: '服务不可用' })
])(
  'keeps an uncertain scope frozen and list absence never authorizes another prepare (%s)',
  async (failure) => {
    mocks.prepare.mockRejectedValueOnce(failure)
    const drawer = await open()
    await button('确认范围并准备').trigger('click')
    await flushPromises()
    expect(drawer.findComponent(ElCheckboxGroup).props('disabled')).toBe(true)
    expect(button('确认范围并准备').props('disabled')).toBe(true)
    await button('核对修订列表').trigger('click')
    await flushPromises()
    expect(button('确认范围并准备').props('disabled')).toBe(true)
    expect(drawer.findComponent(ElCheckboxGroup).props('modelValue')).toEqual(['v1', 'v2'])
    expect(mocks.prepare).toHaveBeenCalledTimes(1)
  }
)
it('a successful prepare with failed list refresh recovers by GET only', async () => {
  await open()
  mocks.revisions.mockRejectedValueOnce(new Error('列表读取失败'))
  await button('确认范围并准备').trigger('click')
  await flushPromises()
  expect(wrapper!.text()).toContain('修订已准备就绪，列表尚未刷新')
  await button('核对修订列表').trigger('click')
  await flushPromises()
  expect(mocks.prepare).toHaveBeenCalledTimes(1)
  expect(wrapper!.text()).not.toContain('修订已准备就绪，列表尚未刷新')
})
it('an existing draft enters the same explicit scope flow', async () => {
  mocks.revisions.mockResolvedValue({ items: [{ ...ready, status: 'draft', source_versions: [] }] })
  await open()
  await button('关闭').trigger('click')
  await flushPromises()
  await button('检查并准备发布').trigger('click')
  await flushPromises()
  expect(mocks.prepare).not.toHaveBeenCalled()
  await button('确认范围并准备').trigger('click')
  await flushPromises()
  expect(mocks.prepare).toHaveBeenCalledTimes(1)
})
it('upload success returns to documents page one; failed GET preserves old rows/page and retry never uploads again', async () => {
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
  const pagination = wrapper.findAllComponents(ElPagination)[0]!
  mocks.documents.mockResolvedValueOnce({ items: [doc(21)], total: 101 })
  pagination.vm.$emit('current-change', 2)
  await flushPromises()
  wrapper.findAllComponents(ElTabs)[0]!.vm.$emit('update:modelValue', 'search')
  await flushPromises()
  mocks.documents.mockRejectedValueOnce(new Error('上传后列表读取失败'))
  const file = new File(['内容'], '新文档.txt', { type: 'text/plain' })
  wrapper.findComponent(ElUpload).props('onChange')!(
    { name: file.name, status: 'ready', uid: 1, raw: Object.assign(file, { uid: 1 }) },
    []
  )
  await flushPromises()
  expect(wrapper.findAllComponents(ElTabs)[0]!.props('modelValue')).toBe('documents')
  expect(pagination.props('currentPage')).toBe(2)
  expect(wrapper.text()).toContain('文档21')
  expect(wrapper.text()).toContain('文件已上传，最新文档列表尚未读取')
  mocks.documents.mockResolvedValueOnce({ items: [doc(101)], total: 102 })
  await button('读取上传后的文档').trigger('click')
  await flushPromises()
  expect(pagination.props('currentPage')).toBe(1)
  expect(wrapper.text()).toContain('文档101')
  expect(mocks.upload).toHaveBeenCalledTimes(1)
})

it('requires both create and update permissions before exposing the preparation action', async () => {
  const permissions = ref(['foundation.knowledge.create'])
  wrapper = mount(KnowledgeManagePage, {
    attachTo: document.body,
    global: { directives: { permission: createPermissionDirective(() => permissions) } }
  })
  await flushPromises()
  expect(
    [...document.querySelectorAll('button')].filter(
      (item) => item.textContent?.trim() === '准备知识发布'
    )
  ).toHaveLength(0)
  permissions.value = ['foundation.knowledge.update']
  await flushPromises()
  expect(
    [...document.querySelectorAll('button')].filter(
      (item) => item.textContent?.trim() === '准备知识发布'
    )
  ).toHaveLength(0)
  permissions.value = ['foundation.knowledge.create', 'foundation.knowledge.update']
  await flushPromises()
  expect(
    [...document.querySelectorAll('button')].filter(
      (item) => item.textContent?.trim() === '准备知识发布'
    ).length
  ).toBeGreaterThan(0)
})
it('publishing remains a separate confirmed action after preparing', async () => {
  mocks.revisions.mockResolvedValue({ items: [ready] })
  await open()
  await button('关闭').trigger('click')
  await flushPromises()
  mocks.confirm.mockResolvedValueOnce(false)
  await button('发布').trigger('click')
  await flushPromises()
  expect(mocks.publish).not.toHaveBeenCalled()
  await button('发布').trigger('click')
  await flushPromises()
  expect(mocks.publish).toHaveBeenCalledExactlyOnceWith('kb-1', 1)
})

it('economical knowledge preparation declares only its actual keyword retrieval capability', async () => {
  mocks.base.mockResolvedValue({
    id: 'kb-1',
    name: '经济库',
    indexing_mode: 'economical',
    embedding_model_id: null
  })
  await open()
  await button('确认范围并准备').trigger('click')
  await flushPromises()
  expect(mocks.prepare).toHaveBeenCalledExactlyOnceWith(
    'kb-1',
    ['v1', 'v2'],
    expect.objectContaining({ allowed_operators: ['KEYWORD'] })
  )
})

it('重新进入知识库自动只读核对并恢复原发布结果', async () => {
  const request = '12345678-1234-4234-8234-123456789abc'
  const key = `skillforge.knowledge-publication.${JSON.stringify(['tenant', 'user', 'kb-1'])}`
  sessionStorage.setItem(
    key,
    JSON.stringify({
      requestId: request,
      kbId: 'kb-1',
      sources: ['v1', 'v2'],
      policy: { allowed_operators: ['KEYWORD'] }
    })
  )
  mocks.result.mockResolvedValueOnce({
    request_id: request,
    status: 'SUCCEEDED',
    revision: { ...ready, kb_id: 'kb-1', status: 'published', capability_version_id: 'cap-1' }
  })
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
  expect(mocks.result).toHaveBeenCalledExactlyOnceWith(request)
  expect(sessionStorage.getItem(key)).toBeNull()
  expect(document.body.textContent).toContain('知识发布已确认：修订 1')
  expect(mocks.combine).not.toHaveBeenCalled()
  expect(mocks.prepare).not.toHaveBeenCalled()
  expect(mocks.publish).not.toHaveBeenCalled()
})

it('自动核对发布失败时保留原请求，刷新页面内列表不重复查询', async () => {
  const request = '12345678-1234-4234-8234-123456789abc'
  const key = `skillforge.knowledge-publication.${JSON.stringify(['tenant', 'user', 'kb-1'])}`
  const pending = JSON.stringify({
    requestId: request,
    kbId: 'kb-1',
    sources: ['v1', 'v2'],
    policy: { allowed_operators: ['KEYWORD'] }
  })
  sessionStorage.setItem(key, pending)
  mocks.result.mockRejectedValueOnce(new Error('回执暂不可用'))
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
  expect(sessionStorage.getItem(key)).toBe(pending)
  expect(document.body.textContent).toContain(request)
  await button('刷新').trigger('click')
  await flushPromises()
  expect(mocks.result).toHaveBeenCalledTimes(1)
  expect(mocks.combine).not.toHaveBeenCalled()
})

it('同一知识库同时存在上传和发布待核对时依次只读核对', async () => {
  const owner = JSON.stringify(['tenant', 'user', 'kb-1'])
  const publicationId = '12345678-1234-4234-8234-123456789abc'
  const uploadId = '22345678-1234-4234-8234-123456789abc'
  sessionStorage.setItem(
    `skillforge.knowledge-publication.${owner}`,
    JSON.stringify({
      requestId: publicationId,
      kbId: 'kb-1',
      sources: ['v1', 'v2'],
      policy: { allowed_operators: ['KEYWORD'] }
    })
  )
  sessionStorage.setItem(
    `skillforge.knowledge-upload.${owner}`,
    JSON.stringify({ name: '文档.txt', size: 4, requestId: uploadId })
  )
  mocks.result.mockResolvedValueOnce({
    request_id: publicationId,
    status: 'SUCCEEDED',
    revision: { ...ready, kb_id: 'kb-1', status: 'published', capability_version_id: 'cap-1' }
  })
  mocks.uploadResult.mockResolvedValueOnce({ request_id: uploadId, status: 'PROCESSING' })
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
  expect(mocks.result).toHaveBeenCalledExactlyOnceWith(publicationId)
  expect(mocks.uploadResult).toHaveBeenCalledExactlyOnceWith('kb-1', uploadId)
  expect(sessionStorage.getItem(`skillforge.knowledge-publication.${owner}`)).toBeNull()
  expect(sessionStorage.getItem(`skillforge.knowledge-upload.${owner}`)).not.toBeNull()
  expect(mocks.combine).not.toHaveBeenCalled()
  expect(mocks.upload).not.toHaveBeenCalled()
})

it('范围确认后一次组合发布并显示精确回执', async () => {
  mocks.combine.mockImplementation(async (request_id: string) => ({
    request_id,
    status: 'SUCCEEDED',
    revision: { ...ready, kb_id: 'kb-1', status: 'published', capability_version_id: 'cap-1' }
  }))
  await open()
  await button('确认范围并发布').trigger('click')
  await flushPromises()
  expect(mocks.confirm).toHaveBeenCalledWith(expect.objectContaining({ confirmText: '确认发布' }))
  expect(mocks.combine).toHaveBeenCalledExactlyOnceWith(
    expect.any(String),
    'kb-1',
    ['v1', 'v2'],
    expect.objectContaining({ allowed_operators: ['VECTOR', 'KEYWORD'] })
  )
  expect(mocks.prepare).not.toHaveBeenCalled()
  expect(mocks.publish).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('知识发布已确认：修订 1')
})
it('原操作 UNKNOWN 按知识库和成员恢复，原范围与策略不被新页面覆盖', async () => {
  mocks.base.mockResolvedValue({ id: 'kb-1', name: '知识库', indexing_mode: 'economical' })
  mocks.combine.mockRejectedValueOnce(Error('断连'))
  await open()
  await button('确认范围并发布').trigger('click')
  await flushPromises()
  const request = mocks.combine.mock.calls[0]![0]
  const entry = JSON.parse(
    sessionStorage.getItem(
      `skillforge.knowledge-publication.${JSON.stringify(['tenant', 'user', 'kb-1'])}`
    )!
  )
  expect(entry).toMatchObject({
    requestId: request,
    kbId: 'kb-1',
    sources: ['v1', 'v2'],
    policy: { allowed_operators: ['KEYWORD'] }
  })
  wrapper!.unmount()
  mocks.kb = 'kb-2'
  await open()
  expect(document.body.textContent).not.toContain(request)
  wrapper!.unmount()
  mocks.kb = 'kb-1'
  mocks.member = 'other'
  await open()
  expect(document.body.textContent).not.toContain(request)
  wrapper!.unmount()
  mocks.member = 'user'
  await open()
  expect(document.body.textContent).toContain(request)
  mocks.result.mockResolvedValueOnce(undefined)
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(mocks.result).toHaveBeenCalledWith(request)
  expect(document.body.textContent).toContain(request)
  expect(mocks.combine).toHaveBeenCalledTimes(1)
})
it('首次派发前明确拒绝解除锁，原 UNKNOWN 查询拒绝不解除', async () => {
  await open()
  mocks.combine.mockRejectedValueOnce(
    new HttpClientError({
      status: 403,
      code: 'KNOWLEDGE_PUBLISH_PRE_DISPATCH_REJECTED',
      userMessage: '拒绝'
    })
  )
  await button('确认范围并发布').trigger('click')
  await flushPromises()
  expect(document.body.textContent).not.toContain('发布结果未知')
  mocks.combine.mockRejectedValueOnce(Error('断连'))
  await button('确认范围并发布').trigger('click')
  await flushPromises()
  const request = mocks.combine.mock.calls[1]![0]
  mocks.result.mockRejectedValueOnce(
    new HttpClientError({
      status: 403,
      code: 'KNOWLEDGE_PUBLISH_PRE_DISPATCH_REJECTED',
      userMessage: '拒绝'
    })
  )
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain(request)
})
it('浏览器存储不可用时不派发组合请求', async () => {
  await open()
  const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw Error('blocked')
  })
  await button('确认范围并发布').trigger('click')
  await flushPromises()
  expect(mocks.combine).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('未发送发布请求')
  spy.mockRestore()
})

it('无发布或回执读取权限时不提供组合发布，独立准备仍保留', async () => {
  mocks.permissions = mocks.permissions.filter(
    (permission) => permission !== 'foundation.knowledge.read'
  )
  await open()
  expect(
    wrapper!.findAllComponents(ElButton).some((item) => item.text() === '确认范围并发布')
  ).toBe(false)
  expect(button('确认范围并准备').props('disabled')).toBe(false)
  expect(mocks.combine).not.toHaveBeenCalled()
})
it('取消确认不执行写入，回执处理中或范围不匹配均保留 UNKNOWN', async () => {
  mocks.confirm.mockResolvedValueOnce(false)
  await open()
  await button('确认范围并发布').trigger('click')
  await flushPromises()
  expect(mocks.combine).not.toHaveBeenCalled()
  mocks.combine.mockRejectedValueOnce(Error('断连'))
  await button('确认范围并发布').trigger('click')
  await flushPromises()
  const request = mocks.combine.mock.calls[0]![0]
  mocks.result.mockResolvedValueOnce({ request_id: request, status: 'PROCESSING' })
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('原请求尚无最终结果')
  mocks.result.mockResolvedValueOnce({
    request_id: request,
    status: 'SUCCEEDED',
    revision: {
      ...ready,
      kb_id: 'kb-1',
      status: 'published',
      source_versions: ['other'],
      capability_version_id: 'cap-1'
    }
  })
  await button('核对原操作').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain(request)
  expect(mocks.combine).toHaveBeenCalledTimes(1)
})

it('同一页面切换知识库时不串用原范围，切回可恢复原操作', async () => {
  mocks.combine.mockRejectedValueOnce(Error('断连'))
  await open()
  await button('确认范围并发布').trigger('click')
  await flushPromises()
  const request = mocks.combine.mock.calls[0]![0]
  routeState.params.id = 'kb-2'
  await flushPromises()
  expect(document.body.textContent).not.toContain(request)
  expect(
    wrapper!
      .findAllComponents(ElDrawer)
      .find((item) => item.props('title') === '准备知识发布')!
      .props('modelValue')
  ).toBe(false)
  routeState.params.id = 'kb-1'
  await flushPromises()
  expect(document.body.textContent).toContain(request)
  expect(mocks.combine).toHaveBeenCalledTimes(1)
})

it('大于 JS 安全整数的修订身份按字符串精确匹配当前发布', async () => {
  mocks.base.mockResolvedValue({
    id: 'kb-1',
    name: '知识库',
    indexing_mode: 'economical',
    active_revision_id: '9223372036854775807'
  })
  mocks.revisions.mockResolvedValue({
    items: [
      { ...ready, id: '9223372036854775807', kb_id: 'kb-1', status: 'published', revision: 8 }
    ]
  })
  wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
  await flushPromises()
  expect(document.body.textContent).toContain('当前发布 · 修订 8')
})

it('独立准备 UNKNOWN 切换知识库后不串用，切回保留原范围', async () => {
  mocks.prepare.mockRejectedValueOnce(Error('断连'))
  const drawer = await open()
  drawer.findComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['v2'])
  await flushPromises()
  await button('确认范围并准备').trigger('click')
  await flushPromises()
  expect(document.body.textContent).toContain('准备结果尚未确认')
  routeState.params.id = 'kb-2'
  await flushPromises()
  expect(document.body.textContent).not.toContain('准备结果尚未确认')
  routeState.params.id = 'kb-1'
  await flushPromises()
  expect(document.body.textContent).toContain('准备结果尚未确认')
  await button('查看原文档范围').trigger('click')
  await flushPromises()
  expect(drawer.findComponent(ElCheckboxGroup).props('modelValue')).toEqual(['v2'])
  expect(drawer.findComponent(ElCheckboxGroup).props('disabled')).toBe(true)
  expect(mocks.prepare).toHaveBeenCalledTimes(1)
})
