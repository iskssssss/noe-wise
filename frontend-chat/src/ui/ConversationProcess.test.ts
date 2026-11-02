import { enableAutoUnmount, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ConversationProcess from './ConversationProcess.vue'
import type { Process } from '../protocol'
import type { ConversationTurn } from './conversationTimeline'

enableAutoUnmount(afterEach)
afterEach(() => vi.useRealTimers())

/** 夹具显式指定运行身份、阶段和时刻，不从展示结果反推事实。 */
function step(id: string, status = 'succeeded', stage = 1): Process {
  return {
    process_id: id,
    run_id: 'r',
    root_run_id: 'r',
    sequence: stage,
    ordinal: 0,
    updated_sequence: stage + 10,
    type: 'tool',
    status,
    stage: { id: `stage-${stage}`, index: stage },
    actor_name: '资料分析员',
    started_at: '2026-10-10T08:00:00+08:00',
    ...(status === 'succeeded' ? { finished_at: '2026-10-10T08:00:02+08:00' } : {}),
    presentation: { title: id, summary: `公开摘要 ${id}` }
  }
}
function render(processes: Process[]) {
  const turn: ConversationTurn = { id: 'turn', runIds: ['r'], messages: [], processes }
  return mount(ConversationProcess, {
    props: {
      turn,
      runStates: { r: 'running' },
      pending: false,
      connection: '已连接',
      locked: false,
      targetName: '主助手'
    }
  })
}

describe('执行阶段交互', () => {
  it('普通等待保持中性，不作为需要用户处理的警告', () => {
    const wrapper = render([step('等待成员结果', 'waiting')])
    expect(wrapper.get('.process-progress-label').text()).toBe('等待执行结果')
    expect(wrapper.get('.process-group').classes()).not.toContain('needs-attention')
    expect(wrapper.get('.process-group').classes()).not.toContain('has-warning')
    expect(wrapper.get('[data-process-id="等待成员结果"]').classes()).not.toContain(
      'needs-attention'
    )
  })
  it('默认按真实操作命名，成功摘要不制造阶段，完整轨迹保留原序及失败', async () => {
    const summary = (id: string, stage: number): Process => ({
      ...step(id, 'succeeded', stage),
      type: 'commentary'
    })
    const wrapper = render([
      summary('准备资料摘要', 1),
      step('读取订单', 'succeeded', 2),
      summary('读取后摘要', 3),
      { ...step('模型准备', 'succeeded', 4), type: 'reasoning' },
      step('核对金额', 'failed', 5),
      summary('失败后摘要', 6)
    ])
    expect(wrapper.findAll('.stage-toggle').map((item) => item.text())).toEqual([
      '读取订单已完成1 项操作',
      '核对金额有步骤失败1 项操作'
    ])
    expect(wrapper.get('[data-process-id="准备资料摘要"]').text()).toContain(
      '公开摘要 准备资料摘要'
    )
    expect(wrapper.get('[data-process-id="模型准备"]').text()).toContain('公开摘要 模型准备')
    expect(wrapper.get('[data-process-id="核对金额"]').text()).toContain('公开摘要 核对金额')
    expect(wrapper.text()).not.toContain('第 1 轮执行')
    await wrapper.get('[data-process-trace-toggle]').trigger('click')
    expect(
      wrapper.findAll('[data-process-id]').map((item) => item.attributes('data-process-id'))
    ).toEqual(['准备资料摘要', '读取订单', '读取后摘要', '模型准备', '核对金额', '失败后摘要'])
    expect(wrapper.findAll('.stage-toggle')[0]!.text()).toContain('第 1 轮执行')
    expect(wrapper.findAll('.stage-toggle')[0]!.attributes('aria-expanded')).toBe('true')
    expect(wrapper.text()).not.toContain('阶段 1')
  })
  it('公开摘要直接可读且不制造业务阶段，空步骤仍有完整轨迹入口', async () => {
    const wrapper = render([{ ...step('工作说明'), type: 'commentary' }])
    expect(wrapper.find('.stage-toggle').exists()).toBe(false)
    expect(wrapper.get('[data-process-id="工作说明"]').isVisible()).toBe(true)
    expect(wrapper.text()).toContain('公开摘要 工作说明')
    const empty = render([
      { ...step('空模型记录'), type: 'reasoning', presentation: { title: '模型调用', summary: '' } }
    ])
    expect(empty.find('[data-process-id="空模型记录"]').exists()).toBe(false)
    await empty.get('[data-process-trace-toggle]').trigger('click')
    expect(empty.get('[data-process-id="空模型记录"]').isVisible()).toBe(true)
    const results = render([
      { ...step('摘要失败', 'failed'), type: 'commentary' },
      {
        ...step('执行产出', 'succeeded', 2),
        stage: undefined,
        type: 'reasoning',
        presentation: {
          title: '执行产出',
          summary: '真实公开结果',
          results: [{ kind: 'text', format: 'plain', text: '结果正文' }]
        }
      }
    ])
    expect(results.get('[data-process-id="摘要失败"]').isVisible()).toBe(true)
    expect(results.get('[data-process-id="执行产出"]').isVisible()).toBe(true)
  })
  it('状态和完整轨迹处于同一工具栏，独立按钮均可访问且折叠后仍可打开轨迹', async () => {
    const wrapper = render([
      { ...step('模型调用'), type: 'reasoning', presentation: { title: '模型调用', summary: '' } },
      step('查询资料', 'running')
    ])
    const toolbar = wrapper.get('.process-toolbar')
    const toggle = toolbar.get('.process-toggle')
    const trace = toolbar.get('[data-process-trace-toggle]')
    expect(toggle.attributes('aria-label')).toContain('查询资料')
    expect(toggle.attributes('aria-label')).toContain('1 项操作')
    expect(toggle.attributes('aria-controls')).toBe(
      wrapper.get('.process-content').attributes('id')
    )
    expect(trace.element.closest('button')).toBe(trace.element)
    expect(trace.element.parentElement?.closest('button')).toBeNull()
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(trace.isVisible()).toBe(true)
    await trace.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(trace.attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('[data-process-id="模型调用"]').exists()).toBe(true)
  })
  it('失败原因就近展示一次，仅在整体收起后补充摘要，单纯摘要不生成详情入口', async () => {
    const process = step('登记计划', 'failed')
    process.presentation.summary = '登记字段缺失，尚未执行写入。'
    process.presentation.results = [
      { kind: 'text', format: 'plain', text: process.presentation.summary }
    ]
    const wrapper = render([process])
    await wrapper.setProps({ runStates: { r: 'cancelled' } })
    expect(wrapper.get('.process-progress-label').text()).toBe('已停止')
    expect(wrapper.get('[data-process-id="登记计划"]').text()).toContain('失败')
    expect(wrapper.text().split(process.presentation.summary)).toHaveLength(2)
    expect(wrapper.find('.step-detail-toggle').exists()).toBe(false)
    expect(wrapper.find('.attention-summary').exists()).toBe(false)
    await wrapper.get('.process-toggle').trigger('click')
    expect(wrapper.find('[data-process-id="登记计划"]').exists()).toBe(false)
    expect(wrapper.get('.attention-summary').text()).toBe(process.presentation.summary)
    expect(wrapper.text().split(process.presentation.summary)).toHaveLength(2)
  })
  it('整轮完成保留失败及纠正后的成功操作，顶栏按真实运行终态显示', async () => {
    const wrapper = render([step('首次登记', 'failed'), step('纠正登记', 'succeeded', 2)])
    await wrapper.setProps({
      runStates: { r: 'completed' },
      turn: {
        ...wrapper.props('turn'),
        messages: [
          {
            message_id: 'final',
            message_version: 1,
            role: 'assistant',
            content: '登记完成',
            phase: 'final',
            state: 'completed'
          }
        ]
      }
    })
    expect(wrapper.get('.process-progress-label').text()).toBe('执行完成')
    expect(wrapper.get('.process-operation-count').text()).toBe('2 项执行记录')
    expect(wrapper.get('[data-process-id="首次登记"]').attributes('data-process-state')).toBe(
      'failed'
    )
    await wrapper.findAll('.stage-toggle')[1]!.trigger('click')
    expect(wrapper.get('[data-process-id="纠正登记"]').attributes('data-process-state')).toBe(
      'succeeded'
    )
  })
  it('已纠正的动作拒绝不提升为对话错误，终态改为执行记录计数但原失败仍在详情', async () => {
    const failed = step('首次完成步骤', 'failed')
    failed.presentation.summary = '尚未提供本步骤的有效执行结果'
    const wrapper = render([failed, step('补齐后完成步骤', 'succeeded', 2)])
    await wrapper.get('.process-toggle').trigger('click')
    expect(wrapper.find('.attention-summary').exists()).toBe(false)
    expect(wrapper.get('.process-operation-count').text()).toBe('已完成 1/2 项操作')
    await wrapper.setProps({ runStates: { r: 'completed' } })
    expect(wrapper.get('.process-operation-count').text()).toBe('2 项执行记录')
    expect(wrapper.text()).not.toContain('尚未提供本步骤的有效执行结果')
    await wrapper.get('.process-toggle').trigger('click')
    expect(wrapper.get('[data-process-id="首次完成步骤"]').text()).toContain(
      '尚未提供本步骤的有效执行结果'
    )
    await wrapper.get('.process-toggle').trigger('click')
    await wrapper.setProps({ runStates: { r: 'failed' } })
    expect(wrapper.get('.attention-summary').text()).toBe('尚未提供本步骤的有效执行结果')
  })
  it('模型调用精简视图不挂载空摘要和阶段边框，完整轨迹仍可查看', async () => {
    const wrapper = render([
      { ...step('模型调用'), type: 'reasoning', presentation: { title: '模型调用', summary: '' } }
    ])
    expect(wrapper.find('.process-empty-summary').exists()).toBe(false)
    expect(wrapper.find('.process-stages').exists()).toBe(false)
    expect(wrapper.get('[data-process-trace-toggle]').text()).toContain('1 次模型调用')
    await wrapper.get('[data-process-trace-toggle]').trigger('click')
    expect(wrapper.find('[data-process-id="模型调用"]').exists()).toBe(true)
  })
  it('纯聊天完成后点击状态打开共享模型轨迹，后续点击仍可收起及重新展开', async () => {
    const wrapper = render([
      { ...step('模型调用'), type: 'reasoning', presentation: { title: '模型调用', summary: '' } }
    ])
    await wrapper.setProps({
      traceExpanded: false,
      runStates: { r: 'completed' },
      turn: {
        ...wrapper.props('turn'),
        messages: [
          {
            message_id: 'final',
            message_version: 1,
            role: 'assistant',
            content: '你好',
            phase: 'final',
            state: 'completed'
          }
        ]
      }
    })
    const toggle = wrapper.get('.process-toggle')
    expect(toggle.attributes('aria-label')).toContain('执行完成')
    expect(toggle.attributes('aria-label')).toContain('1 次模型调用')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-process-id]').exists()).toBe(false)
    await toggle.trigger('click')
    expect(wrapper.emitted('trace')).toEqual([[true]])
    await wrapper.setProps({ traceExpanded: true })
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[data-process-id="模型调用"]').isVisible()).toBe(true)
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-process-id]').exists()).toBe(false)
    await toggle.trigger('click')
    expect(wrapper.get('[data-process-id="模型调用"]').isVisible()).toBe(true)
    expect(wrapper.emitted('trace')).toEqual([[true]])
  })
  it('完整轨迹开启时延迟挂载的已完成片段保留可见调用，手动收起后不被更新覆盖', async () => {
    const turn: ConversationTurn = {
      id: 'turn',
      runIds: ['r'],
      messages: [
        {
          message_id: 'final',
          message_version: 1,
          role: 'assistant',
          content: '你好',
          phase: 'final',
          state: 'completed'
        }
      ],
      processes: [
        {
          ...step('后续模型调用'),
          type: 'reasoning',
          presentation: { title: '模型调用', summary: '' }
        }
      ]
    }
    const wrapper = mount(ConversationProcess, {
      props: {
        turn,
        runStates: { r: 'completed' },
        pending: false,
        locked: false,
        continuation: true,
        traceExpanded: true
      }
    })
    expect(wrapper.get('.process-toggle').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[data-process-id="后续模型调用"]').isVisible()).toBe(true)
    await wrapper.get('.process-toggle').trigger('click')
    await wrapper.setProps({
      turn: { ...turn, processes: [{ ...turn.processes[0]!, updated_sequence: 99 }] }
    })
    expect(wrapper.get('.process-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-process-id]').exists()).toBe(false)
  })
  it('没有执行步骤时展开给出事实为空的提示', async () => {
    const wrapper = render([])
    expect(wrapper.get('.process-empty-state').text()).toBe('暂无可展示的执行步骤。')
    await wrapper.get('.process-toggle').trigger('click')
    expect(wrapper.find('.process-empty-state').exists()).toBe(false)
    await wrapper.get('.process-toggle').trigger('click')
    expect(wrapper.get('.process-empty-state').isVisible()).toBe(true)
    expect(wrapper.emitted('trace')).toBeUndefined()
  })
  it('阶段标题区分排队和输入等待，不将未识别状态推断为已完成', async () => {
    const wrapper = render([step('排队操作', 'queued')])
    expect(wrapper.get('.stage-toggle').text()).toContain('排队中')
    await wrapper.setProps({
      turn: { ...wrapper.props('turn'), processes: [step('排队操作', 'waiting_hitl')] }
    })
    expect(wrapper.get('.stage-toggle').text()).toContain('等待你的输入')
    await wrapper.setProps({
      turn: { ...wrapper.props('turn'), processes: [step('排队操作', 'unrecognized')] }
    })
    expect(wrapper.get('.stage-toggle').text()).toContain('状态待同步')
    expect(wrapper.get('.stage-toggle').text()).not.toContain('已完成')
  })
  it('完成阶段默认精简，当前阶段展开，完整轨迹不改变操作统计且可单独收起', async () => {
    const model = {
      ...step('模型调用'),
      type: 'reasoning',
      presentation: { title: '模型调用', summary: '' }
    }
    const wrapper = render([model, step('查询资料'), step('生成报告', 'running', 2)])
    expect(wrapper.find('[data-process-id="查询资料"]').exists()).toBe(false)
    expect(wrapper.find('[data-process-id="生成报告"]').exists()).toBe(true)
    expect(wrapper.get('.process-operation-count').text()).toBe('已完成 1/2 项操作')
    await wrapper.get('[data-process-trace-toggle]').trigger('click')
    expect(wrapper.findAll('[data-process-id]')).toHaveLength(3)
    expect(wrapper.get('.process-operation-count').text()).toBe('已完成 1/2 项操作')
    await wrapper.findAll('.stage-toggle')[0]!.trigger('click')
    expect(wrapper.findAll('.stage-toggle')[0]!.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-process-id="模型调用"]').exists()).toBe(false)
  })
  it('阶段手动展开保持到后续更新，已完成步骤耗时和执行者可见', async () => {
    const wrapper = render([step('查询资料')])
    await wrapper.get('.stage-toggle').trigger('click')
    expect(wrapper.get('[data-process-id="查询资料"]').text()).toContain('2 秒')
    expect(wrapper.text()).toContain('资料分析员')
    await wrapper.setProps({
      turn: { ...wrapper.props('turn'), processes: [{ ...step('查询资料'), updated_sequence: 99 }] }
    })
    expect(wrapper.get('.stage-toggle').attributes('aria-expanded')).toBe('true')
    await wrapper.get('.process-reference').trigger('click')
    expect(wrapper.emitted('reference')?.[0]).toEqual([
      {
        label: '查询资料',
        source: {
          kind: 'process',
          source: { kind: 'session' },
          process_id: '查询资料',
          updated_sequence: 99
        }
      }
    ])
  })
  it('详情按需挂载，纯文本不解释为 Markdown，根过程收起卸载详情', async () => {
    const process = step('详细操作', 'running')
    process.presentation.results = [
      { kind: 'text', format: 'plain', text: '**保留原文** <b>不是 HTML</b>' },
      { kind: 'text', format: 'markdown', text: '**公开结果**' },
      {
        kind: 'table',
        columns: [{ key: 'name', label: '来源' }],
        rows: [['原始资料'], [null], ['']]
      }
    ]
    process.presentation.references = [{ label: '公开来源', source: { kind: 'knowledge' } }]
    const wrapper = render([process])
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('保留原文')
    expect(wrapper.text()).not.toContain('公开来源')
    await wrapper.get('.step-detail-toggle').trigger('click')
    expect(wrapper.get('.step-details pre').text()).toBe('**保留原文** <b>不是 HTML</b>')
    expect(wrapper.get('.step-details strong').text()).toBe('公开结果')
    expect(wrapper.get('table').text()).toContain('原始资料')
    expect(wrapper.findAll('tbody td').map((cell) => cell.text())).toEqual(['原始资料', '—', ''])
    expect(wrapper.get('tbody td[aria-label="无值"]').text()).toBe('—')
    expect(wrapper.text()).toContain('公开来源')
    await wrapper.get('.step-detail-toggle').trigger('click')
    expect(wrapper.find('table').exists()).toBe(false)
    await wrapper.get('.step-detail-toggle').trigger('click')
    await wrapper.get('.process-toggle').trigger('click')
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.find('[data-process-id]').exists()).toBe(false)
  })
  it('可直接引用公开来源，保留来源身份与版本并遵守输入锁定', async () => {
    const process = step('查询资料', 'running')
    const reference = {
      label: '订单详情',
      source: {
        kind: 'message',
        source: { kind: 'session' },
        message_id: 'm-1',
        message_version: 7
      }
    }
    process.presentation.references = [reference]
    const wrapper = render([process])
    await wrapper.get('.step-detail-toggle').trigger('click')
    await wrapper.get('button[aria-label="引用来源：订单详情"]').trigger('click')
    expect(wrapper.emitted('reference')).toEqual([[reference]])
    await wrapper.setProps({ locked: true })
    expect(
      wrapper.get('button[aria-label="引用来源：订单详情"]').attributes('disabled')
    ).toBeDefined()
    await wrapper.get('button[aria-label="引用来源：订单详情"]').trigger('click')
    expect(wrapper.emitted('reference')).toHaveLength(1)
  })

  it('异常和 Pending 折叠后同时保留，后续根运行成功不会抹除未知结果', async () => {
    const process = step('外部写入', 'outcome_unknown')
    process.presentation.summary = '尚未核实外部操作结果，请勿重复执行。'
    const wrapper = render([process])
    await wrapper.setProps({ pending: true, runStates: { r: 'completed' } })
    await wrapper.get('.process-toggle').trigger('click')
    expect(wrapper.get('.process-progress-label').text()).toBe('结果待核实')
    expect(wrapper.text()).toContain('请勿重复执行')
    expect(wrapper.get('.pending-summary').text()).toContain('等待你的输入')
    expect(wrapper.find('[data-process-id]').exists()).toBe(false)
  })
  it('重连是同步提示，不伪造执行失败', async () => {
    const wrapper = render([step('读取资料', 'running')])
    await wrapper.setProps({ connection: '正在重连' })
    expect(wrapper.get('.process-progress-label').text()).toBe('读取资料')
    expect(wrapper.get('.process-connection-notice').text()).toContain('执行状态等待同步')
    expect(wrapper.find('.attention-summary').exists()).toBe(false)
    await wrapper.setProps({ connection: '已连接' })
    expect(wrapper.find('.process-connection-notice').exists()).toBe(false)
  })
  it('只持有一个计时器，终态和卸载清理，缺失时间不创建计时器', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-10T00:00:05Z'))
    const wrapper = render([step('读取资料', 'running')])
    expect(wrapper.get('.process-elapsed').text()).toContain('5 秒')
    expect(vi.getTimerCount()).toBe(1)
    vi.advanceTimersByTime(2000)
    await nextTick()
    expect(wrapper.get('.process-elapsed').text()).toContain('7 秒')
    await wrapper.setProps({ runStates: { r: 'completed' } })
    expect(vi.getTimerCount()).toBe(0)
    expect(wrapper.find('.step-elapsed').exists()).toBe(false)
    await wrapper.setProps({ runStates: { r: 'running' } })
    expect(vi.getTimerCount()).toBe(1)
    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)
    const missing = render([{ ...step('无时刻', 'running'), started_at: undefined }])
    expect(missing.find('.step-elapsed').exists()).toBe(false)
    expect(vi.getTimerCount()).toBe(0)
  })
})
