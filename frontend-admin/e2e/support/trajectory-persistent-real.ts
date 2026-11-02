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
import type { SessionRoundsResult } from '../../../frontend-platform/src/api/sessionObservation'
import type { PersonalPreference } from '@skillforge/shared/foundation/catalog/types'

type ScenarioKind = 'MULTI_ROUND' | 'DELEGATION' | 'TOOL_RECOVERY' | 'LONG_TOOL_NAME'

interface RootIdentity {
  rootInputId: string
  runId: string
}

interface PersistentCase {
  id: string
  kind: ScenarioKind
  sessionId: string
  roots: RootIdentity[]
  recoveryMode?: 'USER_NEXT_INPUT'
  toolName?: string
  expectedChildAgentId?: string
  expectedChildSessionId?: string
}

interface PersistentFixture {
  version: 1
  institutionBackendURL: string
  platformBackendURL: string
  tenantId: string
  tenantSlug: string
  tenantUsername: string
  platformUsername: string
  cases: PersistentCase[]
}

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`Missing persistent trajectory browser setting: ${name}`)
  return value
}

function responseAt(page: Page, path: string, query: Record<string, string> = {}) {
  return page.waitForResponse((response) => {
    const url = new URL(response.url())
    return (
      response.request().method() === 'GET' &&
      url.pathname === path &&
      Object.entries(query).every(([key, value]) => url.searchParams.get(key) === value)
    )
  })
}

/** 断言只保留路径、状态与公共code，不附加请求、响应或原始正文。 */
async function dataOf<T>(response: Response): Promise<T> {
  expect(response.ok(), `${response.status()} ${new URL(response.url()).pathname}`).toBe(true)
  const envelope = await response.json()
  expect(envelope.code).toBe('OK')
  return envelope.data as T
}

async function login(page: Page, fixture: PersistentFixture, kind: 'admin' | 'platform') {
  const loginPath = `/api/v1/${kind === 'admin' ? 'auth' : 'platform/auth'}/login`
  await page.goto(`/login?redirect=/${kind}/trajectories`)
  if (kind === 'admin')
    await page.locator('input[autocomplete="organization"]').fill(fixture.tenantSlug)
  await page
    .locator('input[autocomplete="username"]')
    .fill(kind === 'admin' ? fixture.tenantUsername : fixture.platformUsername)
  let authenticated: Response
  try {
    const password =
      process.env[kind === 'admin' ? 'E2E_INSTITUTION_PASSWORD' : 'E2E_PLATFORM_PASSWORD'] ??
      required('E2E_PASSWORD')
    await page.locator('input[autocomplete="current-password"]').fill(password)
    const ready = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === loginPath && response.request().method() === 'POST'
    )
    await page
      .getByRole('button', {
        name: kind === 'admin' ? '登录' : '登录运营台',
        exact: true
      })
      .click()
    authenticated = await ready
  } finally {
    // 成功或失败都清掉DOM与表单值，避免失败error-context保留密码输入。
    await page.locator('input[autocomplete="current-password"]').evaluateAll((inputs) => {
      for (const element of inputs) {
        const input = element as HTMLInputElement
        input.value = ''
        input.dispatchEvent(new Event('input', { bubbles: true }))
      }
    })
  }
  await dataOf(authenticated)
  await expect(page.getByRole('heading', { name: 'Agent 轨迹', exact: true })).toBeVisible()
}

async function allNodes(page: Page, prefix: string, initial: ObservationPage) {
  const result = new Map<string, ObservationNode>()
  let current = initial
  for (let batch = 0; batch < 25; batch++) {
    for (const node of [...current.ancestors, ...current.items]) result.set(node.node_id, node)
    if (!current.next_cursor) return result
    const ready = responseAt(page, `${prefix}/nodes`, { cursor: current.next_cursor })
    await page.getByRole('button', { name: '加载更多节点', exact: true }).click()
    const next = await dataOf<ObservationPage>(await ready)
    expect(next.snapshot === initial.snapshot).toBe(true)
    current = next
  }
  throw new Error('Persistent trajectory node pagination exceeded the bounded browser fixture')
}

