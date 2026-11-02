import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing isolated fixture: ${name}`)
  return value
}

test('新建技能使用自动生成的标识', async ({ page }) => {
  await page.goto('/login?redirect=/admin/skills')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: '新建技能', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '新建技能' })
  const generated = await drawer.getByLabel('技能标识').inputValue()
  expect(generated).toMatch(/^skill-[0-9a-f-]{36}$/)
  await drawer.getByLabel('名称', { exact: true }).fill('自动标识技能')
  let upstreamStatus = 0
  await page.route(
    '**/api/v1/capabilities/skill-drafts/create-with-receipt',
    async (route) => {
      upstreamStatus = (await route.fetch()).status()
      await route.abort('failed')
    },
    { times: 1 }
  )
  const request = page.waitForRequest(
    (r) =>
      new URL(r.url()).pathname === '/api/v1/capabilities/skill-drafts/create-with-receipt' &&
      r.method() === 'POST'
  )
  await drawer.getByRole('button', { name: '创建并进入工作草稿' }).click()
  const sent = await request
  expect(sent.postDataJSON().payload.capability_key).toBe(generated)
  await expect(page.getByRole('button', { name: '核对创建结果' })).toBeVisible()
  await expect.poll(() => upstreamStatus).toBe(200)
  await expect(page.getByRole('button', { name: '核对创建结果' })).toBeEnabled()
  const lookup = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/v1/capabilities/skill-drafts/create-with-receipt/result'
  )
  await page.reload()
  const data = (await (await lookup).json()).data
  expect(data.request_id).toBe(sent.postDataJSON().request_id)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'skill-create-receipt.json'),
    JSON.stringify({ command: sent.postDataJSON(), receipt: data })
  )
  await expect(page).toHaveURL(new RegExp(`/admin/skills/${data.definition_id}$`))
})
test('技能工作台编辑并保存发布', async ({ page }) => {
  const definition = required('E2E_SKILL_ID')
  const writes: string[] = []
  page.on('request', (request) => {
    if (request.method() !== 'GET') writes.push(new URL(request.url()).pathname)
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/skills/' + definition)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/skills/${definition}$`))
  await expect(page.getByRole('button', { name: '发布', exact: true })).toHaveCount(1)
  await expect(page.getByRole('button', { name: '保存并发布', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '新建文件', exact: true }).first().click()
  await page.getByPlaceholder('scripts/main.py').fill('SKILL.md')
  await page.getByRole('button', { name: '创建文件', exact: true }).click()
  await page
    .getByRole('textbox', { name: '文件 SKILL.md' })
    .fill(
      '---\nname: browser-skill\ndescription: Browser fixture skill\n---\nFollow the test instructions.\n'
    )
  await page.getByRole('tab', { name: '发布设置' }).click()
  await page.locator('.el-form-item').filter({ hasText: '版本号' }).locator('input').fill('v1')
  const previewed = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/capabilities/skill-drafts/validate-content' &&
      response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '校验', exact: true }).click()
  const previewResponse = await previewed
  expect(previewResponse.status()).toBe(200)
  expect((await previewResponse.json()).data.valid).toBe(true)
  await expect(page.getByText('包格式与平台配置校验通过')).toBeVisible()
  await expect(page.getByRole('button', { name: '保存并发布', exact: true })).toBeEnabled()
  await expect(page.getByRole('button', { name: '发布', exact: true })).toHaveCount(0)
  expect(writes.filter((path) => path === '/api/v1/capabilities/skill-drafts')).toHaveLength(0)
  const submitted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/capabilities/skill-drafts/save-and-publish' &&
      response.request().method() === 'POST'
  )
  await page.getByRole('button', { name: '保存并发布', exact: true }).click()
  await page.getByRole('button', { name: '保存并发布', exact: true }).last().click()
  const response = await submitted
  expect(response.status()).toBe(200)
  const envelope = await response.json()
  expect(envelope.data.status).toBe('SUCCEEDED')
  expect(envelope.data.definition_id).toBe(definition)
  expect(envelope.data.revision).toBe(2)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'skill-publish-receipt.json'),
    JSON.stringify(envelope)
  )
  await expect(page.getByText(/技能发布已确认：版本/)).toBeVisible()
  expect(writes.filter((path) => path.endsWith('/skill-drafts/save-and-publish'))).toHaveLength(1)
  expect(writes.some((path) => path.endsWith('/skill-drafts/publish'))).toBe(false)
})
