import { expect, test, type Locator, type Page } from '@playwright/test'
import { mockConsole } from './support/mock-console'

type Definition = {
  version: string
  entry: string
  nodes: Array<Record<string, unknown> & { id: string; type: string }>
  edges: Array<Record<string, unknown> & { from: string; to: string }>
  meta: Record<string, unknown>
}
type Draft = {
  workflow_id: string
  status: 'draft' | 'published' | 'archived'
  revision: number
  active_definition_version_id: string | null
  definition: Definition
  problems: unknown[]
}
const capability = {
  model_invocable: true,
  user_invocable: true,
  input_schema: { type: 'object', properties: {} },
  output_schema: { type: 'object', properties: {} },
  supports_hitl: false,
  export_fields: [],
  timeout_ms: 30000,
  concurrency_safe: false
}
function definition(): Definition {
  return {
    version: '1.0',
    entry: 'start',
    nodes: [
      { id: 'start', type: 'start', name: '开始' },
      { id: 'reply', type: 'output', name: '业务回复', message: '处理完成' },
      { id: 'end', type: 'end', name: '结束' }
    ],
    edges: [],
    meta: {
      name: 'browser-flow',
      display_name: '浏览器业务流程',
      version: '1.0.0',
      description: '仅用当前支持的开始、输出和结束节点验证浏览器交互',
      capability,
      config_schema: [{ key: 'region', label: '服务地区', type: 'string', default: '杭州' }]
    }
  }
}
/** 夹具只实现本文件实际使用的契约；未知 API 继续由 mockConsole 拒绝。 */
async function workflowFixture(
  page: Page,
  status?: Draft['status'],
  generatedId = false,
  unknownCreate = false
) {
  const console = await mockConsole(page, 'admin')
  let draft: Draft | undefined = status
    ? {
        workflow_id: 'browser-flow',
        status,
        revision: 7,
        active_definition_version_id: null,
        definition: definition(),
        problems: []
      }
    : undefined
  let layout: Record<string, unknown> = {
    start: { x: 40, y: 30 },
    reply: { x: 40, y: 190 },
    end: { x: 40, y: 350 }
  }
  let viewport = { zoom: 1, panX: 24, panY: 24 }
  let layoutRevision = draft?.revision ?? 0
  let publication: Definition | undefined
  console.setHook(async (request, url) => {
    const path = url.pathname.slice('/api/v1'.length)
    const method = request.method()
    if (path === '/builder/workflows' && method === 'POST') {
      const body = request.postDataJSON() as { workflow_id: string; definition: Definition }
      expect(draft, '新建不能覆盖已有工作流').toBeUndefined()
      if (generatedId) expect(body.workflow_id).toMatch(/^workflow-[0-9a-f-]{36}$/)
      else expect(body.workflow_id).toBe('created-browser-flow')
      if (unknownCreate) return { status: 503, code: 'TEMPORARY' }
      draft = {
        workflow_id: body.workflow_id,
        status: 'draft',
        revision: 1,
        active_definition_version_id: null,
        definition: structuredClone(body.definition),
        problems: []
      }
      return { data: draft }
    }
    if (path === '/builder/workflows/draft' && method === 'GET') {
      if (!draft) return { status: 404, code: 'WORKFLOW_NOT_FOUND' }
      expect(url.searchParams.get('workflowId')).toBe(draft.workflow_id)
      return { data: draft }
    }
    if (path === '/builder/workflows/draft' && method === 'PUT') {
      expect(request.headers()['if-match']).toBe(String(draft?.revision))
      const body = request.postDataJSON() as {
        workflow_id: string
        payload: { definition: Definition }
      }
      expect(body.workflow_id).toBe(draft?.workflow_id)
      if (!draft || draft.status === 'archived') return { status: 409, code: 'DRAFT_NOT_EDITABLE' }
      draft = {
        ...draft,
        status: 'draft',
        revision: draft.revision + 1,
        definition: structuredClone(body.payload.definition)
      }
      return { data: draft }
    }
    if (path === '/builder/workflows/draft/snapshot' && method === 'GET')
      return {
        data: { workflow_id: draft?.workflow_id, draft_revision: layoutRevision, layout, viewport }
      }
    if (path === '/builder/workflows/draft/snapshot' && method === 'PUT') {
      expect(request.headers()['if-match']).toBe(String(draft?.revision))
      const body = request.postDataJSON() as {
        workflow_id: string
        payload: { layout: Record<string, unknown>; viewport: typeof viewport }
      }
      expect(body.workflow_id).toBe(draft?.workflow_id)
      layout = structuredClone(body.payload.layout)
      viewport = structuredClone(body.payload.viewport)
      layoutRevision = draft!.revision
      return {
        data: { workflow_id: draft!.workflow_id, draft_revision: layoutRevision, layout, viewport }
      }
    }
    if (path === '/builder/workflows/validate-content' && method === 'POST') {
      expect(request.postDataJSON().definition).toBeTruthy()
      return { data: { revision: 0, problems: [] } }
    }
    if (path === '/builder/workflows/draft/validate' && method === 'POST') {
      expect(request.postDataJSON()).toEqual({ workflow_id: draft?.workflow_id })
      const ids = new Set(draft?.definition.nodes.map((node) => node.id))
      const invalid =
        !draft ||
        draft.definition.nodes.some((node) => !['start', 'output', 'end'].includes(node.type)) ||
        draft.definition.edges.some((edge) => !ids.has(edge.from) || !ids.has(edge.to))
      return {
        data: {
          revision: draft?.revision,
          problems: invalid
            ? [
                {
                  code: 'INVALID_FIXTURE_FLOW',
                  severity: 'error',
                  object_ref: 'definition',
                  field_path: 'nodes',
                  message: '夹具仅支持本文件使用的真实节点与有效连接'
                }
              ]
            : []
        }
      }
    }
    if (path === '/builder/workflows/publish' && method === 'POST') {
      expect(request.headers()['if-match']).toBe(String(draft?.revision))
      expect(request.postDataJSON()).toEqual({ workflow_id: draft?.workflow_id })
      if (!draft || draft.status === 'archived') return { status: 409, code: 'DRAFT_NOT_EDITABLE' }
      publication = structuredClone(draft.definition)
      draft = { ...draft, status: 'published', active_definition_version_id: '41' }
      return {
        data: {
          workflow_id: draft.workflow_id,
          draft_revision: draft.revision,
          definition_version_id: '41',
          definition_version: 1
        }
      }
    }
    if (path === '/builder/workflows/history' && method === 'GET')
      return {
        data: {
          items: publication
            ? [
                {
                  definition_version_id: '41',
                  definition_version: 1,
                  capability_version_id: null,
                  created_at: '2026-10-24T01:00:00Z',
                  current: true
                }
              ]
            : []
        }
      }
    if (path === '/builder/workflows/history/detail' && method === 'GET') {
      if (
        !publication ||
        url.searchParams.get('workflowId') !== draft?.workflow_id ||
        url.searchParams.get('definitionVersionId') !== '41'
      )
        return { status: 404, code: 'WORKFLOW_PUBLICATION_NOT_FOUND', data: null }
      return {
        data: {
          workflow_id: draft!.workflow_id,
          definition_version_id: '41',
          definition_version: 1,
          capability_version_id: null,
          created_at: '2026-10-24T01:00:00Z',
          current: true,
          definition: publication
        }
      }
    }
    if (path === '/capabilities/definitions' && method === 'GET') return { data: [] }
    if (path === '/capabilities/deployments' && method === 'GET') return { data: [] }
    if (path === '/builder/workflows/install' && method === 'POST') {
      if (!publication) return { status: 409, code: 'WORKFLOW_NOT_PUBLISHED' }
      expect(request.postDataJSON()).toEqual({
        workflow_id: draft?.workflow_id,
        payload: {
          config: { region: '杭州', enabled: false },
          custom_prompt: '使用机构术语',
          bindings: { capabilities: [], workflows: [], agents: [] }
        }
      })
      return {
        data: {
          status: 'installed',
          workflow_id: draft!.workflow_id,
          tenant_id: console.user.tenant_id,
          instance_id: 'instance-browser-1',
          installation_revision: '9007199254740995'
        }
      }
    }
    return undefined
  })
  return { ...console, state: () => ({ draft, layout, viewport, publication }) }
}
function field(scope: Locator, page: Page, name: string) {
  return scope.locator('.el-form-item').filter({
    has: page.locator('.el-form-item__label').filter({ hasText: new RegExp(`^${name}$`) })
  })
}
async function selectOption(page: Page, control: Locator, label: string) {
  await control.click()
  await page.getByRole('option', { name: label, exact: true }).click()
}

