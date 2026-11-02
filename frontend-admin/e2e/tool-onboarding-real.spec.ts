import { expect, test } from '@playwright/test'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少隔离夹具：${name}`)
  return value
}
test('选中连接器自动发现工具，确认后才接入', async ({ page }) => {
  // 转发到本次测试拥有的真实机构宿主，随机前端端口不参与 Origin 检查。
  await page.context().route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const headers = await route.request().allHeaders()
    delete headers.origin
    delete headers.host
    const response = await route.fetch({
      url: required('E2E_INSTITUTION_URL') + url.pathname + url.search,
      headers
    })
    await route.fulfill({ response })
  })
  let reads = 0,
    writes = 0
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/connectors/instances/mcp-tools') reads++
    if (request.method() === 'POST' && path === '/api/v1/tools/onboard') writes++
  })
  const target = `/admin/tools?connectorId=${encodeURIComponent(required('E2E_CONNECTOR_ID'))}`
  await page.goto(`/login?redirect=${encodeURIComponent(target)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('onboard-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: 'alpha' }).first()
  await expect(row).toBeVisible()
  expect(reads).toBe(1)
  expect(writes).toBe(0)
  const newTab = page.waitForEvent('popup')
  await page.getByRole('link', { name: '在新标签页设置连接器' }).click()
  const settings = await newTab
  await settings.bringToFront()
  await settings.getByRole('button', { name: '新增连接器', exact: true }).click()
  const drawer = settings.getByRole('dialog', { name: '新增 MCP 连接器' })
  await drawer.getByLabel('名称', { exact: true }).fill('返回后自动发现')
  await drawer.getByLabel('服务地址', { exact: true }).fill(required('E2E_MCP_URL'))
  const refreshedConnectors = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/connectors/instances'
  )
  const saved = settings.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/connectors/instances/create-with-receipt'
  )
  await drawer.getByRole('button', { name: '保存', exact: true }).click()
  expect((await saved).status()).toBe(200)
  await expect(drawer).toBeHidden()
  expect((await refreshedConnectors).status()).toBe(200)
  await settings.close()
  await page.bringToFront()
  await page.getByRole('combobox', { name: '工具来源连接器' }).click()
  await expect(page.getByRole('option', { name: /返回后自动发现/ })).toBeVisible()
  await page.keyboard.press('Escape')
  expect(writes).toBe(0)
  await row.locator('.el-checkbox').click()
  const accepted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/tools/onboard' &&
      response.request().method() === 'POST'
  )
  const refreshed = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/tools/available' &&
      response.request().method() === 'GET'
  )
  await page.getByRole('button', { name: '确认接入并授权调用 1 项工具' }).click()
  const response = await accepted
  expect(response.status(), await response.text()).toBe(200)
  expect((await response.json()).data.status).toBe('SUCCEEDED')
  expect((await refreshed).status()).toBe(200)
  expect(writes).toBe(1)
  await page.context().unrouteAll({ behavior: 'wait' })
})
