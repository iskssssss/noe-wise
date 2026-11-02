import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElOption, ElSelect } from 'element-plus'
import NodeProperties from './NodeProperties.vue'
import WorkflowVariableSelect from './WorkflowVariableSelect.vue'
import NodeArguments from './NodeArguments.vue'
import type { BuilderNode, WorkflowMeta } from '../../utils/workflow'
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: {
    props: ['modelValue', 'label', 'readonly'],
    emits: ['update:modelValue'],
    template: `<textarea :aria-label="label" :readonly="readonly" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`
  }
}))
const wrappers: VueWrapper[] = []
const node = (id: string, type = 'end'): BuilderNode => ({
  id,
  type,
  label: id,
  x: 0,
  y: 0,
  resultTo: 'state',
  assignPairs: [],
  menuActions: []
})
const meta = {
  configSchemaText: '[{"key":"locale","type":"string","label":"语言"}]',
  toolsText: '[{"name":"find_order","description":"查询订单","requirement":"orders"}]',
  capabilityRequirementsText:
    '[{"slot":"docs","type":"knowledge_source","label":"政策知识"},{"slot":"orders","type":"tool"}]'
} as WorkflowMeta
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})
function render(current: BuilderNode, extra: Record<string, unknown> = {}) {
  const wrapper = mount(NodeProperties, {
    props: {
      modelValue: current,
      nodes: [current, node('recover')],
      readonly: false,
      meta,
      ...extra
    },
    attachTo: document.body
  })
  wrappers.push(wrapper)
  return wrapper
}
function select(wrapper: VueWrapper, label: string) {
  const found = wrapper
    .findAllComponents(ElSelect)
    .find((item) => item.find(`input[aria-label="${label}"]`).exists())
  if (!found) throw Error('选择器不存在：' + label)
  return found
}
describe('工作流节点任务配置', () => {
  it.each(['tool', 'script'] as const)(
    '%s参数JSON无效时仅修正原文，恢复后按行编辑保留新增字段',
    async (type) => {
      const key = type === 'tool' ? 'argsText' : 'scriptInputText'
      const label = type === 'tool' ? '工具参数' : '脚本输入'
      const current = {
        ...node(type, type),
        tool: 'find_order',
        argsText: '',
        scriptInputText: '',
        [key]: '{"customer":"A"}'
      }
      const wrapper = render(current)
      const args = wrapper.findComponent(NodeArguments)
      const json = args.get<HTMLTextAreaElement>(`textarea[aria-label="${label} JSON"]`)
      const invalid = '{"customer":"A","order":9007199254740993'
      await json.setValue(invalid)
      await flushPromises()
      expect(current[key]).toBe(invalid)
      expect(args.find('.argument-row').exists()).toBe(false)
      expect(args.find('.argument-add').exists()).toBe(false)
      expect(args.get('details').attributes('open')).toBeDefined()
      expect(json.element.readOnly).toBe(false)
      await json.setValue(invalid + ',"extra":{"source":"原扩展"}}')
      await flushPromises()
      expect(args.findAll('.argument-row')).toHaveLength(3)
      await args.get(`input[aria-label="${label} · customer"]`).setValue('B')
      await flushPromises()
      expect(current[key]).toContain('"order": 9007199254740993')
      expect(current[key]).toContain('"extra": {"source":"原扩展"}')
      expect(JSON.parse(current[key]).customer).toBe('B')
      await wrapper.setProps({ readonly: true })
      expect(
        args.get<HTMLTextAreaElement>(`textarea[aria-label="${label} JSON"]`).element.readOnly
      ).toBe(true)
      expect(args.find('.argument-add').exists()).toBe(false)
    }
  )
  it('工具和知识只选择当前真实声明，旧引用可见且不被静默改写', async () => {
    const current = { ...node('tool', 'tool'), tool: 'old-tool' }
    const wrapper = render(current)
    const tools = select(wrapper, '调用工具')
    expect(tools.findAllComponents(ElOption).map((option) => option.props('value'))).toEqual([
      'old-tool',
      'find_order'
    ])
    tools.vm.$emit('update:modelValue', 'find_order')
    await flushPromises()
    expect(current.tool).toBe('find_order')
    const knowledgeNode = { ...node('query', 'knowledge_query'), knowledgeRequirement: 'docs' }
    await wrapper.setProps({ modelValue: knowledgeNode, nodes: [knowledgeNode] })
    expect(
      select(wrapper, '知识能力槽')
        .findAllComponents(ElOption)
        .map((option) => option.props('value'))
    ).toEqual(['docs'])
  })
  it('失败策略形成合法goto引用，问题定位展开失败处理并聚焦', async () => {
    const current = { ...node('tool', 'tool'), onError: 'goto:recover' }
    const wrapper = render(current)
    expect(select(wrapper, '工具失败策略').props('modelValue')).toBe('goto')
    expect(
      select(wrapper, '失败后跳转节点')
        .findAllComponents(ElOption)
        .map((option) => option.props('value'))
    ).toEqual(['recover'])
    expect(
      (wrapper.vm as unknown as { focusField(path: string): boolean }).focusField('on_error')
    ).toBe(true)
    await flushPromises()
    expect(wrapper.get('[data-node-field="onError"]').attributes('open')).toBeDefined()
    expect(document.activeElement?.getAttribute('aria-label')).toBe('工具失败策略')
    select(wrapper, '工具失败策略').vm.$emit('update:modelValue', 'fail')
    await flushPromises()
    expect(current.onError).toBe('fail')
    expect(wrapper.find('input[aria-label="失败后跳转节点"]').exists()).toBe(false)
  })
  it('提取字段标签写回原声明格式，结果位置保留真实集合和单层字段', async () => {
    const current = {
      ...node('extract', 'llm'),
      extractText: 'name,amount',
      resultTo: 'extracted.summary'
    }
    const wrapper = render(current)
    expect(select(wrapper, '提取字段').props('modelValue')).toEqual(['name', 'amount'])
    select(wrapper, '提取字段').vm.$emit('update:modelValue', ['order_id', 'amount'])
    await wrapper.get('input[aria-label="结果字段名"]').setValue('order')
    expect(current.extractText).toBe('order_id,amount')
    expect(current.resultTo).toBe('extracted.order')
  })
  it('脚本选择冻结资源路径，未提供资源时不宣称正文可以执行', async () => {
    const current = {
      ...node('script', 'script'),
      script: 'scripts/run.py',
      scriptInputText: '{"name":"test"}'
    }
    const wrapper = render(current, { scriptResources: ['scripts/run.py', 'scripts/report.js'] })
    expect(
      select(wrapper, '脚本资源路径')
        .findAllComponents(ElOption)
        .map((option) => option.props('value'))
    ).toEqual(['scripts/run.py', 'scripts/report.js'])
    expect(wrapper.find('textarea[aria-label="脚本"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('脚本内容在资源区编辑')
    await wrapper.setProps({ scriptResources: [] })
    expect(wrapper.text()).toContain('尚无脚本文件')
    expect(current.script).toBe('scripts/run.py')
  })
  it('尚未实现执行的历史节点只读保留，不提供可执行配置承诺', async () => {
    const current = { ...node('parallel', 'parallel'), branchesText: 'one\ntwo' }
    const wrapper = render(current, { nodes: [current, node('one'), node('two')] })
    expect(wrapper.text()).toContain('此节点类型暂不支持执行')
    expect(select(wrapper, '原并行分支').props('modelValue')).toEqual(['one', 'two'])
    expect(wrapper.get('input[aria-label="节点名称"]').attributes('disabled')).toBeDefined()
    expect(current.branchesText).toBe('one\ntwo')
  })
})

it('没有运行消费者的旧字段只读呈现并保留，结束和异步节点不伪造消息效果', async () => {
  const ending = { ...node('done', 'end'), message: '原结束消息', renderText: '{"x":1}' }
  const wrapper = render(ending)
  expect(wrapper.text()).toContain('结束节点只终止流程')
  expect(wrapper.get('textarea[aria-label="输出消息"]').attributes('disabled')).toBeDefined()
  expect(
    wrapper.get('textarea[aria-label="节点渲染扩展 JSON"]').attributes('readonly')
  ).toBeDefined()
  expect(ending.message).toBe('原结束消息')
  expect(ending.renderText).toBe('{"x":1}')
  const human = { ...node('input', 'human_input'), extractText: 'legacy_field' }
  await wrapper.setProps({ modelValue: human, nodes: [human] })
  expect(wrapper.text()).toContain('不执行模型字段提取')
  expect(select(wrapper, '提取字段').props('disabled')).toBe(true)
  expect(human.extractText).toBe('legacy_field')
  const waiting = { ...node('wait', 'async_task'), prompt: '原异步提示', onError: 'goto:recover' }
  await wrapper.setProps({ modelValue: waiting, nodes: [waiting, node('recover')] })
  expect(wrapper.get('textarea[aria-label="节点提示词"]').attributes('readonly')).toBeDefined()
  expect(wrapper.text()).toContain('只等待已有任务的回调')
  expect(select(wrapper, '节点失败策略').props('modelValue')).toBe('goto')
  expect(waiting.prompt).toBe('原异步提示')
})

it('输出消息按选区插入变量并恢复光标，重复插入和只读均遵守当前位置', async () => {
  const current = { ...node('reply', 'output'), message: '前正文后' }
  const wrapper = render(current)
  const input = wrapper.get<HTMLTextAreaElement>('textarea[aria-label="输出消息"]')
  input.element.focus()
  input.element.setSelectionRange(1, 3)
  await input.trigger('select')
  const picker = wrapper
    .findAllComponents(WorkflowVariableSelect)
    .find((item) => item.props('label') === '向输出消息插入变量')!
  picker.vm.$emit('update:modelValue', '${state.user_input}')
  await flushPromises()
  expect(current.message).toBe('前${state.user_input}后')
  expect(input.element.selectionStart).toBe(20)
  expect(document.activeElement).toBe(input.element)
  picker.vm.$emit('update:modelValue', '${user.end_user_id}')
  await flushPromises()
  expect(current.message).toBe('前${state.user_input}${user.end_user_id}后')
  await wrapper.setProps({ readonly: true })
  picker.vm.$emit('update:modelValue', '禁止')
  expect(current.message).toBe('前${state.user_input}${user.end_user_id}后')
})
it('当前节点直接预览真实嵌套输出与类型，卡片统一支持安装配置变量', () => {
  const script = {
    ...node('orders', 'script'),
    scriptOutputSchemaText: '{"type":"object","properties":{"amount":{"type":"number"}}}'
  }
  const wrapper = render(script)
  const preview = wrapper.get('[aria-label="此节点可用输出"]')
  expect(preview.text()).toContain('state.orders.amount')
  expect(preview.text()).toContain('number')
})
