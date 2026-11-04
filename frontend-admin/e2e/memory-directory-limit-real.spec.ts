import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

test('原文路径可定位同一条记忆事实', async ({ page }) => {
  test.skip(!process.env.E2E_MEMORY_LITERAL, '仅运行原文搜索场景')
  const target = fixture('E2E_TARGET_TEXT')
  const factId = fixture('E2E_TARGET_FACT')
  const mode = fixture('E2E_MEMORY_LITERAL')
  const evidence = fixture('E2E_ARTIFACT_DIR')
  await page.goto('/login?redirect=/admin/memory')
  await page.locator('input[autocomplete="organization"]').fill(fixture('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
  await page.locator('input[autocomplete="current-password"]').fill(fixture('E2E_PASSWORD'))
  const initial = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/memories/page'
  )
  await page.getByRole('button', { name: '登录', exact: true }).click()
  expect((await initial).status()).toBe(200)
  const search = page.getByRole('textbox', { name: '搜索记忆', exact: true })
  const requests: { query: string; response: unknown }[] = []
  for (const query of ['agent', target]) {
    await search.fill(query)
    const read = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/memories/page' && url.searchParams.get('q') === query
    })
    await page.getByRole('button', { name: '查询', exact: true }).click()
    const response = await read
    expect(response.status()).toBe(200)
    const body = await response.json()
    expect(body.code).toBe('OK')
    requests.push({ query: new URL(response.url()).searchParams.get('q')!, response: body })
    const expected = query === 'agent' || mode === 'candidate' ? 1 : 0
    expect(
      body.data.items.filter((item: { ref: { fact_id: string } }) => item.ref.fact_id === factId)
    ).toHaveLength(expected)
    if (query === 'agent') {
      const row = page.getByRole('row').filter({ hasText: 'workspace' })
      await row.getByRole('button', { name: '纠正', exact: true }).click()
      const editor = page.getByRole('dialog', { name: '纠正记忆' })
      await expect(editor.getByRole('textbox', { name: '记忆内容', exact: true })).toHaveValue(
        target
      )
      await page.screenshot({ path: join(evidence, 'literal-editor.png'), fullPage: true })
      await page.keyboard.press('Escape')
      await expect(editor).toBeHidden()
    }
  }
  await page.screenshot({ path: join(evidence, 'literal-search.png'), fullPage: true })
  await writeFile(
    join(evidence, 'literal-search.json'),
    JSON.stringify({ mode, factId, target, requests }, null, 2)
  )
})

function fixture(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`Missing isolated fixture: ${name}`)
  return value
}