test('创建结果未知后刷新和核对保留原标识且不重发', async ({ page }) => {
  const fixture = await workflowFixture(page, undefined, true, true)
  await page.goto('/admin/workflows/create')
  const info = page.getByRole('tabpanel', { name: '工作流信息', exact: true })
  const original = await field(info, page, '业务标识').locator('input').inputValue()
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByText(/创建结果尚未确认/)).toBeVisible()
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled()
  await page.reload()
  await expect(page.getByText(/创建结果尚未确认/)).toContainText(original)
  await page.getByRole('button', { name: '核对原工作流', exact: true }).click()
  await expect(page.getByText(/当前未找到原标识/)).toBeVisible()
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled()
  expect(
    fixture.requests.filter((item) => item.path === '/builder/workflows' && item.method === 'POST')
  ).toHaveLength(1)
  fixture.assertHandled()
})

test('新建工作流无需手填业务标识即可保存', async ({ page }) => {
  const fixture = await workflowFixture(page, undefined, true)
  await page.goto('/admin/workflows/create')
  const info = page.getByRole('tabpanel', { name: '工作流信息', exact: true })
  const generated = await field(info, page, '业务标识').locator('input').inputValue()
  expect(generated).toMatch(/^workflow-[0-9a-f-]{36}$/)
  await field(info, page, '名称').locator('input').fill('自动标识流程')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page).toHaveURL(`/admin/workflows/${generated}`)
  expect(fixture.state().draft?.workflow_id).toBe(generated)
  expect(
    fixture.requests.filter((item) => item.path === '/builder/workflows' && item.method === 'POST')
  ).toHaveLength(1)
  fixture.assertHandled()
})

