import type {
  AgentDeployment,
  AgentGrant,
  DeploymentBindings
} from '@skillforge/shared/integration/agentDeployments'
import { HttpClientError } from '@skillforge/shared/utils/http'
import type { CreateAgentGrant } from '../api/agentGrants'

export type PlatformDeploymentInput = DeploymentBindings & {
  tenant_id: string
  integration_id: string | null
  definition_id: string
  grant_id: string
  external_agent_ref: string
  name?: string
}

export interface SetupCommand<T> {
  input: T
  key: string
  status: 'pending' | 'unknown' | 'rejected' | 'succeeded'
}

/** 只在一个添加流程内保存原操作，不把未知请求替换为新的授权或部署。 */
export class AgentSetupProgress {
  grant?: AgentGrant
  deployment?: AgentDeployment
  grantCommand?: SetupCommand<CreateAgentGrant>
  deploymentCommand?: SetupCommand<PlatformDeploymentInput>
  private readonly api: {
    grant: (input: CreateAgentGrant, key: string) => Promise<AgentGrant>
    deploy: (input: PlatformDeploymentInput, key: string) => Promise<AgentDeployment>
  }
  private readonly guard: () => void
  private readonly key: () => string
  private readonly beforeDispatch: () => void

  constructor(
    api: {
      grant: (input: CreateAgentGrant, key: string) => Promise<AgentGrant>
      deploy: (input: PlatformDeploymentInput, key: string) => Promise<AgentDeployment>
    },
    guard: () => void,
    key: () => string = () => crypto.randomUUID(),
    beforeDispatch: () => void = () => {}
  ) {
    this.api = api
    this.guard = guard
    this.key = key
    this.beforeDispatch = beforeDispatch
  }

  get unknown() {
    return this.grantCommand?.status === 'unknown' || this.deploymentCommand?.status === 'unknown'
  }

  acceptGrantReceipt(result: AgentGrant) {
    const command = this.grantCommand
    if (
      !command ||
      command.status !== 'unknown' ||
      !result?.grant_id ||
      result.target_tenant_id !== command.input.target_tenant_id ||
      result.definition_id !== command.input.definition_id
    )
      throw Error('原授权回执的身份无法核实')
    this.grant = result
    command.status = 'succeeded'
  }

  acceptDeploymentReceipt(result: AgentDeployment) {
    const command = this.deploymentCommand
    if (
      !command ||
      command.status !== 'unknown' ||
      !result?.deployment_id ||
      !result.agent_id ||
      result.tenant_id !== command.input.tenant_id ||
      result.definition_id !== command.input.definition_id ||
      result.grant_id !== command.input.grant_id ||
      result.integration_id !== command.input.integration_id ||
      result.external_agent_ref !== command.input.external_agent_ref
    )
      throw Error('原部署回执的身份无法核实')
    this.deployment = result
    command.status = 'succeeded'
  }

  async authorize(input: CreateAgentGrant): Promise<AgentGrant> {
    this.guard()
    if (this.grant) {
      if (JSON.stringify(input) !== JSON.stringify(this.grantCommand?.input))
        throw Error('本流程已有成功授权，请继续原部署或开始新的添加流程')
      return this.grant
    }
    const command = this.command(this.grantCommand, input)
    this.grantCommand = command
    try {
      this.beforeDispatch()
    } catch (reason) {
      this.grantCommand!.status = 'rejected'
      throw reason
    }
    try {
      const result = await this.api.grant(command.input, command.key)
      this.guard()
      if (
        !result?.grant_id ||
        result.target_tenant_id !== command.input.target_tenant_id ||
        result.definition_id !== command.input.definition_id
      )
        throw Error('授权成功响应的身份无法核实，请核对原请求')
      this.grant = result
      this.grantCommand!.status = 'succeeded'
      return result
    } catch (reason) {
      this.grantCommand!.status = uncertain(reason) ? 'unknown' : 'rejected'
      throw reason
    }
  }

  async deploy(input: PlatformDeploymentInput): Promise<AgentDeployment> {
    this.guard()
    if (this.deployment) return this.deployment
    if (this.grantCommand?.status === 'unknown') throw Error('请先核对原授权结果')
    const command = this.command(this.deploymentCommand, input)
    this.deploymentCommand = command
    try {
      this.beforeDispatch()
    } catch (reason) {
      this.deploymentCommand!.status = 'rejected'
      throw reason
    }
    try {
      const result = await this.api.deploy(command.input, command.key)
      this.guard()
      if (
        !result?.deployment_id ||
        !result.agent_id ||
        result.tenant_id !== command.input.tenant_id ||
        result.definition_id !== command.input.definition_id ||
        result.grant_id !== command.input.grant_id ||
        result.integration_id !== command.input.integration_id ||
        result.external_agent_ref !== command.input.external_agent_ref
      )
        throw Error('部署成功响应的身份无法核实，请核对原请求')
      this.deployment = result
      this.deploymentCommand!.status = 'succeeded'
      return result
    } catch (reason) {
      this.deploymentCommand!.status = uncertain(reason) ? 'unknown' : 'rejected'
      throw reason
    }
  }

  private command<T>(prior: SetupCommand<T> | undefined, input: T): SetupCommand<T> {
    if (prior?.status === 'unknown') {
      throw Error('原请求结果未知，请使用原参数核对，不能创建替代操作')
    }
    if (prior && JSON.stringify(input) === JSON.stringify(prior.input)) {
      prior.status = 'pending'
      return prior
    }
    return { input: structuredClone(input), key: this.key(), status: 'pending' }
  }
}

/** 完整消费许可才可部署；过期、撤销和其他目标机构的授权不会成为默认候选。 */
export function usableGrant(
  grant: AgentGrant,
  tenant: string,
  definition: string,
  now = Date.now()
) {
  return (
    grant.target_tenant_id === tenant &&
    grant.definition_id === definition &&
    grant.status === 'active' &&
    grant.actions.includes('deploy') &&
    grant.actions.includes('execute') &&
    (!grant.expires_at || Date.parse(grant.expires_at) > now)
  )
}

export function uncertain(reason: unknown) {
  return !(reason instanceof HttpClientError) || reason.status === 0 || reason.status >= 500
}
