import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { fullPermissions, mockConsole } from './support/mock-console'

const root = '/builder/workflows/test-sessions'
const workflowId = 'workflow-test-browser'
const ids = {
  test: '9223372036854775801',
  session: '9223372036854775802',
  agent: '9223372036854775803',
  user: '9223372036854775804',
  input: '9223372036854775805',
  run: '9223372036854775806'
}
const parameters = { customer: '测试客户', limit: 0, urgent: false }
const inputSchema = {
  type: 'object',
  properties: {
    customer: { type: 'string', title: '客户名称', default: parameters.customer },
    limit: { type: 'integer', title: '处理数量', default: 0, minimum: 0 },
    urgent: { type: 'boolean', title: '紧急处理', default: false }
  },
  required: ['customer', 'limit', 'urgent']
}
const recommended = {
  max_business_model_calls: 1,
  max_tool_calls: 1,
  max_knowledge_calls: 1,
  max_total_tokens: 1024,
  max_wall_time_seconds: 60
}
function definition(message = '原始业务回复') {
  return {
    version: '1.0',
    entry: 'start',
    nodes: [
      { id: 'start', type: 'start', name: '开始' },
      {
        id: 'confirm',
        type: 'human_input',
        name: '确认测试数据',
        message: '请确认本次测试数据',
        result_to: 'state.confirmation'
      },
      { id: 'reply', type: 'output', name: '业务回复', message },
      { id: 'end', type: 'end', name: '结束' }
    ],
    edges: [
      { from: 'start', to: 'confirm' },
      { from: 'confirm', to: 'reply' },
      { from: 'reply', to: 'end' }
    ],
    meta: {
      name: workflowId,
      display_name: '工作流测试会话验收',
      version: '1.0.0',
      description: '所有业务响应均来自本地浏览器夹具',
      capability: {
        model_invocable: true,
        user_invocable: true,
        input_schema: inputSchema,
        output_schema: { type: 'object', properties: {} },
        supports_hitl: true,
        export_fields: [],
        timeout_ms: 30000,
        concurrency_safe: false
      }
    }
  }
}
async function settleFeedback(page: Page) {
  await page.evaluate(
    () => new Promise<void>((resolve) => setTimeout(() => setTimeout(resolve, 0), 0))
  )
}
async function evidence(page: Page, info: TestInfo, name: string) {
  const path = info.outputPath(`${name}.png`)
  await page.screenshot({ path, fullPage: true, animations: 'disabled' })
  await info.attach(name, { path, contentType: 'image/png' })
}
async function assertTheme(page: Page, info: TestInfo) {
  if (info.project.use.colorScheme === 'dark')
    await expect(page.locator('html')).toHaveClass(/dark/)
  else await expect(page.locator('html')).not.toHaveClass(/dark/)
}
async function fixture(
  page: Page,
  info: TestInfo,
  unknownCreate = false,
  candidateScenario = false
) {
  const mock = await mockConsole(page, 'admin', [
    ...fullPermissions,
    'foundation.preferences.manage'
  ])
  let draft = {
    workflow_id: workflowId,
    status: 'draft',
    revision: 7,
    active_definition_version_id: null,
    definition: definition(),
    problems: []
  }
  let submitted: Record<string, unknown> | undefined
  let candidateVersion = 'old'
  let chatSubmitted = false
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const source = () => ({
    kind: 'draft',
    workflow_id: workflowId,
    draft_revision: draft.revision,
    definition_version_id: null,
    hash: `mock-frozen-source-${draft.revision}`
  })
  const initialMessage = () => '服务端已冻结的测试输入：' + String(submitted?.user_input ?? '')
  const receipt = () => ({
    test_session_id: ids.test,
    request_id: submitted?.request_id,
    status: chatSubmitted ? 'waiting' : 'ready',
    source: source(),
    session_id: ids.session,
    agent_id: ids.agent,
    end_user_id: ids.user,
    initial_input_id: ids.input,
    initial_message: initialMessage(),
    expires_at: '2099-10-24T09:30:00Z',
    run_id: chatSubmitted ? ids.run : null,
    run_status: chatSubmitted ? 'WAITING_HITL' : null,
    close_status: 'open',
    blocking_reason: null
  })
  const mutations = () => mock.requests.filter((request) => request.method !== 'GET')
  mock.setHook(async (request, url) => {
    const path = url.pathname.slice('/api/v1'.length),
      method = request.method()
    if (
      method === 'GET' &&
      (path === '/admin/foundation/catalog/preferences' ||
        path === '/tenant/foundation/catalog/preferences')
    )
      return {
        data: {
          theme: info.project.use.colorScheme,
          locale: 'zh-CN',
          page_size: 20,
          compact_navigation: false,
          revision: 7
        }
      }
    if (method === 'GET' && path === '/builder/workflows/draft') return { data: draft }
    if (method === 'PUT' && path === '/builder/workflows/draft') {
      expect(request.headers()['if-match']).toBe(String(draft.revision))
      const command = request.postDataJSON()
      expect(command.workflow_id).toBe(workflowId)
      expect(
        command.payload.definition.nodes.find((node: { id: string }) => node.id === 'reply').message
      ).toBe('先保存后测试的业务回复')
      draft = { ...draft, revision: draft.revision + 1, definition: command.payload.definition }
      return { data: draft }
    }
    if (path === '/builder/workflows/draft/snapshot') {
      const layout = {
        start: { x: 40, y: 30 },
        confirm: { x: 40, y: 180 },
        reply: { x: 40, y: 330 },
        end: { x: 40, y: 480 }
      }
      if (method === 'GET')
        return {
          data: {
            workflow_id: workflowId,
            draft_revision: draft.revision,
            layout,
            viewport: { zoom: 1, panX: 24, panY: 24 }
          }
        }
      if (method === 'PUT') {
        expect(request.headers()['if-match']).toBe(String(draft.revision))
        return {
          data: {
            workflow_id: workflowId,
            draft_revision: draft.revision,
            ...request.postDataJSON().payload
          }
        }
      }
    }
    if (method === 'GET' && path === '/builder/workflows/history') return { data: { items: [] } }
    if (method === 'GET' && path === root + '/options') {
      expect(url.searchParams.get('workflowId')).toBe(workflowId)
      expect(url.searchParams.get('draftRevision')).toBe(String(draft.revision))
      return {
        data: {
          source: source(),
          input_schema: inputSchema,
          config_schema: [],
          capability_slots: candidateScenario
            ? [
                {
                  slot: 'lookup',
                  required: true,
                  type: 'tool',
                  candidates: [
                    {
                      capability: {
                        type: 'TOOL',
                        capabilityId: 'tool',
                        versionId: candidateVersion
                      },
                      tool_deployment_id: 'test-deployment',
                      tool_deployment_revision: 1,
                      knowledge_revision: null,
                      label: candidateVersion === 'old' ? '旧查询服务' : '新查询服务'
                    }
                  ]
                }
              ]
            : [],
          budget: {
            recommended,
            maximum: {
              max_business_model_calls: 3,
              max_tool_calls: 3,
              max_knowledge_calls: 3,
              max_total_tokens: 4096,
              max_wall_time_seconds: 120
            }
          },
          restrictions: ['需要用户回复时，测试会暂停等待'],
          cost_notice: '夹具费用提示：实际运行可能调用模型或工具并产生费用'
        }
      }
    }
    if (method === 'POST' && path === root) {
      expect(submitted).toBeUndefined()
      submitted = request.postDataJSON() as Record<string, unknown>
      expect(submitted.workflow_id).toBe(workflowId)
      expect(submitted.source).toEqual({ kind: 'draft', draft_revision: draft.revision })
      expect(submitted.parameters).toEqual(parameters)
      expect(submitted.config).toEqual({})
      expect(submitted.bindings).toEqual({ capabilities: [], workflows: [], agents: [] })
      expect(submitted.budget).toEqual(recommended)
      expect(submitted.request_id).toEqual(expect.any(String))
      return unknownCreate
        ? { status: 503, code: 'WORKFLOW_TEST_CREATE_UNKNOWN' }
        : { data: receipt() }
    }
    if (method === 'GET' && path === root + '/by-request') {
      expect(submitted).toBeDefined()
      expect(url.searchParams.get('requestId')).toBe(submitted?.request_id)
      return { data: receipt() }
    }
    if (method === 'GET' && path === root) {
      expect(url.searchParams.get('testSessionId')).toBe(ids.test)
      return { data: receipt() }
    }
    if (method === 'POST' && path === '/chat') {
      expect(submitted).toBeDefined()
      expect(chatSubmitted).toBe(false)
      expect(request.postDataJSON()).toMatchObject({
        session_id: ids.session,
        agent_id: ids.agent,
        client_message_id: ids.input,
        content: [{ type: 'text', text: initialMessage() }],
        context: { end_user_id: ids.user }
      })
      expect(request.postDataJSON()).not.toHaveProperty('message')
      chatSubmitted = true
      return {
        data: {
          status: 'ACCEPTED',
          session_id: ids.session,
          execution_id: ids.run,
          input_id: ids.input,
          replayed: false
        }
      }
    }
    if (method === 'GET' && path === '/chat/history') {
      expect(url.searchParams.get('session_id')).toBe(ids.session)
      return {
        data: {
          session_id: ids.session,
          event_cursor: 'mock-event-3',
          has_more: false,
          next_before_message_id: null,
          collaboration: null,
          processes: [],
          activities: [],
          messages: chatSubmitted
            ? [
                {
                  message_id: ids.input,
                  message_version: 1,
                  references: [],
                  run_id: ids.run,
                  role: 'user',
                  content: initialMessage(),
                  created_at: '2026-10-24T09:30:00Z',
                  metadata: {}
                },
                {
                  message_id: 'mock-visible-message',
                  message_version: 1,
                  references: [],
                  run_id: ids.run,
                  role: 'assistant',
                  content: '测试已进入等待确认阶段',
                  created_at: '2026-10-24T09:30:01Z',
                  metadata: { phase: 'commentary' }
                }
              ]
            : [],
          pending_interactions: chatSubmitted
            ? [
                {
                  interaction_type: 'workflow_input',
                  execution_id: ids.run,
                  run_revision: '2',
                  workflow_execution_id: 'mock-workflow-execution',
                  suspend_ref: 'mock-suspend-ref',
                  execution_revision: '3',
                  resume_action_key: 'confirm',
                  tool_wait: { action_id: 'mock-wait-action', generation: '1' },
                  question: '请确认本次测试数据',
                  card: {
                    card_id: 'mock-pending-card',
                    type: 'form',
                    title: '测试待办确认',
                    body: '请确认本次测试数据',
                    actions: [{ id: 'approve', label: '确认继续', type: 'submit' }]
                  }
                }
              ]
            : []
        }
      }
    }
    if (method === 'GET' && path === '/chat/trajectories/detail') {
      expect(url.searchParams.get('session_id')).toBe(ids.session)
      expect(url.searchParams.get('root_input_id')).toBe(ids.input)
      return {
        data: {
          summary: { session_id: ids.session, root_input_id: ids.input },
          usage_summary: {
            calls: 0,
            tokens: '0',
            prompt_tokens: '0',
            completion_tokens: '0',
            amount_by_currency: {},
            unknown_usage_calls: 0,
            cost_coverage: {
              status: 'NO_CALLS',
              total_events: 0,
              priced_events: 0,
              unconfirmed_events: 0,
              missing_model_attempts: 0
            }
          },
          diagnostics: {
            context_measurements: [],
            runtime_context_measurements: [],
            runtime_stage_measurements: [],
            runtime_stage_links: [],
            runtime_stream_milestones: []
          }
        }
      }
    }
    return undefined
  })
  return {
    ...mock,
    errors,
    mutations,
    submitted: () => submitted,
    draft: () => draft,
    changeCandidate: () => {
      candidateVersion = 'new'
    }
  }
}
const panel = (page: Page) => page.getByRole('dialog', { name: '工作流测试', exact: true })
async function openTest(page: Page) {
  await page.getByRole('button', { name: '测试', exact: true }).click()
  await expect(panel(page)).toBeVisible()
  await expect(panel(page)).toBeInViewport()
  const bounds = await panel(page).boundingBox()
  expect(bounds).not.toBeNull()
  expect(bounds!.width).toBeLessThanOrEqual(page.viewportSize()!.width + 0.01)
  for (const content of [panel(page), panel(page).locator('.el-drawer__body')]) {
    const dimensions = await content.evaluate((element) => ({
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth
    }))
    expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth)
  }
}
async function closePanel(page: Page) {
  await panel(page).locator('.el-drawer__close-btn').click()
  await expect(panel(page)).toBeHidden()
}
async function assertDefaults(page: Page) {
  await expect(panel(page).getByLabel('客户名称', { exact: true })).toHaveValue('测试客户')
  await expect(panel(page).getByLabel('处理数量', { exact: true })).toHaveValue('0')
  const urgent = page.getByRole('combobox', { name: '紧急处理', exact: true })
  await expect(panel(page).getByRole('combobox', { name: '紧急处理', exact: true })).toBeVisible()
  await expect(panel(page).locator('.el-select').filter({ has: urgent })).toContainText('否')
}
async function assertWaiting(page: Page) {
  await expect(panel(page)).toContainText('等待回复')
  await expect(panel(page)).toContainText('测试待办确认')
  await expect(panel(page)).toContainText('请确认本次测试数据')
  await expect(panel(page).getByRole('button', { name: '确认继续', exact: true })).toBeEnabled()
  await expect(
    panel(page).getByRole('textbox', { name: '待办回复 ' + ids.run, exact: true })
  ).toBeEnabled()
}

