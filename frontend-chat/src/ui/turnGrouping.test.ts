import { describe, expect, it } from 'vitest'
import {
  conversationTurns,
  finalReady,
  modelCallCount,
  needsAttention,
  operationCount,
  processElapsed,
  processIsLive,
  processProgress,
  processStages,
  visibleProcesses
} from './conversationTimeline'
import type { Message, MessagePhase, Process } from '../protocol'

const user = (input: string): Message => ({
  message_id: input,
  message_version: 1,
  role: 'user',
  content: input,
  input_id: input
})

describe('公开过程的阶段与进度', () => {
  it('只合并相邻的相同 stage 和 Run，不按阶段编号重排交错记录', () => {
    const staged = (id: string, run: string, stage: string, sequence: number) => ({
      ...step(id, run, 'a', sequence),
      root_run_id: 'r',
      stage: { id: stage, index: 1 }
    })
    const [turn] = conversationTurns({
      messages: [user('a')],
      processes: [
        staged('1', 'r', 'a', 1),
        staged('2', 'r', 'a', 2),
        staged('3', 'r', 'b', 3),
        staged('4', 'r', 'a', 4),
        staged('5', 'child', 'a', 5),
        step('6', 'r', 'a', 6),
        step('7', 'r', 'a', 7)
      ]
    })
    expect(
      processStages(turn!).map((group) => group.processes.map((item) => item.process_id))
    ).toEqual([['1', '2'], ['3'], ['4'], ['5'], ['6'], ['7']])
    expect(processStages(turn!).map((group) => group.key)).toEqual(['1', '3', '4', '5', '6', '7'])
  })
  it('统计真实操作，说明、模型调用与公开校验不重复计数', () => {
    const processes = [
      'tool',
      'skill',
      'workflow',
      'memory',
      'knowledge',
      'delegation',
      'commentary',
      'reasoning',
      'validation'
    ].map((type, index) => ({
      ...step(String(index), 'r', 'a', index, index ? 'succeeded' : 'failed'),
      type
    }))
    expect(operationCount(processes)).toEqual({ total: 6, completed: 5 })
  })
  it('整轮终态优先于历史步骤失败，未知结果和过程事实仍保留', () => {
    const [turn] = conversationTurns({
      messages: [user('a'), answer('r', 'a')],
      processes: [step('查询资料', 'r', 'a', 1, 'running')]
    })
    expect(processProgress(turn!, { r: 'running' }).label).toBe('查询资料')
    expect(processProgress(turn!, { r: 'running' }, true).label).toBe('等待你的输入')
    turn!.processes[0]!.status = 'waiting'
    expect(processProgress(turn!, { r: 'running' }).label).toBe('等待执行结果')
    expect(processProgress(turn!, { r: 'cancelling' }).label).toBe('正在停止')
    expect(processProgress(turn!, { r: 'cancelled' }).label).toBe('已停止')
    expect(processProgress(turn!, { r: 'completed' }).label).toBe('执行完成')
    expect(processIsLive(turn!, { r: 'completed' })).toBe(false)
    turn!.processes[0]!.status = 'failed'
    expect(processProgress(turn!, { r: 'completed' }).label).toBe('执行完成')
    expect(processProgress(turn!, { r: 'cancelled' }).label).toBe('已停止')
    expect(processProgress(turn!, { r: 'failed' }).label).toBe('执行遇到问题')
    expect(processProgress(turn!, { r: 'running' }).label).toBe('正在处理')
    expect(turn!.processes[0]!.status).toBe('failed')
    turn!.processes[0]!.status = 'outcome_unknown'
    expect(processProgress(turn!, { r: 'completed' }).label).toBe('结果待核实')
    turn!.processes[0]!.status = 'waiting_hitl'
    expect(processProgress(turn!, { r: 'running' }).label).toBe('等待你的输入')
  })
  it('只从带时区的有效起止时间计算耗时，终态无结束时间不猜测', () => {
    const process = {
      ...step('p', 'r', 'a', 1, 'running'),
      started_at: '2026-10-10T08:00:00+08:00'
    }
    const now = Date.parse('2026-10-10T00:01:05Z')
    expect(processElapsed(process, now)).toBe('1 分 5 秒')
    expect(processElapsed(process, now, false)).toBe('')
    expect(
      processElapsed({ ...process, finished_at: '2026-10-10T00:00:03Z', status: 'succeeded' }, now)
    ).toBe('3 秒')
    for (const started_at of [undefined, 'invalid', '2026-10-10T08:00:00', '2026-10-10T00:02:00Z'])
      expect(processElapsed({ ...process, started_at }, now)).toBe('')
    expect(processElapsed({ ...process, finished_at: 'invalid' }, now)).toBe('')
    expect(processElapsed({ ...process, status: 'succeeded' }, now)).toBe('')
  })
})
const answer = (run: string, input: string, phase: MessagePhase = 'final'): Message => ({
  message_id: `${run}:answer`,
  message_version: 1,
  role: 'assistant',
  content: '答复',
  root_run_id: run,
  root_input_id: input,
  phase,
  state: 'completed'
})
const step = (
  id: string,
  run: string,
  input: string,
  sequence: number,
  status = 'succeeded'
): Process => ({
  process_id: id,
  run_id: run,
  root_run_id: run,
  root_input_id: input,
  sequence,
  ordinal: 0,
  updated_sequence: sequence,
  type: 'tool',
  status,
  presentation: { title: id, summary: '' }
})

