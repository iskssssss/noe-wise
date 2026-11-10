import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { useConversation } from './session'
import { emptyHistory } from './projection'
import type {
  Attachment,
  Limits,
  SteeringInput,
  SteeringState,
  StreamEvent,
  Work
} from './protocol'
const response = (data: unknown) =>
  new Response(JSON.stringify({ code: 'OK', message: '成功', data, trace_id: 'test' }), {
    headers: { 'content-type': 'application/json' }
  })
let wrapper: VueWrapper,
  state: ReturnType<typeof useConversation>,
  posts: string[],
  submit: (body: string) => Promise<Response>,
  historyLoad: (id: string) => Promise<Response>,
  limitsLoad: () => Promise<Response>,
  attachmentLimits: Limits['attachments'],
  uploadedForms: FormData[],
  uploadAttachment: (body: FormData) => Promise<Response>,
  createdSessions: number,
  statusLoad: () => Promise<Response>,
  steeringPosts: string[],
  steeringState: SteeringState,
  steeringReadDenied: boolean,
  agentCatalog: unknown[],
  teamCatalog: unknown[],
  steerSubmit: (body: string) => Promise<Response>,
  publish: (event: StreamEvent) => void
beforeEach(() => {
  localStorage.clear()
  agentCatalog = [{ id: 'agent', name: '助手', is_default: true }]
  teamCatalog = []
  posts = []
  submit = async () => response({ status: 'accepted', execution_id: 'run', session_id: 's' })
  historyLoad = async (id) => response({ ...emptyHistory(), session_id: id })
  attachmentLimits = {
    max_file_bytes: 1000,
    max_files: 3,
    max_total_bytes: 2000,
    extensions: ['txt']
  }
  limitsLoad = async () =>
    response({
      attachments: attachmentLimits,
      references: { max_items: 10, max_file_selectors: 20 }
    })
  uploadedForms = []
  createdSessions = 0
  uploadAttachment = async (body) => {
    const file = body.get('file') as File
    return response({
      id: String(body.get('upload_id')),
      name: file.name,
      input_path: `inputs/${file.name}`,
      size: file.size,
      sha256: 'hash',
      expires_at: '2026-10-12T00:00:00Z'
    } satisfies Attachment)
  }
  statusLoad = async () => response({ status: 'queued' })
  steeringPosts = []
  steeringReadDenied = false
  steeringState = { run_id: 'run', accepting: true, items: [] }
  steerSubmit = async (body) => {
    const value = JSON.parse(body) as SteeringInput
    steeringState = {
      ...steeringState,
      run_id: value.run_id,
      items: [{ command_id: value.command_id, text: value.text, status: 'PENDING' }]
    }
    return response(steeringState)
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: URL, init: RequestInit = {}) => {
      const url = new URL(input),
        path = url.pathname
      if (path === '/api/v1/chat/agents')
        return response({
          channel_id: 'channel',
          items: agentCatalog
        })
      if (path === '/api/v1/chat/client-profile')
        return response({ end_user_id: '42', identity_token: 'synthetic-user-token' })
      if (path === '/api/v1/chat/teams') return response(teamCatalog)
      if (path === '/api/v1/chat/sessions' && init.method === 'GET') return response([])
      if (path === '/api/v1/chat/sessions') {
        createdSessions++
        return response({ session_id: 's' })
      }
      if (path === '/api/v1/chat/history') return historyLoad(url.searchParams.get('session_id')!)
      if (path === '/api/v1/chat/sessions/deliverables') return response([])
      if (path === '/api/v1/chat/runs/steering')
        return steeringReadDenied
          ? new Response(
              JSON.stringify({
                code: 'FORBIDDEN',
                message: 'Runtime 拒绝访问',
                data: null,
                trace_id: 'test'
              }),
              { status: 403 }
            )
          : response({ ...steeringState, run_id: url.searchParams.get('run_id') })
      if (path === '/api/v1/chat/runs/steer') {
        steeringPosts.push(String(init.body))
        return steerSubmit(String(init.body))
      }
      if (path === '/api/v1/chat/limits') return limitsLoad()
      if (path === '/api/v1/chat/sessions/attachments/upload') {
        uploadedForms.push(init.body as FormData)
        return uploadAttachment(init.body as FormData)
      }
      if (path === '/api/v1/chat/sessions/media/limits')
        return response({ max_file_bytes: 1000, mime_types: ['image/png'] })
      if (path === '/api/v1/chat/sessions/media/upload')
        return response({
          media_id: 'media',
          mime_type: 'image/png',
          byte_length: 3,
          width: 1,
          height: 1
        })
      if (path === '/api/v1/chat/events') {
        let abort: (() => void) | undefined
        return new Response(
          new ReadableStream({
            start(controller) {
              publish = ({ event, id, data }) =>
                controller.enqueue(
                  new TextEncoder().encode(
                    `id: ${id}\nevent: ${event}\ndata: ${JSON.stringify(data)}\n\n`
                  )
                )
              abort = () => controller.close()
              init.signal?.addEventListener('abort', abort, { once: true })
            },
            cancel() {
              if (abort) init.signal?.removeEventListener('abort', abort)
            }
          }),
          { headers: { 'content-type': 'text/event-stream' } }
        )
      }
      if (path === '/api/v1/chat') {
        posts.push(String(init.body))
        return submit(String(init.body))
      }
      if (path === '/api/v1/chat/runs/status') return statusLoad()
      throw new Error(`unhandled ${path}`)
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
const connect = () => state.connect('http://localhost', { mode: 'token', token: 'test' }, '测试')
it('没有智能体时自动选择唯一可用团队并读取其会话', async () => {
  agentCatalog = []
  teamCatalog = [
    { id: 'team-1', name: '服务团队', status: 'published' },
    { id: 'disabled', status: 'disabled' }
  ]
  await connect()
  expect(state.target.value).toBe('team:team-1')
  expect(state.selectedTarget.value).toEqual({ team_id: 'team-1' })
  const reads = vi
    .mocked(fetch)
    .mock.calls.filter(
      ([url, init]) =>
        new URL(String(url)).pathname === '/api/v1/chat/sessions' && init?.method === 'GET'
    )
  expect(reads).toHaveLength(1)
  expect(new URL(String(reads[0]![0])).searchParams.get('team_id')).toBe('team-1')
  expect(createdSessions).toBe(0)
})

describe('停用团队的历史与执行边界', () => {
  const team = {
    id: 'team-1',
    revision: 1,
    name: '咨询团队',
    description: '',
    status: 'published',
    primary_agent_id: 'agent',
    members: [{ agent_id: 'agent' }]
  }
  it.each([false, true])('已有会话=%s 时停用禁发，重新发布后使用原目标继续', async (existing) => {
    teamCatalog = [{ ...team }]
    await connect()
    await state.changeTarget('team:team-1')
    if (existing) await state.openSession('s')
    state.draft.value = '保留的草稿'
    state.teams.value[0]!.status = 'disabled'

    expect(state.targetDisabled.value).toBe(true)
    expect(state.composerLocked.value).toBe(true)
    await state.submit()
    await state.upload([new File(['内容'], '附件.txt', { type: 'text/plain' })])
    await expect(state.ensureSession()).rejects.toThrow('团队已停用')
    state.newSession()
    expect(posts).toEqual([])
    expect(uploadedForms).toEqual([])
    expect(createdSessions).toBe(0)
    expect(state.sessionId.value).toBe(existing ? 's' : '')
    expect(state.target.value).toBe('team:team-1')
    expect(state.draft.value).toBe('保留的草稿')

    state.teams.value[0]!.status = 'published'
    expect(state.composerLocked.value).toBe(false)
    await state.submit()
    expect(posts).toHaveLength(1)
    expect(JSON.parse(posts[0]!)).toMatchObject({ session_id: 's', team_id: 'team-1' })
    expect(createdSessions).toBe(existing ? 0 : 1)
  })
  it('可选择停用团队读取原会话，重新连接保留历史和当前草稿', async () => {
    teamCatalog = [{ ...team, status: 'disabled' }]
    historyLoad = async (id) =>
      response({
        ...emptyHistory(),
        session_id: id,
        messages: [{ message_id: 'original', role: 'assistant', content: '已完成的回复' }]
      })
    await connect()
    await state.changeTarget('team:team-1')
    await state.openSession('s')
    state.draft.value = '待重新发布后发送'
    await state.reconnect()
    expect(state.history.value.messages[0]?.content).toBe('已完成的回复')
    expect(state.sessionId.value).toBe('s')
    expect(state.draft.value).toBe('待重新发布后发送')
    expect(state.historyReadFailed.value).toBe(false)
    expect(state.composerLocked.value).toBe(true)
    expect(posts).toEqual([])
    expect(createdSessions).toBe(0)
  })
  it('停用期间不重试未知输入，重新发布也不清除或自动发送原身份', async () => {
    teamCatalog = [{ ...team }]
    await connect()
    await state.changeTarget('team:team-1')
    await state.openSession('s')
    state.draft.value = '只发送一次'
    submit = async () => {
      throw new TypeError('offline')
    }
    await state.submit()
    const original = state.pendingInput.value
    expect(original).toBeTruthy()
    state.teams.value[0]!.status = 'disabled'
    await state.submit(undefined, true)
    state.newSession()
    await state.reconnect()
    expect(state.pendingInput.value).toBe(original)
    expect(state.draft.value).toBe('只发送一次')
    expect(state.sessionId.value).toBe('s')
    state.teams.value[0]!.status = 'published'
    await flushPromises()
    expect(state.pendingInput.value).toBe(original)
    expect(posts).toHaveLength(1)
  })
  it('停用期间不重试未知补充，保留命令和两份草稿', async () => {
    teamCatalog = [{ ...team }]
    await connect()
    await state.changeTarget('team:team-1')
    state.draft.value = '开始任务'
    await state.submit()
    await flushPromises()
    state.supplementDraft.value = '待确认补充'
    steerSubmit = async () => {
      throw new TypeError('offline')
    }
    await state.submitSupplement()
    await flushPromises()
    const original = state.pendingSteering.value
    expect(original).toBeTruthy()
    state.draft.value = '下一条草稿'
    state.teams.value[0]!.status = 'disabled'
    await state.submitSupplement(true)
    expect(state.canSteer.value).toBe(false)
    expect(state.pendingSteering.value).toBe(original)
    expect(state.supplementDraft.value).toBe('待确认补充')
    expect(state.draft.value).toBe('下一条草稿')
    state.teams.value[0]!.status = 'published'
    await flushPromises()
    expect(state.pendingSteering.value).toBe(original)
    expect(steeringPosts).toHaveLength(1)
    expect(posts).toHaveLength(1)
  })
})

it('图片上传结果未知时只读核对原标识，确认后加入草稿', async () => {
  await connect()
  const original = vi.mocked(fetch).getMockImplementation()!
  let found = false
  vi.mocked(fetch).mockImplementation((input, init) => {
    const path = new URL(String(input)).pathname
    if (path === '/api/v1/chat/sessions/media/upload')
      return Promise.reject(new TypeError('offline'))
    if (path === '/api/v1/chat/sessions/media/upload-receipt')
      return Promise.resolve(
        response(
          found
            ? {
                status: 'FOUND',
                media: {
                  media_id: 'image-1',
                  mime_type: 'image/png',
                  byte_length: 3,
                  width: 1,
                  height: 1
                }
              }
            : { status: 'UNKNOWN', media: null }
        )
      )
    return original(input, init)
  })
  await state.upload([new File(['png'], '图片.png', { type: 'image/png' })])
  const pending = state.currentUnconfirmedUploads.value[0]!
  expect(pending.kind).toBe('media')
  await state.checkUnconfirmedUpload(pending.uploadId)
  expect(state.currentUnconfirmedUploads.value).toHaveLength(1)
  expect(state.images.value).toHaveLength(0)
  const read = vi
    .mocked(fetch)
    .mock.calls.find(
      ([url]) => new URL(String(url)).pathname === '/api/v1/chat/sessions/media/upload-receipt'
    )!
  expect(new URL(String(read[0])).searchParams.get('session_id')).toBe('s')
  expect(new URL(String(read[0])).searchParams.get('upload_id')).toBe(pending.uploadId)
  found = true
  await state.checkUnconfirmedUpload(pending.uploadId)
  expect(state.currentUnconfirmedUploads.value).toHaveLength(0)
  expect(state.currentConfirmedUploads.value).toHaveLength(0)
  expect(state.images.value[0]?.media_id).toBe('image-1')
  expect(
    vi
      .mocked(fetch)
      .mock.calls.filter(
        ([url]) => new URL(String(url)).pathname === '/api/v1/chat/sessions/media/upload'
      )
  ).toHaveLength(1)
})
it('核对图片期间切换补充输入时保留已确认结果，待用户加入', async () => {
  await connect()
  const original = vi.mocked(fetch).getMockImplementation()!
  let finish!: (value: Response) => void
  vi.mocked(fetch).mockImplementation((input, init) => {
    const path = new URL(String(input)).pathname
    if (path === '/api/v1/chat/sessions/media/upload')
      return Promise.reject(new TypeError('offline'))
    if (path === '/api/v1/chat/sessions/media/upload-receipt')
      return new Promise((resolve) => {
        finish = resolve
      })
    return original(input, init)
  })
  await state.upload([new File(['png'], '图片.png', { type: 'image/png' })])
  const id = state.currentUnconfirmedUploads.value[0]!.uploadId
  const checking = state.checkUnconfirmedUpload(id)
  await vi.waitFor(() => expect(finish).toBeDefined())
  state.inputMode.value = 'supplement'
  finish(
    response({
      status: 'FOUND',
      media: { media_id: 'image-1', mime_type: 'image/png', byte_length: 3, width: 1, height: 1 }
    })
  )
  await checking
  expect(state.images.value).toEqual([])
  expect(state.currentConfirmedUploads.value).toHaveLength(1)
  state.inputMode.value = 'message'
  await state.claimConfirmedUpload(id)
  expect(state.images.value[0]?.media_id).toBe('image-1')
  expect(state.currentConfirmedUploads.value).toEqual([])
})

it('多个可用团队或仅停用团队时不代用户选择', async () => {
  agentCatalog = []
  teamCatalog = [
    { id: 'a', status: 'published' },
    { id: 'b', status: 'published' }
  ]
  await connect()
  expect(state.target.value).toBe('')
  teamCatalog = [{ id: 'disabled', status: 'disabled' }]
  await connect()
  expect(state.target.value).toBe('')
  expect(createdSessions).toBe(0)
})
it('存在默认智能体时仍优先选择该智能体', async () => {
  teamCatalog = [{ id: 'team-1', status: 'published' }]
  await connect()
  expect(state.target.value).toBe('agent:agent')
})
it('订阅权限失效时保留服务端原因并停止自动重连', async () => {
  await connect()
  await state.openSession('s')
  await flushPromises()
  const fetch = vi.mocked(globalThis.fetch)
  const subscriptions = () =>
    fetch.mock.calls.filter(([input]) => new URL(String(input)).pathname === '/api/v1/chat/events')
  expect(subscriptions()).toHaveLength(1)
  publish({
    id: '',
    event: 'error',
    data: {
      error_code: 'SESSION_ACCESS_DENIED',
      message: '会话访问权限已失效，请刷新权限',
      retryable: false
    }
  })
  await flushPromises()
  expect(state.connection.value).toBe('连接已断开')
  expect(state.error.value).toBe('会话访问权限已失效，请刷新权限')
  expect(subscriptions()).toHaveLength(1)
  expect(posts).toEqual([])
})
for (const outcome of ['running', 'suspended', 'changed-session'] as const) {
  it(`审批后根进度只触发一次权威状态核验：${outcome}`, async () => {
    await connect()
    const work: Work = {
      work_id: 'input',
      session_id: 's',
      input_id: 'input',
      run_id: 'root',
      revision: 1,
      run_revision: 11,
      run_status: 'suspended',
      status: 'active',
      plan_revision: 1,
      approval_required: true,
      tasks: [],
      results: []
    }
    historyLoad = async (id) =>
      response({
        ...emptyHistory(),
        session_id: id,
        work_plans: id === 's' ? [work] : [],
        activities:
          id === 's'
            ? [
                {
                  node_id: 'root',
                  node_type: 'agent_runtime',
                  status: 'waiting_hitl',
                  render: {
                    root_run_id: 'root',
                    root_input_id: 'input',
                    updated_sequence: 46,
                    reason: 'plan_approval_required'
                  }
                }
              ]
            : []
      })
    statusLoad = async () => response({ status: 'suspended' })
    await state.openSession('s')
    await flushPromises()
    const approved = { ...work, revision: 2, approved_revision: 1 }
    state.updateWork(approved)
    expect(state.history.value.work_plans?.[0]?.approved_revision).toBe(1)
    let finish!: (value: Response) => void
    const statusRead = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve
        })
    )
    statusLoad = statusRead
    const original = fetch
    vi.stubGlobal(
      'fetch',
      vi.fn((input: URL, init?: RequestInit) =>
        new URL(input).pathname === '/api/v1/chat/work'
          ? Promise.resolve(response({ ...approved, run_status: 'running', run_revision: 12 }))
          : original(input, init)
      )
    )
    const progress = (id: string, run: string, sequence: number) =>
      publish({
        id,
        event: 'process.started',
        data: {
          session_id: 's',
          process_id: id,
          run_id: run,
          root_run_id: 'root',
          root_input_id: 'input',
          status: 'running',
          updated_sequence: sequence,
          presentation: { title: '模型调用', summary: '' }
        }
      })
    progress('old', 'root', 40)
    progress('member', 'member', 47)
    await flushPromises()
    expect(statusRead).not.toHaveBeenCalled()
    progress('new', 'root', 48)
    progress('newer', 'root', 49)
    await flushPromises()
    expect(statusRead).toHaveBeenCalledTimes(1)
    if (outcome === 'changed-session') await state.openSession('next')
    finish(
      response({
        status: outcome === 'suspended' ? 'suspended' : 'running',
        session_id: 's',
        execution_id: 'root',
        card: null,
        message: null,
        collaboration: null,
        input_id: null,
        replayed: false
      })
    )
    await flushPromises()
    if (outcome === 'changed-session') {
      expect(state.history.value.session_id).toBe('next')
      expect(state.runStates.root).toBeUndefined()
    } else expect(state.runStatus.value).toBe(outcome)
    progress('more', 'root', 50)
    await flushPromises()
    expect(statusRead).toHaveBeenCalledTimes(1)
  })
}
it('第二次真实计划待批SSE保留reason，随后工作挂起和无revision状态读取不回退', async () => {
  await connect()
  const work: Work = {
    work_id: 'input',
    session_id: 's',
    input_id: 'input',
    run_id: 'root',
    run_status: 'running',
    run_revision: 2,
    revision: 1,
    status: 'active',
    plan_revision: 1,
    approval_required: true,
    tasks: [],
    results: []
  }
  historyLoad = async (id) => response({ ...emptyHistory(), session_id: id, work_plans: [work] })
  statusLoad = async () => response({ status: 'running' })
  await state.openSession('s')
  await flushPromises()
  const waiting = (id: string, sequence: number) =>
    publish({
      id,
      event: 'execution.completed',
      data: {
        session_id: 's',
        run_id: 'root',
        root_run_id: 'root',
        root_input_id: 'input',
        status: 'waiting_hitl',
        reason: 'plan_approval_required',
        updated_sequence: sequence
      }
    })
  waiting('first', 46)
  await flushPromises()
  expect(state.history.value.activities?.[0]).toMatchObject({
    status: 'waiting_hitl',
    render: {
      root_run_id: 'root',
      root_input_id: 'input',
      reason: 'plan_approval_required',
      updated_sequence: 46
    }
  })
  state.history.value.work_plans = [{ ...work, run_status: 'suspended', run_revision: 11 }]
  expect(state.runStatus.value).toBe('waiting_hitl')
  publish({ id: 'resume', event: 'execution.accepted', data: { session_id: 's', run_id: 'root' } })
  await flushPromises()
  state.history.value.work_plans = [{ ...work, approved_revision: 1, revision: 2 }]
  expect(state.runStatus.value).toBe('running')
  waiting('second', 161)
  await flushPromises()
  expect(state.history.value.activities?.[0]).toMatchObject({
    status: 'waiting_hitl',
    render: {
      root_run_id: 'root',
      root_input_id: 'input',
      reason: 'plan_approval_required',
      updated_sequence: 161
    }
  })
  state.history.value.work_plans = [
    {
      ...work,
      approved_revision: 1,
      plan_revision: 2,
      revision: 3,
      run_status: 'suspended',
      run_revision: 35
    }
  ]
  expect(state.runStatus.value).toBe('waiting_hitl')
  expect(state.active.value).toBe(false)
  expect(state.history.value.pending_interactions).toEqual([])
  const persisted = JSON.parse(JSON.stringify(state.history.value))
  historyLoad = async () => response(persisted)
  statusLoad = async () =>
    response({
      status: 'suspended',
      session_id: 's',
      execution_id: 'root',
      card: null,
      message: null,
      collaboration: null,
      input_id: null,
      replayed: false
    })
  await state.openSession('s')
  expect(state.runStatus.value).toBe('waiting_hitl')
  expect(state.active.value).toBe(false)
  expect(posts).toHaveLength(0)
})
for (const switchSession of [false, true]) {
  it(`根完成事件刷新对应计划的真实Run事实${switchSession ? '，旧会话迟到结果不覆盖新会话' : '，保留未完成Work'}`, async () => {
    await connect()
    const work: Work = {
      work_id: 'root-input',
      session_id: 's',
      input_id: 'root-input',
      run_id: 'root',
      run_status: 'running',
      run_revision: 10,
      revision: 3,
      status: 'active',
      plan_revision: 1,
      approved_revision: 1,
      approval_required: true,
      tasks: [
        {
          task_id: 'D1',
          task_kind: 'plan',
          title: '待处理步骤',
          agent_name: '主助手',
          executor_type: 'PRIMARY',
          status: 'blocked',
          details_access: 'not_started'
        }
      ],
      results: []
    }
    historyLoad = async (id) =>
      response({ ...emptyHistory(), session_id: id, work_plans: id === 's' ? [work] : [] })
    statusLoad = async () => response({ status: 'running' })
    await state.openSession('s')
    await flushPromises()
    let complete!: (value: Response) => void
    const original = fetch
    const reads: URL[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((input: URL, init?: RequestInit) => {
        const url = new URL(input)
        if (url.pathname === '/api/v1/chat/work') {
          reads.push(url)
          return new Promise<Response>((resolve) => {
            complete = resolve
          })
        }
        return original(input, init)
      })
    )
    publish({
      id: 'member-finished',
      event: 'execution.completed',
      data: {
        session_id: 's',
        run_id: 'member-run',
        root_run_id: 'root',
        root_input_id: 'root-input',
        status: 'completed'
      }
    })
    await flushPromises()
    expect(reads).toHaveLength(0)
    publish({
      id: 'root-finished',
      event: 'execution.completed',
      data: {
        session_id: 's',
        run_id: 'root',
        root_run_id: 'root',
        root_input_id: 'root-input',
        status: 'completed',
        updated_sequence: 46
      }
    })
    await flushPromises()
    expect(reads).toHaveLength(1)
    expect(reads[0]!.searchParams.get('work_id')).toBe('root-input')
    expect(reads[0]!.searchParams.get('session_id')).toBe('s')
    if (switchSession) await state.openSession('next')
    complete(response({ ...work, run_status: 'completed', run_revision: 11 }))
    await flushPromises()
    if (switchSession) {
      expect(state.history.value.session_id).toBe('next')
      expect(state.history.value.work_plans).toEqual([])
    } else {
      expect(state.history.value.work_plans?.[0]).toMatchObject({
        status: 'active',
        run_status: 'completed',
        run_revision: 11,
        tasks: [{ task_id: 'D1', status: 'blocked' }]
      })
    }
  })
}
it('父工作控制目标不受成员受理事件先到或历史成员执行污染', async () => {
  await connect()
  const work: Work = {
    work_id: 'w',
    session_id: 's',
    input_id: 'i',
    run_id: 'parent',
    run_status: 'running',
    run_revision: 2,
    revision: 1,
    status: 'active',
    plan_revision: 1,
    approval_required: false,
    results: [],
    tasks: [
      {
        task_id: 'member',
        task_kind: 'plan',
        title: '成员',
        agent_name: '成员',
        executor_type: 'MEMBER',
        status: 'pending',
        details_access: 'not_started'
      }
    ]
  }
  historyLoad = async (id) => response({ ...emptyHistory(), session_id: id, work_plans: [work] })
  statusLoad = async () => response({ status: 'running' })
  await state.openSession('s')
  await flushPromises()
  publish({
    id: 'member-start',
    event: 'execution.accepted',
    data: { session_id: 's', run_id: 'member-run' }
  })
  await flushPromises()
  expect(state.runId.value).toBe('parent')
  const steeringReads = () =>
    vi
      .mocked(fetch)
      .mock.calls.map(([url]) => new URL(String(url)))
      .filter((url) => url.pathname.endsWith('/runs/steering'))
      .map((url) => url.searchParams.get('run_id'))
  expect(steeringReads()).not.toContain('member-run')
  state.history.value.work_plans = [
    { ...work, tasks: [{ ...work.tasks[0]!, run_id: 'member-run', status: 'running' }] }
  ]
  await flushPromises()
  expect(state.runStates['member-run']).toBeUndefined()
  publish({
    id: 'member-end',
    event: 'execution.completed',
    data: { session_id: 's', run_id: 'member-run', status: 'completed' }
  })
  await flushPromises()
  expect(state.runId.value).toBe('parent')
  expect(state.runStatus.value).toBe('running')
  state.supplementDraft.value = '仅补充父任务'
  await state.submitSupplement()
  expect(JSON.parse(steeringPosts[0]!).run_id).toBe('parent')
  expect(steeringReads()).not.toContain('member-run')
  const knownWork = state.history.value.work_plans[0]!
  historyLoad = async (id) =>
    response({
      ...emptyHistory(),
      session_id: id,
      work_plans: [knownWork],
      activities: [
        { node_type: 'agent_runtime', status: 'running', render: { root_run_id: 'member-run' } }
      ]
    })
  await state.openSession('s')
  await flushPromises()
  expect(state.runId.value).toBe('parent')
  expect(state.runStates['member-run']).toBeUndefined()
  state.history.value.work_plans = [{ ...knownWork, run_status: 'terminated' }]
  await flushPromises()
  expect(state.runStatus.value).toBe('terminated')
  expect(state.active.value).toBe(false)
  state.draft.value = '新的独立轮次'
  submit = async () =>
    response({ status: 'accepted', execution_id: 'next-parent', session_id: 's' })
  await state.submit()
  await flushPromises()
  expect(state.runId.value).toBe('next-parent')
})
it('连接保存服务器身份凭证，恢复只发送同一客户端及原凭证', async () => {
  await connect()
  const saved = JSON.parse(
    localStorage.getItem('skillforge.chat.profile:http://localhost:channel')!
  )
  expect(saved.identity_token).toBe('synthetic-user-token')
  expect(saved.client_ref).toBe(state.clientRef.value)
  await connect()
  const calls = vi.mocked(fetch).mock.calls
  const registration = calls.filter(([url]) =>
    new URL(String(url)).pathname.endsWith('/client-profile')
  )
  expect(registration).toHaveLength(2)
  expect(new Headers(registration[1]![1]!.headers).get('X-Chat-User-Token')).toBe(
    'synthetic-user-token'
  )
  expect(JSON.parse(String(registration[1]![1]!.body)).client_ref).toBe(saved.client_ref)
})
it('旧裸客户端UUID不冒领原终端用户身份', async () => {
  const legacy = 'cd0f4979-6b0b-434a-83cf-9c313f3c080c'
  localStorage.setItem('skillforge.chat.profile:http://localhost:channel', legacy)
  await connect()
  expect(state.clientRef.value).not.toBe(legacy)
  const call = vi
    .mocked(fetch)
    .mock.calls.find(([url]) => new URL(String(url)).pathname.endsWith('/client-profile'))!
  expect(new Headers(call[1]!.headers).has('X-Chat-User-Token')).toBe(false)
})
const longText = (character = 'x') => character.repeat(2000)
const fileText = (file: File) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(file)
  })

