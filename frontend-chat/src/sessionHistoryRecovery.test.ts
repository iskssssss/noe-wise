import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { useConversation } from './session'
import { emptyHistory } from './projection'

const response = (data: unknown) =>
  new Response(JSON.stringify({ code: 'OK', message: '成功', data, trace_id: 'history-test' }), {
    headers: { 'content-type': 'application/json' }
  })
const failure = (message: string, status = 500) =>
  new Response(
    JSON.stringify({ code: 'READ_FAILED', message, data: null, trace_id: 'history-test' }),
    {
      status,
      headers: { 'content-type': 'application/json' }
    }
  )
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
let wrapper: VueWrapper
let state: ReturnType<typeof useConversation>
let list: () => Promise<Response>
let history: (id: string) => Promise<Response>
let chat: () => Promise<Response>
let status: () => Promise<Response>
let calls: Array<{ path: string; method: string; search: string; signal?: AbortSignal | null }>
beforeEach(() => {
  localStorage.clear()
  calls = []
  list = async () => response([])
  history = async (id) => response({ ...emptyHistory(), session_id: id })
  chat = async () => response({ status: 'completed', execution_id: 'run' })
  status = async () => response({ status: 'running' })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: URL, init: RequestInit = {}) => {
      const url = new URL(input)
      const path = url.pathname
      calls.push({ path, method: init.method ?? 'GET', search: url.search, signal: init.signal })
      if (path.endsWith('/chat/agents'))
        return response({
          channel_id: 'channel',
          items: [{ id: 'agent', name: '助手', is_default: true }]
        })
      if (path.endsWith('/chat/client-profile'))
        return response({ end_user_id: '42', identity_token: 'synthetic-history-reader' })
      if (path.endsWith('/chat/teams')) return response([])
      if (path.endsWith('/chat/sessions'))
        return init.method === 'POST' ? response({ session_id: 'created' }) : list()
      if (path.endsWith('/chat/history')) return history(url.searchParams.get('session_id')!)
      if (path.endsWith('/chat')) return chat()
      if (path.endsWith('/chat/runs/status')) return status()
      if (path.endsWith('/chat/sessions/deliverables')) return response([])
      if (path.endsWith('/chat/limits')) return response({})
      if (path.endsWith('/chat/events'))
        return new Response(
          new ReadableStream({
            start(controller) {
              init.signal?.addEventListener('abort', () => controller.close(), { once: true })
            }
          }),
          { headers: { 'content-type': 'text/event-stream' } }
        )
      throw new Error(`Unexpected request: ${path}`)
    })
  )
  wrapper = mount(
    defineComponent({
      setup() {
        state = useConversation()
        return () => h('div')
      }
    })
  )
})
afterEach(() => {
  wrapper.unmount()
  vi.unstubAllGlobals()
})
const connect = () =>
  state.connect('http://localhost:8000', { mode: 'token', token: 'history-test-token' }, '访客')

