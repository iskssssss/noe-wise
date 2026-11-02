import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHttpClient, HttpClientError } from '@skillforge/shared/utils/http'
import { createWorkflowDraftApi } from './workflowDrafts'
import { api } from './index'

function client(onError = vi.fn()) {
  return {
    onError,
    http: createHttpClient({
      base: '/api/v1',
      accessTokenKey: 'test.access',
      refreshTokenKey: 'test.refresh',
      authHeader: (token) => ({ Authorization: `Bearer ${token}` }),
      refreshPath: '/auth/refresh',
      isAuthPath: (path) => path.startsWith('/auth/'),
      onError
    })
  }
}

function apiResponse<T>(data: T, status = 200): Response {
  return new Response(
    JSON.stringify({
      code: 'OK',
      message: '成功',
      data,
      trace_id: 'test-trace'
    }),
    { status, headers: { 'Content-Type': 'application/json' } }
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

it('机构定义预览发送当前编辑内容与原草稿修订', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValue(apiResponse({ validation: { valid: true, issues: [] }, impact: null }))
  vi.stubGlobal('fetch', fetchMock)
  const definition = {
    system_prompt: '当前输入',
    role_description: '客服',
    model_config_id: null,
    execution_config: {
      max_business_model_calls: null,
      max_tool_calls: null,
      max_knowledge_calls: null,
      max_total_tokens: null,
      max_wall_time_seconds: null
    },
    capabilities: [],
    resource_slots: [],
    delegation_slots: []
  }
  await api.agentDefinitions.previewContent('9223372036854775807', 7, definition)
  expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/tenant/agent-definitions/preview-content')
  expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({
    definition_id: '9223372036854775807',
    expected_revision: 7,
    definition
  })
})

describe('登录请求认证边界', () => {
  it('知识文档上传遇到 401 不自动重发文件', async () => {
    localStorage.setItem('skillforge.access_token', 'expired-token')
    localStorage.setItem('skillforge.refresh_token', 'refresh-token')
    const fetchMock = vi.fn().mockResolvedValue(apiResponse(null, 401))
    vi.stubGlobal('fetch', fetchMock)
    const form = new FormData()
    form.append('file', new File(['content'], 'sample.txt'))

    await expect(
      api.knowledge.uploadFile('123', form, 'd4999064-006f-4af4-af0f-04344f345ca5')
    ).rejects.toMatchObject({ status: 401 })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0]![0]).toContain('/api/v1/knowledge/bases/documents/upload?')
  })
  it('浏览器保留旧令牌时登录不携带 Authorization', async () => {
    localStorage.setItem('skillforge.access_token', 'expired-access-token')
    localStorage.setItem('skillforge.refresh_token', 'old-refresh-token')
    const fetchMock = vi.fn().mockResolvedValue(
      apiResponse({
        access_token: 'new-access-token',
        token_type: 'Bearer',
        expires_in: 3600
      })
    )
    vi.stubGlobal('fetch', fetchMock)

    await api.auth.login({
      tenant_slug: 'institution',
      username: 'admin',
      password: 'example-password'
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/auth/login')
    const headers = new Headers(fetchMock.mock.calls[0]![1].headers)
    expect(headers.has('Authorization')).toBe(false)
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  it('登录被拒绝时不借用旧刷新令牌重试或清除其他登录态', async () => {
    localStorage.setItem('skillforge.access_token', 'old-access-token')
    localStorage.setItem('skillforge.refresh_token', 'old-refresh-token')
    const fetchMock = vi.fn().mockResolvedValue(apiResponse(null, 401))
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      api.auth.login({ tenant_slug: 'institution', username: 'admin', password: 'wrong-password' })
    ).rejects.toMatchObject({ status: 401 })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(new Headers(fetchMock.mock.calls[0]![1].headers).has('Authorization')).toBe(false)
    expect(localStorage.getItem('skillforge.access_token')).toBe('old-access-token')
    expect(localStorage.getItem('skillforge.refresh_token')).toBe('old-refresh-token')
  })

  it('个人资料和普通业务请求继续携带当前令牌', async () => {
    localStorage.setItem('skillforge.access_token', 'current-access-token')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        apiResponse({
          sub: '1',
          tenant_id: '2',
          roles: ['tenant_admin'],
          permissions: [],
          authorization_version: 1,
          mfa_state: 'full',
          session_id: '71',
          username: 'admin'
        })
      )
      .mockResolvedValueOnce(apiResponse({ total: 0, items: [] }))
    vi.stubGlobal('fetch', fetchMock)

    await api.auth.me()
    await api.channels.list()

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/auth/me',
      '/api/v1/channels'
    ])
    for (const [, init] of fetchMock.mock.calls) {
      expect(new Headers(init.headers).get('Authorization')).toBe('Bearer current-access-token')
    }
  })
})