describe('长粘贴文本草稿生命周期', () => {
  beforeEach(() => {
    attachmentLimits = {
      max_file_bytes: 20000,
      max_files: 6,
      max_total_bytes: 40000,
      extensions: ['txt']
    }
  })

  it('转换门槛由会话入口持有，短文本保持原生粘贴并且不改草稿', async () => {
    await connect()
    state.draft.value = '原有草稿'
    for (const text of [
      '',
      'x'.repeat(1999),
      '\n'.repeat(30),
      `${'x'.repeat(770)}${'\n'.repeat(29)}`
    ])
      expect(state.stagePastedText(text, 2, 3)).toBe(false)
    expect(state.pastedTexts.value).toEqual([])
    expect(state.draft.value).toBe('原有草稿')
    expect(state.stagePastedText(`${'x'.repeat(771)}${'\n'.repeat(29)}`, 2, 3)).toBe(true)
    expect(state.stagePastedText(longText(), 2, 3)).toBe(true)
    expect(state.pastedTexts.value.map((item) => item.name)).toEqual([
      '粘贴文本.txt',
      '粘贴文本 2.txt'
    ])
    expect(createdSessions).toBe(0)
    expect(uploadedForms).toHaveLength(0)
  })

  it('没有限额时仅暂存精确原文和UTF-8字节，恢复保持原位置且保留后续编辑', async () => {
    await connect()
    const raw = `  中文😀\t\r\n${longText()}\n  `
    state.draft.value = 'abXXcd'
    expect(state.stagePastedText(raw, 2, 4)).toBe(true)
    const staged = state.pastedTexts.value[0]!
    expect(staged).toMatchObject({
      text: raw,
      size: new TextEncoder().encode(raw).byteLength,
      selectedText: 'XX'
    })
    state.draft.value = 'abYYcd改过草稿'
    expect(state.restorePastedText(staged.id)).toBe(2 + raw.length)
    expect(state.draft.value).toBe(`ab${raw}YYcd改过草稿`)
    expect(state.pastedTexts.value).toEqual([])
    expect(createdSessions).toBe(0)
    expect(uploadedForms).toHaveLength(0)
  })

  it('原选区未改动时替换原选中文字，连续同选区还原不删先前还原原文', async () => {
    await connect()
    state.draft.value = 'abXXcd'
    const firstText = `XX${longText('a')}`,
      secondText = longText('b')
    state.stagePastedText(firstText, 2, 4)
    state.stagePastedText(secondText, 2, 4)
    const [first, second] = state.pastedTexts.value
    expect(state.restorePastedText(first!.id)).toBe(2 + firstText.length)
    expect(state.draft.value).toBe(`ab${firstText}cd`)
    expect(state.restorePastedText(second!.id)).toBe(2 + secondText.length)
    expect(state.draft.value).toBe(`ab${secondText}${firstText}cd`)
    expect(state.pastedTexts.value).toEqual([])
  })

  it('旧选区在更短草稿上收敛，不越界；删除和新会话清除本地身份', async () => {
    await connect()
    state.draft.value = 'abcdefghij'
    state.stagePastedText(longText(), 6, 10)
    const first = state.pastedTexts.value[0]!
    state.draft.value = 'ab'
    expect(state.restorePastedText(first.id)).toBe(2002)
    expect(state.draft.value).toBe(`ab${longText()}`)
    state.stagePastedText(longText())
    const second = state.pastedTexts.value[0]!
    expect(second.id).not.toBe(first.id)
    expect(state.removePastedText(second.id)).toBe(true)
    state.stagePastedText(longText())
    state.newSession()
    expect(state.pastedTexts.value).toEqual([])
    expect(state.stagePastedText(longText())).toBe(true)
    expect(state.pastedTexts.value[0]?.name).toBe('粘贴文本.txt')
  })

  it.each(['type', 'file', 'count', 'total'])(
    '已知%s限额失败返回false且保留草稿与已有资源',
    async (kind) => {
      await connect()
      await state.ensureSession()
      state.draft.value = '保留正文'
      state.stagePastedText(longText())
      const original = [...state.pastedTexts.value],
        cap = state.limits.value!.attachments
      if (kind === 'type') cap.extensions = []
      if (kind === 'file') cap.max_file_bytes = 2000
      if (kind === 'count') cap.max_files = 1
      if (kind === 'total') cap.max_total_bytes = 3000
      expect(state.stagePastedText(`中${longText()}`)).toBe(false)
      expect(state.draft.value).toBe('保留正文')
      expect(state.pastedTexts.value).toEqual(original)
      expect(uploadedForms).toHaveLength(0)
      expect(state.error.value).toBeTruthy()
    }
  )

  it('取得真实限额后才验证先前未知草稿，拒绝时不上传也不发送', async () => {
    await connect()
    state.stagePastedText(longText())
    const original = state.pastedTexts.value[0]!
    attachmentLimits.max_file_bytes = 1000
    await state.submit()
    expect(createdSessions).toBe(1)
    expect(state.pastedTexts.value).toEqual([original])
    expect(uploadedForms).toHaveLength(0)
    expect(posts).toHaveLength(0)
    expect(state.pendingInput.value).toBeUndefined()
    expect(state.locked.value).toBe(false)
    expect(state.error.value).toContain('大小')
  })

  it('普通File上传返回确认项并缓存原File，容量也包含本地草稿', async () => {
    await connect()
    state.stagePastedText(longText())
    await state.ensureSession()
    state.limits.value!.attachments.max_total_bytes = 2002
    const file = new File(['abc'], 'normal.txt', { type: 'text/plain' })
    expect(await state.upload([file])).toEqual([])
    expect(uploadedForms).toHaveLength(0)
    state.limits.value!.attachments.max_total_bytes = 40000
    const confirmed = await state.upload([file]),
      id = (confirmed[0]!.value as Attachment).id
    expect(confirmed).toHaveLength(1)
    expect(confirmed[0]!.file).toBe(file)
    expect(state.attachmentFiles.value[id]).toBe(file)
    expect(state.attachmentTexts.value[id]).toBeUndefined()
    expect(state.restoreAttachmentText(id)).toBeUndefined()
    expect(state.removeAttachment(id)).toBe(true)
    expect(state.attachmentFiles.value).toEqual({})
    expect(state.pastedTexts.value).toHaveLength(1)
  })

  it('附件-only发送先上传精确原文，再提交确认ID，不制造占位正文', async () => {
    await connect()
    const raw = `\r\n中文😀\t${longText()}  `
    state.stagePastedText(raw)
    const local = state.pastedTexts.value[0]!
    let cached!: File
    submit = async (body) => {
      const id = JSON.parse(body).context.attachment_ids[0]
      cached = state.attachmentFiles.value[id]!
      expect(state.attachmentTexts.value[id]).toBe(raw)
      expect(state.pastedTexts.value).toEqual([])
      return response({ status: 'accepted', execution_id: 'run', session_id: 's' })
    }
    await state.submit()
    expect(uploadedForms).toHaveLength(1)
    expect(uploadedForms[0]!.get('upload_id')).toBe(local.id)
    expect(await fileText(cached)).toBe(raw)
    expect(JSON.parse(posts[0]!)).toMatchObject({
      content: [],
      context: { attachment_ids: [local.id] }
    })
    expect(state.history.value.messages[0]).toMatchObject({
      role: 'user',
      content: '',
      metadata: { attachment_ids: [local.id] }
    })
    expect(state.attachments.value).toEqual([])
    expect(state.attachmentFiles.value).toEqual({})
    expect(state.attachmentTexts.value).toEqual({})
  })

  it('上传结果未知保留成功项和原文，重复发送不会重放未知上传', async () => {
    await connect()
    state.stagePastedText(longText('a'))
    state.stagePastedText(longText('b'))
    const [first, second] = state.pastedTexts.value,
      originalUpload = uploadAttachment
    uploadAttachment = async (body) => {
      if (body.get('upload_id') === second!.id) throw new TypeError('offline')
      return originalUpload(body)
    }
    await state.submit()
    const uploadedId = first!.id,
      originalFile = state.attachmentFiles.value[uploadedId]
    expect(posts).toHaveLength(0)
    expect(state.pendingInput.value).toBeUndefined()
    expect(state.attachments.value.map((item) => item.id)).toEqual([uploadedId])
    expect(state.attachmentTexts.value[uploadedId]).toBe(first!.text)
    expect(state.pastedTexts.value).toEqual([second])
    expect(state.locked.value).toBe(false)
    uploadAttachment = originalUpload
    await state.submit()
    await state.submit()
    expect(uploadedForms.map((body) => body.get('upload_id'))).toEqual([first!.id, second!.id])
    expect(posts).toHaveLength(0)
    expect(state.currentUnconfirmedUploads.value).toHaveLength(1)
    expect(state.currentUnconfirmedUploads.value[0]).toMatchObject({
      uploadId: second!.id,
      kind: 'attachment'
    })
    expect(state.attachmentFiles.value[uploadedId]).toBe(originalFile)
    expect(state.pastedTexts.value).toEqual([second])
  })

  it('已上传粘贴项在拒绝后仍能从原选区恢复原文，普通File不能恢复', async () => {
    await connect()
    state.draft.value = 'abXXcd'
    const raw = `中😀\r\n${longText()}`
    state.stagePastedText(raw, 2, 4)
    submit = async () =>
      new Response(
        JSON.stringify({ code: 'VALIDATION_ERROR', message: '拒绝', data: null, trace_id: 'test' }),
        {
          status: 422
        }
      )
    await state.submit()
    const id = state.attachments.value[0]!.id
    state.draft.value = 'abYYcd'
    expect(state.restoreAttachmentText(id)).toBe(2 + raw.length)
    expect(state.draft.value).toBe(`ab${raw}YYcd`)
    expect(state.attachments.value).toEqual([])
    expect(state.attachmentFiles.value).toEqual({})
    expect(state.attachmentTexts.value).toEqual({})
    expect(state.restoreAttachmentText(id)).toBeUndefined()
    await state.upload([new File(['普通文件正文'], 'normal.txt')])
    expect(state.restoreAttachmentText(state.attachments.value[0]!.id)).toBeUndefined()
  })

  it('已确认上传项保留原选中文字，连续同选区还原只替换一次', async () => {
    await connect()
    state.draft.value = 'abXXcd'
    const firstText = `XX${longText('a')}`,
      secondText = longText('b')
    state.stagePastedText(firstText, 2, 4)
    state.stagePastedText(secondText, 2, 4)
    submit = async () =>
      new Response(
        JSON.stringify({ code: 'VALIDATION_ERROR', message: '拒绝', data: null, trace_id: 'test' }),
        {
          status: 422
        }
      )
    await state.submit()
    expect(state.pendingInput.value).toBeUndefined()
    const [first, second] = state.attachments.value
    expect(state.restoreAttachmentText(first!.id)).toBe(2 + firstText.length)
    expect(state.draft.value).toBe(`ab${firstText}cd`)
    expect(state.restoreAttachmentText(second!.id)).toBe(2 + secondText.length)
    expect(state.draft.value).toBe(`ab${secondText}${firstText}cd`)
    expect(state.attachments.value).toEqual([])
    expect(state.attachmentTexts.value).toEqual({})
    expect(state.attachmentFiles.value).toEqual({})
  })

  it('公共upload返回失败前成功项，原File缓存随新会话清除', async () => {
    await connect()
    const first = new File(['one'], 'first.txt'),
      second = new File(['two'], 'second.txt'),
      originalUpload = uploadAttachment
    uploadAttachment = async (body) => {
      if ((body.get('file') as File).name === second.name) throw new TypeError('offline')
      return originalUpload(body)
    }
    const confirmed = await state.upload([first, second])
    expect(confirmed).toHaveLength(1)
    expect(confirmed[0]!.file).toBe(first)
    expect(Object.values(state.attachmentFiles.value)).toEqual([first])
    expect(state.currentUnconfirmedUploads.value[0]!.name).toBe('second.txt')
    state.newSession()
    expect(state.attachmentFiles.value).toEqual({})
    expect(state.attachmentTexts.value).toEqual({})
  })

  it('活动Run不能发送新消息，补充模式不能上传，HITL回复不触碰下一条本地草稿', async () => {
    await connect()
    state.stagePastedText(longText())
    const original = [...state.pastedTexts.value]
    await state.ensureSession()
    state.runStates.run = 'running'
    await flushPromises()
    state.setInputMode('message')
    await state.submit()
    state.setInputMode('supplement')
    expect(await state.upload([new File(['x'], 'x.txt')])).toEqual([])
    expect(state.stagePastedText(longText('b'))).toBe(false)
    expect(state.restorePastedText(original[0]!.id)).toBeUndefined()
    state.supplementDraft.value = '补充要求'
    await state.submitSupplement()
    expect(uploadedForms).toHaveLength(0)
    expect(posts).toHaveLength(0)
    expect(state.pastedTexts.value).toEqual(original)
    await state.submit({ execution_id: 'waiting', checkpoint_revision: 1, response: '确认' })
    expect(uploadedForms).toHaveLength(0)
    expect(JSON.parse(posts[0]!).context.attachment_ids).toEqual([])
    expect(state.pastedTexts.value).toEqual(original)
  })

  it('结果未知输入重试只复用冻结请求，不重新上传或吸收下一条本地原文', async () => {
    await connect()
    state.stagePastedText(longText('a'))
    submit = async () => {
      throw new TypeError('offline')
    }
    await state.submit()
    const pending = state.pendingInput.value!,
      original = state.attachments.value[0]!
    expect(state.stagePastedText(longText('b'))).toBe(false)
    expect(state.removeAttachment(original.id)).toBe(false)
    expect(state.restoreAttachmentText(original.id)).toBeUndefined()
    state.draft.value = '之后编辑的草稿'
    state.pastedTexts.value.push({
      id: 'next',
      name: '下一条.txt',
      text: longText('b'),
      size: 2000
    })
    submit = async () => response({ status: 'accepted', execution_id: 'run', session_id: 's' })
    await state.submit(undefined, true)
    expect(posts[1]).toBe(posts[0])
    expect(JSON.parse(posts[1]!).client_message_id).toBe(pending.client_message_id)
    expect(uploadedForms).toHaveLength(1)
    expect(state.draft.value).toBe('之后编辑的草稿')
    expect(state.pastedTexts.value.map((item) => item.id)).toEqual(['next'])
    expect(state.attachmentFiles.value).toEqual({})
  })

  it('异步上传使用发送前正文快照，变化后的草稿不清除', async () => {
    await connect()
    state.draft.value = '发送前正文'
    state.reference({ label: '原引用', source: { id: 'original', nested: { value: 1 } } })
    state.stagePastedText(longText())
    let finish!: (value: Response) => void
    uploadAttachment = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const pending = state.submit()
    await flushPromises()
    state.draft.value = '发送后新草稿'
    state.references.value[0]!.source.nested = { value: 2 }
    finish(
      response({
        id: String(uploadedForms[0]!.get('upload_id')),
        name: (uploadedForms[0]!.get('file') as File).name,
        size: 2000,
        input_path: 'inputs/text',
        sha256: 'h',
        expires_at: '2027-01-01T00:00:00Z'
      })
    )
    await pending
    expect(JSON.parse(posts[0]!)).toMatchObject({
      content: [{ type: 'text', text: '发送前正文' }],
      references: [{ id: 'original', nested: { value: 1 } }]
    })
    expect(state.draft.value).toBe('发送后新草稿')
    expect(state.references.value[0]!.source.nested).toEqual({ value: 2 })
  })

  it('切换会话后迟到上传不能写入或清理新草稿', async () => {
    await connect()
    state.stagePastedText(longText('a'))
    let finish!: (value: Response) => void
    uploadAttachment = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const pending = state.submit()
    await flushPromises()
    await state.openSession('new')
    state.draft.value = '新会话草稿'
    state.stagePastedText(longText('b'))
    const current = [...state.pastedTexts.value]
    finish(
      response({
        id: 'old-upload',
        name: 'old.txt',
        size: 2000,
        input_path: 'inputs/old',
        sha256: 'h',
        expires_at: ''
      })
    )
    await pending
    expect(state.sessionId.value).toBe('new')
    expect(state.draft.value).toBe('新会话草稿')
    expect(state.pastedTexts.value).toEqual(current)
    expect(state.attachments.value).toEqual([])
    expect(state.attachmentFiles.value).toEqual({})
    expect(state.uploading.value).toBe(false)
    expect(posts).toHaveLength(0)
  })

  it('精确事件确认只清理已发送资源，迟到HTTP保留后续上传File和本地草稿', async () => {
    await connect()
    state.stagePastedText(longText('a'))
    let finish!: (value: Response) => void
    submit = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const pending = state.submit()
    await flushPromises()
    const input = state.pendingInput.value!.client_message_id
    publish({
      event: 'execution.completed',
      id: 'ack',
      data: { session_id: 's', input_id: input, run_id: 'run', status: 'completed' }
    })
    await flushPromises()
    expect(state.attachmentFiles.value).toEqual({})
    expect(state.attachmentTexts.value).toEqual({})
    state.draft.value = '下一条草稿'
    state.stagePastedText(longText('b'))
    const nextFile = new File(['new'], 'next.txt'),
      confirmed = await state.upload([nextFile]),
      id = (confirmed[0]!.value as Attachment).id,
      nextLocal = [...state.pastedTexts.value]
    finish(response({ status: 'accepted', execution_id: 'run', session_id: 's' }))
    await pending
    expect(state.draft.value).toBe('下一条草稿')
    expect(state.pastedTexts.value).toEqual(nextLocal)
    expect(state.attachments.value.map((item) => item.id)).toEqual([id])
    expect(state.attachmentFiles.value[id]).toBe(nextFile)
    expect(posts).toHaveLength(1)
  })
})

