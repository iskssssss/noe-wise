import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { ElRadioGroup, ElSelect } from 'element-plus'
import NodeArguments from './NodeArguments.vue'
import CardRowsEditor from './CardRowsEditor.vue'
import AssignValueEditor from './AssignValueEditor.vue'
import WorkflowVariableSelect from './WorkflowVariableSelect.vue'
import ConditionEditor from './ConditionEditor.vue'
import {
  objectEntries,
  writeObject,
  readArray,
  declaredKnowledge,
  workflowVariables,
  type WorkflowVariableOption
} from './nodeEditing'
import type { BuilderNode, WorkflowMeta } from '../../utils/workflow'
vi.mock('@skillforge/shared/components/CodeEditor.vue', () => ({
  default: {
    props: ['modelValue', 'label', 'readonly'],
    emits: ['update:modelValue'],
    template: `<textarea :aria-label="label" :readonly="readonly" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`
  }
}))
const wrappers: VueWrapper[] = []
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
const variables: WorkflowVariableOption[] = [
  { path: 'state.amount', label: '金额', type: 'number', source: '上游 · 查询' }
]
const node = (id: string, type = 'tool'): BuilderNode => ({
  id,
  type,
  label: id,
  x: 0,
  y: 0,
  resultTo: 'state',
  assignPairs: [],
  menuActions: []
})
function radio(wrapper: Pick<VueWrapper, 'findAllComponents'>, label: string) {
  const found = wrapper
    .findAllComponents(ElRadioGroup)
    .find((item) => item.attributes('aria-label') === label)
  if (!found) throw Error('单选组不存在：' + label)
  return found
}
describe('节点编辑的真实变量与原值保留', () => {
  it('修改对象一项保留未知字段、嵌套内容和精确数字词法', () => {
    const original =
      '{"name":"旧","large":9223372036854775807,"price":0.123456789123456789,"extension":{"text":"x,y:z","list":[1,2]}}'
    const updated = writeObject(original, 'name', '"新"')
    expect(updated).toContain('9223372036854775807')
    expect(updated).toContain('0.123456789123456789')
    expect(objectEntries(updated).find((item) => item.key === 'extension')?.value).toBe(
      '{"text":"x,y:z","list":[1,2]}'
    )
    expect(readArray('[{"x":"a,b"}, [1,2], 9223372036854775807]')).toEqual([
      '{"x":"a,b"}',
      '[1,2]',
      '9223372036854775807'
    ])
  })
  it('变量只含配置、真实运行标识与可达上游，排除下游和断开节点', () => {
    const first = { ...node('fetch', 'llm'), extractText: 'amount,owner', resultTo: 'extracted' }
    const current = node('current'),
      later = node('later'),
      isolated = node('isolated')
    const meta = {
      configSchemaText: '[{"key":"region","label":"地区","type":"string"}]',
      capabilityRequirementsText:
        '[{"slot":"docs","type":"knowledge_source"},{"slot":"tool","type":"tool"}]'
    } as WorkflowMeta
    const options = workflowVariables(
      [first, current, later, isolated],
      [
        { id: '1', source: 'fetch', target: 'current' },
        { id: '2', source: 'current', target: 'later' }
      ],
      'current',
      meta
    )
    expect(options.find((item) => item.path === 'config.region')?.type).toBe('string')
    expect(options.map((item) => item.path)).toContain('extracted.amount')
    expect(
      options.some(
        (item) =>
          item.path === 'state.later' ||
          item.path === 'state.isolated' ||
          item.path === 'state.current'
      )
    ).toBe(false)
    expect(declaredKnowledge(meta)).toEqual([{ slot: 'docs', label: 'docs' }])
  })
  it('有Schema时按真实字段编辑，修改文本不重写未知精确数值', async () => {
    const original = '{"name":"旧","large":9223372036854775807,"price":0.123456789123456789}'
    const wrapper = mount(NodeArguments, {
      props: {
        modelValue: original,
        label: '工具参数',
        variables,
        schema: {
          type: 'object',
          properties: { name: { type: 'string', title: '姓名' }, price: { type: 'number' } },
          required: ['name']
        },
        'onUpdate:modelValue': (value) => {
          void wrapper.setProps({ modelValue: value })
        }
      }
    })
    wrappers.push(wrapper)
    await wrapper.get('input[aria-label="工具参数 · name"]').setValue('新')
    await flushPromises()
    expect(wrapper.props('modelValue')).toContain('9223372036854775807')
    expect(wrapper.props('modelValue')).toContain('0.123456789123456789')
    await wrapper.get('input[aria-label="工具参数 · price"]').setValue('0.987654321987654321')
    await flushPromises()
    expect(wrapper.props('modelValue')).toContain('0.987654321987654321')
    expect(wrapper.text()).toContain('必填')
  })
  it('参数引用变量写完整占位符，类型由运行时保留，原对象扩展不丢失', async () => {
    const wrapper = mount(NodeArguments, {
      props: {
        modelValue: '{"amount":10,"extra":{"keep":true}}',
        label: '参数',
        variables,
        schema: { properties: { amount: { type: 'number' } } },
        'onUpdate:modelValue': (value) => {
          void wrapper.setProps({ modelValue: value })
        }
      }
    })
    wrappers.push(wrapper)
    radio(wrapper, '参数 · amount取值方式').vm.$emit('update:modelValue', 'variable')
    await flushPromises()
    const selector = wrapper
      .findAllComponents(WorkflowVariableSelect)
      .find((item) => item.props('label') === '参数 · amount变量')!
    selector.vm.$emit('update:modelValue', '${state.amount}')
    await flushPromises()
    expect(wrapper.props('modelValue')).toContain('"${state.amount}"')
    expect(wrapper.props('modelValue')).toContain('{"keep":true}')
  })
  it('卡片行只修改已编辑字段，保留开放扩展和数字原值', async () => {
    const wrapper = mount(CardRowsEditor, {
      props: {
        modelValue:
          '[{"label":"金额","value":9223372036854775807,"extension":{"color":"neutral"}}]',
        kind: 'fields',
        variables,
        'onUpdate:modelValue': (value) => {
          void wrapper.setProps({ modelValue: value })
        }
      }
    })
    wrappers.push(wrapper)
    expect(wrapper.get<HTMLInputElement>('input[aria-label="卡片字段1内容"]').element.value).toBe(
      '9223372036854775807'
    )
    await wrapper.get('input[aria-label="卡片字段1名称"]').setValue('实际金额')
    await flushPromises()
    expect(wrapper.props('modelValue')).toContain('9223372036854775807')
    expect(wrapper.props('modelValue')).toContain('{"color":"neutral"}')
    await wrapper.get('input[aria-label="卡片字段1内容"]').setValue('9223372036854775806')
    await flushPromises()
    expect(wrapper.props('modelValue')).toContain('"value": 9223372036854775806')
    expect(wrapper.props('modelValue')).not.toContain('"value": "9223372036854775806"')
  })
  it('赋值区分固定文本与变量，裸路径保留值类型', async () => {
    const wrapper = mount(AssignValueEditor, {
      props: {
        modelValue: '"初始"',
        variables,
        label: '赋值1',
        'onUpdate:modelValue': (value) => {
          void wrapper.setProps({ modelValue: value })
        }
      }
    })
    wrappers.push(wrapper)
    await wrapper.get('input[aria-label="赋值1固定值"]').setValue('state.amount')
    expect(wrapper.props('modelValue')).toBe('"state.amount"')
    radio(wrapper, '赋值1取值方式').vm.$emit('update:modelValue', 'variable')
    await flushPromises()
    wrapper.getComponent(WorkflowVariableSelect).vm.$emit('update:modelValue', 'state.amount')
    await flushPromises()
    expect(wrapper.props('modelValue')).toBe('state.amount')
  })
  it('条件规则使用后端实际比较格式，复杂表达式保持专业编辑且不重写', async () => {
    const wrapper = mount(ConditionEditor, {
      props: {
        modelValue: '${state.amount} >= 12.5000',
        variables,
        'onUpdate:modelValue': (value) => {
          void wrapper.setProps({ modelValue: value })
        }
      }
    })
    wrappers.push(wrapper)
    await wrapper.get('input[aria-label="连接条件比较值"]').setValue('15.7500')
    expect(wrapper.props('modelValue')).toBe('${state.amount} >= 15.7500')
    const complex = '${state.amount} > 1 && ${config.enabled} == true'
    await wrapper.setProps({ modelValue: complex })
    expect(wrapper.get<HTMLTextAreaElement>('textarea[aria-label="连接条件"]').element.value).toBe(
      complex
    )
    expect(wrapper.props('modelValue')).toBe(complex)
  })
})

