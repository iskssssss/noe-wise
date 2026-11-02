import type { RequestOptions } from '@skillforge/shared/utils/http'
import type { WorkflowInstallationBindings } from './workflowDrafts'

export type WorkflowTestSource =
  { kind: 'draft'; draftRevision: number } | { kind: 'publication'; definitionVersionId: string }
export interface WorkflowTestSourceReceipt {
  kind: string
  workflowId: string
  draftRevision: number | null
  definitionVersionId: string | null
  hash: string
}
export interface WorkflowTestBudget {
  maxBusinessModelCalls: number
  maxToolCalls: number
  maxKnowledgeCalls: number
  maxTotalTokens: number
  maxWallTimeSeconds: number
}
export interface WorkflowTestCandidate {
  capability: { type: 'TOOL' | 'KNOWLEDGE_SOURCE'; capabilityId: string; versionId: string }
  toolDeploymentId: string | null
  toolDeploymentRevision: number | null
  knowledgeRevision: number | null
  label: string
}
export interface WorkflowTestOptions {
  source: WorkflowTestSourceReceipt
  inputSchema: Record<string, unknown>
  configSchema: unknown
  capabilitySlots: Array<{
    slot: string
    required: boolean
    type: string
    candidates: WorkflowTestCandidate[]
  }>
  budget: { recommended: WorkflowTestBudget; maximum: WorkflowTestBudget }
  restrictions: string[]
  costNotice: string
}
export interface WorkflowTestCreate {
  requestId: string
  workflowId: string
  source: WorkflowTestSource
  config: Record<string, unknown>
  bindings: WorkflowInstallationBindings
  userInput: string
  parameters: Record<string, unknown>
  budget: WorkflowTestBudget
}
export interface WorkflowTestReceipt {
  testSessionId: string
  requestId: string
  status: string
  source: WorkflowTestSourceReceipt
  sessionId: string
  agentId: string
  endUserId: string
  initialInputId: string
  initialMessage: string
  expiresAt: string
  runId: string | null
  runStatus: string | null
  closeStatus: string
  blockingReason: string | null
}
type SourceWire = {
  kind: string
  workflow_id: string
  draft_revision: number | null
  definition_version_id: string | null
  hash: string
}
type BudgetWire = {
  max_business_model_calls: number
  max_tool_calls: number
  max_knowledge_calls: number
  max_total_tokens: number
  max_wall_time_seconds: number
}
type CandidateWire = {
  capability: { type: 'TOOL' | 'KNOWLEDGE_SOURCE'; capabilityId: string; versionId: string }
  tool_deployment_id: string | null
  tool_deployment_revision: number | null
  knowledge_revision: number | null
  label: string
}
type OptionsWire = {
  source: SourceWire
  input_schema: Record<string, unknown>
  config_schema: unknown
  capability_slots: Array<{
    slot: string
    required: boolean
    type: string
    candidates: CandidateWire[]
  }>
  budget: { recommended: BudgetWire; maximum: BudgetWire }
  restrictions: string[]
  cost_notice: string
}
type ReceiptWire = {
  test_session_id: string
  request_id: string
  status: string
  source: SourceWire
  session_id: string
  agent_id: string
  end_user_id: string
  initial_input_id: string
  initial_message: string
  expires_at: string
  run_id: string | null
  run_status: string | null
  close_status: string
  blocking_reason: string | null
}

