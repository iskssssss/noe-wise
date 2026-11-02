/* global process */
import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
const f = JSON.parse(process.env.UPLOAD_FIXTURE)
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
let browser
const errors = [],
  uploads = [],
  chats = []
try {
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
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
  const page = await context.newPage()
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path.endsWith('/upload')) uploads.push(path)
    if (path === '/api/v1/chat' && request.method() === 'POST') chats.push(path)
  })
  await page.goto(origin)
  const input = page.getByLabel('选择文件')
  await expect(input).toBeEnabled()
  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
    'base64'
  )
  await input.setInputFiles([
    { name: 'valid.png', mimeType: 'image/png', buffer: png },
    { name: 'invalid.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('bad') }
  ])
  await expect(page.getByText(/附件 invalid.exe 的类型或大小不符合限制/)).toBeVisible()
  assert.equal(uploads.length, 0)
  const attachment = page.waitForResponse((response) =>
    new URL(response.url()).pathname.endsWith('/attachments/upload')
  )
  const media = page.waitForResponse((response) =>
    new URL(response.url()).pathname.endsWith('/media/upload')
  )
  await input.setInputFiles([
    { name: 'valid.txt', mimeType: 'text/plain', buffer: Buffer.from('upload evidence') },
    { name: 'valid.png', mimeType: 'image/png', buffer: png }
  ])
  assert.equal((await attachment).status(), 200)
  assert.equal((await media).status(), 200)
  await expect(page.getByRole('button', { name: '预览 valid.txt' })).toBeVisible()
  assert.equal(uploads.length, 2)
  assert.deepEqual(chats, [])
  assert.deepEqual(errors, [])
  process.stdout.write(
    'PASS: invalid mixed batch zero POSTs; real attachment and media uploads succeeded; chat submissions=0\n'
  )
} finally {
  if (browser) await browser.close()
  await vite.close()
}
