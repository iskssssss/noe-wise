import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function fixture(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`Missing isolated fixture: ${name}`)
  return value
}

test('超过首批上限时旧记忆无法从当前治理结果定位', async ({ page }) => {
  const target = fixture('E2E_TARGET_TEXT')
  const mode = process.env.E2E_MEMORY_DIRECTORY_MODE ?? 'candidate'
  let listReads = 0
  const requests: { path: string; query: string | null; cursor: string | null }[] = []
  const initial = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return (
      url.pathname === (mode === 'baseline' ? '/api/v1/memories' : '/api/v1/memories/page') &&
      response.request().method() === 'GET'
    )
  })
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (
      request.method() === 'GET' &&
      ['/api/v1/memories', '/api/v1/memories/page'].includes(url.pathname)
    ) {
      listReads++
      requests.push({
        path: url.pathname,
        query: url.searchParams.get('q'),
        cursor: url.searchParams.get('cursor')
      })
    }
  })
  await page.goto('/login?redirect=/admin/memory')
  await page.locator('input[autocomplete="organization"]').fill(fixture('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
  await page.locator('input[autocomplete="current-password"]').fill(fixture('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/memory$/)
  const response = await initial
  expect(response.ok()).toBeTruthy()
  const body = await response.json()
  expect(body.code).toBe('OK')
  const items = mode === 'baseline' ? body.data : body.data.items
  expect(items).toHaveLength(100)
  expect(JSON.stringify(items)).not.toContain(target)
  if (mode === 'baseline') {
    await page.getByRole('textbox', { name: '筛选当前记忆', exact: true }).fill(target)
    await expect(
      page.getByText('当前结果没有匹配记忆，请调整关键词', { exact: true })
    ).toBeVisible()
    await expect(page.getByText(target, { exact: true })).toHaveCount(0)
  } else {
    expect(body.data.next_cursor).toBeTruthy()
    const next = page.waitForResponse((reply) => {
      const url = new URL(reply.url())
      return url.pathname === '/api/v1/memories/page' && !!url.searchParams.get('cursor')
    })
    await page.getByRole('button', { name: '加载更多', exact: true }).click()
    const nextBody = await (await next).json()
    expect(JSON.stringify(nextBody.data.items)).toContain(target)
    await expect(page.getByText(target, { exact: true }).first()).toBeVisible()

    const search = page.waitForResponse((reply) => {
      const url = new URL(reply.url())
      return url.pathname === '/api/v1/memories/page' && url.searchParams.get('q') === target
    })
    await page.getByRole('textbox', { name: '搜索记忆', exact: true }).fill(target)
    await page.getByRole('button', { name: '查询', exact: true }).click()
    const searchBody = await (await search).json()
    if (!JSON.stringify(searchBody.data.items).includes(target) && searchBody.data.next_cursor) {
      await page.getByRole('button', { name: '加载更多', exact: true }).click()
    }
    await expect(page.getByText(target, { exact: true }).first()).toBeVisible()
  }
  await page.screenshot({ path: join(fixture('E2E_ARTIFACT_DIR'), `directory-${mode}.png`) })
  await writeFile(
    join(fixture('E2E_ARTIFACT_DIR'), 'directory-baseline.json'),
    JSON.stringify({
      target,
      mode,
      listCount: items.length,
      listReads,
      requests,
      targetVisible: mode === 'candidate'
    })
  )
})
