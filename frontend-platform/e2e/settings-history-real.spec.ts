import { expect, test } from '@playwright/test'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少隔离夹具：${name}`)
  return value
}

test('未保存修改时确认恢复历史配置', async ({ page }) => {
  await page.goto(`/login?redirect=${encodeURIComponent('/platform/system-settings')}`)
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  await expect(page).toHaveURL(/\/platform\/system-settings$/)
  const user = await page.evaluate(() =>
    JSON.parse(localStorage.getItem('skillforge.platform.user') || '{}')
  )
  expect(user.permissions).toContain('foundation.runtime_settings.update')
  const group = page
    .locator('.el-card')
    .filter({ has: page.getByText('分层任务预算', { exact: true }) })
  const total = group.getByLabel('普通 累计总 Tokens', { exact: false })
  await expect(total).toHaveAttribute('aria-disabled', 'false')
  await total.fill('120000099')
  await total.blur()
  await group.getByRole('button', { name: '历史版本' }).click()
  const drawer = page.getByRole('dialog', { name: '分层任务预算 · 历史' })
  await expect(drawer.getByText('恢复历史版本将丢弃本组未保存修改。')).toBeVisible()
  const version = drawer.locator('.el-card').filter({
    has: page.getByRole('heading', {
      name: `修订 ${required('E2E_TARGET_REVISION')}`,
      exact: true
    })
  })
  await version.getByRole('button', { name: '恢复此版本' }).click()
  const dialog = page.getByRole('dialog', { name: '恢复系统设置' })
  await expect(dialog.getByText(/本组未保存修改将被丢弃/)).toBeVisible()
  await expect(total).toHaveAttribute('aria-disabled', 'true')
  const response = page.waitForResponse(
    (value) =>
      new URL(value.url()).pathname === '/api/v1/admin/system-settings/rollback' &&
      value.request().method() === 'POST'
  )
  await dialog.getByRole('button', { name: '恢复此修订' }).click()
  const restored = await response
  expect(restored.status(), await restored.text()).toBe(200)
  const command = restored.request().postDataJSON()
  expect(command.payload.expected_revision).toBe(Number(required('E2E_CURRENT_REVISION')))
  expect(command.payload.target_revision).toBe(Number(required('E2E_TARGET_REVISION')))
  await expect(total).toHaveValue(required('E2E_TARGET_TOTAL'))
  await expect(drawer).not.toBeVisible()
})

test('机构配置未保存时可暂停机构并保留输入', async ({ page }) => {
  const id = required('E2E_TENANT_ID')
  await page.goto(
    `/login?redirect=${encodeURIComponent(`/platform/tenants/${id}?section=settings`)}`
  )
  await page.getByLabel('账号', { exact: true }).fill(required('E2E_USERNAME'))
  await page.getByLabel('密码', { exact: true }).fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录运营台', exact: true }).click()
  const name = page.getByLabel('机构名称', { exact: true })
  await name.fill('尚未保存的机构名称')
  let writes = 0
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/v1/admin/tenants/update') writes++
  })
  await page.getByRole('button', { name: '暂停机构', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '机构状态' })
  await expect(dialog.getByText('暂停后机构将无法正常使用服务。确认暂停？')).toBeVisible()
  const response = page.waitForResponse(
    (value) =>
      new URL(value.url()).pathname === '/api/v1/admin/tenants/suspend' &&
      value.request().method() === 'POST'
  )
  await dialog.getByRole('button', { name: '暂停机构', exact: true }).click()
  expect((await response).status()).toBe(200)
  await expect(page.getByRole('button', { name: '恢复机构', exact: true })).toBeVisible()
  await expect(name).toHaveValue('尚未保存的机构名称')
  expect(writes).toBe(0)
})
