import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少隔离夹具：${name}`)
  return value
}

test('机构创建响应丢失后刷新保留原标识并只读核对', async ({ page }) => {
  const slug = required('E2E_NEW_TENANT_SLUG')
  await page.goto(`/login?redirect=${encodeURIComponent('/platform/tenants?section=onboarding')}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  const form = page.locator('form.ordinary-form')
  await form.getByLabel('机构名称').fill('机构创建恢复验收')
  await form.getByLabel('机构标识').fill(slug)
  await form.getByLabel('管理员账号').fill('fixture-admin')
  await form.getByLabel('管理员初始密码').fill('FixturePassword123')
  let posts = 0,
    upstream = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/admin/tenants/create-with-receipt'
    )
      posts++
  })
  await page.route(
    '**/api/v1/admin/tenants/create-with-receipt',
    async (route) => {
      upstream = (await route.fetch()).status()
      await route.abort('failed')
    },
    { times: 1 }
  )
  await page.getByRole('button', { name: '创建机构', exact: true }).click()
  await expect.poll(() => upstream).toBe(200)
  await expect(page.getByText('机构创建结果未知', { exact: false })).toBeVisible()
  page.on('dialog', (dialog) => dialog.accept())
  await page.reload()
  await expect(page.getByText(`原机构标识：${slug}`, { exact: false })).toBeVisible()
  await expect(form.getByLabel('管理员初始密码')).toHaveValue('')
  const lookup = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/admin/tenants/create-with-receipt/result'
  )
  await page.getByRole('button', { name: '核对原请求' }).click()
  const response = await lookup
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data
  expect(receipt.status).toBe('SUCCEEDED')
  await expect(page.getByText('本次目标：机构创建恢复验收', { exact: false })).toBeVisible()
  expect(posts).toBe(1)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'tenant-create-recovery.json'),
    JSON.stringify({
      slug,
      posts,
      lookup: response.status(),
      requestId: receipt.request_id,
      tenantId: receipt.tenant_id
    })
  )
})

test('确认事务回滚后可直接修正并重新创建', async ({ page }) => {
  const slug = `${required('E2E_NEW_TENANT_SLUG')}-browser-rollback`
  await page.goto(`/login?redirect=${encodeURIComponent('/platform/tenants?section=onboarding')}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  const form = page.locator('form.ordinary-form')
  await form.getByLabel('机构名称').fill('回滚后恢复机构')
  await form.getByLabel('机构标识').fill(slug)
  await form.getByLabel('管理员账号').fill('browser-rollback')
  await form.getByLabel('管理员初始密码').fill('FixturePassword123')
  const failed = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/admin/tenants/create-with-receipt'
  )
  await page.getByRole('button', { name: '创建机构', exact: true }).click()
  expect((await failed).status()).toBe(500)
  await expect(page.getByText('机构创建结果未知', { exact: false })).toBeVisible()
  const lookup = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/admin/tenants/create-with-receipt/result'
  )
  await page.getByRole('button', { name: '核对原请求' }).click()
  expect((await (await lookup).json()).data.status).toBe('FAILED')
  await expect(page.getByText('机构创建结果未知', { exact: false })).toHaveCount(0)
  await form.getByLabel('管理员账号').fill('browser-recovered')
  const created = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/admin/tenants/create-with-receipt'
  )
  await page.getByRole('button', { name: '创建机构', exact: true }).click()
  expect((await (await created).json()).data.status).toBe('SUCCEEDED')
  await expect(page.getByText('本次目标：回滚后恢复机构', { exact: false })).toBeVisible()
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'tenant-create-rollback.json'),
    JSON.stringify({ slug })
  )
})
