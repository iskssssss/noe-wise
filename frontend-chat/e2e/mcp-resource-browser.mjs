/* global process */
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const fixture = JSON.parse(process.env.MCP_RESOURCE_FIXTURE)
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
            name: '资源验收'
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
  await input.fill('请直接回答资源验收问题。')
  const accepted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat' && response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  const response = await accepted
  assert.equal(response.status(), 202)
  evidence.runId = (await response.json()).data.execution_id
  assert.ok(evidence.runId)
  if (fixture.mode === 'candidate') {
    await expect(page.getByText(fixture.marker, { exact: false }).last()).toBeVisible({
      timeout: 60000
    })
  } else {
    await page.waitForTimeout(30000)
  }
  evidence.body = await page.locator('body').innerText()
  await page.screenshot({ path: join(fixture.evidence, 'browser.png'), fullPage: true })
  assert.equal(evidence.submissions.length, 1)
} finally {
  if (page) {
    evidence.body = await page
      .locator('body')
      .innerText()
      .catch(() => '')
    await page
      .screenshot({ path: join(fixture.evidence, 'browser-final.png'), fullPage: true })
      .catch(() => {})
  }
  await writeFile(join(fixture.evidence, 'browser.json'), JSON.stringify(evidence, null, 2))
  await browser?.close()
  await vite.close()
}
