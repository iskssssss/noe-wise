import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHttpClient } from '../utils/http'
import { createOpenPlatformClient } from './v1'
const query = { group_binding_id: '41', reader_end_user_id: '31', cursor: 'before' }
const client = () =>
  createOpenPlatformClient(
    createHttpClient({
      base: '/api/v1',
      accessTokenKey: 'test.access',
      refreshTokenKey: 'test.refresh',
      authHeader: (token) => ({ Authorization: `Bearer ${token}` }),
      refreshPath: '/auth/refresh',
      isAuthPath: () => false
    })
  )
afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})
describe('群事件观察', () => {
  it('真实请求层向 SDK 保留流式 204，同时普通 204 仍为空结果', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetch)
    const api = client()
    const raw = await api.groups.stream(query)
    expect(raw).toBeInstanceOf(Response)
    expect(raw.status).toBe(204)
    expect(await api.groups.observe(query, vi.fn())).toEqual({
      state: 'identity_undisclosable',
      cursor: 'before',
      message: '群事件身份无法公开，请保留原游标并联系机构管理员人工核对。'
    })
    expect(fetch).toHaveBeenCalledTimes(2)
    const http = createHttpClient({
      base: '/api/v1',
      accessTokenKey: 'a',
      refreshTokenKey: 'r',
      authHeader: () => ({}),
      refreshPath: '/refresh',
      isAuthPath: () => false
    })
    expect(await http.get('/ordinary')).toBeUndefined()
  })
  it('无 ID 的不可重试帧停止消费，保留最后确认事件 ID，不发重连请求', async () => {
    const body = [
      'id: safe-1\nevent: message.delta\ndata: {"session_id":"s","event_id":"safe-1","session_sequence":1,"event_version":1}\n\n',
      'event: error\ndata: {"error_code":"GROUP_EVENT_ID_UNDISCLOSABLE","retryable":false}\n\n',
      'id: later\nevent: message.delta\ndata: {}\n\n'
    ].join('')
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response(body, { headers: { 'Content-Type': 'text/event-stream' } }))
    vi.stubGlobal('fetch', fetch)
    const onEvent = vi.fn()
    expect(await client().groups.observe(query, onEvent)).toEqual({
      state: 'identity_undisclosable',
      cursor: 'safe-1',
      message: '群事件身份无法公开，请保留原游标并联系机构管理员人工核对。'
    })
    expect(onEvent).toHaveBeenCalledTimes(1)
    expect(onEvent.mock.calls[0]![0].id).toBe('safe-1')
    expect(fetch).toHaveBeenCalledOnce()
  })
  it('损坏事件不分发、不推进 ID，并返回可诊断状态', async () => {
    const body =
      'id: safe-1\nevent: message.delta\ndata: {"session_id":"s","event_id":"safe-1","session_sequence":1,"event_version":1}\n\nid: bad-2\nevent: message.delta\ndata: {broken\n\n'
    const fetch = vi.fn().mockResolvedValue(new Response(body))
    vi.stubGlobal('fetch', fetch)
    const onEvent = vi.fn()
    expect(await client().groups.observe(query, onEvent)).toEqual({
      state: 'invalid_event',
      cursor: 'safe-1',
      message: '事件响应格式无效，请保留原游标并重新核对。'
    })
    expect(onEvent).toHaveBeenCalledTimes(1)
    expect(fetch).toHaveBeenCalledOnce()
  })
  it('损坏事件终止并取消仍在途的读取', async () => {
    const cancel = vi.fn()
    const body = new ReadableStream({
      start(stream) {
        stream.enqueue(new TextEncoder().encode('id: bad\ndata: {broken\n\n'))
      },
      cancel
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body)))
    expect(await client().groups.observe(query, vi.fn())).toMatchObject({
      state: 'invalid_event',
      cursor: 'before'
    })
    expect(cancel).toHaveBeenCalledOnce()
  })
  it('204 使用 Last-Event-ID 原游标', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    const result = await client().groups.observe(
      { group_binding_id: '41', reader_end_user_id: '31' },
      vi.fn(),
      { headers: { 'Last-Event-ID': 'header-cursor' } }
    )
    expect(result).toMatchObject({ state: 'identity_undisclosable', cursor: 'header-cursor' })
  })
  it.each([
    'id: bad-2\nevent: message.delta\ndata: {}',
    'id: bad-2\nevent: message.delta\ndata: {}\n',
    'id: bad-2\nevent: message.delta\ndata: {"event_id":"different"}\n\n',
    'id: bad-2\nevent: message.delta\ndata: {}\n\n',
    'id: bad-2\nevent: message.delta\ndata: {"event_id":"bad-2","session_sequence":2,"event_version":1}\n\n',
    'id: bad-2\nevent: message.delta\ndata: {"event_id":"bad-2","session_id":"s","event_version":1}\n\n',
    'id: bad-2\nevent: message.delta\ndata: {"event_id":"bad-2","session_id":"s","session_sequence":2}\n\n'
  ])('残缺帧或身份不一致不推进游标', async (tail) => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(new Response('id: safe-1\nevent: message.delta\ndata: {"session_id":"s","event_id":"safe-1","session_sequence":1,"event_version":1}\n\n' + tail))
    )
    const onEvent = vi.fn()
    expect(await client().groups.observe(query, onEvent)).toMatchObject({
      state: 'invalid_event',
      cursor: 'safe-1'
    })
    expect(onEvent).toHaveBeenCalledTimes(1)
  })
  it('外部取消不伪造正常结束', async () => {
    const controller = new AbortController()
    const body = new ReadableStream({
      start(stream) {
        controller.signal.addEventListener('abort', () => stream.close(), { once: true })
      }
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body)))
    const result = client().groups.observe(query, vi.fn(), { signal: controller.signal })
    await Promise.resolve()
    controller.abort(new DOMException('cancelled', 'AbortError'))
    await expect(result).rejects.toMatchObject({ name: 'AbortError' })
  })
  it('普通连接结束不伪造不可披露状态', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('id: safe-2\nevent: message.delta\ndata: {"session_id":"s","event_id":"safe-2","session_sequence":2,"event_version":1}\n\n'))
    )
    expect(await client().groups.observe(query, vi.fn())).toEqual({
      state: 'ended',
      cursor: 'safe-2'
    })
  })
})
