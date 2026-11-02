import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElInputNumber } from 'element-plus'
import ToolInputForm from './ToolInputForm.vue'
import type { RuntimePendingInteraction } from '../api'
const pending: RuntimePendingInteraction = {
  execution_id: 'run',
  run_revision: '9007199254740993',
  interaction_type: 'tool_input',
  tool_wait: { action_id: 'action', generation: '2' },
  card: {
    card_id: 'tool-input-card',
    type: 'tool_input',
    title: '确认参数',
    tool_wait: { action_id: 'action', generation: '2' },
    requests: [
      {
        id: 'r',
        message: '请填写参数',
        requested_schema: {
          type: 'object',
          required: ['count', 'confirmed'],
          properties: {
            count: { type: 'integer', title: '数量', minimum: 0, default: 0 },
            confirmed: { type: 'boolean', title: '已确认', default: false },
            optional: { type: 'boolean', title: '可选项' }
          }
        }
      }
    ]
  }
}
function response(wrapper: ReturnType<typeof mount>) {
  const value = wrapper.emitted('respond')![0]![0] as {
    run_revision: string
    tool_input: { wait: object; responses: Record<string, { result: { canonical_json: string } }> }
  }
  return { value, body: JSON.parse(value.tool_input.responses.r.result.canonical_json) }
}
describe('机构工具参数表单', () => {
  it('采用已声明默认值，保留false和0，未回答的可选布尔不提交', async () => {
    const wrapper = mount(ToolInputForm, { props: { pending, busy: false } })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const { value, body } = response(wrapper)
    expect(value.run_revision).toBe('9007199254740993')
    expect(value.tool_input.wait).toEqual({ action_id: 'action', generation: '2' })
    expect(body).toEqual({ action: 'accept', content: { count: 0, confirmed: false } })
    expect(wrapper.text()).toContain('默认值：0')
    wrapper.unmount()
  })
  it('越界数值定位到具体字段，拒绝操作仍不发送表单草稿', async () => {
    const wrapper = mount(ToolInputForm, { props: { pending, busy: false } })
    wrapper.getComponent(ElInputNumber).vm.$emit('update:modelValue', -1)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.emitted('respond')).toBeUndefined()
    await vi.waitFor(() =>
      expect(wrapper.get('.el-form-item__error').text()).toContain('数量超出允许范围')
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '拒绝提供')!
      .trigger('click')
    await flushPromises()
    expect(response(wrapper).body).toEqual({ action: 'decline' })
    wrapper.unmount()
  })
})

it.each([
  { minLength: 1, maxLength: 1, text: '😀', valid: true },
  { minLength: 2, maxLength: 2, text: '😀', valid: false },
  { minLength: 2, maxLength: 2, text: '😀文', valid: true },
  { minLength: 1, maxLength: 1, text: '😀文', valid: false }
])(
  '按Unicode码点校验工具输入长度：$minLength–$maxLength / $text',
  async ({ minLength, maxLength, text, valid }) => {
    const request = {
      ...pending,
      card: {
        ...pending.card,
        requests: [
          {
            id: 'r',
            message: '输入简短说明',
            requested_schema: {
              type: 'object',
              required: ['text'],
              properties: { text: { type: 'string', minLength, maxLength } }
            }
          }
        ]
      }
    } as RuntimePendingInteraction
    const wrapper = mount(ToolInputForm, { props: { pending: request, busy: false } })
    try {
      await wrapper.get('input').setValue(text)
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      if (valid) expect(response(wrapper).body).toEqual({ action: 'accept', content: { text } })
      else {
        expect(wrapper.emitted('respond')).toBeUndefined()
        await vi.waitFor(() =>
          expect(wrapper.get('.el-form-item__error').text()).toContain('长度不符合要求')
        )
      }
    } finally {
      wrapper.unmount()
    }
  }
)
