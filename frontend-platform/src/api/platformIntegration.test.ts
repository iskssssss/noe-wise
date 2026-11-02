import { describe, expect, it, vi } from 'vitest'
import { createPlatformIntegrationApi } from './platformIntegration'
import { createAgentGrantsApi } from './agentGrants'
import { createAgentDeploymentsApi } from '@skillforge/shared/integration/agentDeployments'
import { createUsageApi } from '@skillforge/shared/integration/usage'
import { createCapabilityCatalogApi } from './capabilities'

describe('平台直管公开传输边界', () => {
  it('复用正式目录mapper保留大ID和普通修订，不接受number身份', async () => {
    const wire = {
      id: '9223372036854775807',
      tenant_id: null,
      name: '能力',
      capability_type: 'TOOL',
      status: 'PUBLISHED',
      revision: 4,
      default_version_id: '9223372036854775806'
    }
    const http = {
      get: vi
        .fn()
        .mockResolvedValueOnce([wire])
        .mockResolvedValueOnce([{ ...wire, id: 101 }]),
      post: vi.fn(),
      put: vi.fn()
    }
    const api = createCapabilityCatalogApi(http)
    expect(await api.listDefinitions()).toEqual([
      expect.objectContaining({
        id: '9223372036854775807',
        defaultVersionId: '9223372036854775806',
        revision: 4
      })
    ])
    await expect(api.listDefinitions()).rejects.toThrow('能力标识无效')
  })
  it('管理身份只用JWT管理路径和独立操作键，秘密不进入认证头', async () => {
    const http = {
      get: vi.fn().mockResolvedValue({ items: [] }),
      post: vi.fn().mockResolvedValue({})
    }
    const api = createPlatformIntegrationApi(http)
    await api.managementPrincipals(2)
    expect(http.get).toHaveBeenLastCalledWith(
      '/platform/management-principals?page=2&page_size=20',
      undefined
    )
    const input = {
      name: '机构开通机器',
      scopes: ['platform.institutions.provision'],
      target_tenant_ids: [],
      target_definition_ids: [],
      target_namespaces: ['clinic']
    }
    await api.createManagementPrincipal(input, 'create-1')
    expect(http.post).toHaveBeenLastCalledWith('/platform/management-principals', input, {
      headers: { 'Idempotency-Key': 'create-1' }
    })
    await api.rotateManagementPrincipal(
      { principal_id: '9223372036854775807', expected_revision: 7 },
      'rotate-1'
    )
    expect(http.post).toHaveBeenLastCalledWith(
      '/platform/management-principals/rotate',
      { principal_id: '9223372036854775807', expected_revision: 7 },
      { headers: { 'Idempotency-Key': 'rotate-1' } }
    )
  })
  it('机构开通和执行凭证分离，目标身份位于固定路径请求体', async () => {
    const http = {
      get: vi.fn().mockResolvedValue({ items: [] }),
      post: vi.fn().mockResolvedValue({})
    }
    const api = createPlatformIntegrationApi(http)
    const target = { tenant_id: '9223372036854775807', integration_id: 'clinic/a &b' }
    await api.institutionCredentials({ ...target, page: 1 })
    expect(http.get).toHaveBeenLastCalledWith(
      '/platform/institution-credentials?tenant_id=9223372036854775807&integration_id=clinic%2Fa+%26b&page=1&page_size=20',
      undefined
    )
    const input = { ...target, principal_id: '9223372036854775806', expected_revision: 3 }
    await api.rotateCredential(input, 'rotate-execution')
    expect(http.post).toHaveBeenLastCalledWith('/platform/institution-credentials/rotate', input, {
      headers: { 'Idempotency-Key': 'rotate-execution' }
    })
  })
  it('直授和代部署明确目标、授权和CAS，不发送旧应用身份', async () => {
    const http = {
      get: vi.fn().mockResolvedValue({ items: [] }),
      post: vi.fn().mockResolvedValue({}),
      put: vi.fn().mockResolvedValue({})
    }
    const grants = createAgentGrantsApi(http)
    const grantRequest = {
      definition_id: '9223372036854775807',
      target_tenant_id: '9223372036854775806',
      actions: ['deploy', 'execute'] as Array<'deploy' | 'execute'>,
      expires_at: null,
      allow_regrant: true,
      remaining_depth: 2,
      parent_grant_id: null
    }
    await grants.create(grantRequest, 'grant-create')
    expect(http.post).toHaveBeenLastCalledWith('/platform/agent-grants', grantRequest, {
      headers: { 'Idempotency-Key': 'grant-create' }
    })
    await grants.revoke({ grant_id: '9223372036854775807', expected_revision: 5 }, 'grant-revoke')
    expect(http.post).toHaveBeenLastCalledWith(
      '/platform/agent-grants/revoke',
      { grant_id: '9223372036854775807', expected_revision: 5 },
      { headers: { 'Idempotency-Key': 'grant-revoke' } }
    )
    const deployments = createAgentDeploymentsApi(http, '/platform/agent-deployments')
    const payload = {
      definition_id: '9223372036854775807',
      grant_id: 'grant-1',
      integration_id: 'integration-1',
      external_agent_ref: 'agent-a',
      resource_bindings: [],
      delegation_bindings: [],
      parameters: {}
    }
    await deployments.create({ tenant_id: '9223372036854775806', ...payload })
    expect(http.post).toHaveBeenLastCalledWith(
      '/platform/agent-deployments',
      { tenant_id: '9223372036854775806', payload },
      { headers: { 'Idempotency-Key': expect.any(String) } }
    )
    const authorization = {
      deployment_id: '9223372036854775804',
      expected_revision: 8,
      grant_id: '9223372036854775803'
    }
    await deployments.authorization(
      { tenant_id: '9223372036854775806', ...authorization },
      'platform-switch'
    )
    expect(http.post).toHaveBeenLastCalledWith(
      '/platform/agent-deployments/authorization',
      { tenant_id: '9223372036854775806', payload: authorization },
      { headers: { 'Idempotency-Key': 'platform-switch' } }
    )
  })
  it('用量保留原精确金额与不透明游标，人员与机构读取各自正式入口', async () => {
    const result = {
      items: [{ amount_cents: '9223372036854775807.125' }],
      next_cursor: 'opaque:signature/a',
      has_more: true
    }
    const http = { get: vi.fn().mockResolvedValue(result) }
    const api = createUsageApi(http, '/platform/usage')
    expect(
      await api.details({
        deployment_id: '9223372036854775807',
        integration_id: 'integration/a',
        cursor: 'opaque:signature/a'
      })
    ).toBe(result)
    expect(http.get).toHaveBeenLastCalledWith(
      '/platform/usage/details?deployment_id=9223372036854775807&integration_id=integration%2Fa&cursor=opaque%3Asignature%2Fa',
      undefined
    )
    await createUsageApi(http, '/admin/usage').events({ run_id: 'run/1' })
    expect(http.get).toHaveBeenLastCalledWith('/admin/usage/events?run_id=run%2F1', undefined)
  })
})
