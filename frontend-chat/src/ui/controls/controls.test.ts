import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, reactive, ref } from 'vue'
import {
  ChatDrawer,
  ChatButton,
  ChatForm,
  ChatFormItem,
  ChatInput,
  ChatInputNumber,
  ChatPopover,
  ChatSelect,
  ChatTable,
  ChatTableColumn,
  ChatTooltip,
  type FormInstance
} from './index'
import { validateForm } from '../panel'

afterEach(() =>
  document.querySelectorAll('.chat-select__popup').forEach((element) => element.remove())
)

describe('独立会话控件的交互契约', () => {
  it('正文滚动后再移动重新定位，Escape 关闭后移动不重开，重新进入才恢复', async () => {
    const wrapper = mount(ChatTooltip, {
      attachTo: document.body,
      props: { text: '创建时间' },
      slots: { default: '正文' }
    })
    try {
      await wrapper.trigger('mouseenter')
      window.dispatchEvent(new Event('scroll'))
      await flushPromises()
      expect(document.querySelector('[role="tooltip"]')).toBeNull()
      await wrapper.trigger('mousemove', { clientX: 100, clientY: 100 })
      await flushPromises()
      expect(document.querySelector('[role="tooltip"]')?.textContent).toBe('创建时间')
      await wrapper.trigger('keydown', { key: 'Escape' })
      await wrapper.trigger('mousemove', { clientX: 110, clientY: 110 })
      await flushPromises()
      expect(document.querySelector('[role="tooltip"]')).toBeNull()
      await wrapper.trigger('mouseleave')
      await wrapper.trigger('mouseenter')
      await flushPromises()
      expect(document.querySelector('[role="tooltip"]')?.textContent).toBe('创建时间')
    } finally {
      wrapper.unmount()
    }
  })
  it('正文提示保留嵌套链接与选择，文本变化、视口变化和卸载清理', async () => {
    const clicked = vi.fn()
    const wrapper = mount(ChatTooltip, {
      attachTo: document.body,
      props: { text: '消息创建时间' },
      slots: { default: () => h('a', { href: '#message', onClick: clicked }, '正文链接') }
    })
    try {
      await wrapper.trigger('focus')
      await wrapper.setProps({ text: '新创建时间' })
      expect(document.querySelector('[role="tooltip"]')).toBeNull()
      await wrapper.trigger('mouseenter')
      await flushPromises()
      expect(document.querySelector('[role="tooltip"]')?.textContent).toBe('新创建时间')
      await wrapper.get('a').trigger('click')
      expect(clicked).toHaveBeenCalledOnce()
      expect(wrapper.get('a').attributes('href')).toBe('#message')
      expect(document.querySelector('[role="tooltip"]')).toBeNull()
      await wrapper.trigger('focus')
      window.dispatchEvent(new Event('resize'))
      await flushPromises()
      expect(document.querySelector('[role="tooltip"]')).toBeNull()
      await wrapper.trigger('focus')
    } finally {
      wrapper.unmount()
    }
    expect(document.querySelector('[role="tooltip"]')).toBeNull()
  })
  it('按钮提示挂到视口，键盘可见，滚动、禁用和卸载清理且保留外部事件', async () => {
    const clicked = vi.fn()
    const wrapper = mount(ChatButton, {
      attachTo: document.body,
      attrs: { title: '发送消息', 'aria-describedby': 'help', onClick: clicked },
      slots: { default: '发送' }
    })
    try {
      await wrapper.get('button').trigger('focus')
      await flushPromises()
      const tip = document.querySelector<HTMLElement>('[role="tooltip"]')!
      expect(tip.parentElement).toBe(document.body)
      expect(wrapper.element.contains(tip)).toBe(false)
      expect(wrapper.attributes('aria-describedby')).toBe(`help ${tip.id}`)
      await wrapper.trigger('keydown', { key: 'Escape' })
      expect(document.querySelector('[role="tooltip"]')).toBeNull()
      await wrapper.trigger('mouseenter')
      window.dispatchEvent(new Event('scroll'))
      await flushPromises()
      expect(document.querySelector('[role="tooltip"]')).toBeNull()
      await wrapper.trigger('focus')
      await wrapper.setProps({ loading: true })
      expect(document.querySelector('[role="tooltip"]')).toBeNull()
      await wrapper.setProps({ loading: false })
      await wrapper.trigger('click')
      expect(clicked).toHaveBeenCalledOnce()
      await wrapper.trigger('focus')
    } finally {
      wrapper.unmount()
    }
    expect(document.querySelector('[role="tooltip"]')).toBeNull()
  })
  it('菜单保留 CSS 宽度，键盘打开移入焦点，Escape 返回触发按钮', async () => {
    const Host = defineComponent({
      setup() {
        const visible = ref(false)
        return () =>
          h(
            ChatPopover,
            {
              visible: visible.value,
              width: 'min(440px, calc(100vw - 32px))',
              'onUpdate:visible': (value: boolean) => {
                visible.value = value
              }
            },
            {
              reference: () => h('button', { class: 'menu-trigger' }, '更多操作'),
              default: () => h('button', { class: 'menu-action' }, '切换主题')
            }
          )
      }
    })
    const wrapper = mount(Host, { attachTo: document.body })
    try {
      const trigger = wrapper.get('.menu-trigger')
      trigger.element.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 0 }))
      await flushPromises()
      const popup = document.querySelector<HTMLElement>('.chat-popover')!
      const expectedStyle = document.createElement('div').style
      expectedStyle.width = 'min(440px, calc(100vw - 32px))'
      expect(popup.style.width).toBe(expectedStyle.width)
      expect(document.activeElement).toBe(popup.querySelector('.menu-action'))
      document.activeElement!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
      )
      await flushPromises()
      expect(document.querySelector('.chat-popover')).toBeNull()
      expect(document.activeElement).toBe(trigger.element)
    } finally {
      wrapper.unmount()
    }
  })

  it('方向键跳过禁用项，保留 false 原值并允许清空可选项', async () => {
    const wrapper = mount(ChatSelect, {
      attachTo: document.body,
      props: {
        clearable: true,
        options: [
          { value: true, label: '是' },
          { value: 'blocked', label: '不可选', disabled: true },
          { value: false, label: '否' }
        ]
      }
    })
    try {
      const control = wrapper.get('[role="combobox"]')
      await control.trigger('keydown', { key: 'ArrowDown' })
      await control.trigger('keydown', { key: 'ArrowDown' })
      await control.trigger('keydown', { key: 'Enter' })
      expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false])
      await wrapper.setProps({ modelValue: false })
      await wrapper.get('[aria-label="清空选择"]').trigger('click')
      expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([undefined])
    } finally {
      wrapper.unmount()
    }
  })

  it('筛选后的 Enter 提交原数值，Escape 不交给所属模态面板', async () => {
    const wrapper = mount(ChatSelect, {
      attachTo: document.body,
      props: {
        filterable: true,
        options: [
          { value: 7, label: '基础方案' },
          { value: 42, label: '目标方案' }
        ]
      }
    })
    try {
      await wrapper.get('[role="combobox"]').trigger('click')
      const search = document.querySelector<HTMLInputElement>('.select-search input')!
      search.value = '目标'
      search.dispatchEvent(new Event('input', { bubbles: true }))
      await flushPromises()
      search.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
      )
      await flushPromises()
      expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([42])
      await wrapper.get('[role="combobox"]').trigger('click')
      const escape = new KeyboardEvent('keydown', {
        key: 'Escape',
        bubbles: true,
        cancelable: true
      })
      wrapper.get('[role="combobox"]').element.dispatchEvent(escape)
      expect(escape.defaultPrevented).toBe(true)
    } finally {
      wrapper.unmount()
    }
  })

  it('键盘打开筛选器直接聚焦搜索，Shift+Tab 返回触发按钮，Tab 继续原控件顺序', async () => {
    const wrapper = mount(ChatSelect, {
      attachTo: document.body,
      props: { filterable: true, options: [{ value: 'research', label: 'research' }] }
    })
    try {
      const trigger = wrapper.get('.chat-select__trigger')
      ;(trigger.element as HTMLButtonElement).focus()
      await trigger.trigger('keydown', { key: 'ArrowDown' })
      await flushPromises()
      const search = document.querySelector<HTMLInputElement>('.select-search input')!
      expect(document.activeElement).toBe(search)
      search.value = 'research'
      search.dispatchEvent(new Event('input', { bubbles: true }))
      await flushPromises()
      expect(search.value).toBe('research')
      const backwards = new KeyboardEvent('keydown', {
        key: 'Tab',
        shiftKey: true,
        bubbles: true,
        cancelable: true
      })
      search.dispatchEvent(backwards)
      await flushPromises()
      expect(backwards.defaultPrevented).toBe(true)
      expect(document.activeElement).toBe(trigger.element)
      expect(trigger.attributes('aria-expanded')).toBe('false')
      await trigger.trigger('keydown', { key: 'Enter' })
      await flushPromises()
      const forwards = new KeyboardEvent('keydown', {
        key: 'Tab',
        bubbles: true,
        cancelable: true
      })
      search.dispatchEvent(forwards)
      await flushPromises()
      expect(forwards.defaultPrevented).toBe(false)
      expect(document.activeElement).toBe(trigger.element)
      expect(trigger.attributes('aria-expanded')).toBe('false')
    } finally {
      wrapper.unmount()
    }
  })

  it('在触发按钮上直接键入即开始筛选，Escape 返回焦点且不冒泡', async () => {
    const wrapper = mount(ChatSelect, {
      attachTo: document.body,
      props: { filterable: true, options: [{ value: 42, label: 'research' }] }
    })
    try {
      const trigger = wrapper.get('.chat-select__trigger')
      await trigger.trigger('keydown', { key: 'r' })
      await flushPromises()
      const search = document.querySelector<HTMLInputElement>('.select-search input')!
      expect(document.activeElement).toBe(search)
      expect(search.value).toBe('r')
      const parentKeydown = vi.fn()
      document.body.addEventListener('keydown', parentKeydown)
      try {
        search.dispatchEvent(
          new KeyboardEvent('keydown', {
            key: 'Escape',
            bubbles: true,
            cancelable: true
          })
        )
        await flushPromises()
        expect(parentKeydown).not.toHaveBeenCalled()
        expect(document.activeElement).toBe(trigger.element)
        expect(trigger.attributes('aria-expanded')).toBe('false')
      } finally {
        document.body.removeEventListener('keydown', parentKeydown)
      }
    } finally {
      wrapper.unmount()
    }
  })

  it('搜索输入保持光标编辑，输入法候选确认不会提前提交选择', async () => {
    const wrapper = mount(ChatSelect, {
      attachTo: document.body,
      props: { filterable: true, options: [{ value: 42, label: '研究方案' }] }
    })
    try {
      await wrapper.get('.chat-select__trigger').trigger('click')
      await flushPromises()
      const search = document.querySelector<HTMLInputElement>('.select-search input')!
      for (const key of ['Home', 'End', ' ']) {
        const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
        search.dispatchEvent(event)
        expect(event.defaultPrevented).toBe(false)
      }
      search.dispatchEvent(
        new KeyboardEvent('keydown', {
          key: 'Enter',
          isComposing: true,
          bubbles: true,
          cancelable: true
        })
      )
      await flushPromises()
      expect(wrapper.emitted('update:modelValue')).toBeUndefined()
      expect(wrapper.get('.chat-select__trigger').attributes('aria-expanded')).toBe('true')
      search.dispatchEvent(
        new KeyboardEvent('keydown', {
          key: 'Enter',
          bubbles: true,
          cancelable: true
        })
      )
      await flushPromises()
      expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([42])
    } finally {
      wrapper.unmount()
    }
  })

  it('表单失败关联错误并聚焦字段，修正后允许提交', async () => {
    const Host = defineComponent({
      setup() {
        const model = reactive({ title: '', rounds: undefined as number | undefined })
        return () =>
          h(
            ChatForm,
            {
              model,
              rules: {
                title: [{ required: true, whitespace: true, message: '填写标题' }],
                rounds: [
                  { type: 'number', required: true, min: 1, max: 20, message: '轮数超出范围' }
                ]
              }
            },
            {
              default: () => [
                h(
                  ChatFormItem,
                  { label: '标题', prop: 'title' },
                  {
                    default: () =>
                      h(ChatInput, {
                        modelValue: model.title,
                        'onUpdate:modelValue': (value) => {
                          model.title = String(value)
                        }
                      })
                  }
                ),
                h(
                  ChatFormItem,
                  { label: '轮数', prop: 'rounds' },
                  {
                    default: () =>
                      h(ChatInputNumber, {
                        modelValue: model.rounds,
                        min: 1,
                        max: 20,
                        'onUpdate:modelValue': (value) => {
                          model.rounds = value
                        }
                      })
                  }
                )
              ]
            }
          )
      }
    })
    const wrapper = mount(Host, { attachTo: document.body })
    try {
      const form = wrapper.getComponent(ChatForm).vm as unknown as FormInstance
      expect(await validateForm(form)).toBe(false)
      const input = wrapper.get('input')
      expect(document.activeElement).toBe(input.element)
      expect(input.attributes('aria-invalid')).toBe('true')
      expect(input.attributes('aria-describedby')).toContain('-error')
      await input.setValue('研究计划')
      await wrapper.get('input[type="number"]').setValue('3')
      expect(await validateForm(form)).toBe(true)
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })

  it('空草稿失焦不报错，显式提交仍执行校验', async () => {
    const Host = defineComponent({
      setup() {
        const draft = ref('')
        return () =>
          h(
            ChatForm,
            {
              model: { draft: draft.value },
              rules: { draft: [{ required: true, message: '请输入消息' }] }
            },
            {
              default: () =>
                h(
                  ChatFormItem,
                  { prop: 'draft', validateOnBlur: false },
                  {
                    default: () =>
                      h(ChatInput, {
                        type: 'textarea',
                        modelValue: draft.value,
                        'aria-label': '消息'
                      })
                  }
                )
            }
          )
      }
    })
    const wrapper = mount(Host)
    try {
      await wrapper.get('textarea').trigger('focusout')
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(await validateForm(wrapper.getComponent(ChatForm).vm as unknown as FormInstance)).toBe(
        false
      )
      expect(wrapper.get('[role="alert"]').text()).toBe('请输入消息')
    } finally {
      wrapper.unmount()
    }
  })

  it('必填选择器校验失败聚焦触发按钮并关联错误', async () => {
    const Host = defineComponent({
      setup() {
        return () =>
          h(
            ChatForm,
            {
              model: { leader: undefined },
              rules: { leader: [{ required: true, message: '请选择负责人' }] }
            },
            {
              default: () =>
                h(
                  ChatFormItem,
                  { prop: 'leader', label: '负责人' },
                  {
                    default: () => h(ChatSelect, { options: [{ value: 'a1', label: '研究助手' }] })
                  }
                )
            }
          )
      }
    })
    const wrapper = mount(Host, { attachTo: document.body })
    try {
      expect(await validateForm(wrapper.getComponent(ChatForm).vm as unknown as FormInstance)).toBe(
        false
      )
      const trigger = wrapper.get('[role="combobox"]')
      expect(document.activeElement).toBe(trigger.element)
      expect(trigger.attributes('aria-invalid')).toBe('true')
      expect(trigger.attributes('aria-describedby')).toContain('-error')
    } finally {
      wrapper.unmount()
    }
  })

  it('抽屉关闭等待业务保护，重复 Escape 不触发重复确认', async () => {
    let finish!: () => void
    const pending = new Promise<void>((resolve) => {
      finish = resolve
    })
    const beforeClose = vi.fn(async (done: () => void) => {
      await pending
      done()
    })
    const wrapper = mount(ChatDrawer, {
      props: { modelValue: true, title: '编辑团队', beforeClose }
    })
    try {
      await wrapper.get('dialog').trigger('cancel')
      await wrapper.get('dialog').trigger('cancel')
      expect(beforeClose).toHaveBeenCalledOnce()
      expect(wrapper.emitted('update:modelValue')).toBeUndefined()
      finish()
      await flushPromises()
      expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    } finally {
      finish()
      wrapper.unmount()
    }
  })

  it('表格渲染声明列和公开数据，包括 false 与空值', () => {
    const wrapper = mount(ChatTable, {
      props: {
        data: [
          { name: '记录', confirmed: false },
          { name: '下一项', confirmed: null },
          { name: '空文本', confirmed: '' },
          { name: '零', confirmed: 0 }
        ]
      },
      slots: {
        default: () => [
          h(ChatTableColumn, { prop: 'name', label: '名称' }),
          h(ChatTableColumn, { prop: 'confirmed', label: '确认' })
        ]
      }
    })
    try {
      expect(wrapper.findAll('th').map((cell) => cell.text())).toEqual(['名称', '确认'])
      expect(wrapper.findAll('td').map((cell) => cell.text())).toEqual([
        '记录',
        'false',
        '下一项',
        '—',
        '空文本',
        '',
        '零',
        '0'
      ])
      expect(wrapper.find('td[aria-label="无值"]').text()).toBe('—')
    } finally {
      wrapper.unmount()
    }
  })
})
