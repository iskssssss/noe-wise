import {
  expect,
  test,
  type APIResponse,
  type Locator,
  type Page,
  type Response,
  type TestInfo
} from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { commonZhCN } from '@skillforge/shared/i18n/common.zh-CN'

/** 由隔离 Java 夹具通过生产 MemoryRuntime 创建的事实，不在浏览器拦截治理 API。 */
interface MemoryFact {
  ref: { fact_id: string }
  scope: 'SESSION' | 'USER_AGENT' | 'USER'
  status: string
  revision: number
  predicate: string
  subject: string
  review_required: boolean
  object: { canonical_json: string }
  derived_from?: { fact_id: string }
}

/** 单次验收的资源身份及预期事实，全部来自本次独立容器。 */
interface MemoryFixture {
  runId: string
  tenantId: string
  agentA: string
  agentNameA: string
  userA: string
  userNameA: string
  facts: Record<string, MemoryFact>
}

/** 真实密码登录回执中的临时访问令牌，仅用于本测试的授权 API 请求，不写入证据。 */
interface LoginReceipt {
  access_token: string
  permissions: string[]
}

/** 获取 Java 所有者传入的隔离环境值，缺失时直接拒绝连接。 */
function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`Missing isolated memory fixture: ${name}`)
  return value
}

/** 读取冻结夹具；禁止使用浏览器本地缓存或共享后端来补齐资源。 */
async function fixture(): Promise<MemoryFixture> {
  return JSON.parse(await readFile(required('E2E_MEMORY_FIXTURE'), 'utf8')) as MemoryFixture
}

/** 等待实际方法与公开路径回执，避免把列表 GET 与治理 POST 混淆。 */
function responseAt(page: Page, path: string, method = 'GET') {
  return page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === path && response.request().method() === method
  )
}

/** HTTP 状态与业务信封都必须成功；事实断言由调用方根据冻结预期执行。 */
async function dataOf<T>(response: Response | APIResponse): Promise<T> {
  expect(response.ok(), `HTTP ${response.status()} at ${new URL(response.url()).pathname}`).toBe(
    true
  )
  const envelope = await response.json()
  expect(envelope.code).toBe('OK')
  return envelope.data as T
}

/** 通过正式登录页建立安全会话，等到实际记忆、助手与用户列表完成读取。 */
async function login(page: Page, readOnly = false): Promise<LoginReceipt> {
  await page.goto('/login?redirect=/admin/memory')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page
    .locator('input[autocomplete="username"]')
    .fill(required(readOnly ? 'E2E_READER_USERNAME' : 'E2E_USERNAME'))
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  const loginResponse = responseAt(page, '/api/v1/auth/login', 'POST')
  const listResponse = responseAt(page, '/api/v1/memories')
  const agentsResponse = responseAt(page, '/api/v1/agents')
  const usersResponse = responseAt(page, '/api/v1/chat/end-users')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const tokens = await dataOf<LoginReceipt>(await loginResponse)
  await expect(page).toHaveURL(/\/admin\/memory$/)
  await dataOf<MemoryFact[]>(await listResponse)
  await dataOf(await agentsResponse)
  await dataOf(await usersResponse)
  await expect(page.getByRole('heading', { name: '记忆管理', exact: true })).toBeVisible()
  return tokens
}

/** 使用唯一关系标记定位真实表格行，不依赖行号或数据库排序。 */
function row(page: Page, predicate: string) {
  return page.locator('.el-table__body tr').filter({ hasText: predicate })
}