describe('独立会话交互状态', () => {
  it('重连从持久pending正文发现进行中的Run，commentary段结束不解锁下一条任务', async () => {
    await connect()
    historyLoad = async (id) =>
      response({
        ...emptyHistory(),
        session_id: id,
        event_cursor: 'seen',
        messages: [
          {
            message_id: 'a',
            message_version: 1,
            role: 'assistant',
            content: '已披露前缀',
            root_run_id: 'run',
            sequence: 2,
            phase: 'pending',
            state: 'streaming',
            created_at: '2026-10-11T00:00:00Z'
          }
        ]
      })
    statusLoad = vi.fn(async () => response({ status: 'running' }))
    await state.openSession('s')
    await flushPromises()
    expect(statusLoad).toHaveBeenCalledOnce()
    expect(state.active.value).toBe(true)
    expect(state.history.value.messages[0]?.content).toBe('已披露前缀')
    publish({
      id: 'commentary',
      event: 'message.delta',
      data: {
        session_id: 's',
        session_sequence: 3,
        message_id: 'a',
        message_version: 2,
        role: 'assistant',
        content: '已披露前缀，开始查询。',
        root_run_id: 'run',
        phase: 'commentary',
        state: 'completed'
      }
    })
    await flushPromises()
    expect(state.active.value).toBe(true)
    expect(state.runStates.run).toBe('running')
    state.setInputMode('message')
    state.draft.value = '不能因中间段结束重复启动'
    await state.submit()
    expect(posts).toHaveLength(0)
    publish({
      id: 'finished',
      event: 'execution.completed',
      data: {
        session_id: 's',
        session_sequence: 4,
        run_id: 'run',
        status: 'completed'
      }
    })
    await flushPromises()
    expect(state.active.value).toBe(false)
  })
  it('首次补充状态读取失败且没有快照时继续只读轮询，不重发命令', async () => {
    await connect()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      steeringReadDenied = true
      state.draft.value = '开始任务'
      await state.submit()
      await flushPromises()
      expect(state.steering.value).toBeUndefined()
      expect(state.steeringError.value).toBeTruthy()
      const before = vi
        .mocked(fetch)
        .mock.calls.filter(
          ([input]) => new URL(String(input)).pathname === '/api/v1/chat/runs/steering'
        ).length
      steeringReadDenied = false
      await vi.advanceTimersByTimeAsync(2500)
      await flushPromises()
      const after = vi
        .mocked(fetch)
        .mock.calls.filter(
          ([input]) => new URL(String(input)).pathname === '/api/v1/chat/runs/steering'
        ).length
      expect(after).toBeGreaterThan(before)
      expect(state.steering.value?.accepting).toBe(true)
      expect(state.steeringError.value).toBe('')
      expect(steeringPosts).toHaveLength(0)
    } finally {
      vi.useRealTimers()
    }
  })
  it('补充已保存后回读403仍保留命令身份，终态不能将它转成新草稿', async () => {
    await connect()
    state.draft.value = '开始任务'
    await state.submit()
    await flushPromises()
    state.supplementDraft.value = '已保存但回执被拒绝'
    steeringReadDenied = true
    steerSubmit = async (body) => {
      const request = JSON.parse(body) as SteeringInput
      steeringState.items = [
        { command_id: request.command_id, text: request.text, status: 'PENDING' }
      ]
      return new Response(
        JSON.stringify({
          code: 'FORBIDDEN',
          message: 'Runtime 拒绝访问',
          data: null,
          trace_id: 'test'
        }),
        { status: 403 }
      )
    }
    await state.submitSupplement()
    await flushPromises()
    const command = state.pendingSteering.value?.command_id
    expect(command).toBeTruthy()
    state.draft.value = '独立草稿'
    state.runStates.run = 'completed'
    await flushPromises()
    expect(state.pendingSteering.value?.command_id).toBe(command)
    expect(state.draft.value).toBe('独立草稿')
    expect(posts).toHaveLength(1)
    steeringReadDenied = false
    steeringState.accepting = false
    await state.refreshSteering()
    expect(state.pendingSteering.value).toBeUndefined()
    expect(state.steering.value?.items[0]?.status).toBe('PENDING')
    expect(state.supplementDraft.value).toBe('')
    expect(state.draft.value).toBe('独立草稿')
    expect(steeringPosts).toHaveLength(1)
  })
  it('补充与下一条草稿独立，完成时只保留未发送补充', async () => {
    await connect()
    state.draft.value = '开始任务'
    await state.submit()
    await flushPromises()
    expect(state.inputMode.value).toBe('supplement')
    expect(state.canSteer.value).toBe(true)
    state.composerDraft.value = '只分析最近一周'
    state.setInputMode('message')
    state.composerDraft.value = '下一项任务'
    state.setInputMode('supplement')
    expect(state.composerDraft.value).toBe('只分析最近一周')
    await state.submitSupplement()
    await flushPromises()
    expect(state.steering.value?.items[0]?.status).toBe('PENDING')
    expect(state.supplementDraft.value).toBe('')
    expect(state.draft.value).toBe('下一项任务')
    state.supplementDraft.value = '仍在编辑的要求'
    state.runStates.run = 'completed'
    await flushPromises()
    expect(state.inputMode.value).toBe('message')
    expect(state.draft.value).toBe('下一项任务\n\n仍在编辑的要求')
    expect(state.draft.value).not.toContain('只分析最近一周')
    expect(posts).toHaveLength(1)
  })
  it('服务端不接收补充时禁用发送，消费状态按查询结果更新', async () => {
    await connect()
    state.draft.value = '开始任务'
    await state.submit()
    await flushPromises()
    steeringState = {
      run_id: 'run',
      accepting: false,
      items: [{ command_id: 'previous', text: '已有补充', status: 'INCLUDED' }]
    }
    await state.refreshSteering()
    state.supplementDraft.value = '不能继续补充'
    await state.submitSupplement()
    expect(state.canSteer.value).toBe(false)
    expect(steeringPosts).toHaveLength(0)
    expect(state.steering.value?.items[0]?.status).toBe('INCLUDED')
    steeringState.items[0]!.status = 'CONSUMED'
    await state.refreshSteering()
    expect(state.steering.value?.items[0]?.status).toBe('CONSUMED')
  })
  it('补充提交在途锁定导航，受理确认后解除', async () => {
    await connect()
    state.draft.value = '开始任务'
    await state.submit()
    await flushPromises()
    state.supplementDraft.value = '在途补充'
    let finish!: (value: Response) => void
    steerSubmit = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const sending = state.submitSupplement()
    await flushPromises()
    expect(state.steeringSending.value).toBe(true)
    expect(state.locked.value).toBe(true)
    const pending = state.pendingSteering.value!
    finish(
      response({
        run_id: pending.run_id,
        accepting: true,
        items: [{ command_id: pending.command_id, text: pending.text, status: 'PENDING' }]
      })
    )
    await sending
    await flushPromises()
    expect(state.pendingSteering.value).toBeUndefined()
    expect(state.locked.value).toBe(false)
  })
  it('补充网络结果未知保留命令键，显式重试不替换正文', async () => {
    await connect()
    state.draft.value = '开始任务'
    await state.submit()
    await flushPromises()
    state.supplementDraft.value = '仅执行一次的补充'
    steerSubmit = async () => {
      throw new TypeError('offline')
    }
    await state.submitSupplement()
    await flushPromises()
    expect(state.pendingSteering.value).toBeTruthy()
    expect(state.locked.value).toBe(true)
    state.supplementDraft.value = '之后编辑的补充'
    await state.submitSupplement()
    expect(steeringPosts).toHaveLength(1)
    steerSubmit = async (body) => {
      const request = JSON.parse(body) as SteeringInput
      steeringState.items = [
        { command_id: request.command_id, text: request.text, status: 'CONSUMED' }
      ]
      return response(steeringState)
    }
    await state.submitSupplement(true)
    await flushPromises()
    expect(steeringPosts[1]).toBe(steeringPosts[0])
    expect(state.pendingSteering.value).toBeUndefined()
    expect(state.supplementDraft.value).toBe('之后编辑的补充')
    expect(state.locked.value).toBe(false)
  })
  it('原补充结果未知后重试校验拒绝，仍保留原身份等待只读核对', async () => {
    await connect()
    state.draft.value = '开始任务'
    await state.submit()
    await flushPromises()
    state.supplementDraft.value = '原补充'
    steerSubmit = async () => {
      throw new TypeError('offline')
    }
    await state.submitSupplement()
    await flushPromises()
    const original = state.pendingSteering.value!
    steerSubmit = async () =>
      new Response(
        JSON.stringify({ code: 'VALIDATION_ERROR', message: '拒绝', data: null, trace_id: 'test' }),
        { status: 400 }
      )
    await state.submitSupplement(true)
    await flushPromises()
    expect(steeringPosts[1]).toBe(steeringPosts[0])
    expect(state.pendingSteering.value).toEqual(original)
    await state.submitSupplement()
    expect(steeringPosts).toHaveLength(2)
    steeringState.items = [
      { command_id: original.command_id, text: original.text, status: 'CONSUMED' }
    ]
    await state.refreshSteering()
    expect(state.pendingSteering.value).toBeUndefined()
  })
  it('首次补充在入口被明确校验拒绝后保留文本并允许修正', async () => {
    await connect()
    state.draft.value = '开始任务'
    await state.submit()
    await flushPromises()
    state.supplementDraft.value = '待修正补充'
    steerSubmit = async () =>
      new Response(
        JSON.stringify({ code: 'VALIDATION_ERROR', message: '拒绝', data: null, trace_id: 'test' }),
        { status: 400 }
      )
    await state.submitSupplement()
    await flushPromises()
    expect(state.pendingSteering.value).toBeUndefined()
    expect(state.supplementDraft.value).toBe('待修正补充')
    expect(state.canSteer.value).toBe(true)
  })
  it('查询补充结果期间不重试，按原命令身份确认后不重新发送', async () => {
    await connect()
    state.draft.value = '开始任务'
    await state.submit()
    await flushPromises()
    state.supplementDraft.value = '仅执行一次的补充'
    steerSubmit = async () => {
      throw new TypeError('offline')
    }
    await state.submitSupplement()
    await flushPromises()
    const pending = state.pendingSteering.value!
    const original = vi.mocked(fetch).getMockImplementation()!
    let finish!: (value: Response) => void
    vi.mocked(fetch).mockImplementation((input, init) =>
      new URL(String(input)).pathname === '/api/v1/chat/runs/steering'
        ? new Promise((resolve) => {
            finish = resolve
          })
        : original(input, init)
    )
    const reading = state.refreshSteering()
    await flushPromises()
    expect(state.steeringLoading.value).toBe(true)
    expect(state.locked.value).toBe(true)
    await state.submitSupplement(true)
    expect(steeringPosts).toHaveLength(1)
    finish(
      response({
        run_id: pending.run_id,
        accepting: true,
        items: [{ command_id: pending.command_id, text: pending.text, status: 'PENDING' }]
      })
    )
    await reading
    expect(state.pendingSteering.value).toBeUndefined()
    expect(steeringPosts).toHaveLength(1)
    expect(state.locked.value).toBe(false)
  })
  it.each([true, false])(
    '完整查询确认未受理后保留正文，本地完成事件已到达=%s',
    async (completed) => {
      await connect()
      state.draft.value = '开始任务'
      await state.submit()
      await flushPromises()
      state.supplementDraft.value = '未受理补充正文'
      steerSubmit = async () => {
        throw new TypeError('offline')
      }
      await state.submitSupplement()
      await flushPromises()
      expect(state.pendingSteering.value).toBeTruthy()
      state.draft.value = '独立草稿'
      if (completed) state.runStates.run = 'completed'
      await flushPromises()
      steeringState = { run_id: 'run', accepting: false, items: [] }
      await state.refreshSteering()
      expect(state.pendingSteering.value).toBeUndefined()
      expect(state.locked.value).toBe(false)
      expect(state.draft.value).toBe('独立草稿\n\n未受理补充正文')
      expect(state.supplementDraft.value).toBe('')
      expect(state.steeringError.value).toContain('原补充未受理')
      expect(state.steeringError.value).toContain('下一条消息草稿')
      expect(steeringPosts).toHaveLength(1)
      expect(posts).toHaveLength(1)
    }
  )
  it.each(['accepting', 'read-failed'])('补充核对为 %s 时仍保留原命令与锁定', async (mode) => {
    await connect()
    state.draft.value = '开始任务'
    await state.submit()
    await flushPromises()
    state.supplementDraft.value = '待核对正文'
    steerSubmit = async () => {
      throw new TypeError('offline')
    }
    await state.submitSupplement()
    await flushPromises()
    const pending = state.pendingSteering.value
    steeringState = { run_id: 'run', accepting: true, items: [] }
    steeringReadDenied = mode === 'read-failed'
    await state.refreshSteering()
    expect(state.pendingSteering.value).toBe(pending)
    expect(state.locked.value).toBe(true)
    expect(state.supplementDraft.value).toBe('待核对正文')
    expect(state.steeringError.value).not.toContain('原补充未受理')
    expect(steeringPosts).toHaveLength(1)
  })
  it('结束时结果未知的补充不转成新任务，查询确认后只显示真实消费状态', async () => {
    await connect()
    state.draft.value = '开始任务'
    await state.submit()
    await flushPromises()
    state.supplementDraft.value = '可能已经受理'
    steerSubmit = async () => {
      throw new TypeError('offline')
    }
    await state.submitSupplement()
    await flushPromises()
    const pending = state.pendingSteering.value!
    state.draft.value = '独立草稿'
    state.runStates.run = 'completed'
    await flushPromises()
    expect(state.draft.value).toBe('独立草稿')
    expect(state.pendingSteering.value).toBeTruthy()
    steeringState = {
      run_id: 'run',
      accepting: false,
      items: [{ command_id: pending.command_id, text: pending.text, status: 'PENDING' }]
    }
    await state.refreshSteering()
    expect(state.pendingSteering.value).toBeUndefined()
    expect(state.supplementDraft.value).toBe('')
    expect(state.draft.value).toBe('独立草稿')
    expect(posts).toHaveLength(1)
    expect(state.steeringError.value).not.toContain('原补充未受理')
  })
  for (const mismatch of [
    'none',
    'session',
    'run',
    'input',
    'approved',
    'reason',
    'dependency'
  ] as const) {
    it(`挂起计划审批只消费精确工作事实：${mismatch}`, async () => {
      await connect()
      const work: Work = {
        work_id: 'plan',
        session_id: mismatch === 'session' ? 'other' : 's',
        input_id: mismatch === 'input' ? 'other' : 'root-input',
        run_id: mismatch === 'run' ? 'other' : 'external',
        run_revision: 11,
        run_status: 'suspended',
        revision: 1,
        status: 'active',
        plan_revision: 2,
        approved_revision: mismatch === 'approved' ? 2 : null,
        approval_required: mismatch !== 'dependency',
        tasks: [],
        results: []
      }
      historyLoad = async (id) =>
        response({
          ...emptyHistory(),
          session_id: id,
          work_plans: [work],
          activities: [
            {
              node_type: 'agent_runtime',
              status: 'waiting_hitl',
              render: {
                root_run_id: 'external',
                root_input_id: 'root-input',
                reason: mismatch === 'reason' ? undefined : 'plan_approval_required'
              }
            }
          ]
        })
      statusLoad = async () =>
        response({
          status: 'suspended',
          session_id: 's',
          execution_id: 'external',
          card: null,
          message: null,
          collaboration: null,
          input_id: null,
          replayed: false
        })
      await state.openSession('s')
      expect(state.runStates.external).toBe(mismatch === 'none' ? 'waiting_hitl' : 'suspended')
      expect(state.active.value).toBe(mismatch !== 'none')
      expect(state.history.value.pending_interactions).toEqual([])
      expect(posts).toHaveLength(0)
    })
  }
  it('没有真实人工待办的 suspended 仍是活动执行', async () => {
    await connect()
    historyLoad = async (id) =>
      response({
        ...emptyHistory(),
        session_id: id,
        activities: [
          {
            node_id: 'external',
            node_type: 'agent_runtime',
            status: 'suspended',
            render: { root_run_id: 'external' }
          }
        ]
      })
    statusLoad = async () => response({ status: 'suspended' })
    await state.openSession('s')
    expect(state.active.value).toBe(true)
    state.draft.value = '不能误认为人工等待'
    await state.submit()
    expect(posts).toHaveLength(0)
  })
  it('刷新时 suspended 与所属真实待办组合为人工等待，允许另起任务', async () => {
    await connect()
    historyLoad = async (id) =>
      response({
        ...emptyHistory(),
        session_id: id,
        pending_interactions: [{ execution_id: 'waiting', run_revision: 2, checkpoint_revision: 2 }]
      })
    statusLoad = async () => response({ status: 'suspended' })
    await state.openSession('s')
    expect(state.active.value).toBe(false)
    expect(state.locked.value).toBe(false)
    state.draft.value = '独立任务'
    await state.submit()
    expect(posts).toHaveLength(1)
    expect(state.history.value.pending_interactions[0]?.execution_id).toBe('waiting')
  })
  it('等待期间允许另起任务，恢复只消费目标且保留独立草稿', async () => {
    await connect()
    await state.openSession('s')
    const first = { execution_id: 'first', run_revision: 2, checkpoint_revision: 2 }
    const second = { execution_id: 'second', run_revision: 3, checkpoint_revision: 3 }
    state.history.value.pending_interactions = [first, second]
    state.draft.value = '处理另一项工作'
    await state.submit()
    expect(posts).toHaveLength(1)
    expect(state.history.value.pending_interactions).toEqual([first, second])
    state.draft.value = '尚未发送的草稿'
    await state.submit({ ...first, response: '确认' })
    expect(state.history.value.pending_interactions).toEqual([second])
    expect(state.draft.value).toBe('尚未发送的草稿')
    expect(JSON.parse(posts[1]!).content).toEqual([])
    expect(JSON.parse(posts[1]!).hitl_response.execution_id).toBe('first')
  })
  it('回复传输结果未知时，从同一真实用户回复历史确认原输入且不重放', async () => {
    await connect()
    await state.openSession('s')
    const pending = { execution_id: 'run', run_revision: 2, checkpoint_revision: 2 }
    state.history.value.pending_interactions = [pending]
    submit = async () => {
      throw new TypeError('response lost')
    }
    await state.submit({ ...pending, response: '税率为13%' })
    const input = JSON.parse(posts[0]!).client_message_id
    expect(state.pendingInput.value).toBeTruthy()
    historyLoad = async (id) =>
      response({
        ...emptyHistory(),
        session_id: id,
        messages: [
          {
            message_id: input,
            message_version: 1,
            role: 'user',
            content: '税率为13%',
            metadata: {
              input_id: input,
              root_input_id: 'root',
              root_run_id: 'run',
              interaction_reply_kind: 'supplement'
            }
          }
        ]
      })
    await state.reconnect()
    expect(state.pendingInput.value).toBeUndefined()
    expect(state.history.value.messages[0]?.content).toBe('税率为13%')
    expect(posts).toHaveLength(1)
  })
  it('目录尚未读完时锁住交互，避免初始化选择清除草稿', async () => {
    const original = vi.mocked(fetch).getMockImplementation()!
    let finish!: (value: Response) => void
    const teams = new Promise<Response>((resolve) => {
      finish = resolve
    })
    vi.mocked(fetch).mockImplementation((input, init) =>
      new URL(String(input)).pathname === '/api/v1/chat/teams' ? teams : original(input, init)
    )
    const connecting = connect()
    await flushPromises()
    expect(state.client.value).toBeDefined()
    expect(state.connecting.value).toBe(true)
    expect(state.locked.value).toBe(true)
    finish(response([]))
    await connecting
    expect(state.locked.value).toBe(false)
  })

  it('双击只提交一次，并携带明确会话和终端用户身份', async () => {
    await connect()
    state.draft.value = '你好'
    const one = state.submit(),
      two = state.submit()
    await Promise.all([one, two])
    expect(posts).toHaveLength(1)
    expect(JSON.parse(posts[0]!)).toMatchObject({
      session_id: 's',
      agent_id: 'agent',
      context: { end_user_id: '42' },
      content: [{ type: 'text', text: '你好' }]
    })
    expect(state.draft.value).toBe('')
    expect(state.history.value.messages).toEqual([
      expect.objectContaining({
        message_id: JSON.parse(posts[0]!).client_message_id,
        role: 'user',
        content: '你好',
        root_run_id: 'run'
      })
    ])
  })
  it('网络结果未知时保留原请求，显式重试使用同一身份与正文', async () => {
    await connect()
    state.draft.value = '只执行一次'
    submit = async () => {
      throw new TypeError('offline')
    }
    await state.submit()
    expect(state.pendingInput.value).toBeTruthy()
    state.draft.value = '不能替换已发送正文'
    await state.submit()
    expect(posts).toHaveLength(1)
    submit = async () => response({ status: 'accepted', execution_id: 'run', session_id: 's' })
    await state.submit(undefined, true)
    expect(posts).toHaveLength(2)
    expect(posts[0]).toBe(posts[1])
  })
  it('恢复观察期间不能重试未知提交，读取确认后不重发', async () => {
    await connect()
    state.draft.value = '只执行一次'
    submit = async () => {
      throw new TypeError('offline')
    }
    await state.submit()
    const input = state.pendingInput.value!.client_message_id
    let finish!: (value: Response) => void
    historyLoad = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    const reconnecting = state.reconnect()
    await flushPromises()
    expect(state.loading.value).toBe(true)
    await state.submit(undefined, true)
    expect(posts).toHaveLength(1)
    finish(
      response({
        ...emptyHistory(),
        session_id: 's',
        messages: [{ message_id: input, role: 'user', content: '只执行一次' }]
      })
    )
    await reconnecting
    expect(state.pendingInput.value).toBeUndefined()
    expect(posts).toHaveLength(1)
  })
  it.each([403, 404, 409])(
    '原消息结果未知后重试被%s拒绝，仍保留原请求等待历史核对',
    async (status) => {
      await connect()
      await state.openSession('s')
      state.draft.value = '只执行一次'
      submit = async () => {
        throw new TypeError('response lost')
      }
      await state.submit()
      const original = state.pendingInput.value!
      submit = async () =>
        new Response(
          JSON.stringify({
            code: 'CONFLICT',
            message: '当前请求被拒绝',
            data: null,
            trace_id: 'test'
          }),
          { status }
        )
      await state.submit(undefined, true)
      expect(posts).toEqual([posts[0], posts[0]])
      expect(state.pendingInput.value).toEqual(original)
      expect(state.composerLocked.value).toBe(true)
      expect(state.draft.value).toBe('只执行一次')
      expect(state.error.value).toBe('当前请求被拒绝')
      await state.submit()
      expect(posts).toHaveLength(2)
      historyLoad = async (id) =>
        response({
          ...emptyHistory(),
          session_id: id,
          closed: true,
          messages: [
            { message_id: original.client_message_id, role: 'user', content: '只执行一次' }
          ]
        })
      await state.reconnect()
      expect(state.pendingInput.value).toBeUndefined()
      expect(state.sessionClosed.value).toBe(true)
      expect(state.composerLocked.value).toBe(true)
      expect(posts).toHaveLength(2)
    }
  )
  it('原消息结果未知后重试额度不足，仍保留原身份且不允许新发送', async () => {
    await connect()
    state.draft.value = '保留原消息'
    submit = async () => {
      throw new TypeError('response lost')
    }
    await state.submit()
    const original = state.pendingInput.value!
    submit = async () => response({ status: 'quota_exhausted', message: '本月额度已用尽' })
    await state.submit(undefined, true)
    expect(posts).toEqual([posts[0], posts[0]])
    expect(state.pendingInput.value).toEqual(original)
    expect(state.composerLocked.value).toBe(true)
    expect(state.draft.value).toBe('保留原消息')
    expect(state.notice.value).toBe('本月额度已用尽')
    await state.submit()
    expect(posts).toHaveLength(2)
  })
  it.each(['validation', 'quota'])(
    '首次消息在入口被%s明确拒绝，释放编辑并保留草稿',
    async (reason) => {
      await connect()
      state.draft.value = '可修正的原稿'
      submit = async () =>
        reason === 'quota'
          ? response({ status: 'quota_exhausted', message: '本月额度已用尽' })
          : new Response(
              JSON.stringify({
                code: 'VALIDATION_ERROR',
                message: '输入校验未通过',
                data: null,
                trace_id: 'test'
              }),
              { status: 422 }
            )
      await state.submit()
      expect(state.pendingInput.value).toBeUndefined()
      expect(state.composerLocked.value).toBe(false)
      expect(state.draft.value).toBe('可修正的原稿')
      expect(posts).toHaveLength(1)
    }
  )
  it('精确事件回执解除提交，其他会话或同文本的其他输入不能确认本次请求', async () => {
    await connect()
    let finish!: (value: Response) => void
    submit = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    state.draft.value = '你好'
    const submitting = state.submit()
    await flushPromises()
    const input = state.pendingInput.value!.client_message_id
    publish({
      event: 'execution.completed',
      id: 'other-session',
      data: {
        session_id: 'other',
        input_id: input,
        run_id: 'other',
        status: 'completed'
      }
    })
    publish({
      event: 'execution.completed',
      id: 'other-input',
      data: {
        session_id: 's',
        input_id: 'other',
        run_id: 'other',
        status: 'completed'
      }
    })
    await flushPromises()
    expect(state.pendingInput.value?.client_message_id).toBe(input)
    expect(state.sending.value).toBe(true)
    publish({
      event: 'execution.completed',
      id: 'confirmed',
      data: {
        session_id: 's',
        input_id: input,
        run_id: 'run',
        status: 'completed'
      }
    })
    await flushPromises()
    expect(state.pendingInput.value).toBeUndefined()
    expect(state.sending.value).toBe(false)
    expect(state.locked.value).toBe(false)
    expect(state.draft.value).toBe('')
    expect(state.history.value.messages[0]).toMatchObject({
      message_id: input,
      role: 'user',
      content: '你好',
      root_run_id: 'run'
    })
    historyLoad = async (id) =>
      response({
        ...emptyHistory(),
        session_id: id,
        messages: [
          {
            message_id: input,
            message_version: 1,
            role: 'user',
            content: '你好',
            metadata: { client_message_id: input }
          }
        ]
      })
    finish(response({ status: 'accepted', execution_id: 'run', session_id: 's' }))
    await submitting
    expect(state.runStates.run).toBe('completed')
    expect(posts).toHaveLength(1)
    expect(state.history.value.messages).toHaveLength(1)
  })
  it.each(['success', 'error'])('同会话迟到 HTTP %s 不清除新草稿或释放新请求', async (kind) => {
    await connect()
    let finishOld!: (value: Response) => void,
      rejectOld!: (cause: Error) => void,
      finishNew!: (value: Response) => void
    submit = () =>
      new Promise((resolve, reject) => {
        finishOld = resolve
        rejectOld = reject
      })
    state.draft.value = '第一条'
    const old = state.submit()
    await flushPromises()
    const input = state.pendingInput.value!.client_message_id
    publish({
      event: 'execution.completed',
      id: 'confirmed',
      data: {
        session_id: 's',
        input_id: input,
        run_id: 'first',
        status: 'completed'
      }
    })
    await flushPromises()
    state.draft.value = '第二条'
    submit = () =>
      new Promise((resolve) => {
        finishNew = resolve
      })
    const next = state.submit()
    await flushPromises()
    const newer = state.pendingInput.value
    if (kind === 'success')
      finishOld(response({ status: 'accepted', execution_id: 'first', session_id: 's' }))
    else rejectOld(new TypeError('late transport failure'))
    await old
    expect(state.pendingInput.value).toBe(newer)
    expect(state.draft.value).toBe('第二条')
    expect(state.sending.value).toBe(true)
    expect(state.error.value).toBe('')
    expect(state.runStates.first).toBe('completed')
    finishNew(response({ status: 'accepted', execution_id: 'second', session_id: 's' }))
    await next
    expect(state.pendingInput.value).toBeUndefined()
    expect(posts).toHaveLength(2)
  })
  it.each(['message', 'process'])(
    '恢复观察通过精确 %s 身份确认未知提交，只查询不重发',
    async (kind) => {
      await connect()
      state.draft.value = '只执行一次'
      submit = async () => {
        throw new TypeError('offline')
      }
      await state.submit()
      const input = state.pendingInput.value!.client_message_id
      historyLoad = async (id) =>
        response({
          ...emptyHistory(),
          session_id: id,
          messages: [{ message_id: 'different-input', role: 'user', content: '只执行一次' }]
        })
      await state.reconnect()
      expect(state.pendingInput.value?.client_message_id).toBe(input)
      state.draft.value = '之后编辑的独立草稿'
      historyLoad = async (id) =>
        response({
          ...emptyHistory(),
          session_id: id,
          messages:
            kind === 'message' ? [{ message_id: input, role: 'user', content: '只执行一次' }] : [],
          processes:
            kind === 'process'
              ? [
                  {
                    process_id: 'receipt',
                    input_id: input,
                    root_run_id: 'run',
                    run_id: 'run',
                    status: 'succeeded',
                    type: 'tool',
                    sequence: 1,
                    ordinal: 0,
                    updated_sequence: 1,
                    presentation: { title: '处理消息', summary: '' }
                  }
                ]
              : []
        })
      await state.reconnect()
      expect(state.pendingInput.value).toBeUndefined()
      expect(state.locked.value).toBe(false)
      expect(state.error.value).toBe('')
      expect(state.draft.value).toBe('之后编辑的独立草稿')
      expect(posts).toHaveLength(1)
    }
  )
  it('人工回复只匹配本次 input_id，旧 root_input_id 或相同运行终态不能确认', async () => {
    await connect()
    await state.openSession('s')
    state.history.value.pending_interactions = [
      { execution_id: 'run', run_revision: 1, checkpoint_revision: 2 },
      { execution_id: 'another', run_revision: 1, checkpoint_revision: 3 }
    ]
    state.draft.value = '独立草稿'
    submit = async () => {
      throw new TypeError('offline')
    }
    await state.submit({ execution_id: 'run', checkpoint_revision: 2, response: '确认' })
    const input = state.pendingInput.value!.client_message_id
    publish({
      event: 'execution.accepted',
      id: 'old-root',
      data: {
        session_id: 's',
        input_id: 'original',
        root_input_id: input,
        run_id: 'run'
      }
    })
    await flushPromises()
    expect(state.pendingInput.value?.client_message_id).toBe(input)
    publish({
      event: 'execution.accepted',
      id: 'new-input',
      data: {
        session_id: 's',
        input_id: input,
        root_input_id: 'original',
        run_id: 'run'
      }
    })
    await flushPromises()
    expect(state.pendingInput.value).toBeUndefined()
    expect(state.draft.value).toBe('独立草稿')
    expect(state.history.value.pending_interactions.map((item) => item.execution_id)).toEqual([
      'another'
    ])
    expect(posts).toHaveLength(1)
  })
  it.each(['completed', 'terminated'])(
    '缺少输入身份的 %s 终态不被迟到 HTTP 受理回退',
    async (status) => {
      await connect()
      let finish!: (value: Response) => void
      submit = () =>
        new Promise((resolve) => {
          finish = resolve
        })
      state.draft.value = '终态不能回退'
      const pending = state.submit()
      await flushPromises()
      publish({
        event: 'execution.completed',
        id: 'finished',
        data: { run_id: 'run', status }
      })
      await flushPromises()
      expect(state.pendingInput.value).toBeDefined()
      finish(response({ status: 'accepted', execution_id: 'run', session_id: 's' }))
      await pending
      expect(state.pendingInput.value).toBeUndefined()
      expect(state.runStates.run).toBe(status)
      expect(state.active.value).toBe(false)
    }
  )
  it.each(['completed', 'failed', 'cancelled', 'outcome_unknown', 'terminated'])(
    'HTTP 明确返回 %s 时保留真实终态',
    async (status) => {
      await connect()
      state.draft.value = '明确终态'
      submit = async () => response({ status, execution_id: 'run', session_id: 's' })
      await state.submit()
      expect(state.runStates.run).toBe(status)
      expect(state.active.value).toBe(false)
      expect(state.pendingInput.value).toBeUndefined()
    }
  )
  it.each(['cancel-response', 'status-poll', 'sse'] as const)(
    'terminated 收口会话并允许新输入，不重放旧请求：%s',
    async (source) => {
      await connect()
      state.draft.value = '原请求'
      await state.submit()
      const originalFetch = globalThis.fetch
      const cancelRead = vi.fn(async () =>
        response({ status: source === 'cancel-response' ? 'terminated' : 'running' })
      )
      vi.stubGlobal(
        'fetch',
        vi.fn(async (input: URL, init: RequestInit = {}) =>
          new URL(input).pathname === '/api/v1/chat/runs/cancel'
            ? cancelRead()
            : originalFetch(input, init)
        )
      )
      statusLoad = vi.fn(async () => response({ status: 'terminated' }))
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      try {
        if (source === 'sse') {
          publish({
            event: 'execution.completed',
            id: 'terminated',
            data: { run_id: 'run', status: 'terminated', reason: 'budget_exceeded' }
          })
          await flushPromises()
        } else {
          await state.cancel()
          await vi.advanceTimersByTimeAsync(1000)
        }
        expect(state.runStates.run).toBe('terminated')
        expect(state.active.value).toBe(false)
        expect(state.cancelling.value).toBe(false)
        expect(state.locked.value).toBe(false)
        expect(posts).toHaveLength(1)
        const reads = vi.mocked(statusLoad).mock.calls.length
        await vi.advanceTimersByTimeAsync(10_000)
        expect(vi.mocked(statusLoad).mock.calls).toHaveLength(reads)
        expect(cancelRead).toHaveBeenCalledTimes(source === 'sse' ? 0 : 1)
        state.draft.value = '新的独立请求'
        await state.submit()
        expect(posts).toHaveLength(2)
        expect(JSON.parse(posts[1]!).client_message_id).not.toBe(
          JSON.parse(posts[0]!).client_message_id
        )
      } finally {
        vi.useRealTimers()
      }
    }
  )
  it('取消权限明确拒绝时解除取消等待，保留运行状态和原消息', async () => {
    await connect()
    state.draft.value = '原请求'
    await state.submit()
    const originalFetch = globalThis.fetch
    const cancelRead = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            code: 'foundation.permission.denied',
            message: '没有操作权限',
            data: null,
            trace_id: 'test'
          }),
          { status: 403, headers: { 'content-type': 'application/json' } }
        )
    )
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: URL, init: RequestInit = {}) =>
        new URL(input).pathname === '/api/v1/chat/runs/cancel'
          ? cancelRead()
          : originalFetch(input, init)
      )
    )
    statusLoad = vi.fn(async () => response({ status: 'running' }))
    await state.cancel()
    expect(cancelRead).toHaveBeenCalledOnce()
    expect(state.cancelling.value).toBe(false)
    expect(state.active.value).toBe(true)
    expect(state.runStates.run).toBe('running')
    expect(state.error.value).toContain('取消请求未获授权')
    expect(statusLoad).not.toHaveBeenCalled()
    expect(posts).toHaveLength(1)
  })
  it('取消连接中断时继续只读查询原运行，不重新发送取消', async () => {
    await connect()
    state.draft.value = '原请求'
    await state.submit()
    const originalFetch = globalThis.fetch
    const cancelRead = vi.fn(async () => {
      throw new TypeError('连接中断')
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: URL, init: RequestInit = {}) =>
        new URL(input).pathname === '/api/v1/chat/runs/cancel'
          ? cancelRead()
          : originalFetch(input, init)
      )
    )
    statusLoad = vi.fn(async () => response({ status: 'running' }))
    await state.cancel()
    await flushPromises()
    expect(cancelRead).toHaveBeenCalledOnce()
    expect(statusLoad).toHaveBeenCalledOnce()
    expect(state.cancelling.value).toBe(true)
    expect(state.runStates.run).toBe('running')
    expect(posts).toHaveLength(1)
  })
  it.each(['pending-timer', 'late-running', 'late-error'] as const)(
    '取消观察在 SSE 终止后停止，不受旧查询影响：%s',
    async (mode) => {
      await connect()
      state.draft.value = '只发送一次'
      await state.submit()
      const originalFetch = globalThis.fetch
      const cancelRead = vi.fn(async () => response({ status: 'running' }))
      vi.stubGlobal(
        'fetch',
        vi.fn(async (input: URL, init: RequestInit = {}) =>
          new URL(input).pathname === '/api/v1/chat/runs/cancel'
            ? cancelRead()
            : originalFetch(input, init)
        )
      )
      let resolveStatus!: (value: Response) => void
      let rejectStatus!: (reason: Error) => void
      statusLoad = vi.fn(
        () =>
          new Promise<Response>((resolve, reject) => {
            resolveStatus = resolve
            rejectStatus = reject
          })
      )
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      try {
        await state.cancel()
        if (mode !== 'pending-timer') {
          await vi.advanceTimersByTimeAsync(1000)
          expect(statusLoad).toHaveBeenCalledOnce()
        }
        publish({
          event: 'execution.completed',
          id: 'terminated',
          data: { run_id: 'run', status: 'terminated' }
        })
        await flushPromises()
        if (mode === 'late-running') resolveStatus(response({ status: 'running' }))
        if (mode === 'late-error') rejectStatus(new TypeError('late network error'))
        await flushPromises()
        await vi.advanceTimersByTimeAsync(20_000)
        expect(statusLoad).toHaveBeenCalledTimes(mode === 'pending-timer' ? 0 : 1)
        expect(cancelRead).toHaveBeenCalledOnce()
        expect(state.runStates.run).toBe('terminated')
        expect(state.cancelling.value).toBe(false)
        expect(state.active.value).toBe(false)
        expect(state.error.value).toBe('')
        expect(posts).toHaveLength(1)
      } finally {
        vi.useRealTimers()
      }
    }
  )
  it('明确校验拒绝恢复编辑，修正后使用新输入身份提交', async () => {
    await connect()
    state.draft.value = '需要修正'
    submit = async () =>
      new Response(
        JSON.stringify({
          code: 'INVALID_ARGUMENT',
          message: '请修正内容',
          data: null,
          trace_id: 'test'
        }),
        { status: 422 }
      )
    await state.submit()
    expect(state.pendingInput.value).toBeUndefined()
    expect(state.locked.value).toBe(false)
    expect(state.draft.value).toBe('需要修正')
    expect(state.error.value).toBe('请修正内容')
    state.draft.value = '修正完成'
    submit = async () => response({ status: 'accepted', execution_id: 'run', session_id: 's' })
    await state.submit()
    expect(posts).toHaveLength(2)
    expect(JSON.parse(posts[1]!).client_message_id).not.toBe(
      JSON.parse(posts[0]!).client_message_id
    )
    expect(JSON.parse(posts[1]!).content).toEqual([{ type: 'text', text: '修正完成' }])
  })
  it.each([500, 400])('HTTP %s 的服务端异常或损坏响应仍保留原请求', async (status) => {
    await connect()
    state.draft.value = '不能推断未受理'
    submit = async () =>
      status === 500
        ? new Response(
            JSON.stringify({
              code: 'INTERNAL_ERROR',
              message: '内部异常',
              data: null,
              trace_id: 'test'
            }),
            { status }
          )
        : new Response('bad response', { status })
    await state.submit()
    expect(state.pendingInput.value).toBeTruthy()
    expect(state.locked.value).toBe(true)
    submit = async () => response({ status: 'accepted', execution_id: 'run', session_id: 's' })
    await state.submit(undefined, true)
    expect(posts[1]).toBe(posts[0])
  })
  it('失效补参版本被明确拒绝后刷新当前等待卡片', async () => {
    await connect()
    await state.openSession('s')
    state.history.value.pending_interactions = [
      {
        execution_id: 'run',
        run_revision: '1',
        checkpoint_revision: 'old',
        pending_id: 'old'
      }
    ]
    historyLoad = async (id) =>
      response({
        ...emptyHistory(),
        session_id: id,
        pending_interactions: [
          {
            execution_id: 'run',
            run_revision: '1',
            checkpoint_revision: 'new',
            pending_id: 'new'
          }
        ]
      })
    submit = async () =>
      new Response(
        JSON.stringify({
          code: 'CONFLICT',
          message: '等待版本已变化',
          data: null,
          trace_id: 'test'
        }),
        { status: 409 }
      )
    await state.submit({ execution_id: 'run', checkpoint_revision: 'old' })
    expect(state.pendingInput.value).toBeUndefined()
    expect(state.history.value.pending_interactions[0]?.checkpoint_revision).toBe('new')
    expect(state.error.value).toBe('等待版本已变化')
  })
  it('切换身份后迟到提交不能写入新界面', async () => {
    await connect()
    let finish!: (value: Response) => void
    submit = () =>
      new Promise((resolve) => {
        finish = resolve
      })
    state.draft.value = '旧身份'
    const pending = state.submit()
    await flushPromises()
    state.disconnect()
    finish(response({ status: 'accepted', session_id: 's', execution_id: 'old-run' }))
    await pending
    expect(state.client.value).toBeUndefined()
    expect(state.runId.value).toBe('')
    expect(state.history.value.messages).toEqual([])
  })
  it('历史恢复只 GET，不重新提交输入；queued activity 阻止新消息', async () => {
    await connect()
    historyLoad = async (id) =>
      response({
        ...emptyHistory(),
        session_id: id,
        activities: [
          { node_type: 'agent_runtime', status: 'queued', render: { root_run_id: 'queued-run' } }
        ]
      })
    await state.openSession('s')
    expect(state.active.value).toBe(true)
    state.draft.value = '新消息'
    await state.submit()
    expect(posts).toEqual([])
  })
  it('迟到历史不能覆盖新选择', async () => {
    await connect()
    let finish!: (value: Response) => void
    historyLoad = (id) =>
      id === 'old'
        ? new Promise((resolve) => {
            finish = resolve
          })
        : Promise.resolve(response({ ...emptyHistory(), session_id: id }))
    const old = state.openSession('old')
    await flushPromises()
    await state.openSession('new')
    finish(
      response({
        ...emptyHistory(),
        session_id: 'old',
        messages: [
          { message_id: 'private', message_version: 1, role: 'assistant', content: '旧会话' }
        ]
      })
    )
    await old
    expect(state.sessionId.value).toBe('new')
    expect(state.history.value.messages).toEqual([])
  })
})

