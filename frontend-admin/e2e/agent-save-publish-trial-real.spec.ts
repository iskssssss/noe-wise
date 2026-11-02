import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少隔离验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })

test('编辑后发布并试用同一智能体的新配置', async ({ page }) => {
  test.setTimeout(180_000)
  const agent = required('E2E_AGENT_ID')
  const evidence = required('E2E_ARTIFACT_DIR')
  const marker = required('E2E_TRIAL_PROMPT_MARKER')
  const expectedReply = required('E2E_EXPECTED_REPLY')
  const baseline = process.env.E2E_AGENT_TRIAL_MODE === 'baseline'
  let publications = 0
  let sessionWrites = 0
  let chatWrites = 0
  let primaryActions = 0
  page.on('request', (request) => {
    if (request.method() !== 'POST') return
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/agents/save-and-publish') publications++
    if (path === '/api/v1/chat/sessions/create-with-receipt' || path === '/api/v1/chat/sessions')
      sessionWrites++
    if (path === '/api/v1/chat') chatWrites++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/agents/${agent}$`))
  await page.getByLabel('系统提示词', { exact: true }).fill(`你是测试智能体。${marker}`)
  const publish = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/agents/save-and-publish'
  )
  let usersReady: Promise<import('@playwright/test').Response> | undefined
  if (!baseline)
    usersReady = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/v1/chat/end-users'
    )
  await page
    .getByRole('button', { name: baseline ? '保存并发布' : '保存发布并试用', exact: true })
    .click()
  primaryActions++
  const dialog = page.getByRole('dialog', {
    name: baseline ? '保存并发布智能体' : '保存发布并试用智能体'
  })
  await dialog
    .getByRole('button', { name: baseline ? '保存并发布' : '保存发布并试用', exact: true })
    .click()
  const result = await publish
  expect(result.status()).toBe(200)
  const command = result.request().postDataJSON()
  const receipt = (await result.json()).data
  expect(String(receipt.agent_id)).toBe(agent)
  expect(receipt.request_id).toBe(command.request_id)
  expect(String(receipt.publication_id)).toMatch(/^[1-9]\d*$/)
  expect(Number(receipt.revision)).toBeGreaterThan(0)
  if (baseline) {
    await expect(page.getByText('保存并发布已确认', { exact: false })).toBeVisible()
    usersReady = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/v1/chat/end-users'
    )
    await page.getByRole('button', { name: '试用当前发布版本', exact: true }).click()
    primaryActions++
  }
  await expect(page).toHaveURL(/\/admin\/chat\?agent_id=/)
  expect(new URL(page.url()).searchParams.get('agent_id')).toBe(agent)
  expect((await usersReady!).status()).toBe(200)
  expect(publications).toBe(1)
  expect(sessionWrites).toBe(0)
  expect(chatWrites).toBe(0)
  const detail = await page.evaluate(async (id) => {
    const token = localStorage.getItem('skillforge.access_token')
    const response = await fetch(`/api/v1/agents/detail?agentId=${encodeURIComponent(id)}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    return { status: response.status, data: (await response.json()).data }
  }, agent)
  expect(detail.status).toBe(200)
  expect(String(detail.data.id)).toBe(agent)
  expect(Number(detail.data.published_revision)).toBe(Number(receipt.revision))
  await page.screenshot({ path: join(evidence, 'agent-trial-ready.png'), fullPage: true })
  await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
  await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
  await page.getByPlaceholder('输入消息', { exact: true }).fill('请简短说明当前系统能力。')
  const created = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/chat/sessions/create-with-receipt'
  )
  const accepted = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/chat'
  )
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const sessionResponse = await created
  const acceptedResponse = await accepted
  expect(sessionResponse.status()).toBe(200)
  expect(acceptedResponse.status()).toBe(202)
  const session = (await sessionResponse.json()).data
  const chat = (await acceptedResponse.json()).data
  expect(String(session.session_id)).toBe(String(chat.session_id))
  expect(String(chat.execution_id)).toBeTruthy()
  await expect(page.locator('.debug-message').filter({ hasText: expectedReply })).toHaveCount(1, {
    timeout: 120_000
  })
  expect(sessionWrites).toBe(1)
  expect(chatWrites).toBe(1)
  await page.screenshot({ path: join(evidence, 'agent-trial-result.png'), fullPage: true })
  await writeFile(
    join(evidence, 'agent-trial.json'),
    JSON.stringify({
      agentId: agent,
      marker,
      receipt,
      sessionId: String(session.session_id),
      runId: String(chat.execution_id),
      primaryActions,
      confirmations: 1,
      publications,
      sessionWrites,
      chatWrites,
      publishedRevision: Number(detail.data.published_revision)
    })
  )
})

