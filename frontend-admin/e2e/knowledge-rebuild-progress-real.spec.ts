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
test('重建就绪后读取进展并确认发布', async ({ page }) => {
  test.setTimeout(180_000)
  const kb = required('E2E_KNOWLEDGE_ID'),
    gate = required('E2E_GATE_DIR'),
    evidence = required('E2E_ARTIFACT_DIR')
  const mode = process.env.E2E_REBUILD_PROGRESS_MODE ?? 'baseline'
  expect(['baseline', 'candidate']).toContain(mode)
  let creates = 0,
    publishes = 0,
    reads = 0,
    manualRefreshes = 0
  const observations: Array<{ job_id: string; status: string }> = []
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path === '/api/v1/knowledge/bases/rebuilds/create') creates++
    if (
      request.method() === 'POST' &&
      path === '/api/v1/knowledge/bases/rebuilds/publish-with-receipt'
    )
      publishes++
    if (request.method() === 'GET' && path === '/api/v1/knowledge/bases/rebuilds/detail') reads++
  })
  page.on('response', async (response) => {
    if (
      response.request().method() !== 'GET' ||
      new URL(response.url()).pathname !== '/api/v1/knowledge/bases/rebuilds/detail' ||
      response.status() !== 200
    )
      return
    try {
      observations.push((await response.json()).data)
    } catch {
      // 非完整响应不作为观察结果。
    }
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
  if (mode === 'baseline') {
    const running = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === '/api/v1/knowledge/bases/rebuilds/detail'
    )
    await panel.getByRole('button', { name: '刷新重建进展', exact: true }).click()
    manualRefreshes++
    expect((await (await running).json()).data.status).toBe('RUNNING')
  } else {
    await expect
      .poll(
        () => observations.some((item) => item.job_id === job.job_id && item.status === 'RUNNING'),
        { timeout: 30_000 }
      )
      .toBe(true)
  }
  await expect(panel.getByText('运行中', { exact: true })).toBeVisible()
  await page.screenshot({
    path: join(evidence, 'running-before-default-change.png'),
    fullPage: true
  })
  const readsBeforeReady = reads
  await writeFile(join(gate, 'release-embedding'), 'release')
  await expect.poll(() => exists(join(gate, 'server-ready.json')), { timeout: 45_000 }).toBe(true)
  const ready = JSON.parse(await readFile(join(gate, 'server-ready.json'), 'utf8'))
  expect(ready.status).toBe('READY')
  expect(ready.jobId).toBe(job.job_id)
  const readsAtReady = reads
  let readyData: { job_id: string; status: string }
  if (mode === 'baseline') {
    expect(readsAtReady).toBe(readsBeforeReady)
    await expect(panel.getByText('运行中', { exact: true })).toBeVisible()
    await expect(panel.getByRole('button', { name: '发布重建结果', exact: true })).toHaveCount(0)
    await page.screenshot({ path: join(evidence, 'ready-still-running.png'), fullPage: true })
    const refreshed = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === '/api/v1/knowledge/bases/rebuilds/detail'
    )
    await panel.getByRole('button', { name: '刷新重建进展', exact: true }).click()
    manualRefreshes++
    readyData = (await (await refreshed).json()).data
  } else {
    await expect
      .poll(
        () => observations.some((item) => item.job_id === job.job_id && item.status === 'READY'),
        { timeout: 30_000 }
      )
      .toBe(true)
    readyData = observations.findLast(
      (item) => item.job_id === job.job_id && item.status === 'READY'
    )!
    expect(reads).toBeGreaterThan(readsBeforeReady)
  }
  expect(readyData.status).toBe('READY')
  expect(readyData.job_id).toBe(job.job_id)
  const posted = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/knowledge/bases/rebuilds/publish-with-receipt'
  )
  await panel.getByRole('button', { name: '发布重建结果', exact: true }).click()
  await page
    .getByRole('dialog', { name: '发布重建结果' })
    .getByRole('button', { name: '发布重建结果', exact: true })
    .click()
  const result = await posted
  expect(result.status()).toBe(200)
  const receipt = (await result.json()).data
  expect(receipt.status).toBe('SUCCEEDED')
  expect(receipt.snapshot.status).toBe('PUBLISHED')
  expect(receipt.snapshot.job_id).toBe(job.job_id)
  expect(creates).toBe(1)
  expect(publishes).toBe(1)
  await page.screenshot({ path: join(evidence, 'published.png'), fullPage: true })
  await writeFile(
    join(evidence, 'rebuild-progress.json'),
    JSON.stringify({
      job,
      mode,
      readyData,
      receipt,
      observations,
      publishedRevision: receipt.snapshot.published_revision,
      readsBeforeReady,
      readsAtReady,
      manualRefreshes,
      creates,
      publishes
    })
  )
})
