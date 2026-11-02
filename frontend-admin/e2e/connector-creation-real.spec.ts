import { expect, test } from '@playwright/test'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少隔离夹具：${name}`)
  return value
}
test('连接器创建响应中断后刷新自动核对并进入工具接入', async ({ page }) => {
  const forward = async (route: import('@playwright/test').Route) => {
    const requestUrl = new URL(route.request().url())
    const headers = await route.request().allHeaders()
    delete headers.origin
    delete headers.host
    return route.fetch({
      url: required('E2E_INSTITUTION_URL') + requestUrl.pathname + requestUrl.search,
      headers
    })
  }
  await page.route('**/api/v1/**', async (route) => {
    try {
      await route.fulfill({ response: await forward(route) })
    } catch (error) {
      // 页面取消在途只读请求时，Playwright 已经处理该路由。
      if (!(error instanceof Error) || !error.message.includes('Route is already handled'))
        throw error
    }
  })
  let upstream = 0,
    creates = 0,
    onboards = 0
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path === '/api/v1/connectors/instances/create-with-receipt')
      creates++
    if (request.method() === 'POST' && path === '/api/v1/tools/onboard') onboards++
  })
  await page.route(
    '**/api/v1/connectors/instances/create-with-receipt',
    async (route) => {
      upstream = (await forward(route)).status()
      await route.abort('failed')
    },
    { times: 1 }
  )
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/connectors')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('onboard-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: '新增连接器', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '新增 MCP 连接器' })
  await drawer.getByLabel('名称', { exact: true }).fill('浏览器恢复连接器')
  await drawer.getByLabel('服务地址', { exact: true }).fill(required('E2E_MCP_URL'))
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '认证方式' })
    .locator('.el-select')
    .click()
  await page.getByRole('option', { name: 'Bearer Token' }).click()
  await drawer.getByLabel('访问凭据').fill('browser-synthetic-secret')
  const sent = page.waitForRequest(
    (request) =>
      new URL(request.url()).pathname === '/api/v1/connectors/instances/create-with-receipt' &&
      request.method() === 'POST'
  )
  await drawer.getByRole('button', { name: '保存并接入工具' }).click()
  const command = (await sent).postDataJSON()
  await expect.poll(() => upstream).toBe(200)
  await expect(page.getByRole('button', { name: '核对创建结果' })).toBeEnabled()
  const stored = await page.evaluate(() => JSON.stringify(sessionStorage))
  expect(stored).toContain(command.request_id)
  expect(stored).not.toContain('browser-synthetic-secret')
  page.on('dialog', (dialog) => dialog.accept())
  const lookup = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/connectors/instances/create-with-receipt/result'
  )
  await page.reload()
  const response = await lookup
  expect(response.status()).toBe(200)
  const receipt = (await response.json()).data
  expect(receipt.request_id).toBe(command.request_id)
  await expect(page).toHaveURL(new RegExp(`/admin/tools\\?connectorId=${receipt.instance_id}$`))
  await expect(page.getByText('alpha', { exact: true }).first()).toBeVisible()
  expect(creates).toBe(1)
  expect(onboards).toBe(0)
  await page.unrouteAll({ behavior: 'ignoreErrors' })
})
