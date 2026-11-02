import { afterEach, beforeAll, afterAll, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { EditorView } from '@codemirror/view'
import PackageFileWorkbench from './PackageFileWorkbench.vue'

const wrappers: VueWrapper[] = []
const rangeRects = Object.getOwnPropertyDescriptor(Range.prototype, 'getClientRects')
const rangeBounds = Object.getOwnPropertyDescriptor(Range.prototype, 'getBoundingClientRect')
beforeAll(() => {
  // jsdom 没有文字布局；这里只验证真实 CodeMirror 的编辑、撤销和文件归属。
  Object.defineProperty(Range.prototype, 'getClientRects', { configurable: true, value: () => [] })
  Object.defineProperty(Range.prototype, 'getBoundingClientRect', {
    configurable: true,
    value: () => ({ x: 0, y: 0, width: 0, height: 0, top: 0, bottom: 0, left: 0, right: 0 })
  })
})
afterAll(() => {
  if (rangeRects) Object.defineProperty(Range.prototype, 'getClientRects', rangeRects)
  else Reflect.deleteProperty(Range.prototype, 'getClientRects')
  if (rangeBounds) Object.defineProperty(Range.prototype, 'getBoundingClientRect', rangeBounds)
  else Reflect.deleteProperty(Range.prototype, 'getBoundingClientRect')
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

describe('包文件的真实编辑与撤销', () => {
  it('切换文件后撤销只影响当前文件，切回时仍保留各自正文', async () => {
    const wrapper = mount(PackageFileWorkbench, {
      props: {
        files: { 'SKILL.md': '# 技能正文', 'scripts/main.py': 'print("原脚本")' },
        'onUpdate:files': (files) => void wrapper.setProps({ files })
      },
      attachTo: document.body
    })
    wrappers.push(wrapper)
    const workbench = wrapper.vm as unknown as { selectFile(path: string): boolean }
    function view() {
      return EditorView.findFromDOM(wrapper.get('.cm-editor').element as HTMLElement)!
    }
    async function undo() {
      view().contentDOM.dispatchEvent(
        new KeyboardEvent('keydown', {
          key: 'z',
          code: 'KeyZ',
          ctrlKey: true,
          bubbles: true,
          cancelable: true
        })
      )
      await flushPromises()
    }
    view().dispatch({ changes: { from: view().state.doc.length, insert: '\n未保存文档修改' } })
    await flushPromises()
    expect(workbench.selectFile('scripts/main.py')).toBe(true)
    await flushPromises()
    await undo()
    expect(wrapper.props('files')['scripts/main.py']).toBe('print("原脚本")')
    view().dispatch({ changes: { from: view().state.doc.length, insert: '\n# 脚本修改' } })
    await flushPromises()
    await undo()
    expect(wrapper.props('files')['scripts/main.py']).toBe('print("原脚本")')
    expect(wrapper.props('files')['SKILL.md']).toBe('# 技能正文\n未保存文档修改')
    expect(workbench.selectFile('SKILL.md')).toBe(true)
    await flushPromises()
    expect(view().state.doc.toString()).toBe('# 技能正文\n未保存文档修改')
    await undo()
    expect(wrapper.props('files')['SKILL.md']).toBe('# 技能正文\n未保存文档修改')
  })
})
