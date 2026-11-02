import { describe, expect, it, vi } from 'vitest'
import { createWorkflowTestsApi, type WorkflowTestCreate } from './workflowTests'

const source = {
  kind: 'draft',
  workflow_id: 'orders',
  draft_revision: 7,
  definition_version_id: null,
  hash: 'saved-definition-hash'
}
const budget = {
  max_business_model_calls: 4,
  max_tool_calls: 8,
  max_knowledge_calls: 8,
  max_total_tokens: 16000,
  max_wall_time_seconds: 120
}
const receipt = {
  test_session_id: '9007199254740993',
  request_id: 'request:原始',
  status: 'ready',
  source,
  session_id: 'session:private',
  agent_id: 'agent:private',
  end_user_id: '9007199254740995',
  initial_input_id: 'initial:reserved',
  initial_message: '冻结原文🙂',
  expires_at: '2026-10-24T12:00:00Z',
  run_id: null,
  run_status: null,
  close_status: 'open',
  blocking_reason: null
}
const input: WorkflowTestCreate = {
  requestId: receipt.request_id,
  workflowId: 'orders',
  source: { kind: 'draft', draftRevision: 7 },
  config: { enabled: false, count: 0 },
  bindings: {
    capabilities: [
      {
        slot: 'lookup',
        capability: { type: 'TOOL', capabilityId: 'cap:1', versionId: '9007199254740997' },
        tool_deployment_id: 'test-deployment',
        tool_deployment_revision: 3
      }
    ],
    workflows: [],
    agents: []
  },
  userInput: '冻结原文🙂',
  parameters: { count: 0, enabled: false, nullable: null },
  budget: {
    maxBusinessModelCalls: 4,
    maxToolCalls: 8,
    maxKnowledgeCalls: 8,
    maxTotalTokens: 16000,
    maxWallTimeSeconds: 120
  }
}

describe('工作流测试资源公开API', () => {
  it('候选及预算来自后端，精确目标与字符串身份在边界映射', async () => {
    const get = vi.fn().mockResolvedValue({
      source,
      input_schema: { type: 'object' },
      config_schema: [],
      capability_slots: [
        {
          slot: 'lookup',
          required: true,
          type: 'tool',
          candidates: [
            {
              capability: { type: 'TOOL', capabilityId: 'cap:1', versionId: '9007199254740997' },
              tool_deployment_id: 'test-deployment',
              tool_deployment_revision: 3,
              knowledge_revision: null,
              label: '测试查询'
            }
          ]
        }
      ],
      budget: { recommended: budget, maximum: { ...budget, max_total_tokens: 64000 } },
      restrictions: ['仅 TEST + READ_ONLY MCP 工具'],
      cost_notice: '真实调用按实际用量计费'
    })
    const client = createWorkflowTestsApi({ get, post: vi.fn() })
    const result = await client.options('orders', { kind: 'draft', draftRevision: 7 })
    expect(get).toHaveBeenCalledWith(
      '/builder/workflows/test-sessions/options?workflowId=orders&draftRevision=7',
      undefined
    )
    expect(result.capabilitySlots[0]!.candidates[0]!.capability.versionId).toBe('9007199254740997')
    expect(result.budget.recommended.maxTotalTokens).toBe(16000)
    expect(result.costNotice).toContain('实际用量计费')
    await client.options('orders', { kind: 'publication', definitionVersionId: '9007199254740999' })
    expect(get.mock.calls.at(-1)![0]).toBe(
      '/builder/workflows/test-sessions/options?workflowId=orders&definitionVersionId=9007199254740999'
    )
  })
  it('创建仅提交资源命令，不执行chat；保留false/0/null及capability引用的真实wire字段', async () => {
    const post = vi.fn().mockResolvedValue(receipt)
    const client = createWorkflowTestsApi({ get: vi.fn(), post })
    const result = await client.create(input)
    expect(post).toHaveBeenCalledExactlyOnceWith(
      '/builder/workflows/test-sessions',
      {
        request_id: input.requestId,
        workflow_id: 'orders',
        source: { kind: 'draft', draft_revision: 7 },
        config: input.config,
        bindings: input.bindings,
        user_input: input.userInput,
        parameters: input.parameters,
        budget
      },
      { retryOnUnauthorized: false }
    )
    expect(result.testSessionId).toBe('9007199254740993')
    expect(result.initialInputId).toBe('initial:reserved')
    expect(result.initialMessage).toBe('冻结原文🙂')
  })
  it('UNKNOWN查询与关闭只使用原资源身份，未知状态不伪装完成', async () => {
    const get = vi
      .fn()
      .mockResolvedValue({ ...receipt, status: 'future_status', run_status: 'outcome_unknown' })
    const post = vi.fn().mockResolvedValue({ ...receipt, close_status: 'cancelling' })
    const client = createWorkflowTestsApi({ get, post })
    expect((await client.byRequest('request:原始')).status).toBe('future_status')
    expect(get).toHaveBeenCalledWith(
      '/builder/workflows/test-sessions/by-request?requestId=request%3A%E5%8E%9F%E5%A7%8B',
      undefined
    )
    await client.get(receipt.test_session_id)
    expect(get.mock.calls.at(-1)![0]).toContain('testSessionId=9007199254740993')
    expect((await client.close(receipt.test_session_id)).closeStatus).toBe('cancelling')
    expect(post).toHaveBeenCalledExactlyOnceWith(
      '/builder/workflows/test-sessions/close',
      { test_session_id: '9007199254740993' },
      { retryOnUnauthorized: false }
    )
  })
  it('原回复回执保留精确身份与十进制修订，204 保持未知', async () => {
    const identity = {
      testSessionId: 'test:1',
      inputId: 'input:1',
      runId: 'run:1',
      actionId: 'action:1',
      generation: '9007199254740993',
      kind: 'workflow' as const,
      executionId: 'exec:1',
      suspendRef: 'suspend:1',
      actionKey: 'node',
      executionRevision: '9007199254740995'
    }
    const get = vi
      .fn()
      .mockResolvedValueOnce({
        test_session_id: identity.testSessionId,
        session_id: 'session:1',
        input_id: identity.inputId,
        run_id: identity.runId,
        action_id: identity.actionId,
        generation: identity.generation,
        kind: identity.kind,
        execution_id: identity.executionId,
        suspend_ref: identity.suspendRef,
        action_key: identity.actionKey,
        execution_revision: identity.executionRevision,
        accepted_revision: '9007199254740997'
      })
      .mockResolvedValueOnce(undefined)
    const client = createWorkflowTestsApi({ get, post: vi.fn() })
    expect(await client.replyReceipt(identity)).toEqual({
      ...identity,
      sessionId: 'session:1',
      acceptedRevision: '9007199254740997'
    })
    const url = new URL(get.mock.calls[0]![0], 'https://example.invalid')
    expect(Object.fromEntries(url.searchParams)).toEqual({ ...identity })
    expect(await client.replyReceipt(identity)).toBeUndefined()
  })
  it('损失精度的数值身份不转换成看似有效的字符串', async () => {
    const client = createWorkflowTestsApi({
      get: vi.fn().mockResolvedValue({ ...receipt, session_id: Number('9007199254740993') }),
      post: vi.fn()
    })
    await expect(client.get('test')).rejects.toThrow('准确身份')
  })
})