function identity(value: string): string {
  if (typeof value !== 'string' || !value.trim())
    throw Error('测试回执缺少准确身份，请按原请求查询状态')
  return value
}
function sourceFromWire(value: SourceWire): WorkflowTestSourceReceipt {
  return {
    kind: value.kind,
    workflowId: identity(value.workflow_id),
    draftRevision: value.draft_revision,
    definitionVersionId:
      value.definition_version_id === null ? null : identity(value.definition_version_id),
    hash: identity(value.hash)
  }
}
function budgetFromWire(value: BudgetWire): WorkflowTestBudget {
  return {
    maxBusinessModelCalls: value.max_business_model_calls,
    maxToolCalls: value.max_tool_calls,
    maxKnowledgeCalls: value.max_knowledge_calls,
    maxTotalTokens: value.max_total_tokens,
    maxWallTimeSeconds: value.max_wall_time_seconds
  }
}
function budgetToWire(value: WorkflowTestBudget): BudgetWire {
  return {
    max_business_model_calls: value.maxBusinessModelCalls,
    max_tool_calls: value.maxToolCalls,
    max_knowledge_calls: value.maxKnowledgeCalls,
    max_total_tokens: value.maxTotalTokens,
    max_wall_time_seconds: value.maxWallTimeSeconds
  }
}
function receiptFromWire(value: ReceiptWire): WorkflowTestReceipt {
  return {
    testSessionId: identity(value.test_session_id),
    requestId: identity(value.request_id),
    status: value.status,
    source: sourceFromWire(value.source),
    sessionId: identity(value.session_id),
    agentId: identity(value.agent_id),
    endUserId: identity(value.end_user_id),
    initialInputId: identity(value.initial_input_id),
    initialMessage: value.initial_message,
    expiresAt: value.expires_at,
    runId: value.run_id === null ? null : identity(value.run_id),
    runStatus: value.run_status,
    closeStatus: value.close_status,
    blockingReason: value.blocking_reason
  }
}
/** 只创建和查询后端测试资源；执行仍由现有正式 /chat 负责。 */
export function createWorkflowTestsApi(http: {
  get: <T>(path: string, options?: RequestOptions) => Promise<T>
  post: <T>(path: string, body: unknown, options?: RequestOptions) => Promise<T>
}) {
  const root = '/builder/workflows/test-sessions'
  return {
    options: async (
      workflowId: string,
      source: WorkflowTestSource,
      options?: RequestOptions
    ): Promise<WorkflowTestOptions> => {
      const query = new URLSearchParams({ workflowId })
      if (source.kind === 'draft') query.set('draftRevision', String(source.draftRevision))
      else query.set('definitionVersionId', source.definitionVersionId)
      const result = await http.get<OptionsWire>(root + '/options?' + query, options)
      return {
        source: sourceFromWire(result.source),
        inputSchema: result.input_schema,
        configSchema: result.config_schema,
        capabilitySlots: result.capability_slots.map((slot) => ({
          ...slot,
          candidates: slot.candidates.map((candidate) => ({
            capability: {
              type: candidate.capability.type,
              capabilityId: identity(candidate.capability.capabilityId),
              versionId: identity(candidate.capability.versionId)
            },
            toolDeploymentId:
              candidate.tool_deployment_id === null ? null : identity(candidate.tool_deployment_id),
            toolDeploymentRevision: candidate.tool_deployment_revision,
            knowledgeRevision: candidate.knowledge_revision,
            label: candidate.label
          }))
        })),
        budget: {
          recommended: budgetFromWire(result.budget.recommended),
          maximum: budgetFromWire(result.budget.maximum)
        },
        restrictions: result.restrictions,
        costNotice: result.cost_notice
      }
    },
    create: (value: WorkflowTestCreate, options?: RequestOptions) =>
      http
        .post<ReceiptWire>(
          root,
          {
            request_id: value.requestId,
            workflow_id: value.workflowId,
            source:
              value.source.kind === 'draft'
                ? { kind: 'draft', draft_revision: value.source.draftRevision }
                : { kind: 'publication', definition_version_id: value.source.definitionVersionId },
            config: value.config,
            bindings: {
              ...value.bindings,
              capabilities: value.bindings.capabilities.map((binding) => ({
                ...binding,
                capability: {
                  type: binding.capability.type,
                  capabilityId: binding.capability.capabilityId,
                  versionId: binding.capability.versionId
                }
              }))
            },
            user_input: value.userInput,
            parameters: value.parameters,
            budget: budgetToWire(value.budget)
          },
          { ...options, retryOnUnauthorized: false }
        )
        .then(receiptFromWire),
    byRequest: (requestId: string, options?: RequestOptions) =>
      http
        .get<ReceiptWire>(root + '/by-request?' + new URLSearchParams({ requestId }), options)
        .then(receiptFromWire),
    get: (testSessionId: string, options?: RequestOptions) =>
      http
        .get<ReceiptWire>(root + '?' + new URLSearchParams({ testSessionId }), options)
        .then(receiptFromWire),
    close: (testSessionId: string, options?: RequestOptions) =>
      http
        .post<ReceiptWire>(root + '/close', { test_session_id: testSessionId }, options)
        .then(receiptFromWire)
  }
}