describe('历史结果发布等待', () => {
  const pending = (status = 503, code = 'TASK_OUTPUT_PUBLICATION_PENDING') =>
    new Response(
      JSON.stringify({
        code,
        message: '结果发布中',
        data: null,
        trace_id: 'pending',
        retryable: true
      }),
      { status }
    )
  const eventCalls = () =>
    vi
      .mocked(fetch)
      .mock.calls.filter(
        ([url]) =>
          new URL(url instanceof Request ? url.url : url).pathname === '/api/v1/chat/events'
      )
  afterEach(() => vi.useRealTimers())
  function expireFirstObservation() {
    const originalFetch = globalThis.fetch
    let expired = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: URL, init: RequestInit = {}) => {
        if (new URL(input).pathname === '/api/v1/chat/events' && !expired) {
          expired = true
          return new Response(
            JSON.stringify({
              code: 'EVENT_CURSOR_EXPIRED',
              message: '游标过期',
              data: null,
              trace_id: 'expired'
            }),
            { status: 409 }
          )
        }
        return originalFetch(input, init)
      })
    )
  }

  it('过期游标的精确发布等待成功后只按新游标恢复观察，保留草稿与原未确认输入', async () => {
    await connect()
    expireFirstObservation()
    let reads = 0
    historyLoad = async (id) => {
      reads++
      if (reads === 2) return pending()
      return response({
        ...emptyHistory(),
        session_id: id,
        event_cursor: reads === 1 ? 'old' : 'fresh',
        messages: [
          {
            message_id: reads === 1 ? 'old-message' : 'fresh-message',
            role: 'assistant',
            content: reads === 1 ? '原消息' : '已发布消息'
          }
        ]
      })
    }
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await state.openSession('s')
    await flushPromises()
    state.draft.value = '下一条未发送草稿'
    const unconfirmed = {
      session_id: 's',
      client_message_id: 'original-unconfirmed',
      content: [{ type: 'text' as const, text: '原输入' }],
      context: { end_user_id: '42' }
    }
    state.pendingInput.value = unconfirmed
    expect(reads).toBe(2)
    expect(eventCalls()).toHaveLength(1)
    expect(state.connection.value).toBe('结果发布中，正在等待')
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(reads).toBe(3)
    expect(eventCalls()).toHaveLength(2)
    expect((eventCalls()[1]![1]!.headers as Headers).get('Last-Event-ID')).toBe('fresh')
    expect(state.history.value.messages.map((item) => item.message_id)).toEqual([
      'old-message',
      'fresh-message'
    ])
    expect(state.draft.value).toBe('下一条未发送草稿')
    expect(state.pendingInput.value).toBe(unconfirmed)
    expect(state.error.value).toBe('')
    expect(posts).toEqual([])
  })
  it.each(['switch', 'disconnect'] as const)(
    '过期游标发布等待在%s后取消，不读取或回填旧会话',
    async (action) => {
      await connect()
      expireFirstObservation()
      let reads = 0
      historyLoad = async (id) =>
        id !== 's'
          ? response({ ...emptyHistory(), session_id: id })
          : ++reads === 1
            ? response({ ...emptyHistory(), session_id: id, event_cursor: 'old' })
            : pending()
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      await state.openSession('s')
      await flushPromises()
      expect(reads).toBe(2)
      if (action === 'switch') await state.openSession('other')
      else state.disconnect()
      state.draft.value = '当前新草稿'
      await vi.advanceTimersByTimeAsync(15000)
      await flushPromises()
      expect(reads).toBe(2)
      expect(
        eventCalls().filter(([url]) => new URL(String(url)).searchParams.get('session_id') === 's')
      ).toHaveLength(1)
      expect(state.history.value.session_id).not.toBe('s')
      expect(state.draft.value).toBe('当前新草稿')
      expect(state.error.value).toBe('')
      expect(posts).toEqual([])
    }
  )
  it('过期游标快照503后只读恢复，不重发且保留草稿', async () => {
    await connect()
    expireFirstObservation()
    let reads = 0
    historyLoad = async (id) => {
      reads++
      if (reads === 1) return response({ ...emptyHistory(), session_id: id, event_cursor: 'old' })
      if (reads === 2) return pending(503, 'OTHER_FAILURE')
      return response({ ...emptyHistory(), session_id: id, event_cursor: 'fresh' })
    }
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await state.openSession('s')
    await flushPromises()
    state.draft.value = '准确保留的草稿'
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(reads).toBe(3)
    expect(eventCalls()).toHaveLength(2)
    expect(state.history.value.event_cursor).toBe('fresh')
    expect(state.draft.value).toBe('准确保留的草稿')
    expect(posts).toEqual([])
  })
  it('过期游标发布等待耗尽后仍只读恢复，不推进游标或清草稿', async () => {
    await connect()
    expireFirstObservation()
    let reads = 0
    historyLoad = async (id) =>
      ++reads === 1
        ? response({ ...emptyHistory(), session_id: id, event_cursor: 'old' })
        : pending()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await state.openSession('s')
    await flushPromises()
    state.draft.value = '耗尽后保留'
    await vi.advanceTimersByTimeAsync(15000)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(reads).toBeGreaterThan(6)
    expect(eventCalls()).toHaveLength(1)
    expect(state.history.value.event_cursor).toBe('old')
    expect(state.draft.value).toBe('耗尽后保留')
    expect(state.connection.value).not.toBe('连接已断开')
    expect(posts).toEqual([])
  })
  it.each([
    [409, 'TASK_OUTPUT_PUBLICATION_PENDING'],
    [401, 'UNAUTHORIZED'],
    [403, 'FORBIDDEN']
  ] as const)('过期游标历史%s/%s不扩大为发布等待', async (status, code) => {
    await connect()
    expireFirstObservation()
    let reads = 0
    historyLoad = async (id) =>
      ++reads === 1
        ? response({ ...emptyHistory(), session_id: id, event_cursor: 'old' })
        : pending(status, code)
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await state.openSession('s')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(15000)
    expect(reads).toBe(2)
    expect(eventCalls()).toHaveLength(1)
    expect(state.error.value).toBe(status === 401 ? '渠道凭证已失效，请重新连接。' : '结果发布中')
    expect(state.history.value.event_cursor).toBe(status === 401 ? null : 'old')
    expect(posts).toEqual([])
  })

  it.each(['open', 'reconnect'] as const)(
    '503 pending 后自动读取成功并恢复观察：%s',
    async (mode) => {
      await connect()
      if (mode === 'reconnect') await state.openSession('s')
      const initialEvents = eventCalls().length
      let reads = 0
      historyLoad = async (id) =>
        ++reads === 1 ? pending() : response({ ...emptyHistory(), session_id: id })
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
      const reading = mode === 'open' ? state.openSession('s') : state.reconnect()
      await flushPromises()
      expect(reads).toBe(1)
      expect(state.loading.value).toBe(true)
      expect(eventCalls()).toHaveLength(initialEvents)
      expect(state.history.value.messages).toEqual([])
      await vi.advanceTimersByTimeAsync(1000)
      await reading
      await flushPromises()
      expect(reads).toBe(2)
      expect(state.loading.value).toBe(false)
      expect(state.error.value).toBe('')
      expect(state.connection.value).toBe('已连接')
      expect(eventCalls()).toHaveLength(initialEvents + 1)
      expect(posts).toEqual([])
      expect(createdSessions).toBe(0)
    }
  )

  it('恢复未知提交期间只重读历史，保留原请求且不重放 POST', async () => {
    await connect()
    submit = async () => {
      throw new TypeError('response lost')
    }
    state.draft.value = '只能提交一次'
    await state.submit()
    const original = state.pendingInput.value
    expect(original).toBeDefined()
    expect(posts).toHaveLength(1)
    let reads = 0
    historyLoad = async (id) =>
      ++reads === 1 ? pending() : response({ ...emptyHistory(), session_id: id })
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const recovery = state.reconnect()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1000)
    await recovery
    expect(reads).toBe(2)
    expect(posts).toHaveLength(1)
    expect(state.pendingInput.value).toBe(original)
    expect(state.connection.value).toBe('已连接')
  })

  it('切换会话取消等待，不继续旧会话 GET 或污染新会话', async () => {
    await connect()
    const reads: string[] = []
    historyLoad = async (id) => {
      reads.push(id)
      return id === 'old' ? pending() : response({ ...emptyHistory(), session_id: id })
    }
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const old = state.openSession('old')
    await flushPromises()
    expect(state.connection.value).toBe('结果发布中，正在等待')
    await state.openSession('new')
    await old
    await vi.advanceTimersByTimeAsync(20_000)
    expect(reads).toEqual(['old', 'new'])
    expect(state.sessionId.value).toBe('new')
    expect(state.history.value.session_id).toBe('new')
    expect(state.history.value.messages).toEqual([])
    expect(state.error.value).toBe('')
    expect(state.loading.value).toBe(false)
    expect(eventCalls()).toHaveLength(1)
    expect(posts).toEqual([])
  })

  it('连续 pending 最多五次 GET 后释放页面，允许手动恢复且无 POST', async () => {
    await connect()
    let reads = 0
    historyLoad = async () => {
      reads++
      return pending()
    }
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const reading = state.openSession('s')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(15_000)
    await reading
    expect(reads).toBe(5)
    expect(state.loading.value).toBe(false)
    expect(state.error.value).toContain('结果发布中')
    expect(state.connection.value).toBe('历史读取未完成，请重新读取会话记录')
    expect(eventCalls()).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(reads).toBe(5)
    historyLoad = async (id) => response({ ...emptyHistory(), session_id: id })
    await state.reconnect()
    await flushPromises()
    expect(state.error.value).toBe('')
    expect(eventCalls()).toHaveLength(1)
    expect(posts).toEqual([])
    expect(createdSessions).toBe(0)
  })

  it.each([
    [503, 'SERVICE_UNAVAILABLE'],
    [409, 'TASK_OUTPUT_PUBLICATION_PENDING']
  ])('普通失败不自动重试：%s %s', async (status, code) => {
    await connect()
    let reads = 0
    historyLoad = async () => {
      reads++
      return pending(status as number, code as string)
    }
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await state.openSession('s')
    await vi.advanceTimersByTimeAsync(20_000)
    expect(reads).toBe(1)
    expect(state.loading.value).toBe(false)
    expect(eventCalls()).toHaveLength(0)
    expect(posts).toEqual([])
  })
})

