import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElDatePicker, ElInput, ElPagination } from 'element-plus'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import OpenApiDocsPage from './OpenApiDocsPage.vue'
import BillingRecordsPage from './BillingRecordsPage.vue'
import KnowledgeManagePage from './KnowledgeManagePage.vue'

const mocks = vi.hoisted(() => ({
  records: vi.fn(),
  requests: vi.fn(),
  getBase: vi.fn(),
  listDocuments: vi.fn(),
  listRevisions: vi.fn(),
  availableModels: vi.fn(),
  get: vi.fn(),
  post: vi.fn()
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ user: null, identityVersion: 0 }) }))
vi.mock('../../api', () => ({
  api: {
    billing: { records: mocks.records, requests: mocks.requests },
    knowledge: {
      getBase: mocks.getBase,
      listDocuments: mocks.listDocuments,
      listRevisions: mocks.listRevisions
    },
    settings: { availableModels: mocks.availableModels }
  },
  get: mocks.get,
  post: mocks.post
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'kb-owned' } }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: vi.fn(),
  message: { success: vi.fn() }
}))

const wrappers: VueWrapper[] = []
const rangeMethods = ['getBoundingClientRect', 'getClientRects'] as const
const addedRangeMethods: string[] = []
// jsdom 未提供 Range 布局 API；补零尺寸测量仅支持真实 CodeMirror 挂载，scope 不据此验收视觉。
beforeAll(() => {
  for (const name of rangeMethods) {
    if (typeof Range.prototype[name] === 'function') continue
    Object.defineProperty(Range.prototype, name, {
      configurable: true,
      value: name === 'getBoundingClientRect' ? () => new DOMRect() : () => []
    })
    addedRangeMethods.push(name)
  }
})
afterAll(() => {
  for (const name of addedRangeMethods) Reflect.deleteProperty(Range.prototype, name)
})
beforeEach(() => {
  vi.resetAllMocks()
  mocks.records.mockResolvedValue({ items: [], total: 41 })
  mocks.requests.mockResolvedValue({ items: [], total: 41 })
  mocks.getBase.mockResolvedValue({ id: 'kb-owned', name: '知识库文档' })
  mocks.listDocuments.mockResolvedValue({ items: [], total: 41 })
  mocks.listRevisions.mockResolvedValue({ items: [] })
  mocks.availableModels.mockResolvedValue([])
  mocks.get.mockResolvedValue({ status: 'IDLE', write_locked: false })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

/** 检查控件实际 DOM 的描述关系，而非组件 prop 或源代码里是否写过帮助。 */
function visibleHelp(control: Element) {
  const ids = (control.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean)
  expect(ids.length).toBeGreaterThan(0)
  expect(new Set(ids).size).toBe(ids.length)
  return ids
    .map((id) => {
      const targets = [...document.querySelectorAll<HTMLElement>('[id]')].filter(
        (node) => node.id === id
      )
      expect(targets).toHaveLength(1)
      const target = targets[0]!
      expect(target.textContent?.trim()).not.toBe('')
      for (let ancestor: HTMLElement | null = target; ancestor; ancestor = ancestor.parentElement) {
        expect(ancestor.hidden).toBe(false)
        expect(getComputedStyle(ancestor).display).not.toBe('none')
        expect(getComputedStyle(ancestor).visibility).not.toBe('hidden')
      }
      return target.textContent!.trim()
    })
    .join(' ')
}

describe('原 UI-G07–09 控件帮助与只读 scope', () => {
  it('开放API两个代码区域是纯只读生成示例，真实搜索与基础地址仍有帮助', async () => {
    const wrapper = mount(OpenApiDocsPage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    const editors = wrapper.findAllComponents(CodeEditor)
    expect(editors).toHaveLength(2)
    for (const editor of editors) {
      expect(editor.props('readonly')).toBe(true)
      expect(editor.get('.cm-content').attributes('contenteditable')).toBe('false')
    }
    const fields = wrapper.findAllComponents(ElInput)
    expect(fields).toHaveLength(2)
    for (const field of fields) visibleHelp(field.get('input').element)
    await fields
      .find((field) => field.props('placeholder') === 'API 基础地址')!
      .get('input')
      .setValue('https://example.test/owned-api')
    await flushPromises()
    expect(editors[0]!.props('modelValue')).toContain('https://example.test/owned-api')
    for (const editor of editors) {
      expect(editor.props('readonly')).toBe(true)
      expect(editor.get('.cm-content').attributes('contenteditable')).toBe('false')
    }
    expect(mocks.post).not.toHaveBeenCalled()
  })

  it('计费起止日期及每页条数关联可见帮助，更改条数保持回第一页的查询行为', async () => {
    const wrapper = mount(BillingRecordsPage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    const dates = wrapper.findAll<HTMLInputElement>('.el-range-input')
    expect(dates).toHaveLength(2)
    for (const date of dates) {
      expect(visibleHelp(date.element)).toContain('北京时间')
      expect(visibleHelp(date.element)).toContain('留空')
    }
    const pagination = wrapper.findComponent(ElPagination)
    expect(visibleHelp(pagination.get('input[role="combobox"]').element)).toContain('每页')
    const picker = wrapper.findComponent(ElDatePicker)
    picker.vm.$emit('update:modelValue', ['2026-10-12', '2026-10-13'])
    await flushPromises()
    for (const date of wrapper.findAll('.el-range-input')) visibleHelp(date.element)
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(mocks.records).toHaveBeenLastCalledWith(
      expect.objectContaining({ from: undefined, to: undefined, page: 2, pageSize: 20 })
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查询')!
      .trigger('click')
    await flushPromises()
    expect(mocks.records).toHaveBeenLastCalledWith(
      expect.objectContaining({ from: '2026-10-12', to: '2026-10-13', page: 1, pageSize: 20 })
    )
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    pagination.vm.$emit('size-change', 50)
    await flushPromises()
    expect(mocks.records).toHaveBeenLastCalledWith(
      expect.objectContaining({
        from: '2026-10-12',
        to: '2026-10-13',
        page: 1,
        pageSize: 50
      })
    )
    visibleHelp(pagination.get('input[role="combobox"]').element)
    expect(mocks.post).not.toHaveBeenCalled()
  })

  it('知识库详情每页条数拥有唯一非空可见帮助，分页仍读取当前知识库', async () => {
    const wrapper = mount(KnowledgeManagePage, { attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    const pagination = wrapper.findComponent(ElPagination)
    expect(visibleHelp(pagination.get('input[role="combobox"]').element)).toContain('文档')
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(mocks.listDocuments).toHaveBeenLastCalledWith('kb-owned', { page: 2, page_size: 20 })
    pagination.vm.$emit('size-change', 50)
    await flushPromises()
    expect(mocks.listDocuments).toHaveBeenLastCalledWith('kb-owned', { page: 1, page_size: 50 })
    visibleHelp(pagination.get('input[role="combobox"]').element)
    expect(mocks.post).not.toHaveBeenCalled()
  })
})
