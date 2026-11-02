import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing isolated fixture: ${name}`)
  return value
}
test('从会话创建精确工单并由坐席手动领取', async ({ page }) => {
  const session = required('E2E_SESSION_ID'),
    writes: string[] = []
  page.on('request', (request) => {
    if (request.method() !== 'GET') writes.push(new URL(request.url()).pathname)
  })
  await page.goto('/login?redirect=/admin/chat')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/chat$/)
  await page.locator('.el-select').filter({ hasText: '历史会话' }).getByRole('combobox').click()
  await page.getByRole('option', { name: session, exact: true }).click()
  await page.getByRole('tab', { name: '任务', exact: true }).click()
  const created = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/v1/handoff/tickets' && r.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '创建人工接管工单', exact: true }).click()
  await page.getByRole('button', { name: '创建工单', exact: true }).click()
  const response = await created
  expect(response.status()).toBe(200)
  const receipt = await response.json()
  expect(receipt.data.session_id).toBe(session)
  expect(receipt.data.command_id).toBe(response.request().postDataJSON().command_id)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'handoff-create-receipt.json'),
    JSON.stringify(receipt)
  )
  await page.getByRole('link', { name: '打开工单' }).click()
  const drawer = page.getByRole('dialog', { name: /人工接管/ })
  await expect(drawer).toBeVisible()
  expect(writes.filter((path) => path.endsWith('/handoff/tickets/accept'))).toHaveLength(0)
  const accepted = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/v1/handoff/tickets/accept' &&
      r.request().method() === 'POST'
  )
  await drawer.getByRole('button', { name: '领取工单', exact: true }).click()
  expect((await (await accepted).json()).data.status).toBe('assigned')
  expect(writes.filter((path) => path.endsWith('/handoff/tickets'))).toHaveLength(1)
  expect(
    writes.some((path) => path.endsWith('/handoff/tickets/replies') || path === '/api/v1/chat')
  ).toBe(false)
})
