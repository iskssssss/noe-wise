import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskDependencyGraph from './TaskDependencyGraph.vue'
import type { WorkTask } from '../protocol'

const task = (id: string, dependencies: string[] = []): WorkTask => ({
  task_id: id,
  task_kind: 'plan',
  title: id,
  agent_name: '主助手',
  status: 'pending',
  details_access: 'not_started',
  depends_on: dependencies
})
describe('任务依赖DAG', () => {
  it('只画真实已知边，按依赖层级布局，同层保持原任务顺序', () => {
    const wrapper = mount(TaskDependencyGraph, {
      props: {
        tasks: [task('a'), task('b'), task('c', ['a', 'b', 'missing']), task('d', ['c'])]
      }
    })
    expect(
      wrapper
        .findAll('[data-edge-source]')
        .map((edge) => [edge.attributes('data-edge-source'), edge.attributes('data-edge-target')])
    ).toEqual([
      ['a', 'c'],
      ['b', 'c'],
      ['c', 'd']
    ])
    expect(wrapper.get('[data-task-id="a"]').attributes('transform')).toBe('translate(16,16)')
    expect(wrapper.get('[data-task-id="b"]').attributes('transform')).toBe('translate(16,108)')
    expect(wrapper.get('[data-task-id="c"]').attributes('transform')).toBe('translate(256,16)')
    expect(wrapper.get('[data-task-id="d"]').attributes('transform')).toBe('translate(496,16)')
    expect(wrapper.find('[data-task-id="missing"]').exists()).toBe(false)
  })
  it('状态刷新保留节点身份、布局和用户滚动，不自动缩放或居中', async () => {
    const tasks = [task('a'), task('b', ['a'])]
    const wrapper = mount(TaskDependencyGraph, { props: { tasks } })
    const element = wrapper.get('[data-task-id="b"]').element
    const scroll = wrapper.get('.task-graph-scroll').element
    scroll.scrollLeft = 120
    scroll.scrollTop = 50
    const bounds = wrapper.get('svg').attributes('viewBox')
    await wrapper.setProps({ tasks: [tasks[0]!, { ...tasks[1]!, status: 'outcome_unknown' }] })
    expect(wrapper.get('[data-task-id="b"]').element).toBe(element)
    expect(wrapper.get('[data-task-id="b"]').attributes('transform')).toBe('translate(256,16)')
    expect(wrapper.get('[data-task-id="b"]').attributes('aria-label')).toContain('结果待核实')
    expect(wrapper.get('svg').attributes('viewBox')).toBe(bounds)
    expect(scroll.scrollLeft).toBe(120)
    expect(scroll.scrollTop).toBe(50)
  })
  it('节点支持点击与键盘选择，读取期间不重复触发', async () => {
    const value = task('a')
    const wrapper = mount(TaskDependencyGraph, { props: { tasks: [value] } })
    const node = wrapper.get('[role="button"]')
    await node.trigger('click')
    await node.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('select')).toEqual([[value], [value]])
    await wrapper.setProps({ disabled: true })
    await node.trigger('click')
    expect(wrapper.emitted('select')).toHaveLength(2)
    expect(node.attributes('tabindex')).toBe('-1')
  })
  it('同层详情返回可恢复用户图形位置，不使用自动适配视口', () => {
    const wrapper = mount(TaskDependencyGraph, {
      props: { tasks: [task('a')], position: { left: 120, top: 50 } }
    })
    const scroll = wrapper.get('.task-graph-scroll').element
    expect(scroll.scrollLeft).toBe(120)
    expect(scroll.scrollTop).toBe(50)
    scroll.scrollLeft = 180
    wrapper.unmount()
    expect(wrapper.emitted('viewport')).toEqual([[{ left: 180, top: 50 }]])
  })
  it('循环依赖不伪造成DAG或无限布局，保留明确的核对提示', () => {
    const wrapper = mount(TaskDependencyGraph, {
      props: { tasks: [task('a', ['b']), task('b', ['a'])] }
    })
    expect(wrapper.find('svg').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('依赖存在冲突')
  })
})