async function selectNode(page: Page, prefix: string, node: ObservationNode) {
  await page.getByPlaceholder('工具名、可见摘要或错误信息').fill(node.name)
  const ready = responseAt(page, `${prefix}/nodes`, { query: node.name })
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  const result = await dataOf<ObservationPage>(await ready)
  const fresh = [...result.items, ...result.ancestors].find((item) => item.node_id === node.node_id)
  expect(Boolean(fresh)).toBe(true)
  expect(fresh!.source_session_id).toBe(node.source_session_id)
  expect(fresh!.root_input_id).toBe(node.root_input_id)
  expect(fresh!.run_id).toBe(node.run_id)
  expect(fresh!.kind).toBe(node.kind)
  expect(fresh!.name).toBe(node.name)
  expect(fresh!.status).toBe(node.status)
  await page.locator(`[data-node-id=${JSON.stringify(node.node_id)}]`).click()
  await expect(
    page.getByRole('region', { name: '当前执行节点详情', exact: true }).getByRole('heading', {
      name: node.name,
      exact: true
    })
  ).toBeVisible()
  return fresh!
}

/** 手工截图遮住用户/模型正文，只保留分区、调用树、名称和状态布局。 */
async function capture(page: Page, filename: string) {
  await page.screenshot({
    path: test.info().outputPath(filename),
    fullPage: true,
    mask: [
      page.locator('.question-answer .readable'),
      // 机构端这里是用户问题摘要；平台端是需要视觉核验的公开轮次编号。
      ...(new URL(page.url()).pathname.startsWith('/admin/')
        ? [page.locator('.round-summary > span')]
        : []),
      page.locator('.node-detail .readable'),
      page.locator('.recorded-content')
    ]
  })
}

async function rootData(
  page: Page,
  prefix: string,
  root: RootIdentity,
  action: () => Promise<unknown>
) {
  const detailReady = responseAt(page, `${prefix}/detail`, { root_input_id: root.rootInputId })
  const nodesReady = responseAt(page, `${prefix}/nodes`, { root_input_id: root.rootInputId })
  await action()
  const detail = await dataOf<TrajectoryDetail>(await detailReady)
  const initial = await dataOf<ObservationPage>(await nodesReady)
  expect(detail.summary.root_input_id).toBe(root.rootInputId)
  expect(detail.summary.model_calls).toBeGreaterThan(0)
  expect(initial.snapshot === detail.snapshot).toBe(true)
  const nodes = await allNodes(page, prefix, initial)
  expect([...nodes.values()].some((node) => node.run_id === root.runId)).toBe(true)
  return { detail, nodes }
}