test('新建工作流：校验未保存内容，保存后发布并安装', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1100 })
  const fixture = await workflowFixture(page)
  await page.goto('/admin/workflows/create')
  const info = page.getByRole('tabpanel', { name: '工作流信息', exact: true })
  await expect(info).toBeVisible()
  await field(info, page, '业务标识').locator('input').fill('created-browser-flow')
  await field(info, page, '名称').locator('input').fill('新建浏览器流程')
  const params = info.getByRole('region', { name: '安装参数声明' })
  await params.getByRole('button', { name: '添加安装参数', exact: true }).click()
  const region = params.locator('.config-field').nth(0)
  await field(region, page, '参数标识').locator('input').fill('region')
  await field(region, page, '展示名称').locator('input').fill('服务地区')
  await field(region, page, '默认值').locator('input').fill('杭州')
  await field(region, page, '必填').locator('.el-switch__core').click()
  await params.getByRole('button', { name: '添加安装参数', exact: true }).click()
  const enabled = params.locator('.config-field').nth(1)
  await field(enabled, page, '参数标识').locator('input').fill('enabled')
  await field(enabled, page, '展示名称').locator('input').fill('启用附加检查')
  await selectOption(page, field(enabled, page, '类型').locator('.el-select__wrapper'), 'boolean')
  await selectOption(page, field(enabled, page, '默认值').locator('.el-select__wrapper'), '否')
  await page.getByRole('button', { name: '校验', exact: true }).first().click()
  await expect
    .poll(() =>
      fixture.requests.filter((item) => item.path === '/builder/workflows/validate-content')
    )
    .toHaveLength(1)
  await expect(page).toHaveURL(/\/admin\/workflows\/create$/)
  expect(fixture.state().draft).toBeUndefined()
  expect(
    fixture.requests.filter(
      (item) => item.path === '/builder/workflows/draft/snapshot' && item.method === 'PUT'
    )
  ).toHaveLength(0)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/workflows\/created-browser-flow$/)
  expect(fixture.state().draft?.definition.meta.config_schema).toEqual([
    { key: 'region', label: '服务地区', type: 'string', required: true, default: '杭州' },
    { key: 'enabled', label: '启用附加检查', type: 'boolean', required: false, default: false }
  ])
  expect(fixture.state().draft?.definition.nodes.map((node) => node.type)).toEqual(['start', 'end'])
  await page.getByRole('button', { name: '发布', exact: true }).click()
  await page
    .getByRole('dialog', { name: '发布工作流', exact: true })
    .getByRole('button', { name: '发布工作流', exact: true })
    .click()
  await expect(page.getByRole('button', { name: '安装', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '安装', exact: true }).click()
  const install = page.getByRole('dialog', { name: '安装工作流', exact: true })
  await expect(install).toBeVisible()
  await expect(field(install, page, '服务地区（必填）').locator('input')).toHaveValue('杭州')
  await expect(field(install, page, '启用附加检查（可选）')).toContainText('否')
  await field(install, page, '自定义提示').locator('textarea').fill('使用机构术语')
  await install.getByRole('button', { name: '安装', exact: true }).click()
  await expect(install).toBeHidden()
  await expect(
    page.getByText('已安装实例 instance-browser-1 · 安装修订 9007199254740995', { exact: false })
  ).toBeVisible()
  expect(
    fixture.requests.filter((item) => item.path === '/builder/workflows/install')
  ).toHaveLength(1)
  fixture.assertHandled()
})