describe('历史列表与消息读取的失败恢复', () => {
  it('列表初次读取有独立加载与失败状态，显式重读只发GET并清除原读取错误', async () => {
    const pending = deferred<Response>()
    list = () => pending.promise
    const connecting = connect()
    await flushPromises()
    expect(state.sessionsLoading.value).toBe(true)
    expect(state.loading.value).toBe(false)
    pending.resolve(failure('历史列表暂时不可用'))
    await connecting
    expect(state.sessionsLoading.value).toBe(false)
    expect(state.sessionsError.value).toContain('历史列表暂时不可用')
    expect(state.error.value).toBe(state.sessionsError.value)
    list = async () =>
      response([{ session_id: 'old-session', summary: '已有会话', message_count: 2 }])
    await state.refreshSessions()
    expect(state.sessions.value.map((item) => item.session_id)).toEqual(['old-session'])
    expect(state.sessionsError.value).toBe('')
    expect(state.error.value).toBe('')
    expect(
      calls.filter((call) => call.path.endsWith('/chat/sessions')).map((call) => call.method)
    ).toEqual(['GET', 'GET'])
  })

  it('已有列表刷新失败保留旧会话，列表重试不清除其他操作的反馈', async () => {
    list = async () => response([{ session_id: 'old-session', message_count: 2 }])
    await connect()
    list = async () => failure('列表刷新失败')
    await state.refreshSessions()
    expect(state.sessions.value).toHaveLength(1)
    state.error.value = '另一个操作需要处理'
    list = async () => response([])
    await state.refreshSessions()
    expect(state.error.value).toBe('另一个操作需要处理')
  })

  it('会话读取失败可原地重读，刷新失败保留已读内容和输入草稿', async () => {
    await connect()
    history = async () => failure('会话记录读取失败')
    await state.openSession('existing')
    expect(state.historyReadFailed.value).toBe(true)
    expect(state.sessionId.value).toBe('existing')
    expect(state.history.value.messages).toHaveLength(0)
    state.draft.value = '保留输入草稿'
    history = async (id) =>
      response({
        ...emptyHistory(),
        session_id: id,
        messages: [
          { message_id: 'saved', message_version: 1, role: 'assistant', content: '已保存的回复' }
        ]
      })
    await state.reconnect()
    expect(state.historyReadFailed.value).toBe(false)
    expect(state.history.value.messages[0]?.content).toBe('已保存的回复')
    history = async () => failure('后台刷新失败')
    await state.reconnect()
    expect(state.historyReadFailed.value).toBe(true)
    expect(state.history.value.messages[0]?.content).toBe('已保存的回复')
    expect(state.draft.value).toBe('保留输入草稿')
    expect(
      calls
        .filter((call) => call.path.endsWith('/chat/history'))
        .every((call) => call.method === 'GET')
    ).toBe(true)
  })

  it('切换会话后旧读取失败不能把新会话标为失败', async () => {
    await connect()
    const old = deferred<Response>()
    history = (id) =>
      id === 'old' ? old.promise : Promise.resolve(response({ ...emptyHistory(), session_id: id }))
    const opening = state.openSession('old')
    await flushPromises()
    await state.openSession('new')
    old.resolve(failure('旧会话读取失败'))
    await opening
    expect(state.sessionId.value).toBe('new')
    expect(state.historyReadFailed.value).toBe(false)
    expect(state.error.value).toBe('')
  })

  it('旧会话运行状态读取迟到失败不能切断新会话观察', async () => {
    await connect()
    const oldStatus = deferred<Response>()
    status = () => oldStatus.promise
    history = async (id) =>
      response({
        ...emptyHistory(),
        session_id: id,
        messages:
          id === 'old'
            ? [{ role: 'assistant', state: 'streaming', root_run_id: 'old-run', content: '处理中' }]
            : []
      })
    const opening = state.openSession('old')
    await flushPromises()
    expect(calls.some((call) => call.path.endsWith('/chat/runs/status'))).toBe(true)
    await state.openSession('new')
    await flushPromises()
    const subscriptions = () => calls.filter((call) => call.path.endsWith('/chat/events'))
    const current = subscriptions().find((call) => call.search.includes('session_id=new'))!
    expect(current).toBeDefined()
    expect(current.signal?.aborted).toBe(false)
    // 模拟已取消的旧 GET 仍迟到失败，不能让其接管当前订阅的 AbortController。
    oldStatus.resolve(failure('旧运行状态读取失败'))
    await opening
    await flushPromises()
    expect(state.sessionId.value).toBe('new')
    expect(current.signal?.aborted).toBe(false)
    expect(
      subscriptions().map((call) => new URLSearchParams(call.search).get('session_id'))
    ).toEqual(['new'])
    expect(state.error.value).toBe('')
  })

  it.each(['existing', 'created'])(
    '%s 会话读取失败后保留草稿，恢复观察前不能发送',
    async (kind) => {
      await connect()
      history = async () => failure('会话记录读取失败')
      if (kind === 'existing') await state.openSession('existing')
      state.draft.value = '保留的输入'
      if (kind === 'created') await state.submit()
      expect(state.historyReadFailed.value).toBe(true)
      expect(state.sessionId.value).toBe(kind)
      await state.submit()
      expect(calls.filter((call) => call.path.endsWith('/chat'))).toHaveLength(0)
      expect(state.draft.value).toBe('保留的输入')
      history = async (id) =>
        response({
          ...emptyHistory(),
          session_id: id,
          has_more: true,
          next_before_message_id: 'page'
        })
      await state.reconnect()
      expect(state.historyReadFailed.value).toBe(false)
      expect(state.history.value.has_more).toBe(true)
      expect(state.history.value.next_before_message_id).toBe('page')
      await state.loadEarlier()
      expect(calls.filter((call) => call.path.endsWith('/chat/history')).at(-1)?.search).toContain(
        'before_message_id=page'
      )
      expect(calls.filter((call) => call.path.endsWith('/chat/events'))).toHaveLength(1)
      expect(calls.filter((call) => call.path.endsWith('/chat'))).toHaveLength(0)
      await state.submit()
      expect(calls.filter((call) => call.path.endsWith('/chat'))).toHaveLength(1)
    }
  )

  it('读取失败期间保留结果未知的原请求，恢复只读且不自动重发', async () => {
    await connect()
    await state.openSession('existing')
    chat = async () => {
      throw new TypeError('网络中断')
    }
    state.draft.value = '原输入'
    await state.submit()
    const original = state.pendingInput.value
    expect(original).toBeDefined()
    history = async () => failure('会话记录读取失败')
    await state.reconnect()
    await state.submit(undefined, true)
    expect(state.pendingInput.value).toBe(original)
    expect(calls.filter((call) => call.path.endsWith('/chat'))).toHaveLength(1)
    history = async (id) => response({ ...emptyHistory(), session_id: id })
    await state.reconnect()
    expect(state.pendingInput.value).toBe(original)
    expect(calls.filter((call) => call.path.endsWith('/chat'))).toHaveLength(1)
  })

  it('历史读取被 403 拒绝时不发送，保留草稿并提供恢复状态', async () => {
    await connect()
    history = async () => failure('无权读取会话', 403)
    await state.openSession('existing')
    state.draft.value = '保留草稿'
    await state.submit()
    expect(state.historyReadFailed.value).toBe(true)
    expect(state.draft.value).toBe('保留草稿')
    expect(calls.filter((call) => call.path.endsWith('/chat'))).toHaveLength(0)
  })

  it('历史读取返回 401 时按身份失效断开，不能继续发送', async () => {
    await connect()
    await state.openSession('existing')
    chat = async () => {
      throw new TypeError('网络中断')
    }
    state.draft.value = '结果未知的输入'
    await state.submit()
    expect(state.pendingInput.value).toBeDefined()
    history = async () => failure('身份已失效', 401)
    await state.reconnect()
    expect(state.client.value).toBeUndefined()
    expect(state.sessionId.value).toBe('')
    expect(state.pendingInput.value).toBeUndefined()
    expect(calls.filter((call) => call.path.endsWith('/chat'))).toHaveLength(1)
  })

  it('恢复时历史尚未包含已确认输入，保留本地临时消息直到持久记录出现', async () => {
    await connect()
    await state.openSession('existing')
    state.draft.value = '已受理的输入'
    await state.submit()
    const message = state.history.value.messages.find((item) => item.content === '已受理的输入')
    expect(message).toBeDefined()
    await state.reconnect()
    expect(
      state.history.value.messages.find((item) => item.message_id === message?.message_id)
    ).toBeDefined()
    expect(calls.filter((call) => call.path.endsWith('/chat'))).toHaveLength(1)
  })
})
