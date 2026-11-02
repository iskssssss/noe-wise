import type { Page } from '@playwright/test'
import type {
  ObservationNode,
  TrajectoryDetail,
  TrajectorySummary
} from '../../../frontend-shared/src/observation/types'
import { mockConsole } from './mock-console'

/** 浏览器夹具只拦截自身请求；实际投影、隔离与筛选由后端真实数据库测试验证。 */
export async function mockTrajectory(
  page: Page,
  kind: 'admin' | 'platform',
  options: { dense?: boolean; unknown?: boolean } = {}
) {
  const fixture = await mockConsole(page, kind, ['foundation.sessions.read'])
  const prefix = kind === 'platform' ? '/platform' : '/chat'
  const time = '2026-10-25T02:00:00Z'
  const totals = {
    prompt_tokens: '9007199254740993',
    completion_tokens: '35',
    cached_input_tokens: '20',
    cache_write_input_tokens: null,
    reasoning_output_tokens: null
  }
  const coverage = {
    status: 'COMPLETE',
    total_attempts: 1,
    reported_attempts: 1,
    unknown_attempts: 0,
    partial_attempts: 0,
    zero_usage_attempts: 0
  }
  const summary: TrajectorySummary = {
    tenant_id: '9007199254740993',
    tenant_name: '轨迹验收机构',
    session_id: 'observed-session',
    root_input_id: 'observed-input',
    agent_id: '7001',
    agent_name: '流程助手',
    input: '请给出评估操作步骤',
    output: '已找到评估操作说明，请按以下步骤执行。',
    status: 'COMPLETED',
    answer_status: 'COMPLETED',
    started_at: time,
    finished_at: '2026-10-25T02:00:02Z',
    duration_ms: '2000',
    trace_ids: ['trace-observed'],
    models: ['observed-model'],
    tools: ['knowledge_query'],
    step_count: 1,
    model_calls: 1,
    tool_calls: 1,
    totals,
    coverage
  }
  const detail: TrajectoryDetail = {
    termination: null,
    summary,
    messages: [],
    snapshot: 'fixture-snapshot',
    read_at: time,
    usage_summary: {
      calls: 1,
      tokens: '9007199254741028',
      prompt_tokens: totals.prompt_tokens,
      completion_tokens: totals.completion_tokens,
      amount_by_currency: { CNY: '2' },
      unknown_usage_calls: 0,
      cost_coverage: {
        status: 'COMPLETE',
        total_events: 1,
        priced_events: 1,
        unconfirmed_events: 0,
        missing_model_attempts: 0
      }
    },
    usage: {
      tenant_id: summary.tenant_id,
      session_id: summary.session_id,
      root_input_id: summary.root_input_id,
      read_at: time,
      coverage,
      totals,
      cache: null,
      breakdowns: [],
      attempts: [],
      next_cursor: null
    }
  }
  function node(
    id: string,
    parent: string | null,
    type: ObservationNode['kind'],
    name: string
  ): ObservationNode {
    return {
      node_id: id,
      parent_node_id: parent,
      kind: type,
      name,
      status: 'SUCCEEDED',
      source_session_id: summary.session_id,
      root_input_id: summary.root_input_id,
      input_id: summary.root_input_id,
      run_id: 'observed-run',
      action_id: null,
      step_id: type === 'ENTRY' || type === 'AGENT' ? null : 'observed-step',
      iteration: type === 'ENTRY' || type === 'AGENT' ? null : '1',
      trace_id: 'trace-observed',
      span_id: null,
      started_at: time,
      finished_at: '2026-10-25T02:00:02Z',
      duration_ms: '2000',
      sequence: String(id.length),
      summary: null,
      input: { ref: null, availability: 'NOT_RECORDED' },
      output: { ref: null, availability: 'NOT_RECORDED' },
      source_event_ids: [],
      attempts: []
    }
  }
  const nodes = [
    node('entry', null, 'ENTRY', '用户请求'),
    node('agent', 'entry', 'AGENT', '流程助手'),
    node('step', 'agent', 'STEP', '第 1 轮'),
    node('llm', 'step', 'LLM', 'observed-model'),
    node('tool', 'step', 'TOOL', 'knowledge_query'),
    node('retrieval', 'tool', 'RETRIEVAL', '评估流程检索')
  ]
  if (options.unknown)
    for (const node of nodes.filter((item) =>
      ['entry', 'agent', 'step', 'tool'].includes(item.node_id)
    ))
      node.status = 'OUTCOME_UNKNOWN'
  nodes[3]!.attempts = [
    {
      attempt_id: 'attempt-observed',
      input: { ref: 'model-request-observed', availability: 'FULL' },
      output: { ref: 'model-response-observed', availability: 'FULL' },
      diagnostic_available: true,
      provider: 'fixture',
      model: 'observed-model',
      status: 'SUCCEEDED',
      started_at: time,
      finished_at: '2026-10-25T02:00:02Z',
      duration_ms: '2000',
      report_status: 'REPORTED',
      totals
    }
  ]
  nodes[4]!.input = { ref: 'input-tool', availability: 'FULL' }
  nodes[4]!.output = { ref: 'output-tool', availability: 'FULL' }
  if (options.dense) {
    nodes.push(
      node('input-guard', 'step', 'GUARDRAIL', '输入安全与访问权限校验'),
      node('step-two', 'agent', 'STEP', '第 2 轮：整理证据与委派复核'),
      node('memory-tool', 'step-two', 'TOOL', 'search_memory'),
      node('memory', 'memory-tool', 'MEMORY', '查找本次任务可访问的历史结论'),
      node('dispatch', 'step-two', 'TOOL', 'delegate_to_agent'),
      node('member', 'dispatch', 'AGENT', '资料核验成员'),
      node('member-step', 'member', 'STEP', '成员第 1 轮'),
      node(
        'member-model',
        'member-step',
        'LLM',
        '机构审核模型 / published-model-revision-20261025'
      ),
      node(
        'deep-tool',
        'member-step',
        'TOOL',
        '跨部门资料核验与异常证据归档工具 / institution_evidence_reconciliation_with_extended_diagnostic_name'
      ),
      node('deep-retrieval', 'deep-tool', 'RETRIEVAL', '核对不同来源的资料版本与访问范围'),
      node('output-guard', 'member-step', 'GUARDRAIL', '输出披露范围与结果完整性校验'),
      node('compaction', 'agent', 'COMPACTION', '压缩已完成执行轮的上下文'),
      node('summary-model', 'compaction', 'LLM', '上下文摘要模型')
    )
    const selected = nodes.find((value) => value.node_id === 'deep-tool')!
    selected.summary = '跨部门工具名称较长，完整名称与来源身份仍可在节点详情中阅读。'
    selected.input = { ref: 'input-tool', availability: 'FULL' }
    selected.output = { ref: 'output-tool', availability: 'FULL' }
  }
  let failNodes = false
  fixture.setHook(async (_request, url) => {
    const path = url.pathname.slice('/api/v1'.length)
    if (path.endsWith('/foundation/catalog/navigation/current'))
      return {
        data: [
          {
            title: 'Agent 轨迹',
            path: `/${kind}/trajectories`,
            permission: 'foundation.sessions.read',
            children: []
          }
        ]
      }
    if (path === `${prefix}/trajectories`)
      return {
        data: {
          items: [summary],
          page: Number(url.searchParams.get('page') || '1'),
          page_size: 20,
          total: 1,
          read_at: time
        }
      }
    if (path === `${prefix}/trajectories/detail`) return { data: detail }
    if (path === `${prefix}/trajectories/nodes`) {
      if (failNodes) return { status: 503, code: 'OBSERVATION_UNAVAILABLE' }
      const searched = Boolean(url.searchParams.get('query'))
      return {
        data: {
          items: searched ? [nodes[5]] : nodes,
          ancestors: searched ? [nodes[0], nodes[1], nodes[2], nodes[4]] : [],
          next_cursor: null,
          snapshot: detail.snapshot,
          coverage: 'COMPLETE',
          read_at: time
        }
      }
    }
    if (path === `${prefix}/trajectories/content`)
      return {
        data: {
          content_ref: url.searchParams.get('content_ref'),
          availability: 'FULL',
          schema_id: 'application/json',
          value: { query: '评估操作流程', result: '已记录的工具结果' }
        }
      }
    if (kind === 'platform' && path === '/platform/sessions/rounds')
      return {
        data: {
          tenant_id: summary.tenant_id,
          session_id: summary.session_id,
          items: [
            {
              root_input_id: summary.root_input_id,
              round_number: '1',
              first_sequence: '1',
              last_sequence: '6',
              answer_status: 'COMPLETED',
              execution_status: 'COMPLETED',
              started_at: time,
              updated_at: time,
              run_count: 1,
              runs: []
            }
          ],
          next_cursor: null,
          read_at: time
        }
      }
    if (kind === 'platform' && path === '/platform/sessions/detail')
      return {
        data: {
          ...summary,
          message_count: 2,
          root_input_count: 1,
          answer_status: 'COMPLETED',
          execution_status: 'COMPLETED',
          usage_snapshot: totals
        }
      }
    if (kind === 'platform' && path === '/platform/sessions/messages')
      return {
        data: {
          tenant_id: summary.tenant_id,
          session_id: summary.session_id,
          root_input_id: summary.root_input_id,
          items: [],
          next_cursor: null,
          read_at: time
        }
      }
    if (kind === 'platform' && path === '/platform/sessions/usage') return { data: detail.usage }
    return undefined
  })
  return {
    ...fixture,
    summary,
    detail,
    nodes,
    setNodesFailure(value: boolean) {
      failNodes = value
    }
  }
}
