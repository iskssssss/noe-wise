import { describe, expect, it, vi } from 'vitest'

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  del: vi.fn(),
  request: vi.fn()
}))
vi.mock('@skillforge/shared/utils/http', () => ({ createHttpClient: () => http }))
vi.mock('@skillforge/shared/feedback', () => ({ message: { error: vi.fn() } }))
vi.mock('../stores/auth', () => ({ useAuthStore: vi.fn() }))
vi.mock('../router', () => ({ default: {} }))
import { api } from './index'

it('keeps model summaries separate from full configuration reads', () => {
  const options = { signal: new AbortController().signal }
  api.admin.models.summaries(options)
  expect(http.get).toHaveBeenLastCalledWith('/admin/models/summaries', options)
  api.admin.models.list(options)
  expect(http.get).toHaveBeenLastCalledWith('/admin/models', options)
  api.admin.models.detail('9007199254740993', options)
  expect(http.get).toHaveBeenLastCalledWith('/admin/models/detail?id=9007199254740993', options)
})

describe('platform API identifier query parameters', () => {
  it('普通机构创建不因 401 自动重发，仍保留调用方反馈设置', () => {
    const payload = {
      tenant_name: '机构',
      tenant_slug: 'example',
      admin_username: 'admin',
      admin_password: 'fixture'
    }
    api.admin.createTenant('request-1', payload, { silent: true, retryOnUnauthorized: true })
    expect(http.post).toHaveBeenLastCalledWith(
      '/admin/tenants/create-with-receipt',
      { request_id: 'request-1', payload },
      {
        silent: true,
        retryOnUnauthorized: false
      }
    )
  })
  it('encodes nested identifiers separately and preserves request bodies', () => {
    const tenantId = 'tenant/a &b'
    const memberId = 'member?x=1'
    const body = { roles: ['tenant_admin'] }
    api.admin.updateMember(tenantId, memberId, body)
    const [path, actualBody] = http.patch.mock.lastCall!
    const url = new URL(path, 'https://example.test')
    expect(url.pathname).toBe('/admin/tenants/members/update')
    expect(url.search).toBe('')
    expect(actualBody).toEqual({ tenant_id: tenantId, member_id: memberId, payload: body })
  })

  it('combines member identifier with existing pagination and filters', () => {
    api.admin.listMembers('a/b', { page: 2, role: 'role & one' })
    const url = new URL(http.get.mock.lastCall![0], 'https://example.test')
    expect(url.pathname).toBe('/admin/tenants/members')
    expect(Object.fromEntries(url.searchParams)).toEqual({
      tenantId: 'a/b',
      page: '2',
      role: 'role & one'
    })
  })

  it('uses fixed definition paths and CAS-bound writes', () => {
    api.agentTemplates.detail('a/b')
    expect(http.get).toHaveBeenLastCalledWith(
      '/platform/agent-definitions/detail?definition_id=a%2Fb',
      undefined
    )
    api.agentTemplates.validate('definition/a', 7)
    expect(http.post).toHaveBeenLastCalledWith(
      '/platform/agent-definitions/validate',
      { definition_id: 'definition/a', expected_revision: 7 },
      { headers: { 'Idempotency-Key': expect.any(String) } }
    )
    api.agentTemplates.update('definition/a', 7, { name: '定义' })
    expect(http.put).toHaveBeenLastCalledWith(
      '/platform/agent-definitions/update',
      { definition_id: 'definition/a', expected_revision: 7, payload: { name: '定义' } },
      { headers: { 'Idempotency-Key': expect.any(String) } }
    )
  })

  it('uses the system-agent contract without nesting the revision payload', () => {
    const config = {
      name: '记忆提取',
      description: '提取候选',
      system_prompt: 'extract',
      model_config_id: 'model-1',
      timeout_seconds: 30,
      max_business_model_calls: 3,
      mounts: []
    }
    api.systemAgents.detail('agent/a')
    api.systemAgents.debugResult('agent/a', 'key /?', { silent: true })
    api.systemAgents.update({ agent_id: 'agent/a', expected_revision: 7, config })
    api.systemAgents.publish({ agent_id: 'agent/a', expected_revision: 7 })
    api.systemAgents.debug({
      agent_id: 'agent/a',
      source: 'DRAFT',
      expected_revision: 7,
      input: 'test',
      idempotency_key: 'key'
    })
    api.systemAgentTools.update(
      'deployment/a',
      2,
      {
        name: '记忆查询',
        tool_definition_id: 'tool-1',
        provider_type: 'PLATFORM',
        provider_ref: 'platform.memory.lookup',
        environment: 'TEST',
        active: true
      },
      'write-key'
    )

    expect(http.get).toHaveBeenCalledWith(
      '/admin/system-agents/detail?agentId=agent%2Fa',
      undefined
    )
    expect(http.get).toHaveBeenCalledWith(
      '/admin/system-agents/debug-result?agentId=agent%2Fa&idempotencyKey=key%20%2F%3F',
      { silent: true }
    )
    expect(http.put).toHaveBeenCalledWith('/admin/system-agents/update', {
      agent_id: 'agent/a',
      expected_revision: 7,
      config
    })
    expect(http.post).toHaveBeenCalledWith('/admin/system-agents/publish', {
      agent_id: 'agent/a',
      expected_revision: 7
    })
    expect(http.post).toHaveBeenLastCalledWith('/admin/system-agents/debug', {
      agent_id: 'agent/a',
      source: 'DRAFT',
      expected_revision: 7,
      input: 'test',
      idempotency_key: 'key'
    })
    expect(http.put).toHaveBeenLastCalledWith(
      '/admin/system-agent-tools/update',
      {
        id: 'deployment/a',
        expected_revision: 2,
        payload: {
          name: '记忆查询',
          tool_definition_id: 'tool-1',
          provider_type: 'PLATFORM',
          provider_ref: 'platform.memory.lookup',
          environment: 'TEST',
          active: true
        }
      },
      { retryOnUnauthorized: false, headers: { 'Idempotency-Key': 'write-key' } }
    )
  })

  it('系统 Agent 工具的创建和发布关闭 401 自动重发', () => {
    const payload = {
      name: '查询',
      tool_definition_id: 'tool-1',
      provider_type: 'PLATFORM' as const,
      provider_ref: 'platform.memory.lookup',
      environment: 'TEST' as const,
      active: true
    }
    api.systemAgentTools.create(payload, 'create-key')
    expect(http.post).toHaveBeenLastCalledWith('/admin/system-agent-tools', payload, {
      retryOnUnauthorized: false,
      headers: { 'Idempotency-Key': 'create-key' }
    })
    api.systemAgentTools.publish('deployment', 3, 'publish-key')
    expect(http.post).toHaveBeenLastCalledWith(
      '/admin/system-agent-tools/publish',
      { id: 'deployment', expected_revision: 3 },
      { retryOnUnauthorized: false, headers: { 'Idempotency-Key': 'publish-key' } }
    )
    api.systemAgentTools.receipt('key /?')
    expect(http.get).toHaveBeenLastCalledWith(
      '/admin/system-agent-tools/receipt?request_id=key%20%2F%3F',
      { silent: true }
    )
  })
})

