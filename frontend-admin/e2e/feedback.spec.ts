import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { mockConsole } from './support/mock-console'

const feedbackModule =
  '/@fs/' +
  fileURLToPath(new URL('../../frontend-shared/src/feedback/index.ts', import.meta.url)).replaceAll(
    '\\',
    '/'
  )
const toasts = (page: Page) => page.locator('.el-message:visible')
const diagnostics = '请求跟踪：feedback-browser-proof\n' + '仅用于排查的长诊断信息 '.repeat(90)

async function evidence(page: Page, info: TestInfo, name: string) {
  await settleAnimations(page)
  const path = info.outputPath(`${name}.png`)
  await page.screenshot({ path, fullPage: true })
  await info.attach(name, { path, contentType: 'image/png' })
}

async function settleAnimations(page: Page) {
  await page.evaluate(() =>
    Promise.all(
      document
        .getAnimations()
        .filter((animation) => Number.isFinite(animation.effect?.getComputedTiming().endTime))
        .map((animation) => animation.finished.catch(() => undefined))
    )
  )
}

async function settleFeedback(page: Page) {
  // HTTP fallback延后一个任务；跨过两个浏览器任务后检查最终归属，避免只看到短暂空窗。
  await page.evaluate(
    () => new Promise<void>((resolve) => setTimeout(() => setTimeout(resolve, 0), 0))
  )
}

async function openAgents(page: Page) {
  const mock = await mockConsole(page, 'admin')
  await page.goto('/admin/agents')
  await expect(page.getByRole('button', { name: '新建智能体', exact: true })).toBeVisible()
  return mock
}

async function showDiagnosticMessage(page: Page) {
  await page.evaluate(
    async ({ module, details }) => {
      const { message } = await import(/* @vite-ignore */ module)
      message.error('操作未完成，请稍后重试', { details, duration: 0 })
    },
    { module: feedbackModule, details: diagnostics }
  )
}

test('Workflow409仅一条warning保留本地内容，查看差异及导出不重发或覆盖', async ({ page }, info) => {
  const mock = await mockConsole(page, 'admin')
  const definition = (message: string) => ({
    version: '1.0',
    entry: 'start',
    nodes: [
      { id: 'start', type: 'start', name: '开始' },
      { id: 'reply', type: 'output', name: '业务回复', message },
      { id: 'end', type: 'end', name: '结束' }
    ],
    edges: [
      { from: 'start', to: 'reply' },
      { from: 'reply', to: 'end' }
    ],
    meta: {
      name: 'feedback-browser',
      display_name: '反馈验收工作流',
      version: '1.0.0',
      description: '错误归属验收'
    }
  })
  let revision = 7
  mock.setHook(async (request, url) => {
    const path = url.pathname.slice('/api/v1'.length)
    if (path === '/builder/workflows/draft' && request.method() === 'GET')
      return {
        data: {
          workflow_id: 'feedback-browser',
          status: 'draft',
          revision,
          active_definition_version_id: null,
          definition: definition(revision === 7 ? '原始内容' : '服务端最新内容'),
          problems: []
        }
      }
    if (path === '/builder/workflows/draft' && request.method() === 'PUT') {
      expect(request.headers()['if-match']).toBe('7')
      revision = 8
      return { status: 409, code: 'WORKFLOW_REVISION_CONFLICT' }
    }
    if (path === '/builder/workflows/draft/snapshot')
      return {
        data: {
          workflow_id: 'feedback-browser',
          draft_revision: revision,
          layout: { start: { x: 40, y: 30 }, reply: { x: 40, y: 190 }, end: { x: 40, y: 350 } },
          viewport: { zoom: 1, panX: 24, panY: 24 }
        }
      }
    return undefined
  })
  await page.goto('/admin/workflows/feedback-browser')
  await page.getByRole('combobox', { name: '定位节点', exact: true }).click()
  await page.getByRole('option', { name: '业务回复 · reply', exact: true }).click()
  await page.getByRole('textbox', { name: '输出消息', exact: true }).fill('本地保留的业务内容')
  const save = page.getByRole('button', { name: '保存草稿', exact: true })
  await save.click()
  const warning = page
    .locator('.el-alert--warning')
    .filter({ hasText: '工作流状态已变化，本地内容已保留' })
  await expect(warning).toHaveCount(1)
  await expect(warning).toBeVisible()
  await expect(warning.getByRole('button', { name: '查看最新差异', exact: true })).toBeVisible()
  await expect(warning.getByRole('button', { name: '导出当前内容', exact: true })).toBeVisible()
  await settleFeedback(page)
  await expect(toasts(page)).toHaveCount(0)
  await expect(page.locator('.el-alert--error')).toHaveCount(0)
  await expect(save).toBeDisabled()
  await save.scrollIntoViewIfNeeded()
  const saveBox = await save.boundingBox()
  expect(saveBox).not.toBeNull()
  expect(saveBox!.x).toBeGreaterThanOrEqual(0)
  expect(saveBox!.x + saveBox!.width).toBeLessThanOrEqual(page.viewportSize()!.width)
  await evidence(page, info, 'workflow-conflict-single-inline')
  const download = page.waitForEvent('download')
  await warning.getByRole('button', { name: '导出当前内容', exact: true }).click()
  const file = await download
  const path = info.outputPath('preserved-local-workflow.json')
  await file.saveAs(path)
  const exported = JSON.parse(await readFile(path, 'utf8'))
  expect(exported.nodes.find((node: { id: string }) => node.id === 'reply').message).toBe(
    '本地保留的业务内容'
  )
  await warning.getByRole('button', { name: '查看最新差异', exact: true }).click()
  await expect(page.getByRole('tabpanel', { name: /^校验与变更/ })).toContainText(
    '本地保留的业务内容'
  )
  await expect(toasts(page)).toHaveCount(0)
  expect(mock.requests.filter((request) => request.method !== 'GET')).toHaveLength(1)
  mock.assertHandled()
})

