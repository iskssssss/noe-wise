import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string) {
  const value = process.env[name]
  if (!value) throw Error(`缺少知识重新索引验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('重新索引成功但回执丢失时可恢复原请求状态', async ({ page }) => {
  test.setTimeout(150_000)
  const evidence = required('E2E_ARTIFACT_DIR')
  const candidate = process.env.E2E_KNOWLEDGE_REINDEX_RECEIPT === 'candidate'
  let writes = 0
  let intercepted = false
  let upstream: object | undefined

  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/knowledge/documents/reindex'
    )
      writes++
  })
  await page.route('**/api/v1/knowledge/documents/reindex', async (route) => {
    if (route.request().method() !== 'POST' || intercepted) return route.continue()
    intercepted = true
    const response = await route.fetch()
    const body = await response.json()
    upstream = {
      status: response.status(),
      request: route.request().postDataJSON(),
      body
    }
    await writeFile(join(evidence, 'first-upstream.json'), JSON.stringify(upstream, null, 2))
    expect(response.status()).toBe(200)
    await route.abort('failed')
  })

  try {
    await page.goto(
      `/login?redirect=${encodeURIComponent('/admin/knowledge/' + required('E2E_KNOWLEDGE_ID'))}`
    )
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()

    const row = page.getByRole('row').filter({ hasText: required('E2E_TARGET_TITLE') })
    const reindex = row.getByRole('button', { name: '重新索引', exact: true })
    await expect(row).toBeVisible()
    await reindex.click()
    await expect.poll(() => intercepted && writes === 1).toBe(true)

    if (candidate) {
      await expect(
        page.getByText(
          '文档重新索引结果尚未确认；请按原请求核对结果，当前不会使用旧版本再次提交。',
          { exact: true }
        )
      ).toBeVisible()
      await expect(reindex).toBeDisabled()
      await writeFile(
        join(evidence, 'storage-before-reload.json'),
        JSON.stringify(
          {
            url: page.url(),
            entries: await page.evaluate(() => Object.fromEntries(Object.entries(sessionStorage)))
          },
          null,
          2
        )
      )
      await page.reload()
      await writeFile(
        join(evidence, 'storage-after-reload.json'),
        JSON.stringify(
          {
            url: page.url(),
            entries: await page.evaluate(() => Object.fromEntries(Object.entries(sessionStorage)))
          },
          null,
          2
        )
      )
      await expect(
        page.getByText(
          '文档重新索引结果尚未确认；请按原请求核对结果，当前不会使用旧版本再次提交。',
          { exact: true }
        )
      ).toBeVisible()
      await expect(reindex).toBeDisabled()
      const checked = page.waitForResponse(
        (response) =>
          response.request().method() === 'GET' &&
          new URL(response.url()).pathname === '/api/v1/knowledge/documents/reindex/result'
      )
      await page.getByRole('button', { name: '核对重建结果', exact: true }).click()
      const receipt = await checked
      const receiptBody = await receipt.json()
      await writeFile(
        join(evidence, 'result-response.json'),
        JSON.stringify({ status: receipt.status(), body: receiptBody }, null, 2)
      )
      expect(receipt.status()).toBe(200)
      expect(receiptBody.data.status).toBe('SUCCEEDED')
      expect(receiptBody.data.document.source_version).toBeTruthy()
      await expect(page.getByText('文档重新索引已完成')).toBeVisible()
      await expect(reindex).toBeEnabled()
    } else {
      await expect(reindex).toBeEnabled()
      const second = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === '/api/v1/knowledge/documents/reindex'
      )
      await reindex.click()
      const conflict = await second
      await writeFile(
        join(evidence, 'second-response.json'),
        JSON.stringify(
          {
            status: conflict.status(),
            request: conflict.request().postDataJSON(),
            body: await conflict.json()
          },
          null,
          2
        )
      )
      expect(conflict.status()).toBe(409)
    }

    expect(writes).toBe(candidate ? 1 : 2)
    await page.screenshot({
      path: join(evidence, candidate ? 'after-check.png' : 'after-conflict.png'),
      fullPage: true
    })
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify({ writes, intercepted, upstream }, null, 2)
    )
  }
})
