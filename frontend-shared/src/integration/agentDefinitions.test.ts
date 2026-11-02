import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createAgentDefinitionsApi,
  type AgentDefinitionCreateInput,
  type AgentDefinitionsBase,
  type AgentDefinitionsHttp
} from './agentDefinitions'
import type { RequestOptions } from '../utils/http'

interface Request {
  method: 'GET' | 'POST' | 'PUT'
  path: string
  body?: unknown
  options?: RequestOptions
}

function transport(response: unknown = {}) {
  const requests: Request[] = []
  const http: AgentDefinitionsHttp = {
    async get<T>(path: string, options?: RequestOptions) {
      requests.push({ method: 'GET', path, options })
      return response as T
    },
    async post<T>(path: string, body?: unknown, options?: RequestOptions) {
      requests.push({ method: 'POST', path, body, options })
      return response as T
    },
    async put<T>(path: string, body?: unknown, options?: RequestOptions) {
      requests.push({ method: 'PUT', path, body, options })
      return response as T
    }
  }
  return { http, requests }
}

const definitionId = '9007199254740993123'
const bases: AgentDefinitionsBase[] = ['/platform/agent-definitions', '/tenant/agent-definitions']

describe('共享定义客户端的正式宿主协议', () => {
  afterEach(() => vi.unstubAllGlobals())

  it.each(bases)('%s读取精确身份、筛选和草稿修订并透传取消信号', async (base) => {
    const fixture = transport()
    const api = createAgentDefinitionsApi(fixture.http, base)
    const signal = new AbortController().signal
    const options = { signal, silent: true }
    await api.list(
      {
        page: 2,
        limit: 25,
        name: '门店 & 总部',
        category: '医疗/服务',
        status: 'published',
        distribution_policy: 'AUTHORIZED_DEPLOY'
      },
      options
    )
    await api.detail(definitionId, options)
    await api.history(definitionId, options)
    await api.impact(definitionId, 17, options)
    const query = new URL(fixture.requests[0]!.path, 'https://fixture.local').searchParams
    expect(Object.fromEntries(query)).toEqual({
      page: '2',
      limit: '25',
      name: '门店 & 总部',
      category: '医疗/服务',
      status: 'published',
      distribution_policy: 'AUTHORIZED_DEPLOY'
    })
    expect(fixture.requests.slice(1).map((request) => request.path)).toEqual([
      `${base}/detail?definition_id=${definitionId}`,
      `${base}/history?definition_id=${definitionId}`,
      `${base}/impact?definition_id=${definitionId}&draft_revision=17`
    ])
    expect(fixture.requests.every((request) => request.options === options)).toBe(true)
    expect(fixture.requests.every((request) => request.method === 'GET')).toBe(true)
  })

  it.each(bases)('%s写入只发送共享DTO内容并保留调用者原幂等键', async (base) => {
    const fixture = transport()
    const api = createAgentDefinitionsApi(fixture.http, base)
    const payload: AgentDefinitionCreateInput = {
      name: '总部共享定义',
      distribution_policy: 'AUTHORIZED_DEPLOY',
      definition: {
        system_prompt: '遵守门店授权',
        role_description: '',
        model_config_id: definitionId,
        execution_config: {
          max_business_model_calls: null,
          max_tool_calls: 5,
          max_knowledge_calls: null,
          max_total_tokens: null,
          max_wall_time_seconds: null
        },
        capabilities: [],
        resource_slots: [],
        delegation_slots: []
      }
    }
    await api.create(payload, 'create-original')
    await api.update(definitionId, 17, payload, 'edit-original')
    await api.validate(definitionId, 17, 'validate-original')
    await api.publish(definitionId, 17, 'publish-original')
    await api.deprecate(definitionId, 18, 'deprecate-original')
    await api.restore(definitionId, 19, '1.2.0', 'restore-original')
    expect(fixture.requests.map(({ method, path, body }) => ({ method, path, body }))).toEqual([
      { method: 'POST', path: base, body: payload },
      {
        method: 'PUT',
        path: `${base}/update`,
        body: { definition_id: definitionId, expected_revision: 17, payload }
      },
      {
        method: 'POST',
        path: `${base}/validate`,
        body: { definition_id: definitionId, expected_revision: 17 }
      },
      {
        method: 'POST',
        path: `${base}/publish`,
        body: { definition_id: definitionId, expected_revision: 17 }
      },
      {
        method: 'POST',
        path: `${base}/deprecate`,
        body: { definition_id: definitionId, expected_revision: 18 }
      },
      {
        method: 'POST',
        path: `${base}/restore`,
        body: { definition_id: definitionId, expected_revision: 19, published_revision: '1.2.0' }
      }
    ])
    expect(
      fixture.requests.map((request) =>
        new Headers(request.options?.headers).get('Idempotency-Key')
      )
    ).toEqual([
      'create-original',
      'edit-original',
      'validate-original',
      'publish-original',
      'deprecate-original',
      'restore-original'
    ])
  })

  it('新命令可生成键，显式核验沿用原键且传输失败不自动再发', async () => {
    const randomUUID = vi.fn().mockReturnValue('generated-original')
    vi.stubGlobal('crypto', { randomUUID })
    const fixture = transport()
    const api = createAgentDefinitionsApi(fixture.http)
    await api.publish(definitionId, 3)
    await api.publish(definitionId, 3, 'generated-original')
    expect(randomUUID).toHaveBeenCalledTimes(1)
    expect(
      fixture.requests.map((request) =>
        new Headers(request.options?.headers).get('Idempotency-Key')
      )
    ).toEqual(['generated-original', 'generated-original'])
    const failure = new Error('HTTP_OUTCOME_UNKNOWN')
    const post = vi.fn().mockRejectedValue(failure)
    const rejected = createAgentDefinitionsApi({ ...fixture.http, post })
    await expect(rejected.publish(definitionId, 3, 'unknown-original')).rejects.toBe(failure)
    expect(post).toHaveBeenCalledTimes(1)
    expect(post.mock.calls[0]?.[2]).toEqual({ headers: { 'Idempotency-Key': 'unknown-original' } })
  })

  it('返回原公开响应且不静默丢弃服务器应拒绝的显式分页值', async () => {
    const response = {
      valid: false,
      issues: [
        { path: 'definition.model_config_id', code: 'MODEL_REQUIRED', message: '请选择模型' }
      ]
    }
    const fixture = transport(response)
    const api = createAgentDefinitionsApi(fixture.http)
    expect(await api.validate(definitionId, 1, 'validation')).toBe(response)
    await api.list({ page: 0, limit: 0 })
    expect(fixture.requests[1]?.path).toBe('/platform/agent-definitions?page=0&limit=0')
  })
})