it.each([false, true])(
  '过期游标恢复保留历史、草稿并阻止旧会话迟到响应：切换=%s',
  async (switchSession) => {
    await connect()
    const originalFetch = globalThis.fetch
    let events = 0
    let resolveHistory!: (value: Response) => void
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: URL, init: RequestInit = {}) => {
        const url = new URL(input)
        if (url.pathname === '/api/v1/chat/events' && url.searchParams.get('session_id') === 's') {
          events++
          if (events === 1)
            return new Response(
              JSON.stringify({
                code: 'EVENT_CURSOR_EXPIRED',
                message: '游标过期',
                trace_id: 't',
                data: null
              }),
              { status: 409 }
            )
        }
        return originalFetch(input, init)
      })
    )
    let historyReads = 0
    historyLoad = async (id) => {
      if (id !== 's') return response({ ...emptyHistory(), session_id: id })
      historyReads++
      if (historyReads === 1)
        return response({
          ...emptyHistory(),
          session_id: id,
          event_cursor: 'old',
          messages: [{ message_id: 'older', role: 'user', content: '早期历史' }]
        })
      return new Promise<Response>((resolve) => {
        resolveHistory = resolve
      })
    }
    await state.openSession('s')
    await flushPromises()
    state.draft.value = '未发送草稿'
    if (switchSession) await state.openSession('other')
    resolveHistory(
      response({
        ...emptyHistory(),
        session_id: 's',
        event_cursor: 'fresh',
        collaboration: { active: true, pending: false, selected_active: true },
        messages: [{ message_id: 'latest', role: 'assistant', content: '恢复消息' }]
      })
    )
    await flushPromises()
    expect(posts).toEqual([])
    if (switchSession) {
      expect(state.sessionId.value).toBe('other')
      expect(state.history.value.messages).toEqual([])
      expect(events).toBe(1)
    } else {
      expect(state.history.value.messages.map((message) => message.message_id)).toEqual([
        'older',
        'latest'
      ])
      expect(state.history.value.collaboration.active).toBe(true)
      expect(state.draft.value).toBe('未发送草稿')
      const fetch = vi.mocked(globalThis.fetch)
      const subscriptions = fetch.mock.calls.filter(
        ([input]) => new URL(String(input)).pathname === '/api/v1/chat/events'
      )
      expect((subscriptions[1]![1]!.headers as Headers).get('Last-Event-ID')).toBe('fresh')
    }
  }
)