it('菜单真实跳转形成上游范围，脚本输出类型仅读取真实Schema', () => {
  const script = {
    ...node('load', 'script'),
    scriptOutputSchemaText:
      '{"type":"object","properties":{"count":{"type":"integer","title":"数量"}}}'
  }
  const menu = {
    ...node('menu', 'menu'),
    menuActions: [{ id: 'continue', label: '继续', to: 'target' }]
  }
  const target = node('target')
  const options = workflowVariables(
    [script, menu, target],
    [{ id: 'a', source: 'load', target: 'menu' }],
    'target'
  )
  expect(options.find((item) => item.path === 'state.load.count')).toMatchObject({
    type: 'integer',
    label: '数量'
  })
  expect(options.find((item) => item.path === 'state.load')?.type).toBe('object')
})

it('卡片链接在选区替换变量并恢复中间光标', async () => {
  const wrapper = mount(CardRowsEditor, {
    attachTo: document.body,
    props: {
      modelValue: '[{"id":"go","label":"查看","type":"link","url":"https://old.example/path"}]',
      kind: 'actions',
      variables,
      'onUpdate:modelValue': (value) => {
        void wrapper.setProps({ modelValue: value })
      }
    }
  })
  wrappers.push(wrapper)
  const input = wrapper.get<HTMLInputElement>('input[aria-label="卡片操作1内容"]')
  input.element.focus()
  input.element.setSelectionRange(8, 11)
  await input.trigger('select')
  wrapper.getComponent(WorkflowVariableSelect).vm.$emit('update:modelValue', '${state.amount}')
  await flushPromises()
  expect(wrapper.props('modelValue')).toContain('https://${state.amount}.example/path')
  expect(input.element.selectionStart).toBe(23)
  expect(document.activeElement).toBe(input.element)
})