test('流程画布：选节点改属性，端口连线并编辑条件，缩放与键盘位置独立保存', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1100 })
  const fixture = await workflowFixture(page, 'draft')
  await page.goto('/admin/workflows/browser-flow')
  const canvas = page.getByRole('region', { name: '工作流画布', exact: true })
  await expect(canvas.locator('[data-node-id]')).toHaveCount(3)
  await canvas.locator('[data-node-id="reply"]').click()
  await page
    .getByRole('textbox', { name: '输出消息', exact: true })
    .fill('完成 ${config.region} 的服务')
  await canvas.getByRole('button', { name: '开始的输出端口', exact: true }).click()
  await canvas.getByRole('button', { name: '业务回复的输入端口', exact: true }).click()
  await expect(page.getByRole('tab', { name: '连接与条件（1）', exact: true })).toHaveAttribute(
    'aria-selected',
    'true'
  )
  await page.getByRole('tab', { name: '流程设计', exact: true }).click()
  await canvas.getByRole('button', { name: '业务回复的输出端口', exact: true }).click()
  await canvas.getByRole('button', { name: '结束的输入端口', exact: true }).click()
  await expect(page.getByRole('tab', { name: '连接与条件（2）', exact: true })).toHaveAttribute(
    'aria-selected',
    'true'
  )
  await page.getByRole('tab', { name: '流程设计', exact: true }).click()
  const edge = canvas.getByRole('button', { name: '编辑连接：开始 → 业务回复', exact: true })
  await edge.focus()
  await edge.press('Enter')
  const connections = page.getByRole('tabpanel', { name: '连接与条件（2）', exact: true })
  const first = connections.locator('.el-table__body tbody tr').first()
  await first.getByText('专业表达式', { exact: true }).click()
  await first.locator('.cm-content').fill('${config.region} == "杭州"')
  await first.getByRole('spinbutton').fill('5')
  await first.getByRole('spinbutton').press('Tab')
  await page.getByRole('tab', { name: '流程设计', exact: true }).click()
  await canvas.getByRole('button', { name: '放大画布', exact: true }).click()
  await expect(canvas.getByRole('button', { name: '恢复画布原始比例' })).toHaveText('110%')
  const reply = canvas.locator('[data-node-id="reply"]')
  await reply.focus()
  await reply.press('ArrowRight')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect.poll(() => fixture.state().draft?.revision).toBe(8)
  await expect.poll(() => fixture.state().layout.reply).toEqual({ x: 48, y: 190 })
  expect(fixture.state().viewport.zoom).toBeCloseTo(1.1)
  expect(fixture.state().draft?.definition.nodes.find((node) => node.id === 'reply')?.message).toBe(
    '完成 ${config.region} 的服务'
  )
  expect(fixture.state().draft?.definition.edges).toEqual([
    { from: 'start', to: 'reply', condition: '${config.region} == "杭州"', priority: 5 },
    { from: 'reply', to: 'end' }
  ])
  expect(fixture.state().draft?.definition.nodes.every((node) => !('layout' in node))).toBe(true)
  await canvas.screenshot({ path: test.info().outputPath('workflow-canvas-saved.png') })
  fixture.assertHandled()
})

test('窄窗口归档工作流：节点可选可读，禁止编辑与连线，视口仍可缩放平移', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const fixture = await workflowFixture(page, 'archived')
  await page.goto('/admin/workflows/browser-flow')
  const canvas = page.getByRole('region', { name: '工作流画布', exact: true })
  await expect(canvas.locator('[data-node-id]')).toHaveCount(3)
  await expect(page.getByRole('button', { name: '添加节点', exact: true })).toBeDisabled()
  await expect(canvas.getByRole('button', { name: '开始的输出端口', exact: true })).toBeDisabled()
  await canvas.getByRole('button', { name: '适应画布', exact: true }).click()
  const output = canvas.locator('[data-node-id="reply"]')
  const before = await output.locator('..').getAttribute('style')
  await output.click()
  await expect(page.getByRole('textbox', { name: '输出消息', exact: true })).toBeDisabled()
  await expect(page.getByRole('textbox', { name: '输出消息', exact: true })).toHaveValue('处理完成')
  await page.getByRole('button', { name: '收起配置', exact: true }).click()
  await expect(output).toBeFocused()
  await page.keyboard.press('ArrowRight')
  await page.keyboard.press('Delete')
  await expect(canvas.locator('[data-node-id]')).toHaveCount(3)
  expect(await output.locator('..').getAttribute('style')).toBe(before)
  await canvas.getByRole('button', { name: '放大画布', exact: true }).click()
  const world = canvas.locator('.workflow-canvas-world')
  const transform = await world.getAttribute('style')
  const surface = canvas.locator('.workflow-canvas-surface')
  await surface.scrollIntoViewIfNeeded()
  const bounds = await surface.boundingBox()
  expect(bounds).not.toBeNull()
  await page.mouse.move(bounds!.x + bounds!.width - 12, bounds!.y + bounds!.height - 24)
  await page.mouse.down()
  await page.mouse.move(bounds!.x + bounds!.width - 46, bounds!.y + bounds!.height - 62, {
    steps: 6
  })
  await page.mouse.up()
  await expect(world).not.toHaveAttribute('style', transform!)
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true
  )
  await page.screenshot({
    path: test.info().outputPath('workflow-readonly-narrow.png'),
    fullPage: true
  })
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})
