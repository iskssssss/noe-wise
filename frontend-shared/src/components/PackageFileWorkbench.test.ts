import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PackageFileWorkbench from './PackageFileWorkbench.vue'
import { confirm } from '../feedback'
const { focusLine } = vi.hoisted(() => ({ focusLine: vi.fn() }))
vi.mock('../feedback', () => ({ confirm: vi.fn() }))
vi.mock('./CodeEditor.vue', () => ({
  default: {
    props: ['modelValue', 'label', 'readonly'],
    emits: ['update:modelValue'],
    methods: { focusLine },
    template: `<textarea :aria-label="label" :readonly="readonly" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`
  }
}))
const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.clearAllMocks()
})
function render(files: Record<string, string>, readonly = false, dirty = false) {
  const wrapper = mount(PackageFileWorkbench, {
    props: {
      files,
      readonly,
      dirty,
      'onUpdate:files': (next) => {
        void wrapper.setProps({ files: next })
      }
    }
  })
  wrappers.push(wrapper)
  return wrapper
}
function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAll('button').find((item) => item.text() === label)
  if (!found) throw new Error('按钮不存在：' + label)
  return found
}
describe('包文件任务工作台', () => {
  it('草稿文件状态区分已保存与待保存', async () => {
    const wrapper = render({ 'SKILL.md': '# 技能' }, false, true)
    expect(wrapper.text()).toContain('草稿 · 修改后需保存')
    await wrapper.setProps({ dirty: false })
    expect(wrapper.text()).toContain('草稿 · 已保存')
  })
  it('问题定位选中实际文件并聚焦指定行，无效路径不改变编辑对象', async () => {
    const wrapper = render({ 'SKILL.md': '# 技能', 'scripts/main.py': 'line1\nline2' })
    const exposed = wrapper.vm as unknown as {
      reveal(path: string, line?: number): Promise<boolean>
    }
    expect(await exposed.reveal('scripts/main.py', 2)).toBe(true)
    expect(focusLine).toHaveBeenCalledWith(2)
    expect(wrapper.get('textarea').attributes('aria-label')).toBe('文件 scripts/main.py')
    expect(await exposed.reveal('missing', 1)).toBe(false)
    expect(focusLine).toHaveBeenCalledTimes(1)
  })
  it('按目录呈现文件，定位真实文件时保留内存编辑，不存在路径保持原选择', async () => {
    const wrapper = render({
      'SKILL.md': '# 技能',
      'scripts/main.py': 'print(1)',
      'references/guide.md': '参考'
    })
    await flushPromises()
    expect(wrapper.findAll('[role="treeitem"]').length).toBe(5)
    expect(wrapper.get('textarea').attributes('aria-label')).toBe('文件 SKILL.md')
    await wrapper.get('textarea').setValue('# 修改未发布')
    const exposed = wrapper.vm as unknown as { selectFile(path: string): boolean }
    expect(exposed.selectFile('scripts/main.py')).toBe(true)
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('print(1)')
    expect(exposed.selectFile('missing.py')).toBe(false)
    expect(exposed.selectFile('SKILL.md')).toBe(true)
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('# 修改未发布')
  })
  it('创建目录下文件并拒绝重名、目录冲突和输入法确认', async () => {
    const wrapper = render({ 'scripts/main.py': '' })
    await button(wrapper, '新建文件').trigger('click')
    await flushPromises()
    const path = wrapper.get('input[placeholder="scripts/main.py"]')
    await path.setValue('scripts')
    await button(wrapper, '创建文件').trigger('click')
    expect(wrapper.text()).toContain('与已有文件或目录冲突')
    await path.setValue('references/guide.md')
    await path.trigger('keydown', { key: 'Enter', isComposing: true })
    expect(wrapper.emitted('update:files')).toBeUndefined()
    await path.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(wrapper.get('textarea').attributes('aria-label')).toBe('文件 references/guide.md')
    expect(wrapper.props('files')).toHaveProperty('references/guide.md', '')
    expect(wrapper.text()).not.toContain('与已有文件或目录冲突')
  })
  it('确认删除后选中相邻文件，保留其他文件内容', async () => {
    vi.mocked(confirm).mockResolvedValueOnce(true)
    const wrapper = render({ 'SKILL.md': '# 文档', 'scripts/main.py': '内容' })
    await flushPromises()
    await button(wrapper, '删除文件').trigger('click')
    await flushPromises()
    expect(wrapper.props('files')).toEqual({ 'scripts/main.py': '内容' })
    expect(wrapper.get('textarea').attributes('aria-label')).toBe('文件 scripts/main.py')
  })
  it('原始资源只读且不可误写，编辑文本保留资源，仍允许明确删除', async () => {
    const resource = '/wAB'
    const wrapper = render({ 'SKILL.md': '# 文档', 'assets/font.woff2': resource })
    const exposed = wrapper.vm as unknown as { selectFile(path: string): boolean }
    exposed.selectFile('assets/font.woff2')
    await flushPromises()
    expect(wrapper.get('textarea').attributes('readonly')).toBeDefined()
    expect(wrapper.get('textarea').element.value).toBe(resource)
    expect(wrapper.text()).toContain('导入会替换草稿文件')
    await wrapper.get('textarea').setValue('不应作为正文保存')
    expect(wrapper.emitted('update:files')).toBeUndefined()
    exposed.selectFile('SKILL.md')
    await flushPromises()
    expect(wrapper.get('textarea').attributes('readonly')).toBeUndefined()
    await wrapper.get('textarea').setValue('# 新正文')
    await flushPromises()
    expect(wrapper.props('files')).toEqual({
      'SKILL.md': '# 新正文',
      'assets/font.woff2': resource
    })
    exposed.selectFile('assets/font.woff2')
    await flushPromises()
    vi.mocked(confirm).mockResolvedValueOnce(true)
    await button(wrapper, '删除文件').trigger('click')
    await flushPromises()
    expect(wrapper.props('files')).toEqual({ 'SKILL.md': '# 新正文' })
  })
  it('不支持在线编辑的路径保留输入并提示导入，改为文本路径后可以继续创建', async () => {
    const wrapper = render({ 'SKILL.md': '# 文档' })
    await button(wrapper, '新建文件').trigger('click')
    await flushPromises()
    const path = wrapper.get('input[placeholder="scripts/main.py"]')
    await path.setValue('references/config.toml')
    await button(wrapper, '创建文件').trigger('click')
    expect(wrapper.text()).toContain('此文件类型不支持在线新建或文本编辑')
    expect(wrapper.text()).toContain('完整 ZIP')
    expect((path.element as HTMLInputElement).value).toBe('references/config.toml')
    expect(wrapper.emitted('update:files')).toBeUndefined()
    await path.setValue('references/guide.TXT')
    await button(wrapper, '创建文件').trigger('click')
    await flushPromises()
    expect(wrapper.props('files')).toEqual({ 'SKILL.md': '# 文档', 'references/guide.TXT': '' })
    expect(wrapper.get('textarea').attributes('readonly')).toBeUndefined()
    expect(wrapper.text()).not.toContain('此文件类型不支持在线新建或文本编辑')
  })
  it('只读版本仍能定位和查看文件，隐藏写操作', async () => {
    const wrapper = render({ 'SKILL.md': '# 文档' }, true)
    await flushPromises()
    expect(wrapper.findAll('button').some((item) => item.text() === '新建文件')).toBe(false)
    expect(wrapper.findAll('button').some((item) => item.text() === '删除文件')).toBe(false)
    expect(wrapper.get('textarea').attributes('readonly')).toBeDefined()
  })
})