describe('流式响应', () => {
  it('返回尚未缓冲的原响应并保留认证、游标头和取消信号', async () => {
    const { http } = client()
    http.saveTokens({ access_token: 'stream-access' })
    const abort = new AbortController()
    const streamResponse = new Response('event: connected\ndata: {}\n\n', {
      headers: { 'Content-Type': 'text/event-stream' }
    })
    const json = vi.spyOn(streamResponse, 'json')
    const fetchMock = vi.fn().mockResolvedValue(streamResponse)
    vi.stubGlobal('fetch', fetchMock)

    const result = await http.get<Response>('/open-platform/groups/stream', {
      responseType: 'stream',
      signal: abort.signal,
      headers: { Accept: 'text/event-stream', 'Last-Event-ID': 'original/cursor' }
    })

    expect(result).toBe(streamResponse)
    expect(result.bodyUsed).toBe(false)
    expect(json).not.toHaveBeenCalled()
    const [, init] = fetchMock.mock.calls[0]!
    expect(init.signal).toBe(abort.signal)
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBe('Bearer stream-access')
    expect(headers.get('Last-Event-ID')).toBe('original/cursor')
    expect(headers.get('Accept')).toBe('text/event-stream')
    expect(await result.text()).toContain('event: connected')
  })

  it('流连接取消静默保留 AbortError，错误响应继续采用统一错误边界', async () => {
    const { http, onError } = client()
    const aborted = new DOMException('cancelled', 'AbortError')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(aborted))
    await expect(http.get('/stream', { responseType: 'stream' })).rejects.toBe(aborted)
    expect(onError).not.toHaveBeenCalled()

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(apiResponse(null, 403)))
    await expect(http.get('/stream', { responseType: 'stream' })).rejects.toMatchObject({
      status: 403
    })
    await vi.waitFor(() => expect(onError).toHaveBeenCalledTimes(1))
  })
})

describe('DELETE 请求体', () => {
  it('知识版本准备请求显式提交完整调用策略', async () => {
    const fetchMock = vi.fn().mockResolvedValue(apiResponse({ revision: 3, status: 'ready' }))
    vi.stubGlobal('fetch', fetchMock)
    const policy = {
      model_invocable: true,
      user_invocable: false,
      allowed_operators: ['VECTOR', 'KEYWORD'],
      freshness_policy: 'PINNED',
      citation_required: true
    }
    await api.knowledge.markRevisionReady('kb-1', 3, ['source-v1'], policy)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/knowledge/bases/revisions/ready',
      expect.objectContaining({ method: 'POST' })
    )
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({
      kb_id: 'kb-1',
      revision: '3',
      payload: { source_versions: ['source-v1'], policy }
    })
  })

  it('任务接口通过真实客户端只添加一次 API 前缀', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(apiResponse([])))
    vi.stubGlobal('fetch', fetchMock)
    await api.conversationTasks.list('session-a')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/conversation-tasks/list?session_id=session-a&query=&include_closed=false&offset=0&limit=30',
      expect.objectContaining({ method: 'GET' })
    )
  })

  it('将资源标识作为 JSON 发送并保留请求头', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await client().http.del(
      '/agents/delete',
      { agent_id: 'agent-7' },
      {
        headers: { 'If-Match': 'revision-3' }
      }
    )

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agents/delete',
      expect.objectContaining({
        method: 'DELETE',
        body: JSON.stringify({ agent_id: 'agent-7' })
      })
    )
    const headers = new Headers(fetchMock.mock.calls[0]?.[1].headers)
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(headers.get('If-Match')).toBe('revision-3')
  })
})

