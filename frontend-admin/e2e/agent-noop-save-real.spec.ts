import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少智能体验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('未修改的已发布智能体无需保存草稿', async ({ page }) => {
  test.setTimeout(120_000)
  const mode = required('E2E_AGENT_NOOP_SAVE')
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  const agent = required('E2E_AGENT_ID')
  const revision = Number(required('E2E_PUBLISHED_REVISION'))
  const writes: unknown[] = []
  const errors: string[] = []
  let opened = false
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    if (request.method() === 'PATCH' && new URL(request.url()).pathname === '/api/v1/agents/update')
      writes.push(request.postDataJSON())
  })
  try {
    await page.goto(`/login?redirect=${encodeURIComponent('/admin/agents/' + agent)}`)
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    const detail = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return (
        url.pathname === '/api/v1/agents/detail' &&
        url.searchParams.get('agentId') === agent &&
        response.ok()
      )
    })
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const initial = await detail
    const initialBody = await initial.json()
    expect(initialBody.data.status).toBe('published')
    expect(initialBody.data.has_draft_changes).toBe(false)
    expect(initialBody.data.published_revision).toBe(revision)
    await expect(page.getByText('已与发布同步', { exact: true })).toBeVisible()
    opened = true
    const save = page.getByRole('button', { name: '保存草稿', exact: true })
    if (mode === 'baseline') {
      await expect(save).toBeEnabled()
      const saved = page.waitForResponse(
        (response) =>
          response.request().method() === 'PATCH' &&
          new URL(response.url()).pathname === '/api/v1/agents/update'
      )
      await save.click()
      const response = await saved
      const body = await response.json()
      await writeFile(
        join(evidence, 'save.json'),
        JSON.stringify(
          { status: response.status(), request: response.request().postDataJSON(), body },
          null,
          2
        )
      )
      expect(response.status()).toBe(200)
      await expect(page.getByText('草稿待发布', { exact: true })).toBeVisible()
      expect(writes).toHaveLength(1)
    } else {
      await expect(save).toBeDisabled()
      expect(writes).toHaveLength(0)
    }
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'browser-result.json'),
      JSON.stringify({ mode, agent, revision, writes }, null, 2)
    )
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify({ mode, agent, revision, writes, errors }, null, 2)
    )
    if (opened) {
      await writeFile(join(evidence, 'page.html'), await page.locator('body').innerHTML())
      await page.screenshot({ path: join(evidence, `agent-noop-${mode}.png`), fullPage: true })
    }
  }
})
