import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少知识标题验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('仅修改文档标题仍提交原正文并保留新来源', async ({ page }) => {
  test.setTimeout(120_000)
  const mode = required('E2E_KNOWLEDGE_TITLE_REUSE')
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  const documentId = required('E2E_DOCUMENT_ID')
  const oldTitle = required('E2E_OLD_TITLE')
  const newTitle = required('E2E_NEW_TITLE')
  const originalContent = required('E2E_ORIGINAL_CONTENT')
  const originalSource = required('E2E_SOURCE_VERSION')
  const errors: string[] = []
  const network: { path: string; method: string; status: number }[] = []
  let edits = 0
  let opened = false
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/v1/knowledge/documents/update') edits++
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
  try {
    await page.goto(
      `/login?redirect=${encodeURIComponent('/admin/knowledge/' + required('E2E_KNOWLEDGE_ID'))}`
    )
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const row = page.getByRole('row').filter({ hasText: oldTitle })
    await expect(row).toBeVisible()
    const [detail] = await Promise.all([
      page.waitForResponse((response) => {
        const url = new URL(response.url())
        return (
          url.pathname === '/api/v1/knowledge/documents/detail' &&
          url.searchParams.get('docId') === documentId
        )
      }),
      row.getByRole('button', { name: '编辑 / 分块', exact: true }).click()
    ])
    await writeFile(join(evidence, 'document-before.json'), await detail.text())
    expect(detail.status()).toBe(200)
    const original = (await detail.json()).data
    expect(String(original.id)).toBe(documentId)
    expect(original).toMatchObject({
      title: oldTitle,
      content: originalContent,
      source_version: originalSource
    })
    const panel = page.getByRole('dialog', { name: oldTitle, exact: true })
    await expect(panel).toBeVisible()
    opened = true
    await expect(panel.getByPlaceholder('文档标题', { exact: true })).toHaveValue(oldTitle)
    await expect(panel.getByRole('textbox', { name: '代码编辑器', exact: true })).toHaveText(
      originalContent
    )
    await panel.getByPlaceholder('文档标题', { exact: true }).fill(newTitle)
    const [saved] = await Promise.all([
      page.waitForResponse(
        (response) => new URL(response.url()).pathname === '/api/v1/knowledge/documents/update'
      ),
      panel.getByRole('button', { name: '保存原文', exact: true }).click()
    ])
    const request = saved.request().postDataJSON()
    const response = await saved.json()
    await writeFile(
      join(evidence, 'title-update.json'),
      JSON.stringify(
        {
          method: saved.request().method(),
          path: new URL(saved.url()).pathname,
          request,
          status: saved.status(),
          response
        },
        null,
        2
      )
    )
    expect(saved.request().method()).toBe('PATCH')
    expect(String(request.doc_id)).toBe(documentId)
    expect(request).toEqual({
      doc_id: request.doc_id,
      payload: {
        title: newTitle,
        content: originalContent,
        expected_source_version: originalSource
      }
    })
    expect(saved.status()).toBe(200)
    expect(String(response.data.id)).toBe(documentId)
    expect(response.data).toMatchObject({
      title: newTitle,
      content: originalContent,
      status: 'ready'
    })
    expect(response.data.source_version).not.toBe(originalSource)
    const updatedPanel = page.getByRole('dialog', { name: newTitle, exact: true })
    await expect(updatedPanel).toBeVisible()
    await expect(updatedPanel).toContainText(response.data.source_version)
    await expect(updatedPanel.getByRole('button', { name: '保存原文', exact: true })).toBeEnabled()
    await expect
      .poll(() =>
        updatedPanel.evaluate((element) => {
          const rect = element.getBoundingClientRect()
          return rect.left >= -1 && rect.right <= window.innerWidth + 1
        })
      )
      .toBe(true)
    expect(edits).toBe(1)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'result.json'),
      JSON.stringify(
        {
          mode,
          documentId,
          originalSource,
          sourceVersion: response.data.source_version,
          edits,
          errors
        },
        null,
        2
      )
    )
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify({ mode, url: page.url(), edits, errors, network }, null, 2)
    )
    if (opened) {
      await writeFile(join(evidence, 'document.html'), await page.locator('body').innerHTML())
      await page.screenshot({ path: join(evidence, `title-update-${mode}.png`), fullPage: true })
    }
  }
})
