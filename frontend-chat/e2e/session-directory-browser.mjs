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
const errors = [],
  chats = []
try {
  await vite.listen()
  const address = vite.httpServer.address()
  assert.equal(typeof address, 'object')
  const origin = `http://127.0.0.1:${address.port}`
  browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
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
          name: '目录验收'
        })
      )
    },
    { ...fixture, origin }
  )
  const page = await context.newPage()
  page.on('pageerror', (error) => errors.push(error.message))
  await page.route('**/api/v1/chat', async (route) => {
    chats.push(route.request().method())
    await route.abort()
  })
  const listResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat/sessions' &&
      response.request().method() === 'GET'
  )
  await page.goto(origin)
  assert.equal((await listResponse).status(), 200)
  await page.getByRole('button', { name: '打开会话导航' }).click()
  const history = page.getByRole('navigation', { name: '历史会话' })
  await expect(history.getByRole('button', { name: /预约复查 目录验收问题/ })).toBeVisible()
  const search = page.getByRole('textbox', { name: '搜索会话' })
  await search.fill('复查')
  await expect(history.getByRole('button', { name: /预约复查 目录验收问题/ })).toBeVisible()
  await search.fill('不存在的关键词')
  await expect(history).toContainText('没有匹配的会话')
  await search.fill('')
  await history.getByRole('button', { name: /预约复查 目录验收问题/ }).click()
  const restored = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/chat/history' &&
      response.request().method() === 'GET'
  )
  await page.reload()
  assert.equal((await restored).status(), 200)
  await page.getByRole('button', { name: '打开会话导航' }).click()
  await expect(history.getByRole('button', { name: /预约复查 目录验收问题/ })).toBeVisible()
  await expect(page.getByText('预约复查 目录验收问题').first()).toBeVisible()
  assert.deepEqual(chats, [])
  assert.deepEqual(errors, [])
  process.stdout.write(
    'PASS: real channel, session list, title, search, and reload; chat submissions=0\n'
  )
} finally {
  if (browser) await browser.close()
  await vite.close()
}
