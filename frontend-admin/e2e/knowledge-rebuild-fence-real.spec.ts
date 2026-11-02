import { expect, test } from '@playwright/test'
import { access, readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少隔离验收参数：${name}`)
  return value
}
async function exists(path: string): Promise<boolean> {
  try {
    await access(path)
    return true
  } catch {
    return false
  }
}
test.describe.configure({ retries: 0 })
test('封存原发布后迟到请求被拒绝', async ({ page }) => {
  test.setTimeout(180_000)
  const kb = required('E2E_KNOWLEDGE_ID'),
    gate = required('E2E_GATE_DIR'),
    evidence = required('E2E_ARTIFACT_DIR')
  let creates = 0,
    publishes = 0,
    upstreamWrites = 0
  let originalCommand: Record<string, unknown> = {}
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path === '/api/v1/knowledge/bases/rebuilds/create') creates++
    if (
      request.method() === 'POST' &&
      path === '/api/v1/knowledge/bases/rebuilds/publish-with-receipt'
    )
      publishes++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/knowledge/' + kb)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/knowledge/${kb}$`))
  await page.getByRole('tab', { name: '索引与模型' }).click()
  const panel = page.getByRole('tabpanel', { name: '索引与模型' })
  await panel
    .locator('.el-form-item')
    .filter({ hasText: '目标向量模型' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: required('E2E_MODEL_NAME'), exact: true }).click()
  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/knowledge/bases/rebuilds/create'
  )
  await panel.getByRole('button', { name: '开始重建', exact: true }).click()
  const response = await created
  expect(response.status()).toBe(200)
  const job = (await response.json()).data
  expect(job.status).toBe('QUEUED')
  expect(job.job_id).toMatch(/^[1-9]\d*$/)
  expect(job.kb_id).toBe(kb)
  expect(job.target_model_id).toMatch(/^[1-9]\d*$/)
  await expect.poll(() => exists(join(gate, 'embedding-started')), { timeout: 45_000 }).toBe(true)
  const running = page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      new URL(r.url()).pathname === '/api/v1/knowledge/bases/rebuilds/detail'
  )
  await panel.getByRole('button', { name: '刷新重建进展', exact: true }).click()
  expect((await (await running).json()).data.status).toBe('RUNNING')
  await page.screenshot({
    path: join(evidence, 'running-before-default-change.png'),
    fullPage: true
  })
  await writeFile(join(gate, 'release-embedding'), 'release')
  await expect.poll(() => exists(join(gate, 'server-ready.json')), { timeout: 45_000 }).toBe(true)
  const ready = JSON.parse(await readFile(join(gate, 'server-ready.json'), 'utf8'))
  expect(ready.status).toBe('READY')
  expect(ready.jobId).toBe(job.job_id)
  const refreshed = page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      new URL(r.url()).pathname === '/api/v1/knowledge/bases/rebuilds/detail'
  )
  await panel.getByRole('button', { name: '刷新重建进展', exact: true }).click()
  const readyData = (await (await refreshed).json()).data
  expect(readyData.status).toBe('READY')
  await page.route(
    '**/api/v1/knowledge/bases/rebuilds/publish-with-receipt',
    async (route) => {
      originalCommand = route.request().postDataJSON()
      await writeFile(join(evidence, 'held-original-command.json'), JSON.stringify(originalCommand))
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'UNAUTHORIZED', message: '测试替换响应' })
      })
    },
    { times: 1 }
  )
  await panel.getByRole('button', { name: '发布重建结果', exact: true }).click()
  await page
    .getByRole('dialog', { name: '发布重建结果' })
    .getByRole('button', { name: '发布重建结果', exact: true })
    .click()
  await expect(page.getByText('重建发布结果尚未确认', { exact: false })).toBeVisible()
  expect(publishes).toBe(1)
  expect(upstreamWrites).toBe(0)
  const fence = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/knowledge/bases/rebuilds/publish-fence'
  )
  await page.getByRole('button', { name: '结束未确认的重建发布', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '结束原发布', exact: true }).click()
  const fenced = await fence
  expect(fenced.status()).toBe(200)
  const fenceReceipt = (await fenced.json()).data
  expect(fenceReceipt.status).toBe('FENCED')
  expect(fenced.request().postDataJSON()).toEqual(originalCommand)
  const token = await page.evaluate(() => localStorage.getItem('skillforge.access_token'))
  expect(token).toBeTruthy()
  const late = await page.request.post(`/api/v1/knowledge/bases/rebuilds/publish-with-receipt`, {
    headers: { Authorization: `Bearer ${token}` },
    data: originalCommand,
    maxRetries: 0
  })
  upstreamWrites++
  const lateBody = await late.json()
  expect(late.status()).toBe(200)
  expect(lateBody.data).toEqual({ status: 'FENCED', snapshot: null })
  const observed = await page.evaluate(
    async ({ id, jobId, requestId }) => {
      const token = localStorage.getItem('skillforge.access_token')
      const headers = { Authorization: `Bearer ${token}` }
      const [base, resolution, rebuild] = await Promise.all([
        fetch(`/api/v1/knowledge/bases/detail?kbId=${encodeURIComponent(id)}`, { headers }),
        fetch(
          `/api/v1/knowledge/bases/rebuilds/publish-resolution?kb_id=${encodeURIComponent(id)}&job_id=${encodeURIComponent(jobId)}&request_id=${encodeURIComponent(requestId)}`,
          { headers }
        ),
        fetch(`/api/v1/knowledge/bases/rebuilds/detail?kbId=${encodeURIComponent(id)}`, { headers })
      ])
      return {
        base: await base.json(),
        resolution: await resolution.json(),
        rebuild: await rebuild.json()
      }
    },
    { id: kb, jobId: String(job.job_id), requestId: String(originalCommand.request_id) }
  )
  expect(observed.resolution.data.status).toBe('FENCED')
  expect(observed.rebuild.data.status).toBe('READY')
  expect(observed.rebuild.data.job_id).toBe(job.job_id)
  expect(String(observed.base.data.active_revision_id)).toBe(required('E2E_ORIGINAL_REVISION_ID'))
  expect(creates).toBe(1)
  expect(publishes).toBe(1)
  expect(upstreamWrites).toBe(1)
  await page.screenshot({ path: join(evidence, 'fenced-original.png'), fullPage: true })
  await writeFile(
    join(evidence, 'rebuild-fence.json'),
    JSON.stringify({
      job,
      originalCommand,
      fenceReceipt,
      lateStatus: late.status(),
      lateBody,
      observed,
      creates,
      publishes,
      upstreamWrites
    })
  )
})
