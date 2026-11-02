import { expect, test } from '@playwright/test'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少隔离夹具：${name}`)
  return value
}
test('唯一授权定义自动选择并保护用户输入', async ({ page }) => {
  // 机构宿主由本次夹具启动；转发只去除随机 Vite 端口的 Origin，响应来自真实宿主。
  await page.route('**/api/v1/**', async (route) => {
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
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/agent-deployments')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('browser-deployment-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/agent-deployments$/)
  let reads = 0
  page.on('request', (request) => {
    if (
      request.method() === 'GET' &&
      new URL(request.url()).pathname === '/api/v1/tenant/agent-deployments/definitions'
    )
      reads++
  })
  await page.getByRole('button', { name: '部署公共智能体' }).click()
  const drawer = page.getByRole('dialog', { name: '部署公共智能体' })
  await expect(drawer.getByLabel('实例名称')).toHaveValue('浏览器授权助手')
  await expect(drawer.getByText('已自动选择唯一可用授权')).toBeVisible()
  expect(reads).toBe(1)
  await drawer.getByRole('button', { name: '取消', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  await expect(page.getByRole('dialog', { name: '关闭表单' })).not.toBeVisible()
  reads = 0
  await page.getByRole('button', { name: '部署公共智能体' }).click()
  await expect(drawer.getByLabel('实例名称')).toHaveValue('浏览器授权助手')
  await expect(drawer.getByText('已自动选择唯一可用授权')).toBeVisible()
  expect(reads).toBe(1)
  await drawer.getByLabel('实例名称').fill('用户编辑的实例')
  await drawer.getByRole('button', { name: '取消', exact: true }).click()
  const discard = page.getByRole('dialog', { name: '关闭表单' })
  await expect(discard).toBeVisible()
  await discard.getByRole('button', { name: '继续编辑' }).click()
  await expect(drawer.getByLabel('实例名称')).toHaveValue('用户编辑的实例')
  const created = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/tenant/agent-deployments' &&
      response.request().method() === 'POST'
  )
  const refreshed = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/tenant/agent-deployments' &&
      response.request().method() === 'GET'
  )
  await drawer.getByRole('button', { name: '保存机构实例' }).click()
  const response = await created
  expect(response.status(), await response.text()).toBe(200)
  expect((await response.json()).data.definition_id).toBe(required('E2E_DEFINITION_ID'))
  expect((await refreshed).status()).toBe(200)
  await page.unrouteAll({ behavior: 'wait' })
})
