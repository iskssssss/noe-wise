import { effectScope } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import {
  parseChatControlCommand,
  useChatControlCommands,
  type ChatControlApi
} from './chatCommands'

describe('目标控制命令', () => {
  it('创建目标默认 256 轮，显式轮数支持至 1024', () => {
    expect(parseChatControlCommand('/goal 完成交付')).toEqual({
      kind: 'create',
      objective: '完成交付',
      maxRounds: 256
    })
    expect(parseChatControlCommand('/goal --rounds 1024 完成交付')).toEqual({
      kind: 'create',
      objective: '完成交付',
      maxRounds: 1024
    })
  })
  it('越界轮数给出实际支持范围且不创建目标', () => {
    for (const rounds of [0, 1025]) {
      expect(parseChatControlCommand(`/goal --rounds ${rounds} 完成交付`)).toMatchObject({
        kind: 'help',
        message: expect.stringContaining('--rounds 1-1024')
      })
    }
  })
})

describe('命令未确认后的恢复指引', () => {
  function fixture() {
    const api: ChatControlApi = {
      goals: vi.fn().mockResolvedValue({ goal: null }),
      createGoal: vi.fn().mockRejectedValue(new Error('network')),
      pauseGoal: vi.fn(),
      resumeGoal: vi.fn(),
      delivery: vi.fn().mockRejectedValue(new Error('network'))
    }
    const scope = effectScope()
    const controls = scope.run(() => useChatControlCommands(() => 'session'))!
    return { api, scope, controls }
  }

  it('写命令先核对状态，原样重试仍使用原身份，查询不会创建目标', async () => {
    const { api, scope, controls } = fixture()
    try {
      await controls.execute(api, 's', '/goal 完成交付')
      expect(controls.feedback.value).toContain('操作结果尚未确认')
      expect(controls.feedback.value).toContain('/goal status')
      expect(controls.feedback.value).toContain('原样发送原命令')
      expect(controls.feedback.value).not.toContain('刷新')
      await controls.execute(api, 's', '/goal status')
      expect(api.createGoal).toHaveBeenCalledTimes(1)
      await controls.execute(api, 's', '/goal 完成交付')
      const calls = vi.mocked(api.createGoal).mock.calls
      expect(calls[1]![1]).toBe(calls[0]![1])
    } finally {
      scope.stop()
    }
  })

  it.each([
    ['/goal status', '/goal status'],
    ['/delivery', '/delivery']
  ])('只读命令 %s 失败后明确指向再次查询', async (command, next) => {
    const { api, scope, controls } = fixture()
    vi.mocked(api.goals).mockRejectedValue(new Error('network'))
    try {
      await controls.execute(api, 's', command, 'run')
      expect(controls.feedback.value).toContain(`可再次发送 ${next} 查询`)
      expect(api.createGoal).not.toHaveBeenCalled()
      expect(api.pauseGoal).not.toHaveBeenCalled()
      expect(api.resumeGoal).not.toHaveBeenCalled()
    } finally {
      scope.stop()
    }
  })

  it('创建响应未返回目标时仅确认请求提交，并给出状态查询入口', async () => {
    const { api, scope, controls } = fixture()
    vi.mocked(api.createGoal).mockResolvedValue({
      goal: null,
      continuation_enabled: false,
      completion_pending: false,
      publication: null
    })
    try {
      await controls.execute(api, 's', '/goal 完成交付')
      expect(controls.feedback.value).toBe('目标请求已提交，可发送 /goal status 查询当前状态')
    } finally {
      scope.stop()
    }
  })
})