/** 原生 input 提供描述关联；Element Plus 零尺寸 input 的位置由可视容器代表。 */
async function assertNearbyDescription(
  control: Locator,
  nearby: Locator,
  visibleControl = control
) {
  await expect(control).toBeAttached()
  await expect(visibleControl).toBeVisible()
  expect(
    await control.evaluate(
      (element) =>
        element instanceof HTMLElement && (element.tabIndex >= 0 || element.isContentEditable)
    )
  ).toBe(true)
  const describedBy = await control.getAttribute('aria-describedby')
  expect(describedBy, '控件必须关联真实帮助文字').toBeTruthy()
  const ids = describedBy!.split(/\s+/).filter(Boolean)
  let visibleTip = false
  for (const id of ids) {
    const tip = nearby.locator(`[id="${id}"]`)
    if ((await tip.count()) === 0 || !(await tip.isVisible())) continue
    expect((await tip.innerText()).trim()).not.toBe('')
    const [controlBox, tipBox] = await Promise.all([
      visibleControl.boundingBox(),
      tip.boundingBox()
    ])
    expect(controlBox).not.toBeNull()
    expect(tipBox).not.toBeNull()
    expect(Math.abs(tipBox!.y - controlBox!.y)).toBeLessThan(400)
    visibleTip = true
  }
  expect(visibleTip, 'aria-describedby 至少指向一个附近可见的帮助').toBe(true)
}

/** 点击实际可视开关，状态由正式 input 的 aria-checked 核对，不强制点击零尺寸 input。 */
async function setPendingReviews(page: Page, enabled: boolean) {
  const switchRoot = page.locator('.toolbar .el-switch')
  const input = switchRoot.getByRole('switch')
  await expect(switchRoot).toBeVisible()
  await expect(input).toHaveAttribute('aria-checked', enabled ? 'false' : 'true')
  await switchRoot.click()
  await expect(input).toHaveAttribute('aria-checked', enabled ? 'true' : 'false')
}

/** 记录每个旅程的真实输出；失败时仍由 Playwright 保存截图和 trace。 */
async function saveReceipt(testInfo: TestInfo, receipt: Record<string, unknown>) {
  await writeFile(
    join(required('E2E_ARTIFACT_DIR'), `${testInfo.title.split(' ')[0]}-receipt.json`),
    JSON.stringify({ title: testInfo.title, ...receipt }, null, 2),
    'utf8'
  )
}

test('B01 combined filters and nearby accessible tips', async ({ page }, testInfo) => {
  const data = await fixture()
  await login(page)
  const toolbar = page.locator('.toolbar')
  const selects = toolbar.locator('.el-select')
  await expect(selects).toHaveCount(3)
  for (let index = 0; index < 3; index++) {
    await assertNearbyDescription(selects.nth(index).getByRole('combobox'), toolbar)
  }
  await assertNearbyDescription(toolbar.getByRole('switch'), toolbar, toolbar.locator('.el-switch'))
  await selects.nth(0).locator('.el-select__wrapper').click()
  await expect(selects.nth(0).getByRole('combobox')).toHaveAttribute('aria-expanded', 'true')
  await page.getByRole('option', { name: '用户与智能体', exact: true }).click()
  await selects.nth(1).locator('.el-select__wrapper').click()
  await expect(selects.nth(1).getByRole('combobox')).toHaveAttribute('aria-expanded', 'true')
  await page.getByRole('option', { name: data.agentNameA, exact: true }).click()
  await selects.nth(2).locator('.el-select__wrapper').click()
  await expect(selects.nth(2).getByRole('combobox')).toHaveAttribute('aria-expanded', 'true')
  await page.getByRole('option', { name: data.userNameA, exact: true }).click()
  const response = responseAt(page, '/api/v1/memories')
  await toolbar.getByRole('button', { name: '查询', exact: true }).click()
  const reply = await response
  const params = new URL(reply.url()).searchParams
  expect(params.get('scope')).toBe('USER_AGENT')
  expect(params.get('agent_id')).toBe(data.agentA)
  expect(params.get('end_user_id')).toBe(data.userA)
  const selected = await dataOf<MemoryFact[]>(reply)
  expect(selected.map((fact) => fact.ref.fact_id).sort()).toEqual(
    ['main', 'approve', 'reject', 'conflict'].map((key) => data.facts[key].ref.fact_id).sort()
  )
  for (const excluded of ['session', 'user', 'agentDistractor', 'userDistractor']) {
    await expect(row(page, data.facts[excluded].predicate)).toHaveCount(0)
  }
  await page.screenshot({ path: join(required('E2E_ARTIFACT_DIR'), 'B01-filters.png') })
  await saveReceipt(testInfo, {
    assertions: [
      'scope/agent/user组合条件由真实HTTP提交',
      '返回集合完全匹配预置事实',
      '4个筛选控件附近帮助及aria-describedby'
    ],
    selectedFactIds: selected.map((fact) => fact.ref.fact_id),
    filters: Object.fromEntries(params)
  })
})

