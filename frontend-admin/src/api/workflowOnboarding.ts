import { get, post } from './index'
import { workflowBindingsToWire, type WorkflowInstallationBindings } from './workflowDrafts'

export interface WorkflowOnboardingCommand {
  request_id: string
  agent_id: string
  workflow_id: string
  expected_definition_version_id: string
  mode: 'REUSE' | 'INSTALL' | 'UPDATE'
  expected_installation_revision: number
  config: Record<string, unknown>
  custom_prompt: string
  bindings: WorkflowInstallationBindings
  mount_id?: string
  expected_mount_revision?: number
}
export interface WorkflowOnboardingReceipt {
  request_id: string
  status: 'PROCESSING' | 'SUCCEEDED' | 'REJECTED'
  agent_id: string
  workflow_id: string
  instance_id?: string
  installation_revision?: number
  mount_id?: string
  mount_revision?: number
  error_message?: string
}
export const workflowOnboardingApi = {
  submit: (command: WorkflowOnboardingCommand) =>
    post<WorkflowOnboardingReceipt>(
      '/agents/workflows/onboard',
      {
        ...command,
        bindings: workflowBindingsToWire(command.bindings)
      },
      { retryOnUnauthorized: false }
    ),
  result: (requestId: string) =>
    get<WorkflowOnboardingReceipt | undefined>(
      `/agents/workflows/onboard/result?${new URLSearchParams({ requestId })}`
    )
}
