import { defineComponent, h, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { ElForm, ElInputNumber } from 'element-plus'
import { describe, expect, it } from 'vitest'

describe('数字输入禁用状态', () => {
  it.each([true, false])('表单由 %s 切换后同步原生状态和无障碍状态', async (initial) => {
    const disabled = ref(initial)
    const value = ref(2)
    const wrapper = mount(
      defineComponent({
        setup: () => () =>
          h(ElForm, { disabled: disabled.value }, () =>
            h(ElInputNumber, {
              modelValue: value.value,
              'onUpdate:modelValue': (next: number) => {
                value.value = next
              }
            })
          )
      }),
      { attachTo: document.body }
    )
    const input = wrapper.get('input')
    const check = (expected: boolean) => {
      expect((input.element as HTMLInputElement).disabled).toBe(expected)
      expect(input.attributes('aria-disabled')).toBe(String(expected))
    }
    check(initial)
    disabled.value = !initial
    await nextTick()
    check(!initial)
    await input.trigger('keydown', { key: 'ArrowUp', code: 'ArrowUp' })
    await nextTick()
    expect(value.value).toBe(initial ? 3 : 2)
    disabled.value = initial
    await nextTick()
    check(initial)
    await input.trigger('keydown', { key: 'ArrowUp', code: 'ArrowUp' })
    await nextTick()
    expect(value.value).toBe(3)
    wrapper.unmount()
  })
})
