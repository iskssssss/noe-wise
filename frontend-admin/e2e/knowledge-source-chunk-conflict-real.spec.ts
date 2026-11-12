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
test('保留正文草稿并明确确认覆盖已保存分块', async ({ page }) => {
  test.setTimeout(150_000)
  const mode = required('E2E_KNOWLEDGE_SOURCE_CHUNK')
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  const docId = required('E2E_DOCUMENT_ID')
  const title = required('E2E_OLD_TITLE')
  const original = required('E2E_ORIGINAL_CONTENT')
  const draft = required('E2E_SOURCE_DRAFT')
  const beforeChunk = required('E2E_OLD_CHUNK')
  const editedChunk = required('E2E_EDITED_CHUNK')
  const network: { path: string; method: string; status: number }[] = []
  const errors: string[] = []
  let sourceWrites = 0
  let chunkWrites = 0
  let opened = false
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/knowledge/documents/update' && request.method() === 'PATCH')
      sourceWrites++
    if (path === '/api/v1/knowledge/chunks/update' && request.method() === 'PATCH') chunkWrites++
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
    const row = page.getByRole('row').filter({ hasText: title })
    await expect(row).toBeVisible()
    const [detail] = await Promise.all([
      page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/knowledge/documents/detail' &&
          new URL(response.url()).searchParams.get('docId') === docId
      ),
      row.getByRole('button', { name: '编辑 / 分块', exact: true }).click()
    ])
    expect(detail.status()).toBe(200)
    const panel = page.getByRole('dialog', { name: title, exact: true })
    await expect(panel).toBeVisible()
    opened = true
    const editor = panel.getByRole('textbox', { name: '代码编辑器', exact: true })
    await expect(editor).toHaveText(original)
    await editor.fill(draft)
    await panel.getByRole('tab', { name: '分块', exact: true }).click()
    const chunk = panel.locator('.el-card').filter({ hasText: '分块 1 ·' })
    await expect(chunk).toBeVisible()
    const chunkEditor = chunk.locator('textarea')
    await expect(chunkEditor).toHaveValue(beforeChunk)
    await chunkEditor.fill(editedChunk)
    const [saved] = await Promise.all([
      page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/knowledge/chunks/update' &&
          response.request().method() === 'PATCH'
      ),
      chunk.getByRole('button', { name: '保存分块', exact: true }).click()
    ])
    const response = await saved.json()
    await writeFile(
      join(evidence, 'chunk-update.json'),
      JSON.stringify(
        { status: saved.status(), request: saved.request().postDataJSON(), response },
        null,
        2
      )
    )
    expect(saved.status()).toBe(200)
    expect(response.data.content).toBe(editedChunk)
    expect(response.data.source_version).not.toBe(required('E2E_SOURCE_VERSION'))
    await expect
      .poll(
        () =>
          network.filter(
            (item) =>
              item.path.startsWith('/api/v1/knowledge/documents/detail') && item.status === 200
          ).length
      )
      .toBeGreaterThanOrEqual(2)
    await panel.getByRole('tab', { name: '原文', exact: true }).click()
    await expect(editor).toHaveText(draft)
    if (mode === 'baseline') {
      const [source] = await Promise.all([
        page.waitForResponse(
          (result) =>
            new URL(result.url()).pathname === '/api/v1/knowledge/documents/update' &&
            result.request().method() === 'PATCH'
        ),
        panel.getByRole('button', { name: '保存原文', exact: true }).click()
      ])
      const result = await source.json()
      await writeFile(
        join(evidence, 'source-update.json'),
        JSON.stringify(
          { status: source.status(), request: source.request().postDataJSON(), response: result },
          null,
          2
        )
      )
      expect(source.status()).toBe(200)
      expect(result.data.content).toBe(draft)
      expect(result.data.source_version).not.toBe(response.data.source_version)
      expect(source.request().postDataJSON().payload.expected_source_version).toBe(
        response.data.source_version
      )
      expect(sourceWrites).toBe(1)
    } else {
      await panel.getByRole('button', { name: '保存原文', exact: true }).click()
      const confirm = page.locator('.el-message-box')
      await expect(confirm).toBeVisible()
      await expect(confirm).toContainText('分块')
      await page.screenshot({ path: join(evidence, 'overwrite-confirmation.png'), fullPage: true })
      await confirm.getByRole('button', { name: '取消', exact: true }).click()
      await expect(confirm).toBeHidden()
      await expect(editor).toHaveText(draft)
      expect(sourceWrites).toBe(0)
    }
    expect(chunkWrites).toBe(1)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'result.json'),
      JSON.stringify(
        {
          mode,
          docId,
          chunkSource: response.data.source_version,
          sourceWrites,
          chunkWrites,
          network
        },
        null,
        2
      )
    )
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify({ mode, sourceWrites, chunkWrites, errors, network }, null, 2)
    )
    if (opened) {
      await writeFile(join(evidence, 'document.html'), await page.locator('body').innerHTML())
      await page.screenshot({ path: join(evidence, `document-${mode}.png`), fullPage: true })
    }
  }
})
