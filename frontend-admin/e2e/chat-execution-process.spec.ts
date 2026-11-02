import { test, expect, type Page, type Response } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { readFileSync } from 'node:fs'
import { resolve, join } from 'node:path'

/** 只接收Java隔离夹具；live文字沿用已授权唯一标记，不增加模型调用或预算。 */
function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少隔离Chat浏览器夹具：${name}`)
  return value
}
/** 用真实响应作为ready信号，不按固定sleep推断登录、受理或持久恢复已经完成。 */
function responseAt(page: Page, path: string, method = 'GET') {
  return page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === path && response.request().method() === method
  )
}
/** 传输成功必须同时满足HTTP和公共信封，错误输出只记录非敏感路径。 */
async function dataOf(response: Response) {
  expect(response.ok(), `HTTP ${response.status()}：${new URL(response.url()).pathname}`).toBe(true)
  const result = await response.json()
  expect(result.code).toBe('OK')
  expect(result.trace_id).toBeTruthy()
  return result.data
}

// 本lane可能接真实模型；单次运行失败交由Java记录，浏览器层禁止自动重发输入。
test.describe.configure({ retries: 0 })

test('真实机构登录发送一次并恢复持久公开回复，不展示内部推理', async ({ page }) => {
  test.setTimeout(180_000)
  const corpus = JSON.parse(
    readFileSync(
      resolve(process.cwd(), '../tools/full_project_testing/fixtures/user_prompts.json'),
      'utf8'
    )
  ) as { scenarios: { id: string; turns: { text: string }[] }[] }
  const scenario = corpus.scenarios.find((item) => item.id === 'CHAT-PROMPT-029')
  expect(scenario).toBeTruthy()
  const live = process.env.E2E_LIVE_MODEL === '1'
  const message = live ? required('E2E_MESSAGE') : scenario!.turns[0]!.text
  const expectedReply = required('E2E_EXPECTED_REPLY')
  const errors: string[] = []
  let submissions = 0
  let sessionCreations = 0
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/chat')
      submissions++
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/chat/sessions/create-with-receipt'
    )
      sessionCreations++
  })
  await page.goto('/login?redirect=/admin/chat')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  const usersReady = responseAt(page, '/api/v1/chat/end-users')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/chat$/)
  const users = await dataOf(await usersReady)
  expect(users.length).toBeGreaterThan(0)
  const user = users.find(
    (item: { display_name: string }) => item.display_name === 'Runtime E2E User'
  )
  expect(user).toBeTruthy()
  await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
  await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
  // 隔离夹具只有一个可用对象，页面会自动选中；选中后不再显示占位文案。
  const target = page.locator('.el-select').filter({ hasText: 'Runtime E2E Agent' })
  await expect(target.locator('.el-select__placeholder')).toHaveText('Runtime E2E Agent')
  const input = page.getByPlaceholder('输入消息', { exact: true })
  await input.fill(message)
  const created = responseAt(page, '/api/v1/chat/sessions/create-with-receipt', 'POST')
  const accepted = responseAt(page, '/api/v1/chat', 'POST')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const session = await dataOf(await created)
  const receiptResponse = await accepted
  const receipt = await dataOf(receiptResponse)
  const submitted = receiptResponse.request().postDataJSON()
  const sessionId = String(session.session_id)
  const runId = String(receipt.execution_id ?? '')
  const inputId = String(submitted.client_message_id)
  expect(submitted.session_id).toBe(sessionId)
  expect(String(submitted.context.end_user_id)).toBe(String(user.id))
  expect(submitted.content[0].text).toBe(message)
  expect(inputId).toBeTruthy()
  expect(runId).toBeTruthy()
  await expect(page.locator('.debug-messages')).toContainText(expectedReply, { timeout: 120_000 })
  await expect(page.locator('.debug-message').filter({ hasText: expectedReply })).toHaveCount(1)
  await expect(page.locator('body')).not.toContainText(required('E2E_INTERNAL_REASONING'))
  expect(submissions).toBe(1)

  // 刷新后自动以原会话身份读取持久历史，禁止再次 POST 业务输入。
  const historyReady = responseAt(page, '/api/v1/chat/sessions')
  const restored = responseAt(page, '/api/v1/chat/history')
  await page.reload()
  const sessions = await dataOf(await historyReady)
  const selected = sessions.find((item: { session_id: string }) => item.session_id === sessionId)
  expect(selected).toBeTruthy()
  const evidence = required('E2E_ARTIFACT_DIR')
  await mkdir(evidence, { recursive: true })
  const history = await dataOf(await restored)
  expect(history.session_id).toBe(sessionId)
  expect(history.messages.filter((item: { role: string }) => item.role === 'user')).toHaveLength(1)
  await expect(page.locator('.debug-message').filter({ hasText: expectedReply })).toHaveCount(1)
  await page.screenshot({ path: join(evidence, 'session-reload-restored.png'), fullPage: true })
  await writeFile(
    join(evidence, 'session-reload-restored.json'),
    JSON.stringify(
      { sessionId, restoredAutomatically: true, manualSelectionActions: 0, submissions },
      null,
      2
    )
  )
  await expect(page.locator('body')).not.toContainText(required('E2E_INTERNAL_REASONING'))
  expect(submissions).toBe(1)
  expect(sessionCreations).toBe(1)
  expect(errors).toEqual([])
  await page.screenshot({ path: join(evidence, 'chat-public-history.png'), fullPage: true })
  await writeFile(
    join(evidence, 'browser-receipt.json'),
    JSON.stringify(
      {
        sessionId,
        runId,
        inputId,
        submissions,
        restoredFromRealHistory: true,
        bootstrapMocked: false,
        promptScenario: live ? null : scenario!.id,
        promptTurnsConsumed: live ? [] : [0],
        live,
        errors
      },
      null,
      2
    )
  )
})

test('智能体试用刷新恢复当前会话，重新进入时开启新试用', async ({ page }) => {
  test.setTimeout(180_000)
  const agentId = required('E2E_AGENT_ID')
  const expectedReply = required('E2E_EXPECTED_REPLY')
  let submissions = 0
  let historyReads = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/chat')
      submissions++
    if (request.method() === 'GET' && new URL(request.url()).pathname === '/api/v1/chat/history')
      historyReads++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agentId)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/agents\//)
  const usersReady = responseAt(page, '/api/v1/chat/end-users')
  await page.getByRole('button', { name: '试用当前发布版本' }).click()
  await expect(page).toHaveURL(/\/admin\/chat\?agent_id=/)
  const users = await dataOf(await usersReady)
  expect(
    users.some((user: { display_name: string }) => user.display_name === 'Runtime E2E User')
  ).toBe(true)
  await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
  await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
  await page.getByPlaceholder('输入消息', { exact: true }).fill('请简短说明当前系统能力。')
  const created = responseAt(page, '/api/v1/chat/sessions/create-with-receipt', 'POST')
  const accepted = responseAt(page, '/api/v1/chat', 'POST')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const session = await dataOf(await created)
  await dataOf(await accepted)
  await expect(page.locator('.debug-message').filter({ hasText: expectedReply })).toHaveCount(1, {
    timeout: 120_000
  })
  const historyList = responseAt(page, '/api/v1/chat/sessions')
  const restored = responseAt(page, '/api/v1/chat/history')
  await page.reload()
  const sessions = await dataOf(await historyList)
  const sessionId = String(session.session_id)
  const selected = sessions.find((item: { session_id: string }) => item.session_id === sessionId)
  expect(selected).toBeTruthy()
  expect((await dataOf(await restored)).session_id).toBe(sessionId)
  await expect(page.locator('.debug-message').filter({ hasText: expectedReply })).toHaveCount(1)
  const evidence = required('E2E_ARTIFACT_DIR')
  await page.screenshot({ path: join(evidence, 'trial-refresh-restored.png'), fullPage: true })
  const readsAfterReload = historyReads
  await page.goto('/admin/agents/' + agentId)
  const newUsersReady = responseAt(page, '/api/v1/chat/end-users')
  await page.getByRole('button', { name: '试用当前发布版本' }).click()
  await expect(page).toHaveURL(/\/admin\/chat\?agent_id=/)
  await dataOf(await newUsersReady)
  await expect(page.getByText('已预选智能体的当前发布版本', { exact: false })).toBeVisible()
  await expect(page.locator('.debug-message').filter({ hasText: expectedReply })).toHaveCount(0)
  expect(historyReads).toBe(readsAfterReload)
  await writeFile(
    join(evidence, 'trial-refresh-restored.json'),
    JSON.stringify(
      {
        sessionId,
        agentId,
        restoredAutomatically: true,
        manualSelectionActions: 0,
        freshTrialHasNoPriorHistory: true,
        submissions
      },
      null,
      2
    )
  )
  expect(submissions).toBe(1)
})

test('新工作流一次操作保存并打开测试配置', async ({ page }) => {
  test.setTimeout(120_000)
  await page.goto('/login?redirect=/admin/workflows/create')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/workflows\/create$/)
  const workflowId = `test-flow-${Date.now()}`
  await page
    .locator('.el-form-item')
    .filter({ hasText: '业务标识' })
    .locator('input')
    .fill(workflowId)
  await page
    .locator('.el-form-item')
    .filter({ hasText: '名称' })
    .first()
    .locator('input')
    .fill('工作流测试流程')
  let creates = 0
  let testCreates = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/builder/workflows'
    )
      creates++
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/builder/workflows/test-sessions'
    )
      testCreates++
  })
  const saved = responseAt(page, '/api/v1/builder/workflows', 'POST')
  const options = responseAt(page, '/api/v1/builder/workflows/test-sessions/options')
  await page.getByRole('button', { name: '保存并配置测试', exact: true }).click()
  const panel = page.getByRole('dialog', { name: '工作流测试' })
  const savedDraft = await dataOf(await saved)
  const optionResponse = await options
  expect(optionResponse.status()).toBe(200)
  expect(new URL(optionResponse.url()).searchParams.get('draftRevision')).toBe(
    String(savedDraft.revision)
  )
  expect(new URL(optionResponse.url()).searchParams.get('workflowId')).toBe(workflowId)
  const evidence = required('E2E_ARTIFACT_DIR')
  await expect(page).toHaveURL(new RegExp(`/admin/workflows/${workflowId}$`))
  await expect(panel.getByLabel('测试消息')).toBeVisible()
  expect(creates).toBe(1)
  expect(testCreates).toBe(0)
  await page.screenshot({
    path: join(evidence, 'workflow-test-entry-candidate.png'),
    fullPage: true
  })
  await writeFile(
    join(evidence, 'workflow-test-entry-candidate.json'),
    JSON.stringify(
      {
        workflowId,
        revision: savedDraft.revision,
        actions: 1,
        creates,
        testCreates,
        runtimeFixture: true
      },
      null,
      2
    )
  )
  const message = '测试当前保存后的工作流'
  await panel.getByLabel('测试消息').fill(message)
  await panel.locator('.el-drawer__close-btn').click()
  await expect(panel).toBeHidden()
  await page.getByRole('tab', { name: '工作流信息' }).click()
  await page
    .locator('.el-form-item')
    .filter({ hasText: '名称' })
    .first()
    .locator('input')
    .fill('修改后的测试流程')
  await page.getByRole('button', { name: '测试', exact: true }).click()
  await expect(panel.getByLabel('测试消息')).toHaveValue(message)
  await expect(panel).toContainText('当前草稿有未保存修改')
  const update = responseAt(page, '/api/v1/builder/workflows/draft', 'PUT')
  const updatedOptions = responseAt(page, '/api/v1/builder/workflows/test-sessions/options')
  const testCreated = responseAt(page, '/api/v1/builder/workflows/test-sessions', 'POST')
  const accepted = responseAt(page, '/api/v1/chat', 'POST')
  await panel.getByRole('button', { name: '保存并测试', exact: true }).click()
  const updated = await dataOf(await update)
  const nextOptions = await updatedOptions
  expect(nextOptions.status()).toBe(200)
  expect(new URL(nextOptions.url()).searchParams.get('draftRevision')).toBe(
    String(updated.revision)
  )
  const testCreatedResponse = await testCreated
  const createdTest = await dataOf(testCreatedResponse)
  await dataOf(await accepted)
  expect(createdTest.source.draft_revision).toBe(updated.revision)
  expect(testCreatedResponse.request().postDataJSON().user_input).toBe(message)
  expect(testCreates).toBe(1)
  let observedRun: { id: string; status: string } | undefined
  await expect
    .poll(
      async () => {
        observedRun = await page.evaluate(async (id) => {
          const token = localStorage.getItem('skillforge.access_token')
          const response = await fetch(
            `/api/v1/builder/workflows/test-sessions?testSessionId=${encodeURIComponent(id)}`,
            {
              headers: { Authorization: `Bearer ${token}` }
            }
          )
          if (!response.ok) return undefined
          const result = await response.json()
          return result.data?.run_id && result.data?.run_status
            ? { id: String(result.data.run_id), status: String(result.data.run_status) }
            : undefined
        }, String(createdTest.test_session_id))
        return !!observedRun
      },
      { timeout: 60_000 }
    )
    .toBe(true)
  await page.screenshot({
    path: join(evidence, 'workflow-save-test-candidate.png'),
    fullPage: true
  })
  await writeFile(
    join(evidence, 'workflow-save-test-candidate.json'),
    JSON.stringify(
      {
        workflowId,
        revision: updated.revision,
        testSessionId: createdTest.test_session_id,
        run: observedRun,
        manualActions: 1,
        testCreates
      },
      null,
      2
    )
  )
})

test('服务身份轮换断连后通过原回执恢复', async ({ page }) => {
  test.setTimeout(120_000)
  await page.goto('/login?redirect=/admin/governance')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/governance$/)
  await page.getByRole('button', { name: '创建服务身份', exact: true }).click()
  const drawer = page.locator('.el-drawer').filter({ hasText: '签发服务身份' })
  const integration = 'browser-receipt-' + Date.now()
  await drawer.getByLabel('集成标识').fill(integration)
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '授权 Scope' })
    .locator('.el-select')
    .click()
  await page.getByRole('option').filter({ hasText: 'sessions.read' }).first().click()
  await drawer.getByRole('button', { name: '创建', exact: true }).click()
  const secretDialog = page.getByRole('dialog', { name: '请保存本次签发凭据' })
  await expect(secretDialog).toBeVisible()
  await secretDialog.getByRole('button', { name: '已保存' }).click()
  let operations = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/service-principals/rotate-with-receipt'
    )
      operations++
  })
  let originalRequest = ''
  let finishInterception!: () => void
  const interceptionFinished = new Promise<void>((resolve) => {
    finishInterception = resolve
  })
  await page.route('**/api/v1/service-principals/rotate-with-receipt', async (route) => {
    originalRequest = String(route.request().postDataJSON().request_id)
    const committed = await route.fetch()
    expect(committed.status()).toBe(200)
    await route.abort('failed')
    finishInterception()
  })
  await page
    .getByRole('row')
    .filter({ hasText: integration })
    .getByRole('button', { name: '轮换', exact: true })
    .click()
  await page.getByRole('button', { name: '轮换凭据', exact: true }).click()
  await expect(page.getByText('本次凭证轮换结果未知')).toBeVisible()
  expect(operations).toBe(1)
  expect(originalRequest).toBeTruthy()
  await interceptionFinished
  await page.unroute('**/api/v1/service-principals/rotate-with-receipt')
  page.on('dialog', (dialog) => void dialog.accept())
  const receiptReady = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/service-principals/rotate-with-receipt/result'
  )
  await page.reload()
  const receipt = await receiptReady
  expect(receipt.status()).toBe(200)
  await expect(
    page.getByText('原轮换已确认，但明文无法恢复。如需新凭据，请重新轮换。')
  ).toBeVisible()
  expect(operations).toBe(1)
  const evidence = required('E2E_ARTIFACT_DIR')
  await page.screenshot({
    path: join(evidence, 'principal-rotation-recovered.png'),
    fullPage: true
  })
  await writeFile(
    join(evidence, 'principal-rotation-receipt.json'),
    JSON.stringify(
      {
        request_id: originalRequest,
        posted: operations,
        recovery_status: receipt.status()
      },
      null,
      2
    )
  )
})

test('服务身份签发响应中断后通过原回执恢复', async ({ page }) => {
  test.setTimeout(120_000)
  await page.goto('/login?redirect=/admin/governance')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/governance$/)
  await page.getByRole('button', { name: '创建服务身份', exact: true }).click()
  const drawer = page.locator('.el-drawer').filter({ hasText: '签发服务身份' })
  await drawer.getByLabel('集成标识').fill('issue-recovery-' + Date.now())
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '授权 Scope' })
    .locator('.el-select')
    .click()
  await page.getByRole('option').filter({ hasText: 'sessions.read' }).first().click()
  let operations = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/service-principals/issue-with-receipt'
    )
      operations++
  })
  let requestId = ''
  let finishInterception!: () => void
  const intercepted = new Promise<void>((resolve) => {
    finishInterception = resolve
  })
  await page.route('**/api/v1/service-principals/issue-with-receipt', async (route) => {
    requestId = String(route.request().postDataJSON().request_id)
    const committed = await route.fetch()
    expect(committed.status()).toBe(200)
    await route.abort('failed')
    finishInterception()
  })
  await drawer.getByRole('button', { name: '创建', exact: true }).click()
  await expect(page.getByText('本次凭证签发结果未知')).toBeVisible()
  await intercepted
  expect(operations).toBe(1)
  expect(requestId).toBeTruthy()
  await page.unroute('**/api/v1/service-principals/issue-with-receipt')
  page.on('dialog', (dialog) => void dialog.accept())
  const receiptReady = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/service-principals/issue-with-receipt/result'
  )
  await page.reload()
  const receipt = await receiptReady
  expect(receipt.status()).toBe(200)
  await expect(page.getByText('首次凭据无法恢复', { exact: false }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '轮换该身份凭据' })).toBeEnabled()
  expect(operations).toBe(1)
  const evidence = required('E2E_ARTIFACT_DIR')
  await page.screenshot({ path: join(evidence, 'principal-issue-recovered.png'), fullPage: true })
  await writeFile(
    join(evidence, 'principal-issue-receipt.json'),
    JSON.stringify(
      {
        request_id: requestId,
        posted: operations,
        recovery_status: receipt.status()
      },
      null,
      2
    )
  )
})

for (const action of ['suspend', 'revoke'] as const) {
  test(`服务身份${action === 'suspend' ? '暂停' : '吊销'}响应中断后通过原回执恢复`, async ({
    page
  }) => {
    test.setTimeout(120_000)
    await page.goto('/login?redirect=/admin/governance')
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/governance$/)
    await page.getByRole('button', { name: '创建服务身份', exact: true }).click()
    const drawer = page.locator('.el-drawer').filter({ hasText: '签发服务身份' })
    const integration = `state-recovery-${action}-${Date.now()}`
    await drawer.getByLabel('集成标识').fill(integration)
    await drawer
      .locator('.el-form-item')
      .filter({ hasText: '授权 Scope' })
      .locator('.el-select')
      .click()
    await page.getByRole('option').filter({ hasText: 'sessions.read' }).first().click()
    await drawer.getByRole('button', { name: '创建', exact: true }).click()
    const secretDialog = page.getByRole('dialog', { name: '请保存本次签发凭据' })
    await expect(secretDialog).toBeVisible()
    await secretDialog.getByRole('button', { name: '已保存' }).click()
    let operations = 0
    const path = '/api/v1/service-principals/status-with-receipt'
    page.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === path) operations++
    })
    let requestId = ''
    let finishInterception!: () => void
    const intercepted = new Promise<void>((resolve) => {
      finishInterception = resolve
    })
    await page.route('**' + path, async (route) => {
      const submitted = route.request().postDataJSON()
      requestId = String(submitted.request_id)
      expect(submitted.action).toBe(action)
      const committed = await route.fetch()
      expect(committed.status()).toBe(200)
      const body = await committed.json()
      expect(body.data.changed).toBe(true)
      await route.abort('failed')
      finishInterception()
    })
    const label = action === 'suspend' ? '暂停' : '吊销'
    await page
      .getByRole('row')
      .filter({ hasText: integration })
      .getByRole('button', { name: label, exact: true })
      .click()
    await page
      .getByRole('button', {
        name: action === 'suspend' ? '暂停身份' : '吊销服务身份',
        exact: true
      })
      .click()
    await expect(page.getByText('本次服务身份状态操作结果未知')).toBeVisible()
    await intercepted
    expect(operations).toBe(1)
    expect(requestId).toBeTruthy()
    await page.unroute('**' + path)
    page.on('dialog', (dialog) => void dialog.accept())
    const receiptReady = page.waitForResponse(
      (response) => new URL(response.url()).pathname === path + '/result'
    )
    await page.reload()
    const receipt = await receiptReady
    expect(receipt.status()).toBe(200)
    const statusNotice = page.locator('p[role=status]').filter({ hasText: `原${label}操作已确认` })
    await expect(statusNotice).toContainText(integration)
    const row = page.getByRole('row').filter({ hasText: integration })
    await expect(row).toContainText(action === 'suspend' ? '已停用' : '已撤销')
    await expect(row.getByRole('button', { name: label, exact: true })).toBeDisabled()
    expect(operations).toBe(1)
    const evidence = required('E2E_ARTIFACT_DIR')
    await page.screenshot({
      path: join(evidence, `principal-${action}-recovered.png`),
      fullPage: true
    })
    await writeFile(
      join(evidence, `principal-${action}-receipt.json`),
      JSON.stringify(
        {
          request_id: requestId,
          posted: operations,
          recovery_status: receipt.status()
        },
        null,
        2
      )
    )
  })
}

test('已暂停服务身份可明确吊销', async ({ page }) => {
  test.setTimeout(120_000)
  await page.goto('/login?redirect=/admin/governance')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/governance$/)
  await page.getByRole('button', { name: '创建服务身份', exact: true }).click()
  const drawer = page.locator('.el-drawer').filter({ hasText: '签发服务身份' })
  const integration = 'suspend-then-revoke-' + Date.now()
  await drawer.getByLabel('集成标识').fill(integration)
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '授权 Scope' })
    .locator('.el-select')
    .click()
  await page.getByRole('option').filter({ hasText: 'sessions.read' }).first().click()
  await drawer.getByRole('button', { name: '创建', exact: true }).click()
  await page
    .getByRole('dialog', { name: '请保存本次签发凭据' })
    .getByRole('button', { name: '已保存' })
    .click()
  const row = page.getByRole('row').filter({ hasText: integration })
  const paused = responseAt(page, '/api/v1/service-principals/status-with-receipt', 'POST')
  await row.getByRole('button', { name: '暂停', exact: true }).click()
  await page.getByRole('button', { name: '暂停身份', exact: true }).click()
  expect((await dataOf(await paused)).changed).toBe(true)
  await expect(row).toContainText('已停用')
  const revoked = responseAt(page, '/api/v1/service-principals/status-with-receipt', 'POST')
  await row.getByRole('button', { name: '吊销', exact: true }).click()
  await page.getByRole('button', { name: '吊销服务身份', exact: true }).click()
  expect((await dataOf(await revoked)).changed).toBe(true)
  await expect(row).toContainText('已撤销')
  await expect(row.getByRole('button', { name: '吊销', exact: true })).toBeDisabled()
  const evidence = required('E2E_ARTIFACT_DIR')
  await page.screenshot({
    path: join(evidence, 'principal-revoke-after-suspend.png'),
    fullPage: true
  })
  await writeFile(
    join(evidence, 'principal-revoke-after-suspend.json'),
    JSON.stringify(
      {
        suspend_changed: true,
        revoke_changed: true,
        final_status: 'revoked'
      },
      null,
      2
    )
  )
})

test('Webhook 创建响应中断后刷新核对原订阅而不重复创建', async ({ page }) => {
  test.setTimeout(120_000)
  await page.goto('/login?redirect=/admin/governance')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/governance$/)
  await page.getByRole('tab', { name: 'Webhook', exact: true }).click()
  await page.getByRole('button', { name: '订阅回调', exact: true }).click()
  const drawer = page.locator('.el-drawer').filter({ hasText: '新增 Webhook' })
  const endpoint = `https://127.0.0.1:1/noe-fixture-${Date.now()}`
  await drawer.getByLabel('回调地址').fill(endpoint)
  await drawer.getByLabel('签名密钥').fill('isolated-fixture-signing-secret')
  let operations = 0
  const path = '/api/v1/webhook-subscriptions/create-with-receipt'
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === path) operations++
  })
  let finishInterception!: () => void
  const intercepted = new Promise<void>((resolve) => {
    finishInterception = resolve
  })
  await page.route('**' + path, async (route) => {
    const committed = await route.fetch()
    expect(committed.status()).toBe(200)
    await route.abort('failed')
    finishInterception()
  })
  await page.route('**' + path + '/result?*', (route) => route.fulfill({ status: 204 }))
  await drawer.getByRole('button', { name: '创建', exact: true }).click()
  await intercepted
  expect(operations).toBe(1)
  await expect(drawer.getByRole('button', { name: '创建', exact: true })).toBeDisabled()
  await page.unroute('**' + path)
  await page.unroute('**' + path + '/result?*')
  page.on('dialog', (dialog) => void dialog.accept())
  await page.reload()
  await expect(page.locator('p[role="status"]').filter({ hasText: '原订阅已确认' })).toBeVisible()
  await page.getByRole('tab', { name: 'Webhook', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: endpoint })).toBeVisible()
  await expect(page.getByRole('button', { name: '订阅回调', exact: true })).toBeEnabled()
  expect(operations).toBe(1)
  const evidence = required('E2E_ARTIFACT_DIR')
  await page.screenshot({ path: join(evidence, 'webhook-create-recovered.png'), fullPage: true })
  await writeFile(
    join(evidence, 'webhook-create-recovered.json'),
    JSON.stringify(
      {
        completed_server_creates: operations,
        recovered_after_reload: true
      },
      null,
      2
    )
  )
})

