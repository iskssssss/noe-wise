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
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/workflows\/browser-publish$/)
  await page.getByRole('tab', { name: '工作流信息' }).click()
  await page
    .getByRole('tabpanel', { name: '工作流信息' })
    .getByLabel('名称', { exact: true })
    .fill('浏览器组合发布')
  let posts = 0,
    upstream = 0,
    lookups = 0,
    manualChecks = 0
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
  await page.screenshot({ path: join(required('E2E_ARTIFACT_DIR'), 'publish-interrupted.png') })
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (
      request.method() === 'GET' &&
      url.pathname === '/api/v1/builder/workflows/save-and-publish/result'
    ) {
      expect(url.searchParams.get('workflow_id')).toBe('browser-publish')
      expect(url.searchParams.get('request_id')).toBe(command.request_id)
      lookups++
    }
  })
  const lookup = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return (
      url.pathname === '/api/v1/builder/workflows/save-and-publish/result' &&
      url.searchParams.get('workflow_id') === 'browser-publish' &&
      url.searchParams.get('request_id') === command.request_id
    )
  })
  page.on('dialog', (dialog) => dialog.accept())
  await page.reload()
  if (process.env.E2E_PUBLISH_RECOVERY_MODE === 'baseline') {
    await expect(page.getByText(command.request_id, { exact: false })).toBeVisible()
    expect(lookups).toBe(0)
    await page.getByRole('button', { name: '核对原发布' }).click()
    manualChecks++
  }
  const response = await lookup
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data
  expect(receipt.request_id).toBe(command.request_id)
  expect(receipt.status).toBe('SUCCEEDED')
  await expect(page.getByRole('button', { name: '核对原发布' })).toHaveCount(0)
  expect(posts).toBe(1)
  expect(lookups).toBe(1)
  await page.screenshot({ path: join(required('E2E_ARTIFACT_DIR'), 'publish-recovered.png') })
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'workflow-publish.json'),
    JSON.stringify({ command, receipt, posts, upstream, lookups, manualChecks })
  )
})
