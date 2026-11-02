import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`Missing isolated fixture: ${name}`)
  return value
}
test('上传响应中断后核对原回执，再确认范围并发布', async ({ page }) => {
  const kb = required('E2E_KB_ID')
  const writes: string[] = []
  let originalUploadId = ''
  page.on('request', (request) => {
    if (request.method() !== 'GET') writes.push(new URL(request.url()).pathname)
  })
  await page.goto(`/login?redirect=${encodeURIComponent('/admin/knowledge/' + kb)}`)
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/admin/knowledge/${kb}$`))
  const createdAgent = await page.evaluate(async () => {
    const token = localStorage.getItem('skillforge.access_token')
    const response = await fetch('/api/v1/agents', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`
      },
      body: JSON.stringify({ name: '知识挂载验收助手', system_prompt: '使用已挂载知识回答。' })
    })
    if (!response.ok) throw Error(`创建验收助手失败: ${response.status}`)
    return (await response.json()).data.id as string
  })
  const agentPage = await page.context().newPage()
  await agentPage.goto(`/admin/agents/${createdAgent}`)
  await agentPage.getByRole('tab', { name: /能力挂载/ }).click()
  await agentPage.getByRole('button', { name: '添加能力', exact: true }).first().click()
  const mount = agentPage.getByRole('dialog', { name: '添加能力挂载' })
  await mount
    .locator('.el-form-item')
    .filter({ hasText: '能力类型' })
    .locator('.el-select__wrapper')
    .click()
  await agentPage.getByRole('option', { name: '知识来源', exact: true }).click()
  const knowledge = mount
    .locator('.el-form-item')
    .filter({ hasText: '按名称、用途或标识查找知识库' })
  await knowledge.locator('.el-select__wrapper').click()
  await agentPage.getByRole('option').filter({ hasText: '浏览器经济知识库' }).click()
  await expect(mount.getByText('该知识库尚未发布')).toBeVisible()
  await expect(mount.getByRole('button', { name: '添加能力', exact: true })).toBeDisabled()
  await expect(page.getByText('尚无文档，点击“上传文件”添加知识内容')).toBeVisible()
  await expect(
    page.getByRole('button', { name: '上传文件' }).and(page.locator('button'))
  ).toBeEnabled()
  await page.route('**/api/v1/knowledge/bases/documents/upload?**', async (route) => {
    const response = await route.fetch({ maxRetries: 0 })
    expect(response.status()).toBe(200)
    const envelope = await response.json()
    expect(envelope.data.status).toBe('SUCCEEDED')
    originalUploadId = new URL(route.request().url()).searchParams.get('request_id') ?? ''
    expect(envelope.data.request_id).toBe(originalUploadId)
    await writeFile(
      join(required('E2E_ARTIFACT_DIR'), 'knowledge-upload-receipt.json'),
      JSON.stringify(envelope)
    )
    await route.abort('failed')
  })
  await page.locator('input[type="file"]').setInputFiles({
    name: '浏览器验收文档.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('文档正文包含浏览器验收知识。')
  })
  await expect(page.getByText(/上传结果尚未确认/)).toBeVisible()
  const lookup = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/knowledge/bases/documents/upload/result'
  )
  await page.reload()
  const recovered = await lookup
  expect(recovered.status()).toBe(200)
  expect((await recovered.json()).data.request_id).toBe(originalUploadId)
  await expect(page.getByText(/上传结果尚未确认/)).toHaveCount(0)
  await expect(page.getByText('浏览器验收文档', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: '知识发布' }).click()
  const prepare = page
    .getByRole('tabpanel', { name: '知识发布' })
    .getByRole('button', { name: '准备知识发布', exact: true })
  await expect(prepare).toBeEnabled()
  // 仅模拟大库的列表总数；来源版本选择和发布仍由真实服务端处理。
  await page.route(
    '**/api/v1/knowledge/bases/documents?**',
    async (route) => {
      const response = await route.fetch()
      const body = await response.json()
      body.data.total = 2001
      await route.fulfill({ response, json: body })
    },
    { times: 1 }
  )
  await prepare.click()
  const drawer = page.getByRole('dialog', { name: '准备知识发布' })
  await expect(drawer.getByRole('checkbox').first()).not.toBeChecked()
  const sourceRead = page.waitForResponse((response) =>
    new URL(response.url()).pathname.endsWith('/latest-ready-sources')
  )
  await drawer.getByRole('button', { name: '选择最新 2000 项' }).click()
  const selectedSources = await sourceRead
  expect(selectedSources.status()).toBe(200)
  expect((await selectedSources.json()).data).toHaveLength(1)
  await expect(drawer.getByRole('checkbox').first()).toBeChecked()
  const mountRefresh = agentPage.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/knowledge/bases/mount-option' &&
      response.request().method() === 'GET'
  )
  const submitted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname ===
        '/api/v1/knowledge/bases/revisions/prepare-and-publish' &&
      response.request().method() === 'POST'
  )
  await drawer.getByRole('button', { name: '确认范围并发布', exact: true }).click()
  await page.getByRole('button', { name: '确认发布', exact: true }).click()
  const response = await submitted
  expect(response.status()).toBe(200)
  const command = response.request().postDataJSON()
  expect(command.payload.source_versions.length).toBeGreaterThan(0)
  expect(command.payload.policy.allowed_operators).toEqual(['KEYWORD'])
  const envelope = await response.json()
  expect(envelope.data.status).toBe('SUCCEEDED')
  expect(String(envelope.data.revision.kb_id)).toBe(kb)
  expect(envelope.data.revision.source_versions).toEqual(command.payload.source_versions)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'knowledge-publish-receipt.json'),
    JSON.stringify(envelope)
  )
  expect((await mountRefresh).status()).toBe(200)
  await expect(mount.getByText('该知识库尚未发布')).toHaveCount(0)
  const addMount = mount.getByRole('button', { name: '添加能力', exact: true })
  await expect(addMount).toBeEnabled()
  const mounted = agentPage.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/agents/capability-mounts' &&
      response.request().method() === 'POST'
  )
  await addMount.click()
  expect((await mounted).status()).toBe(201)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'knowledge-agent-mount.json'),
    JSON.stringify({ agentId: createdAgent })
  )
  await expect(page.getByText(/知识发布已确认：修订/)).toBeVisible()
  await page.getByRole('tab', { name: '文档维护' }).click()
  const update = page.getByRole('button', { name: '更新知识发布', exact: true })
  await expect(update).toBeVisible()
  await update.click()
  await expect(drawer.getByRole('checkbox').first()).toBeChecked()
  await drawer.getByRole('button', { name: '关闭', exact: true }).click()
  expect(writes.filter((path) => path.endsWith('/prepare-and-publish'))).toHaveLength(1)
  expect(writes.filter((path) => path.endsWith('/documents/upload'))).toHaveLength(1)
  expect(
    writes.some(
      (path) => path.endsWith('/revisions/prepare') || path.endsWith('/revisions/publish')
    )
  ).toBe(false)
})
