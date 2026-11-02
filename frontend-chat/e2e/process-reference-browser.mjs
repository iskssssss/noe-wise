/* global process */
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const require = createRequire(new URL('../../frontend-admin/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const vite = await createServer({
  root: fileURLToPath(new URL('../', import.meta.url)),
  server: { host: '127.0.0.1', port: 0 },
  plugins: [
    {
      name: 'reference-browser-fixture',
      resolveId(id) {
        if (id === 'virtual:reference-fixture') return '\0reference-fixture'
      },
      load(id) {
        if (id !== '\0reference-fixture') return
        return `import { createApp, h, ref } from 'vue'
        import ProcessStep from '/src/ui/ProcessStep.vue'
        import '/src/style.css'
        const locked = ref(false)
        window.toggleLocked = () => locked.value = !locked.value
        window.received = []
        const item = {process_id:'p', run_id:'r', root_run_id:'r', sequence:1, ordinal:0,
          updated_sequence:8, type:'tool', status:'succeeded', presentation:{title:'读取订单', summary:'已读取',
          results:[], references:[{label:'订单详情', source:{kind:'message', source:{kind:'session'}, message_id:'m-1', message_version:7}}]}}
        createApp({render:()=>h('ul',[h(ProcessStep,{item, locked:locked.value, now:0, live:false,
          onReference: value=>window.received.push(value)})])}).mount('#fixture')`
      }
    }
  ]
})
let browser
try {
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
  browser = await chromium.launch({ headless: true })
  const page = await browser.newPage({
    viewport: { width: 390, height: 844 },
    reducedMotion: 'reduce'
  })
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto(origin)
  await page.evaluate(async () => {
    document.body.innerHTML = '<div id="fixture"></div>'
    await import('/@id/virtual:reference-fixture')
  })
  await page.getByRole('button', { name: '展开读取订单详情' }).click()
  const reference = page.getByRole('button', { name: '引用来源：订单详情' })
  await expect(reference).toBeVisible()
  await reference.click()
  assert.deepEqual(await page.evaluate(() => window.received), [
    {
      label: '订单详情',
      source: {
        kind: 'message',
        source: { kind: 'session' },
        message_id: 'm-1',
        message_version: 7
      }
    }
  ])
  await page.evaluate(() => window.toggleLocked())
  await expect(reference).toBeDisabled()
  assert.deepEqual(errors, [])
  process.stdout.write(
    'PASS: source reference preserves identity and version; locked input disables action (browser fixture)\n'
  )
} finally {
  if (browser) await browser.close()
  await vite.close()
}
