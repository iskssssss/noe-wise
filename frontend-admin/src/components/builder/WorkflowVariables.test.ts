import { afterEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { ElTree, ElTreeSelect } from 'element-plus'
import WorkflowVariableSelect from './WorkflowVariableSelect.vue'
import NodeParameterValue from './NodeParameterValue.vue'
import {
  customVariableError,
  insertAtSelection,
  workflowVariables,
  type WorkflowToolContract
} from './nodeEditing'
import type { BuilderNode, WorkflowMeta } from '../../utils/workflow'
const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})
const node = (id: string, type = 'script'): BuilderNode => ({
  id,
  type,
  label: id,
  x: 0,
  y: 0,
  resultTo: 'state',
  assignPairs: [],
  menuActions: []
})
const nested = {
  type: 'object',
  additionalProperties: false,
  properties: {
    order: {
      type: 'object',
      properties: {
        customer: {
          type: 'object',
          properties: { email: { type: 'string' } },
          required: ['email']
        },
        lines: {
          type: 'array',
          items: { type: 'object', properties: { title: { type: 'string' } } }
        },
        'bad.key': { type: 'string' }
      },
      required: ['customer']
    }
  },
  required: ['order']
}
const script = { ...node('orders'), scriptOutputSchemaText: JSON.stringify(nested) }
const current = node('send', 'tool')
const edges = [{ id: 'a', source: 'orders', target: 'send' }]
const contract: WorkflowToolContract = {
  inputSchema: {},
  outputSchema: nested,
  outputSchemaPath: 'structuredContent',
  versionId: '1',
  versionKey: 'v1',
  capabilityKey: 'orders',
  toolName: 'fetch',
  sensitiveFields: []
}
describe('上游输出直接选择', () => {
  it('从真实Schema递归列出对象字段、类型和可选性，数组只列整体且不伪造点字段', () => {
    const options = workflowVariables([script, current], edges, 'send')
    expect(
      options.find((option) => option.path === 'state.orders.order.customer.email')
    ).toMatchObject({
      type: 'string',
      parentPath: 'state.orders.order.customer',
      sourceNodeId: 'orders',
      availability: 'available'
    })
    expect(options.find((option) => option.path === 'state.orders.order.lines')).toMatchObject({
      type: 'array',
      availability: 'conditional'
    })
    expect(
      options.some((option) => option.path.includes('.0.') || option.path.includes('bad.key'))
    ).toBe(false)
    expect(options.some((option) => option.path === 'runtime.run_id')).toBe(false)
    expect(customVariableError('state.orders.order.lines.0.title', options)).toContain(
      '数组只能整体引用'
    )
  })
  it('分支上游标注可能没有值，边条件可以引用源节点自身结果', () => {
    const start = node('start', 'start'),
      alternate = node('alternate', 'output')
    const branched = [
      { id: 's1', source: 'start', target: 'orders' },
      { id: 's2', source: 'start', target: 'alternate' },
      ...edges,
      { id: 'b', source: 'alternate', target: 'send' }
    ]
    expect(
      workflowVariables([start, script, alternate, current], branched, 'send').find(
        (option) => option.path === 'state.orders'
      )?.availability
    ).toBe('conditional')
    expect(
      workflowVariables([script, current], edges, 'orders').some(
        (option) => option.sourceNodeId === 'orders'
      )
    ).toBe(false)
    expect(
      workflowVariables(
        [script, current],
        edges,
        'orders',
        undefined,
        {},
        { includeCurrent: true }
      ).find((option) => option.path === 'state.orders.order.customer.email')?.type
    ).toBe('string')
  })
  it('工具Schema定位structuredContent并排除脱敏字段，未知结构允许受约束的自定义路径', () => {
    const tool = { ...node('orders', 'tool'), tool: 'alias' }
    const options = workflowVariables([tool, current], edges, 'send', undefined, {
      alias: { ...contract, sensitiveFields: ['order'] }
    })
    expect(options.some((option) => option.path.startsWith('state.orders.order'))).toBe(false)
    expect(
      options.some((option) => option.path.startsWith('state.orders.structuredContent.order'))
    ).toBe(false)
    expect(customVariableError('state.orders.structuredContent.order.customer', options)).toContain(
      '脱敏'
    )
    const known = workflowVariables([tool, current], edges, 'send', undefined, { alias: contract })
    expect(
      known.find((option) => option.path === 'state.orders.structuredContent.order.customer.email')
        ?.type
    ).toBe('string')
    const unknown = workflowVariables(
      [{ ...node('orders'), scriptOutputSchemaText: '' }, current],
      edges,
      'send'
    )
    expect(customVariableError('state.orders.actual.value', unknown)).toBe('')
    expect(customVariableError('state.disconnected.value', unknown)).not.toBe('')
  })
  it('配置、真实公开输入和知识输出使用实际Runtime变量域', () => {
    const meta: WorkflowMeta = {
      name: 'variables',
      version: '1.0.0',
      displayName: '变量测试',
      description: '',
      category: 'general',
      tagsText: '',
      configSchemaText: '[]',
      capability: {
        input_schema: {
          type: 'object',
          properties: { customer: { type: 'object', properties: { name: { type: 'string' } } } }
        }
      }
    }
    const options = workflowVariables(
      [{ ...node('orders', 'knowledge_query') }, current],
      edges,
      'send',
      meta
    )
    expect(options.find((option) => option.path === 'state.ctx_customer.name')?.type).toBe('string')
    expect(options.find((option) => option.path === 'state.orders.plan.plan_id')?.type).toBe(
      'string'
    )
    expect(options.find((option) => option.path === 'state.orders.items')?.type).toBe('array')
    expect(options.some((option) => option.path.startsWith('state.orders.items.'))).toBe(false)
  })
  it('分组树保留整体对象可选并支持字段路径搜索，自定义引用遵守只读', async () => {
    const options = workflowVariables([script, current], edges, 'send')
    const wrapper = mount(WorkflowVariableSelect, {
      props: { options, label: '参数变量', template: true },
      attachTo: document.body
    })
    wrappers.push(wrapper)
    const tree = wrapper.getComponent(ElTreeSelect)
    const groups = tree.props('data') as {
      label: string
      children: { value: string; children: { value: string }[] }[]
    }[]
    expect(groups[0]?.label).toBe('orders · orders')
    expect(tree.props('defaultExpandAll')).toBe(false)
    expect(tree.props('defaultExpandedKeys')).toContain('group:orders · orders')
    expect(tree.props('defaultExpandedKeys')).toContain('state.orders.order.customer')
    expect(tree.props('defaultExpandedKeys')).not.toContain('group:运行上下文')
    expect(groups.find((group) => group.label === 'orders · orders')?.children[0]?.value).toBe(
      'state.orders'
    )
    const filter = tree.props('filterNodeMethod')!
    const treeNode = tree.getComponent(ElTree).vm.getNode('state.orders')
    const leaf = tree.getComponent(ElTree).vm.getNode('state.orders.order.customer.email')
    expect(leaf.disabled).toBe(false)
    expect(tree.getComponent(ElTree).vm.getNode('group:orders · orders').disabled).toBe(true)
    expect(
      filter(
        'customer.email',
        { searchText: 'orders state.orders.order.customer.email string' },
        treeNode
      )
    ).toBe(true)
    tree.vm.$emit('update:modelValue', 'state.orders.order.customer.email')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([
      '${state.orders.order.customer.email}'
    ])
    await wrapper.setProps({ disabled: true })
    tree.vm.$emit('update:modelValue', 'state.orders')
    expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
  })
  it('文本替换选区且连续插入保持中间光标，参数外部切换固定值退出变量模式', async () => {
    expect(insertAtSelection('前正文后', '${state.orders}', { start: 1, end: 3 })).toEqual({
      text: '前${state.orders}后',
      caret: 16
    })
    const wrapper = mount(NodeParameterValue, {
      props: { modelValue: '"${state.orders}"', variables: [], label: '值' }
    })
    wrappers.push(wrapper)
    expect(wrapper.findComponent(WorkflowVariableSelect).exists()).toBe(true)
    await wrapper.setProps({ modelValue: '42' })
    expect(wrapper.findComponent(WorkflowVariableSelect).exists()).toBe(false)
    expect(wrapper.get<HTMLInputElement>('input[aria-label="值"]').element.value).toBe('42')
  })
})