test('测试候选变化后提示并要求重新选择，保留测试消息', async ({ page }, info) => {
  const mock = await fixture(page, info, false, true)
  await page.goto('/admin/workflows/' + workflowId)
  await openTest(page)
  const picker = panel(page).getByRole('combobox', { name: '测试能力 lookup' })
  await expect(panel(page).getByText('旧查询服务')).toBeVisible()
  await panel(page).getByRole('textbox', { name: '测试消息', exact: true }).fill('保留测试内容')
  await closePanel(page)
  mock.changeCandidate()
  await openTest(page)
  await expect(panel(page)).toContainText('之前选择的测试候选已不可用，请重新选择。')
  await expect(panel(page).getByRole('textbox', { name: '测试消息', exact: true })).toHaveValue(
    '保留测试内容'
  )
  await picker.fill('新查询')
  await page.getByRole('option', { name: '新查询服务', exact: true }).click()
  await expect(panel(page)).not.toContainText('之前选择的测试候选已不可用')
  expect(mock.mutations()).toHaveLength(0)
  mock.assertHandled()
})

test('保存dirty来源与schema默认值后只发一次正式chat，真实待办与抽屉重开保持状态', async ({
  page
}, info) => {
  const mock = await fixture(page, info)
  await page.goto('/admin/workflows/' + workflowId)
  await assertTheme(page, info)
  await openTest(page)
  await assertDefaults(page)
  const message = '请按此草稿发起测试，并等待我确认'
  await panel(page).getByRole('textbox', { name: '测试消息', exact: true }).fill(message)
  await closePanel(page)
  await page.getByRole('combobox', { name: '定位节点', exact: true }).click()
  await page.getByRole('option', { name: '业务回复 · reply', exact: true }).click()
  await page.getByRole('textbox', { name: '输出消息', exact: true }).fill('先保存后测试的业务回复')
  await page.getByRole('button', { name: '收起配置', exact: true }).click()
  await openTest(page)
  await expect(panel(page).getByRole('textbox', { name: '测试消息', exact: true })).toHaveValue(
    message
  )
  await expect(panel(page)).toContainText('当前草稿有未保存修改，请先保存后测试')
  await expect(panel(page).getByRole('button', { name: '创建并测试', exact: true })).toBeDisabled()
  await panel(page).getByRole('button', { name: '先保存草稿', exact: true }).click()
  await expect.poll(() => mock.draft().revision).toBe(8)
  await expect(panel(page).getByRole('button', { name: '创建并测试', exact: true })).toBeEnabled()
  await assertDefaults(page)
  await panel(page).getByRole('button', { name: '创建并测试', exact: true }).click()
  await assertWaiting(page)
  const traceLink = panel(page).getByRole('link', { name: '查看完整执行追踪与用量（新标签页）' })
  await expect(traceLink).toHaveAttribute(
    'href',
    `/admin/chat/sessions/${ids.session}/trace?root_input_id=${ids.input}`
  )
  await expect(traceLink).toHaveAttribute('target', '_blank')
  await page
    .context()
    .route('**/api/v1/**', (route) => route.fulfill({ status: 503, body: 'fixture only' }))
  const [tracePage] = await Promise.all([page.waitForEvent('popup'), traceLink.click()])
  await expect(tracePage).toHaveURL(
    new RegExp(`/admin/chat/sessions/${ids.session}/trace\\?root_input_id=${ids.input}`)
  )
  await tracePage.close()
  await assertWaiting(page)
  expect(mock.submitted()?.user_input).toBe(message)
  expect(mock.mutations().map((request) => request.path)).toEqual([
    '/builder/workflows/draft',
    '/builder/workflows/draft/snapshot',
    root,
    '/chat'
  ])
  await closePanel(page)
  await openTest(page)
  await assertWaiting(page)
  await expect(
    panel(page).getByRole('region', { name: '测试消息记录', exact: true })
  ).toContainText('服务端已冻结的测试输入：' + message)
  expect(mock.mutations().filter((request) => request.path === '/chat')).toHaveLength(1)
  expect(mock.mutations().filter((request) => request.path === root + '/close')).toHaveLength(0)
  await evidence(page, info, 'workflow-test-waiting-retained')
  expect(mock.errors).toEqual([])
  mock.assertHandled()
})