function mountAssignment(value = '"初始"') {
  const host = mount(
    defineComponent({
      components: { AssignValueEditor },
      setup() {
        return { value: ref(value), variables, locked: ref(false) }
      },
      template:
        '<AssignValueEditor v-model="value" :variables="variables" :readonly="locked" label="赋值1" />'
    })
  )
  wrappers.push(host)
  return { host, editor: host.getComponent(AssignValueEditor) }
}
it('真实v-model宿主逐字符输入表达式时保留显式选择的模式和值', async () => {
  const { editor } = mountAssignment()
  radio(editor, '赋值1取值方式').vm.$emit('update:modelValue', 'expression')
  await flushPromises()
  for (const value of ['$', '${', '${state.amount}']) {
    await editor.get('textarea[aria-label="赋值1表达式"]').setValue(value)
    await flushPromises()
    expect(radio(editor, '赋值1取值方式').props('modelValue')).toBe('expression')
    expect(
      editor.get<HTMLTextAreaElement>('textarea[aria-label="赋值1表达式"]').element.value
    ).toBe(value)
    expect(editor.props('modelValue')).toBe(value)
  }
})

it('固定数字输入中间态保留数字类型，模式和类型切换均回显原字面量', async () => {
  const { editor } = mountAssignment('1')
  const type = () => editor.findAllComponents(ElSelect)[0]!
  for (const value of ['1e', '1e-', '1e-3']) {
    await editor.get('input[aria-label="赋值1固定值"]').setValue(value)
    await flushPromises()
    expect(type().props('modelValue')).toBe('number')
    expect(editor.props('modelValue')).toBe(value)
  }
  type().vm.$emit('update:modelValue', 'boolean')
  await flushPromises()
  expect(editor.props('modelValue')).toBe('false')
  editor.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue', 'true')
  await flushPromises()
  expect(editor.props('modelValue')).toBe('true')
  type().vm.$emit('update:modelValue', 'null')
  await flushPromises()
  expect(editor.props('modelValue')).toBe('null')
})
it('真正外部换值仍推断模式和类型，不复用先前回显标记', async () => {
  const { host, editor } = mountAssignment()
  radio(editor, '赋值1取值方式').vm.$emit('update:modelValue', 'expression')
  await flushPromises()
  await editor.get('textarea[aria-label="赋值1表达式"]').setValue('$')
  await flushPromises()
  for (const [value, mode] of [
    ['state.amount', 'variable'],
    ['"外部文本"', 'fixed'],
    ['${state.amount}', 'expression'],
    ['true', 'fixed'],
    ['$', 'fixed']
  ]) {
    host.vm.value = value!
    await flushPromises()
    expect(radio(editor, '赋值1取值方式').props('modelValue')).toBe(mode)
    expect(editor.props('modelValue')).toBe(value)
  }
})
it('外部空值保留原空值行为，readonly继续禁用当前输入', async () => {
  const { host, editor } = mountAssignment('${state.amount}')
  host.vm.value = ''
  await flushPromises()
  expect(radio(editor, '赋值1取值方式').props('modelValue')).toBe('expression')
  host.vm.locked = true
  await flushPromises()
  expect(radio(editor, '赋值1取值方式').props('disabled')).toBe(true)
  expect(editor.get('textarea[aria-label="赋值1表达式"]').attributes('disabled')).toBeDefined()
})
