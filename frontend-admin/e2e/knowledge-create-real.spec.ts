import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw new Error(`Missing isolated fixture: ${name}`)
  return value
}

test('创建后直接维护新知识库', async ({ page }) => {
  const writes: string[] = []
  page.on('request', (request) => {
    if (request.method() !== 'GET') writes.push(new URL(request.url()).pathname)
  })
  await page.goto('/login?redirect=/admin/knowledge')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/knowledge$/)
  await page.getByRole('button', { name: '新建知识库' }).click()
  const drawer = page.getByRole('dialog')
  await drawer.getByLabel('名称').fill('浏览器创建知识库')
  await drawer.locator('input[type="file"]').setInputFiles({
    name: '首份文档.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('这是知识库首份测试文档。')
  })
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '索引方式' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: '经济索引' }).click()
  await drawer.getByText('自定义分块和检索（可选）').click()
  await drawer.getByRole('spinbutton', { name: '分块大小', exact: true }).fill('640')
  await drawer.getByRole('spinbutton', { name: '重叠长度', exact: true }).fill('40')
  await drawer.getByRole('spinbutton', { name: '召回条数', exact: true }).fill('7')
  const response = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/v1/knowledge/bases/create-with-receipt' &&
      r.request().method() === 'POST'
  )
  const upload = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/v1/knowledge/bases/documents/upload' &&
      r.request().method() === 'POST'
  )
  await page.route(
    '**/api/v1/knowledge/bases/detail?*',
    (route) =>
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'TEMPORARY', message: '暂时无法读取' })
      }),
    { times: 1 }
  )
  await drawer.getByRole('button', { name: '保存', exact: true }).click()
  const created = await response
  expect(created.status()).toBe(200)
  const envelope = await created.json()
  expect(envelope.code).toBe('OK')
  expect(typeof envelope.data.kb_id).toBe('string')
  expect(created.request().postDataJSON().payload).toMatchObject({
    chunk_size: 640,
    chunk_overlap: 40,
    top_k: 7
  })
  expect(
    writes.filter((path) => path === '/api/v1/knowledge/bases/create-with-receipt')
  ).toHaveLength(1)
  expect(writes).not.toContain('/api/v1/knowledge/bases/update')
  const id = String(envelope.data.kb_id)
  await expect(page).toHaveURL(new RegExp(`/admin/knowledge/${id}$`))
  const pending = page.getByRole('status').filter({ hasText: '首份文档.txt' })
  await expect(pending).toContainText('尚未上传')
  await expect(page.getByRole('button', { name: '上传文件', exact: true }).last()).toBeDisabled()
  expect(writes.filter((path) => path === '/api/v1/knowledge/bases/documents/upload')).toHaveLength(
    0
  )
  await pending.getByRole('button', { name: '重新读取并继续' }).click()
  const uploaded = await upload
  expect(uploaded.status()).toBe(200)
  expect(new URL(uploaded.url()).searchParams.get('kb_id')).toBe(id)
  expect((await uploaded.json()).data.status).toBe('SUCCEEDED')
  expect(writes.filter((path) => path === '/api/v1/knowledge/bases/documents/upload')).toHaveLength(
    1
  )
  await expect(page.getByRole('button', { name: '上传文件' }).first()).toBeVisible()
  await page.goto('/admin/knowledge')
  await page.route(
    '**/api/v1/knowledge/bases/create-with-receipt',
    async (route) => {
      await route.fetch()
      await route.abort('failed')
    },
    { times: 1 }
  )
  await page.getByRole('button', { name: '新建知识库' }).click()
  const recovery = page.getByRole('dialog')
  await recovery.getByLabel('名称').fill('恢复知识库')
  await recovery.locator('input[type="file"]').setInputFiles({
    name: '恢复文档.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('仅在确认创建后上传。')
  })
  await recovery
    .locator('.el-form-item')
    .filter({ hasText: '索引方式' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: '经济索引' }).click()
  await recovery.getByRole('button', { name: '保存', exact: true }).click()
  await expect(recovery.getByRole('button', { name: '核对创建结果' })).toBeVisible()
  expect(writes.filter((path) => path === '/api/v1/knowledge/bases/documents/upload')).toHaveLength(
    1
  )
  const recoveredUpload = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/v1/knowledge/bases/documents/upload' &&
      r.request().method() === 'POST'
  )
  const lookup = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/knowledge/bases/create-with-receipt/result'
  )
  await recovery.getByRole('button', { name: '核对创建结果' }).click()
  const recovered = (await (await lookup).json()).data
  await expect(page).toHaveURL(new RegExp(`/admin/knowledge/${recovered.kb_id}$`))
  const recoveredUploadResponse = await recoveredUpload
  expect(recoveredUploadResponse.status()).toBe(200)
  expect(new URL(recoveredUploadResponse.url()).searchParams.get('kb_id')).toBe(recovered.kb_id)
  expect(
    writes.filter((path) => path === '/api/v1/knowledge/bases/create-with-receipt')
  ).toHaveLength(2)
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), 'creation-receipt.json'),
    JSON.stringify({ ...envelope, recovered })
  )
})