test('B02 pending review approve and reject persist', async ({ page }, testInfo) => {
  const data = await fixture()
  await login(page)
  await setPendingReviews(page, true)
  const reviewsResponse = responseAt(page, '/api/v1/memories/reviews')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const pending = await dataOf<MemoryFact[]>(await reviewsResponse)
  expect(pending.map((fact) => fact.ref.fact_id).sort()).toEqual(
    [data.facts.approve.ref.fact_id, data.facts.reject.ref.fact_id].sort()
  )
  const approvedResponse = responseAt(page, '/api/v1/memories/review', 'POST')
  await row(page, data.facts.approve.predicate)
    .getByRole('button', { name: '批准', exact: true })
    .click()
  const approved = await dataOf<MemoryFact>(await approvedResponse)
  expect(approved.ref.fact_id).toBe(data.facts.approve.ref.fact_id)
  expect(approved.status).toBe('ACTIVE')
  await expect(row(page, data.facts.approve.predicate)).toHaveCount(0)
  const rejectedResponse = responseAt(page, '/api/v1/memories/review', 'POST')
  await row(page, data.facts.reject.predicate)
    .getByRole('button', { name: '拒绝', exact: true })
    .click()
  const rejected = await dataOf<MemoryFact>(await rejectedResponse)
  expect(rejected.ref.fact_id).toBe(data.facts.reject.ref.fact_id)
  expect(rejected.status).toBe('REJECTED')
  await expect(page.getByText('暂无记录', { exact: true })).toBeVisible()
  await setPendingReviews(page, false)
  const listResponse = responseAt(page, '/api/v1/memories')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await dataOf<MemoryFact[]>(await listResponse)
  await expect(row(page, data.facts.approve.predicate)).toContainText('启用')
  await expect(row(page, data.facts.reject.predicate)).toContainText('已拒绝')
  await page.screenshot({ path: join(required('E2E_ARTIFACT_DIR'), 'B02-reviewed.png') })
  await saveReceipt(testInfo, {
    pendingFactIds: pending.map((fact) => fact.ref.fact_id),
    approved,
    rejected
  })
})

