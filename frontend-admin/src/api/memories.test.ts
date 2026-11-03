import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './index'

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('Memory 管理 API 契约', () => {
  it('仅把调用方明确提供的范围确认写入审核 payload', async () => {
    const fetchMock = vi.fn().mockImplementation(
      async () =>
        new Response(
          JSON.stringify({ code: 'OK', message: '成功', data: {}, trace_id: 'scope-review-test' }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          }
        )
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValue('idem-review-scope') })

    await api.memories.review('fact/user', 3, true, 'USER')
    await api.memories.review('fact/user-agent', 4, true)
    await api.memories.review('fact/user', 3, false)

    expect(
      fetchMock.mock.calls.map(([, init]) => JSON.parse(String((init as RequestInit).body)))
    ).toEqual([
      {
        fact_id: 'fact/user',
        payload: { expected_revision: 3, approved: true, confirmed_scope: 'USER' }
      },
      { fact_id: 'fact/user-agent', payload: { expected_revision: 4, approved: true } },
      { fact_id: 'fact/user', payload: { expected_revision: 3, approved: false } }
    ])
  })

  it.each(['disconnect', 'unavailable'] as const)(
    '对%s保留原审核正文及幂等键供同请求恢复',
    async (failure) => {
      const fetchMock = vi.fn()
      if (failure === 'disconnect')
        fetchMock.mockRejectedValueOnce(new Error('connection interrupted'))
      else
        fetchMock.mockResolvedValueOnce(
          new Response(JSON.stringify({ code: 'PENDING', message: '结果待确认' }), { status: 503 })
        )
      fetchMock.mockResolvedValueOnce(
        new Response(JSON.stringify({ code: 'OK', message: '', data: {}, trace_id: 'recovered' }))
      )
      vi.stubGlobal('fetch', fetchMock)
      const randomUUID = vi.fn().mockReturnValue('original-' + failure)
      vi.stubGlobal('crypto', { randomUUID })
      const factId = 'pending-fact-' + failure
      await expect(api.memories.review(factId, 7, true)).rejects.toThrow()
      await api.memories.review(factId, 7, true)
      const first: RequestInit = fetchMock.mock.calls[0]![1]
      const retry: RequestInit = fetchMock.mock.calls[1]![1]
      expect(retry.body).toBe(first.body)
      expect(new Headers(retry.headers).get('Idempotency-Key')).toBe('original-' + failure)
      expect(new Headers(first.headers).get('Idempotency-Key')).toBe('original-' + failure)
      expect(randomUUID).toHaveBeenCalledTimes(1)
    }
  )

  it('按后端路径筛选列表，并为所有治理写入携带独立幂等键', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const fetchMock = vi.fn().mockImplementation(
      async () =>
        new Response(
          JSON.stringify({ code: 'OK', message: '成功', data: [], trace_id: 'memory-test' }),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          }
        )
    )
    vi.stubGlobal('fetch', fetchMock)
    const randomUUID = vi
      .fn()
      .mockReturnValueOnce('idem-review')
      .mockReturnValueOnce('idem-forget')
      .mockReturnValueOnce('idem-restore')
      .mockReturnValueOnce('idem-correct')
    vi.stubGlobal('crypto', { randomUUID })

    await api.memories.list({ status: 'PENDING CONFIRM' })
    await api.memories.list({
      scope: 'USER_AGENT',
      type: 'episode',
      agent_id: '7',
      end_user_id: 'user-a'
    })
    await api.memories.list({
      scope: 'USER',
      type: 'preference',
      end_user_id: 'user-a'
    })
    await api.memories.reviews()
    await api.memories.detail('fact/1')
    await api.memories.review('fact/1', 3, true)
    await api.memories.forget('fact/1', 4)
    await api.memories.restore('fact/1', 5)
    await api.memories.correct('fact/1', {
      expected_revision: 6,
      subject: 'user:1',
      predicate: 'theme',
      object_json: '{"value":"dark"}',
      confidence: 0.9,
      importance: 0.8
    })

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/memories?status=PENDING%20CONFIRM',
      '/api/v1/memories?scope=USER_AGENT&type=episode&agent_id=7&end_user_id=user-a',
      '/api/v1/memories?scope=USER&type=preference&end_user_id=user-a',
      '/api/v1/memories/reviews',
      '/api/v1/memories/detail?fact_id=fact%2F1',
      '/api/v1/memories/review',
      '/api/v1/memories/forget',
      '/api/v1/memories/restore',
      '/api/v1/memories/correct'
    ])
    const writes = fetchMock.mock.calls.slice(5) as [string, RequestInit][]
    expect(writes.map(([, init]) => (init.headers as Headers).get('Idempotency-Key'))).toEqual([
      'idem-review',
      'idem-forget',
      'idem-restore',
      'idem-correct'
    ])
    expect(writes.map(([, init]) => JSON.parse(String(init.body)).fact_id)).toEqual(
      Array(4).fill('fact/1')
    )
    expect(writes.map(([, init]) => JSON.parse(String(init.body)).payload)).toEqual([
      { expected_revision: 3, approved: true },
      { expected_revision: 4 },
      { expected_revision: 5 },
      {
        expected_revision: 6,
        subject: 'user:1',
        predicate: 'theme',
        object_json: '{"value":"dark"}',
        confidence: 0.9,
        importance: 0.8
      }
    ])
  })
})
