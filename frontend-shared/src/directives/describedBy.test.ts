import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref, withDirectives } from 'vue'
import { describe, expect, it } from 'vitest'
import ElementPlus, { ElInputNumber, ElSelect, ElSwitch } from 'element-plus'
import { vDescribedBy } from './describedBy'

describe('输入帮助关联', () => {
  it.each([ElInputNumber, ElSelect, ElSwitch])(
    '关联实际控件并在说明身份变化后更新',
    async (control) => {
      const id = ref('first-help')
      const wrapper = mount(
        defineComponent({
          setup: () => () =>
            withDirectives(h(control, { modelValue: undefined }), [[vDescribedBy, id.value]])
        }),
        { global: { plugins: [ElementPlus] } }
      )
      for (const input of wrapper.findAll('input, button'))
        expect(input.attributes('aria-describedby')).toBe('first-help')
      id.value = 'second-help'
      await nextTick()
      for (const input of wrapper.findAll('input, button'))
        expect(input.attributes('aria-describedby')).toBe('second-help')
      wrapper.unmount()
    }
  )
  it('保留现有说明并只清除自己追加的关联', async () => {
    const id = ref<string | undefined>('field-help')
    const wrapper = mount(
      defineComponent({
        setup: () => () =>
          withDirectives(h('div', [h('input', { 'aria-describedby': 'existing-help' })]), [
            [vDescribedBy, id.value]
          ])
      })
    )
    expect(wrapper.get('input').attributes('aria-describedby')).toBe('existing-help field-help')
    id.value = undefined
    await nextTick()
    expect(wrapper.get('input').attributes('aria-describedby')).toBe('existing-help')
    wrapper.unmount()
  })
})