describe('团队写入路径', () => {
  it('将团队身份置于固定命令路径的请求体，保留修订负载', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(apiResponse({}))
      .mockResolvedValueOnce(apiResponse({}))
    vi.stubGlobal('fetch', fetchMock)
    const draft = {
      revision: 7,
      name: '团队',
      description: '',
      status: 'draft' as const,
      members: [{ agent_id: 'agent-1' }],
      primary_agent_id: 'agent-1'
    }

    await api.teams.update('team-1', draft)
    await api.teams.publish('team-1')

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/teams/update',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ id: 'team-1', payload: draft })
      })
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/teams/publish',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ id: 'team-1' })
      })
    )
  })
})

it('工作流创建遇到 401 不自动刷新或重发原写请求', async () => {
  localStorage.setItem('test.access', 'access-token')
  localStorage.setItem('test.refresh', 'refresh-token')
  const fetchMock = vi.fn().mockResolvedValue(apiResponse(null, 401))
  vi.stubGlobal('fetch', fetchMock)
  await expect(
    createWorkflowDraftApi(client().http).create('flow', { entry: 'start' })
  ).rejects.toMatchObject({ status: 401 })
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/builder/workflows')
})

it('团队组合发布遇到 401 不刷新令牌或重发原写请求', async () => {
  localStorage.setItem('skillforge.access_token', 'expired-access-token')
  localStorage.setItem('skillforge.refresh_token', 'refresh-token')
  const fetchMock = vi.fn().mockResolvedValue(apiResponse({}, 401))
  vi.stubGlobal('fetch', fetchMock)
  await expect(
    api.teams.saveAndPublish('request-1', null, {
      revision: 0,
      name: '团队',
      description: '',
      status: 'draft',
      members: [{ agent_id: 'agent-1' }],
      primary_agent_id: 'agent-1'
    })
  ).rejects.toBeInstanceOf(HttpClientError)
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/teams/save-and-publish')
})

