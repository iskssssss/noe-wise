import { expect, test, type Response } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少公开披露浏览器夹具：${name}`)
  return value
}

function responseAt(page: import('@playwright/test').Page, path: string, method = 'GET') {
  return page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === path && response.request().method() === method
  )
}

async function dataOf(response: Response) {
  expect(response.ok(), `HTTP ${response.status()}：${new URL(response.url()).pathname}`).toBe(true)
  const result = await response.json()
  expect(result.code).toBe('OK')
  return result.data
}

test('当前公开策略约束浏览器实时投影', async ({ page }) => {
  test.setTimeout(180_000)
  const evidence = required('E2E_ARTIFACT_DIR')
  const sessionId = required('E2E_SESSION_ID')
  const rejected = required('E2E_REJECTED_CONTENT')
  const safe = required('E2E_SAFE_CONTENT')
  const sentinel = required('E2E_ORDER_SENTINEL')
  const mode = process.env.E2E_DISCLOSURE_MODE || 'candidate'
  expect(['baseline', 'candidate']).toContain(mode)
  await mkdir(evidence, { recursive: true })

  await page.goto('/login?redirect=/admin/chat')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  const sessionsReady = responseAt(page, '/api/v1/chat/sessions')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const sessions = await dataOf(await sessionsReady)
  const session = sessions.find((item: { session_id: string }) => item.session_id === sessionId)
  expect(session).toBeTruthy()

  await page.locator('.el-select').filter({ hasText: '历史会话' }).getByRole('combobox').click()
  const historyReady = responseAt(page, '/api/v1/chat/history')
  const eventsReady = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat/events' && response.status() === 200
  )
  await page.getByRole('option', { name: session.summary || sessionId, exact: true }).click()
  const history = await dataOf(await historyReady)
  expect(history.session_id).toBe(sessionId)
  const input = page.getByPlaceholder('输入消息', { exact: true })
  await input.fill('建立公开投影实时订阅')
  const accepted = responseAt(page, '/api/v1/chat', 'POST')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  expect((await dataOf(await accepted)).session_id).toBe(sessionId)
  await eventsReady
  await expect(page.getByText(/^fixture-answer-runtime-e2e-/).last()).toBeVisible({
    timeout: 60_000
  })
  await writeFile(required('E2E_READY_FILE'), 'ready')

  await expect(page.getByText(sentinel, { exact: true }).last()).toBeVisible({ timeout: 60_000 })
  const rejectedVisible = (await page.getByText(rejected, { exact: false }).count()) > 0
  const safeVisible = (await page.getByText(safe, { exact: false }).count()) > 0
  if (mode === 'baseline') {
    expect(rejectedVisible).toBe(true)
    expect(safeVisible).toBe(false)
  } else {
    expect(rejectedVisible).toBe(false)
    expect(safeVisible).toBe(true)
  }
  await page.screenshot({
    path: join(evidence, `browser-${mode}.png`),
    fullPage: true
  })
  await writeFile(
    join(evidence, 'browser-receipt.json'),
    JSON.stringify(
      {
        mode,
        session_id: sessionId,
        history_safe_before_append: !JSON.stringify(history).includes(rejected),
        rejected_visible: rejectedVisible,
        safe_visible: safeVisible,
        order_sentinel_visible: true
      },
      null,
      2
    )
  )
})