test('GET失败由页面inline承接，重复刷新和恢复都没有额外全局toast', async ({ page }, info) => {
  const mock = await mockConsole(page, 'admin')
  let failed = true
  mock.setHook(async (_request, url) =>
    url.pathname === '/api/v1/agents' && failed
      ? { status: 500, code: 'AGENTS_READ_FAILED' }
      : undefined
  )
  await page.goto('/admin/agents')
  await expect(page.locator('.el-alert--error')).toHaveCount(1)
  await settleFeedback(page)
  await expect(toasts(page)).toHaveCount(0)
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect
    .poll(() => mock.requests.filter((request) => request.path === '/agents').length)
    .toBe(2)
  await expect(page.locator('.el-alert--error')).toHaveCount(1)
  await settleFeedback(page)
  await expect(toasts(page)).toHaveCount(0)
  await evidence(page, info, 'get-failure-single-inline')
  failed = false
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.locator('.el-alert--error')).toHaveCount(0)
  await expect(page.getByText(mock.agents[0]!.name, { exact: true })).toBeVisible()
  mock.assertHandled()
})

test('真实HTTP客户端未claim的失败仍有唯一全局兜底与独立排查信息', async ({ page }, info) => {
  const mock = await openAgents(page)
  mock.setHook(async (_request, url) =>
    url.pathname === '/api/v1/agents' ? { status: 500, code: 'UNCLAIMED_READ_FAILED' } : undefined
  )
  // 直接调用真实已导出的HTTP消费者，故意没有页面error owner；不挂载任何伪造UI。
  await page.evaluate(async () => {
    const module = '/src/api/index.ts'
    const { api } = await import(/* @vite-ignore */ module)
    await api.agents.list().catch(() => undefined)
  })
  await expect(toasts(page)).toHaveCount(1)
  await expect(toasts(page).getByRole('button', { name: '排查信息', exact: true })).toBeVisible()
  await expect(page.locator('.el-alert--error')).toHaveCount(0)
  await toasts(page).getByRole('button', { name: '排查信息', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '问题排查信息', exact: true })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('Trace ID: mock-')
  await evidence(page, info, 'http-global-fallback-diagnostics')
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  mock.assertHandled()
})

