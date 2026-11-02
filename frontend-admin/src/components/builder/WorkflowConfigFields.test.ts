import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { ElInput } from 'element-plus'
import WorkflowConfigFields from './WorkflowConfigFields.vue'

describe('安装参数高级编辑', () => {
  it.each([{ values: 'one' }, { values: { label: 'one' } }, { values: ['one', 2] }])(
    '不支持的可选值形态 $values 保留原 JSON 并提供纠正入口',
    ({ values }) => {
      const raw = JSON.stringify([{ key: 'status', type: 'enum', enum_values: values }])
      const wrapper = mount(WorkflowConfigFields, { props: { modelValue: raw } })
      try {
        expect(wrapper.text()).toContain('安装参数格式不正确')
        expect(
          wrapper.get<HTMLTextAreaElement>('textarea[aria-label="安装参数声明 JSON"]').element.value
        ).toBe(raw)
        expect(wrapper.emitted('update:modelValue')).toBeUndefined()
      } finally {
        wrapper.unmount()
      }
    }
  )

  it('纠正可选值后恢复表单，合法字符串数组保持原选项', async () => {
    const raw = JSON.stringify([{ key: 'status', type: 'enum', enum_values: 'one' }])
    const wrapper = mount(WorkflowConfigFields, { props: { modelValue: raw } })
    try {
      const corrected = JSON.stringify([
        { key: 'status', type: 'enum', enum_values: ['待处理', '已完成'] }
      ])
      await wrapper.setProps({ modelValue: corrected })
      expect(wrapper.text()).not.toContain('安装参数格式不正确')
      expect(
        wrapper
          .findAllComponents(ElInput)
          .some((input) => input.props('modelValue') === '待处理\n已完成')
      ).toBe(true)
      expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    } finally {
      wrapper.unmount()
    }
  })
})