describe('平台账单接口', () => {
  it('模型月费用查询编码机构与月份，分页使用明确snake_case参数且仅GET', () => {
    api.billing.monthly('tenant/a&b', { month: '2026-10', page: 2, page_size: 50 })
    const url = new URL(http.get.mock.lastCall![0], 'https://example.test')
    expect(url.pathname).toBe('/platform/billing/monthly')
    expect(Object.fromEntries(url.searchParams)).toEqual({
      tenant_id: 'tenant/a&b',
      month: '2026-10',
      page: '2',
      page_size: '50'
    })
    expect(api.billing).not.toHaveProperty('publishPrice')
    expect(api.billing).not.toHaveProperty('seal')
    expect(api.billing).not.toHaveProperty('adjust')
  })

  it('运营审计和欠费扫描使用平台命名空间的固定路径', () => {
    api.operations.auditLogs()
    api.operations.processBillingStates('operation-1')
    api.operations.billingStateResult('operation/1')

    expect(http.get).toHaveBeenCalledWith('/platform/audit/logs', undefined)
    expect(http.post).toHaveBeenCalledWith(
      '/platform/billing/process-states',
      { operation_id: 'operation-1' },
      undefined
    )
    expect(http.get).toHaveBeenLastCalledWith(
      '/platform/billing/result?operationId=operation%2F1',
      undefined
    )
  })
})
