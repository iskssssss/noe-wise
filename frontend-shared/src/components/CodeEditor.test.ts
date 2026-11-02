import { afterEach, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { EditorView } from '@codemirror/view'
import { foldCode, foldedRanges } from '@codemirror/language'
import CodeEditor from './CodeEditor.vue'
const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})
it('CodeEditor公开插入API使用保留选区且只读拒绝修改', async () => {
  const wrapper = mount(CodeEditor, {
    props: { modelValue: '前正文后', label: '提示词' },
    attachTo: document.body
  })
  wrappers.push(wrapper)
  const view = EditorView.findFromDOM(wrapper.get('.cm-editor').element as HTMLElement)!
  view.dispatch({ selection: { anchor: 1, head: 3 } })
  const editor = wrapper.vm as unknown as { insertText(text: string): boolean }
  expect(editor.insertText('${state.orders}')).toBe(true)
  expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['前${state.orders}后'])
  await wrapper.setProps({ readonly: true })
  expect(editor.insertText('禁止')).toBe(false)
  await flushPromises()
  expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
})

it('只读JSON可以折叠内部对象及全部展开，不修改原始数字与正文', async () => {
  const text =
    '{\n  "nested": {\n    "id": 900719925474099312345\n  },\n  "items": [\n    1,\n    2\n  ]\n}'
  const wrapper = mount(CodeEditor, {
    props: { modelValue: text, language: 'json', readonly: true, label: '调用 JSON' },
    attachTo: document.body
  })
  wrappers.push(wrapper)
  const view = EditorView.findFromDOM(wrapper.get('.cm-editor').element as HTMLElement)!
  const editor = wrapper.vm as unknown as { foldAll(): boolean; unfoldAll(): boolean }
  expect(wrapper.find('.cm-foldGutter').exists()).toBe(true)
  expect(editor.foldAll()).toBe(true)
  expect(foldedRanges(view.state).size).toBeGreaterThan(0)
  expect(editor.unfoldAll()).toBe(true)
  expect(foldedRanges(view.state).size).toBe(0)
  view.dispatch({ selection: { anchor: view.state.doc.line(2).from } })
  expect(foldCode(view)).toBe(true)
  const ranges: Array<{ from: number; to: number }> = []
  foldedRanges(view.state).between(0, text.length, (from, to) => ranges.push({ from, to }))
  expect(ranges).toHaveLength(1)
  expect(text.slice(ranges[0]!.from, ranges[0]!.to)).toContain('900719925474099312345')
  expect(view.state.doc.toString()).toBe(text)
  expect(wrapper.emitted('update:modelValue')).toBeUndefined()
})
