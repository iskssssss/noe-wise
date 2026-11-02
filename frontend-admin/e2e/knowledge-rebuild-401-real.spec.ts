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
test('发布响应替换为 401 后核对同一作业', async ({ page }) => {
  test.setTimeout(180_000)
  const kb = required('E2E_KNOWLEDGE_ID'),
    gate = required('E2E_GATE_DIR'),
    evidence = required('E2E_ARTIFACT_DIR')
  const mode = process.env.E2E_REBUILD_401_MODE ?? 'baseline'
  expect(['baseline', 'candidate']).toContain(mode)
  const publishPath = `/api/v1/knowledge/bases/rebuilds/${mode === 'candidate' ? 'publish-with-receipt' : 'publish'}`
  let creates = 0,
    publishes = 0
  let refreshes = 0,
    upstreamWrites = 0,
    upstreamStatus = 0
  const publishCommands: unknown[] = []
  let upstreamReceipt: Record<string, unknown> = {}
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path === '/api/v1/knowledge/bases/rebuilds/create') creates++
    if (request.method() === 'POST' && path === publishPath) {
      publishes++
      publishCommands.push(request.postDataJSON())
    }
    if (request.method() === 'POST' && path.includes('/auth/refresh')) refreshes++
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
  await page.route(`**${publishPath}`, async (route) => {
    if (upstreamWrites > 0) {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'TEST_REPLAY_BLOCKED', message: '测试阻断重复发布' })
      })
      return
    }
    upstreamWrites++
    const upstream = await route.fetch({ maxRetries: 0 })
    upstreamStatus = upstream.status()
    const envelope = await upstream.json()
    expect(upstreamStatus).toBe(200)
    const snapshot = mode === 'candidate' ? envelope.data.snapshot : envelope.data
    if (mode === 'candidate') expect(envelope.data.status).toBe('SUCCEEDED')
    expect(snapshot.status).toBe('PUBLISHED')
    expect(snapshot.job_id).toBe(job.job_id)
    upstreamReceipt = envelope.data
    await writeFile(
      join(evidence, 'upstream-publication.json'),
      JSON.stringify({ upstreamStatus, receipt: upstreamReceipt })
    )
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'UNAUTHORIZED', message: '测试替换响应' })
    })
  })
  await panel.getByRole('button', { name: '发布重建结果', exact: true }).click()
  await page
    .getByRole('dialog', { name: '发布重建结果' })
    .getByRole('button', { name: '发布重建结果', exact: true })
    .click()
  await expect.poll(() => upstreamStatus, { timeout: 30_000 }).toBe(200)
  await expect(panel.getByRole('button', { name: '刷新重建进展', exact: true })).toBeEnabled()
  const reread = page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      new URL(r.url()).pathname === '/api/v1/knowledge/bases/rebuilds/detail'
  )
  const originalRequestId =
    mode === 'candidate' ? String((publishCommands[0] as { request_id: string }).request_id) : ''
  if (mode === 'candidate') {
    expect((publishCommands[0] as { job_id: string }).job_id).toBe(job.job_id)
    expect((publishCommands[0] as { kb_id: string }).kb_id).toBe(kb)
  }
  const resolution =
    mode === 'candidate'
      ? page.waitForResponse((r) => {
          const url = new URL(r.url())
          return (
            r.request().method() === 'GET' &&
            url.pathname === '/api/v1/knowledge/bases/rebuilds/publish-resolution' &&
            url.searchParams.get('request_id') === originalRequestId &&
            url.searchParams.get('kb_id') === kb &&
            url.searchParams.get('job_id') === String(job.job_id)
          )
        })
      : undefined
  await page.reload()
  const read = await reread
  expect(read.status()).toBe(200)
  const afterReload = (await read.json()).data
  expect(afterReload.status).toBe('PUBLISHED')
  expect(afterReload.job_id).toBe(job.job_id)
  const upstreamSnapshot =
    mode === 'candidate' ? (upstreamReceipt.snapshot as Record<string, unknown>) : upstreamReceipt
  expect(afterReload.published_revision).toBe(upstreamSnapshot.published_revision)
  let resolutionReceipt: unknown = null
  if (resolution) {
    const response = await resolution
    expect(response.status()).toBe(200)
    resolutionReceipt = (await response.json()).data
    expect(resolutionReceipt).toEqual(
      expect.objectContaining({
        status: 'SUCCEEDED',
        snapshot: expect.objectContaining({
          job_id: job.job_id,
          published_revision: afterReload.published_revision
        })
      })
    )
    expect(publishes).toBe(1)
    expect(refreshes).toBe(0)
    await expect(page.getByText('重建发布结果尚未确认', { exact: false })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '核对原重建发布', exact: true })).toHaveCount(0)
    await page.getByRole('tab', { name: '索引与模型' }).click()
    await expect(
      page.getByRole('tabpanel', { name: '索引与模型' }).getByText('已发布', { exact: true })
    ).toBeVisible()
  }
  expect(upstreamWrites).toBe(1)
  expect(creates).toBe(1)
  await page.screenshot({ path: join(evidence, 'rebuild-after-401-reload.png'), fullPage: true })
  await writeFile(
    join(evidence, 'rebuild-401.json'),
    JSON.stringify({
      job,
      mode,
      originalRequestId,
      resolutionReceipt,
      upstreamStatus,
      upstreamWrites,
      publishPosts: publishes,
      publishCommands,
      refreshes,
      afterReload,
      publishedRevision: afterReload.published_revision,
      creates
    })
  )
})