it.each([false, true])('运行中可上传下一条草稿资料但不能发送：image=%s', async (image) => {
  await connect()
  state.draft.value = '原运行请求'
  await state.submit()
  await flushPromises()
  expect(state.active.value).toBe(true)
  state.setInputMode('message')
  const originalRequest = posts[0]!
  const originalInput = JSON.parse(originalRequest)
  state.draft.value = '下一条草稿'
  const file = new File(['abc'], image ? 'next.png' : 'next.txt', {
    type: image ? 'image/png' : 'text/plain'
  })
  const uploaded = await state.upload([file])
  expect(uploaded).toHaveLength(1)
  expect(image ? state.images.value : state.attachments.value).toHaveLength(1)
  expect(state.active.value).toBe(true)
  await state.submit()
  expect(posts).toHaveLength(1)
  expect(posts[0]).toBe(originalRequest)
  expect(originalInput.context.attachment_ids).toEqual([])
  expect(originalInput.content).toEqual([{ type: 'text', text: '原运行请求' }])
  publish({
    event: 'execution.completed',
    id: 'finished',
    data: { run_id: 'run', status: 'completed' }
  })
  await flushPromises()
  expect(state.active.value).toBe(false)
  expect(posts).toHaveLength(1)
  submit = async () => response({ status: 'accepted', execution_id: 'next-run', session_id: 's' })
  await state.submit()
  expect(posts).toHaveLength(2)
  const nextInput = JSON.parse(posts[1]!)
  expect(nextInput.client_message_id).not.toBe(originalInput.client_message_id)
  expect(nextInput.context.attachment_ids).toEqual(
    image ? [] : [(uploaded[0]!.value as Attachment).id]
  )
  if (image) expect(nextInput.content).toContainEqual({ type: 'image', media_id: 'media' })
  expect(posts[0]).toBe(originalRequest)
})