test('发布响应 401 后核对原发布且不重发', async ({ page }) => {
  test.setTimeout(120_000)
  const agent = required('E2E_AGENT_ID')
  const evidence = required('E2E_ARTIFACT_DIR')
  const baseline = process.env.E2E_AGENT_TRIAL_MODE === 'baseline'
  let posts = 0,
    upstream = 0,
    refreshes = 0,
    sessions = 0,
    chats = 0
  let command: { agent_id: string; request_id: string } | undefined
  let original: Record<string, unknown> | undefined
  page.on('request', (request) => {
    if (request.method() !== 'POST') return
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/agents/save-and-publish') posts++
    if (path === '/api/v1/auth/refresh') refreshes++
    if (path === '/api/v1/chat/sessions/create-with-receipt' || path === '/api/v1/chat/sessions')
      sessions++
    if (path === '/api/v1/chat') chats++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/agents/${agent}$`))
  await page
    .getByLabel('系统提示词', { exact: true })
    .fill(`你是测试智能体。${required('E2E_TRIAL_PROMPT_MARKER')}`)
  await page.route('**/api/v1/agents/save-and-publish', async (route) => {
    if (upstream > 0) return route.abort('failed')
    command = route.request().postDataJSON()
    const actual = await route.fetch()
    upstream++
    expect(actual.status()).toBe(200)
    original = (await actual.json()).data
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'AUTH_EXPIRED', message: 'isolated test', trace_id: 'fixture' })
    })
  })
  const interrupted = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/agents/save-and-publish' &&
      response.status() === 401
  )
  await page.getByRole('button', { name: '保存并发布', exact: true }).click()
  await page
    .getByRole('dialog', { name: '保存并发布智能体' })
    .getByRole('button', { name: '保存并发布', exact: true })
    .click()
  await interrupted
  if (baseline) await expect.poll(() => posts, { timeout: 20_000 }).toBe(2)
  await expect(page.getByText('保存并发布结果尚未确认', { exact: false })).toBeVisible()
  expect(command).toBeTruthy()
  expect(original).toBeTruthy()
  const lookup = await page.evaluate(
    async ({ agentId, requestId }) => {
      const token = localStorage.getItem('skillforge.access_token')
      const response = await fetch(
        `/api/v1/agents/save-and-publish/result?agentId=${encodeURIComponent(agentId)}&requestId=${encodeURIComponent(requestId)}`,
        { headers: { Authorization: `Bearer ${token}` } }
      )
      return {
        status: response.status,
        data: response.status === 200 ? (await response.json()).data : null
      }
    },
    { agentId: agent, requestId: command!.request_id }
  )
  expect(lookup.status).toBe(200)
  expect(lookup.data).toEqual(original)
  expect(String(lookup.data.agent_id)).toBe(agent)
  expect(lookup.data.request_id).toBe(command!.request_id)
  await page.screenshot({ path: join(evidence, 'agent-trial-401.png'), fullPage: true })
  await writeFile(
    join(evidence, 'agent-trial-401.json'),
    JSON.stringify({
      agentId: agent,
      requestId: command!.request_id,
      receipt: lookup.data,
      posts,
      upstream,
      refreshes,
      sessions,
      chats
    })
  )
  expect(posts).toBe(baseline ? 2 : 1)
  expect(refreshes).toBe(baseline ? 1 : 0)
  expect(upstream).toBe(1)
  expect(sessions).toBe(0)
  expect(chats).toBe(0)
})
