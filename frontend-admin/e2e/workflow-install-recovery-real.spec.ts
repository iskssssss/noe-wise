import { expect, test } from '@playwright/test'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing fixture: ${name}`)
  return value
}

test('工作流安装响应中断后刷新并自动核对原回执', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/workflows/receipt-flow')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: '安装', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '安装工作流' })
  await expect(drawer).toBeVisible()
  let posts = 0,
    upstream = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/builder/workflows/install-with-receipt'
    )
      posts++
  })
  await page.route(
    '**/api/v1/builder/workflows/install-with-receipt',
    async (route) => {
      upstream = (await route.fetch()).status()
      await route.abort('failed')
    },
    { times: 1 }
  )
  const sent = page.waitForRequest(
    (request) =>
      request.method() === 'POST' &&
      new URL(request.url()).pathname.endsWith('/install-with-receipt')
  )
  await drawer.getByRole('button', { name: '安装', exact: true }).click()
  const command = (await sent).postDataJSON()
  await expect.poll(() => upstream).toBe(200)
  await expect(page.getByText(/安装结果尚未确认/).first()).toBeVisible()
  page.on('dialog', (dialog) => dialog.accept())
  const lookup = page.waitForResponse((response) =>
    new URL(response.url()).pathname.endsWith('/install-with-receipt/result')
  )
  await page.reload()
  const response = await lookup
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data
  expect(receipt.request_id).toBe(command.request_id)
  expect(receipt.workflow_id).toBe('receipt-flow')
  expect(receipt.installation_revision).toBe('1')
  await expect(page.getByText(/已安装实例/)).toBeVisible()
  expect(posts).toBe(1)
})

test('工作流提取字段回车添加后保存到真实草稿', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/workflows/extract-flow')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.locator('[data-node-id="model"]').click()
  const panel = page.locator('aside[aria-label="节点配置"]')
  const input = panel.getByRole('combobox', { name: '提取字段' })
  await input.fill('order_id')
  await expect(page.getByRole('option', { name: 'order_id' })).toBeVisible()
  await input.press('Enter')
  await expect(panel.locator('.el-tag').getByText('order_id')).toBeVisible()
  const saved = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/builder/workflows/draft' &&
      response.request().method() === 'PUT'
  )
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  const response = await saved
  expect(response.status(), await response.text()).toBe(200)
  const node = (await response.json()).data.definition.nodes.find(
    (item: { id: string }) => item.id === 'model'
  )
  expect(node.extract).toEqual(['order_id'])
  const reread = page.waitForResponse(
    (result) =>
      new URL(result.url()).pathname === '/api/v1/builder/workflows/draft' &&
      result.request().method() === 'GET'
  )
  await page.reload()
  const loaded = (await (await reread).json()).data.definition.nodes.find(
    (item: { id: string }) => item.id === 'model'
  )
  expect(loaded.extract).toEqual(['order_id'])
})