test('B03 correction validates JSON and forget restore survives reload', async ({
  page
}, testInfo) => {
  const data = await fixture()
  await login(page)
  let corrections = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/memories/correct'
    )
      corrections++
  })
  await row(page, data.facts.main.predicate)
    .getByRole('button', { name: '纠正', exact: true })
    .click()
  const drawer = page.locator('.el-drawer')
  await expect(drawer).toBeVisible()
  const fields = drawer.locator('.el-form-item')
  await expect(fields).toHaveCount(5)
  await assertNearbyDescription(fields.nth(0).locator('input'), fields.nth(0))
  await assertNearbyDescription(fields.nth(1).locator('input'), fields.nth(1))
  await assertNearbyDescription(fields.nth(2).locator('.cm-content'), fields.nth(2))
  await assertNearbyDescription(fields.nth(3).getByRole('spinbutton'), fields.nth(3))
  await assertNearbyDescription(fields.nth(4).getByRole('spinbutton'), fields.nth(4))
  const predicate = `corrected-${data.runId}`
  const editor = drawer.locator('.cm-content')
  await fields.nth(1).locator('input').fill(predicate)
  await editor.fill('{invalid-json')
  await drawer.getByRole('button', { name: '保存纠正', exact: true }).click()
  await expect(drawer.getByText('请输入有效的 JSON 事实内容', { exact: true })).toBeVisible()
  await expect(fields.nth(1).locator('input')).toHaveValue(predicate)
  await expect(editor).toHaveText('{invalid-json')
  expect(corrections).toBe(0)
  const objectJson = JSON.stringify({ value: predicate })
  await editor.fill(objectJson)
  const correctedResponse = responseAt(page, '/api/v1/memories/correct', 'POST')
  await drawer.getByRole('button', { name: '保存纠正', exact: true }).click()
  const corrected = await dataOf<MemoryFact>(await correctedResponse)
  expect(corrected.ref.fact_id).not.toBe(data.facts.main.ref.fact_id)
  expect(corrected.derived_from?.fact_id).toBe(data.facts.main.ref.fact_id)
  expect(corrected.object.canonical_json).toBe(objectJson)
  await expect(drawer).not.toBeVisible()
  const correctedRow = row(page, predicate)
  await expect(correctedRow).toHaveCount(1)
  await correctedRow.getByRole('button', { name: '遗忘', exact: true }).click()
  const forgottenResponse = responseAt(page, '/api/v1/memories/forget', 'POST')
  await page
    .getByRole('dialog', { name: '遗忘记忆', exact: true })
    .getByRole('button', { name: '确认', exact: true })
    .click()
  const forgotten = await dataOf<MemoryFact>(await forgottenResponse)
  expect(forgotten.status).toBe('FORGOTTEN')
  await expect(correctedRow).toContainText('已遗忘')
  await page.reload()
  await expect(correctedRow.getByRole('button', { name: '恢复', exact: true })).toBeVisible()
  await correctedRow.getByRole('button', { name: '恢复', exact: true }).click()
  const restoredResponse = responseAt(page, '/api/v1/memories/restore', 'POST')
  await page
    .getByRole('dialog', { name: '恢复记忆', exact: true })
    .getByRole('button', { name: '确认', exact: true })
    .click()
  const restored = await dataOf<MemoryFact>(await restoredResponse)
  expect(restored.status).toBe('ACTIVE')
  expect(restored.revision).toBe(forgotten.revision + 1)
  await expect(correctedRow).toContainText('启用')
  await page.reload()
  await expect(correctedRow).toContainText(predicate)
  await expect(correctedRow).toContainText('启用')
  expect(corrections).toBe(1)
  await page.screenshot({ path: join(required('E2E_ARTIFACT_DIR'), 'B03-restored.png') })
  await saveReceipt(testInfo, {
    corrected,
    forgotten,
    restored,
    corrections,
    invalidJsonBlockedBeforeHttp: true,
    drawerTips: 5
  })
})

test('B04 real revision conflict preserves correction draft', async ({ page }, testInfo) => {
  const data = await fixture()
  const tokens = await login(page)
  await row(page, data.facts.conflict.predicate)
    .getByRole('button', { name: '纠正', exact: true })
    .click()
  const drawer = page.locator('.el-drawer')
  await expect(drawer).toBeVisible()
  const draftPredicate = `draft-conflict-${data.runId}`
  const objectJson = JSON.stringify({ value: draftPredicate })
  await drawer.locator('.el-form-item').nth(1).locator('input').fill(draftPredicate)
  await drawer.locator('.cm-content').fill(objectJson)
  // 另一个真实 HTTP 写入在抽屉加载后推进 revision，固定制造竞争而非依赖延时。
  const changed = await dataOf<MemoryFact>(
    await page.request.post('/api/v1/memories/forget', {
      headers: {
        Authorization: `Bearer ${tokens.access_token}`,
        'Idempotency-Key': `browser-conflict-${data.runId}`
      },
      data: {
        fact_id: data.facts.conflict.ref.fact_id,
        payload: { expected_revision: data.facts.conflict.revision }
      }
    })
  )
  expect(changed.revision).toBe(data.facts.conflict.revision + 1)
  const conflictResponse = responseAt(page, '/api/v1/memories/correct', 'POST')
  await drawer.getByRole('button', { name: '保存纠正', exact: true }).click()
  const rejected = await conflictResponse
  expect(rejected.ok(), '修订竞争必须拒绝纠正请求').toBe(false)
  const error = await rejected.json()
  expect(typeof error.message).toBe('string')
  // 正式HTTP消费层按状态分类给出可读提示；原始原因只保存在技术证据，不作为用户文案。
  const visibleErrorMessage =
    rejected.status() === 422 ? commonZhCN.http.validationFailed : commonZhCN.http.conflict
  await expect(drawer.getByRole('alert')).toContainText(visibleErrorMessage)
  await expect(drawer.locator('.el-form-item').nth(1).locator('input')).toHaveValue(draftPredicate)
  await expect(drawer.locator('.cm-content')).toHaveText(objectJson)
  await expect(drawer.getByRole('button', { name: '保存纠正', exact: true })).toBeEnabled()
  const current = await dataOf<MemoryFact[]>(
    await page.request.get('/api/v1/memories', {
      headers: { Authorization: `Bearer ${tokens.access_token}` }
    })
  )
  const unchanged = current.find((fact) => fact.ref.fact_id === changed.ref.fact_id)!
  expect(unchanged.status).toBe('FORGOTTEN')
  expect(unchanged.revision).toBe(changed.revision)
  expect(unchanged.object.canonical_json).toBe(changed.object.canonical_json)
  expect(current.some((fact) => fact.predicate === draftPredicate)).toBe(false)
  await page.screenshot({ path: join(required('E2E_ARTIFACT_DIR'), 'B04-conflict-draft.png') })
  await saveReceipt(testInfo, {
    changed,
    rejectedStatus: rejected.status(),
    error,
    visibleErrorMessage,
    draftPredicate,
    objectJson,
    draftPreserved: true,
    rejectedWritePreservedCurrentFact: true,
    expectedConflictStatus: 409,
    conflictStatusMatchesContract: rejected.status() === 409,
    conflictContract:
      '.agents/skills/skillforge-engineering/references/engineering-standards.md:183'
  })
  // 准备阶段的 stale revision 当前被归为422；安全断言和回执先完成，最后保留严格规范失败。
  expect(rejected.status(), 'CAS修订冲突按工程规范必须返回409；422为生产错误分类缺陷').toBe(409)
})

