import { afterEach, describe, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { createClient, observe, readEvents, serviceBase, sign } from './client'
import type { StreamEvent } from './protocol'

afterEach(() => vi.unstubAllGlobals())
describe('独立聊天 HTTP 边界', () => {
  it('历史、写入和SSE均携带同一服务器签发终端用户凭证', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ code: 'OK', message: '成功', data: [], trace_id: 't' }))
      )
    vi.stubGlobal('fetch', fetch)
    const api = createClient('http://localhost', { mode: 'token', token: 'channel' })
    api.setIdentityToken('signed-user-credential')
    await api.json('/chat/history?session_id=one')
    await api.events('one', '', new AbortController().signal)
    for (const call of fetch.mock.calls) {
      const headers = call[1]!.headers as Headers
      expect(headers.get('X-Channel-Token')).toBe('channel')
      expect(headers.get('X-Chat-User-Token')).toBe('signed-user-credential')
    }
  })
  it('拒绝含凭证或非 HTTP 的服务地址', () => {
    expect(() => serviceBase('javascript:alert(1)')).toThrow()
    expect(() => serviceBase('https://user:password@example.com')).toThrow()
    expect(serviceBase('https://example.com/api/v1/')).toBe('https://example.com')
  })
  it('HMAC 使用实际编码的查询串和同一份正文', async () => {
    vi.stubGlobal('crypto', webcrypto)
    const url = new URL('https://example.test/api/v1/chat?name=%E4%B8%AD&x=a+b')
    const headers = await sign(
      { mode: 'hmac', apiKey: 'key', secret: 'secret' },
      'POST',
      url,
      '{"text":"中文"}',
      1700000000000
    )
    expect(headers.get('X-Timestamp')).toBe('1700000000')
    expect(headers.get('X-Api-Key')).toBe('key')
    expect(headers.get('X-Signature')).toMatch(/^[a-f0-9]{64}$/)
    const different = await sign(
      { mode: 'hmac', apiKey: 'key', secret: 'secret' },
      'POST',
      new URL(url.href.replace('a+b', 'a%20b')),
      '{"text":"中文"}',
      1700000000000
    )
    expect(headers.get('X-Signature')).not.toBe(different.get('X-Signature'))
  })
  it('POST 网络失败不自动重试', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('offline'))
    vi.stubGlobal('fetch', fetch)
    const api = createClient('http://localhost', { mode: 'token', token: 'private' })
    await expect(api.json('/chat', 'POST', { client_message_id: 'stable' })).rejects.toThrow(
      'offline'
    )
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0]![1]).toMatchObject({
      credentials: 'omit',
      redirect: 'error',
      body: '{"client_message_id":"stable"}'
    })
  })
  it('401 清除当前身份而 403 保留连接用于其他目标', async () => {
    const invalid = vi.fn()
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(
          new Response(
            JSON.stringify({
              code: 'FORBIDDEN',
              message: '目标不可访问',
              data: null,
              trace_id: 'trace'
            }),
            { status: 403 }
          )
        )
        .mockResolvedValueOnce(
          new Response(
            JSON.stringify({
              code: 'UNAUTHORIZED',
              message: '凭证失效',
              data: null,
              trace_id: 'trace'
            }),
            { status: 401 }
          )
        )
    )
    const api = createClient('http://localhost', { mode: 'token', token: 'private' }, invalid)
    await expect(api.json('/chat/agents')).rejects.toThrow('目标不可访问')
    expect(invalid).not.toHaveBeenCalled()
    await expect(api.json('/chat/agents')).rejects.toThrow('凭证失效')
    expect(invalid).toHaveBeenCalledOnce()
  })
  it('拒绝没有 envelope 的成功响应', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}')))
    await expect(
      createClient('http://localhost', { mode: 'token', token: 'private' }).json('/chat')
    ).rejects.toThrow('格式无效')
  })
})
describe('独立 SSE 分块解析', () => {
  it('逐字节中文、CRLF、多行 data 和心跳不改变事件', async () => {
    const wire =
      ':heartbeat\r\n\r\nid: opaque-1\r\nevent: message.delta\r\ndata: {"content":\r\ndata: "中文"}\r\n\r\n'
    const bytes = new TextEncoder().encode(wire)
    const events: StreamEvent[] = [],
      heartbeat = vi.fn()
    const stream = new ReadableStream({
      start(controller) {
        for (const byte of bytes) controller.enqueue(Uint8Array.of(byte))
        controller.close()
      }
    })
    await readEvents(
      new Response(stream, { headers: { 'content-type': 'text/event-stream' } }),
      (event) => events.push(event),
      heartbeat
    )
    expect(events).toEqual([{ id: 'opaque-1', event: 'message.delta', data: { content: '中文' } }])
    expect(heartbeat).toHaveBeenCalledOnce()
  })
  it('不提交网络中断时缺少事件结束分隔符的内容', async () => {
    const events: StreamEvent[] = []
    await readEvents(
      new Response('event: message.delta\ndata: {"content":"partial"}', {
        headers: { 'content-type': 'text/event-stream' }
      }),
      (event) => events.push(event)
    )
    expect(events).toEqual([])
  })
  it('错误 content-type 不当作成功事件流', async () => {
    await expect(
      readEvents(new Response('{}', { headers: { 'content-type': 'application/json' } }), () => {})
    ).rejects.toThrow('有效事件流')
  })
})