describe('按本轮身份组织会话', () => {
  it('并发运行按 root_input_id 归属，工具按公开序号排序', () => {
    const turns = conversationTurns({
      messages: [user('a'), user('b'), answer('run-b', 'b'), answer('run-a', 'a')],
      processes: [
        step('late', 'run-a', 'a', 8),
        step('other', 'run-b', 'b', 6),
        step('early', 'run-a', 'a', 3)
      ]
    })
    expect(turns.map((turn) => turn.id)).toEqual(['a', 'b'])
    expect(turns[0]!.runIds).toEqual(['run-a'])
    expect(turns[0]!.processes.map((item) => item.process_id)).toEqual(['early', 'late'])
    expect(turns[1]!.processes.map((item) => item.process_id)).toEqual(['other'])
  })
  it('未取得最终消息的运行过程关联原输入，未知运行独立保留', () => {
    const turns = conversationTurns({
      messages: [user('a')],
      processes: [step('active', 'run-a', 'a', 3, 'running'), step('unknown', 'run-x', 'x', 5)]
    })
    expect(turns[0]!.processes[0]!.process_id).toBe('active')
    expect(finalReady(turns[0]!)).toBe(false)
    expect(turns[1]!.id).toBe('run:run-x')
    const restored = conversationTurns({
      messages: [user('a'), answer('run-x', 'x')],
      processes: [step('active', 'run-a', 'a', 3, 'running'), step('unknown', 'run-x', 'x', 5)]
    })
    expect(restored.find((turn) => turn.runIds.includes('run-x'))!.id).toBe('run:run-x')
  })
  it('公开说明不代表最终正文到达，失败或等待必须保留可见状态', () => {
    const [turn] = conversationTurns({
      messages: [user('a'), answer('r', 'a', 'commentary')],
      processes: [step('failed', 'r', 'a', 3, 'outcome_unknown')]
    })
    expect(finalReady(turn!)).toBe(false)
    expect(needsAttention(turn!)).toBe(true)
    turn!.messages.push(answer('r', 'a'))
    expect(finalReady(turn!)).toBe(true)
  })

  it('默认保留公开摘要和业务步骤，完整轨迹保持所有原身份及顺序', () => {
    const model = { ...step('model', 'r', 'a', 1), type: 'reasoning' }
    const commentary = {
      ...step('commentary', 'r', 'a', 2),
      type: 'commentary',
      presentation: { title: '工作摘要', summary: '正在生成演示文稿。' }
    }
    const rejected = {
      ...step('rejected', 'r', 'a', 3, 'failed'),
      presentation: { title: '执行代码', summary: '生成参数存在冲突，本次尚未执行。' }
    }
    const [turn] = conversationTurns({
      messages: [user('a'), answer('r', 'a')],
      processes: [rejected, commentary, model]
    })
    expect(visibleProcesses(turn!).map((process) => process.process_id)).toEqual([
      'commentary',
      'rejected'
    ])
    expect(visibleProcesses(turn!, true).map((process) => process.process_id)).toEqual([
      'model',
      'commentary',
      'rejected'
    ])
    expect(modelCallCount(turn!)).toBe(1)
    expect(needsAttention(turn!)).toBe(true)
    expect(turn!.processes).toHaveLength(3)
  })

  it('异常、等待和含公开内容的模型步骤不可被默认省略，后续任意成功不推断恢复', () => {
    const processes = ['running', 'failed', 'outcome_unknown', 'waiting', 'cancelled'].map(
      (status, index) => ({ ...step(status, 'r', 'a', index + 1, status), type: 'reasoning' })
    )
    processes.push({
      ...step('public-result', 'r', 'a', 6),
      type: 'reasoning',
      presentation: { title: '模型调用', summary: '已接收部分公开正文。' }
    })
    processes.push(step('later-success', 'r', 'a', 7))
    const [turn] = conversationTurns({
      messages: [user('a'), answer('r', 'a')],
      processes
    })
    expect(visibleProcesses(turn!)).toEqual(turn!.processes)
    expect(needsAttention(turn!)).toBe(true)
  })
})
