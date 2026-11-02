import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElButton, ElInput, ElPagination } from 'element-plus'
import MarketplacePage from './MarketplacePage.vue'
const mocks = vi.hoisted(() => ({ search: vi.fn() }))
vi.mock('../../api', () => ({ api: { marketplace: { search: mocks.search } } }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
let wrapper: VueWrapper
const result = (name: string) => ({
  items: [{ workflow_id: name, name, category: '办公' }],
  total: 61
})
beforeEach(() => {
  vi.resetAllMocks()
  mocks.search.mockResolvedValueOnce(result('第一页工作流'))
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function open() {
  wrapper = mount(MarketplacePage, { attachTo: document.body })
  await flushPromises()
  return wrapper.getComponent(ElPagination)
}
describe('市场分页状态与已读内容一致', () => {
  it('翻页失败保留原页码和列表，再次翻页成功才展示下一页', async () => {
    const pagination = await open()
    mocks.search.mockRejectedValueOnce(new Error('读取失败'))
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(pagination.props('currentPage')).toBe(1)
    expect(wrapper.text()).toContain('第一页工作流')
    mocks.search.mockResolvedValueOnce(result('第二页工作流'))
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(pagination.props('currentPage')).toBe(2)
    expect(wrapper.text()).toContain('第二页工作流')
    expect(wrapper.text()).not.toContain('第一页工作流')
  })
  it('新搜索失败恢复原分页与已应用条件，输入保持可供修正', async () => {
    const pagination = await open()
    mocks.search.mockResolvedValueOnce(result('第二页工作流'))
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    wrapper.getComponent(ElInput).vm.$emit('update:modelValue', '新关键词')
    mocks.search.mockRejectedValueOnce(new Error('搜索失败'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(pagination.props('currentPage')).toBe(2)
    expect(wrapper.text()).toContain('第二页工作流')
    expect(wrapper.text()).toContain('筛选条件已修改')
    expect(wrapper.getComponent(ElInput).props('modelValue')).toBe('新关键词')
    mocks.search.mockResolvedValueOnce(result('原条件第三页'))
    pagination.vm.$emit('current-change', 3)
    await flushPromises()
    expect(mocks.search).toHaveBeenLastCalledWith('', undefined, undefined, 3)
    expect(
      wrapper
        .findAllComponents(ElButton)
        .find((b) => b.text() === '重置')!
        .props('disabled')
    ).toBe(false)
  })
})
