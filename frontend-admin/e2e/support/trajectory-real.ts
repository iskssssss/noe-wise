import { test, expect, type Page, type Response } from '@playwright/test'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import type {
  ObservationNode,
  ObservationPage,
  TrajectoryContent,
  TrajectoryDetail,
  TrajectoryListPage
} from '@skillforge/shared/observation/types'

interface LiveTrajectoryFixture {
  institutionBackendURL: string
  platformBackendURL: string
  tenantId: string
  tenantSlug: string
  sessionId: string
  rootInputId: string
  runId: string
  traceId: string | null
  tenantUsername: string
  platformUsername: string
  toolName: string
  expectedToolResultFragment: string
}

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`Missing isolated trajectory browser setting: ${name}`)
  return value
}

function responseAt(page: Page, path: string, query?: [string, string]) {
  return page.waitForResponse((response) => {
    const url = new URL(response.url())
    return (
      response.request().method() === 'GET' &&
      url.pathname === path &&
      (!query || url.searchParams.get(query[0]) === query[1])
    )
  })
}

/** 不把正文、凭据、快照或 content_ref 附加到断言与证据中。 */
async function dataOf<T>(response: Response): Promise<T> {
  expect(response.ok(), `${response.status()} ${new URL(response.url()).pathname}`).toBe(true)
  const envelope = await response.json()
  expect(envelope.code).toBe('OK')
  return envelope.data as T
}

function nodeRow(page: Page, node: ObservationNode) {
  return page.locator(`[data-node-id=${JSON.stringify(node.node_id)}]`)
}

async function selectLlm(page: Page, prefix: string, node: ObservationNode) {
  await page.getByPlaceholder('工具名、可见摘要或错误信息').fill(node.name)
  const ready = responseAt(page, `${prefix}/nodes`, ['query', node.name])
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await dataOf<ObservationPage>(await ready)
  await nodeRow(page, node).click()
  await expect(page.getByRole('button', { name: '查看原始输入', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '查看原始输出', exact: true })).toHaveCount(0)
}

