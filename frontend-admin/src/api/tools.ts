import { get, post } from './index'

export interface AvailableToolDeployment {
  deployment_key: string
  provider_type: string
  provider_ref: string
  provider_label: string
  environment: string
}
export interface AvailableTool {
  definition_id: string
  default_version_id: string
  name: string
  title?: string | null
  description: string
  tool_name: string
  deployments: AvailableToolDeployment[]
}
export interface ToolOnboardingItem {
  tool_name: string
  definition_id: string
  version_id: string
  deployment_key: string
  environment: string
}
export interface ToolOnboardingReceipt {
  request_id: string
  connector_id: string
  tool_names: string[]
  status: 'PROCESSING' | 'SUCCEEDED' | 'REJECTED'
  items?: ToolOnboardingItem[]
  error_code?: string
  error_message?: string
}
export const toolsApi = {
  available: () => get<{ items: AvailableTool[] }>('/tools/available'),
  onboard: (requestId: string, connectorId: string, names: string[], allowAgentCalls: true) =>
    post<ToolOnboardingReceipt>('/tools/onboard', {
      request_id: requestId,
      connector_id: connectorId,
      tool_names: names,
      allow_agent_calls: allowAgentCalls
    }),
  onboarding: (requestId: string) =>
    get<ToolOnboardingReceipt | undefined>(
      `/tools/onboard/result?${new URLSearchParams({ requestId })}`
    )
}
