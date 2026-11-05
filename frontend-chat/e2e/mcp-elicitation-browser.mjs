/* global process */
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
const f = JSON.parse(process.env.ELICITATION_FIXTURE)
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
        target: f.backend,
        changeOrigin: true,
        configure(server) {
          server.on('proxyReq', (request) => request.setHeader('Origin', 'http://localhost:3200'))
        }
      }
    }
  }
})
let browser, context
const posts = []
let releaseHistory
let loadingObservation
try {
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
  browser = await chromium.launch({ headless: true })
  context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await context.addInitScript(
    (input) => {
      localStorage.setItem(
        `skillforge.chat.profile:${input.origin}:${input.channel}`,
        JSON.stringify({ client_ref: input.clientRef, identity_token: input.identity })
      )
      sessionStorage.setItem(
        'skillforge.chat.connection',
        JSON.stringify({
          address: input.origin,
          mode: 'token',
          token: input.token,
          name: '表单续答验收'
        })
      )
      sessionStorage.setItem(
        'skillforge.chat.selection',
        JSON.stringify({
          clientRef: input.clientRef,
          target: `agent:${input.agent}`,
          session: input.session
        })
      )
    },
    { ...f, origin }
  )
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true })
  const page = await context.newPage()
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/v1/chat' && request.method() === 'POST')
      posts.push(request.postDataJSON())
  })
  await page.goto(origin)
  const card = page.getByRole('region', { name: '需要你的确认', exact: true })
  await expect(card).toBeVisible({ timeout: 30000 })
  await expect(page.getByText('正在读取会话…', { exact: true })).toHaveCount(0, { timeout: 30000 })
  if (process.env.E2E_MCP_LOADING) {
    await card.getByRole('spinbutton').fill('27')
    let reached
    const historyReached = new Promise((resolve) => {
      reached = resolve
    })
    const historyGate = new Promise((resolve) => {
      releaseHistory = resolve
    })
    await page.route(
      '**/api/v1/chat/history?*',
      async (route) => {
        const actual = await route.fetch()
        reached()
        await historyGate
        await route.fulfill({ response: actual })
      },
      { times: 1 }
    )
    await page.getByRole('button', { name: '更多会话操作', exact: true }).click()
    await page.getByRole('button', { name: '重新连接', exact: true }).click()
    await historyReached
    await expect(page.getByText('正在读取会话…', { exact: true })).toBeVisible()
    await expect(card).toBeVisible()
    const submit = card.getByRole('button', { name: '提交信息', exact: true })
    const enabled = await submit.isEnabled()
    if (process.env.E2E_MCP_LOADING === 'baseline') {
      assert.equal(enabled, true)
      await submit.click()
      await page.waitForTimeout(250)
      assert.equal(posts.length, 0)
    } else {
      await expect(submit).toBeDisabled()
      await expect(card.getByRole('button', { name: '拒绝提供', exact: true })).toBeDisabled()
    }
    assert.equal(posts.length, 0)
    loadingObservation = {
      mode: process.env.E2E_MCP_LOADING,
      enabled,
      postsDuringLoading: posts.length,
      visible: await page.locator('body').innerText()
    }
    await page.screenshot({ path: join(f.evidence, 'browser-history-loading.png'), fullPage: true })
    releaseHistory()
    await expect(page.getByText('正在读取会话…', { exact: true })).toHaveCount(0)
  }
  await page.screenshot({ path: join(f.evidence, 'browser-waiting.png'), fullPage: true })
  await card.getByRole('spinbutton').fill('27')
  const pending = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/v1/chat' && r.request().method() === 'POST'
  )
  await card.getByRole('button', { name: '提交信息', exact: true }).click()
  const response = await pending
  assert.equal(response.status(), 202)
  const accepted = await response.json()
  assert.equal(posts.length, 1)
  const reply = posts[0].hitl_response
  assert.equal(reply.execution_id, f.wait.execution_id)
  assert.equal(reply.run_revision, f.wait.run_revision)
  assert.deepEqual(reply.tool_input.wait, f.wait.tool_wait)
  assert.equal(reply.tool_input.responses.quantity.result.schema_id, 'application/json')
  assert.deepEqual(JSON.parse(reply.tool_input.responses.quantity.result.canonical_json), {
    action: 'accept',
    content: { quantity: 27 }
  })
  await expect(page.getByText(f.reply, { exact: true })).toBeVisible({ timeout: 40000 })
  await expect(card).toHaveCount(0)
  await page.screenshot({ path: join(f.evidence, 'browser-completed.png'), fullPage: true })
  await writeFile(
    join(f.evidence, 'browser-receipt.json'),
    JSON.stringify(
      { posts, accepted, loadingObservation, visible: await page.locator('body').innerText() },
      null,
      2
    )
  )
} finally {
  releaseHistory?.()
  if (context) {
    await context.tracing.stop({ path: join(f.evidence, 'browser-trace.zip') })
    await context.close()
  }
  await browser?.close()
  await vite.close()
}
