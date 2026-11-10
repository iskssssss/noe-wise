/* global process */
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const f = JSON.parse(process.env.INPUT_RETRY_FIXTURE)
assert.ok(['baseline', 'candidate'].includes(f.mode))
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
let submitted = false,
  observe = false
const statuses = [],
  requests = [],
  responses = [],
  errors = [],
  blockedReads = []
let accepted, rejected, original, repeated, closed, restored
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
          name: '输入重试验收'
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
    {
      origin,
      channel: f.channel,
      clientRef: f.clientRef,
      identity: f.identity,
      token: f.token,
      agent: f.agent,
      session: f.session
    }
  )
  page = await context.newPage()
  page.on('pageerror', (error) => errors.push(error.message))
  await page.route('**/api/v1/chat/events?**', async (route) => {
    if (!observe) {
      blockedReads.push({ path: '/chat/events', at: Date.now() })
      return route.abort('connectionreset')
    }
    return route.continue()
  })
  await page.route('**/api/v1/chat/history?**', async (route) => {
    if (submitted && !observe) {
      blockedReads.push({ path: '/chat/history', at: Date.now() })
      return route.abort('connectionreset')
    }
    const response = await route.fetch({ timeout: 20000 })
    const body = await response.json()
    responses.push({ path: '/chat/history', status: response.status(), body })
    if (observe) restored = body
    return route.fulfill({ response })
  })
  await page.route('**/api/v1/chat', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    submitted = true
    const body = route.request().postDataJSON()
    requests.push(body)
    const response = await route.fetch({ timeout: 20000 })
    const result = { status: response.status(), body: await response.json() }
    responses.push({ path: '/chat', ...result })
    if (requests.length === 1) {
      original = body
      accepted = result
      assert.equal(result.status, 202)
      assert.equal(result.body.code, 'OK')
      assert.equal(body.session_id, f.session)
      assert.ok(body.client_message_id)
      assert.ok(result.body.data.execution_id)
      await writeFile(
        join(f.evidence, 'accepted.json'),
        JSON.stringify({ request: original, response: accepted }, null, 2)
      )
      return route.abort('connectionreset')
    }
    assert.equal(requests.length, 2, '只允许一次初始提交与一次显式原请求重试')
    repeated = body
    rejected = result
    assert.deepEqual(repeated, original)
    assert.equal(result.status, 409)
    await writeFile(
      join(f.evidence, 'rejected-retry.json'),
      JSON.stringify({ request: repeated, response: rejected }, null, 2)
    )
    return route.fulfill({ response })
  })
  await context.tracing.start({ screenshots: true, snapshots: true, sources: false })
  const initial = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/chat/history'
  )
  await page.goto(origin)
  assert.equal((await initial).status(), 200)
  const input = page.getByRole('textbox', { name: '消息', exact: true })
  await expect(input).toBeEnabled()
  await input.fill('原请求已执行但客户端回执丢失，请保留原输入身份。')
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await expect(
    page.getByText('上次提交结果尚未确认。请先恢复观察；仍需重试时，使用下方的原请求入口。', {
      exact: true
    })
  ).toBeVisible()
  await expect(input).toBeDisabled()
  await expect.poll(() => accepted?.status).toBe(202)

  await expect
    .poll(
      async () => {
        const response = await fetch(
          `${f.backend}/api/v1/chat/runs/status?session_id=${encodeURIComponent(f.session)}&run_id=${encodeURIComponent(accepted.body.data.execution_id)}`,
          {
            headers: { Authorization: `Bearer ${f.adminToken}` },
            signal: AbortSignal.timeout(10000)
          }
        )
        const body = await response.json()
        statuses.push({ status: response.status, body })
        assert.equal(response.status, 200)
        return body.data.status
      },
      { timeout: 90000, intervals: [1000] }
    )
    .toMatch(/^(completed|failed|cancelled)$/)
  assert.equal(statuses.at(-1).body.data.status, 'completed')
  await writeFile(
    join(f.evidence, 'original-runtime-status.json'),
    JSON.stringify(statuses, null, 2)
  )
  const closeResponse = await fetch(`${f.backend}/api/v1/chat/sessions/close`, {
    method: 'POST',
    signal: AbortSignal.timeout(10000),
    headers: { Authorization: `Bearer ${f.adminToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ session_id: f.session, payload: { reason: '输入结果未知重试验收' } })
  })
  closed = { status: closeResponse.status, body: await closeResponse.json() }
  assert.equal(closed.status, 200)
  assert.equal(closed.body.data.status, 'closed')
  await writeFile(join(f.evidence, 'closed.json'), JSON.stringify(closed, null, 2))
  await expect(page.getByRole('button', { name: '重试原请求', exact: true })).toBeEnabled()
  const retryResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat' && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '重试原请求', exact: true }).click()
  assert.equal((await retryResponse).status(), 409)
  const unknown = page.getByText(
    '上次提交结果尚未确认。请先恢复观察；仍需重试时，使用下方的原请求入口。',
    { exact: true }
  )
  const newSession = page.getByRole('button', { name: '新建会话', exact: true })
  if (f.mode === 'baseline') {
    await expect(unknown).toHaveCount(0)
    await expect(input).toBeEnabled()
    await expect(newSession).toBeEnabled()
  } else {
    await expect(unknown).toBeVisible()
    await expect(input).toBeDisabled()
    await expect(page.getByRole('button', { name: '发送消息', exact: true })).toBeDisabled()
    await expect(newSession).toBeDisabled()
    await page.getByRole('button', { name: '打开会话导航', exact: true }).click()
    const history = page.getByRole('navigation', { name: '历史会话', exact: true })
    await expect(history.getByRole('button')).not.toHaveCount(0)
    for (const button of await history.getByRole('button').all())
      await expect(button).toBeDisabled()
    await page.getByRole('button', { name: '收起会话导航', exact: true }).click()
  }
  const beforeRecovery = await page.locator('body').innerText()
  await writeFile(join(f.evidence, 'after-retry.html'), await page.content())
  await page.screenshot({ path: join(f.evidence, 'after-retry.png'), fullPage: true })
  observe = true
  const recovery = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/chat/history'
  )
  if (f.mode === 'candidate')
    await page.getByRole('button', { name: '恢复观察', exact: true }).click()
  else await page.reload()
  assert.equal((await recovery).status(), 200)
  await expect(page.getByText(f.reply, { exact: true })).toBeVisible()
  await expect(unknown).toHaveCount(0)
  assert.equal(restored.data.session_id, f.session)
  const originalMessages = restored.data.messages.filter((message) => message.role === 'user')
  assert.equal(originalMessages.length, 1)
  assert.equal(originalMessages[0].message_id, original.client_message_id)
  assert.equal(requests.length, 2)
  assert.deepEqual(errors, [])
  await writeFile(join(f.evidence, 'restored-history.json'), JSON.stringify(restored, null, 2))
  await page.screenshot({ path: join(f.evidence, 'restored.png'), fullPage: true })
  await writeFile(
    join(f.evidence, 'receipt.json'),
    JSON.stringify(
      {
        mode: f.mode,
        sessionId: f.session,
        inputId: original.client_message_id,
        runId: accepted.body.data.execution_id,
        submissions: requests.length,
        original,
        repeated,
        accepted,
        rejected,
        closed,
        beforeRecovery,
        restored,
        blockedReads,
        errors
      },
      null,
      2
    )
  )
  process.stdout.write(
    f.mode === 'baseline'
      ? 'PASS: 已复现重试拒绝丢失原请求，真实历史确认原输入已执行\n'
      : 'PASS: 重试拒绝保留原请求，只读恢复确认原输入且无重复执行\n'
  )
} finally {
  await writeFile(
    join(f.evidence, 'http-evidence.json'),
    JSON.stringify(
      { requests, responses, statuses, accepted, rejected, closed, blockedReads, errors },
      null,
      2
    )
  )
  if (page) {
    await writeFile(join(f.evidence, 'final-state.html'), await page.content())
    await page.screenshot({ path: join(f.evidence, 'final-state.png'), fullPage: true })
  }
  if (context) await context.tracing.stop({ path: f.tracePath })
  if (browser) await browser.close()
  await vite.close()
}
