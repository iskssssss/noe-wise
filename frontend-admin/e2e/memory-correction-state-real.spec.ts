import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`缺少记忆纠正验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
// 凭据不写入 trace；业务 HTTP 正文由本用例显式选择保存。
test.use({ trace: 'off', video: 'off' })
test('遗忘记忆先恢复再纠正，下一运行只使用新正文', async ({ page }) => {
  test.setTimeout(150_000)
  const mode = required('E2E_MEMORY_CORRECTION_STATE')
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR'),
    factId = required('E2E_MEMORY_FACT_ID')
  const query = required('E2E_MEMORY_QUERY'),
    oldText = required('E2E_MEMORY_OLD_TEXT')
  const newText = required('E2E_MEMORY_NEW_TEXT'),
    originalRevision = Number(required('E2E_MEMORY_REVISION'))
  const requests: unknown[] = [],
    errors: string[] = [],
    writes: string[] = []
  let replacement: Record<string, unknown> | undefined
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path.startsWith('/api/v1/memories/')) writes.push(path)
  })
  try {
    await page.goto('/login?redirect=/admin/memory')
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/memory$/)
    await page.getByRole('textbox', { name: '搜索记忆', exact: true }).fill(query)
    const searched = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/memories/page' && url.searchParams.get('q') === query
    })
    await page.getByRole('button', { name: '查询', exact: true }).click()
    const search = await searched
    expect(search.status()).toBe(200)
    const initial = await search.json()
    requests.push({ path: '/api/v1/memories/page', query, status: search.status(), body: initial })
    const initialFact = initial.data.items.find(
      (item: { ref: { fact_id: string } }) => item.ref.fact_id === factId
    )
    expect(initialFact.status).toBe('ACTIVE')
    expect(initialFact.revision).toBe(originalRevision)
    const row = page.getByRole('row').filter({ hasText: oldText })
    await expect(row.getByRole('button', { name: '纠正', exact: true })).toBeEnabled()
    await row.getByRole('button', { name: '遗忘', exact: true }).click()
    const forgotten = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/memories/forget'
    )
    await page
      .getByRole('dialog', { name: '遗忘记忆', exact: true })
      .getByRole('button', { name: '遗忘记忆', exact: true })
      .click()
    const forget = await forgotten,
      forgottenBody = await forget.json()
    requests.push({
      path: '/api/v1/memories/forget',
      command: forget.request().postDataJSON(),
      status: forget.status(),
      body: forgottenBody
    })
    expect(forget.status()).toBe(200)
    expect(forgottenBody.data.status).toBe('FORGOTTEN')
    expect(forgottenBody.data.revision).toBe(originalRevision + 1)
    await expect(row.getByRole('button', { name: '恢复', exact: true })).toBeEnabled()
    await page.screenshot({ path: join(evidence, 'forgotten-actions.png'), fullPage: true })
    if (mode === 'candidate') {
      await expect(row.getByRole('button', { name: '纠正', exact: true })).toBeDisabled()
      await expect(row.getByText('恢复后可纠正', { exact: true })).toBeVisible()
      expect(writes.filter((path) => path.endsWith('/correct'))).toHaveLength(0)
      await row.getByRole('button', { name: '恢复', exact: true }).click()
      const restored = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === '/api/v1/memories/restore'
      )
      await page
        .getByRole('dialog', { name: '恢复记忆', exact: true })
        .getByRole('button', { name: '恢复记忆', exact: true })
        .click()
      const restore = await restored,
        restoredBody = await restore.json()
      requests.push({
        path: '/api/v1/memories/restore',
        command: restore.request().postDataJSON(),
        status: restore.status(),
        body: restoredBody
      })
      expect(restore.status()).toBe(200)
      expect(restoredBody.data.status).toBe('ACTIVE')
      expect(restoredBody.data.revision).toBe(originalRevision + 2)
    }
    await expect(row.getByRole('button', { name: '纠正', exact: true })).toBeEnabled()
    await row.getByRole('button', { name: '纠正', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '纠正记忆', exact: true })
    await expect(drawer.getByRole('textbox', { name: '记忆内容', exact: true })).toHaveValue(
      oldText
    )
    await drawer.getByRole('textbox', { name: '记忆内容', exact: true }).fill(newText)
    const corrected = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/memories/correct'
    )
    await drawer.getByRole('button', { name: '保存纠正', exact: true }).click()
    const correct = await corrected,
      correctedBody = await correct.json(),
      command = correct.request().postDataJSON()
    requests.push({
      path: '/api/v1/memories/correct',
      command,
      status: correct.status(),
      body: correctedBody
    })
    expect(command.fact_id).toBe(factId)
    expect(command.payload.expected_revision).toBe(originalRevision + (mode === 'baseline' ? 1 : 2))
    expect(command.payload.object_json).toContain(newText)
    if (mode === 'baseline') {
      expect(correct.status()).toBe(422)
      expect(JSON.stringify(correctedBody)).toContain('memory_supersede_source_mismatch')
      await expect(drawer).toBeVisible()
      await expect(drawer.getByRole('textbox', { name: '记忆内容', exact: true })).toHaveValue(
        newText
      )
    } else {
      expect(correct.status()).toBe(200)
      replacement = correctedBody.data
      expect(correctedBody.data.status).toBe('ACTIVE')
      expect(correctedBody.data.ref.fact_id).not.toBe(factId)
      await expect(drawer).toBeHidden()
      await expect(page.getByRole('row').filter({ hasText: newText })).toBeVisible()
      await expect(row.getByRole('button', { name: '纠正', exact: true })).toBeDisabled()
    }
    expect(writes.filter((path) => path.endsWith('/correct'))).toHaveLength(1)
    expect(writes.filter((path) => path.endsWith('/forget'))).toHaveLength(1)
    expect(writes.filter((path) => path.endsWith('/restore'))).toHaveLength(
      mode === 'candidate' ? 1 : 0
    )
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'browser-result.json'),
      JSON.stringify({ mode, factId, replacement, writes }, null, 2)
    )
  } finally {
    await writeFile(
      join(evidence, 'http.json'),
      JSON.stringify({ requests, errors, writes }, null, 2)
    )
    await writeFile(join(evidence, 'final.html'), await page.locator('body').innerHTML())
    await page.screenshot({ path: join(evidence, 'final.png'), fullPage: true })
  }
})
