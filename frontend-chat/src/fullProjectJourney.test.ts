import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { createHmac, createHash, webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { useConversation } from './session'
import { emptyHistory, pendingIdentity } from './projection'
import { conversationTurns } from './ui/conversationTimeline'
import { splitMarkdown, parseBarChart, safeMermaidSource } from './ui/richContent'
import type { ChatInput, History, Message, Pending, Process } from './protocol'

/** 合成用户输入只用于消费者状态/传输回归，不把文字经过客户端当成模型语义验收。 */
type UserScenario = {
  id: string
  category: string
  source_ids: string[]
  turns: { intent: string; text: string }[]
  suites: string[]
  oracle: string[]
}
const repository = resolve(process.cwd(), '..')
const corpus = JSON.parse(
  readFileSync(resolve(repository, 'tools/full_project_testing/fixtures/user_prompts.json'), 'utf8')
) as {
  execution_policy: { external_paid_model_calls: number }
  scenarios: UserScenario[]
}
const provenance = JSON.parse(
  readFileSync(
    resolve(repository, 'tools/full_project_testing/fixtures/user_prompts.provenance.json'),
    'utf8'
  )
) as { sources: { id: string; url: string; accessed_on: string }[] }
const envelope = (data: unknown) =>
  new Response(JSON.stringify({ code: 'OK', message: '', trace_id: 'complex-chat', data }), {
    headers: { 'content-type': 'application/json' }
  })
/** 显式释放屏障，不使用延时猜测业务请求是否受理或晚到。 */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

let wrapper: VueWrapper
let state: ReturnType<typeof useConversation>
let serverHistory: History
let posted: ChatInput[]
let delivered: (event: string, data: Record<string, unknown>) => void
let accept: (body: ChatInput) => Promise<Response>
let eventSequence: number
let historyPage: (url: URL) => History
let signedRequests: number

beforeEach(() => {
  localStorage.clear()
  vi.stubGlobal('crypto', webcrypto)
  serverHistory = { ...emptyHistory(), session_id: 'complex-session' }
  historyPage = () => structuredClone(serverHistory)
  posted = []
  eventSequence = 0
  signedRequests = 0
  accept = async (body) =>
    envelope({
      status: 'completed',
      session_id: body.session_id,
      execution_id: body.hitl_response?.execution_id ?? `run-${posted.length}`
    })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: URL, init: RequestInit = {}) => {
      const url = new URL(input)
      const wire = typeof init.body === 'string' ? init.body : ''
      const headers = new Headers(init.headers)
      // 独立用 Node HMAC 验证实际 wire，避免只重复调用生产 sign 得到同一错误。
      const timestamp = headers.get('X-Timestamp')
      const digest = createHash('sha256').update(wire).digest('hex')
      const expected = createHmac('sha256', 'synthetic-hmac-secret')
        .update(`${timestamp}\n${init.method}\n${url.pathname}${url.search}\n${digest}`)
        .digest('hex')
      expect(headers.get('X-Api-Key')).toBe('synthetic-key')
      expect(headers.get('X-Signature')).toBe(expected)
      expect(init.credentials).toBe('omit')
      expect(init.redirect).toBe('error')
      signedRequests++
      if (url.pathname.endsWith('/chat/agents'))
        return envelope({
          channel_id: 'synthetic-channel',
          items: [
            { id: 'agent-1', name: '同名助手', is_default: true, role_description: '数据核对' },
            { id: 'agent-2', name: '同名助手', is_default: false, role_description: '报告整理' }
          ]
        })
      if (url.pathname.endsWith('/chat/client-profile'))
        return envelope({ end_user_id: 'visitor-1', identity_token: 'synthetic-user-token' })
      if (url.pathname.endsWith('/chat/teams')) return envelope([])
      if (url.pathname.endsWith('/chat/sessions'))
        return init.method === 'POST'
          ? envelope({ session_id: 'complex-session' })
          : envelope([
              { session_id: 'complex-session', summary: '多轮复杂用户任务', message_count: 72 }
            ])
      if (url.pathname.endsWith('/chat/history')) return envelope(historyPage(url))
      if (url.pathname.endsWith('/chat/sessions/deliverables')) return envelope([])
      if (url.pathname.endsWith('/chat/limits'))
        return envelope({
          attachments: {
            max_file_bytes: 128_000,
            max_files: 8,
            max_total_bytes: 512_000,
            extensions: ['txt', 'csv', 'json', 'md']
          },
          references: { max_items: 10, max_file_selectors: 20 }
        })
      if (url.pathname.endsWith('/chat/runs/status'))
        return envelope({
          status: serverHistory.pending_interactions.some(
            (item) => item.execution_id === url.searchParams.get('run_id')
          )
            ? 'suspended'
            : 'completed'
        })
      if (url.pathname.endsWith('/chat/runs/steering'))
        return envelope({ run_id: url.searchParams.get('run_id'), accepting: false, items: [] })
      if (url.pathname.endsWith('/chat/events')) {
        return new Response(
          new ReadableStream({
            start(controller) {
              delivered = (event, data) =>
                controller.enqueue(
                  new TextEncoder().encode(
                    `id: opaque-${++eventSequence}\nevent: ${event}\ndata: ${JSON.stringify({
                      session_id: 'complex-session',
                      session_sequence: eventSequence,
                      ...data
                    })}\n\n`
                  )
                )
              init.signal?.addEventListener('abort', () => controller.close(), { once: true })
            }
          }),
          { headers: { 'content-type': 'text/event-stream' } }
        )
      }
      if (url.pathname.endsWith('/chat')) {
        const body = JSON.parse(wire) as ChatInput
        posted.push(body)
        return accept(body)
      }
      throw new Error(`复杂客户端夹具缺少明确响应：${url.pathname}`)
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
/** 所有请求经过真实前端 HMAC/信封/SSE消费者，但 fetch/服务事实由隔离夹具提供。 */
async function connect() {
  await state.connect(
    'http://localhost',
    { mode: 'hmac', apiKey: 'synthetic-key', secret: 'synthetic-hmac-secret' },
    '合成运营用户'
  )
  await state.openSession('complex-session')
}

describe('全项目多轮用户提示词消费者回归（transport mock）', () => {
  it('32个场景有独立来源、至少3轮和真实存在的suite；默认不调用付费模型', () => {
    expect(corpus.scenarios.length).toBeGreaterThanOrEqual(30)
    expect(new Set(corpus.scenarios.map((item) => item.id)).size).toBe(corpus.scenarios.length)
    expect(corpus.execution_policy.external_paid_model_calls).toBe(0)
    for (const scenario of corpus.scenarios) {
      expect(scenario.turns.length).toBeGreaterThanOrEqual(3)
      expect(new Set(scenario.turns.map((turn) => turn.text)).size).toBe(scenario.turns.length)
      expect(scenario.oracle.length).toBeGreaterThanOrEqual(3)
      for (const source of scenario.source_ids) {
        const record = provenance.sources.find((item) => item.id === source)
        expect(record?.url).toMatch(/^https:\/\//)
        expect(record?.accessed_on).toBe('2026-10-12')
      }
      for (const suite of scenario.suites) expect(existsSync(resolve(repository, suite))).toBe(true)
    }
  })

  it.each(corpus.scenarios)('$id：真实用户的初始、补充和改口保持独立输入身份', async (scenario) => {
    await connect()
    for (const turn of scenario.turns) {
      state.draft.value = turn.text
      await state.submit()
      expect(posted.at(-1)).toMatchObject({
        session_id: 'complex-session',
        agent_id: 'agent-1',
        content: [{ type: 'text', text: turn.text }],
        context: { end_user_id: 'visitor-1' }
      })
      expect(state.pendingInput.value).toBeUndefined()
    }
    expect(new Set(posted.map((body) => body.client_message_id)).size).toBe(scenario.turns.length)
    expect(signedRequests).toBeGreaterThan(scenario.turns.length)
  })

  it('受理丢回执后冻结两附件/图片/v1引用；精确SSE与迟到HTTP不释放下一请求', async () => {
    await connect()
    const text = corpus.scenarios[26]!.turns[0]!.text
    const files = ['sales-72.csv', 'brief.md'].map((name, index) => ({
      id: `file-${index}`,
      name,
      input_path: `inputs/${name}`,
      size: 72 + index,
      sha256: 'synthetic-hash',
      expires_at: '2026-10-13T00:00:00Z'
    }))
    state.attachments.value = files
    state.images.value = [
      { media_id: 'image-1', mime_type: 'image/png', byte_length: 100, width: 10, height: 10 }
    ]
    state.reference({
      label: '报告v1',
      source: { kind: 'artifact_files', files: [{ artifact_id: 'revision-v1', path: 'report.md' }] }
    })
    state.draft.value = text
    accept = async () => {
      throw new TypeError('受理后的HTTP响应丢失')
    }
    await state.submit()
    const frozen = structuredClone(posted[0]!)
    expect(state.pendingInput.value?.client_message_id).toBe(frozen.client_message_id)
    state.draft.value = corpus.scenarios[26]!.turns[2]!.text
    const nextDraft = state.draft.value
    const firstReceipt = deferred<Response>()
    const nextReceipt = deferred<Response>()
    accept = async (body) =>
      body.client_message_id === frozen.client_message_id
        ? firstReceipt.promise
        : nextReceipt.promise
    const retry = state.submit(undefined, true)
    await vi.waitFor(() => expect(posted).toHaveLength(2))
    expect(posted[1]).toEqual(frozen)
    delivered('execution.accepted', { run_id: 'run-original', input_id: 'same-text-other-input' })
    await flushPromises()
    expect(state.pendingInput.value?.client_message_id).toBe(frozen.client_message_id)
    delivered('execution.accepted', { run_id: 'run-original', input_id: frozen.client_message_id })
    delivered('execution.completed', {
      run_id: 'run-original',
      input_id: frozen.client_message_id,
      status: 'completed'
    })
    await flushPromises()
    expect(state.pendingInput.value).toBeUndefined()
    expect(state.draft.value).toBe(nextDraft)
    const provisional = state.history.value.messages.find(
      (item) => item.message_id === frozen.client_message_id
    )!
    expect(provisional.content).toBe(text)
    expect(provisional.metadata?.attachment_ids).toHaveLength(2)
    delivered('message.delta', {
      message_id: 'different-input',
      message_version: 1,
      role: 'user',
      content: '另一条公开消息'
    })
    await flushPromises()
    expect(
      state.history.value.messages.find((item) => item.message_id === frozen.client_message_id)
        ?.content
    ).toBe(text)
    delivered('message.delta', {
      message_id: frozen.client_message_id,
      message_version: 1,
      role: 'user',
      content: '内容未通过公开检查，本次交付未完成。'
    })
    await flushPromises()
    const visible = state.history.value.messages.find(
      (item) => item.message_id === frozen.client_message_id
    )!
    expect(visible.content).toBe('内容未通过公开检查，本次交付未完成。')
    expect(visible.metadata?.content_parts).toBeUndefined()
    expect(visible.metadata?.attachment_ids).toBeUndefined()
    const next = state.submit()
    await vi.waitFor(() => expect(posted).toHaveLength(3))
    const nextInput = state.pendingInput.value?.client_message_id
    expect(nextInput).toBeTruthy()
    firstReceipt.resolve(
      envelope({ status: 'accepted', session_id: 'complex-session', execution_id: 'run-original' })
    )
    await retry
    expect(state.pendingInput.value?.client_message_id).toBe(nextInput)
    expect(state.sending.value).toBe(true)
    expect(state.runStates['run-original']).toBe('completed')
    nextReceipt.resolve(
      envelope({ status: 'completed', session_id: 'complex-session', execution_id: 'run-next' })
    )
    await next
    expect(posted[2]!.context.attachment_ids).toEqual([])
    expect(posted[2]!.references).toEqual([])
  })

  it('HTTP确认后只读历史同版本以服务端公开快照替换本地原文和资源', async () => {
    await connect()
    state.draft.value = '合成敏感原文'
    state.images.value = [
      { media_id: 'image-private', mime_type: 'image/png', byte_length: 100, width: 10, height: 10 }
    ]
    state.attachments.value = [
      {
        id: 'private-file',
        name: '文件.txt',
        input_path: 'inputs/file',
        size: 2,
        sha256: 'synthetic',
        expires_at: '2026-10-13T00:00:00Z'
      }
    ]
    historyPage = () => ({
      ...emptyHistory(),
      session_id: 'complex-session',
      messages: [
        {
          message_id: posted[0]!.client_message_id,
          message_version: 1,
          role: 'user',
          content: '内容未通过公开检查，本次交付未完成。',
          metadata: {}
        }
      ]
    })
    await state.submit()
    const message = state.history.value.messages.find(
      (item) => item.message_id === posted[0]!.client_message_id
    )!
    expect(message.content).toBe('内容未通过公开检查，本次交付未完成。')
    expect(message.metadata?.content_parts).toBeUndefined()
    expect(message.metadata?.attachment_ids).toBeUndefined()
  })
  it('重连时同身份公开历史替换本地回执，其他消息不能覆盖它', async () => {
    await connect()
    state.draft.value = '合成原文'
    state.images.value = [
      { media_id: 'private-image', mime_type: 'image/png', byte_length: 100, width: 10, height: 10 }
    ]
    await state.submit()
    const input = posted[0]!.client_message_id
    expect(
      state.history.value.messages.find((message) => message.message_id === input)?.content
    ).toBe('合成原文')
    serverHistory.messages = [
      { message_id: 'other', message_version: 1, role: 'user', content: '另一条消息' },
      { message_id: input, message_version: 1, role: 'user', content: '安全正文', metadata: {} }
    ]
    await state.reconnect()
    const message = state.history.value.messages.find((item) => item.message_id === input)!
    expect(message.content).toBe('安全正文')
    expect(message.metadata?.content_parts).toBeUndefined()
    expect(state.history.value.messages.find((item) => item.message_id === 'other')?.content).toBe(
      '另一条消息'
    )
  })
  it('72条跨3页历史与在线v3累积同时合并，不能被旧页v1回退或重复', async () => {
    const all: Message[] = Array.from({ length: 72 }, (_, index) => ({
      message_id: `history-${index}`,
      message_version: 1,
      sequence: index + 1,
      role: index % 3 === 0 ? 'user' : 'assistant',
      phase: index % 3 === 1 ? 'commentary' : 'final',
      state: 'completed',
      root_run_id: `history-run-${Math.floor(index / 3)}`,
      root_input_id: `input-${Math.floor(index / 3)}`,
      content: corpus.scenarios[index % corpus.scenarios.length]!.turns[index % 3]!.text
    }))
    historyPage = (url) => {
      const before = url.searchParams.get('before_message_id')
      const end = before ? Number(before.replace('history-', '')) : all.length
      const start = Math.max(0, end - 30)
      return {
        ...emptyHistory(),
        session_id: 'complex-session',
        messages: all.slice(start, end),
        has_more: start > 0,
        next_before_message_id: start ? `history-${start}` : undefined
      }
    }
    await connect()
    expect(state.history.value.messages).toHaveLength(30)
    delivered('message.delta', {
      ...all[20],
      message_version: 3,
      content: '已纠正的净额v3，旧历史不能覆盖'
    })
    await flushPromises()
    await state.loadEarlier()
    await state.loadEarlier()
    expect(state.history.value.messages).toHaveLength(72)
    expect(new Set(state.history.value.messages.map((item) => item.message_id)).size).toBe(72)
    expect(
      state.history.value.messages.find((item) => item.message_id === 'history-20')
    ).toMatchObject({
      message_version: 3,
      content: '已纠正的净额v3，旧历史不能覆盖'
    })
    expect(state.history.value.has_more).toBe(false)
    expect(conversationTurns(state.history.value)).toHaveLength(24)
  })

  it('3根Run、2个委派过程、2个待办恢复；回复R2不消耗R1或独立下一条草稿', async () => {
    const pending: Pending[] = [
      {
        execution_id: 'root-1',
        run_revision: 3,
        checkpoint_revision: 5,
        question: '请确认统计口径'
      },
      {
        execution_id: 'root-2',
        run_revision: '7',
        interaction_type: 'tool_input',
        tool_wait: { action_id: 'action-2', generation: '2' },
        question: '请确认统计口径'
      }
    ]
    serverHistory.pending_interactions = pending
    serverHistory.processes = ['child-1', 'child-2'].map((run, index): Process => ({
      process_id: `delegate-${index}`,
      run_id: run,
      root_run_id: 'root-2',
      root_input_id: 'input-2',
      sequence: index + 1,
      ordinal: index,
      updated_sequence: index + 1,
      status: 'succeeded',
      type: 'delegation',
      actor_name: index ? '报告整理' : '数据核对',
      presentation: { title: '团队成员已交付', summary: `成员${index + 1}的真实公开结果` }
    }))
    await connect()
    expect(state.active.value).toBe(false)
    state.draft.value = '同时整理会议纪要，但不要替我回复原来的数据问题'
    await state.submit()
    expect(posted[0]!.hitl_response).toBeUndefined()
    expect(state.history.value.pending_interactions).toHaveLength(2)
    await state.reconnect()
    expect(state.history.value.processes.map((item) => item.run_id)).toEqual(['child-1', 'child-2'])
    state.draft.value = '这份预算要求保留到下一条任务'
    accept = async (body) => {
      const run = String(body.hitl_response?.execution_id)
      serverHistory.pending_interactions = pending.filter((item) => item.execution_id !== run)
      delivered('execution.completed', {
        run_id: run,
        status: 'completed',
        input_id: body.client_message_id
      })
      return envelope({ status: 'completed', session_id: body.session_id, execution_id: run })
    }
    await state.submit({
      ...pendingIdentity(pending[1]!),
      tool_input: { wait: pending[1]!.tool_wait, responses: { quantity: { value: 27 } } }
    })
    expect(posted[1]!.hitl_response).toMatchObject({
      execution_id: 'root-2',
      run_revision: '7',
      tool_input: { wait: { action_id: 'action-2', generation: '2' } }
    })
    expect(posted[1]!.content).toEqual([])
    expect(state.history.value.pending_interactions.map((item) => item.execution_id)).toEqual([
      'root-1'
    ])
    expect(state.draft.value).toBe('这份预算要求保留到下一条任务')
    expect(state.runStates['root-2']).toBe('completed')
  })

  it('长Unicode原文还原选区保留后续编辑；6项chart/8节点流程图只提升闭合源码', async () => {
    await connect()
    const original = '中文,"带引号"\r\n🇨🇳 👨‍👩‍👧‍👦 é\n'.repeat(160)
    state.draft.value = '前文选中文字后文'
    expect(state.stagePastedText(original, 2, 6)).toBe(true)
    expect(state.pastedTexts.value[0]?.size).toBe(new TextEncoder().encode(original).length)
    state.restorePastedText(state.pastedTexts.value[0]!.id)
    expect(state.draft.value).toBe(`前文${original}后文`)
    state.draft.value = '前文选中文字后文'
    expect(state.stagePastedText(original, 2, 6)).toBe(true)
    state.draft.value += '新增说明'
    state.restorePastedText(state.pastedTexts.value[0]!.id)
    // 草稿已经编辑过，不能再擅自删除当时的选中文字；只在原位置插入。
    expect(state.draft.value).toBe(`前文${original}选中文字后文新增说明`)
    const chart = JSON.stringify({
      type: 'bar',
      max: 600,
      items: Array.from({ length: 6 }, (_, index) => ({
        label: `区域${index + 1}`,
        value: (index + 1) * 100
      }))
    })
    const mermaid =
      'flowchart LR\n' +
      Array.from({ length: 7 }, (_, index) => `N${index}-->N${index + 1}`).join('\n')
    const fence = '\u0060\u0060\u0060'
    const unfinished = splitMarkdown(`${fence}chart\n${chart}`, true)
    expect(unfinished).toEqual([{ kind: 'text', text: `${fence}chart\n${chart}` }])
    const blocks = splitMarkdown(
      `${fence}chart\n${chart}\n${fence}\n\n${fence}mermaid\n${mermaid}\n${fence}\n\n<script>window.bad=true</script><a href="javascript:alert(1)">链接</a>`
    )
    expect(blocks.filter((block) => block.kind === 'chart')).toHaveLength(1)
    expect(parseBarChart(chart)?.items).toHaveLength(6)
    expect(safeMermaidSource(mermaid)).toBe(true)
    expect(blocks.filter((block) => block.kind === 'mermaid')).toHaveLength(1)
    for (const block of blocks)
      if (block.kind === 'html') expect(block.html).not.toMatch(/<script|javascript:/i)
  })
})