test('Webhook 专属授权可通过页面创建订阅', async ({ page }) => {
  test.setTimeout(120_000)
  await page.goto('/login?redirect=/admin/governance')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_WEBHOOK_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/governance$/)
  await expect
    .poll(() =>
      page.evaluate(() => JSON.parse(localStorage.getItem('skillforge.user') || '{}').permissions)
    )
    .toContain('foundation.webhooks.create')
  const permissions = await page.evaluate(
    () => JSON.parse(localStorage.getItem('skillforge.user') || '{}').permissions as string[]
  )
  expect(permissions).toContain('foundation.webhooks.create')
  expect(permissions).not.toContain('foundation.service_principals.create')
  await expect(page.getByRole('tab', { name: '服务身份', exact: true })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: 'Webhook', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '订阅回调', exact: true }).click()
  const drawer = page.locator('.el-drawer').filter({ hasText: '新增 Webhook' })
  const endpoint = `https://127.0.0.1:1/webhook-permission-${Date.now()}`
  await drawer.getByLabel('回调地址').fill(endpoint)
  await drawer.getByLabel('签名密钥').fill('synthetic-permission-secret')
  await drawer.getByRole('button', { name: '创建', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: endpoint })).toBeVisible()
  const evidence = required('E2E_ARTIFACT_DIR')
  await page.screenshot({
    path: join(evidence, 'webhook-permission-recovered.png'),
    fullPage: true
  })
  await writeFile(
    join(evidence, 'webhook-permission-recovered.json'),
    JSON.stringify(
      {
        webhook_create_permission: true,
        principal_create_permission: false,
        page_create_succeeded: true
      },
      null,
      2
    )
  )
})

