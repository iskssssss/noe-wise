import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少记忆恢复验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('恢复记忆保留原共享审核状态', async ({ page }) => {
  test.setTimeout(120_000)
  const mode = required('E2E_MEMORY_RESTORE_REVIEW')
  const stage = required('E2E_MEMORY_RESTORE_STAGE')
  expect(['baseline', 'candidate']).toContain(mode)
  expect(['restore', 'review']).toContain(stage)
  const evidence = required('E2E_ARTIFACT_DIR')
  const factId = required('E2E_MEMORY_FACT_ID')
  const query = required('E2E_MEMORY_QUERY')
  const text = required('E2E_MEMORY_TEXT')
  let revision = Number(required('E2E_MEMORY_REVISION'))
  const requests: unknown[] = []
  const writes: string[] = []
  const errors: string[] = []
  let opened = false
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path.startsWith('/api/v1/memories/')) writes.push(path)
  })
  try {
    await page.goto('/login?redirect=/admin/memory')
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/memory$/)
    opened = true
    await page.getByRole('textbox', { name: '搜索记忆', exact: true }).fill(query)
    const searched = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/memories/page' && url.searchParams.get('q') === query
    })
    await page.getByRole('button', { name: '查询', exact: true }).click()
    const search = await searched
    const initial = await search.json()
    requests.push({ path: '/api/v1/memories/page', query, status: search.status(), body: initial })
    expect(search.status()).toBe(200)
    const fact = initial.data.items.find(
      (item: { ref: { fact_id: string } }) => item.ref.fact_id === factId
    )
    expect(fact).toMatchObject({
      scope: 'USER',
      status: 'PENDING_CONFIRM',
      review_required: true,
      revision
    })
    const row = page.getByRole('row').filter({ hasText: text })
    await expect(row).toBeVisible()
    await expect(row.getByText('请先审核跨助手共享', { exact: true })).toBeVisible()
    if (stage === 'review') {
      expect(mode).toBe('candidate')
      await row.getByRole('button', { name: '批准', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: '确认跨助手共享', exact: true })
      await expect(dialog).toContainText('USER')
      await expect(dialog).toContainText(text)
      const reviewed = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === '/api/v1/memories/review'
      )
      await dialog.getByRole('button', { name: '确认跨助手共享', exact: true }).click()
      const response = await reviewed
      const body = await response.json()
      const command = response.request().postDataJSON()
      requests.push({ path: '/api/v1/memories/review', command, status: response.status(), body })
      expect(command).toEqual({
        fact_id: factId,
        payload: { expected_revision: revision, approved: true, confirmed_scope: 'USER' }
      })
      expect(response.status()).toBe(200)
      revision++
      expect(body.data).toMatchObject({
        scope: 'USER',
        status: 'ACTIVE',
        review_required: true,
        revision
      })
      await expect(row.getByRole('button', { name: '批准', exact: true })).toHaveCount(0)
      await expect(row.getByRole('button', { name: '纠正', exact: true })).toBeEnabled()
      await writeFile(join(evidence, 'reviewed.json'), JSON.stringify(body.data, null, 2))
      await page.screenshot({ path: join(evidence, 'reviewed.png'), fullPage: true })
    }
    await row.getByRole('button', { name: '遗忘', exact: true }).click()
    const forgotten = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/memories/forget'
    )
    await page
      .getByRole('dialog', { name: '遗忘记忆', exact: true })
      .getByRole('button', { name: '遗忘记忆', exact: true })
      .click()
    const forget = await forgotten
    const forgottenBody = await forget.json()
    requests.push({
      path: '/api/v1/memories/forget',
      command: forget.request().postDataJSON(),
      status: forget.status(),
      body: forgottenBody
    })
    expect(forget.request().postDataJSON()).toEqual({
      fact_id: factId,
      payload: { expected_revision: revision }
    })
    expect(forget.status()).toBe(200)
    revision++
    expect(forgottenBody.data).toMatchObject({
      scope: 'USER',
      status: 'FORGOTTEN',
      review_required: true,
      revision
    })
    await expect(row.getByRole('button', { name: '恢复', exact: true })).toBeEnabled()
    await row.getByRole('button', { name: '恢复', exact: true }).click()
    const restored = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/memories/restore'
    )
    await page
      .getByRole('dialog', { name: '恢复记忆', exact: true })
      .getByRole('button', { name: '恢复记忆', exact: true })
      .click()
    const restore = await restored
    const restoredBody = await restore.json()
    requests.push({
      path: '/api/v1/memories/restore',
      command: restore.request().postDataJSON(),
      status: restore.status(),
      body: restoredBody
    })
    expect(restore.request().postDataJSON()).toEqual({
      fact_id: factId,
      payload: { expected_revision: revision }
    })
    expect(restore.status()).toBe(200)
    revision++
    const expectedStatus =
      mode === 'candidate' && stage === 'restore' ? 'PENDING_CONFIRM' : 'ACTIVE'
    expect(restoredBody.data).toMatchObject({
      ref: { fact_id: factId },
      scope: 'USER',
      status: expectedStatus,
      review_required: true,
      revision
    })
    await expect(row.getByRole('button', { name: '遗忘', exact: true })).toBeEnabled()
    if (expectedStatus === 'PENDING_CONFIRM') {
      await expect(row.getByRole('button', { name: '批准', exact: true })).toBeEnabled()
      await expect(row.getByRole('button', { name: '纠正', exact: true })).toBeDisabled()
      await expect(row.getByText('请先审核跨助手共享', { exact: true })).toBeVisible()
    } else {
      await expect(row.getByRole('button', { name: '批准', exact: true })).toHaveCount(0)
      await expect(row.getByRole('button', { name: '纠正', exact: true })).toBeEnabled()
    }
    expect(writes).toEqual(
      stage === 'review'
        ? ['/api/v1/memories/review', '/api/v1/memories/forget', '/api/v1/memories/restore']
        : ['/api/v1/memories/forget', '/api/v1/memories/restore']
    )
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'browser-result.json'),
      JSON.stringify(
        { mode, stage, factId, revision, restored: restoredBody.data, writes, errors },
        null,
        2
      )
    )
  } finally {
    await writeFile(join(evidence, 'http.json'), JSON.stringify(requests, null, 2))
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify({ mode, stage, factId, revision, writes, errors }, null, 2)
    )
    if (opened) {
      await writeFile(join(evidence, 'final.html'), await page.locator('body').innerHTML())
      await page.screenshot({ path: join(evidence, `memory-${stage}-${mode}.png`), fullPage: true })
    }
  }
})
