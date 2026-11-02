import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import TaskDetailsPanel from './TaskDetailsPanel.vue'
import TaskPlanCard from './TaskPlanCard.vue'
import type { Client } from '../client'
import type { Work } from '../protocol'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())
const work = (id: string): Work => ({
  work_id: id,
  session_id: 's',
  input_id: id,
  run_id: `run:${id}`,
  revision: 1,
  status: 'active',
  run_status: 'running',
  plan_revision: 1,
  approval_required: false,
  title: `任务 ${id}`,
  tasks: [],
  results: []
})
describe('单一任务侧栏', () => {
  it('收到工作和新进度不会自动打开侧栏，只有显式打开才读取历史', async () => {
    const json = vi.fn().mockResolvedValue({ items: [work('old')] })
    const wrapper = mount(TaskDetailsPanel, {
      props: {
        modelValue: false,
        session: 's',
        client: { json } as unknown as Client,
        workPlans: [work('old')]
      }
    })
    await wrapper.setProps({ workPlans: [work('old'), work('new')] })
    expect(wrapper.find('aside').exists()).toBe(false)
    expect(json).not.toHaveBeenCalled()
    await wrapper.setProps({ modelValue: true, selectedId: 'old' })
    await flushPromises()
    expect(json).toHaveBeenCalledOnce()
    expect(wrapper.findComponent(TaskPlanCard).props('work').work_id).toBe('old')
  })
  it('所选任务暂未返回时原地重读，并保留原任务选择', async () => {
    const json = vi
      .fn()
      .mockResolvedValueOnce({ items: [] })
      .mockResolvedValueOnce({ items: [work('old')] })
    const wrapper = mount(TaskDetailsPanel, {
      props: {
        modelValue: true,
        selectedId: 'old',
        session: 's',
        client: { json } as unknown as Client
      }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('当前任务记录暂未返回')
    const retry = wrapper.findAll('button').find((item) => item.text() === '重新读取任务')!
    await retry.trigger('click')
    await flushPromises()
    expect(json).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent(TaskPlanCard).props('work').work_id).toBe('old')
    expect(wrapper.emitted('selected')).toEqual([['old']])
  })

  it('历史刷新和新工作不会覆盖正在查看的旧任务或抢走输入焦点', async () => {
    const json = vi.fn().mockResolvedValue({ items: [work('new'), work('old')] })
    const input = document.createElement('textarea')
    document.body.append(input)
    input.focus()
    const wrapper = mount(TaskDetailsPanel, {
      attachTo: document.body,
      props: {
        modelValue: true,
        selectedId: 'old',
        session: 's',
        client: { json } as unknown as Client,
        workPlans: [work('old')]
      }
    })
    await flushPromises()
    expect(document.activeElement).toBe(input)
    expect(wrapper.get('dialog').classes()).not.toContain('chat-drawer')
    await wrapper.setProps({ workPlans: [{ ...work('old'), revision: 2 }, work('latest')] })
    await flushPromises()
    expect(wrapper.findComponent(TaskPlanCard).props('work').work_id).toBe('old')
    expect(wrapper.findComponent(TaskPlanCard).props('work').revision).toBe(2)
    expect(document.activeElement).toBe(input)
    expect(wrapper.findAll('.task-panel-views button').map((button) => button.text())).toEqual([
      '概览',
      '步骤',
      '依赖关系'
    ])
    input.remove()
  })
  it('窄屏只开一个全屏层，步骤视图不增加嵌套抽屉', async () => {
    vi.stubGlobal('matchMedia', () => ({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
    const json = vi.fn().mockResolvedValue({ items: [work('old')] })
    const wrapper = mount(TaskDetailsPanel, {
      props: {
        modelValue: true,
        selectedId: 'old',
        session: 's',
        client: { json } as unknown as Client,
        workPlans: [work('old')]
      }
    })
    await flushPromises()
    expect(wrapper.findAll('dialog')).toHaveLength(1)
    await wrapper.findAll('.task-panel-views button')[1]!.trigger('click')
    expect(wrapper.findComponent(TaskPlanCard).props('view')).toBe('steps')
    expect(wrapper.findAll('dialog')).toHaveLength(1)
  })
  it('宽窄屏切换保持同一步骤实例、草稿、失败读取与输入焦点', async () => {
    let resize!: (event: MediaQueryListEvent) => void
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: false,
      addEventListener: (_: string, listener: typeof resize) => {
        if (query === '(max-width: 800px)') resize = listener
      },
      removeEventListener: vi.fn()
    }))
    const showModal = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '')
    })
    const close = vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute('open')
      queueMicrotask(() => this.dispatchEvent(new Event('close')))
    })
    const value: Work = {
      ...work('old'),
      tasks: [
        {
          task_id: 't',
          task_kind: 'plan',
          executor_type: 'PRIMARY',
          run_id: 'run:old',
          title: '保持选中步骤',
          agent_name: '主 Agent',
          status: 'waiting',
          details_access: 'available'
        }
      ]
    }
    const json = vi
      .fn()
      .mockImplementation((path: string) =>
        path.startsWith('/chat/work/tasks/detail')
          ? Promise.reject(new Error('步骤详情读取失败'))
          : Promise.resolve({ items: [value] })
      )
    const wrapper = mount(TaskDetailsPanel, {
      attachTo: document.body,
      props: {
        modelValue: true,
        selectedId: 'old',
        session: 's',
        client: { json } as unknown as Client,
        workPlans: [value]
      }
    })
    await flushPromises()
    const dialog = wrapper.get('dialog').element as HTMLDialogElement
    dialog.showModal = showModal
    dialog.close = close
    await wrapper.findAll('.task-panel-views button')[1]!.trigger('click')
    await wrapper.get('[data-task-id="t"]').trigger('click')
    await flushPromises()
    const card = wrapper.findComponent(TaskPlanCard).vm.$
    const input = wrapper.get('textarea')
    await input.setValue('缩放时保留草稿')
    input.element.focus()
    for (const narrow of [true, false, true]) {
      resize({ matches: narrow } as MediaQueryListEvent)
      await flushPromises()
      expect(wrapper.findComponent(TaskPlanCard).vm.$).toBe(card)
      expect(wrapper.get('textarea').element).toBe(input.element)
      expect(wrapper.get('textarea').element.value).toBe('缩放时保留草稿')
      expect(document.activeElement).toBe(input.element)
      expect(document.querySelector('.chat-toast')?.textContent).toContain('步骤详情读取失败')
      expect(dialog.querySelector('.chat-toast')?.textContent).toContain('重新读取')
    }
    expect(showModal).toHaveBeenCalledTimes(2)
    expect(close).toHaveBeenCalledOnce()
    const retry = [...dialog.querySelectorAll<HTMLButtonElement>('.chat-toast button')].find(
      (button) => button.textContent === '重新读取'
    )!
    retry.click()
    await flushPromises()
    expect(
      json.mock.calls.filter(([path]) => path.startsWith('/chat/work/tasks/detail'))
    ).toHaveLength(2)
    const cancel = new Event('cancel', { cancelable: true })
    dialog.dispatchEvent(cancel)
    await flushPromises()
    expect(cancel.defaultPrevented).toBe(true)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    const confirmation = [...document.querySelectorAll<HTMLButtonElement>('dialog button')].find(
      (button) => button.textContent === '继续编辑'
    )!
    confirmation.click()
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('缩放时保留草稿')
  })
})