describe('草稿 API 请求契约', () => {
  it('新建草稿使用后端约定的 workflow_id 字段', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const fetchMock = vi.fn().mockResolvedValue(
      apiResponse({
        workflow_id: 'browser-e2e-flow',
        status: 'draft',
        revision: 1,
        definition: { entry: 'start' },
        problems: [
          {
            code: 'WF_GRAPH_END',
            severity: 'warning',
            object_ref: 'workflow',
            field_path: '/nodes',
            message: '缺少结束节点'
          }
        ]
      })
    )
    vi.stubGlobal('fetch', fetchMock)

    const created = await createWorkflowDraftApi(client().http).create('browser-e2e-flow', {
      entry: 'start'
    })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/builder/workflows',
      expect.objectContaining({
        method: 'POST',
        body: '{"workflow_id":"browser-e2e-flow","definition":{"entry":"start"}}'
      })
    )
    expect(created).toMatchObject({ workflowId: 'browser-e2e-flow', revision: 1 })
    expect(created.problems[0]).toMatchObject({ objectRef: 'workflow', fieldPath: '/nodes' })
  })

  it('转换列表、快照、差异和发布响应中的 snake_case 字段', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        apiResponse({
          total: 1,
          items: [
            {
              workflow_id: 'flow',
              status: 'draft',
              revision: 2,
              active_definition_version_id: '7',
              name: '流程',
              description: '描述'
            }
          ]
        })
      )
      .mockResolvedValueOnce(
        apiResponse({
          workflow_id: 'flow',
          draft_revision: 2,
          layout: { nodes: [] },
          viewport: { zoom: 1 }
        })
      )
      .mockResolvedValueOnce(
        apiResponse({
          draft_revision: 2,
          base_definition_version: 1,
          changes: [
            { operation: 'replace', path: '/meta/name', before_value: '旧', after_value: '新' }
          ]
        })
      )
      .mockResolvedValueOnce(
        apiResponse({
          workflow_id: 'flow',
          draft_revision: 2,
          definition_version_id: '9',
          definition_version: 2
        })
      )
    vi.stubGlobal('fetch', fetchMock)

    const drafts = createWorkflowDraftApi(client().http)
    await expect(drafts.list()).resolves.toMatchObject({
      items: [{ workflowId: 'flow', activeDefinitionVersionId: '7' }]
    })
    await expect(drafts.snapshot.get('flow')).resolves.toMatchObject({
      workflowId: 'flow',
      draftRevision: 2
    })
    await expect(drafts.diff('flow')).resolves.toMatchObject({
      draftRevision: 2,
      baseDefinitionVersion: 1,
      changes: [{ beforeValue: '旧', afterValue: '新' }]
    })
    await expect(drafts.publish('flow', 2)).resolves.toMatchObject({
      workflowId: 'flow',
      draftRevision: 2,
      definitionVersionId: '9',
      definitionVersion: 2
    })
  })

  it('安装提交精确 bindings 并映射实例安装修订', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        apiResponse({
          total: 1,
          items: [
            {
              id: '9223372036854775807',
              instance_id: 'child-1',
              workflow_id: 'child',
              definition_version_id: '601',
              installation_revision: 2,
              capability_version_id: '701',
              status: 'published',
              created_at: '2026-09-27T00:00:00Z'
            }
          ]
        })
      )
      .mockResolvedValueOnce(
        apiResponse({
          status: 'installed',
          workflow_id: 'flow',
          tenant_id: '9',
          instance_id: 'root-1',
          installation_revision: 3
        })
      )
    vi.stubGlobal('fetch', fetchMock)
    const drafts = createWorkflowDraftApi(client().http)
    await expect(drafts.instances()).resolves.toMatchObject({
      items: [
        {
          id: '9223372036854775807',
          instanceId: 'child-1',
          definitionVersionId: '601',
          installationRevision: '2'
        }
      ]
    })
    const bindings = {
      capabilities: [
        {
          slot: 'lookup',
          capability: { type: 'TOOL' as const, capabilityId: '21', versionId: '201' },
          tool_deployment_id: 'prod',
          tool_deployment_revision: 4
        }
      ],
      workflows: [
        {
          workflow: 'child',
          instance_id: '9223372036854775807',
          definition_version_id: '601',
          installation_revision: '2'
        }
      ],
      agents: [{ slot: 'specialist', agent_id: 'a-1' }]
    }
    await expect(drafts.install('flow', bindings)).resolves.toMatchObject({
      workflowId: 'flow',
      instanceId: 'root-1',
      installationRevision: '3'
    })
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/builder/workflows/install',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          workflow_id: 'flow',
          payload: {
            config: {},
            custom_prompt: '',
            bindings: {
              ...bindings,
              capabilities: [
                {
                  slot: 'lookup',
                  tool_deployment_id: 'prod',
                  tool_deployment_revision: 4,
                  capability: { type: 'TOOL', capability_id: '21', version_id: '201' }
                }
              ]
            }
          }
        })
      })
    )
  })

  it('保留 CAS 请求头并转换读取、保存、fork 和校验结果', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const wireDraft = (revision: number) => ({
      workflow_id: 'flow',
      status: 'draft',
      revision,
      definition: { entry: 'start' },
      problems: []
    })
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(apiResponse(wireDraft(2)))
      .mockResolvedValueOnce(
        apiResponse({
          workflow_id: 'flow',
          draft_revision: 2,
          layout: { nodes: [] },
          viewport: { zoom: 1 }
        })
      )
      .mockResolvedValueOnce(apiResponse(wireDraft(3)))
      .mockResolvedValueOnce(apiResponse(wireDraft(4)))
      .mockResolvedValueOnce(
        apiResponse({
          revision: 4,
          problems: [
            {
              code: 'WF_GRAPH_END',
              severity: 'warning',
              object_ref: 'workflow',
              field_path: '/nodes',
              message: '缺少结束节点'
            }
          ]
        })
      )
    vi.stubGlobal('fetch', fetchMock)
    const drafts = createWorkflowDraftApi(client().http)

    await expect(drafts.get('flow')).resolves.toMatchObject({ workflowId: 'flow', revision: 2 })
    await expect(
      drafts.snapshot.save('flow', 2, { nodes: [] }, { zoom: 1 })
    ).resolves.toMatchObject({ draftRevision: 2 })
    await expect(drafts.save('flow', 2, { entry: 'start' })).resolves.toMatchObject({ revision: 3 })
    await expect(drafts.fork('flow', 3)).resolves.toMatchObject({ revision: 4 })
    await expect(drafts.validate('flow')).resolves.toMatchObject({
      revision: 4,
      problems: [{ objectRef: 'workflow', fieldPath: '/nodes' }]
    })

    const [, snapshotSave] = fetchMock.mock.calls[1] as [string, RequestInit]
    const [, save] = fetchMock.mock.calls[2] as [string, RequestInit]
    const [, fork] = fetchMock.mock.calls[3] as [string, RequestInit]
    expect((snapshotSave.headers as Headers).get('If-Match')).toBe('2')
    expect((save.headers as Headers).get('If-Match')).toBe('2')
    expect((fork.headers as Headers).get('If-Match')).toBe('3')
  })

  it('保留 If-Match，同时追加认证头和 JSON 请求体', async () => {
    localStorage.setItem('test.access', 'access-token')
    const fetchMock = vi.fn().mockResolvedValue(apiResponse({ revision: 2 }))
    vi.stubGlobal('fetch', fetchMock)
    const { http } = client()

    await http.put(
      '/builder/workflows/demo/draft',
      { definition: { entry: 'start' } },
      {
        headers: { 'If-Match': '1' }
      }
    )

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.headers).toEqual(expect.any(Headers))
    expect((init.headers as Headers).get('If-Match')).toBe('1')
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer access-token')
    expect((init.headers as Headers).get('Content-Type')).toBe('application/json')
    expect(init.body).toBe('{"definition":{"entry":"start"}}')
  })

  it('silent 探测失败不触发全局错误提示，并向调用方提供安全错误', async () => {
    const { http, onError } = client()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: 'NOT_FOUND',
            message: '不存在',
            data: null,
            trace_id: 'trace-not-found'
          }),
          { status: 404 }
        )
      )
    )

    await expect(
      http.get('/builder/workflows/legacy/draft', { silent: true })
    ).rejects.toMatchObject({
      status: 404,
      userMessage: '请求的资源不存在或已被移除',
      technicalDetails: '不存在'
    } satisfies Partial<HttpClientError>)
    expect(onError).not.toHaveBeenCalled()
  })

  it('将后端错误放入技术详情，onError 只接收安全主文案', async () => {
    const { http, onError } = client()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: 'SERVICE_UNAVAILABLE',
            message: 'database connection refused',
            data: null,
            trace_id: 'trace-safe-7'
          }),
          { status: 503 }
        )
      )
    )

    await expect(http.get('/service-health')).rejects.toMatchObject({
      status: 503,
      userMessage: '暂时无法读取最新内容，请稍后重新加载',
      technicalDetails: 'database connection refused',
      traceId: 'trace-safe-7'
    } satisfies Partial<HttpClientError>)
    await vi.waitFor(() =>
      expect(onError).toHaveBeenCalledWith(
        503,
        '暂时无法读取最新内容，请稍后重新加载',
        '/service-health',
        expect.objectContaining({
          technicalDetails: 'database connection refused',
          traceId: 'trace-safe-7'
        })
      )
    )
  })

  it('将网络异常包装为安全错误，避免旧 catch 展示浏览器原始错误', async () => {
    const { http, onError } = client()
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(http.get('/service-health')).rejects.toMatchObject({
      status: 0,
      userMessage: '连接中断，未能读取最新内容。请检查网络后重新加载',
      technicalDetails: 'Failed to fetch'
    } satisfies Partial<HttpClientError>)
    await vi.waitFor(() =>
      expect(onError).toHaveBeenCalledWith(
        0,
        '连接中断，未能读取最新内容。请检查网络后重新加载',
        '/service-health',
        expect.objectContaining({
          technicalDetails: 'Failed to fetch'
        })
      )
    )
  })
})