/** 真实登录后仅消费已完成模型轨迹；全程没有 API route 拦截或业务写入。 */
export function trajectoryRealBrowserCase(kind: 'admin' | 'platform') {
  test(`${kind} 真实模型轨迹：登录、检索、执行树、受控结果与刷新`, async ({ page }) => {
    const fixture = JSON.parse(
      await readFile(required('E2E_TRAJECTORY_FIXTURE'), 'utf8')
    ) as LiveTrajectoryFixture
    const prefix = `/api/v1/${kind === 'admin' ? 'chat' : 'platform'}/trajectories`
    const loginPath = `/api/v1/${kind === 'admin' ? 'auth' : 'platform/auth'}/login`
    const errors: string[] = []
    const unexpectedWrites: string[] = []
    const reads: Record<string, number> = {}
    let privateDiagnosticRead = false
    page.on('pageerror', (error) => errors.push(error.name))
    page.on('request', (request) => {
      const path = new URL(request.url()).pathname
      if (!path.startsWith('/api/')) return
      if (request.method() === 'GET') reads[path] = (reads[path] ?? 0) + 1
      else if (!(path === loginPath && request.method() === 'POST'))
        unexpectedWrites.push(`${request.method()} ${path}`)
    })

    await page.goto(`/login?redirect=/${kind}/trajectories`)
    if (kind === 'admin')
      await page.locator('input[autocomplete="organization"]').fill(fixture.tenantSlug)
    await page
      .locator('input[autocomplete="username"]')
      .fill(kind === 'admin' ? fixture.tenantUsername : fixture.platformUsername)
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    const authenticated = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === loginPath && response.request().method() === 'POST'
    )
    await page
      .getByRole('button', { name: kind === 'admin' ? '登录' : '登录运营台', exact: true })
      .click()
    await dataOf(await authenticated)
    await expect(page.getByRole('heading', { name: 'Agent 轨迹', exact: true })).toBeVisible()

    await page.locator('summary').filter({ hasText: '更多筛选' }).click()
    await page.getByRole('textbox', { name: 'SessionID', exact: true }).fill(fixture.sessionId)
    if (kind === 'platform')
      await page.getByRole('textbox', { name: '机构 ID', exact: true }).fill(fixture.tenantId)
    const listReady = responseAt(page, prefix, ['session_id', fixture.sessionId])
    await page.getByRole('button', { name: '查询', exact: true }).click()
    const list = await dataOf<TrajectoryListPage>(await listReady)
    expect(list.items.some((item) => item.root_input_id === fixture.rootInputId)).toBe(true)
    const detailReady = responseAt(page, `${prefix}/detail`)
    const nodesReady = responseAt(page, `${prefix}/nodes`)
    await page
      .locator('.el-table__body tbody tr')
      .filter({ hasText: fixture.rootInputId })
      .getByRole('link', { name: '查看轨迹', exact: true })
      .click()
    const detail = await dataOf<TrajectoryDetail>(await detailReady)
    const nodes = await dataOf<ObservationPage>(await nodesReady)
    expect(detail.summary.session_id).toBe(fixture.sessionId)
    expect(detail.summary.root_input_id).toBe(fixture.rootInputId)
    expect(detail.summary.model_calls).toBeGreaterThan(0)
    expect(detail.summary.tool_calls).toBeGreaterThan(0)
    expect(detail.summary.totals?.prompt_tokens).toMatch(/^\d+$/)
    expect(nodes.snapshot === detail.snapshot).toBe(true)
    await expect(page.getByRole('region', { name: '执行调用树', exact: true })).toBeVisible()
    await expect(page.getByRole('region', { name: '任务输入与结果', exact: true })).toBeVisible()
    await page.screenshot({
      path: test.info().outputPath('real-execution-tree.png'),
      fullPage: true
    })

    const search = page.getByPlaceholder('工具名、可见摘要或错误信息')
    await search.fill(fixture.toolName)
    const toolReady = responseAt(page, `${prefix}/nodes`, ['query', fixture.toolName])
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    const toolPage = await dataOf<ObservationPage>(await toolReady)
    const tool = toolPage.items.find(
      (node) => node.kind === 'TOOL' && node.name.includes(fixture.toolName)
    )
    expect(Boolean(tool)).toBe(true)
    await nodeRow(page, tool!).click()
    const contentReady = responseAt(page, `${prefix}/content`)
    await page.getByRole('button', { name: '查看原始输出', exact: true }).click()
    const content = await dataOf<TrajectoryContent>(await contentReady)
    expect(['FULL', 'AVAILABLE', 'PARTIAL'].includes(content.availability)).toBe(true)
    expect(JSON.stringify(content.value).includes(fixture.expectedToolResultFragment)).toBe(true)
    const contentReader = page.getByRole('dialog', { name: '节点原始内容', exact: true })
    await expect(
      contentReader.getByRole('region', { name: '节点原始内容', exact: true })
    ).toBeVisible()
    await contentReader.getByRole('button', { name: /关闭/ }).click()

    const auxiliary = nodes.items.find(
      (node) =>
        node.kind === 'LLM' &&
        node.attempts.length > 0 &&
        node.attempts.every((attempt) => !attempt.diagnostic_available)
    )
    expect(Boolean(auxiliary)).toBe(true)
    await selectLlm(page, prefix, auxiliary!)
    await page.locator('.attempts details > summary').first().click()
    await expect(page.locator('.attempts')).toContainText('输入 / 输出 Token')
    await expect(page.locator('.attempts details > summary .el-tag').first()).toBeVisible()
    expect(auxiliary!.attempts[0]!.totals?.prompt_tokens).toMatch(/^\d+$/)
    await expect(page.getByRole('button', { name: '调用诊断', exact: true })).toHaveCount(0)

    const llm = nodes.items.find(
      (node) => node.kind === 'LLM' && node.attempts.some((attempt) => attempt.diagnostic_available)
    )
    expect(Boolean(llm)).toBe(true)
    await selectLlm(page, prefix, llm!)
    const diagnosticIndex = llm!.attempts.findIndex((attempt) => attempt.diagnostic_available)
    await page.locator('.attempts details > summary').nth(diagnosticIndex).click()
    if (kind === 'admin')
      await expect(page.getByRole('button', { name: '调用诊断', exact: true })).toHaveCount(0)
    else
      await expect(
        page.getByRole('button', { name: '调用诊断', exact: true }).first()
      ).toBeVisible()
    if (kind === 'platform' && process.env.E2E_TRAJECTORY_PRIVATE_DIAGNOSTICS === '1') {
      const privateReady = responseAt(page, '/api/v1/platform/sessions/model-attempt/detail')
      await page.getByRole('button', { name: '调用诊断', exact: true }).first().click()
      const diagnostic = await dataOf<{ session_id: string; attempt_id: string }>(
        await privateReady
      )
      expect(diagnostic.session_id).toBe(llm!.source_session_id)
      expect(diagnostic.attempt_id).toBe(llm!.attempts[diagnosticIndex]!.attempt_id)
      privateDiagnosticRead = true
      await page
        .getByRole('dialog', { name: '模型调用详情', exact: true })
        .getByRole('button', { name: /关闭/ })
        .click()
    }

    await page.getByRole('button', { name: '查看轨迹 JSON', exact: true }).click()
    const json = page.getByRole('dialog', { name: '轨迹 JSON', exact: true })
    await expect(json.locator('.cm-content')).toContainText(fixture.rootInputId)
    await json.getByRole('button', { name: /关闭/ }).click()
    const refreshed = responseAt(page, `${prefix}/detail`)
    await page.getByRole('button', { name: '刷新', exact: true }).first().click()
    const refreshedDetail = await dataOf<TrajectoryDetail>(await refreshed)
    expect(refreshedDetail.summary.root_input_id).toBe(fixture.rootInputId)
    await expect(page.getByRole('region', { name: '执行调用树', exact: true })).toBeVisible()
    await page.setViewportSize({ width: 720, height: 960 })
    await page.emulateMedia({ colorScheme: 'dark' })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true
    )
    expect(unexpectedWrites).toEqual([])
    expect(errors).toEqual([])

    const evidence = required('E2E_ARTIFACT_DIR')
    await mkdir(evidence, { recursive: true })
    await writeFile(
      join(evidence, 'browser-receipt.json'),
      JSON.stringify(
        {
          state: 'PASSED',
          kind,
          tenantId: fixture.tenantId,
          sessionId: fixture.sessionId,
          rootInputId: fixture.rootInputId,
          runId: fixture.runId,
          modelCalls: detail.summary.model_calls,
          toolCalls: detail.summary.tool_calls,
          nodeKinds: [...new Set(nodes.items.map((node) => node.kind))],
          contentAvailability: content.availability,
          institutionHasNoPrivateDiagnosticEntry: kind === 'admin',
          privateDiagnosticRead,
          auxiliaryLlmHasUsageWithoutDiagnosticEntry: true,
          authentication: 'real-login-form',
          apiRouting: 'real-vite-proxy',
          newBusinessWrites: unexpectedWrites.length,
          reads,
          errors
        },
        null,
        2
      )
    )
  })
}
