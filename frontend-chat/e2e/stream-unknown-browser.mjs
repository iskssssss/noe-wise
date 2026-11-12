/* global process */
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const fixture = JSON.parse(process.env.STREAM_UNKNOWN_FIXTURE)
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
const evidence = { session: fixture.session, submissions: [], historyReads: 0, errors: [] }
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
            name: '断流验收'
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
  const initial = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/chat/history' && response.ok()
  )
  await page.goto(origin)
  assert.equal((await (await initial).json()).data.session_id, fixture.session)
  const input = page.getByRole('textbox', { name: '消息', exact: true })
  await expect(input).toBeEnabled()
  await input.fill('请直接回答断流验收问题。')
  const accepted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat' && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  const response = await accepted
  assert.equal(response.status(), 202)
  evidence.runId = (await response.json()).data.execution_id
  assert.ok(evidence.runId)
  const events = page.evaluate(
    async ({ session, token, identity, run }) => {
      const controller = new AbortController(),
        timer = setTimeout(() => controller.abort(), 60000)
      const response = await fetch(
        '/api/v1/chat/events?session_id=' + encodeURIComponent(session),
        {
          headers: {
            'X-Channel-Token': token,
            'X-Chat-User-Token': identity,
            Accept: 'text/event-stream'
          },
          signal: controller.signal
        }
      )
      const reader = response.body.getReader(),
        decoder = new TextDecoder(),
        previews = [],
        processes = [],
        received = []
      let buffer = ''
      try {
        while (true) {
          const chunk = await reader.read()
          buffer += decoder.decode(chunk.value, { stream: !chunk.done }).replace(/\r/g, '')
          const blocks = buffer.split('\n\n')
          buffer = blocks.pop()
          for (const block of blocks) {
            const lines = block.split('\n')
            const event = lines
              .find((line) => line.startsWith('event:'))
              ?.slice(6)
              .trim()
            if (event !== 'message.preview' && event !== 'process.finished') continue
            const data = lines
              .filter((line) => line.startsWith('data:'))
              .map((line) => line.slice(5).trim())
              .join('\n')
            if (!data) continue
            const value = JSON.parse(data)
            if (value.run_id !== run) continue
            const id =
              lines
                .find((line) => line.startsWith('id:'))
                ?.slice(3)
                .trim() ?? ''
            received.push({ id, event, data: value })
            if (event === 'message.preview') previews.push(value)
            else if (value.type === 'reasoning' && value.status === 'outcome_unknown')
              processes.push(value)
            if (previews.some((item) => item.state === 'unknown') && processes.length)
              return { status: response.status, previews, processes, received }
          }
          if (chunk.done) throw Error('preview_or_model_unknown_not_observed')
        }
      } finally {
        clearTimeout(timer)
        await reader.cancel()
        controller.abort()
      }
    },
    {
      session: fixture.session,
      token: fixture.token,
      identity: fixture.identity,
      run: evidence.runId
    }
  )
  const preview = page.locator(`article.preview-message[data-preview-run="${evidence.runId}"]`)
  await expect(preview).toContainText('FIRST_STREAM_VISIBLE', { timeout: 40000 })
  evidence.before = await page.locator('body').innerText()
  await page.screenshot({
    path: join(fixture.evidence, 'preview-before-abort.png'),
    fullPage: true
  })
  await writeFile(join(fixture.evidence, 'abort-model'), 'abort')
  evidence.events = await events
  assert.equal(evidence.events.status, 200)
  assert.ok(
    evidence.events.previews.some(
      (item) =>
        item.state === 'streaming' &&
        item.authoritative === false &&
        item.content.includes('FIRST_STREAM_VISIBLE')
    )
  )
  assert.equal(evidence.events.previews.at(-1).authoritative, false)
  assert.equal(evidence.events.previews.at(-1).state, 'unknown')
  assert.equal(evidence.events.processes[0].status, 'outcome_unknown')
  await expect(preview).toHaveCount(0, { timeout: 20000 })
  const toggle = page.locator('.process-toggle').first()
  await expect(toggle).toBeVisible()
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') await toggle.click()
  const trace = page.locator('[data-process-trace-toggle]').first()
  if ((await trace.isVisible()) && (await trace.getAttribute('aria-pressed')) !== 'true')
    await trace.click()
  const process = page.locator('.process-step').filter({ hasText: '模型调用' })
  await expect(process).toHaveAttribute('data-process-state', 'outcome_unknown', { timeout: 20000 })
  await expect(page.getByText('已连接 · 结果待核实', { exact: true })).toBeVisible({
    timeout: 20000
  })
  await expect(page.getByRole('button', { name: '发送补充', exact: true })).toHaveCount(0)
  const statusResponse = await page.request.get(
    origin +
      '/api/v1/chat/runs/status?session_id=' +
      encodeURIComponent(fixture.session) +
      '&run_id=' +
      encodeURIComponent(evidence.runId),
    {
      headers: { 'X-Channel-Token': fixture.token, 'X-Chat-User-Token': fixture.identity }
    }
  )
  evidence.statusHttp = statusResponse.status()
  evidence.status = await statusResponse.json()
  assert.equal(evidence.status.data.status, 'outcome_unknown')
  evidence.after = await page.locator('body').innerText()
  evidence.inputEnabled = await input.isEnabled()
  evidence.stopVisible = await page
    .getByRole('button', { name: '停止运行', exact: true })
    .isVisible()
  await page.screenshot({ path: join(fixture.evidence, 'unknown-after-abort.png'), fullPage: true })
  const reload = page.waitForResponse(
    (item) => new URL(item.url()).pathname === '/api/v1/chat/history' && item.ok()
  )
  await page.reload()
  const history = (await (await reload).json()).data
  assert.equal(history.session_id, fixture.session)
  await expect(page.getByText(/ · 结果待核实$/)).toBeVisible({ timeout: 20000 })
  await expect(page.getByRole('button', { name: '发送补充', exact: true })).toHaveCount(0, {
    timeout: 20000
  })
  evidence.history = history
  evidence.reloadVisible = await page.locator('body').innerText()
  evidence.reloadInputEnabled = await input.isEnabled()
  evidence.reloadPreviewCount = await page.locator('article.preview-message').count()
  await page.screenshot({
    path: join(fixture.evidence, 'unknown-after-reload.png'),
    fullPage: true
  })
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
