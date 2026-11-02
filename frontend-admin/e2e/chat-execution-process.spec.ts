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
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/chat')
      submissions++
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

  // 刷新后从真实历史目录按固定Session身份恢复；禁止再次POST业务输入。
  const historyReady = responseAt(page, '/api/v1/chat/sessions')
  await page.reload()
  const sessions = await dataOf(await historyReady)
  const selected = sessions.find((item: { session_id: string }) => item.session_id === sessionId)
  expect(selected).toBeTruthy()
  await page.locator('.el-select').filter({ hasText: '历史会话' }).getByRole('combobox').click()
  const restored = responseAt(page, '/api/v1/chat/history')
  await page.getByRole('option', { name: selected.summary || sessionId, exact: true }).click()
  const history = await dataOf(await restored)
  expect(history.session_id).toBe(sessionId)
  expect(history.messages.filter((item: { role: string }) => item.role === 'user')).toHaveLength(1)
  await expect(page.locator('.debug-message').filter({ hasText: expectedReply })).toHaveCount(1)
  await expect(page.locator('body')).not.toContainText(required('E2E_INTERNAL_REASONING'))
  expect(submissions).toBe(1)
  expect(errors).toEqual([])
  const evidence = required('E2E_ARTIFACT_DIR')
  await mkdir(evidence, { recursive: true })
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