test('超过首批上限时旧记忆无法从当前治理结果定位', async ({ page }) => {
  test.skip(Boolean(process.env.E2E_MEMORY_RESTORE), '本轮验证衰减记忆恢复')
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

test('衰减记忆治理动作与新运行可用状态一致', async ({ page }) => {
  const mode = process.env.E2E_MEMORY_RESTORE
  test.skip(!mode, '仅运行衰减恢复专项')
  const target = fixture('E2E_TARGET_TEXT')
  const factId = fixture('E2E_MEMORY_FACT_ID')
  const revision = Number(fixture('E2E_MEMORY_REVISION'))
  await page.goto('/login?redirect=/admin/memory')
  await page.locator('input[autocomplete="organization"]').fill(fixture('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
  await page.locator('input[autocomplete="current-password"]').fill(fixture('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/memory$/)
  await page.getByRole('textbox', { name: '搜索记忆', exact: true }).fill(target)
  const searched = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/v1/memories/page' && url.searchParams.get('q') === target
  })
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const searchEnvelope = await (await searched).json()
  expect(searchEnvelope.code).toBe('OK')
  const before = searchEnvelope.data.items.find(
    (item: { ref: { fact_id: string } }) => item.ref.fact_id === factId
  )
  expect(before.status).toBe('DECAYED')
  expect(before.revision).toBe(revision)
  const row = page.getByRole('row').filter({ hasText: target })
  const action = mode === 'baseline' ? '遗忘' : '恢复'
  await expect(row.getByRole('button', { name: action, exact: true })).toBeVisible()
  await page.screenshot({
    path: join(fixture('E2E_ARTIFACT_DIR'), 'memory-decayed-before.png'),
    fullPage: true
  })
  await row.getByRole('button', { name: action, exact: true }).click()
  const operation = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname ===
        `/api/v1/memories/${mode === 'baseline' ? 'forget' : 'restore'}` &&
      response.request().method() === 'POST'
  )
  await page
    .getByRole('dialog', { name: action + '记忆', exact: true })
    .getByRole('button', { name: action + '记忆', exact: true })
    .click()
  const response = await operation
  const body = await response.text()
  const command = response.request().postDataJSON()
  expect(command.fact_id).toBe(factId)
  expect(command.payload.expected_revision).toBe(revision)
  expect(response.status()).toBe(mode === 'baseline' ? 422 : 200)
  const envelope = JSON.parse(body)
  if (mode === 'candidate') {
    expect(envelope.code).toBe('OK')
    expect(envelope.data.status).toBe('ACTIVE')
    expect(envelope.data.revision).toBe(revision + 1)
    await expect(row.getByRole('button', { name: '遗忘', exact: true })).toBeVisible()
  } else await expect(row.getByRole('button', { name: '遗忘', exact: true })).toBeVisible()
  await page.screenshot({
    path: join(fixture('E2E_ARTIFACT_DIR'), 'memory-decayed-after.png'),
    fullPage: true
  })
  await writeFile(
    join(fixture('E2E_ARTIFACT_DIR'), 'memory-restore.json'),
    JSON.stringify(
      {
        mode,
        target,
        factId,
        beforeStatus: before.status,
        beforeRevision: revision,
        action,
        httpStatus: response.status(),
        command,
        response: envelope
      },
      null,
      2
    )
  )
  const reviewTarget = fixture('E2E_MEMORY_REVIEW_TARGET')
  const reviewId = fixture('E2E_MEMORY_REVIEW_FACT_ID')
  const reviewRevision = Number(fixture('E2E_MEMORY_REVIEW_REVISION'))
  await page.getByRole('textbox', { name: '搜索记忆', exact: true }).fill(reviewTarget)
  const reviewSearch = page.waitForResponse((reply) => {
    const url = new URL(reply.url())
    return url.pathname === '/api/v1/memories/page' && url.searchParams.get('q') === reviewTarget
  })
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const pendingEnvelope = await (await reviewSearch).json()
  const pending = pendingEnvelope.data.items.find(
    (item: { ref: { fact_id: string } }) => item.ref.fact_id === reviewId
  )
  expect(pending.status).toBe('PENDING_CONFIRM')
  expect(pending.review_required).toBe(true)
  const reviewRow = page.getByRole('row').filter({ hasText: reviewTarget })
  await expect(reviewRow.getByRole('button', { name: '批准', exact: true })).toBeVisible()
  const rejected = page.waitForResponse(
    (reply) =>
      new URL(reply.url()).pathname === '/api/v1/memories/review' &&
      reply.request().method() === 'POST'
  )
  const rejectedList = page.waitForResponse((reply) => {
    const url = new URL(reply.url())
    return url.pathname === '/api/v1/memories/page' && url.searchParams.get('q') === reviewTarget
  })
  await reviewRow.getByRole('button', { name: '拒绝', exact: true }).click()
  const rejectedResponse = await rejected
  expect(rejectedResponse.status()).toBe(200)
  const rejectedEnvelope = await rejectedResponse.json()
  expect(rejectedEnvelope.code).toBe('OK')
  expect(rejectedEnvelope.data.status).toBe('REJECTED')
  expect(rejectedEnvelope.data.review_required).toBe(true)
  expect(rejectedEnvelope.data.revision).toBe(reviewRevision + 1)
  const reviewCommand = rejectedResponse.request().postDataJSON()
  expect(reviewCommand.fact_id).toBe(reviewId)
  expect(reviewCommand.payload).toMatchObject({
    expected_revision: reviewRevision,
    approved: false
  })
  const rejectedPage = await (await rejectedList).json()
  expect(
    rejectedPage.data.items.find(
      (item: { ref: { fact_id: string } }) => item.ref.fact_id === reviewId
    ).status
  ).toBe('REJECTED')
  const terminalActions: Record<string, number> = {}
  for (const label of ['批准', '拒绝', '遗忘', '恢复']) {
    const button = reviewRow.getByRole('button', { name: label, exact: true })
    await expect(button).toHaveCount(mode === 'baseline' && label !== '恢复' ? 1 : 0)
    terminalActions[label] = await button.count()
  }
  await page.screenshot({
    path: join(fixture('E2E_ARTIFACT_DIR'), 'memory-rejected-actions.png'),
    fullPage: true
  })
  await writeFile(
    join(fixture('E2E_ARTIFACT_DIR'), 'memory-review-rejected.json'),
    JSON.stringify(
      {
        mode,
        factId: reviewId,
        reviewCommand,
        response: rejectedEnvelope,
        terminalActions
      },
      null,
      2
    )
  )
})