it('运行中准备资料仍在任何上传POST前校验完整文档批次', async () => {
  await connect()
  state.draft.value = '当前任务'
  await state.submit()
  expect(state.active.value).toBe(true)
  state.setInputMode('message')
  const result = await state.upload([
    new File(['ok'], 'valid.txt'),
    new File(['bad'], 'invalid.exe')
  ])
  expect(result).toEqual([])
  expect(uploadedForms).toHaveLength(0)
  expect(state.attachments.value).toEqual([])
  expect(posts).toHaveLength(1)
  expect(state.error.value).toContain('invalid.exe')
})
it('运行中手动上传的迟到回执不能进入新会话草稿', async () => {
  await connect()
  state.draft.value = '原任务'
  await state.submit()
  await flushPromises()
  state.setInputMode('message')
  let finish!: (value: Response) => void
  uploadAttachment = () =>
    new Promise((resolve) => {
      finish = resolve
    })
  const pending = state.upload([new File(['ok'], 'old.txt')])
  await flushPromises()
  await state.openSession('new-session')
  state.draft.value = '新会话草稿'
  finish(
    response({
      id: 'late-file',
      name: 'old.txt',
      size: 2,
      input_path: 'inputs/old.txt',
      sha256: 'h',
      expires_at: ''
    })
  )
  await pending
  expect(state.attachments.value).toEqual([])
  expect(state.draft.value).toBe('新会话草稿')
  expect(posts).toHaveLength(1)
})