test('已暂停的 Webhook 明确展示状态且禁止重复暂停', async ({ page }) => {
  test.setTimeout(120_000)
  await page.goto('/login?redirect=/admin/governance')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/governance$/)
  await page.getByRole('tab', { name: 'Webhook', exact: true }).click()
  await page.getByRole('button', { name: '订阅回调', exact: true }).click()
  const drawer = page.locator('.el-drawer').filter({ hasText: '新增 Webhook' })
  const endpoint = `https://127.0.0.1:1/webhook-pause-baseline-${Date.now()}`
  await drawer.getByLabel('回调地址').fill(endpoint)
  await drawer.getByLabel('签名密钥').fill('synthetic-pause-fixture')
  await drawer.getByRole('button', { name: '创建', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: endpoint })
  await expect(row).toBeVisible()
  const first = responseAt(page, '/api/v1/webhook-subscriptions/pause-with-receipt', 'POST')
  await row.getByRole('button', { name: '暂停', exact: true }).click()
  await page.getByRole('button', { name: '暂停订阅', exact: true }).click()
  expect((await dataOf(await first)).changed).toBe(true)
  await expect(row).toContainText('已暂停')
  await expect(row.getByRole('button', { name: '暂停', exact: true })).toBeDisabled()
  const evidence = required('E2E_ARTIFACT_DIR')
  await page.screenshot({ path: join(evidence, 'webhook-pause-confirmed.png'), fullPage: true })
  await writeFile(
    join(evidence, 'webhook-pause-confirmed.json'),
    JSON.stringify({ first_changed: true, repeat_disabled: true }, null, 2)
  )
})

