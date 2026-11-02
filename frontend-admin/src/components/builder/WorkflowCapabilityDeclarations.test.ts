import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElButton, ElFormItem, ElSelect } from 'element-plus'
import WorkflowCapabilityDeclarations from './WorkflowCapabilityDeclarations.vue'

const mocks = vi.hoisted(() => ({ definitions: vi.fn(), error: vi.fn(), info: vi.fn() }))
vi.mock('../../api', () => ({ api: { capabilities: { definitions: mocks.definitions } } }))
vi.mock('@skillforge/shared/feedback', () => ({
  message: { error: mocks.error, info: mocks.info }
}))

const tool = {
  id: '10',
  capabilityType: 'TOOL',
  capabilityKey: 'orders',
  name: '查询订单',
  defaultVersionId: '11'
}
function openSelector(wrapper: ReturnType<typeof mount>) {
  return wrapper
    .findAllComponents(ElFormItem)
    .find((field) => field.props('label') === '添加已发布工具')!
    .getComponent(ElSelect)
}
describe('工作流工具快捷声明', () => {
  it('已有槽或工具名称时生成不冲突的引用，并保留原声明', async () => {
    mocks.definitions.mockResolvedValueOnce([tool])
    const wrapper = mount(WorkflowCapabilityDeclarations, {
      props: {
        requirements: JSON.stringify([{ slot: 'tool_1', type: 'tool', capability_id: 'old' }]),
        toolDeclarations: JSON.stringify([{ name: 'tool_2', requirement: 'tool_1' }])
      }
    })
    const select = openSelector(wrapper)
    select.vm.$emit('visible-change', true)
    await flushPromises()
    select.vm.$emit('change', '10')
    expect(JSON.parse(wrapper.emitted('update:requirements')![0]![0] as string)).toEqual([
      expect.objectContaining({ slot: 'tool_1' }),
      expect.objectContaining({ slot: 'tool_3', capability_id: 'orders' })
    ])
    expect(JSON.parse(wrapper.emitted('update:toolDeclarations')![0]![0] as string)).toEqual([
      expect.objectContaining({ name: 'tool_2' }),
      expect.objectContaining({ name: 'tool_3', requirement: 'tool_3' })
    ])
    expect(wrapper.emitted('tool-added')![0]).toEqual(['tool_3'])
    wrapper.unmount()
  })
  it('目录读取失败时不添加声明，重新打开可重试', async () => {
    mocks.definitions.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([tool])
    const wrapper = mount(WorkflowCapabilityDeclarations)
    const select = openSelector(wrapper)
    select.vm.$emit('visible-change', true)
    await flushPromises()
    expect(mocks.error).toHaveBeenCalledWith('工具目录读取失败，请重新打开选择框重试')
    select.vm.$emit('change', '10')
    expect(wrapper.emitted('tool-added')).toBeUndefined()
    select.vm.$emit('visible-change', true)
    await flushPromises()
    select.vm.$emit('change', '10')
    expect(wrapper.emitted('tool-added')![0]).toEqual(['tool_1'])
    wrapper.unmount()
  })
  it('选择知识来源时避让已有槽，仅生成知识声明', async () => {
    mocks.definitions.mockResolvedValueOnce([
      {
        id: '20',
        capabilityType: 'KNOWLEDGE_SOURCE',
        capabilityKey: 'knowledge-base:8',
        name: '订单知识',
        defaultVersionId: '21'
      },
      {
        id: '22',
        capabilityType: 'KNOWLEDGE_SOURCE',
        capabilityKey: 'unpublished',
        name: '未发布',
        defaultVersionId: null
      }
    ])
    const wrapper = mount(WorkflowCapabilityDeclarations, {
      props: {
        requirements: JSON.stringify([
          { slot: 'knowledge_1', type: 'knowledge_source', capability_id: 'old' }
        ])
      }
    })
    const select = wrapper
      .findAllComponents(ElFormItem)
      .find((field) => field.props('label') === '添加已发布知识来源')!
      .getComponent(ElSelect)
    select.vm.$emit('visible-change', true)
    await flushPromises()
    expect(mocks.definitions).toHaveBeenLastCalledWith('KNOWLEDGE_SOURCE', 'PUBLISHED')
    select.vm.$emit('change', '22')
    expect(wrapper.emitted('knowledge-added')).toBeUndefined()
    select.vm.$emit('change', '20')
    expect(JSON.parse(wrapper.emitted('update:requirements')![0]![0] as string)).toEqual([
      expect.objectContaining({ slot: 'knowledge_1' }),
      expect.objectContaining({
        slot: 'knowledge_2',
        type: 'knowledge_source',
        capability_id: 'knowledge-base:8',
        label: '订单知识',
        required: true
      })
    ])
    expect(wrapper.emitted('knowledge-added')![0]).toEqual(['knowledge_2'])
    expect(wrapper.emitted('update:toolDeclarations')).toBeUndefined()
    wrapper.unmount()
  })
  it('复用唯一工具声明及原版本约束', async () => {
    mocks.definitions.mockResolvedValueOnce([tool])
    const wrapper = mount(WorkflowCapabilityDeclarations, {
      props: {
        requirements: JSON.stringify([
          { slot: 'orders_slot', type: 'tool', capability_id: 'orders', version_constraint: '2.x' }
        ]),
        toolDeclarations: JSON.stringify([{ name: 'lookup_orders', requirement: 'orders_slot' }])
      }
    })
    const select = openSelector(wrapper)
    select.vm.$emit('visible-change', true)
    await flushPromises()
    select.vm.$emit('change', '10')
    expect(wrapper.emitted('tool-added')![0]).toEqual(['lookup_orders'])
    expect(wrapper.emitted('update:requirements')).toBeUndefined()
    expect(wrapper.emitted('update:toolDeclarations')).toBeUndefined()
    wrapper.unmount()
  })
  it('工具槽已存在但尚无声明时只补全工具声明', async () => {
    mocks.definitions.mockResolvedValueOnce([tool])
    const wrapper = mount(WorkflowCapabilityDeclarations, {
      props: {
        requirements: JSON.stringify([
          { slot: 'orders_slot', type: 'tool', capability_id: 'orders', version_constraint: '2.x' }
        ])
      }
    })
    const select = openSelector(wrapper)
    select.vm.$emit('visible-change', true)
    await flushPromises()
    select.vm.$emit('change', '10')
    expect(wrapper.emitted('update:requirements')).toBeUndefined()
    expect(JSON.parse(wrapper.emitted('update:toolDeclarations')![0]![0] as string)).toEqual([
      expect.objectContaining({ name: 'tool_1', requirement: 'orders_slot' })
    ])
    wrapper.unmount()
  })
  it('知识来源已有唯一槽时复用，有多个槽时要求明确选择', async () => {
    const knowledge = {
      id: '20',
      capabilityType: 'KNOWLEDGE_SOURCE',
      capabilityKey: 'knowledge-base:8',
      name: '订单知识',
      defaultVersionId: '21'
    }
    mocks.definitions.mockResolvedValue([knowledge])
    const slot = {
      slot: 'known',
      type: 'knowledge_source',
      capability_id: 'knowledge-base:8',
      version_constraint: '2.x'
    }
    const wrapper = mount(WorkflowCapabilityDeclarations, {
      props: { requirements: JSON.stringify([slot]) }
    })
    const select = wrapper
      .findAllComponents(ElFormItem)
      .find((field) => field.props('label') === '添加已发布知识来源')!
      .getComponent(ElSelect)
    select.vm.$emit('visible-change', true)
    await flushPromises()
    select.vm.$emit('change', '20')
    expect(wrapper.emitted('knowledge-added')![0]).toEqual(['known'])
    expect(wrapper.emitted('update:requirements')).toBeUndefined()
    await wrapper.setProps({ requirements: JSON.stringify([slot, { ...slot, slot: 'other' }]) })
    select.vm.$emit('change', '20')
    expect(wrapper.emitted('knowledge-added')).toHaveLength(1)
    expect(mocks.info).toHaveBeenLastCalledWith(
      '该知识来源已有多个或不完整的能力槽，请在已有声明中选择或修正。'
    )
    wrapper.unmount()
  })
  it('同一个工具槽存在多个别名时不猜测也不新增声明', async () => {
    mocks.definitions.mockResolvedValueOnce([tool])
    const wrapper = mount(WorkflowCapabilityDeclarations, {
      props: {
        requirements: JSON.stringify([
          { slot: 'orders_slot', type: 'tool', capability_id: 'orders', version_constraint: '2.x' }
        ]),
        toolDeclarations: JSON.stringify([
          { name: 'first', requirement: 'orders_slot' },
          { name: 'second', requirement: 'orders_slot' }
        ])
      }
    })
    const select = openSelector(wrapper)
    select.vm.$emit('visible-change', true)
    await flushPromises()
    select.vm.$emit('change', '10')
    expect(wrapper.emitted('tool-added')).toBeUndefined()
    expect(wrapper.emitted('update:requirements')).toBeUndefined()
    expect(wrapper.emitted('update:toolDeclarations')).toBeUndefined()
    expect(mocks.info).toHaveBeenLastCalledWith(
      '该工具已有多个或不完整的工具声明，请在已有声明中选择或修正。'
    )
    wrapper.unmount()
  })
  it('节点配置直接显示快捷选择，高级声明按需展开，格式错误时仍能修正 JSON', async () => {
    const wrapper = mount(WorkflowCapabilityDeclarations, {
      props: { quickType: 'TOOL', requirements: '{' }
    })
    expect(wrapper.text()).toContain('能力槽或工具声明格式不正确')
    expect(
      wrapper.findAllComponents(ElFormItem).some((field) => field.props('label') === '能力槽 JSON')
    ).toBe(false)
    const advanced = wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text() === '高级声明配置')!
    await advanced.trigger('click')
    expect(
      wrapper.findAllComponents(ElFormItem).some((field) => field.props('label') === '能力槽 JSON')
    ).toBe(true)
    await wrapper.setProps({ requirements: '[]' })
    expect(
      wrapper
        .findAllComponents(ElFormItem)
        .some((field) => field.props('label') === '添加已发布工具')
    ).toBe(true)
    wrapper.unmount()
  })
})
