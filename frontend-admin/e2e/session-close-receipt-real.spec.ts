import { expect, test } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { ChatHistoryResponse } from '../src/api'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少关闭回执验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('成功关闭回执不依赖额外详情读取，保留原会话历史和草稿', async ({ page }) => {
  test.setTimeout(170_000)
  const mode = process.env.E2E_SESSION_CLOSE_RECEIPT ?? 'candidate'
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  await mkdir(evidence, { recursive: true })
  const reply = required('E2E_EXPECTED_REPLY')
  const message = '请回复以验证会话关闭前的真实执行。'
  const draft = '关闭前尚未发送的草稿应保留。'
  const errors: string[] = []
  const network: { path: string; method: string; status: number }[] = []
  let loggedIn = false
  let submits = 0
  let closes = 0
  let closeAccepted = false
  let detailReadsAfterClose = 0
  let droppedDetails = 0
  let sessionId = ''
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (request.method() !== 'POST') return
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/chat') submits++
    if (path === '/api/v1/chat/sessions/close') closes++
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/v1/chat'))
      network.push({
        path: url.pathname + url.search,
        method: response.request().method(),
        status: response.status()
      })
  })
  // 先取得真实关闭回执再原样交付，确保仅影响关闭之后的读取。
  await page.route(
    (url) => url.pathname === '/api/v1/chat/sessions/close',
    async (route) => {
      const response = await route.fetch({ timeout: 15_000 })
      const body = await response.json()
      await writeFile(
        join(evidence, 'close-upstream.json'),
        JSON.stringify(
          {
            status: response.status(),
            request: route.request().postDataJSON(),
            response: body
          },
          null,
          2
        )
      )
      closeAccepted = response.status() === 200 && body.data?.status === 'closed'
      await route.fulfill({ response })
    }
  )
  await page.route(
    (url) => url.pathname === '/api/v1/chat/sessions/detail',
    async (route) => {
      if (!closeAccepted) return route.continue()
      detailReadsAfterClose++
      const response = await route.fetch({ timeout: 15_000 })
      const body = await response.json()
      await writeFile(
        join(evidence, `dropped-detail-${detailReadsAfterClose}.json`),
        JSON.stringify(
          {
            fault: '真实上游详情响应已取得，受控丢弃浏览器回包',
            path: new URL(route.request().url()).pathname + new URL(route.request().url()).search,
            status: response.status(),
            response: body
          },
          null,
          2
        )
      )
      expect(response.status()).toBe(200)
      expect(body.data.closed).toBe(true)
      await route.abort('failed')
      droppedDetails++
    }
  )
  try {
    await page.goto('/login?redirect=/admin/chat')
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    const users = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/v1/chat/end-users'
    )
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/chat$/)
    loggedIn = true
    expect((await users).status()).toBe(200)
    await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
    await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
    const input = page.getByPlaceholder('输入消息', { exact: true })
    await input.fill(message)
    const completedHistory = page.waitForResponse(
      async (response) => {
        if (new URL(response.url()).pathname !== '/api/v1/chat/history' || !response.ok())
          return false
        const history: ChatHistoryResponse = (await response.json()).data
        return !!history.activities?.some(
          (activity) =>
            activity.node_type === 'agent_runtime' &&
            ['completed', 'failed', 'cancelled', 'terminated'].includes(activity.status)
        )
      },
      { timeout: 120_000 }
    )
    const accepted = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/chat'
    )
    await page.getByRole('button', { name: '发送', exact: true }).click()
    const response = await accepted
    const body = await response.json()
    await writeFile(
      join(evidence, 'accepted.json'),
      JSON.stringify(
        {
          status: response.status(),
          request: response.request().postDataJSON(),
          response: body
        },
        null,
        2
      )
    )
    expect(response.status()).toBe(202)
    sessionId = String(body.data.session_id)
    expect(sessionId).toBeTruthy()
    const historyResponse = await completedHistory
    const history: ChatHistoryResponse = (await historyResponse.json()).data
    await writeFile(join(evidence, 'completed-history.json'), await historyResponse.text())
    expect(history.session_id).toBe(sessionId)
    expect(
      history.activities?.find((activity) => activity.node_id === body.data.execution_id)?.status
    ).toBe('completed')
    await expect(page.locator('.debug-message').filter({ hasText: reply })).toHaveCount(1)
    await input.fill(draft)
    await page.getByRole('button', { name: '关闭会话', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '关闭会话', exact: true })
    await expect(dialog).toBeVisible()
    const closed = page.waitForResponse(
      (result) =>
        result.request().method() === 'POST' &&
        new URL(result.url()).pathname === '/api/v1/chat/sessions/close'
    )
    await dialog.getByRole('button', { name: '关闭会话', exact: true }).click()
    const closeResponse = await closed
    expect(closeResponse.status()).toBe(200)
    expect((await closeResponse.json()).data).toEqual({ session_id: sessionId, status: 'closed' })
    await expect(dialog).not.toBeVisible()
    const unknown = page.getByText(
      '关闭结果尚待核对。请读取会话状态，确认前不会再次关闭或提交新输入。',
      { exact: true }
    )
    const confirmed = page.getByText(
      '此会话已关闭，保留历史记录供查看。请新建会话继续对话；关闭不会撤销已执行的操作。',
      { exact: true }
    )
    if (mode === 'baseline') {
      await expect.poll(() => droppedDetails).toBe(1)
      await expect(unknown).toBeVisible()
      await expect(confirmed).toHaveCount(0)
    } else {
      await expect(confirmed).toBeVisible()
      await expect(unknown).toHaveCount(0)
    }
    await expect(page.getByRole('button', { name: '读取会话状态', exact: true })).toBeEnabled()
    expect(detailReadsAfterClose).toBe(mode === 'baseline' ? 1 : 0)
    expect(droppedDetails).toBe(mode === 'baseline' ? 1 : 0)
    await expect(page.getByRole('button', { name: '关闭会话', exact: true })).toBeDisabled()
    await expect(page.getByRole('button', { name: '发送', exact: true })).toBeDisabled()
    await expect(input).toHaveValue(draft)
    await expect(page.locator('.debug-message').filter({ hasText: message })).toHaveCount(1)
    await expect(page.locator('.debug-message').filter({ hasText: reply })).toHaveCount(1)
    expect(submits).toBe(1)
    expect(closes).toBe(1)
    expect(errors).toEqual([])
    await (mode === 'baseline' ? unknown : confirmed).scrollIntoViewIfNeeded()
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify(
        {
          mode,
          sessionId,
          submits,
          closes,
          closeAccepted,
          detailReadsAfterClose,
          droppedDetails,
          errors,
          network
        },
        null,
        2
      )
    )
    if (loggedIn) {
      await writeFile(join(evidence, 'final.html'), await page.locator('body').innerHTML())
      await page.screenshot({
        path: join(evidence, `session-close-receipt-${mode}.png`),
        fullPage: true
      })
    }
  }
})
