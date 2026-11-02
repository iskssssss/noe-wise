import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import JsonViewer from './JsonViewer.vue'

const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
})

function render(value?: unknown) {
  const foldAll = vi.fn()
  const unfoldAll = vi.fn()
  const wrapper = mount(JsonViewer, {
    props: { value, label: '受控调用内容' },
    global: {
      stubs: {
        CodeEditor: defineComponent({
          props: {
            modelValue: String,
            language: String,
            label: String,
            height: String,
            readonly: Boolean
          },
          setup(props, { expose }) {
            expose({ foldAll, unfoldAll })
            return () =>
              h(
                'pre',
                { 'data-language': props.language, 'aria-label': props.label },
                props.modelValue
              )
          }
        })
      }
    }
  })
  wrappers.push(wrapper)
  return { wrapper, foldAll, unfoldAll }
}

async function click(wrapper: VueWrapper, label: string) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === label)!
    .trigger('click')
}

describe('JSON 查看器', () => {
  it('默认格式化精确数字，展开折叠复用编辑器，原文切换保留全部原始空白', async () => {
    const original = ' \r\n{"id":900719925474099312345,"value":1.000e+40} \t'
    const { wrapper, foldAll, unfoldAll } = render(original)
    expect(wrapper.get('pre').text()).toBe(
      '{\n  "id": 900719925474099312345,\n  "value": 1.000e+40\n}'
    )
    expect(wrapper.get('pre').attributes('data-language')).toBe('json')
    expect(wrapper.get('pre').attributes('aria-label')).toBe('受控调用内容')
    await click(wrapper, '全部折叠')
    expect(foldAll).toHaveBeenCalledOnce()
    await click(wrapper, '全部展开')
    expect(unfoldAll).toHaveBeenCalledOnce()
    await click(wrapper, '查看原文')
    expect(wrapper.get('pre').element.textContent).toBe(original)
    expect(wrapper.get('pre').attributes('data-language')).toBe('text')
    expect(wrapper.text()).not.toContain('全部折叠')
    await click(wrapper, '格式化显示')
    expect(wrapper.get('pre').text()).toContain('900719925474099312345')
  })

  it('复制完整原文，结果在顶部提示并保留当前内容', async () => {
    const original = ' {"number":900719925474099312345} '
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const { wrapper } = render(original)
    await click(wrapper, '复制原文')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith(original)
    expect(document.querySelector('.el-message--success')?.textContent).toContain('已复制原文')
    writeText.mockRejectedValueOnce(new Error('denied'))
    await click(wrapper, '复制原文')
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '复制失败，请在原文中选择内容后手动复制。'
    )
    expect(wrapper.get('pre').text()).toContain('900719925474099312345')
  })

  it('切换对象清除旧原文模式与迟到复制反馈', async () => {
    let complete!: () => void
    const writeText = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          complete = resolve
        })
    )
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const { wrapper } = render('{"old":1}')
    await click(wrapper, '查看原文')
    await click(wrapper, '复制原文')
    await wrapper.setProps({ value: '{"new":2}' })
    complete()
    await flushPromises()
    expect(wrapper.get('pre').text()).toBe('{\n  "new": 2\n}')
    expect(wrapper.text()).not.toContain('已复制原文')
    expect(wrapper.text()).not.toContain('完整原文')
  })

  it('普通文本与无效 JSON 完整回退正文，空值与未记录明确区分', async () => {
    const original = '原始正文\n{"id":01}\n 末行 '
    const { wrapper } = render(original)
    expect(wrapper.get('pre').element.textContent).toBe(original)
    expect(wrapper.get('pre').attributes('data-language')).toBe('text')
    expect(wrapper.text()).not.toContain('全部折叠')
    await wrapper.setProps({ value: null })
    expect(wrapper.get('pre').text()).toBe('null')
    expect(wrapper.get('pre').attributes('data-language')).toBe('json')
    await wrapper.setProps({ value: undefined })
    expect(wrapper.text()).toBe('未记录')
    expect(wrapper.find('pre').exists()).toBe(false)
  })

  it('结构化值直接格式化，不能序列化的值显示本地错误', async () => {
    const { wrapper } = render({ nested: [{ name: '完整值' }] })
    expect(wrapper.get('pre').text()).toContain('"name": "完整值"')
    const circular: Record<string, unknown> = {}
    circular.self = circular
    await wrapper.setProps({ value: circular })
    expect(wrapper.text()).toContain('无法将此内容表示为 JSON，请提供原始文本。')
    expect(wrapper.find('pre').exists()).toBe(false)
  })
})
