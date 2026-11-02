/* global process */
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

// 凭据仅由隔离Java夹具注入；产品按标签页保存，证据只记录认证方式和请求事实。
const fixture = JSON.parse(process.env.STREAM_FIXTURE)
const require = createRequire(new URL('../../frontend-admin/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const root = fileURLToPath(new URL('../', import.meta.url))
const prompts = JSON.parse(
  await readFile(
    new URL('../../tools/full_project_testing/fixtures/user_prompts.json', import.meta.url),
    'utf8'
  )
)
const scenario = prompts.scenarios.find((item) => item.id === 'CHAT-PROMPT-029')
assert.ok(scenario, '必须消费登记的真实表达合成场景')
const proxy = {
  target: fixture.backend,
  changeOrigin: true,
  configure(server) {
    server.on('proxyReq', (request) => {
      // 仅适配隔离Vite随机端口的Origin；渠道凭证与签名不替换、不注入管理员身份。
      request.setHeader('Origin', 'http://localhost:3200')
    })
  }
}
const vite = await createServer({
  root,
  server: { host: '127.0.0.1', port: 0, strictPort: false, proxy: { '/api': proxy } }
})
let browser
const errors = [],
  snapshots = [],
  authentication = []
let releaseReceipt
let submitted
try {
  await mkdir(fixture.evidence, { recursive: true })
  await vite.listen()
  const address = vite.httpServer.address()
  assert.equal(typeof address, 'object')
  browser = await chromium.launch({ headless: true })
  const url = `http://127.0.0.1:${address.port}/`
  const signedContext = await browser.newContext()
  const signedPage = await signedContext.newPage()
  signedPage.on('pageerror', (error) => errors.push(error.message))
  await signedPage.goto(url)
  await signedPage.getByRole('textbox', { name: '你的称呼' }).fill('合成报告核对用户')
  await signedPage.getByRole('combobox', { name: '认证方式' }).click()
  await signedPage.getByRole('option', { name: 'API 签名', exact: true }).click()
  await signedPage.getByRole('textbox', { name: 'API Key', exact: true }).fill(fixture.apiKey)
  await signedPage.getByLabel(/^API Secret\s*\*?$/).fill(fixture.apiSecret)
  const signedProfile = signedPage.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat/client-profile' &&
      response.request().method() === 'POST'
  )
  await signedPage.getByRole('button', { name: '进入会话' }).click()
  assert.equal((await signedProfile).status(), 200)
  await expect(signedPage.getByRole('textbox', { name: '消息', exact: true })).toBeEnabled()
  authentication.push({ mode: 'hmac', realProfileRegistration: true, modelCalls: 0 })
  await signedContext.close()
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto(url)
  await page.getByRole('textbox', { name: '你的称呼' }).fill('流式验收')
  await page.locator('input[type="password"]').fill(fixture.channelToken)
  const tokenProfile = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat/client-profile' &&
      response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '进入会话' }).click()
  const input = page.getByRole('textbox', { name: '消息', exact: true })
  await expect(input).toBeEnabled()
  assert.equal((await tokenProfile).status(), 200)
  authentication.push({ mode: 'token', realProfileRegistration: true })
  const invalid = await fetch(`${url}api/v1/chat/agents`, {
    headers: { 'X-Channel-Token': 'synthetic-invalid-token' }
  })
  assert.equal(invalid.status, 401)
  const receiptGate = new Promise((resolve) => {
    releaseReceipt = resolve
  })
  let resolveReceipt, rejectReceipt
  const receiptDone = new Promise((resolve, reject) => {
    resolveReceipt = resolve
    rejectReceipt = reject
  })
  // 流式断言失败时finally会关闭页面；立即登记处理器，保留后续await原始失败。
  receiptDone.catch(() => {})
  await page.route('**/api/v1/chat', async (route) => {
    try {
      submitted = route.request().postDataJSON()
      const receipt = await route.fetch()
      assert.ok(receipt.status() >= 200 && receipt.status() < 300)
      await receiptGate
      await route.fulfill({ response: receipt })
      resolveReceipt()
    } catch (error) {
      rejectReceipt(error)
    }
  })
  await input.fill(scenario.turns[0].text)
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  const body = page.locator('.message.assistant .message-body')
  const waitForProviderStage = async (stage) => {
    await expect
      .poll(
        async () => {
          const response = await fetch(`${fixture.control}/status`)
          assert.equal(response.status, 200)
          const status = await response.json()
          assert.equal(status.providerCompleted, false)
          return status.stage
        },
        { timeout: 60000 }
      )
      .toBe(stage)
  }
  const assertFinalArgumentsHidden = async () => {
    await expect(
      page.locator('.message.assistant').filter({ hasText: 'FIRST_STREAM_VISIBLE' })
    ).toHaveCount(0)
    await expect(
      page.locator('.message.assistant').filter({ hasText: 'SECOND_STREAM_VISIBLE' })
    ).toHaveCount(0)
    await expect(
      page.locator('.message.assistant').filter({ hasText: 'STREAM_FINISHED' })
    ).toHaveCount(0)
  }
  // 参数确已从真实供应商 flush；完整结果和本地引用门禁确认前不可展示最终正文。
  await waitForProviderStage(1)
  await assertFinalArgumentsHidden()
  snapshots.push({ stage: 'first-arguments-held', length: 0, at: Date.now() })
  await page.screenshot({ path: `${fixture.evidence}/first-desktop.png` })
  assert.ok(submitted.session_id && submitted.client_message_id)
  assert.equal(submitted.content[0].text, scenario.turns[0].text)
  const controlBody = JSON.stringify({
    session_id: submitted.session_id,
    input_id: submitted.client_message_id,
    end_user_id: submitted.context.end_user_id
  })
  const forbidden = await fetch(
    `${url}api/v1/chat/history?session_id=${encodeURIComponent(submitted.session_id)}`,
    { headers: { 'X-Channel-Token': fixture.otherChannelToken } }
  )
  assert.equal(forbidden.status, 403)
  const advance = await fetch(`${fixture.control}/advance`, { method: 'POST', body: controlBody })
  assert.equal(advance.status, 200, await advance.text())
  await waitForProviderStage(2)
  await assertFinalArgumentsHidden()
  snapshots.push({ stage: 'second-arguments-held', length: 0, at: Date.now() })
  await page.screenshot({ path: `${fixture.evidence}/second-desktop.png` })
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 568 })
    await assertFinalArgumentsHidden()
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    await page.screenshot({ path: `${fixture.evidence}/second-mobile-${width}.png` })
  }
  const finish = await fetch(`${fixture.control}/finish`, { method: 'POST', body: controlBody })
  assert.equal(finish.status, 200, await finish.text())
  await expect(body).toContainText('STREAM_FINISHED', { timeout: 60000 })
  await expect(page.locator('.process-progress-label')).toHaveText('执行完成', { timeout: 60000 })
  await expect(body).toHaveCount(1)
  const segment = page.locator('.message.assistant')
  const messageId = await segment.getAttribute('data-message-id')
  assert.ok(messageId)
  await expect(segment).toHaveAttribute('data-message-phase', 'final')
  const completed = await body.innerText()
  for (const marker of ['FIRST_STREAM_VISIBLE', 'SECOND_STREAM_VISIBLE', 'STREAM_FINISHED']) {
    assert.equal(completed.split(marker).length - 1, 1, `${marker} 应在确认后恰好交付一次`)
  }
  snapshots.push({ stage: 'completed', length: completed.length, at: Date.now() })
  await input.fill(scenario.turns[1].text)
  releaseReceipt()
  await receiptDone
  await expect(input).toHaveValue(scenario.turns[1].text)
  await expect(page.locator('.process-progress-label')).toHaveText('执行完成')
  assert.equal(await body.innerText(), completed)
  assert.ok(!(await page.locator('body').innerText()).includes('fixture-reasoning-'))
  await page.getByRole('button', { name: '更多会话操作' }).click()
  await page.getByRole('button', { name: '重新连接', exact: true }).click()
  await expect(body).toContainText('STREAM_FINISHED')
  await expect(body).toHaveCount(1)
  await expect(segment).toHaveAttribute('data-message-id', messageId)
  assert.equal(await body.innerText(), completed)
  await page.screenshot({ path: `${fixture.evidence}/reconnected-mobile.png` })
  assert.deepEqual(errors, [])
  await writeFile(
    `${fixture.evidence}/browser-evidence.json`,
    JSON.stringify(
      {
        snapshots,
        errors,
        authentication,
        unauthorizedRejected: true,
        otherChannelRejected: true,
        lateReceiptPreservedNewDraft: true,
        bootstrapMocked: false,
        promptScenario: scenario.id,
        promptTurnsConsumed: [0, 1],
        reconnectedSameMessage: true,
        stableMessageIdentity: true,
        finalArgumentsHiddenUntilConfirmed: true
      },
      null,
      2
    )
  )
} finally {
  releaseReceipt?.()
  await browser?.close()
  await vite.close()
}
