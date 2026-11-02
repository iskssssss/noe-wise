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
test('历史知识库重建期间运营默认变化', async ({ page }) => {
  test.setTimeout(180_000)
  const kb = required('E2E_KNOWLEDGE_ID'),
    gate = required('E2E_GATE_DIR'),
    evidence = required('E2E_ARTIFACT_DIR')
  const mode = process.env.E2E_LEGACY_REBUILD_MODE ?? 'baseline'
  expect(['baseline', 'candidate']).toContain(mode)
  let creates = 0,
    publishes = 0
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path === '/api/v1/knowledge/bases/rebuilds/create') creates++
    if (request.method() === 'POST' && path === '/api/v1/knowledge/bases/rebuilds/publish')
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
  await writeFile(join(gate, 'switch-default'), 'switch')
  await expect
    .poll(() => exists(join(gate, 'settings-switched.json')), { timeout: 30_000 })
    .toBe(true)
  await expect.poll(() => exists(join(gate, 'server-ready.json')), { timeout: 45_000 }).toBe(true)
  const ready = JSON.parse(await readFile(join(gate, 'server-ready.json'), 'utf8'))
  expect(ready.status).toBe('READY')
  expect(String(ready.jobId)).toBe(String(job.job_id))
  const refreshed = page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      new URL(r.url()).pathname === '/api/v1/knowledge/bases/rebuilds/detail'
  )
  await panel.getByRole('button', { name: '刷新重建进展', exact: true }).click()
  const readyData = (await (await refreshed).json()).data
  expect(readyData.status).toBe('READY')
  const posted = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/knowledge/bases/rebuilds/publish'
  )
  await panel.getByRole('button', { name: '发布重建结果', exact: true }).click()
  await page
    .getByRole('dialog', { name: '发布重建结果' })
    .getByRole('button', { name: '发布重建结果', exact: true })
    .click()
  let result = await posted
  let conflict:
    { activeRevisionId: string; jobId: string; status: string; code: string } | undefined
  if (mode === 'candidate') {
    expect(result.status()).toBe(409)
    const rejection = await result.json()
    expect(rejection.code).toBe('KNOWLEDGE_REBUILD_MODE_CHANGED')
    const observed = await page.evaluate(async (id) => {
      const token = localStorage.getItem('skillforge.access_token')
      const headers = { Authorization: `Bearer ${token}` }
      const [base, rebuild] = await Promise.all([
        fetch(`/api/v1/knowledge/bases/detail?kbId=${encodeURIComponent(id)}`, { headers }),
        fetch(`/api/v1/knowledge/bases/rebuilds/detail?kbId=${encodeURIComponent(id)}`, { headers })
      ])
      return {
        baseStatus: base.status,
        base: await base.json(),
        jobStatus: rebuild.status,
        job: await rebuild.json()
      }
    }, kb)
    expect(observed.baseStatus).toBe(200)
    expect(observed.jobStatus).toBe(200)
    conflict = {
      activeRevisionId: String(observed.base.data.active_revision_id),
      jobId: String(observed.job.data.job_id),
      status: observed.job.data.status,
      code: rejection.code
    }
    expect(conflict.activeRevisionId).toBe(required('E2E_ORIGINAL_REVISION_ID'))
    expect(conflict.jobId).toBe(String(job.job_id))
    expect(conflict.status).toBe('READY')
    await writeFile(join(evidence, 'publish-conflict.json'), JSON.stringify({ conflict, observed }))
    await writeFile(join(gate, 'restore-default'), 'restore')
    await expect
      .poll(() => exists(join(gate, 'settings-restored.json')), { timeout: 30_000 })
      .toBe(true)
    const reread = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === '/api/v1/knowledge/bases/rebuilds/detail'
    )
    await panel.getByRole('button', { name: '刷新重建进展', exact: true }).click()
    const current = (await (await reread).json()).data
    expect(current.status).toBe('READY')
    expect(String(current.job_id)).toBe(String(job.job_id))
    const explicitPublish = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === '/api/v1/knowledge/bases/rebuilds/publish'
    )
    await panel.getByRole('button', { name: '发布重建结果', exact: true }).click()
    await page
      .getByRole('dialog', { name: '发布重建结果' })
      .getByRole('button', { name: '发布重建结果', exact: true })
      .click()
    result = await explicitPublish
  }
  expect(result.status()).toBe(200)
  const published = (await result.json()).data
  expect(published.status).toBe('PUBLISHED')
  expect(String(published.job_id)).toBe(String(job.job_id))
  expect(creates).toBe(1)
  expect(publishes).toBe(mode === 'candidate' ? 2 : 1)
  await page.screenshot({
    path: join(evidence, 'published-after-default-change.png'),
    fullPage: true
  })
  await writeFile(
    join(evidence, 'legacy-rebuild.json'),
    JSON.stringify({
      job,
      ready: readyData,
      published,
      mode,
      conflict,
      publishedRevision: published.published_revision,
      creates,
      publishes
    })
  )
})
