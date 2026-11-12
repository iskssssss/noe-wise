/* global process */
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const fixture = JSON.parse(process.env.ACCEPTED_HISTORY_FIXTURE)
const { chromium, expect } = createRequire(
  new URL('../../frontend-admin/package.json', import.meta.url)
)('@playwright/test')
const vite = await createServer({
  root: fileURLToPath(new URL('../', import.meta.url)),
  server: {
    host: '127.0.0.1',
    port: 0,
    proxy: {
      '/api': {
        target: fixture.backend,
        changeOrigin: true,
        configure(server) {
          server.on('proxyReq', (request) => request.setHeader('Origin', 'http://localhost:3200'))
        }
      }
    }
  }
})
let browser, page
const evidence = {
  mode: fixture.mode,
  session: fixture.session,
  submissions: [],
  historyReads: 0,
  errors: []
}
try {
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
  browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await context.addInitScript(
    (input) => {
      const profileKey = `skillforge.chat.profile:${input.origin}:${input.channel}`
      if (!localStorage.getItem(profileKey))
        localStorage.setItem(
          profileKey,
          JSON.stringify({ client_ref: input.clientRef, identity_token: input.identity })
        )
      if (!sessionStorage.getItem('skillforge.chat.connection'))
        sessionStorage.setItem(
          'skillforge.chat.connection',
          JSON.stringify({
            address: input.origin,
            mode: 'token',
            token: input.token,
            name: '受理历史验收'
          })
        )
      if (!sessionStorage.getItem('skillforge.chat.selection'))
        sessionStorage.setItem(
          'skillforge.chat.selection',
          JSON.stringify({
            clientRef: input.clientRef,
            target: `agent:${input.agent}`,
            session: input.session
          })
        )
    },
    { ...fixture, origin }
  )
  page = await context.newPage()
  page.on('pageerror', (error) => evidence.errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/chat' && request.method() === 'POST')
      evidence.submissions.push(request.postDataJSON())
    if (path === '/api/v1/chat/history' && request.method() === 'GET') evidence.historyReads++
  })
  let delivered = false,
    dropped = false,
    allowEvents = false
  const histories = [],
    submissions = []
  await page.route('**/api/v1/chat/events?**', async (route) => {
    if (!allowEvents) return route.abort('connectionreset')
    await route.continue()
  })
  await page.route('**/api/v1/chat/history?**', async (route) => {
    const response = await route.fetch({ timeout: 20000 })
    const body = await response.json()
    histories.push({ status: response.status(), body, dropped: delivered && !dropped })
    if (delivered && !dropped) {
      await writeFile(
        join(fixture.evidence, 'dropped-history.json'),
        JSON.stringify({ status: response.status(), body }, null, 2)
      )
      assert.equal(response.status(), 200)
      await route.abort('connectionreset')
      dropped = true
      return
    }
    await route.fulfill({ response })
  })
  await page.route('**/api/v1/chat', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    const request = route.request().postDataJSON(),
      response = await route.fetch({ timeout: 20000 }),
      body = await response.json()
    submissions.push({ request, status: response.status(), body })
    assert.equal(response.status(), 202)
    delivered = true
    await writeFile(
      join(fixture.evidence, 'accepted.json'),
      JSON.stringify({ request, status: response.status(), body }, null, 2)
    )
    await route.fulfill({ response })
  })
  const initial = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/chat/history' && response.ok()
  )
  await page.goto(origin)
  assert.equal((await (await initial).json()).data.session_id, fixture.session)
  const input = page.getByRole('textbox', { name: '消息', exact: true })
  await expect(input).toBeEnabled()
  const text = '受理成功后检查历史读取情况。'
  await input.fill(text)
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await expect.poll(() => dropped, { timeout: 30000 }).toBe(true)
  await expect.poll(async () => await input.inputValue()).toBe('')
  await page.waitForTimeout(300)
  await writeFile(
    join(fixture.evidence, 'before-release.txt'),
    await page.locator('body').innerText()
  )
  await page.screenshot({ path: join(fixture.evidence, 'before-release.png'), fullPage: true })
  allowEvents = true
  if (fixture.mode === 'candidate') {
    await expect(
      page.getByText('消息已受理，但会话记录读取失败，请重新连接。', { exact: true })
    ).toBeVisible()
    await expect(page.getByText(text, { exact: true })).toHaveCount(1)
    const nextDraft = '尚未发送的下一条草稿'
    await input.fill(nextDraft)
    const recovery = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/v1/chat/history' && response.ok()
    )
    await page.getByRole('button', { name: '更多会话操作', exact: true }).click()
    await page.getByRole('button', { name: '重新连接', exact: true }).click()
    const restored = await recovery
    const restoredBody = await restored.json()
    assert.equal(restoredBody.data.session_id, fixture.session)
    assert.equal(
      restoredBody.data.messages.filter(
        (message) => message.message_id === submissions[0].request.client_message_id
      ).length,
      1
    )
    await expect(input).toHaveValue(nextDraft)
    await expect(page.getByText(text, { exact: true })).toHaveCount(1)
    assert.equal(submissions.length, 1)
    await writeFile(
      join(fixture.evidence, 'recovery-history.json'),
      JSON.stringify(restoredBody, null, 2)
    )
    await page.screenshot({ path: join(fixture.evidence, 'recovered.png'), fullPage: true })
  }
  await writeFile(join(fixture.evidence, 'release-model'), 'release')
  await expect(page.getByText('STREAM_FINISHED', { exact: false }).last()).toBeVisible({
    timeout: 90000
  })
  await page.screenshot({ path: join(fixture.evidence, 'completed.png'), fullPage: true })
  assert.equal(submissions.length, 1)
  if (fixture.mode === 'candidate') await expect(input).toHaveValue('尚未发送的下一条草稿')
  assert.deepEqual(evidence.errors, [])
  await writeFile(
    join(fixture.evidence, 'receipt.json'),
    JSON.stringify(
      {
        runId: submissions[0].body.data.execution_id,
        inputId: submissions[0].request.client_message_id,
        submissions: submissions.length,
        histories,
        errors: evidence.errors
      },
      null,
      2
    )
  )
} finally {
  await writeFile(
    join(fixture.evidence, 'browser-final.html'),
    await page
      .locator('body')
      .innerHTML()
      .catch(() => '')
  )
  await browser?.close()
  await vite.close()
}