it('入口不可达和共享写入路径保守提示，类型冲突不伪确定', () => {
  const start = node('entry', 'start')
  const options = workflowVariables([start, script, current], edges, 'send', {
    entryNodeId: 'entry'
  } as WorkflowMeta)
  expect(options.find((option) => option.path === 'state.orders')?.availability).toBe('conditional')
  const a = {
    ...script,
    id: 'a',
    resultTo: 'state.shared',
    scriptOutputSchemaText: '{"type":"number"}'
  }
  const b = {
    ...script,
    id: 'b',
    resultTo: 'state.shared',
    scriptOutputSchemaText: '{"type":"string"}'
  }
  const shared = workflowVariables(
    [a, b, current],
    [
      { id: 'a', source: 'a', target: 'b' },
      { id: 'b', source: 'b', target: 'send' }
    ],
    'send'
  ).find((option) => option.path === 'state.shared')
  expect(shared).toMatchObject({
    type: 'unknown',
    availability: 'conditional',
    source: '多个上游节点'
  })
  expect(shared?.description).toContain('最后一次写入')
})
it('已选条件字段在收起后持续提示，已知参数类型不匹配和未知类型有可见说明', async () => {
  const options = [
    {
      path: 'state.amount',
      type: 'string',
      label: '金额',
      source: '查询',
      availability: 'conditional' as const
    }
  ]
  const wrapper = mount(NodeParameterValue, {
    props: {
      modelValue: '"${state.amount}"',
      schema: { type: 'number' },
      variables: options,
      label: '金额'
    }
  })
  wrappers.push(wrapper)
  expect(wrapper.text()).toContain('此引用可能没有值')
  expect(wrapper.text()).toContain('类型可能不匹配：参数要求 number，变量声明为 string')
  await wrapper.setProps({ variables: [{ ...options[0]!, type: 'unknown' }] })
  expect(wrapper.text()).toContain('变量类型尚未确定')
  await wrapper.setProps({ variables: [] })
  expect(wrapper.text()).toContain('未找到此引用的可达来源')
})

it('局部Schema引用、组合结构和可空类型使用真实字段，并终止递归引用展开', () => {
  const schema = {
    type: 'object',
    $defs: { customer: { type: 'object', properties: { email: { type: ['string', 'null'] } } } },
    properties: {
      customer: { $ref: '#/$defs/customer' },
      result: {
        anyOf: [
          { type: 'object', properties: { id: { type: 'integer' } }, required: ['id'] },
          { type: 'null' }
        ]
      },
      recursive: { $ref: '#' }
    }
  }
  const options = workflowVariables(
    [{ ...script, scriptOutputSchemaText: JSON.stringify(schema) }, current],
    edges,
    'send'
  )
  expect(options.find((option) => option.path === 'state.orders.customer.email')?.type).toBe(
    'string | null'
  )
  expect(options.find((option) => option.path === 'state.orders.result.id')).toMatchObject({
    type: 'integer',
    availability: 'conditional'
  })
  expect(options.length).toBeLessThan(30)
})
