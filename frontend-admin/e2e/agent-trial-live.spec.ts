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
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test.skip(process.env.E2E_LIVE_MODEL !== '1', '仅运行已授权的隔离真实模型样本')

test('真实机构登录试用并恢复持久公开回复', async ({ page }) => {
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
  await page.goto(
    `/login?redirect=${encodeURIComponent('/admin/agents/' + required('E2E_AGENT_ID'))}`
  )
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/agents\//)
  const usersReady = responseAt(page, '/api/v1/chat/end-users')
  let teamReads = 0
  page.on('request', (request) => {
    if (request.method() === 'GET' && new URL(request.url()).pathname === '/api/v1/teams')
      teamReads++
  })
  await page.getByRole('button', { name: '试用当前发布版本' }).click()
  await expect(page).toHaveURL(/\/admin\/chat\?agent_id=/)
  await expect(page.getByText('已预选智能体的当前发布版本', { exact: false })).toBeVisible()
  await expect(page.getByText('当前账号没有团队读取权限', { exact: false })).toBeVisible()
  expect(teamReads).toBe(0)
  const users = await dataOf(await usersReady)
  expect(users.length).toBeGreaterThan(0)
  const user = users.find(
    (item: { display_name: string }) => item.display_name === 'Runtime E2E User'
  )
  expect(user).toBeTruthy()
  await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
  await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
  await expect(page.locator('.session-setup')).toContainText('Runtime E2E Agent')
  const input = page.getByPlaceholder('输入消息', { exact: true })
  await input.fill(message)
  const created = responseAt(page, '/api/v1/chat/sessions', 'POST')
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
  const evidence = required('E2E_ARTIFACT_DIR')
  await mkdir(evidence, { recursive: true })
  await writeFile(
    join(evidence, 'accepted-receipt.json'),
    JSON.stringify({ sessionId, runId, inputId, submissions, teamReads }, null, 2)
  )
  const assistantReply = page
    .locator('.debug-message')
    .filter({ has: page.locator('.message-heading strong', { hasText: /^智能体$/ }) })
    .filter({ hasText: expectedReply })
  await expect(assistantReply).toHaveCount(1, { timeout: 120_000 })
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
  await expect(assistantReply).toHaveCount(1)
  expect(submissions).toBe(1)
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
