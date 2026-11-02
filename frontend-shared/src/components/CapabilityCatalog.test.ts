import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus, { ElSelect } from 'element-plus'
import CapabilityCatalog from './CapabilityCatalog.vue'
const wrappers: VueWrapper[] = []
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
async function render() {
  const api = {
    listDefinitions: vi.fn().mockResolvedValue([
      {
        id: '1',
        capabilityKey: 'document_search',
        name: '资料检索',
        description: '查询历史合同',
        capabilityType: 'TOOL',
        status: 'PUBLISHED'
      },
      {
        id: '2',
        capabilityKey: 'writer',
        name: '报告撰写',
        description: '生成分析报告',
        capabilityType: 'SKILL',
        status: 'DRAFT'
      }
    ])
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/capabilities', component: CapabilityCatalog }]
  })
  await router.push('/capabilities')
  const wrapper = mount(CapabilityCatalog, {
    props: {
      api: api as never,
      basePath: '/capabilities',
      skillBasePath: '/skills',
      permissions: []
    },
    global: { plugins: [router, ElementPlus] }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, api }
}
describe('能力目录任务筛选', () => {
  it('按用途关键词搜索、组合状态筛选并直接清空恢复目录', async () => {
    const { wrapper } = await render()
    await wrapper.get('input[aria-label="能力名称、标识或用途"]').setValue(' 历史合同 ')
    expect(wrapper.text()).toContain('显示 1 / 2 项能力')
    expect(wrapper.findAll('.el-table__row')).toHaveLength(1)
    const state = wrapper
      .findAllComponents(ElSelect)
      .find((item) => item.find('input[aria-label="能力状态"]').exists())!
    state.vm.$emit('update:modelValue', 'DRAFT')
    await flushPromises()
    expect(wrapper.text()).toContain('没有匹配的能力')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '清空筛选')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.el-table__row')).toHaveLength(2)
    expect(wrapper.text()).toContain('document_search')
    expect(wrapper.findAll('button').some((item) => item.text() === '新增能力')).toBe(false)
  })
  it('目录刷新失败保留已有能力及重试入口', async () => {
    const { wrapper, api } = await render()
    api.listDefinitions.mockRejectedValueOnce(new Error('目录暂时不可用'))
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '刷新')!
      .trigger('click')
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain('目录暂时不可用')
    expect(wrapper.findAll('.el-table__row')).toHaveLength(2)
    expect(wrapper.text()).toContain('资料检索')
  })
})
