import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { chromium } from '@playwright/test'

const port = await new Promise((resolve, reject) => {
  const listener = createServer()
  listener.once('error', reject)
  listener.listen(0, '127.0.0.1', () => {
    const value = listener.address().port
    listener.close(() => resolve(value))
  })
})
const base = `http://127.0.0.1:${port}`
const server = spawn(
  process.execPath,
  ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'],
  {
    env: process.env,
    stdio: 'inherit'
  }
)
let browser
try {
  let ready = false
  for (let i = 0; i < 120 && !ready; i++) {
    if (server.exitCode !== null) throw Error('Vite exited before readiness')
    try {
      ready = (await fetch(`${base}/login`)).ok
    } catch {
      /* startup */
    }
    if (!ready) await new Promise((resolve) => setTimeout(resolve, 500))
  }
  if (!ready) throw Error('Vite readiness timed out')
  browser = await chromium.launch({ headless: true })
  const page = await browser.newPage()
  await page.goto(`${base}/login?redirect=/platform/usage`)
  await page.locator('input[autocomplete="username"]').fill(process.env.E2E_USERNAME)
  await page.locator('input[autocomplete="current-password"]').fill(process.env.E2E_PASSWORD)
  await page.getByRole('button', { name: '登录运营台' }).click()
  await page.waitForURL('**/platform/usage')
  const row = page.locator('.usage-table .el-table__body-wrapper tbody tr').first()
  await row.waitFor()
  await row.locator('.el-table__expand-icon').click()
  const button = page.getByRole('button', { name: '查看关联运行' }).first()
  await button.waitFor()
  const response = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/v1/platform/trajectories/detail'
  )
  await button.click()
  const actual = await response
  if (actual.status() !== 200) throw Error(`trajectory status ${actual.status()}`)
  const body = await actual.json()
  if (!body.data?.usage?.attempts?.some((item) => item.attempt_id === 'attempt:root-browser-a:1'))
    throw Error('original attempt missing')
  const drawer = page.getByRole('dialog', { name: '关联运行' })
  await drawer.getByText('已找到该用量对应的模型调用。').waitFor()
  const [detail] = await Promise.all([
    page.waitForEvent('popup'),
    drawer.getByRole('link', { name: '打开完整轨迹' }).click()
  ])
  await detail.waitForURL('**/platform/trajectories/detail?**')
  await detail.getByText('会话执行轨迹', { exact: true }).first().waitFor()
  if (!(await page.getByRole('button', { name: '查看关联运行' }).count()))
    throw Error('source page lost')
  process.stdout.write('PASS: usage -> authenticated trajectory -> original attempt\n')

  await page.goto(`${base}/platform/trajectories`)
  const pagination = page.locator('.trajectory-pagination')
  await page.getByText('找到 22 条轨迹').waitFor()
  let pageFailures = 0
  let sizeFailures = 0
  await page.route('**/api/v1/platform/trajectories?*', async (route) => {
    const url = new URL(route.request().url())
    if (
      (url.searchParams.get('page') === '2' && pageFailures++ === 0) ||
      (url.searchParams.get('page_size') === '50' && sizeFailures++ === 0)
    ) {
      await route.fulfill({ status: 503, body: 'injected read failure' })
    } else {
      await route.continue()
    }
  })
  await pagination.locator('.btn-next').click()
  await page.getByText('当前保留上次成功读取的结果，本次刷新未完成。').waitFor()
  const retry = page.waitForResponse((r) => {
    const url = new URL(r.url())
    return url.pathname === '/api/v1/platform/trajectories' && url.searchParams.get('page') === '2'
  })
  await pagination.locator('.btn-next').click()
  if ((await retry).status() !== 200) throw Error('pagination retry failed')
  await pagination.locator('.number.is-active').filter({ hasText: '2' }).waitFor()
  process.stdout.write('PASS: injected page read failure -> user retry -> real API\n')
  await pagination.locator('.el-select').click()
  await page.getByRole('option', { name: '50条/页' }).click()
  await page.getByText('当前保留上次成功读取的结果，本次刷新未完成。').waitFor()
  const sizeRetry = page.waitForResponse((r) => {
    const url = new URL(r.url())
    return url.pathname === '/api/v1/platform/trajectories' && url.searchParams.get('page_size') === '50'
  })
  await pagination.locator('.el-select').click()
  await page.getByRole('option', { name: '50条/页' }).click()
  if ((await sizeRetry).status() !== 200) throw Error('page size retry failed')
  await page.getByText('当前保留上次成功读取的结果，本次刷新未完成。').waitFor({ state: 'hidden' })
  process.stdout.write('PASS: injected page size read failure -> user retry -> real API\n')
} finally {
  if (browser) await browser.close()
  server.kill()
}
