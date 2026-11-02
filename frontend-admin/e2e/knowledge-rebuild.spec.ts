import { expect, test, type Response } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

/** 只连接 Java 所有者提供的隔离端点，缺失时拒绝连接共享开发服务。 */
function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`Missing isolated knowledge fixture: ${name}`)
  return value
}

/** 同时核对 HTTP 与公开信封，页面通知不替代持久回执。 */
async function dataOf(response: Response) {
  expect(response.status()).toBe(200)
  const envelope = await response.json()
  expect(envelope.code).toBe('OK')
  return envelope.data
}

test('knowledge rebuild locks writes, publishes explicitly and cancels a later job', async ({
  page,
  request
}) => {
  const kb = required('E2E_KB_ID')
  const route = `/admin/knowledge/${kb}`
  await page.goto(`/login?redirect=${encodeURIComponent(route)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  const login = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/v1/auth/login' && r.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await dataOf(await login)
  await expect(page).toHaveURL(new RegExp(`/admin/knowledge/${kb}$`))
  await page.getByRole('tab', { name: '索引与模型', exact: true }).click()
  const pane = page
    .getByRole('tabpanel')
    .filter({ has: page.getByText('目标向量模型', { exact: true }) })
  const targetModel = pane.getByRole('combobox', { name: '目标向量模型', exact: true })
  await expect(targetModel).toBeEnabled()
  await pane.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: 'Browser target embedding', exact: true }).click()
  const createdResponse = page.waitForResponse((r) =>
    new URL(r.url()).pathname.endsWith('/knowledge/bases/rebuilds/create')
  )
  await pane.getByRole('button', { name: '开始重建', exact: true }).click()
  const created = await dataOf(await createdResponse)
  expect(created.write_locked).toBe(true)
  expect(created.document_count).toBeGreaterThan(0)
  const control = required('E2E_CONTROL_URL')
  expect((await request.get(`${control}/run`)).status()).toBe(200)
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(pane.getByRole('button', { name: '开始重建', exact: true })).toBeDisabled()
  const uploadButton = page
    .getByRole('button', { name: '上传文件', exact: true })
    .and(page.locator('button'))
  await expect(uploadButton).toBeDisabled()
  await expect(uploadButton).toHaveAttribute('aria-disabled', 'true')
  expect((await request.get(`${control}/release`)).status()).toBe(200)
  await expect
    .poll(async () => {
      await page.getByRole('button', { name: '刷新', exact: true }).click()
      return await pane.getByRole('button', { name: '发布重建结果', exact: true }).count()
    })
    .toBe(1)
  const publishResponse = page.waitForResponse((r) =>
    new URL(r.url()).pathname.endsWith('/knowledge/bases/rebuilds/publish')
  )
  await pane.getByRole('button', { name: '发布重建结果', exact: true }).click()
  await page
    .getByRole('dialog', { name: '发布重建结果' })
    .getByRole('button', { name: '发布重建结果', exact: true })
    .click()
  const published = await dataOf(await publishResponse)
  expect(published.status).toBe('PUBLISHED')
  expect(published.write_locked).toBe(false)
  expect(published.published_revision).toBeGreaterThan(0)
  const secondResponse = page.waitForResponse((r) =>
    new URL(r.url()).pathname.endsWith('/knowledge/bases/rebuilds/create')
  )
  await pane.getByRole('button', { name: '开始重建', exact: true }).click()
  const second = await dataOf(await secondResponse)
  expect(second.job_id).not.toBe(created.job_id)
  const cancelResponse = page.waitForResponse((r) =>
    new URL(r.url()).pathname.endsWith('/knowledge/bases/rebuilds/cancel')
  )
  await pane.getByRole('button', { name: '取消重建', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '取消重建', exact: true }).click()
  const cancelled = await dataOf(await cancelResponse)
  expect(cancelled.status).toBe('CANCELLED')
  expect(cancelled.write_locked).toBe(false)
  expect(cancelled.job_id).toBe(second.job_id)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'browser-receipt.json'),
    JSON.stringify({ create: created, publish: published, cancel: cancelled }, null, 2)
  )
})
