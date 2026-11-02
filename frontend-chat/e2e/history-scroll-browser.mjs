/* global process */
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const fixture = JSON.parse(process.env.DIRECTORY_FIXTURE)
const require = createRequire(new URL('../../frontend-admin/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
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
let browser
try {
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
  browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } })
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
          name: '历史验收'
        })
      )
    },
    { ...fixture, origin }
  )
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  const loaded = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/chat/sessions'
  )
  await page.goto(origin)
  assert.equal((await loaded).status(), 200)
  await page.getByRole('button', { name: '打开会话导航' }).click()
  const history = page.getByRole('navigation', { name: '历史会话' })
  await history.getByRole('button', { name: /预约复查 目录验收问题/ }).click()
  const earlier = page.getByRole('button', { name: '加载更早消息' })
  await expect(earlier).toBeVisible()
  let release, received, finished
  const resumed = new Promise((resolve) => {
    release = resolve
  })
  const responseReady = new Promise((resolve) => {
    received = resolve
  })
  const routeFinished = new Promise((resolve) => {
    finished = resolve
  })
  await page.route('**/api/v1/chat/history?*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.searchParams.has('before_message_id')) return route.continue()
    const response = await route.fetch()
    assert.equal(response.status(), 200)
    received()
    await resumed
    try {
      await route.fulfill({ response })
    } catch (error) {
      if (!route.request().failure()) throw error
    } finally {
      finished()
    }
  })
  await earlier.click()
  await responseReady
  const cancelled = page.waitForEvent('requestfailed', (request) =>
    new URL(request.url()).searchParams.has('before_message_id')
  )
  await page.getByRole('button', { name: '打开会话导航' }).click()
  await history.getByRole('button', { name: /新会话消息0/ }).click()
  await cancelled
  const timeline = page.getByRole('region', { name: '会话消息' })
  await expect(page.getByText('新会话消息34', { exact: false })).toBeVisible()
  await timeline.evaluate((element) => {
    element.scrollTop = 150
  })
  const before = await timeline.evaluate((element) => element.scrollTop)
  assert.ok(before > 0)
  release()
  await routeFinished
  await page.waitForTimeout(200)
  assert.equal(await timeline.evaluate((element) => element.scrollTop), before)
  assert.deepEqual(errors, [])
  process.stdout.write('PASS: delayed real history cannot move another session\n')
} finally {
  if (browser) await browser.close()
  await vite.close()
}