test('Webhook 暂停提交后响应中断可只读核对原操作', async ({ page }) => {
  test.setTimeout(120_000)
  await page.goto('/login?redirect=/admin/governance')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/governance$/)
  await page.getByRole('tab', { name: 'Webhook', exact: true }).click()
  await page.getByRole('button', { name: '订阅回调', exact: true }).click()
  const drawer = page.locator('.el-drawer').filter({ hasText: '新增 Webhook' })
  const endpoint = `https://127.0.0.1:1/webhook-pause-disconnect-${Date.now()}`
  await drawer.getByLabel('回调地址').fill(endpoint)
  await drawer.getByLabel('签名密钥').fill('synthetic-pause-disconnect')
  await drawer.getByRole('button', { name: '创建', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: endpoint })
  await expect(row).toBeVisible()
  let posts = 0
  const path = '/api/v1/webhook-subscriptions/pause-with-receipt'
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === path) posts++
  })
  let finishInterception!: () => void
  const intercepted = new Promise<void>((resolve) => {
    finishInterception = resolve
  })
  await page.route('**' + path, async (route) => {
    const response = await route.fetch()
    expect(response.status()).toBe(200)
    expect((await response.json()).data.changed).toBe(true)
    await route.abort('failed')
    finishInterception()
  })
  await row.getByRole('button', { name: '暂停', exact: true }).click()
  await page.getByRole('button', { name: '暂停订阅', exact: true }).click()
  await intercepted
  await expect(page.getByText('回调订阅暂停结果尚未确认', { exact: false }).first()).toBeVisible()
  await expect(row.getByRole('button', { name: '暂停', exact: true })).toBeDisabled()
  expect(posts).toBe(1)
  await page.unroute('**' + path)
  const receiptReady = page.waitForResponse(
    (response) => new URL(response.url()).pathname === path + '/result'
  )
  await page.reload()
  expect((await receiptReady).status()).toBe(200)
  await expect(page.getByText('原暂停操作已确认', { exact: false }).first()).toBeVisible()
  await page.getByRole('tab', { name: 'Webhook', exact: true }).click()
  await expect(row).toContainText('已暂停')
  await expect(row.getByRole('button', { name: '暂停', exact: true })).toBeDisabled()
  expect(posts).toBe(1)
  const evidence = required('E2E_ARTIFACT_DIR')
  await page.screenshot({ path: join(evidence, 'webhook-pause-recovered.png'), fullPage: true })
  await writeFile(
    join(evidence, 'webhook-pause-recovered.json'),
    JSON.stringify(
      { committed: true, response_interrupted: true, recovered: true, attempts: posts },
      null,
      2
    )
  )
})
