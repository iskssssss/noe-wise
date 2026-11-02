import { describe, expect, it, vi } from 'vitest'
import { createAgentDeploymentsApi } from './agentDeployments'

describe('机构公共Agent部署契约', () => {
  it('总部明确目标保留在原生请求正文，授权切换保持指定命令键', async () => {
    const http = {
      get: vi.fn().mockResolvedValue({ items: [] }),
      post: vi.fn().mockResolvedValue({}),
      put: vi.fn().mockResolvedValue({})
    }
    const api = createAgentDeploymentsApi(http)
    const tenant = '9223372036854775806'
    await api.list(undefined, tenant)
    expect(http.get).toHaveBeenLastCalledWith(
      `/tenant/agent-deployments?tenant_id=${tenant}`,
      undefined
    )
    await api.definitions(undefined, tenant)
    expect(http.get).toHaveBeenLastCalledWith(
      `/tenant/agent-deployments/definitions?tenant_id=${tenant}`,
      undefined
    )
    await api.grants(undefined, tenant)
    expect(http.get).toHaveBeenLastCalledWith(
      `/tenant/agent-deployments/grants?tenant_id=${tenant}`,
      undefined
    )
    await api.detail('9223372036854775804', undefined, tenant)
    expect(http.get).toHaveBeenLastCalledWith(
      `/tenant/agent-deployments/detail?deployment_id=9223372036854775804&tenant_id=${tenant}`,
      undefined
    )
    const bindings = { resource_bindings: [], delegation_bindings: [], parameters: {} }
    const deploy = {
      tenant_id: tenant,
      definition_id: '9223372036854775807',
      grant_id: '9223372036854775805',
      integration_id: null,
      external_agent_ref: 'headquarters-instance',
      ...bindings
    }
    await api.create(deploy)
    expect(http.post).toHaveBeenLastCalledWith('/tenant/agent-deployments', deploy, {
      headers: { 'Idempotency-Key': expect.any(String) }
    })
    const bind = {
      tenant_id: tenant,
      deployment_id: '9223372036854775804',
      expected_revision: 7,
      ...bindings
    }
    await api.bindings(bind)
    expect(http.put).toHaveBeenLastCalledWith('/tenant/agent-deployments/bindings', bind, {
      headers: { 'Idempotency-Key': expect.any(String) }
    })
    const authorization = {
      tenant_id: tenant,
      deployment_id: bind.deployment_id,
      expected_revision: 8,
      grant_id: '9223372036854775803'
    }
    await api.authorization(authorization, 'stable-headquarters-switch')
    expect(http.post).toHaveBeenLastCalledWith(
      '/tenant/agent-deployments/authorization',
      authorization,
      { headers: { 'Idempotency-Key': 'stable-headquarters-switch' } }
    )
  })

  it('当前机构JWT固定路径，创建与CAS绑定保留稳定字符串身份', async () => {
    const http = {
      get: vi.fn().mockResolvedValue({ items: [] }),
      post: vi.fn().mockResolvedValue({}),
      put: vi.fn().mockResolvedValue({})
    }
    const api = createAgentDeploymentsApi(http)
    await api.definitions()
    expect(http.get).toHaveBeenLastCalledWith('/tenant/agent-deployments/definitions', undefined)
    await api.bindings({
      deployment_id: '9223372036854775807',
      expected_revision: 7,
      resource_bindings: [],
      delegation_bindings: [],
      parameters: { clinic: '门店甲' }
    })
    expect(http.put).toHaveBeenLastCalledWith(
      '/tenant/agent-deployments/bindings',
      {
        deployment_id: '9223372036854775807',
        expected_revision: 7,
        resource_bindings: [],
        delegation_bindings: [],
        parameters: { clinic: '门店甲' }
      },
      { headers: { 'Idempotency-Key': expect.any(String) } }
    )
    await api.grants()
    expect(http.get).toHaveBeenLastCalledWith('/tenant/agent-deployments/grants', undefined)
  })
})
