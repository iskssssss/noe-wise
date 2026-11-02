import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing isolated fixture: ${name}`)
  return value
}
test('创建渠道时一并绑定智能体并展示首次令牌', async ({ page }) => {
  const writes: string[] = []
  page.on('request', (request) => {
    if (request.method() !== 'GET') writes.push(new URL(request.url()).pathname)
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/channels')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/channels$/)
  await page.getByRole('button', { name: '新增渠道', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '新增渠道' })
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '渠道名称' })
    .locator('input')
    .fill('浏览器渠道')
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '接入智能体' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: required('E2E_AGENT_NAME'), exact: true }).click()
  await page.getByRole('option', { name: required('E2E_SECOND_AGENT_NAME'), exact: true }).click()
  await page.keyboard.press('Escape')
  await drawer
    .locator('.el-form-item')
    .filter({ has: page.locator('.el-form-item__label').filter({ hasText: /^默认智能体$/ }) })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: required('E2E_SECOND_AGENT_NAME'), exact: true }).click()
  const submitted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/channels/onboarding' &&
      response.request().method() === 'POST'
  )
  await drawer.getByRole('button', { name: '保存', exact: true }).click()
  const response = await submitted
  expect(response.status()).toBe(200)
  const result = (await response.json()).data
  expect(result.channel.channel_token).toBeTruthy()
  await expect(page.getByText('所选智能体已绑定。')).toBeVisible()
  await expect(page.getByText(result.channel.channel_token, { exact: true })).toBeVisible()
  expect(writes.filter((path) => path === '/api/v1/channels/onboarding')).toHaveLength(1)
  expect(writes).not.toContain('/api/v1/channels/agents')
  await page
    .getByRole('dialog', { name: /渠道访问凭据/ })
    .getByRole('button', { name: '关闭', exact: true })
    .click()
  await page.route(
    '**/api/v1/channels/onboarding',
    async (route) => {
      await route.fetch()
      await route.abort('failed')
    },
    { times: 1 }
  )
  await page.getByRole('button', { name: '新增渠道', exact: true }).click()
  const recoveryDrawer = page.getByRole('dialog', { name: '新增渠道' })
  await recoveryDrawer
    .locator('.el-form-item')
    .filter({ hasText: '渠道名称' })
    .locator('input')
    .fill('恢复渠道')
  const recoveryRequest = page.waitForRequest(
    (request) =>
      new URL(request.url()).pathname === '/api/v1/channels/onboarding' &&
      request.method() === 'POST'
  )
  await recoveryDrawer.getByRole('button', { name: '保存', exact: true }).click()
  const originalRequest = (await recoveryRequest).postDataJSON().request_id
  await expect(recoveryDrawer.getByRole('button', { name: '核对创建结果' })).toBeVisible()
  const receiptResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/channels/onboarding/result'
  )
  page.on('dialog', (dialog) => dialog.accept())
  await page.reload()
  const recovered = (await (await receiptResponse).json()).data
  expect(recovered.request_id).toBe(originalRequest)
  await expect(page.getByRole('dialog', { name: '渠道访问凭据 · 恢复渠道' })).toBeVisible()
  await expect(
    page.getByText('已确认渠道创建成功，但首次令牌无法恢复。', { exact: false })
  ).toBeVisible()
  expect(writes.filter((path) => path === '/api/v1/channels/onboarding')).toHaveLength(2)
  expect(writes).not.toContain('/api/v1/channels/rotate-token')
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'channel-onboarding-result.json'),
    JSON.stringify({ request_id: result.request_id, channel_id: result.channel_id, recovered })
  )
})
