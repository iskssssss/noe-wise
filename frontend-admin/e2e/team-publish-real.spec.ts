import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing isolated fixture: ${name}`)
  return value
}
test('团队保存并发布后预选试用，未发送消息', async ({ page }) => {
  const writes: string[] = []
  page.on('request', (request) => {
    if (request.method() !== 'GET') writes.push(new URL(request.url()).pathname)
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/teams')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/teams$/)
  await page.getByRole('button', { name: '新增团队', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '新增团队' })
  await drawer.getByLabel('名称', { exact: true }).fill('浏览器协作团队')
  await expect(drawer.getByText('已预选唯一可用成员并设为主智能体，可按需修改。')).toBeVisible()
  await expect(drawer.locator('.el-select').first()).toContainText(required('E2E_AGENT_NAME'))
  const submitted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/teams/save-and-publish' &&
      response.request().method() === 'POST'
  )
  await drawer.getByRole('button', { name: '保存并发布', exact: true }).click()
  await page.getByRole('button', { name: '保存并发布', exact: true }).last().click()
  const response = await submitted
  expect(response.status()).toBe(200)
  const envelope = await response.json()
  expect(envelope.data.status).toBe('SUCCEEDED')
  expect(envelope.data.team.status).toBe('published')
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'team-publish-receipt.json'),
    JSON.stringify(envelope)
  )
  await expect(page.getByText(/团队“浏览器协作团队”已发布，修订/)).toBeVisible()
  await page.getByRole('button', { name: '试用当前发布版本' }).click()
  await expect(page).toHaveURL(/\/admin\/chat\?team_id=/)
  await expect(
    page.getByText('已预选团队的当前发布版本。请选择终端用户并手动发送消息。')
  ).toBeVisible()
  await expect(page.getByRole('combobox', { name: '终端用户' })).toHaveValue('')
  await expect(page.getByRole('button', { name: '发送', exact: true })).toBeDisabled()
  await page.goto('/admin/teams')
  const row = page.getByRole('row').filter({ hasText: '浏览器协作团队' })
  await row.getByRole('button', { name: '试用', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/chat\\?team_id=${envelope.data.team.id}$`))
  await expect(
    page.getByText('已预选团队的当前发布版本。请选择终端用户并手动发送消息。')
  ).toBeVisible()
  expect(writes.filter((path) => path === '/api/v1/teams/save-and-publish')).toHaveLength(1)
  expect(writes.some((path) => path.startsWith('/api/v1/chat/'))).toBe(false)
})

test('新建与编辑草稿响应中断后只读恢复原保存', async ({ page }) => {
  let posts = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/teams/save-with-receipt'
    )
      posts++
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/teams')}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: '新增团队', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '新增团队' })
  await expect(drawer.getByText('已预选唯一可用成员并设为主智能体，可按需修改。')).toBeVisible()
  await drawer.getByLabel('名称', { exact: true }).fill('恢复草稿团队')
  let upstream = 0
  const drop = async () =>
    page.route(
      '**/api/v1/teams/save-with-receipt',
      async (route) => {
        upstream = (await route.fetch()).status()
        await route.abort('failed')
      },
      { times: 1 }
    )
  await drop()
  const sent = page.waitForRequest(
    (request) =>
      new URL(request.url()).pathname === '/api/v1/teams/save-with-receipt' &&
      request.method() === 'POST'
  )
  await drawer.getByRole('button', { name: '保存', exact: true }).click()
  const command = (await sent).postDataJSON()
  await expect.poll(() => upstream).toBe(200)
  await expect(page.getByText(/草稿保存结果尚未确认/)).toBeVisible()
  page.on('dialog', (dialog) => dialog.accept())
  const lookup = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/teams/save-with-receipt/result'
  )
  await page.reload()
  const receipt = (await (await lookup).json()).data
  expect(receipt.request_id).toBe(command.request_id)
  expect(receipt.revision).toBe(1)
  await expect(page.getByText(/草稿保存结果尚未确认/)).toHaveCount(0)
  const row = page.getByRole('row').filter({ hasText: '恢复草稿团队' })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: '编辑', exact: true }).click()
  const editor = page.getByRole('dialog', { name: '编辑团队' })
  await editor.getByLabel('名称', { exact: true }).fill('恢复草稿团队更新')
  upstream = 0
  await drop()
  const updated = page.waitForRequest(
    (request) =>
      new URL(request.url()).pathname === '/api/v1/teams/save-with-receipt' &&
      request.method() === 'POST'
  )
  await editor.getByRole('button', { name: '保存', exact: true }).click()
  const updateCommand = (await updated).postDataJSON()
  await expect.poll(() => upstream).toBe(200)
  await expect(page.getByText(/草稿保存结果尚未确认/)).toBeVisible()
  const updateLookup = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/teams/save-with-receipt/result'
  )
  await page.reload()
  const updateReceipt = (await (await updateLookup).json()).data
  expect(updateReceipt.request_id).toBe(updateCommand.request_id)
  expect(updateReceipt.team_id).toBe(receipt.team_id)
  expect(updateReceipt.revision).toBe(2)
  await expect(page.getByRole('row').filter({ hasText: '恢复草稿团队更新' })).toBeVisible()
  expect(posts).toBe(2)
})
