import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少知识验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('观察分块写入成功后回执丢失的实际状态', async ({ page }) => {
  test.setTimeout(150_000)
  const evidence = required('E2E_ARTIFACT_DIR')
  const title = required('E2E_OLD_TITLE')
  const docId = required('E2E_DOCUMENT_ID')
  const candidate = process.env.E2E_KNOWLEDGE_CHUNK_RECEIPT === 'candidate'
  const network: object[] = []
  const errors: string[] = []
  let writes = 0
  let intercepted = false
  let discarded = false
  let opened = false
  let upstream: unknown
  let recoveredChunkId = ''
  let recoveredSource = ''
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (
      new URL(request.url()).pathname === '/api/v1/knowledge/chunks/update' &&
      request.method() === 'PATCH'
    )
      writes++
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/v1/knowledge/'))
      network.push({
        path: url.pathname + url.search,
        method: response.request().method(),
        status: response.status()
      })
  })
  await page.route('**/api/v1/knowledge/chunks/update', async (route) => {
    if (route.request().method() !== 'PATCH' || intercepted) return route.continue()
    intercepted = true
    const response = await route.fetch()
    const body = await response.json()
    upstream = { status: response.status(), request: route.request().postDataJSON(), body }
    await writeFile(join(evidence, 'first-upstream.json'), JSON.stringify(upstream, null, 2))
    expect(response.status()).toBe(200)
    await route.abort('failed')
    discarded = true
  })
  try {
    await page.goto(
      `/login?redirect=${encodeURIComponent('/admin/knowledge/' + required('E2E_KNOWLEDGE_ID'))}`
    )
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const row = page.getByRole('row').filter({ hasText: title })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '编辑 / 分块', exact: true }).click()
    const panel = page.getByRole('dialog', { name: title, exact: true })
    await expect(panel).toBeVisible()
    opened = true
    await panel.getByRole('tab', { name: '分块', exact: true }).click()
    const first = panel.locator('.el-card').filter({ hasText: '分块 0 ·' })
    const second = panel.locator('.el-card').filter({ hasText: '分块 1 ·' })
    await expect(first.locator('textarea')).toHaveValue(required('E2E_FIRST_CHUNK'))
    await expect(second.locator('textarea')).toHaveValue(required('E2E_OLD_CHUNK'))
    await first.locator('textarea').fill(required('E2E_EDITED_CHUNK'))
    await second.locator('textarea').fill(required('E2E_SECOND_DRAFT'))
    await first.getByRole('button', { name: '保存分块', exact: true }).click()
    await expect.poll(() => discarded && writes === 1).toBe(true)
    if (candidate) {
      await expect(
        panel.getByText(
          '分块保存结果尚未确认；请核对最新分块，恢复编辑或后续保存不会确认原请求。',
          { exact: true }
        )
      ).toBeVisible()
      await expect(second.getByRole('button', { name: '保存分块', exact: true })).toBeDisabled()
    } else {
      await expect(second.getByRole('button', { name: '保存分块', exact: true })).toBeEnabled()
    }
    await expect(first.locator('textarea')).toHaveValue(required('E2E_EDITED_CHUNK'))
    await expect(second.locator('textarea')).toHaveValue(required('E2E_SECOND_DRAFT'))
    await page.screenshot({ path: join(evidence, 'after-lost-receipt.png'), fullPage: true })
    await writeFile(
      join(evidence, 'after-lost-receipt.html'),
      await page.locator('body').innerHTML()
    )
    if (candidate) {
      const detailResponse = page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/knowledge/documents/detail' &&
          response.request().method() === 'GET'
      )
      const chunksResponse = page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/knowledge/documents/chunks' &&
          response.request().method() === 'GET'
      )
      await page.getByRole('button', { name: '核对最新分块', exact: true }).click()
      const detail = await detailResponse
      const chunks = await chunksResponse
      expect(detail.status()).toBe(200)
      expect(chunks.status()).toBe(200)
      const detailBody = await detail.json()
      const chunksBody = await chunks.json()
      recoveredSource = detailBody.data.source_version
      recoveredChunkId = String(
        chunksBody.data.items.find((item: { chunk_index: number }) => item.chunk_index === 1).id
      )
      await writeFile(
        join(evidence, 'check-responses.json'),
        JSON.stringify({ detail: detailBody, chunks: chunksBody }, null, 2)
      )
      expect(recoveredSource).not.toBe(required('E2E_SOURCE_VERSION'))
      expect(recoveredChunkId).not.toBe(required('E2E_OLD_SECOND_CHUNK_ID'))
      await expect(
        page.getByRole('button', { name: '按当前版本恢复编辑', exact: true })
      ).toBeVisible()
      expect(writes).toBe(1)
      await expect(second.getByRole('button', { name: '保存分块', exact: true })).toBeDisabled()
      await page.screenshot({ path: join(evidence, 'after-check.png'), fullPage: true })
      await page.getByRole('button', { name: '按当前版本恢复编辑', exact: true }).click()
      await expect(second.locator('textarea')).toHaveValue(required('E2E_SECOND_DRAFT'))
      await expect(second.getByRole('button', { name: '保存分块', exact: true })).toBeEnabled()
      expect(writes).toBe(1)
    }
    const [secondResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/knowledge/chunks/update' &&
          response.request().method() === 'PATCH'
      ),
      second.getByRole('button', { name: '保存分块', exact: true }).click()
    ])
    await writeFile(
      join(evidence, 'second-response.json'),
      JSON.stringify(
        {
          status: secondResponse.status(),
          request: secondResponse.request().postDataJSON(),
          body: await secondResponse.text()
        },
        null,
        2
      )
    )
    if (candidate) {
      expect(secondResponse.status()).toBe(200)
      const first = upstream as {
        request: { payload: { expected_source_version: string } }
        body: { data: { source_version: string } }
      }
      const secondRequest = secondResponse.request().postDataJSON() as {
        chunk_id: string
        payload: { expected_source_version: string }
      }
      expect(String(secondRequest.chunk_id)).toBe(recoveredChunkId)
      expect(secondRequest.payload.expected_source_version).toBe(recoveredSource)
      expect(secondRequest.payload.expected_source_version).toBe(first.body.data.source_version)
      expect(secondRequest.payload.expected_source_version).not.toBe(
        first.request.payload.expected_source_version
      )
    }
    expect(writes).toBe(2)
    if (candidate) {
      const firstRequest = (
        upstream as { request: { chunk_id: string; payload: { expected_source_version: string } } }
      ).request
      const records = panel.getByRole('status')
      await expect(
        records.getByText(
          '分块保存结果尚未确认；请核对最新分块，恢复编辑或后续保存不会确认原请求。',
          { exact: true }
        )
      ).toBeVisible()
      await expect(
        records.getByText(
          `原来源 ${firstRequest.payload.expected_source_version} · 分块 ${firstRequest.chunk_id}`,
          { exact: true }
        )
      ).toBeVisible()
    }
    await expect(second.locator('textarea')).toHaveValue(required('E2E_SECOND_DRAFT'))
    expect(errors).toEqual([])
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify({ docId, writes, intercepted, discarded, upstream, errors, network }, null, 2)
    )
    if (opened) {
      await writeFile(join(evidence, 'final.html'), await page.locator('body').innerHTML())
      await page.screenshot({ path: join(evidence, 'final.png'), fullPage: true })
    }
  }
})
