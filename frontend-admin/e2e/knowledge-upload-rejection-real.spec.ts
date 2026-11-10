import { expect, test } from '@playwright/test'
import { access, readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少知识上传验收参数：${name}`)
  return value
}

async function exists(path: string): Promise<boolean> {
  try {
    await access(path)
    return true
  } catch {
    return false
  }
}

test.describe.configure({ retries: 0 })
// 不记录含登录凭据的 trace，只保存明确选择的业务请求和响应。
test.use({ trace: 'off', video: 'off' })
test('重建写锁拒绝上传后回执终结并可发布新文档', async ({ page }) => {
  test.setTimeout(170_000)
  const mode = required('E2E_KNOWLEDGE_UPLOAD_REJECTION')
  expect(['baseline', 'candidate']).toContain(mode)
  const kb = required('E2E_KNOWLEDGE_ID'),
    evidence = required('E2E_ARTIFACT_DIR')
  const gate = required('E2E_GATE_DIR'),
    marker = required('E2E_KNOWLEDGE_MARKER')
  const errors: string[] = [],
    writes: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path.startsWith('/api/v1/knowledge/')) writes.push(path)
  })
  const uploadPath = '/api/v1/knowledge/bases/documents/upload'
  try {
    await page.goto(`/login?redirect=${encodeURIComponent('/admin/knowledge/' + kb)}`)
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill(required('E2E_USERNAME'))
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/admin/knowledge/${kb}$`))
    const uploadButton = page.locator('button').filter({ hasText: /^上传文件$/ })
    await expect(uploadButton).toBeEnabled()
    await expect(page.getByRole('tab', { name: '文档维护' })).toHaveAttribute(
      'aria-selected',
      'true'
    )
    await writeFile(join(gate, 'page-ready'), 'ready')
    await expect
      .poll(() => exists(join(gate, 'rebuild-ready.json')), { timeout: 60_000 })
      .toBe(true)
    const rebuild = JSON.parse(await readFile(join(gate, 'rebuild-ready.json'), 'utf8'))
    expect(rebuild.status).toBe('READY')
    expect(rebuild.write_locked).toBe(true)
    await expect(uploadButton).toBeEnabled()
    const rejected = page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === uploadPath
    )
    const rejectedFile = {
      name: 'locked-upload.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('This file must not create a document while rebuild is locked.')
    }
    await page.locator('input[type="file"]').setInputFiles(rejectedFile)
    const response = await rejected
    const requestId = new URL(response.url()).searchParams.get('request_id')!
    const body = await response.json()
    await writeFile(
      join(gate, 'upload-response.json'),
      JSON.stringify(
        {
          requestId,
          status: response.status(),
          body,
          file: { name: rejectedFile.name, text: rejectedFile.buffer.toString() }
        },
        null,
        2
      )
    )
    if (mode === 'baseline') {
      expect(response.status()).toBe(409)
      expect(body.code).toBe('CONFLICT')
      await expect(page.getByText(/上传结果尚未确认/)).toBeVisible()
      await expect(uploadButton).toBeDisabled()
    } else {
      expect(response.status()).toBe(200)
      expect(body.data.status).toBe('REJECTED')
      expect(body.data.request_id).toBe(requestId)
      await expect(page.getByText(/上传结果尚未确认/)).toHaveCount(0)
    }
    await page.screenshot({ path: join(evidence, 'upload-during-lock.png'), fullPage: true })
    await writeFile(join(gate, 'cancel-rebuild'), 'cancel')
    await expect
      .poll(() => exists(join(gate, 'rebuild-cancelled.json')), { timeout: 30_000 })
      .toBe(true)
    const cancelled = JSON.parse(await readFile(join(gate, 'rebuild-cancelled.json'), 'utf8'))
    expect(cancelled.status).toBe('CANCELLED')
    expect(cancelled.write_locked).toBe(false)
    const checked = page.waitForResponse(
      (r) => new URL(r.url()).pathname === uploadPath + '/result'
    )
    if (mode === 'baseline')
      await page.getByRole('button', { name: '核对上传回执', exact: true }).click()
    else {
      await page.getByText('按请求 ID 查询历史上传回执', { exact: true }).click()
      await page.getByRole('textbox', { name: '上传请求 ID', exact: true }).fill(requestId)
      await page.getByRole('button', { name: '查询上传回执', exact: true }).click()
    }
    const lookup = await checked
    expect(lookup.status()).toBe(200)
    const receipt = (await lookup.json()).data
    expect(receipt.request_id).toBe(requestId)
    expect(receipt.status).toBe(mode === 'baseline' ? 'PROCESSING' : 'REJECTED')
    await writeFile(join(evidence, 'receipt-after-cancel.json'), JSON.stringify(receipt, null, 2))
    if (mode === 'baseline') {
      await expect(uploadButton).toBeDisabled()
      await expect(page.getByText(/上传结果尚未确认/)).toBeVisible()
    } else {
      await expect(uploadButton).toBeEnabled()
      const uploaded = page.waitForResponse(
        (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === uploadPath
      )
      const fileName = 'recovered-upload.txt'
      await page.locator('input[type="file"]').setInputFiles({
        name: fileName,
        mimeType: 'text/plain',
        buffer: Buffer.from(
          `The newly published source contains ${marker}. Cite this exact source.`
        )
      })
      const successful = await uploaded
      expect(successful.status()).toBe(200)
      const upload = (await successful.json()).data
      expect(upload.status).toBe('SUCCEEDED')
      expect(upload.request_id).not.toBe(requestId)
      await writeFile(join(evidence, 'new-upload.json'), JSON.stringify(upload, null, 2))
      await expect(page.getByText('recovered-upload', { exact: true })).toBeVisible()
      await page.getByRole('button', { name: '更新知识发布', exact: true }).click()
      const drawer = page.getByRole('dialog', { name: '准备知识发布', exact: true })
      await expect(drawer.getByRole('checkbox')).toHaveCount(2)
      await drawer.getByRole('button', { name: '取消选择当前显示文档', exact: true }).click()
      await drawer.locator('label[aria-label="包含文档 recovered-upload"]').click()
      await expect(
        drawer.getByRole('checkbox', { name: '包含文档 recovered-upload', exact: true })
      ).toBeChecked()
      const published = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/knowledge/bases/revisions/prepare-and-publish'
      )
      await drawer.getByRole('button', { name: '确认范围并发布', exact: true }).click()
      await page.getByRole('button', { name: '确认发布', exact: true }).click()
      const publication = await published
      expect(publication.status()).toBe(200)
      const publicationBody = (await publication.json()).data
      expect(publicationBody.status).toBe('SUCCEEDED')
      expect(publicationBody.revision.source_versions).toEqual([upload.document.source_version])
      await writeFile(
        join(evidence, 'publication.json'),
        JSON.stringify(
          { command: publication.request().postDataJSON(), receipt: publicationBody },
          null,
          2
        )
      )
      await expect(page.getByText(/知识发布已确认：修订/)).toBeVisible()
      await expect(drawer).toBeHidden()
      await expect(page.getByRole('button', { name: '确认发布', exact: true })).toBeHidden()
      await expect(uploadButton).toBeEnabled()
      await expect(page.getByText('当前发布 · 修订 2', { exact: true })).toBeVisible()
    }
    expect(writes.filter((path) => path === uploadPath)).toHaveLength(mode === 'baseline' ? 1 : 2)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'browser-result.json'),
      JSON.stringify({ mode, requestId, receipt, writes, errors }, null, 2)
    )
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify({ mode, writes, errors }, null, 2)
    )
    await writeFile(join(evidence, 'final.html'), await page.locator('body').innerHTML())
    await page.screenshot({ path: join(evidence, 'final.png'), fullPage: true })
  }
})
