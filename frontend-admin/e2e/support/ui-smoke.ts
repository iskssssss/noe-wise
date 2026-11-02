import { expect, type Locator, type Page, type TestInfo } from '@playwright/test'
import { fullPermissions, mockConsole } from './mock-console'
import type { NavigationItem } from '@skillforge/shared/foundation/navigation'

/** Explicit read projections for this UI run; every other API still fails closed. */
export async function mockUiSmoke(
  page: Page,
  kind: 'admin' | 'platform',
  theme: 'light' | 'dark',
  options: { navigation?: NavigationItem[]; allowPreferenceWrites?: boolean } = {}
) {
  const fixture = await mockConsole(page, kind, [
    ...fullPermissions,
    'foundation.preferences.manage',
    'foundation.settings.read'
  ])
  const time = '2026-10-24T09:30:00+08:00'
  const tenant = '9007199254740993',
    session = 'session-ui-9007199254740993',
    root = 'input-ui-1',
    run = 'run-ui-1'
  const totals = {
    prompt_tokens: '9007199254740993',
    completion_tokens: '240',
    cached_input_tokens: '1000',
    cache_write_input_tokens: null,
    reasoning_output_tokens: null
  }
  const coverage = {
    status: 'PARTIAL',
    total_attempts: 2,
    reported_attempts: 1,
    unknown_attempts: 1,
    partial_attempts: 0,
    zero_usage_attempts: 0
  }
  const title = '客户服务方案复核：需要确认的结果与后续步骤'
  const summary = {
    tenant_id: tenant,
    tenant_name: '灵知演示机构',
    session_id: session,
    user_id: 'user-0',
    user_name: '用户-00',
    agent_id: fixture.agents[1]!.id,
    agent_name: fixture.agents[1]!.name,
    team_id: null,
    channel_type: 'web',
    answer_status: 'COMPLETED',
    execution_status: 'OUTCOME_UNKNOWN',
    summary: title,
    message_count: 3,
    root_input_count: 1,
    created_at: time,
    updated_at: time,
    last_activity_at: time
  }
  const messages = [
    {
      message_id: 'question',
      role: 'user',
      content: '请检查客户服务方案，并列出需要我确认的事项。',
      root_input_id: root,
      run_id: run,
      phase: null,
      state: 'completed',
      message_version: '1',
      created_at: time
    },
    {
      message_id: 'commentary',
      role: 'assistant',
      content: '已检查方案与公开记录，正在整理需要确认的事项。',
      root_input_id: root,
      run_id: run,
      phase: 'commentary',
      state: 'completed',
      message_version: '1',
      created_at: time
    },
    {
      message_id: 'answer',
      role: 'assistant',
      content:
        '方案复核已完成。\n\n1. 服务范围和交付时间已记录。\n2. 外部提交结果尚未确认，请核实回执后再继续，避免重复提交。\n3. 请确认下一阶段的联系人与预算。',
      root_input_id: root,
      run_id: run,
      phase: 'final',
      state: 'completed',
      message_version: '2',
      created_at: time
    }
  ]
  const rounds = [
    {
      root_input_id: root,
      round_number: '1',
      first_sequence: '1',
      last_sequence: '4',
      answer_status: 'COMPLETED',
      execution_status: 'OUTCOME_UNKNOWN',
      started_at: time,
      updated_at: time,
      run_count: 1,
      runs: [{ run_id: run, loops: [] }]
    }
  ]
  const history = {
    session_id: session,
    end_user_id: 'user-0',
    agent_id: fixture.agents[1]!.id,
    target: { agent_id: fixture.agents[1]!.id },
    messages: messages.map((message) => ({ ...message, metadata: { phase: message.phase } })),
    has_more: false,
    next_before_message_id: null,
    event_cursor: 'ui-event-4',
    activities: [],
    processes: [],
    pending_interactions: [
      {
        execution_id: run,
        run_revision: '3',
        interaction_type: 'user_input',
        title: '请确认后续联系人',
        question: '请提供下一阶段的联系人。外部提交仍需核实回执，请不要重复提交。',
        tool_wait: { action_id: 'action-wait', generation: '1' }
      }
    ]
  }
  let personal = { theme, locale: 'zh-CN', page_size: 20, compact_navigation: false, revision: 7 }
  fixture.setHook(async (request, url) => {
    const path = url.pathname.slice('/api/v1'.length)
    if (
      request.method() === 'PUT' &&
      path.endsWith('/foundation/catalog/preferences') &&
      options.allowPreferenceWrites
    ) {
      const command = request.postDataJSON()
      expect(command.expected_revision).toBe(personal.revision)
      expect(command.theme).toBe(theme)
      expect(command.locale).toBe('zh-CN')
      expect(command.page_size).toBe(20)
      expect(typeof command.compact_navigation).toBe('boolean')
      personal = {
        ...personal,
        compact_navigation: command.compact_navigation,
        revision: personal.revision + 1
      }
      return { data: personal }
    }
    if (request.method() !== 'GET') return undefined
    if (path.endsWith('/foundation/catalog/navigation/current') && options.navigation)
      return { data: options.navigation }
    if (path.endsWith('/foundation/catalog/preferences'))
      return {
        data: personal
      }
    if (path.endsWith('/foundation/catalog/branding'))
      return {
        data: {
          display_name: '灵知演示机构',
          logo_url: null,
          primary_color: '#0f766e',
          contact_email: 'support@example.test',
          support_url: null,
          revision: 2
        }
      }
    if (path.endsWith('/foundation/catalog/navigation/current'))
      return {
        data: [
          {
            title: '工作空间',
            path: null,
            permission: null,
            children: (kind === 'admin'
              ? [
                  ['智能体', 'agents'],
                  ['记忆管理', 'memory'],
                  ['会话记录', 'sessions'],
                  ['智能体调试', 'chat'],
                  ['展示设置', 'foundation/catalog']
                ]
              : [
                  ['机构管理', 'tenants'],
                  ['模型管理', 'models'],
                  ['会话观察', 'sessions'],
                  ['展示设置', 'foundation/catalog']
                ]
            ).map(([title, path]) => ({
              title,
              path: `/${kind}/${path}`,
              permission: null,
              children: []
            }))
          }
        ]
      }
    if (path === '/admin/models/detail')
      return {
        data: {
          ...fixture.models[0],
          endpoint: 'https://models.example.test/v1',
          currency: 'CNY',
          credential_configured: true,
          context_window_tokens: 131072,
          max_output_tokens: 8192,
          supported_max_output_tokens: 16384,
          timeout_seconds: 600,
          reasoning: 'DEFAULT',
          input_price_per_million_cents: '10.000000',
          output_price_per_million_cents: '20.000000'
        }
      }
    if (path === '/marketplace/reviews') return { data: { total: 4, items: [] } }
    if (path === '/knowledge/bases' || path === '/channels')
      return { data: { total: 3, items: [] } }
    if (path === '/teams') return { data: [] }
    if (path === '/chat/sessions') return { data: [summary] }
    if (path === '/chat/sessions/detail')
      return {
        data: {
          session: summary,
          agent: fixture.agents[1],
          executions: [],
          tasks: [],
          callbacks: []
        }
      }
    if (path === '/chat/history') return { data: history }
    if (path === '/chat/work/history') return { data: { items: [] } }
    if (path === '/chat/work') return { data: null }
    if (path === '/chat/sessions/trace')
      return {
        data: {
          session_id: session,
          usage_summary: {
            calls: 2,
            tokens: null,
            unknown_usage_calls: 1,
            amount_by_currency: { CNY: '0.010000' }
          },
          rounds: [
            {
              root_input_id: root,
              runs: [
                {
                  run_id: run,
                  steps: [
                    {
                      step_id: 'step-1',
                      phases: [
                        {
                          event_id: 'event-1',
                          kind: '模型调用',
                          status: '已记录',
                          occurred_at: time,
                          detail: { model: '服务方案模型', report_status: 'PARTIAL' },
                          content_ref: null
                        }
                      ]
                    }
                  ]
                }
              ]
            }
          ],
          diagnostics: { usage_measurements: [] },
          next_cursor: null
        }
      }
    if (path === '/platform/sessions')
      return { data: { items: [summary], page: 1, page_size: 20, total: 1, read_at: time } }
    if (path === '/platform/sessions/detail')
      return { data: { ...summary, usage_snapshot: totals, active_execution_id: null } }
    if (path === '/platform/sessions/rounds')
      return {
        data: {
          tenant_id: tenant,
          session_id: session,
          items: rounds,
          next_cursor: null,
          read_at: time
        }
      }
    if (path === '/platform/sessions/messages')
      return {
        data: {
          tenant_id: tenant,
          session_id: session,
          root_input_id: root,
          items: messages,
          next_cursor: null,
          read_at: time
        }
      }
    if (path === '/platform/sessions/trace')
      return {
        data: {
          tenant_id: tenant,
          session_id: session,
          root_input_id: root,
          items: [],
          loop_groups: [],
          unassociated_usage_summary: null,
          sequence_upper_bound: '4',
          next_cursor: null,
          read_at: time
        }
      }
    if (path === '/platform/sessions/usage')
      return {
        data: {
          tenant_id: tenant,
          session_id: session,
          root_input_id: root,
          totals,
          coverage,
          cache: { availability: 'UNKNOWN', hit_ratio: null },
          breakdowns: [],
          attempts: [
            {
              attempt_id: 'attempt-ui-1',
              invocation_id: 'invocation-ui-1',
              run_id: run,
              trace_node_id: null,
              provider: '演示提供方',
              model: '服务方案模型',
              call_point: 'agent',
              report_status: 'PARTIAL',
              usage_source: 'PROVIDER',
              totals,
              ingested_at: time
            }
          ],
          next_cursor: null,
          read_at: time
        }
      }
    return undefined
  })
  // A finite controlled observation with a long retry period; no business effect is mocked as success.
  await page.route('**/api/v1/chat/events?*', async (route) => {
    fixture.requests.push({
      method: route.request().method(),
      path: '/chat/events',
      query: new URL(route.request().url()).search,
      body: null
    })
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      headers: { 'Retry-After': '60' },
      body: 'retry: 60000\n: controlled mock observation\n\n'
    })
  })
  return { ...fixture, session, title, tenant }
}

export async function captureUi(page: Page, info: TestInfo, name: string, focus?: Locator) {
  if (focus) await focus.scrollIntoViewIfNeeded()
  else await page.evaluate(() => window.scrollTo(0, 0))
  await page.evaluate(() => document.fonts.ready)
  await expect(page.locator('.el-loading-mask')).toHaveCount(0)
  const overflow = await page.evaluate(() => ({
    width: window.innerWidth,
    content: document.documentElement.scrollWidth
  }))
  expect(overflow.content, `page overflow in ${name}`).toBeLessThanOrEqual(overflow.width + 2)
  await page.screenshot({
    path: info.outputPath(`${name}.png`),
    fullPage: false,
    animations: 'disabled'
  })
}
