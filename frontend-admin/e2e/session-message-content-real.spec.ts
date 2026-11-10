import { test, expect, type Page, type Response } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { ChatHistoryResponse } from '../src/api'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少会话内容浏览器夹具：${name}`)
  return value
}

function responseAt(page: Page, path: string, method = 'GET') {
  return page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === path && response.request().method() === method
  )
}

async function dataOf(response: Response) {
  expect(response.ok(), `HTTP ${response.status()}：${new URL(response.url()).pathname}`).toBe(true)
  const envelope = await response.json()
  expect(envelope.code).toBe('OK')
  expect(envelope.trace_id).toBeTruthy()
  return envelope.data
}

// 基线和候选各只发送一次输入；失败由宿主保留证据，不重试模型调用。
test.describe.configure({ retries: 0 })

test('真实发送图片和文件后，会话记录保留完整用户输入', async ({ page }) => {
  test.setTimeout(180_000)
  const mode = required('E2E_SESSION_MESSAGE_CONTENT')
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  await mkdir(evidence, { recursive: true })
  const expectedReply = required('E2E_EXPECTED_REPLY')
  const message = '请确认已收到这条消息及图片和文件。'
  const filename = 'session-history.txt'
  const errors: string[] = []
  const network: { path: string; method: string; status: number }[] = []
  let submissions = 0
  let sessionCreations = 0
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path === '/api/v1/chat') submissions++
    if (request.method() === 'POST' && path === '/api/v1/chat/sessions/create-with-receipt')
      sessionCreations++
  })
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname
    if (path.startsWith('/api/v1/chat'))
      network.push({ path, method: response.request().method(), status: response.status() })
  })

  await page.goto('/login?redirect=/admin/chat')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  const usersReady = responseAt(page, '/api/v1/chat/end-users')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/chat$/)
  const users = await dataOf(await usersReady)
  const user = users.find(
    (item: { display_name: string }) => item.display_name === 'Runtime E2E User'
  )
  expect(user).toBeTruthy()
  await page.locator('.el-select').filter({ hasText: '终端用户' }).getByRole('combobox').click()
  await page.getByRole('option', { name: 'Runtime E2E User', exact: true }).click()
  await expect(
    page
      .locator('.el-select')
      .filter({ hasText: 'Runtime E2E Agent' })
      .locator('.el-select__placeholder')
  ).toHaveText('Runtime E2E Agent')

  const created = responseAt(page, '/api/v1/chat/sessions/create-with-receipt', 'POST')
  const imageUploaded = responseAt(page, '/api/v1/chat/sessions/media/upload', 'POST')
  await page.locator('input[type="file"][accept="image/*"]').setInputFiles({
    name: 'session-history.png',
    mimeType: 'image/png',
    buffer: Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
      'base64'
    )
  })
  const sessionId = String((await dataOf(await created)).session_id)
  const media = await dataOf(await imageUploaded)
  expect(media.media_id).toBeTruthy()
  await expect(page.locator('.debug-composer')).toContainText(`图片 ${media.media_id}`)
  const fileUploaded = responseAt(page, '/api/v1/chat/sessions/attachments/upload', 'POST')
  await page
    .locator('.el-upload')
    .filter({ has: page.getByRole('button', { name: '附加文件', exact: true }) })
    .locator('input[type="file"]')
    .setInputFiles({
      name: filename,
      mimeType: 'text/plain',
      buffer: Buffer.from('history attachment evidence', 'utf8')
    })
  const attachment = await dataOf(await fileUploaded)
  expect(attachment.name).toBe(filename)
  await expect(page.locator('.debug-composer')).toContainText(filename)
  await page.getByPlaceholder('输入消息', { exact: true }).fill(message)
  const accepted = responseAt(page, '/api/v1/chat', 'POST')
  const terminalHistory = page.waitForResponse(
    async (response) => {
      const url = new URL(response.url())
      if (
        url.pathname !== '/api/v1/chat/history' ||
        url.searchParams.get('session_id') !== sessionId ||
        !response.ok()
      )
        return false
      const history: ChatHistoryResponse = (await response.json()).data
      return !!history.activities?.some(
        (activity) =>
          activity.node_type === 'agent_runtime' &&
          ['completed', 'failed', 'cancelled'].includes(activity.status)
      )
    },
    { timeout: 120_000 }
  )
  await page.getByRole('button', { name: '发送', exact: true }).click()
  const acceptedResponse = await accepted
  const receipt = await dataOf(acceptedResponse)
  const submitted = acceptedResponse.request().postDataJSON()
  const runId = String(receipt.execution_id ?? '')
  const inputId = String(submitted.client_message_id ?? '')
  expect(submitted.session_id).toBe(sessionId)
  expect(String(submitted.context.end_user_id)).toBe(String(user.id))
  expect(submitted.content).toEqual([
    { type: 'text', text: message },
    { type: 'image', media_id: media.media_id }
  ])
  expect(submitted.context.attachment_ids).toEqual([attachment.id])
  expect(runId).toBeTruthy()
  expect(inputId).toBeTruthy()
  // 先保留受理身份，后续页面断言失败时仍能关联真实 Runtime 执行。
  await writeFile(
    join(evidence, 'browser-receipt.json'),
    JSON.stringify({ sessionId, runId, inputId, mode, submissions, live: false }, null, 2)
  )
  await writeFile(
    join(evidence, 'session-content-input.json'),
    JSON.stringify({ sessionId, media, attachment, submitted }, null, 2)
  )
  const terminalResponse = await terminalHistory
  await writeFile(
    join(evidence, 'session-content-terminal-history.json'),
    await terminalResponse.text()
  )
  const terminal: ChatHistoryResponse = await dataOf(terminalResponse)
  const execution = terminal.activities?.find((activity) => activity.node_id === runId)
  expect(execution?.status, `Runtime 结束：${execution?.render?.reason ?? execution?.status}`).toBe(
    'completed'
  )
  await expect(page.locator('.debug-message').filter({ hasText: expectedReply })).toHaveCount(1)
  const liveMessage = page.locator('.debug-message').filter({ hasText: message })
  await expect(liveMessage.locator('img')).toHaveCount(1)
  await expect
    .poll(() =>
      liveMessage
        .locator('img')
        .evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0)
    )
    .toBe(true)
  await expect(liveMessage.getByLabel('已发送附件')).toContainText(filename)
  await page.screenshot({ path: join(evidence, 'session-content-chat-live.png'), fullPage: true })

  await page.goto('/admin/sessions')
  const row = page.getByRole('row').filter({ hasText: sessionId })
  await expect(row).toBeVisible()
  const historyReady = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat/history' &&
      new URL(response.url()).searchParams.get('session_id') === sessionId
  )
  await row.locator('.session-summary').click()
  const history: ChatHistoryResponse = await dataOf(await historyReady)
  await writeFile(join(evidence, 'session-content-history.json'), JSON.stringify(history, null, 2))
  expect(history.session_id).toBe(sessionId)
  const inputs = history.messages.filter((item) => item.role === 'user')
  expect(inputs).toHaveLength(1)
  expect(inputs[0]!.metadata.content_parts).toEqual(submitted.content)
  expect(inputs[0]!.metadata.attachments).toMatchObject([{ id: attachment.id, name: filename }])
  const panel = page.getByRole('dialog', { name: '会话详情', exact: true })
  await expect(panel).toBeVisible()
  await expect
    .poll(() =>
      panel.evaluate((element) => {
        const rect = element.getBoundingClientRect()
        return rect.left >= -1 && rect.right <= window.innerWidth + 1
      })
    )
    .toBe(true)
  await expect(panel).toContainText(expectedReply)
  const historyMessage = panel.locator('.el-card').filter({ hasText: message })
  await expect(historyMessage).toHaveCount(1)
  if (mode === 'baseline') {
    await expect(historyMessage.locator('img')).toHaveCount(0)
    await expect(historyMessage).not.toContainText(filename)
  } else {
    await expect(historyMessage.locator('img')).toHaveCount(1)
    await expect
      .poll(() =>
        historyMessage
          .locator('img')
          .evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0)
      )
      .toBe(true)
    await expect(historyMessage.getByLabel('已发送附件')).toContainText(filename)
  }
  await writeFile(join(evidence, `session-content-${mode}.html`), await panel.innerHTML())
  await historyMessage.scrollIntoViewIfNeeded()
  await page.screenshot({ path: join(evidence, `session-content-${mode}.png`), fullPage: true })
  if (mode === 'candidate') {
    await page.setViewportSize({ width: 390, height: 844 })
    await expect
      .poll(() =>
        panel.evaluate((element) => {
          const rect = element.getBoundingClientRect()
          return rect.left >= -1 && rect.right <= window.innerWidth + 1
        })
      )
      .toBe(true)
    await expect(historyMessage.getByLabel('已发送附件')).toContainText(filename)
    await historyMessage.getByLabel('已发送附件').scrollIntoViewIfNeeded()
    await expect(historyMessage.getByLabel('已发送附件')).toBeInViewport()
    await expect
      .poll(() => panel.evaluate((element) => element.scrollWidth <= element.clientWidth))
      .toBe(true)
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true)
    await page.screenshot({
      path: join(evidence, 'session-content-candidate-narrow.png'),
      fullPage: true
    })
    await page.setViewportSize({ width: 1280, height: 720 })
  }

  const restored = responseAt(page, '/api/v1/chat/history')
  await page.goto('/admin/chat')
  expect((await dataOf(await restored)).session_id).toBe(sessionId)
  const restoredMessage = page.locator('.debug-message').filter({ hasText: message })
  await expect(restoredMessage.locator('img')).toHaveCount(1)
  await expect
    .poll(() =>
      restoredMessage
        .locator('img')
        .evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0)
    )
    .toBe(true)
  await expect(restoredMessage.getByLabel('已发送附件')).toContainText(filename)
  await expect(page.locator('.debug-message').filter({ hasText: expectedReply })).toHaveCount(1)
  await expect(page.locator('body')).not.toContainText(required('E2E_INTERNAL_REASONING'))
  await page.screenshot({
    path: join(evidence, 'session-content-chat-restored.png'),
    fullPage: true
  })
  await writeFile(
    join(evidence, 'session-content-result.json'),
    JSON.stringify(
      {
        sessionId,
        runId,
        inputId,
        mode,
        submissions,
        sessionCreations,
        historyHasImage: true,
        historyHasAttachment: true,
        managementRendersContent: mode === 'candidate',
        chatRestored: true,
        network,
        errors
      },
      null,
      2
    )
  )
  expect(submissions).toBe(1)
  expect(sessionCreations).toBe(1)
  expect(errors).toEqual([])
})
