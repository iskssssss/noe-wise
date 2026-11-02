import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing isolated fixture: ${name}`)
  return value
}

test('保存发布响应丢失后刷新核对原回执', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/workflows/browser-publish')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('workflow-publish-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/workflows\/browser-publish$/)
  await page.getByRole('tab', { name: '工作流信息' }).click()
  await page
    .getByRole('tabpanel', { name: '工作流信息' })
    .getByLabel('名称', { exact: true })
    .fill('浏览器组合发布')
  let posts = 0,
    upstream = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/builder/workflows/save-and-publish'
    )
      posts++
  })
  await page.route(
    '**/api/v1/builder/workflows/save-and-publish',
    async (route) => {
      upstream = (await route.fetch()).status()
      await route.abort('failed')
    },
    { times: 1 }
  )
  const sent = page.waitForRequest(
    (request) =>
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/builder/workflows/save-and-publish'
  )
  await page.getByRole('button', { name: '保存并发布', exact: true }).click()
  await page
    .getByRole('dialog', { name: '保存并发布工作流' })
    .getByRole('button', { name: '保存并发布' })
    .click()
  const command = (await sent).postDataJSON()
  await expect.poll(() => upstream).toBe(200)
  await expect(page.getByRole('button', { name: '核对原发布' })).toBeEnabled()
  page.on('dialog', (dialog) => dialog.accept())
  await page.reload()
  await expect(page.getByText(command.request_id, { exact: false })).toBeVisible()
  const lookup = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/builder/workflows/save-and-publish/result'
  )
  await page.getByRole('button', { name: '核对原发布' }).click()
  const response = await lookup
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data
  expect(receipt.request_id).toBe(command.request_id)
  expect(receipt.status).toBe('SUCCEEDED')
  await expect(page.getByRole('button', { name: '核对原发布' })).toHaveCount(0)
  expect(posts).toBe(1)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'workflow-publish.json'),
    JSON.stringify({ command, receipt })
  )
})
