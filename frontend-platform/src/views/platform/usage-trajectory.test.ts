import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import UsageTrajectory from './UsageTrajectory.vue'

const calls = vi.hoisted(() => ({ detail: vi.fn() }))
vi.mock('../../api', () => ({ api: { trajectories: { detail: calls.detail } } }))

const row = {
  origin_kind: 'native',
  tenant_id: '7',
  session_id: 's',
  root_input_id: 'root',
  run_id: 'run',
  attempt_id: 'attempt',
  invocation_id: 'invocation'
}
const data = {
  summary: { status: 'COMPLETED', tenant_id: '7', session_id: 's', root_input_id: 'root' },
  usage: {
    attempts: [
      {
        attempt_id: 'attempt',
        run_id: 'run',
        source_session_id: 's',
        source_root_input_id: 'root',
        invocation_id: 'invocation',
        model: 'deepseek-flash',
        trace_node_id: 'node'
      }
    ]
  }
}

describe('用量关联运行', () => {
  it('核对原调用后提供完整轨迹入口', async () => {
    calls.detail.mockResolvedValue(data)
    const wrapper = mount(UsageTrajectory, {
      props: { row: row as never },
      global: {
        stubs: {
          ElDrawer: { template: '<div><slot /></div>' },
          RouterLink: { name: 'RouterLink', props: ['to'], template: '<a><slot /></a>' }
        }
      }
    })
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(calls.detail).toHaveBeenCalledWith(
      { tenant_id: '7', session_id: 's', root_input_id: 'root' },
      expect.anything()
    )
    expect(wrapper.text()).toContain('已找到该用量对应的模型调用')
    expect(wrapper.getComponent({ name: 'RouterLink' }).props('to')).toMatchObject({
      query: { tenant_id: '7', root_input_id: 'root', node_id: 'node' }
    })
    wrapper.unmount()
  })
  it('关联不符时不提供轨迹链接，系统调用不显示入口', async () => {
    calls.detail.mockResolvedValue({
      ...data,
      usage: { attempts: [{ ...data.usage.attempts[0], run_id: 'other' }] }
    })
    const wrapper = mount(UsageTrajectory, {
      props: { row: row as never },
      global: {
        stubs: {
          ElDrawer: { template: '<div><slot /></div>' },
          RouterLink: { template: '<a><slot /></a>' }
        }
      }
    })
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('未找到与此记录匹配的模型调用')
    expect(wrapper.find('a').exists()).toBe(false)
    await wrapper.setProps({ row: { ...row, origin_kind: 'system' } as never })
    expect(wrapper.find('button').exists()).toBe(false)
    wrapper.unmount()
  })
})
