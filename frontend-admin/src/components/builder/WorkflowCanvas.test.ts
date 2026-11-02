import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { ElButton, ElPopover } from 'element-plus'
import WorkflowCanvas from './WorkflowCanvas.vue'
import type { BuilderNode } from '../../utils/workflow'

const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})
function render(
  readonly = false,
  extra: Partial<{
    nodes: BuilderNode[]
    busy: boolean
    canPaste: boolean
    selectedIds: string[]
    selectedId: string
  }> = {}
) {
  const nodes: BuilderNode[] = [
    {
      id: 'start',
      label: '开始',
      type: 'start',
      x: 80,
      y: 80,
      resultTo: '',
      assignPairs: [],
      menuActions: []
    },
    {
      id: 'end',
      label: '结束',
      type: 'end',
      x: 340,
      y: 240,
      resultTo: '',
      assignPairs: [],
      menuActions: []
    }
  ]
  const wrapper = mount(WorkflowCanvas, {
    props: {
      nodes,
      edges: [],
      readonly,
      ...extra,
      'onUpdate:nodes': (value) => {
        void wrapper.setProps({ nodes: value })
      },
      'onUpdate:edges': (value) => {
        void wrapper.setProps({ edges: value })
      },
      'onUpdate:viewport': (value) => {
        void wrapper.setProps({ viewport: value })
      },
      'onUpdate:selectedIds': (value) => {
        void wrapper.setProps({ selectedIds: value })
      },
      'onUpdate:selectedId': (value) => {
        void wrapper.setProps({ selectedId: value })
      }
    }
  })
  wrappers.push(wrapper)
  return wrapper
}
async function action(wrapper: VueWrapper, label: string) {
  const visible = wrapper.findAll('button').find((button) => button.text() === label)
  if (visible && !visible.element.closest('.workflow-canvas-batch-menu')) return visible
  const trigger = wrapper.findAll('button').find((button) => button.text() === '批量编辑')!
  if (!wrapper.findComponent(ElPopover).props('visible')) {
    await trigger.trigger('click')
    await vi.waitFor(() => expect(wrapper.findComponent(ElPopover).props('visible')).toBe(true))
  }
  return wrapper.findAllComponents(ElButton).find((button) => button.text() === label)!
}
describe('工作流画布', () => {
  it('批量编辑通过真实 Popover 打开操作菜单，完成选择后关闭并保留可见计数', async () => {
    const wrapper = render()
    expect(wrapper.findComponent(ElPopover).props('visible')).toBe(false)
    const selectAll = await action(wrapper, '全选节点')
    expect(wrapper.findComponent(ElPopover).props('visible')).toBe(true)
    expect(selectAll.isVisible()).toBe(true)
    await selectAll.trigger('click')
    expect(wrapper.findComponent(ElPopover).props('visible')).toBe(false)
    expect(wrapper.text()).toContain('已选 2 个节点')
    await (await action(wrapper, '清除选择')).trigger('click')
    expect(wrapper.findComponent(ElPopover).props('visible')).toBe(false)
    expect(wrapper.props('selectedIds')).toEqual([])
  })
  it('下一步与插入操作携带真实节点或边身份，并取消未完成连线', async () => {
    const wrapper = render()
    await wrapper.get('[aria-label="开始的输出端口"]').trigger('click')
    await wrapper.get('[aria-label="在开始后添加节点"]').trigger('click')
    expect(wrapper.emitted('add-next')).toEqual([['start']])
    expect(wrapper.find('.workflow-canvas-pending').exists()).toBe(false)
    expect(wrapper.find('[aria-label="在结束后添加节点"]').exists()).toBe(false)
    await wrapper.get('[aria-label="开始的输出端口"]').trigger('click')
    await wrapper.get('[aria-label="结束的输入端口"]').trigger('click')
    await flushPromises()
    const edge = wrapper.props('edges')[0]!
    await wrapper.get('.workflow-canvas-context button').trigger('click')
    expect(wrapper.emitted('insert-node')).toEqual([[edge.id]])
    expect(wrapper.props('edges')).toHaveLength(1)
    await wrapper.setProps({ readonly: true })
    expect(wrapper.find('.workflow-canvas-context').exists()).toBe(false)
    expect(wrapper.find('.workflow-canvas-add').exists()).toBe(false)
  })
  it('端口连接创建真实边，重复连接不会创建第二条，连线支持键盘编辑', async () => {
    const wrapper = render()
    await wrapper.get('[aria-label="开始的输出端口"]').trigger('click')
    await wrapper.get('[aria-label="结束的输入端口"]').trigger('click')
    await flushPromises()
    expect(wrapper.props('edges')).toHaveLength(1)
    expect(wrapper.props('edges')[0]).toMatchObject({ source: 'start', target: 'end' })
    await wrapper.get('[aria-label="开始的输出端口"]').trigger('click')
    await wrapper.get('[aria-label="结束的输入端口"]').trigger('click')
    expect(wrapper.props('edges')).toHaveLength(1)
    expect(wrapper.text()).toContain('已有连接')
    await wrapper.get('.workflow-canvas-edge-hit').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('edit-edge')).toHaveLength(2)
  })
  it('键盘移动保留其他节点与未知字段，只读切换取消连线并禁止写入', async () => {
    const wrapper = render()
    const node = wrapper.get('[data-node-id="start"]')
    await node.trigger('keydown', { key: 'ArrowRight', shiftKey: true })
    await flushPromises()
    expect(wrapper.props('nodes')[0]?.x).toBe(112)
    expect(wrapper.props('nodes')[1]?.x).toBe(340)
    await wrapper.get('[aria-label="开始的输出端口"]').trigger('click')
    await wrapper.setProps({ readonly: true })
    expect(wrapper.find('.workflow-canvas-pending').exists()).toBe(false)
    await node.trigger('keydown', { key: 'Delete' })
    expect(wrapper.emitted('remove-node')).toBeUndefined()
    await node.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.props('nodes')[0]?.x).toBe(112)
  })
  it('拖放完成后浏览器补发click不会重新开始连线', async () => {
    const wrapper = render()
    const output = wrapper.get('[aria-label="开始的输出端口"]')
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: vi.fn(() => wrapper.get('[data-input-node="end"]').element)
    })
    await output.trigger('pointerdown', { button: 0, pointerId: 1, clientX: 10, clientY: 10 })
    await output.trigger('pointerup', { pointerId: 1, clientX: 20, clientY: 20 })
    await output.trigger('click')
    await flushPromises()
    expect(wrapper.props('edges')).toHaveLength(1)
    expect(wrapper.find('.workflow-canvas-pending').exists()).toBe(false)
  })
  it('修饰点击与多选模式均切换节点，组选择不会打开节点侧栏', async () => {
    const wrapper = render()
    const start = wrapper.get('[data-node-id="start"]'),
      end = wrapper.get('[data-node-id="end"]')
    await start.trigger('click', { shiftKey: true })
    await end.trigger('click', { ctrlKey: true })
    expect(wrapper.props('selectedIds')).toEqual(['start', 'end'])
    expect(wrapper.props('selectedId')).toBe('end')
    expect(wrapper.findAll('.workflow-canvas-node.selected')).toHaveLength(2)
    await start.trigger('click')
    expect(wrapper.emitted('edit-node')).toBeUndefined()
    expect(wrapper.text()).toContain('已选 2 个节点')
    await end.trigger('click', { metaKey: true })
    expect(wrapper.props('selectedIds')).toEqual(['start'])
    await (await action(wrapper, '多选模式')).trigger('click')
    expect((await action(wrapper, '多选模式')).attributes('aria-pressed')).toBe('true')
    await end.trigger('click')
    await start.trigger('click')
    expect(wrapper.props('selectedIds')).toEqual(['end'])
    expect(wrapper.emitted('edit-node')).toBeUndefined()
    await (await action(wrapper, '全选节点')).trigger('click')
    expect(wrapper.props('selectedIds')).toEqual(['start', 'end'])
    await (await action(wrapper, '清除选择')).trigger('click')
    expect(wrapper.props('selectedIds')).toEqual([])
    expect(wrapper.props('selectedId')).toBe('')
  })
  it('组拖动仅预览后一次写入，保持相对位置与非负边界，补发点击不弹属性', async () => {
    const wrapper = render()
    await (await action(wrapper, '全选节点')).trigger('click')
    const start = wrapper.get('[data-node-id="start"]')
    await start.trigger('pointerdown', { button: 0, pointerId: 4, clientX: 100, clientY: 100 })
    await start.trigger('pointermove', { pointerId: 4, clientX: -100, clientY: -100 })
    expect(wrapper.emitted('update:nodes')).toBeUndefined()
    expect(wrapper.findAll('.workflow-canvas-node')[0]!.attributes('style')).toContain('left: 0px')
    expect(wrapper.findAll('.workflow-canvas-node')[1]!.attributes('style')).toContain(
      'left: 260px'
    )
    await start.trigger('pointerup', { pointerId: 4 })
    await start.trigger('click')
    expect(wrapper.emitted('update:nodes')).toHaveLength(1)
    expect(wrapper.props('nodes').map(({ x, y }) => [x, y])).toEqual([
      [0, 0],
      [260, 160]
    ])
    expect(wrapper.emitted('edit-node')).toBeUndefined()
  })
  it('画布或节点聚焦时键盘移动整组一次，保留字段并在边界保持组间距', async () => {
    const wrapper = render()
    const original = wrapper.props('nodes')[1]!
    await (await action(wrapper, '全选节点')).trigger('click')
    await wrapper
      .get('.workflow-canvas-surface')
      .trigger('keydown', { key: 'ArrowRight', shiftKey: true })
    expect(wrapper.props('nodes').map((node) => node.x)).toEqual([112, 372])
    expect(wrapper.props('nodes')[1]).toEqual({ ...original, x: 372 })
    expect(wrapper.emitted('update:nodes')).toHaveLength(1)
    for (let count = 0; count < 4; count++) {
      await wrapper
        .get('[data-node-id="start"]')
        .trigger('keydown', { key: 'ArrowLeft', shiftKey: true })
    }
    expect(wrapper.props('nodes').map((node) => node.x)).toEqual([0, 260])
  })
  it('对齐与等距按当前组一次写入，等距保留两端且不足三个节点时禁用', async () => {
    const wrapper = render()
    await (await action(wrapper, '全选节点')).trigger('click')
    expect((await action(wrapper, '水平等距')).attributes('disabled')).toBeDefined()
    await wrapper.setProps({
      nodes: [
        ...wrapper.props('nodes'),
        {
          ...wrapper.props('nodes')[1]!,
          id: 'middle',
          x: 140,
          y: 440
        }
      ]
    })
    await (await action(wrapper, '全选节点')).trigger('click')
    await (await action(wrapper, '水平等距')).trigger('click')
    expect(wrapper.props('nodes').map((node) => node.x)).toEqual([80, 340, 210])
    expect(wrapper.emitted('update:nodes')).toHaveLength(1)
    await (await action(wrapper, '垂直等距')).trigger('click')
    expect(wrapper.props('nodes').map((node) => node.y)).toEqual([80, 260, 440])
    expect(wrapper.emitted('update:nodes')).toHaveLength(2)
    await (await action(wrapper, '左对齐')).trigger('click')
    expect(wrapper.props('nodes').map((node) => node.x)).toEqual([80, 80, 80])
    await (await action(wrapper, '顶对齐')).trigger('click')
    expect(wrapper.props('nodes').map((node) => node.y)).toEqual([80, 80, 80])
    expect(wrapper.emitted('update:nodes')).toHaveLength(4)
  })
  it('复制粘贴与批删携带完整身份，单节点删除仍使用既有事件', async () => {
    const wrapper = render(false, { canPaste: true })
    await (await action(wrapper, '全选节点')).trigger('click')
    await (await action(wrapper, '复制节点')).trigger('click')
    await (await action(wrapper, '粘贴节点')).trigger('click')
    await wrapper.get('.workflow-canvas-surface').trigger('keydown', { key: 'Delete' })
    expect(wrapper.emitted('copy-nodes')).toEqual([[['start', 'end']]])
    expect(wrapper.emitted('paste-nodes')).toEqual([[]])
    expect(wrapper.emitted('remove-nodes')).toEqual([[['start', 'end']]])
    expect(wrapper.emitted('remove-node')).toBeUndefined()
    expect(wrapper.emitted('update:nodes')).toBeUndefined()
    await (await action(wrapper, '清除选择')).trigger('click')
    await wrapper.get('[data-node-id="end"]').trigger('click')
    await (await action(wrapper, '删除节点')).trigger('click')
    expect(wrapper.emitted('remove-node')).toEqual([['end']])
  })
  it('复制快捷键仅在画布内生效，不抢输入内容、IME 或工具按钮，粘贴受剪贴板状态控制', async () => {
    const wrapper = render(false, { canPaste: true })
    await (await action(wrapper, '全选节点')).trigger('click')
    const surface = wrapper.get('.workflow-canvas-surface')
    await surface.trigger('keydown', { key: 'c', ctrlKey: true })
    await surface.trigger('keydown', { key: 'v', metaKey: true })
    expect(wrapper.emitted('copy-nodes')).toHaveLength(1)
    expect(wrapper.emitted('paste-nodes')).toHaveLength(1)
    await surface.trigger('keydown', { key: 'c', ctrlKey: true, isComposing: true })
    await surface.trigger('keydown', { key: 'c', ctrlKey: true, keyCode: 229 })
    await (await action(wrapper, '全选节点')).trigger('keydown', { key: 'c', ctrlKey: true })
    for (const element of [
      document.createElement('input'),
      document.createElement('textarea'),
      document.createElement('div')
    ]) {
      element.setAttribute('contenteditable', 'true')
      surface.element.append(element)
      element.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'c', ctrlKey: true, bubbles: true })
      )
      element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Delete', bubbles: true }))
      element.remove()
    }
    expect(wrapper.emitted('copy-nodes')).toHaveLength(1)
    expect(wrapper.emitted('remove-nodes')).toBeUndefined()
    await wrapper.setProps({ canPaste: false })
    await surface.trigger('keydown', { key: 'v', ctrlKey: true })
    expect(wrapper.emitted('paste-nodes')).toHaveLength(1)
    expect((await action(wrapper, '粘贴节点')).attributes('disabled')).toBeDefined()
  })
  it.each(['readonly', 'busy'] as const)(
    '%s 禁止组编辑、复制粘贴与端口写入，仍可选择和平移缩放',
    async (state) => {
      const wrapper = render(false, { canPaste: true })
      await (await action(wrapper, '全选节点')).trigger('click')
      await wrapper.setProps({ [state]: true })
      const surface = wrapper.get('.workflow-canvas-surface')
      for (const key of ['ArrowRight', 'Delete', 'Backspace', 'c', 'v']) {
        await surface.trigger('keydown', { key, ctrlKey: key.length === 1 })
      }
      expect(wrapper.emitted('update:nodes')).toBeUndefined()
      expect(wrapper.emitted('copy-nodes')).toBeUndefined()
      expect(wrapper.emitted('paste-nodes')).toBeUndefined()
      expect(wrapper.emitted('remove-nodes')).toBeUndefined()
      for (const label of [
        '左对齐',
        '顶对齐',
        '水平等距',
        '垂直等距',
        '复制节点',
        '粘贴节点',
        '删除节点'
      ]) {
        expect((await action(wrapper, label)).attributes('disabled')).toBeDefined()
      }
      await surface.trigger('wheel', { deltaY: 20 })
      expect(wrapper.emitted('update:viewport')).toHaveLength(1)
      await (await action(wrapper, '清除选择')).trigger('click')
      expect(wrapper.props('selectedIds')).toEqual([])
    }
  )
  it('外部撤销或删除后选择收敛，外部主选与多选模型能同步且取消旧拖动', async () => {
    const wrapper = render(false, { selectedIds: ['start', 'end'], selectedId: 'end' })
    await flushPromises()
    const start = wrapper.get('[data-node-id="start"]')
    await start.trigger('pointerdown', { button: 0, pointerId: 8, clientX: 10, clientY: 10 })
    await start.trigger('pointermove', { pointerId: 8, clientX: 50, clientY: 50 })
    await wrapper.setProps({ nodes: [wrapper.props('nodes')[0]!] })
    await start.trigger('pointerup', { pointerId: 8 })
    expect(wrapper.props('selectedIds')).toEqual(['start'])
    expect(wrapper.props('selectedId')).toBe('start')
    expect(wrapper.emitted('update:nodes')).toBeUndefined()
    await wrapper.setProps({ selectedIds: [] })
    expect(wrapper.props('selectedId')).toBe('')
    await wrapper.setProps({ selectedId: 'start' })
    expect(wrapper.props('selectedIds')).toEqual(['start'])
    await wrapper.setProps({ nodes: [] })
    expect(wrapper.props('selectedIds')).toEqual([])
    expect(wrapper.props('selectedId')).toBe('')
  })
  it('外部批量选中复制节点并设置首个为主选时保留整组，恢复画布焦点不改变视口', async () => {
    const wrapper = render()
    await wrapper.setProps({ selectedIds: ['end', 'start'], selectedId: 'end' })
    await flushPromises()
    expect(wrapper.props('selectedIds')).toEqual(['end', 'start'])
    expect(wrapper.props('selectedId')).toBe('end')
    expect(wrapper.findAll('.workflow-canvas-node.selected')).toHaveLength(2)
    const focus = vi.spyOn(wrapper.get('.workflow-canvas-surface').element as HTMLElement, 'focus')
    const exposed = wrapper.vm as unknown as { focusCanvas(): void }
    exposed.focusCanvas()
    expect(focus).toHaveBeenCalledWith({ preventScroll: true })
    expect(wrapper.emitted('update:viewport')).toBeUndefined()
  })
  it('真实父组件 v-model 异步回传时，首次选择即能拖动和用键盘移动', async () => {
    const fixture = render().props('nodes')
    const parent = mount(
      defineComponent({
        setup() {
          const nodes = ref(fixture),
            selectedIds = ref<string[]>([]),
            selectedId = ref('')
          return () =>
            h(WorkflowCanvas, {
              nodes: nodes.value,
              edges: [],
              selectedIds: selectedIds.value,
              selectedId: selectedId.value,
              'onUpdate:nodes': (value) => {
                nodes.value = value
              },
              'onUpdate:selectedIds': (value) => {
                selectedIds.value = value
              },
              'onUpdate:selectedId': (value) => {
                selectedId.value = value
              }
            })
        }
      })
    )
    wrappers.push(parent)
    const canvas = parent.findComponent(WorkflowCanvas)
    const start = canvas.get('[data-node-id="start"]')
    await start.trigger('pointerdown', { button: 0, pointerId: 11, clientX: 10, clientY: 10 })
    await start.trigger('pointermove', { pointerId: 11, clientX: 42, clientY: 42 })
    await start.trigger('pointerup', { pointerId: 11 })
    expect(canvas.props('nodes')[0]).toMatchObject({ x: 112, y: 112 })
    await canvas.get('[data-node-id="end"]').trigger('keydown', { key: 'ArrowRight' })
    expect(canvas.props('nodes')[1]?.x).toBe(348)
    expect(canvas.props('selectedIds')).toEqual(['end'])
  })
})
