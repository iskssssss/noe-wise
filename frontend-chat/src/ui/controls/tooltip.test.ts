import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { h } from 'vue'
import { ChatDrawer } from './overlays'
import { ChatButton } from './forms'

let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})
describe('原生模态框内的提示', () => {
  it('Tooltip跟随触发按钮挂入当前dialog，失去焦点后移除', async () => {
    wrapper = mount(ChatDrawer, {
      props: { modelValue: true, title: '测试面板' },
      slots: { default: () => h(ChatButton, { title: '操作说明' }, () => '操作') },
      attachTo: document.body
    })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '操作')!
      .trigger('focus')
    await flushPromises()
    expect(wrapper.get('dialog [role="tooltip"]').text()).toBe('操作说明')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '操作')!
      .trigger('blur')
    await flushPromises()
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
  })
})
