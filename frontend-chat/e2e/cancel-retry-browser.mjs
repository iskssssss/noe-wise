/* global process */
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const fixture = JSON.parse(process.env.CANCEL_RETRY_FIXTURE)
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
  cancels: [],
  statuses: [],
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
            name: '取消验收'
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
  await page.route('**/api/v1/chat/runs/cancel', async (route) => {
    evidence.cancels.push(route.request().postDataJSON())
    if (evidence.cancels.length === 1) return route.abort('failed')
    return route.continue()
  })
  page.on('response', async (response) => {
    const url = new URL(response.url())
    if (url.pathname === '/api/v1/chat/runs/status' && response.ok()) {
      try {
        evidence.statuses.push({
          path: url.pathname + url.search,
          status: response.status(),
          body: await response.json()
        })
      } catch {}
    }
  })
  const initial = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/chat/history' && response.ok()
  )
  await page.goto(origin)
  assert.equal((await (await initial).json()).data.session_id, fixture.session)
  const input = page.getByRole('textbox', { name: '消息', exact: true })
  await expect(input).toBeEnabled()
  await input.fill('请直接回答取消验收问题。')
  const accepted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat' && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  const response = await accepted
  assert.equal(response.status(), 202)
  evidence.runId = (await response.json()).data.execution_id
  assert.ok(evidence.runId)
  const preview = page.locator(`article.preview-message[data-preview-run="${evidence.runId}"]`)
  await expect(preview).toContainText('FIRST_STREAM_VISIBLE', { timeout: 40000 })
  const status = page.waitForResponse((item) => {
    const url = new URL(item.url())
    return (
      url.pathname === '/api/v1/chat/runs/status' &&
      url.searchParams.get('run_id') === evidence.runId &&
      item.ok()
    )
  })
  await page.getByRole('button', { name: '停止运行', exact: true }).click()
  const firstStatus = await status
  evidence.firstStatus = await firstStatus.json()
  assert.equal(evidence.firstStatus.data.status, 'running')
  assert.equal(evidence.cancels.length, 1)
  assert.equal(evidence.cancels[0].session_id, fixture.session)
  assert.equal(evidence.cancels[0].run_id, evidence.runId)
  await writeFile(join(fixture.evidence, 'run-id.txt'), evidence.runId)
  await writeFile(join(fixture.evidence, 'inspect-before'), 'inspect')
  await expect
    .poll(
      async () => {
        try {
          await import('node:fs/promises').then((fs) =>
            fs.access(join(fixture.evidence, 'before-ready'))
          )
          return true
        } catch {
          return false
        }
      },
      { timeout: 30000 }
    )
    .toBe(true)
  evidence.beforeText = await page.locator('body').innerText()
  await page.screenshot({
    path: join(fixture.evidence, `cancel-running-${fixture.mode}.png`),
    fullPage: true
  })
  if (fixture.mode === 'baseline') {
    await expect(page.getByRole('button', { name: '正在取消运行', exact: true })).toBeDisabled()
    await expect(page.getByRole('button', { name: '继续取消', exact: true })).toHaveCount(0)
    assert.equal(evidence.cancels.length, 1)
  } else {
    const retry = page.getByRole('button', { name: '继续取消', exact: true })
    await expect(retry).toBeEnabled()
    await expect(page.getByText('Failed to fetch', { exact: true })).toHaveCount(0)
    const cancelled = page.waitForResponse(
      (item) =>
        new URL(item.url()).pathname === '/api/v1/chat/runs/cancel' &&
        item.request().method() === 'POST'
    )
    await retry.click()
    const response = await cancelled
    evidence.retryStatus = response.status()
    evidence.retryBody = await response.json()
    assert.equal(response.status(), 200)
    assert.equal(evidence.cancels.length, 2)
    assert.deepEqual(evidence.cancels[1], evidence.cancels[0])
    await expect
      .poll(
        () =>
          evidence.statuses.some((item) => item.body.data.status === 'cancelled') ||
          evidence.retryBody.data.status === 'cancelled',
        { timeout: 40000 }
      )
      .toBe(true)
    await expect(page.getByRole('button', { name: '继续取消', exact: true })).toHaveCount(0)
    await page.screenshot({ path: join(fixture.evidence, 'cancel-completed.png'), fullPage: true })
  }
  assert.equal(evidence.submissions.length, 1)
  assert.deepEqual(evidence.errors, [])
} catch (error) {
  evidence.error = String(error)
  throw error
} finally {
  await writeFile(join(fixture.evidence, 'receipt.json'), JSON.stringify(evidence, null, 2))
  if (browser) await browser.close()
  await vite.close()
}