describe('Chat 请求序列化契约', () => {
  it('纯 Plan 命令在提交回执中完成，不建立 SSE 订阅', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const fetchMock = vi.fn().mockResolvedValue(
      apiResponse({
        status: 'collaboration_updated',
        session_id: 'session-a',
        execution_id: null,
        collaboration: { active: true, pending: false, selected_active: true }
      })
    )
    vi.stubGlobal('fetch', fetchMock)
    const result = await api.chat.submit({ message: '/plan', session_id: 'session-a' })

    expect(result).toMatchObject({ status: 'collaboration_updated', session_id: 'session-a' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/chat',
      expect.objectContaining({ method: 'POST' })
    )
  })

  it('读取 collaboration 并以幂等命令切换 Plan 模式', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const state = { active: false, pending: true, selected_active: true }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(apiResponse(state))
      .mockResolvedValueOnce(apiResponse(state))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.chat.collaboration('session / 1')).resolves.toEqual(state)
    await expect(api.chat.setPlanMode('session / 1', true, 'command-1')).resolves.toEqual(state)

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      '/api/v1/chat/runs/collaboration?session_id=session+%2F+1'
    )
    expect(fetchMock.mock.calls[1]).toEqual([
      '/api/v1/chat/runs/plan-mode',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ session_id: 'session / 1', command_id: 'command-1', active: true })
      })
    ])
  })

  it('未选择终端用户时省略 end_user_id', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const fetchMock = vi.fn().mockResolvedValue(apiResponse([]))
    vi.stubGlobal('fetch', fetchMock)
    await api.chat.sessions()
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/chat/sessions?')
  })

  it('使用字符串 end_user_id 加载可选用户及其会话', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        apiResponse([{ id: '9007199254740993', display_name: '测试用户', status: 'active' }])
      )
      .mockResolvedValueOnce(apiResponse([]))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.chat.endUsers()).resolves.toEqual([
      { id: '9007199254740993', display_name: '测试用户', status: 'active' }
    ])
    await expect(
      api.chat.sessions({
        endUserId: '9007199254740993',
        agentId: 'agent-7'
      })
    ).resolves.toEqual([])

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/chat/end-users')
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      '/api/v1/chat/sessions?end_user_id=9007199254740993&agentId=agent-7'
    )
  })

  it('历史团队筛选、详情绑定和关闭沿用既有会话接口', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const detail = {
      session: { session_id: 'session / 1', team_id: 'team-B' },
      end_user_id: '9223372036854775806',
      target_agent_id: null,
      closed: true
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(apiResponse([]))
      .mockResolvedValueOnce(apiResponse(detail))
      .mockResolvedValueOnce(apiResponse({ session_id: 'session / 1', status: 'closed' }))
    vi.stubGlobal('fetch', fetchMock)
    await api.chat.sessions({ endUserId: '9223372036854775806', teamId: 'team-B' })
    await expect(api.chat.sessionDetail('session / 1')).resolves.toEqual(detail)
    await api.chat.closeSession('session / 1')
    expect(fetchMock.mock.calls[0]![0]).toBe(
      '/api/v1/chat/sessions?end_user_id=9223372036854775806&team_id=team-B'
    )
    expect(fetchMock.mock.calls[1]![0]).toBe(
      '/api/v1/chat/sessions/detail?sessionId=session%20%2F%201'
    )
    expect(fetchMock.mock.calls[2]).toEqual([
      '/api/v1/chat/sessions/close',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ session_id: 'session / 1' })
      })
    ])
  })

  it('创建会话保持超过安全整数范围的终端用户 ID 字符串', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const fetchMock = vi.fn().mockResolvedValue(apiResponse({ session_id: 'session-large-user' }))
    vi.stubGlobal('fetch', fetchMock)

    await api.chat.createSession('9007199254740993', { agent_id: 'agent-7' })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/chat/sessions',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ end_user_id: '9007199254740993', agent_id: 'agent-7' })
      })
    )
  })

  it('按公开 Chat DTO 发送明确恢复身份和业务 context，一次提交只执行 POST', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const receipt = {
      status: 'queued',
      session_id: 'sess-runtime',
      execution_id: 'run-7',
      input_id: 'input-7'
    }
    const fetchMock = vi.fn().mockResolvedValueOnce(apiResponse(receipt))
    vi.stubGlobal('fetch', fetchMock)
    const hitl = {
      execution_id: 'run-7',
      run_revision: '4',
      workflow_input: {
        wait: { action_id: 'workflow-action-7', generation: '1' },
        execution_ref: 'exec-7',
        suspend_ref: 'workflow-wait-7',
        execution_revision: '9',
        action_key: 'confirm',
        response: {
          schema_id: 'application/json',
          canonical_json: '{"action":"confirm","approved":true}'
        }
      }
    }
    const request = {
      message: 'hello',
      session_id: 'sess-runtime',
      agent_id: 'agent-7',
      workflow_id: 'workflow-ignored',
      scope_id: 'order-7',
      scope_type: 'order',
      locale: 'zh-CN',
      client_message_id: 'client-7',
      hitl_response: hitl,
      context: {
        end_user_id: '9007199254740993',
        task_id: 'task-7',
        attachment_ids: ['attachment-7']
      }
    }

    await expect(api.chat.submit(request)).resolves.toEqual(receipt)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const { message, ...fields } = request
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/chat',
      expect.objectContaining({
        method: 'POST',
        body: expect.any(String)
      })
    )
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({
      ...fields,
      content: [{ type: 'text', text: message }]
    })
  })

  it('未提供可选字段时不伪造 null，submit 补充 client_message_id 后结束', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const fetchMock = vi.fn().mockResolvedValueOnce(
      apiResponse({
        status: 'queued',
        session_id: 'sess-runtime',
        execution_id: 'run-7',
        input_id: 'input-7'
      })
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'generated-client-id' })

    await api.chat.submit({ message: 'hello', session_id: 'sess-runtime', agent_id: 'agent-7' })

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(init.body))).toEqual({
      content: [{ type: 'text', text: 'hello' }],
      session_id: 'sess-runtime',
      agent_id: 'agent-7',
      client_message_id: 'generated-client-id'
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('会话观察只执行 GET，携带公开游标并由调用方释放连接', async () => {
    localStorage.setItem('skillforge.access_token', 'access-token')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          'id: event-8\nevent: run.started\ndata: {"session_id":"sess-runtime","run_id":"run-7"}\n\n' +
            'id: event-9\nevent: execution.completed\ndata: {"session_id":"sess-runtime","run_id":"run-7","status":"completed"}\n\n',
          { status: 200, headers: { 'Content-Type': 'text/event-stream' } }
        )
      )
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()
    const listener = vi.fn((event) => {
      if (event.event === 'execution.completed') controller.abort()
    })
    const cursor = vi.fn()

    await api.chat.observeSession(
      { sessionId: 'sess-runtime', afterEventId: 'event-7', signal: controller.signal },
      {
        onEvent: listener,
        onCursor: cursor
      }
    )

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/chat/events?session_id=sess-runtime',
      expect.objectContaining({
        method: 'GET',
        signal: controller.signal
      })
    )
    const headers = new Headers(fetchMock.mock.calls[0]![1].headers)
    expect(headers.get('Last-Event-ID')).toBe('event-7')
    expect(listener.mock.calls.map((call) => call[0].event)).toEqual([
      'run.started',
      'execution.completed'
    ])
    expect(cursor).toHaveBeenLastCalledWith('event-9')
    expect(controller.signal.aborted).toBe(true)
  })
})
