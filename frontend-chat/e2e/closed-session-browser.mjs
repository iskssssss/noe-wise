/* global process */
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
const f = JSON.parse(process.env.CLOSED_FIXTURE)
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
let browser, context, page
const errors = [],
  chats = [],
  snapshots = [],
  detailReads = []
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
          name: '上传验收'
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
  page = await context.newPage()
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/chat' && request.method() === 'POST') chats.push(path)
    if (path === '/api/v1/chat/sessions/detail') detailReads.push(path)
  })
  page.on('response', async (response) => {
    if (new URL(response.url()).pathname === '/api/v1/chat/history' && response.ok())
      snapshots.push(await response.json())
  })
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true })
  const initial = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/chat/history'
  )
  await page.goto(origin)
  await initial

  const input = page.getByRole('textbox', { name: '消息', exact: true })
  await expect(input).toBeEnabled()
  await input.fill('关闭前的真实运行验收')
  const accepted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat' && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  const result = await accepted
  assert.equal(result.status(), 202)
  const receipt = (await result.json()).data
  await expect(page.getByText(f.reply, { exact: true }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '停止运行', exact: true })).toHaveCount(0)
  const closeResponse = await page.request.post(origin + '/api/v1/chat/sessions/close', {
    headers: { Authorization: `Bearer ${f.adminToken}` },
    data: { session_id: f.session, payload: { reason: '真实关闭会话验收' } }
  })
  assert.equal(closeResponse.status(), 200)
  const closed = await closeResponse.json()
  assert.equal(closed.data.status, 'closed')
  const historyReady = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/chat/history'
  )
  await page.reload()
  assert.equal((await historyReady).status(), 200)
  await expect(page.getByText(f.reply, { exact: true }).first()).toBeVisible()
  const beforeAttempt = chats.length
  let rejected = null
  if (f.baseline) {
    await expect(input).toBeEnabled()
    await input.fill('已关闭会话不应允许编辑发送')
    const denied = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/chat' &&
        response.request().method() === 'POST'
    )
    await page.getByRole('button', { name: '发送消息', exact: true }).click()
    const response = await denied
    assert.ok(response.status() >= 400)
    rejected = { status: response.status(), body: await response.json() }
    assert.equal(chats.length - beforeAttempt, 1)
  } else {
    assert.equal(snapshots.at(-1).data.closed, true)
    assert.deepEqual(detailReads, [])
    await expect(input).toBeDisabled()
    await expect(page.getByLabel('选择文件')).toBeDisabled()
    await expect(page.getByRole('button', { name: '添加文件或图片', exact: true })).toBeDisabled()
    await expect(page.getByText(/会话已关闭/).first()).toBeVisible()
    assert.equal(chats.length - beforeAttempt, 0)
  }
  await page.screenshot({ path: join(f.evidence, 'closed-restored.png'), fullPage: true })
  if (f.baseline) await input.fill('')
  await page.getByRole('button', { name: '新建会话', exact: true }).click()
  await expect(input).toBeEnabled()
  await expect(page.getByText(f.reply, { exact: true })).toHaveCount(0)
  assert.deepEqual(errors, [])
  await writeFile(
    join(f.evidence, 'receipt.json'),
    JSON.stringify(
      {
        runId: receipt.execution_id,
        sessionId: f.session,
        snapshots,
        rejected,
        submissions: chats.length,
        detailReads,
        baseline: f.baseline
      },
      null,
      2
    )
  )
  process.stdout.write(
    'PASS: completed runtime, real close, restored closed session, and fresh composer\n'
  )
} finally {
  if (page) await page.screenshot({ path: join(f.evidence, 'final-state.png'), fullPage: true })
  if (context) await context.tracing.stop({ path: join(f.evidence, 'browser-trace.zip') })
  if (browser) await browser.close()
  await vite.close()
}