it('安全预览独立于正式历史，重连与切会话清除且旧会话迟到事件无效', async () => {
  await connect()
  state.draft.value = '开始任务'
  await state.submit()
  await flushPromises()
  const preview = {
    session_id: 's',
    run_id: 'run',
    root_input_id: 'input',
    input_id: 'input',
    preview_message_id: 'preview-run',
    attempt_id: 'attempt',
    ordinal: 1,
    preview_sequence: 1,
    role: 'assistant',
    content: '安全正文候选',
    state: 'streaming',
    authoritative: false
  }
  publish({
    id: 'preview-first',
    event: 'message.preview',
    data: { ...preview, session_sequence: 50 }
  })
  await flushPromises()
  expect(state.previews.value.run?.content).toBe('安全正文候选')
  expect(state.history.value.messages.some((message) => message.content === '安全正文候选')).toBe(
    false
  )
  await state.reconnect()
  await flushPromises()
  expect(state.previews.value).toEqual({})
  publish({
    id: 'old-preview-new-session-sequence',
    event: 'message.preview',
    data: { ...preview, preview_sequence: 1, session_sequence: 60 }
  })
  await flushPromises()
  expect(state.previews.value).toEqual({})
  publish({
    id: 'new-attempt',
    event: 'message.preview',
    data: {
      ...preview,
      attempt_id: 'new-attempt',
      ordinal: 2,
      session_sequence: 61,
      content: '新执行候选'
    }
  })
  await flushPromises()
  expect(state.previews.value.run?.content).toBe('新执行候选')
  await state.reconnect()
  await flushPromises()
  publish({
    id: 'retired-attempt-late',
    event: 'message.preview',
    data: { ...preview, preview_sequence: 99, session_sequence: 70 }
  })
  await flushPromises()
  expect(state.previews.value).toEqual({})
  state.newSession()
  await state.openSession('other')
  await flushPromises()
  publish({
    id: 'preview-late',
    event: 'message.preview',
    data: { ...preview, preview_sequence: 2, session_sequence: 51 }
  })
  await flushPromises()
  expect(state.previews.value).toEqual({})
})
