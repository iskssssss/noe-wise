import { describe, expect, it, vi } from 'vitest'
import { HttpClientError } from '@skillforge/shared/utils/http'
import type { AgentDeployment, AgentGrant } from '@skillforge/shared/integration/agentDeployments'
import { AgentSetupProgress, usableGrant, type PlatformDeploymentInput } from './agentSetup'

const grant = {
  grant_id: '21',
  definition_id: '31',
  target_tenant_id: '41',
  status: 'active',
  actions: ['deploy', 'execute'],
  expires_at: null
} as AgentGrant
const grantInput = {
  definition_id: '31',
  target_tenant_id: '41',
  actions: ['deploy', 'execute'] as Array<'deploy' | 'execute'>,
  expires_at: null,
  allow_regrant: false,
  remaining_depth: 0
}
const deploymentInput: PlatformDeploymentInput = {
  tenant_id: '41',
  integration_id: 'integration-1',
  definition_id: '31',
  grant_id: '21',
  external_agent_ref: 'assistant',
  resource_bindings: [],
  delegation_bindings: [],
  parameters: {}
}
const deployment = { ...deploymentInput, deployment_id: '51', agent_id: '61' } as AgentDeployment
const unknown = () => new HttpClientError({ status: 0, userMessage: '结果未知' })

describe('机构添加智能体的原操作进度', () => {
  it('授权成功后部署失败只继续原部署，不再创建授权', async () => {
    const api = {
      grant: vi.fn().mockResolvedValue(grant),
      deploy: vi
        .fn()
        .mockRejectedValueOnce(
          new HttpClientError({ status: 422, userMessage: '部署声明需要修正' })
        )
        .mockResolvedValue(deployment)
    }
    const flow = new AgentSetupProgress(
      api,
      () => {},
      () => 'stable-key'
    )
    await flow.authorize(grantInput)
    await expect(flow.deploy(deploymentInput)).rejects.toThrow('部署声明需要修正')
    expect(await flow.authorize(grantInput)).toBe(grant)
    expect(await flow.deploy(deploymentInput)).toBe(deployment)
    expect(api.grant).toHaveBeenCalledTimes(1)
    expect(api.deploy.mock.calls.map((call) => call[1])).toEqual(['stable-key', 'stable-key'])
  })

  it('未知授权阻止新写入，显式核对使用原正文和原键', async () => {
    const api = {
      grant: vi.fn().mockRejectedValueOnce(unknown()).mockResolvedValue(grant),
      deploy: vi.fn()
    }
    const key = vi.fn().mockReturnValue('grant-original')
    const flow = new AgentSetupProgress(api, () => {}, key)
    await expect(flow.authorize(grantInput)).rejects.toThrow('结果未知')
    await expect(flow.authorize({ ...grantInput, definition_id: '32' })).rejects.toThrow(
      '原请求结果未知'
    )
    await expect(flow.deploy(deploymentInput)).rejects.toThrow('请先核对原授权结果')
    flow.acceptGrantReceipt(grant)
    expect(flow.grant).toBe(grant)
    expect(api.grant).toHaveBeenCalledTimes(1)
    expect(key).toHaveBeenCalledTimes(1)
  })

  it('未知部署核对沿同键，不以新外部标识代替旧操作', async () => {
    const api = {
      grant: vi.fn(),
      deploy: vi.fn().mockRejectedValueOnce(unknown()).mockResolvedValue(deployment)
    }
    const flow = new AgentSetupProgress(
      api,
      () => {},
      () => 'deployment-original'
    )
    await expect(flow.deploy(deploymentInput)).rejects.toThrow('结果未知')
    await expect(
      flow.deploy({ ...deploymentInput, external_agent_ref: 'replacement' })
    ).rejects.toThrow('原请求结果未知')
    flow.acceptDeploymentReceipt(deployment)
    expect(api.deploy).toHaveBeenCalledTimes(1)
  })

  it('写入后身份失效不会采纳回执或开始部署', async () => {
    const guard = vi
      .fn()
      .mockImplementationOnce(() => {})
      .mockImplementation(() => {
        throw Error('身份已变化')
      })
    const api = { grant: vi.fn().mockResolvedValue(grant), deploy: vi.fn() }
    const flow = new AgentSetupProgress(api, guard)
    await expect(flow.authorize(grantInput)).rejects.toThrow('身份已变化')
    expect(flow.grant).toBeUndefined()
    await expect(flow.deploy(deploymentInput)).rejects.toThrow('身份已变化')
    expect(api.deploy).not.toHaveBeenCalled()
  })

  it('仅本机构完整且未过期的消费许可可以预选', () => {
    expect(usableGrant(grant, '41', '31')).toBe(true)
    expect(usableGrant(grant, '42', '31')).toBe(false)
    expect(usableGrant({ ...grant, actions: ['execute'] }, '41', '31')).toBe(false)
    expect(usableGrant({ ...grant, expires_at: '2020-01-01T00:00:00Z' }, '41', '31')).toBe(false)
    expect(usableGrant({ ...grant, status: 'revoked' }, '41', '31')).toBe(false)
  })

  it('成功响应归属不符仍保留未知状态，不能用错误机构回执推进', async () => {
    const api = {
      grant: vi.fn().mockResolvedValue({ ...grant, target_tenant_id: 'other' }),
      deploy: vi.fn()
    }
    const flow = new AgentSetupProgress(api, () => {})
    await expect(flow.authorize(grantInput)).rejects.toThrow('身份无法核实')
    expect(flow.grant).toBeUndefined()
    expect(flow.grantCommand?.status).toBe('unknown')
    expect(api.deploy).not.toHaveBeenCalled()
  })
})