export function persistentTrajectoryBrowserCase(kind: 'admin' | 'platform') {
  // 手工截图已遮住正文；离开应用后再由runner生成失败上下文，避免保存页面原文。
  test.afterEach(async ({ page }) => {
    if (!page.isClosed()) await page.goto('about:blank')
  })
  test(`${kind} 持久环境真实轨迹：多轮、委派、工具恢复与长名称`, async ({ page }) => {
    const fixture = JSON.parse(
      await readFile(required('E2E_TRAJECTORY_PERSISTENT_FIXTURE'), 'utf8')
    ) as PersistentFixture
    expect(fixture.version).toBe(1)
    const expectedKinds: ScenarioKind[] = [
      'MULTI_ROUND',
      'DELEGATION',
      'TOOL_RECOVERY',
      'LONG_TOOL_NAME'
    ]
    expect(expectedKinds.every((kind) => fixture.cases.some((item) => item.kind === kind))).toBe(
      true
    )
    const prefix = `/api/v1/${kind === 'admin' ? 'chat' : 'platform'}/trajectories`
    const loginPath = `/api/v1/${kind === 'admin' ? 'auth' : 'platform/auth'}/login`
    const errors: string[] = []
    const writes: string[] = []
    const httpErrors: string[] = []
    const reads: Record<string, number> = {}
    const cases: Array<Record<string, unknown>> = []
    page.on('pageerror', (error) => errors.push(error.name))
    page.on('console', (message) => {
      if (message.type() === 'error') errors.push('console.error')
    })
    page.on('request', (request) => {
      const path = new URL(request.url()).pathname
      if (!path.startsWith('/api/')) return
      if (request.method() === 'GET') reads[path] = (reads[path] ?? 0) + 1
      else if (!(path === loginPath && request.method() === 'POST'))
        writes.push(`${request.method()} ${path}`)
    })
    page.on('response', (response) => {
      const path = new URL(response.url()).pathname
      if (path.startsWith('/api/') && response.status() >= 400)
        httpErrors.push(`${response.status()} ${path}`)
    })
    const preferenceReady = responseAt(
      page,
      `/api/v1/${kind === 'platform' ? 'admin' : 'tenant'}/foundation/catalog/preferences`
    )
    await login(page, fixture, kind)
    const preference = await dataOf<PersonalPreference>(await preferenceReady)
    expect(['light', 'dark', 'system'].includes(preference.theme)).toBe(true)
    const renderedThemes = new Set<string>()

    for (const [index, scenario] of fixture.cases.entries()) {
      expect(scenario.roots.length).toBeGreaterThan(0)
      if (scenario.kind === 'MULTI_ROUND') expect(scenario.roots.length).toBeGreaterThanOrEqual(2)
      await page.setViewportSize({ width: 1440, height: 1000 })
      await page.emulateMedia({ colorScheme: 'light' })
      await page.goto(`/${kind}/trajectories`)
      await page.locator('summary').filter({ hasText: '更多筛选' }).click()
      await page.getByRole('textbox', { name: 'SessionID', exact: true }).fill(scenario.sessionId)
      if (kind === 'platform')
        await page.getByRole('textbox', { name: '机构 ID', exact: true }).fill(fixture.tenantId)
      const listReady = responseAt(page, prefix, { session_id: scenario.sessionId })
      await page.getByRole('button', { name: '查询', exact: true }).click()
      const list = await dataOf<TrajectoryListPage>(await listReady)
      expect(
        scenario.roots.every((root) =>
          list.items.some((item) => item.root_input_id === root.rootInputId)
        )
      ).toBe(true)
      const label = `${index + 1}-${scenario.id.replace(/[^a-zA-Z0-9_-]/g, '-')}`
      const roots: Array<Record<string, unknown>> = []
      let platformRounds: SessionRoundsResult | undefined
      let userRecoveryFailure: { root: RootIdentity; node: ObservationNode } | undefined
      if (scenario.recoveryMode === 'USER_NEXT_INPUT') {
        expect(scenario.kind).toBe('TOOL_RECOVERY')
        expect(scenario.roots.length).toBe(2)
        expect(scenario.roots[0]!.rootInputId !== scenario.roots[1]!.rootInputId).toBe(true)
        expect(scenario.roots[0]!.runId !== scenario.roots[1]!.runId).toBe(true)
      }

      for (const [rootIndex, root] of scenario.roots.entries()) {
        await page.setViewportSize({ width: 1440, height: 1000 })
        await page.emulateMedia({ colorScheme: 'light' })
        const roundsReady =
          kind === 'platform' && rootIndex === 0
            ? responseAt(page, '/api/v1/platform/sessions/rounds', {
                tenant_id: fixture.tenantId,
                session_id: scenario.sessionId
              })
            : undefined
        const { detail, nodes } = await rootData(page, prefix, root, async () => {
          if (rootIndex === 0)
            return page
              .locator('.el-table__body tbody tr')
              .filter({ hasText: root.rootInputId })
              .getByRole('link', { name: '查看轨迹', exact: true })
              .click()
          if (kind === 'platform')
            return page.locator(`[data-round-id=${JSON.stringify(root.rootInputId)}]`).click()
          const position = list.items.findIndex((item) => item.root_input_id === root.rootInputId)
          return page.locator('.round-menu .el-menu-item').nth(position).click()
        })
        if (roundsReady) platformRounds = await dataOf<SessionRoundsResult>(await roundsReady)
        if (platformRounds) {
          for (const [position, round] of platformRounds.items.entries()) {
            expect(round.round_number).toMatch(/^[1-9]\d*$/)
            await expect(
              page.locator(
                `[data-round-id=${JSON.stringify(round.root_input_id)}] .round-summary > span`
              )
            ).toHaveText(`第 ${round.round_number} 轮`)
            const previous = platformRounds.items[position - 1]
            if (previous)
              expect(BigInt(previous.first_sequence) > BigInt(round.first_sequence)).toBe(true)
          }
        }
        expect(detail.summary.session_id).toBe(scenario.sessionId)
        await expect(
          page.getByRole('region', { name: '任务输入与结果', exact: true })
        ).toBeVisible()
        await expect(page.getByRole('region', { name: '执行过程', exact: true })).toBeVisible()
        await expect(
          page.getByRole('complementary', { name: '业务轮次', exact: true })
        ).toBeVisible()
        await capture(page, `${label}-root-${rootIndex + 1}-sections.png`)
        const values = [...nodes.values()]
        const rootReceipt: Record<string, unknown> = {
          rootInputId: root.rootInputId,
          runId: root.runId,
          modelCalls: detail.summary.model_calls,
          toolCalls: detail.summary.tool_calls,
          nodeKinds: [...new Set(values.map((node) => node.kind))],
          nodeCount: nodes.size
        }
        if (platformRounds)
          rootReceipt.roundNumbers = platformRounds.items.map((round) => ({
            rootInputId: round.root_input_id,
            roundNumber: round.round_number
          }))

        if (scenario.kind === 'DELEGATION') {
          expect(Boolean(scenario.expectedChildSessionId && scenario.expectedChildAgentId)).toBe(
            true
          )
          const child = values.find(
            (node) =>
              node.kind === 'AGENT' &&
              node.source_session_id === scenario.expectedChildSessionId &&
              node.name.includes(scenario.expectedChildAgentId!)
          )
          expect(Boolean(child)).toBe(true)
          expect(child!.source_session_id !== scenario.sessionId).toBe(true)
          expect(child!.run_id !== root.runId).toBe(true)
          expect(nodes.get(child!.parent_node_id ?? '')?.kind).toBe('TOOL')
          await selectNode(page, prefix, child!)
          await capture(page, `${label}-delegated-agent.png`)
          rootReceipt.childSessionId = child!.source_session_id
          rootReceipt.childRunId = child!.run_id
        }

        if (scenario.kind === 'TOOL_RECOVERY' || scenario.kind === 'LONG_TOOL_NAME') {
          expect(Boolean(scenario.toolName)).toBe(true)
          const tools = values.filter(
            (node) => node.kind === 'TOOL' && node.name === scenario.toolName
          )
          expect(tools.length).toBeGreaterThan(0)
          const successful = tools.find((node) => node.status === 'SUCCEEDED')
          let selectedTool: ObservationNode
          if (scenario.kind === 'TOOL_RECOVERY' && scenario.recoveryMode === 'USER_NEXT_INPUT') {
            rootReceipt.recoveryRole = rootIndex === 0 ? 'INITIAL_FAILURE' : 'USER_REQUESTED_RETRY'
            if (rootIndex === 0) {
              const failed = tools.find((node) => node.status === 'FAILED')
              expect(Boolean(failed)).toBe(true)
              expect(Boolean(successful)).toBe(false)
              userRecoveryFailure = { root, node: failed! }
              selectedTool = failed!
            } else {
              expect(Boolean(successful && userRecoveryFailure)).toBe(true)
              expect(userRecoveryFailure!.root.rootInputId !== root.rootInputId).toBe(true)
              expect(userRecoveryFailure!.root.runId !== root.runId).toBe(true)
              expect(Boolean(userRecoveryFailure!.node.finished_at && successful!.started_at)).toBe(
                true
              )
              expect(
                Date.parse(userRecoveryFailure!.node.finished_at!) <=
                  Date.parse(successful!.started_at!)
              ).toBe(true)
              selectedTool = successful!
            }
          } else if (scenario.kind === 'TOOL_RECOVERY') {
            expect(Boolean(successful)).toBe(true)
            const failed = tools.filter((node) => ['FAILED', 'REJECTED'].includes(node.status))
            expect(
              failed.some((node) => BigInt(node.sequence) < BigInt(successful!.sequence))
            ).toBe(true)
            await selectNode(page, prefix, failed[0]!)
            await capture(page, `${label}-failed-tool.png`)
            selectedTool = successful!
          } else {
            expect(scenario.toolName!.length).toBeGreaterThanOrEqual(48)
            expect(Boolean(successful)).toBe(true)
            selectedTool = successful!
          }
          rootReceipt.toolStatuses = tools.map((node) => node.status)
          selectedTool = await selectNode(page, prefix, selectedTool)
          await capture(
            page,
            `${label}-root-${rootIndex + 1}-${selectedTool.status.toLowerCase()}-tool-name.png`
          )
          if (selectedTool.status === 'SUCCEEDED') {
            expect(Boolean(selectedTool.output.ref)).toBe(true)
            const ready = responseAt(page, `${prefix}/content`)
            await page.getByRole('button', { name: '查看原始输出', exact: true }).click()
            const content = await dataOf<TrajectoryContent>(await ready)
            expect(content.content_ref === selectedTool.output.ref).toBe(true)
            expect(['FULL', 'AVAILABLE', 'PARTIAL'].includes(content.availability)).toBe(true)
            rootReceipt.contentAvailability = content.availability
            await page
              .getByRole('dialog', { name: '节点原始内容', exact: true })
              .getByRole('button', { name: /关闭/ })
              .click()
          }
          for (const width of [1280, 720]) {
            await page.setViewportSize({ width, height: 1000 })
            await page.emulateMedia({ colorScheme: width === 720 ? 'dark' : 'light' })
            const dark =
              preference.theme === 'dark' || (preference.theme === 'system' && width === 720)
            if (dark) await expect(page.locator('html')).toHaveClass(/dark/)
            else await expect(page.locator('html')).not.toHaveClass(/dark/)
            renderedThemes.add(dark ? 'dark' : 'light')
            expect(
              await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)
            ).toBe(true)
            await expect(
              page
                .getByRole('region', { name: '当前执行节点详情', exact: true })
                .getByRole('heading', { name: scenario.toolName!, exact: true })
            ).toBeVisible()
            await capture(
              page,
              `${label}-root-${rootIndex + 1}-${width}-${dark ? 'dark' : 'light'}.png`
            )
          }
          rootReceipt.toolNameLength = scenario.toolName!.length
        }
        const refreshed = responseAt(page, `${prefix}/detail`, { root_input_id: root.rootInputId })
        await page.getByRole('button', { name: '刷新', exact: true }).first().click()
        const current = await dataOf<TrajectoryDetail>(await refreshed)
        expect(current.summary.root_input_id).toBe(root.rootInputId)
        roots.push(rootReceipt)
      }
      cases.push({
        id: scenario.id,
        kind: scenario.kind,
        sessionId: scenario.sessionId,
        recoveryMode:
          scenario.recoveryMode ?? (scenario.kind === 'TOOL_RECOVERY' ? 'SAME_ROOT' : null),
        roots
      })
    }
    expect(writes).toEqual([])
    expect(httpErrors).toEqual([])
    expect(errors).toEqual([])
    const evidence = required('E2E_ARTIFACT_DIR')
    await mkdir(evidence, { recursive: true })
    await writeFile(
      join(evidence, 'persistent-browser-receipt.json'),
      JSON.stringify(
        {
          state: 'PASSED',
          version: 1,
          kind,
          cases,
          reads,
          newBusinessWrites: writes.length,
          authentication: 'real-login-form',
          apiRouting: 'real-vite-proxy',
          errors,
          httpErrors,
          savedThemePreference: preference.theme,
          renderedThemes: [...renderedThemes]
        },
        null,
        2
      )
    )
  })
}
