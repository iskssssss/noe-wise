import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElCheckbox, ElSelect } from 'element-plus'
import JsonSchemaFields from './JsonSchemaFields.vue'

vi.mock('./CodeEditor.vue', () => ({
  default: {
    props: ['modelValue', 'label', 'readonly'],
    emits: ['update:modelValue'],
    template:
      '<textarea :aria-label="label" :readonly="readonly" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  }
}))
let wrapper: VueWrapper
afterEach(() => wrapper?.unmount())
function open(schema: unknown, readonly = false) {
  wrapper = mount(JsonSchemaFields, {
    props: {
      modelValue: typeof schema === 'string' ? schema : JSON.stringify(schema),
      label: '输入约束',
      readonly,
      'onUpdate:modelValue': (value: string) => {
        void wrapper.setProps({ modelValue: value })
      }
    }
  })
  return wrapper
}
function value() {
  return JSON.parse(wrapper.props('modelValue'))
}
function row(name: string) {
  return wrapper.findAll('.schema-field').find((field) => field.get('strong').text() === name)!
}
async function click(text: string) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === text)!
    .trigger('click')
  await flushPromises()
}

describe('渐进式 JSON Schema 字段编辑', () => {
  it('枚举、常量、默认值或额外约束锁定类型，同时允许编辑说明名称和必填', async () => {
    const schema = {
      type: 'object',
      properties: {
        choice: { type: 'string', enum: ['a'] },
        fixed: { type: 'string', const: 'b' },
        count: { type: 'integer', default: 1 },
        limited: { type: 'string', minLength: 2 }
      }
    }
    open(schema)
    for (const name of Object.keys(schema.properties)) {
      const select = row(name).getComponent(ElSelect)
      expect(select.props('disabled')).toBe(true)
      expect(row(name).text()).toContain('修改类型时请在原始 JSON 中一并调整')
      select.vm.$emit('update:modelValue', name === 'count' ? 'string' : 'integer')
    }
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(value()).toEqual(schema)
    await row('choice').get('textarea').setValue('仅可选择a')
    row('choice').getComponent(ElCheckbox).vm.$emit('update:modelValue', true)
    await flushPromises()
    await row('choice').get('input').setValue('selection')
    expect(value().properties.selection).toEqual({
      type: 'string',
      enum: ['a'],
      description: '仅可选择a'
    })
    expect(value().required).toEqual(['selection'])
    expect(value().properties.fixed).toEqual(schema.properties.fixed)
    expect(value().properties.count).toEqual(schema.properties.count)
  })
  it('只补丁普通字段，未知根/字段关键字、枚举与复杂嵌套结构完整保留', async () => {
    const nested = {
      type: 'array',
      items: { anyOf: [{ $ref: '#/$defs/record' }, { type: 'null' }] },
      'x-custom': { depth: 2 }
    }
    const schema = {
      type: 'object',
      title: '业务契约',
      additionalProperties: false,
      $defs: { record: { type: 'object', properties: { count: { type: 'integer', minimum: 1 } } } },
      'x-root': { preserved: [1, 2] },
      properties: {
        name: {
          type: 'string',
          description: '旧说明',
          minLength: 2,
          enum: ['甲', '乙'],
          'x-field': { keep: true }
        },
        nested
      },
      required: ['name']
    }
    open(schema)
    await row('name').get('textarea').setValue('新的字段说明')
    expect(value()).toEqual({
      ...schema,
      properties: {
        ...schema.properties,
        name: { ...schema.properties.name, description: '新的字段说明' }
      }
    })
    expect(row('nested').text()).toContain('此字段包含复杂结构')
    expect(row('nested').find('input').exists()).toBe(false)
    expect(value().properties.nested).toEqual(nested)
    expect(schema.properties.name.description).toBe('旧说明')
  })

  it('字段名与必填约束同步，重名或空名称拒绝覆盖并保留旧字段', async () => {
    open({
      type: 'object',
      properties: { old: { type: 'string' }, taken: { type: 'boolean' } },
      required: ['old', 'external']
    })
    await row('old').get('input').setValue('new')
    expect(value().required).toEqual(['new', 'external'])
    expect(value().properties).toEqual({ new: { type: 'string' }, taken: { type: 'boolean' } })
    await row('new').get('input').setValue('taken')
    expect(wrapper.text()).toContain('未更名：字段名已存在')
    expect(value().properties.new).toEqual({ type: 'string' })
    expect(row('new').get<HTMLInputElement>('input').element.value).toBe('new')
    await row('new').get('input').setValue('')
    expect(wrapper.text()).toContain('字段名不能为空')
    expect(value().required).toEqual(['new', 'external'])
    row('new').getComponent(ElCheckbox).vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(value().required).toEqual(['external'])
    await row('new').get('button').trigger('click')
    expect(value().properties).not.toHaveProperty('new')
    expect(value().required).toEqual(['external'])
  })

  it.each([
    { type: ['object', 'null'], properties: { x: { type: 'string' } } },
    { type: 'object', anyOf: [{ required: ['x'] }, { required: ['y'] }] },
    { $ref: '#/$defs/record', $defs: { record: { type: 'object' } } },
    { type: 'object', dependentRequired: { a: ['b'] } },
    { type: 'object', properties: { a: true }, required: 'a' }
  ])('复杂根使用专业模式，不生成有损字段投影：%j', (schema) => {
    open(schema)
    expect(wrapper.find('textarea[aria-label="输入约束"]').exists()).toBe(true)
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '字段编辑')!
        .attributes()
    ).toHaveProperty('disabled')
    expect(value()).toEqual(schema)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('专业模式保留原文，模式切换无隐式写入，修复复杂结构后由用户切回字段编辑', async () => {
    const original =
      '{ "type": "object", "properties": { "x": {"type":"string"} }, "additionalProperties": false }'
    open(original)
    await click('原始 JSON（专业）')
    expect(wrapper.get<HTMLTextAreaElement>('textarea[aria-label="输入约束"]').element.value).toBe(
      original
    )
    await click('字段编辑')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    await click('原始 JSON（专业）')
    await wrapper.get('textarea[aria-label="输入约束"]').setValue('{')
    expect(wrapper.text()).toContain('JSON 格式不正确')
    await wrapper.get('textarea[aria-label="输入约束"]').setValue('{"type":"object"}')
    expect(wrapper.find('textarea[aria-label="输入约束"]').exists()).toBe(true)
    await click('字段编辑')
    expect(wrapper.text()).toContain('尚未定义字段')
  })

  it('添加普通字段才将空Schema声明为object，并安全处理特殊字段名', async () => {
    open({})
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    await wrapper.get('input[placeholder="新字段名"]').setValue('__proto__')
    await click('添加字段')
    expect(value()).toEqual(
      JSON.parse('{"type":"object","properties":{"__proto__":{"type":"string"}}}')
    )
    row('__proto__').getComponent(ElSelect).vm.$emit('update:modelValue', 'integer')
    await flushPromises()
    expect(value().properties.__proto__).toEqual({ type: 'integer' })
    row('__proto__').getComponent(ElCheckbox).vm.$emit('update:modelValue', true)
    await flushPromises()
    expect(value().required).toEqual(['__proto__'])
  })

  it('只读模式的字段控件及专业编辑器均不能写回', async () => {
    const schema = { type: 'object', properties: { count: { type: 'integer' } } }
    open(schema, true)
    row('count').getComponent(ElSelect).vm.$emit('update:modelValue', 'string')
    row('count').getComponent(ElCheckbox).vm.$emit('update:modelValue', true)
    await row('count').get('input').setValue('changed')
    await click('原始 JSON（专业）')
    await wrapper.get('textarea').setValue('{}')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(value()).toEqual(schema)
  })
})