test('长详情在独立主题弹层中阅读，窄屏可见，Esc关闭后焦点返回触发按钮', async ({ page }, info) => {
  const mock = await openAgents(page)
  await showDiagnosticMessage(page)
  const toast = toasts(page)
  await expect(toast).toHaveCount(1)
  await expect(toast).toContainText('操作未完成，请稍后重试')
  await expect(toast).not.toContainText('feedback-browser-proof')
  const trigger = toast.getByRole('button', { name: '排查信息', exact: true })
  await trigger.focus()
  await trigger.press('Enter')
  const dialog = page.getByRole('dialog', { name: '问题排查信息', exact: true })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('feedback-browser-proof')
  await expect(dialog.getByRole('button', { name: '关闭', exact: true })).toBeFocused()
  await settleAnimations(page)
  const contentBox = dialog.locator('.el-message-box')
  const box = await contentBox.boundingBox()
  expect(box).not.toBeNull()
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width)
  expect(box!.y).toBeGreaterThanOrEqual(0)
  expect(box!.y + box!.height).toBeLessThanOrEqual(page.viewportSize()!.height)
  expect(await dialog.evaluate((element) => element.contains(document.activeElement))).toBe(true)
  const colors = await contentBox.evaluate((element) => ({
    background: getComputedStyle(element).backgroundColor,
    dark: document.documentElement.classList.contains('dark')
  }))
  expect(colors.dark).toBe(info.project.use.colorScheme === 'dark')
  expect(colors.background).not.toBe('rgba(0, 0, 0, 0)')
  await evidence(page, info, 'long-details-independent-dialog')
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(trigger).toBeFocused()
  await expect(toast).not.toContainText('feedback-browser-proof')
  mock.assertHandled()
})

test('确认框内展开排查信息不触发确认，不增加第二层模态且动作始终可达', async ({ page }, info) => {
  const mock = await openAgents(page)
  await page.evaluate(async (module) => {
    const { confirm } = await import(/* @vite-ignore */ module)
    const state = window as unknown as { feedbackConfirmation?: boolean }
    delete state.feedbackConfirmation
    void confirm({
      title: '确认反馈验收操作',
      content: '执行受控操作？',
      details: 'confirmation-diagnostic-proof',
      confirmText: '明确执行',
      cancelText: '继续编辑',
      danger: true
    }).then((value: boolean) => {
      state.feedbackConfirmation = value
    })
  }, feedbackModule)
  const confirmation = page.getByRole('dialog', { name: '确认反馈验收操作', exact: true })
  await expect(confirmation).toBeVisible()
  await expect(
    confirmation.getByRole('button', { name: '明确执行', exact: true })
  ).not.toBeFocused()
  const disclosure = confirmation.locator('details')
  const summary = disclosure.locator('summary').filter({ hasText: '排查信息' })
  await summary.focus()
  await summary.press('Enter')
  await expect(disclosure).toHaveAttribute('open', '')
  await expect(disclosure.locator('pre')).toHaveText('confirmation-diagnostic-proof')
  await expect(page.getByRole('dialog')).toHaveCount(1)
  expect(
    await page.evaluate(
      () => (window as unknown as { feedbackConfirmation?: boolean }).feedbackConfirmation
    )
  ).toBeUndefined()
  await expect(confirmation).toBeVisible()
  await expect(confirmation.getByRole('button', { name: '明确执行', exact: true })).toBeVisible()
  await expect(confirmation.getByRole('button', { name: '继续编辑', exact: true })).toBeVisible()
  await evidence(page, info, 'confirmation-inline-details')
  await confirmation.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect
    .poll(() =>
      page.evaluate(
        () => (window as unknown as { feedbackConfirmation?: boolean }).feedbackConfirmation
      )
    )
    .toBe(false)
  await evidence(page, info, 'confirmation-cancel-safe')
  mock.assertHandled()
})

test('四条不同反馈最多保留三条，重复消息不会继续堆叠', async ({ page }, info) => {
  const mock = await openAgents(page)
  await page.evaluate(async (module) => {
    const { message } = await import(/* @vite-ignore */ module)
    for (const text of ['第一条反馈', '第二条反馈', '第三条反馈', '第四条反馈', '第四条反馈'])
      message.info(text, { duration: 0 })
  }, feedbackModule)
  await expect(toasts(page)).toHaveCount(3)
  await expect(page.locator('.el-message').filter({ hasText: '第一条反馈' })).toHaveCount(0)
  await expect(toasts(page).filter({ hasText: '第四条反馈' })).toHaveCount(1)
  await evidence(page, info, 'three-message-limit')
  mock.assertHandled()
})
