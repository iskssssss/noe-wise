import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { ChatHistoryResponse } from '../src/api'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少会话引用验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('从会话问答记录查看真实 Runtime 采用的知识原文', async ({ page }) => {
  test.setTimeout(150_000)
  const mode = required('E2E_SESSION_CITATION')
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR'),
    sessionId = required('E2E_SESSION_ID')
  const messageId = required('E2E_MESSAGE_ID'),
    citationId = required('E2E_CITATION_ID')
  const sourceVersion = required('E2E_SOURCE_VERSION'),
    sentinel = required('E2E_SENTINEL')
  const expectedReply = required('E2E_EXPECTED_REPLY')
  const errors: string[] = []
  const network: { path: string; method: string; status: number }[] = []
  let citationReads = 0,
    submissions = 0,
    historyOpened = false
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/chat/citations/content') citationReads++
    if (path === '/api/v1/chat' && request.method() === 'POST') submissions++
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/v1/chat') || url.pathname.startsWith('/api/v1/sessions'))
      network.push({
        path: url.pathname + url.search,
        method: response.request().method(),
        status: response.status()
      })
  })
  try {
    await page.goto(`/login?redirect=${encodeURIComponent('/admin/sessions')}`)
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const row = page.getByRole('row').filter({ hasText: sessionId })
    await expect(row).toBeVisible()
    const historyReady = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/chat/history' && url.searchParams.get('session_id') === sessionId
      )
    })
    await row.locator('.session-summary').click()
    const historyResponse = await historyReady
    await writeFile(join(evidence, 'history.json'), await historyResponse.text())
    expect(historyResponse.status()).toBe(200)
    const history: ChatHistoryResponse = (await historyResponse.json()).data
    expect(history.session_id).toBe(sessionId)
    expect(history.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    const answer = history.messages.find((message) => message.message_id === messageId)
    expect(answer?.role).toBe('assistant')
    expect(answer?.content).toContain(expectedReply)
    expect(answer?.metadata.citations).toMatchObject([
      { citation_id: citationId, source_version: sourceVersion, label: '知识依据 1' }
    ])
    const panel = page.getByRole('dialog', { name: '会话详情', exact: true })
    await expect(panel).toBeVisible()
    historyOpened = true
    await expect
      .poll(() =>
        panel.evaluate((element) => {
          const rect = element.getBoundingClientRect()
          return rect.left >= -1 && rect.right <= window.innerWidth + 1
        })
      )
      .toBe(true)
    await expect(panel).toContainText(expectedReply)
    const citationButton = panel.getByRole('button', { name: '知识依据 1', exact: true })
    expect(citationReads).toBe(0)
    if (mode === 'baseline') {
      await expect(citationButton).toHaveCount(0)
    } else {
      await expect(panel.locator(`#citation-${citationId}`)).toBeVisible()
      await expect(citationButton).toBeVisible()
      const [citationResponse] = await Promise.all([
        page.waitForResponse(
          (response) => new URL(response.url()).pathname === '/api/v1/chat/citations/content'
        ),
        citationButton.click()
      ])
      const url = new URL(citationResponse.url())
      const body = await citationResponse.json()
      await writeFile(
        join(evidence, 'citation-read.json'),
        JSON.stringify(
          {
            path: url.pathname + url.search,
            status: citationResponse.status(),
            cacheControl: citationResponse.headers()['cache-control'],
            body
          },
          null,
          2
        )
      )
      expect(citationResponse.status()).toBe(200)
      expect(citationResponse.headers()['cache-control']).toBe('no-store')
      expect(url.searchParams.get('session_id')).toBe(sessionId)
      expect(url.searchParams.get('message_id')).toBe(messageId)
      expect(url.searchParams.get('citation_id')).toBe(citationId)
      expect(body.data.source_version).toBe(sourceVersion)
      expect(body.data.text).toContain(sentinel)
      const citationPanel = page.getByRole('dialog', { name: '知识依据', exact: true })
      await expect(citationPanel).toBeVisible()
      await expect(citationPanel).toContainText(`来源版本：${sourceVersion}`)
      await expect(citationPanel.locator('.citation-text')).toContainText(sentinel)
      await expect
        .poll(() =>
          citationPanel.evaluate((element) => {
            const rect = element.getBoundingClientRect()
            return rect.left >= -1 && rect.right <= window.innerWidth + 1
          })
        )
        .toBe(true)
      await page.screenshot({ path: join(evidence, 'citation-open.png'), fullPage: true })
      await writeFile(join(evidence, 'citation-open.html'), await citationPanel.innerHTML())
      await citationPanel.locator('.el-drawer__close-btn').click()
      await expect(citationPanel).toBeHidden()
      await expect(panel).toBeVisible()
      await expect(panel).toContainText(expectedReply)
      await expect(citationButton).toBeVisible()
      const originalUrl = page.url()
      const [inlineResponse] = await Promise.all([
        page.waitForResponse(
          (response) => new URL(response.url()).pathname === '/api/v1/chat/citations/content'
        ),
        panel.locator(`a[href="#citation-${citationId}"]`).click()
      ])
      expect(inlineResponse.status()).toBe(200)
      expect(inlineResponse.url()).toBe(citationResponse.url())
      expect(page.url()).toBe(originalUrl)
      await expect(citationPanel).toBeVisible()
      await expect(citationPanel.locator('.citation-text')).toContainText(sentinel)
      await writeFile(
        join(evidence, 'inline-citation-read.json'),
        JSON.stringify(
          { status: inlineResponse.status(), body: await inlineResponse.json() },
          null,
          2
        )
      )
      await citationPanel.locator('.el-drawer__close-btn').click()
      await expect(citationPanel).toBeHidden()
      await expect(panel).toBeVisible()
      await expect(panel).toContainText(expectedReply)
    }
    expect(citationReads).toBe(mode === 'baseline' ? 0 : 2)
    expect(submissions).toBe(0)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'result.json'),
      JSON.stringify(
        {
          mode,
          sessionId,
          messageId,
          citationId,
          sourceVersion,
          citationReads,
          submissions,
          errors
        },
        null,
        2
      )
    )
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify(
        { mode, url: page.url(), citationReads, submissions, errors, network },
        null,
        2
      )
    )
    if (historyOpened) {
      await writeFile(
        join(evidence, `session-citation-${mode}.html`),
        await page.locator('body').innerHTML()
      )
      await page.screenshot({
        path: join(evidence, `session-citation-${mode}.png`),
        fullPage: true
      })
    }
  }
})