describe('过期游标只读恢复', () => {
  const conflict = (code: string, status = 409) =>
    new Response(
      JSON.stringify({
        code,
        message: '订阅拒绝',
        data: null,
        trace_id: 'trace'
      }),
      { status }
    )
  it('持续快照503达到既有上限后停止，不重订阅旧游标或提交输入', async () => {
    vi.useFakeTimers()
    try {
      const fetch = vi
        .fn()
        .mockResolvedValueOnce(conflict('EVENT_CURSOR_EXPIRED'))
        .mockResolvedValue(conflict('SNAPSHOT_UNAVAILABLE', 503))
      vi.stubGlobal('fetch', fetch)
      const client = createClient('http://localhost', { mode: 'token', token: 't' })
      const recover = vi.fn(
        async () => (await client.json<{ cursor: string }>('/snapshot', 'GET')).cursor
      )
      const stopped = observe(
        client,
        's',
        'old',
        new AbortController().signal,
        vi.fn(),
        vi.fn(),
        recover
      ).catch((error) => error)
      await vi.advanceTimersByTimeAsync(300000)
      expect(await stopped).toMatchObject({
        message: '实时连接暂时不可用，请点击重新连接恢复观察。'
      })
      expect(recover).toHaveBeenCalledTimes(9)
      expect(fetch).toHaveBeenCalledTimes(10)
      expect(fetch.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true)
      const count = fetch.mock.calls.length
      await vi.advanceTimersByTimeAsync(60000)
      expect(fetch).toHaveBeenCalledTimes(count)
    } finally {
      vi.useRealTimers()
    }
  })
  it('精确游标过期后使用权威游标重新订阅', async () => {
    const controller = new AbortController()
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(conflict('EVENT_CURSOR_EXPIRED'))
      .mockImplementationOnce(async () => {
        controller.abort()
        return new Response()
      })
    vi.stubGlobal('fetch', fetch)
    const recover = vi.fn(async () => 'fresh')
    await observe(
      createClient('http://localhost', { mode: 'token', token: 't' }),
      's',
      'old',
      controller.signal,
      vi.fn(),
      vi.fn(),
      recover
    )
    expect(recover).toHaveBeenCalledOnce()
    expect((fetch.mock.calls[1]![1].headers as Headers).get('Last-Event-ID')).toBe('fresh')
    expect(fetch.mock.calls.every((call) => call[1].method === 'GET')).toBe(true)
  })
  it.each([
    [409, 'OTHER_CONFLICT'],
    [403, 'EVENT_CURSOR_EXPIRED'],
    [401, 'UNAUTHORIZED']
  ])('拒绝 %s/%s 不读取历史或重连', async (status, code) => {
    const fetch = vi.fn().mockResolvedValue(conflict(String(code), Number(status)))
    vi.stubGlobal('fetch', fetch)
    const recover = vi.fn(async () => 'fresh')
    await expect(
      observe(
        createClient('http://localhost', { mode: 'token', token: 't' }),
        's',
        'old',
        new AbortController().signal,
        vi.fn(),
        vi.fn(),
        recover
      )
    ).rejects.toMatchObject({ code, status })
    expect(recover).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledOnce()
  })
  it.each(['unavailable'])('快照 %s 后只读重试成功，再用新游标订阅', async (failure) => {
    vi.useFakeTimers()
    try {
      const controller = new AbortController()
      let snapshots = 0
      let subscriptions = 0
      const fetch = vi.fn(async (input: string, init: RequestInit) => {
        if (new URL(String(input)).pathname.endsWith('/snapshot')) {
          snapshots++
          if (snapshots === 1) {
            if (failure === 'network') throw new TypeError('offline')
            return conflict('SNAPSHOT_UNAVAILABLE', 503)
          }
          return new Response(
            JSON.stringify({
              code: 'OK',
              message: '成功',
              trace_id: 'test',
              data: { cursor: 'fresh' }
            })
          )
        }
        subscriptions++
        if (subscriptions === 1) return conflict('EVENT_CURSOR_EXPIRED')
        expect((init.headers as Headers).get('Last-Event-ID')).toBe('fresh')
        controller.abort()
        return new Response()
      })
      vi.stubGlobal('fetch', fetch)
      const client = createClient('http://localhost', { mode: 'token', token: 't' })
      const observing = observe(
        client,
        's',
        'old',
        controller.signal,
        vi.fn(),
        vi.fn(),
        async () => (await client.json<{ cursor: string }>('/snapshot', 'GET')).cursor
      )
      await vi.advanceTimersByTimeAsync(2000)
      await observing
      expect(snapshots).toBe(2)
      expect(subscriptions).toBe(2)
      expect(fetch.mock.calls.every(([, init]) => init.method === 'GET')).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })
  it.each([400, 401, 403, 404, 409, 410])('恢复快照被 %s 拒绝时停止并保留错误', async (status) => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(conflict('EVENT_CURSOR_EXPIRED'))
      .mockResolvedValueOnce(conflict('SNAPSHOT_DENIED', status))
    vi.stubGlobal('fetch', fetch)
    const client = createClient('http://localhost', { mode: 'token', token: 't' })
    await expect(
      observe(
        client,
        's',
        'old',
        new AbortController().signal,
        vi.fn(),
        vi.fn(),
        async () => (await client.json<{ cursor: string }>('/snapshot', 'GET')).cursor
      )
    ).rejects.toMatchObject({ status, code: 'SNAPSHOT_DENIED' })
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('恢复快照重试等待期间取消，不再读取或订阅', async () => {
    vi.useFakeTimers()
    try {
      const controller = new AbortController()
      const fetch = vi
        .fn()
        .mockResolvedValueOnce(conflict('EVENT_CURSOR_EXPIRED'))
        .mockResolvedValue(conflict('SNAPSHOT_UNAVAILABLE', 503))
      vi.stubGlobal('fetch', fetch)
      const client = createClient('http://localhost', { mode: 'token', token: 't' })
      const observing = observe(
        client,
        's',
        'old',
        controller.signal,
        vi.fn(),
        vi.fn(),
        async () => (await client.json<{ cursor: string }>('/snapshot', 'GET')).cursor
      )
      const cancelled = observing.catch((error) => error)
      await vi.advanceTimersByTimeAsync(1)
      expect(fetch).toHaveBeenCalledTimes(2)
      controller.abort()
      await vi.advanceTimersByTimeAsync(5000)
      expect(await cancelled).toMatchObject({ name: 'AbortError' })
      expect(fetch).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })
  it('历史恢复期间取消后不再订阅', async () => {
    const controller = new AbortController()
    const fetch = vi.fn().mockResolvedValue(conflict('EVENT_CURSOR_EXPIRED'))
    vi.stubGlobal('fetch', fetch)
    await observe(
      createClient('http://localhost', { mode: 'token', token: 't' }),
      's',
      'old',
      controller.signal,
      vi.fn(),
      vi.fn(),
      async () => {
        controller.abort()
        return 'fresh'
      }
    )
    expect(fetch).toHaveBeenCalledOnce()
  })
  it('历史读取拒绝直接交付原错误', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(conflict('EVENT_CURSOR_EXPIRED')))
    const denied = new Error('历史不可访问')
    await expect(
      observe(
        createClient('http://localhost', { mode: 'token', token: 't' }),
        's',
        'old',
        new AbortController().signal,
        vi.fn(),
        vi.fn(),
        async () => {
          throw denied
        }
      )
    ).rejects.toBe(denied)
  })
  it('恢复快照的未分类网络错误原样交付，不扩大自动重试', async () => {
    const fetch = vi.fn().mockResolvedValue(conflict('EVENT_CURSOR_EXPIRED'))
    vi.stubGlobal('fetch', fetch)
    const failure = new TypeError('offline')
    const recover = vi.fn(async () => {
      throw failure
    })
    await expect(
      observe(
        createClient('http://localhost', { mode: 'token', token: 't' }),
        's',
        'old',
        new AbortController().signal,
        vi.fn(),
        vi.fn(),
        recover
      )
    ).rejects.toBe(failure)
    expect(recover).toHaveBeenCalledOnce()
    expect(fetch).toHaveBeenCalledOnce()
  })
})

describe('订阅流错误', () => {
  const streamError = (retryable: boolean) =>
    new Response(
      `event: error\ndata: ${JSON.stringify({ error_code: 'SESSION_ACCESS_DENIED', message: '会话访问权限已失效，请刷新权限', retryable })}\n\n`,
      { headers: { 'content-type': 'text/event-stream' } }
    )

  it('不可重试的流错误保留原因并停止订阅', async () => {
    const fetch = vi.fn().mockResolvedValue(streamError(false))
    vi.stubGlobal('fetch', fetch)
    const recover = vi.fn()
    await expect(
      observe(
        createClient('http://localhost', { mode: 'token', token: 't' }),
        's',
        'old',
        new AbortController().signal,
        vi.fn(),
        vi.fn(),
        recover
      )
    ).rejects.toMatchObject({
      code: 'SESSION_ACCESS_DENIED',
      message: '会话访问权限已失效，请刷新权限'
    })
    expect(fetch).toHaveBeenCalledOnce()
    expect(fetch.mock.calls[0]![1].method).toBe('GET')
    expect(recover).not.toHaveBeenCalled()
  })

  it('可重试的流错误沿用游标只读重连', async () => {
    vi.useFakeTimers()
    try {
      const controller = new AbortController()
      const fetch = vi
        .fn()
        .mockResolvedValueOnce(streamError(true))
        .mockImplementationOnce(async () => {
          controller.abort()
          return new Response()
        })
      vi.stubGlobal('fetch', fetch)
      const recover = vi.fn()
      const observing = observe(
        createClient('http://localhost', { mode: 'token', token: 't' }),
        's',
        'old',
        controller.signal,
        vi.fn(),
        vi.fn(),
        recover
      )
      await vi.advanceTimersByTimeAsync(2000)
      await observing
      expect(fetch).toHaveBeenCalledTimes(2)
      expect(fetch.mock.calls.every((call) => call[1].method === 'GET')).toBe(true)
      expect((fetch.mock.calls[1]![1].headers as Headers).get('Last-Event-ID')).toBe('old')
      expect(recover).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })
})