test('创建结果未知只有单一提示，只查原request不重建，关闭重开后显式开始既有测试', async ({
  page
}, info) => {
  const mock = await fixture(page, info, true)
  await page.goto('/admin/workflows/' + workflowId)
  await assertTheme(page, info)
  await openTest(page)
  await assertDefaults(page)
  const message = '创建结果未知时，请保留本次输入和原请求'
  await panel(page).getByRole('textbox', { name: '测试消息', exact: true }).fill(message)
  await panel(page).getByRole('button', { name: '创建并测试', exact: true }).click()
  const unknown = panel(page)
    .locator('.el-alert:visible')
    .filter({ hasText: /结果尚未确认/ })
  await expect(unknown).toHaveCount(1)
  await expect(panel(page).locator('.el-alert--error:visible')).toHaveCount(0)
  await settleFeedback(page)
  await expect(page.locator('.el-message:visible')).toHaveCount(0)
  expect(mock.mutations().map((request) => request.path)).toEqual([root])
  const requestId = mock.submitted()?.request_id
  await evidence(page, info, 'workflow-test-create-unknown')
  await closePanel(page)
  await openTest(page)
  await expect(
    panel(page).getByRole('button', { name: '开始已创建的测试', exact: true })
  ).toBeVisible()
  expect(mock.submitted()?.user_input).toBe(message)
  expect(
    mock.requests
      .filter((request) => request.path === root + '/by-request')
      .map((request) => new URLSearchParams(request.query).get('requestId'))
  ).toEqual([requestId])
  await panel(page).getByRole('button', { name: '查询测试状态', exact: true }).click()
  expect(mock.mutations().map((request) => request.path)).toEqual([root])
  await panel(page).getByRole('button', { name: '开始已创建的测试', exact: true }).click()
  await assertWaiting(page)
  await expect(
    panel(page).getByRole('region', { name: '测试消息记录', exact: true })
  ).toContainText('服务端已冻结的测试输入：' + message)
  expect(mock.mutations().map((request) => request.path)).toEqual([root, '/chat'])
  await settleFeedback(page)
  await expect(page.locator('.el-message:visible')).toHaveCount(0)
  expect(mock.errors).toEqual([])
  mock.assertHandled()
})