test('B05 restricted role hides writes and every governance API denies', async ({
  page
}, testInfo) => {
  const data = await fixture()
  const tokens = await login(page, true)
  expect(tokens.permissions).toContain('foundation.memory.read')
  expect(tokens.permissions).not.toContain('foundation.memory.update')
  for (const name of ['批准', '拒绝', '遗忘', '恢复']) {
    await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
  }
  await row(page, data.facts.session.predicate)
    .getByRole('button', { name: '纠正', exact: true })
    .click()
  const drawer = page.locator('.el-drawer')
  await expect(drawer).toBeVisible()
  await expect(drawer.getByRole('button', { name: '保存纠正', exact: true })).toHaveCount(0)
  const denied: { path: string; status: number; code: string }[] = []
  const fact = data.facts.session
  for (const path of ['review', 'correct', 'forget', 'restore']) {
    const payload =
      path === 'review'
        ? { expected_revision: fact.revision, approved: true }
        : path === 'correct'
          ? {
              expected_revision: fact.revision,
              object_json: '{"value":"forbidden"}',
              confidence: 0.9,
              importance: 0.5
            }
          : { expected_revision: fact.revision }
    const response = await page.request.post(`/api/v1/memories/${path}`, {
      headers: {
        Authorization: `Bearer ${tokens.access_token}`,
        'Idempotency-Key': `reader-${path}-${data.runId}`
      },
      data: { fact_id: fact.ref.fact_id, payload }
    })
    expect(response.status(), `受限角色不得通过 ${path} 写入`).toBe(403)
    const error = await response.json()
    denied.push({ path, status: response.status(), code: error.code })
  }
  const after = await dataOf<MemoryFact[]>(
    await page.request.get('/api/v1/memories', {
      headers: { Authorization: `Bearer ${tokens.access_token}` }
    })
  )
  const unchanged = after.find((value) => value.ref.fact_id === fact.ref.fact_id)!
  expect(unchanged.revision).toBe(fact.revision)
  expect(unchanged.object.canonical_json).toBe(fact.object.canonical_json)
  await page.screenshot({ path: join(required('E2E_ARTIFACT_DIR'), 'B05-read-only.png') })
  await saveReceipt(testInfo, {
    permissions: tokens.permissions,
    denied,
    unchangedFactId: unchanged.ref.fact_id,
    unchangedRevision: unchanged.revision
  })
})
