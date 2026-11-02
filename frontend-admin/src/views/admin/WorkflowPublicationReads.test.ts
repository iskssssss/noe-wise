import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElButton, ElDrawer } from 'element-plus'
import WorkflowDetailPage from './WorkflowDetailPage.vue'
vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({
    user: { tenant_id: '7', sub: '8', permissions: ['foundation.workflows.read'] }
  })
}))

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  snapshotGet: vi.fn(),
  history: vi.fn(),
  publicationDetail: vi.fn(),
  restore: vi.fn(),
  definitions: vi.fn(),
  confirm: vi.fn()
}))
vi.mock('../../api', () => ({
  api: {
    workflowDrafts: {
      get: mocks.get,
      history: mocks.history,
      publicationDetail: mocks.publicationDetail,
      restore: mocks.restore,
      snapshot: { get: mocks.snapshotGet }
    },
    capabilities: { definitions: mocks.definitions }
  }
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { workflowId: 'flow' } }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('../../components/builder/NodeProperties.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../../components/builder/WorkflowTestPanel.vue', () => ({
  default: {
    name: 'WorkflowTestPanel',
    props: ['modelValue', 'publications'],
    template: '<div />'
  }
}))
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: { template: '<div />' }
}))
vi.mock('@skillforge/shared/feedback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@skillforge/shared/feedback')>()),
  confirm: mocks.confirm
}))

const definition = {
  version: '1.0',
  entry: 'start',
  nodes: [
    { id: 'start', type: 'start' },
    { id: 'end', type: 'end' }
  ],
  edges: [{ from: 'start', to: 'end' }],
  meta: { name: 'flow', version: '1.0.0' }
}
const draft = {
  workflowId: 'flow',
  revision: 5,
  status: 'draft',
  activeDefinitionVersionId: 33,
  problems: [],
  definition
}
const summaries = [
  {
    definitionVersionId: 44,
    definitionVersion: 2,
    current: true,
    capabilityVersionId: 77,
    createdAt: '2026-10-29T00:00:00Z'
  },
  {
    definitionVersionId: 11,
    definitionVersion: 1,
    current: false,
    capabilityVersionId: 66,
    createdAt: '2026-10-28T00:00:00Z'
  }
]
let wrapper: ReturnType<typeof mount>
function button(label: string) {
  return wrapper.findAllComponents(ElButton).find((item) => item.text() === label)!
}
async function historyTab() {
  await wrapper.get('#tab-history').trigger('click')
  await flushPromises()
}
beforeEach(async () => {
  vi.resetAllMocks()
  window.history.replaceState(null, '')
  mocks.get.mockResolvedValue(draft)
  mocks.snapshotGet.mockResolvedValue({ draftRevision: 5, layout: {}, viewport: {} })
  mocks.history.mockResolvedValue(summaries)
  mocks.confirm.mockResolvedValue(true)
  mocks.publicationDetail.mockImplementation(async (_id: string, version: number) => ({
    ...summaries.find((item) => item.definitionVersionId === version),
    workflowId: 'flow',
    definition: { ...definition, meta: { ...definition.meta, description: `版本图-${version}` } }
  }))
  wrapper = mount(WorkflowDetailPage, { attachTo: document.body })
  await flushPromises()
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})

describe('工作流发布图按实际动作读取', () => {
  it('测试版本选择和发布历史仅加载摘要，不读取任何完整图', async () => {
    await button('测试').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'WorkflowTestPanel' }).props('publications')).toEqual([
      { id: '44', label: '发布版本 2' },
      { id: '11', label: '发布版本 1' }
    ])
    await historyTab()
    expect(wrapper.text()).toContain('当前发布')
    expect(mocks.publicationDetail).not.toHaveBeenCalled()
  })

  it('查看只读选定版本，详情失败保留历史和原查看图，再次点击仅重试GET', async () => {
    await historyTab()
    const views = wrapper.findAllComponents(ElButton).filter((item) => item.text() === '查看定义')
    await views[0]!.trigger('click')
    await flushPromises()
    const drawer = wrapper
      .findAllComponents(ElDrawer)
      .find((item) => item.props('title') === '工作流发布修订 2')!
    expect(document.body.textContent).toContain('版本图-44')
    drawer.vm.$emit('update:modelValue', false)
    await flushPromises()
    mocks.publicationDetail.mockRejectedValueOnce(new Error('版本详情暂不可读'))
    await views[1]!.trigger('click')
    await flushPromises()
    expect(drawer.props('title')).toBe('工作流发布修订 2')
    expect(document.body.textContent).toContain('版本图-44')
    expect(
      wrapper.findAllComponents(ElButton).filter((item) => item.text() === '查看定义')
    ).toHaveLength(2)
    await views[1]!.trigger('click')
    await flushPromises()
    expect(mocks.publicationDetail.mock.calls).toEqual([
      ['flow', 44],
      ['flow', 11],
      ['flow', 11]
    ])
    expect(mocks.history).toHaveBeenCalledTimes(1)
    expect(mocks.restore).not.toHaveBeenCalled()
  })

  it('本地内容比较只读取动作时当前发布版本，不读取其他历史图', async () => {
    await button('比较已发布版本').trigger('click')
    await flushPromises()
    expect(mocks.publicationDetail).toHaveBeenCalledExactlyOnceWith('flow', 44)
    expect(wrapper.text()).toContain('发布修订 2')
  })

  it('恢复直接使用摘要版本与当前草稿CAS，保留原草稿直到写入成功', async () => {
    await historyTab()
    mocks.restore.mockRejectedValueOnce(new Error('恢复未成功'))
    await wrapper
      .findAllComponents(ElButton)
      .filter((item) => item.text() === '恢复为草稿')[1]!
      .trigger('click')
    await flushPromises()
    expect(mocks.restore).toHaveBeenCalledExactlyOnceWith('flow', 5, 11)
    expect(mocks.publicationDetail).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('草稿已保存')
    expect(mocks.get).toHaveBeenCalledTimes(1)
  })
})
