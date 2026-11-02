import { expect, test, type Locator, type Page, type TestInfo } from '@playwright/test'
import { createHash } from 'node:crypto'
import { Buffer } from 'node:buffer'
import { writeFile } from 'node:fs/promises'
import { mockConsole } from './support/mock-console'

type WorkflowNode = Record<string, unknown> & { id: string; type: string }
type WorkflowEdge = Record<string, unknown> & { from: string; to: string }
type Definition = {
  version: string
  entry: string
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  meta: Record<string, unknown>
  resources: Array<{ path: string; content_base64: string; sha256: string }>
}
const orderSchema = {
  type: 'object',
  additionalProperties: false,
  required: ['order'],
  properties: {
    order: {
      type: 'object',
      title: '订单',
      required: ['customer', 'total'],
      additionalProperties: false,
      properties: {
        customer: {
          type: 'object',
          title: '客户',
          required: ['email'],
          additionalProperties: false,
          properties: {
            email: { type: 'string', title: '客户邮箱', description: '发送通知使用的收件地址' }
          }
        },
        total: { type: 'number', title: '订单金额' }
      }
    },
    items: {
      type: 'array',
      title: '明细',
      items: { type: 'object', properties: { sku: { type: 'string' } } }
    }
  }
}
const path = 'state.orders.order.customer.email'
function definition(disconnected = false): Definition {
  const script =
    'export default async () => ({ order: { customer: { email: "customer@example.invalid" }, total: 101 }, items: [] })\n'
  return {
    version: '1.0',
    entry: 'start',
    resources: [
      {
        path: 'scripts/orders.js',
        content_base64: Buffer.from(script).toString('base64'),
        sha256: createHash('sha256').update(script).digest('hex')
      }
    ],
    nodes: [
      { id: 'start', type: 'start', name: '开始' },
      {
        id: 'fetch',
        type: 'script',
        name: '查询订单',
        result_to: 'state.orders',
        args: { script: 'scripts/orders.js', input: {}, output_schema: orderSchema }
      },
      {
        id: 'notify',
        type: 'tool',
        name: '通知客户',
        tool: 'send_notice',
        args: { recipient: disconnected ? '${state.orders.order.customer.email}' : '' },
        result_to: 'state.notice'
      },
      { id: 'reply', type: 'output', name: '业务回复', message: '前文 后文' },
      { id: 'prompt', type: 'llm', name: '整理说明', prompt: '开头 结尾' },
      { id: 'end', type: 'end', name: '结束' }
    ],
    edges: [
      ...(disconnected ? [{ from: 'start', to: 'notify' }] : [{ from: 'start', to: 'fetch' }]),
      ...(disconnected
        ? []
        : [
            {
              from: 'fetch',
              to: 'notify',
              condition: '${state.orders.order.total} > 100',
              priority: 7
            }
          ]),
      { from: 'notify', to: 'reply' },
      ...(disconnected
        ? [
            { from: 'reply', to: 'fetch' },
            { from: 'fetch', to: 'prompt' }
          ]
        : [{ from: 'reply', to: 'prompt' }]),
      { from: 'prompt', to: 'end' }
    ],
    meta: {
      name: 'dataflow-browser',
      display_name: '订单数据流浏览器验收',
      version: '1.0.0',
      description: '仅验证浏览器中的数据选择和定义保存，不执行工作流',
      capability: {
        model_invocable: true,
        user_invocable: true,
        input_schema: { type: 'object', properties: {} },
        output_schema: { type: 'object', properties: {} },
        supports_hitl: false,
        export_fields: [],
        timeout_ms: 30000,
        concurrency_safe: false
      },
      tools: [{ name: 'send_notice', requirement: 'notice', label: '发送通知' }],
      capability_requirements: [
        {
          slot: 'notice',
          type: 'tool',
          capability_id: 'tool.notice',
          version_constraint: '1.0.0',
          label: '发送通知',
          required: true
        }
      ]
    }
  }
}

async function fixture(page: Page, theme: 'light' | 'dark', disconnected = false) {
  const mock = await mockConsole(page, 'admin')
  let draft = {
    workflow_id: 'dataflow-browser',
    status: 'draft',
    revision: 7,
    active_definition_version_id: null,
    definition: definition(disconnected),
    problems: []
  }
  let layout: Record<string, unknown> = Object.fromEntries(
    draft.definition.nodes.map((node, index) => [node.id, { x: 40, y: 30 + index * 160 }])
  )
  let viewport = { zoom: 1, panX: 24, panY: 24 }
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  mock.setHook(async (request, url) => {
    const apiPath = url.pathname.slice('/api/v1'.length),
      method = request.method()
    if (apiPath.endsWith('/foundation/catalog/preferences'))
      return {
        data: { theme, locale: 'zh-CN', page_size: 20, compact_navigation: false, revision: 7 }
      }
    if (apiPath === '/builder/workflows/draft' && method === 'GET') return { data: draft }
    if (apiPath === '/builder/workflows/draft' && method === 'PUT') {
      expect(request.headers()['if-match']).toBe(String(draft.revision))
      const body = request.postDataJSON()
      expect(body.workflow_id).toBe(draft.workflow_id)
      draft = {
        ...draft,
        revision: draft.revision + 1,
        definition: structuredClone(body.payload.definition)
      }
      return { data: draft }
    }
    if (apiPath === '/builder/workflows/draft/snapshot' && method === 'GET')
      return {
        data: { workflow_id: draft.workflow_id, draft_revision: draft.revision, layout, viewport }
      }
    if (apiPath === '/builder/workflows/draft/snapshot' && method === 'PUT') {
      expect(request.headers()['if-match']).toBe(String(draft.revision))
      const body = request.postDataJSON()
      layout = structuredClone(body.payload.layout)
      viewport = structuredClone(body.payload.viewport)
      return {
        data: { workflow_id: draft.workflow_id, draft_revision: draft.revision, layout, viewport }
      }
    }
    if (apiPath === '/capabilities/definitions' && method === 'GET')
      return {
        data: [
          {
            id: '200',
            tenant_id: mock.user.tenant_id,
            capability_type: 'TOOL',
            capability_key: 'tool.notice',
            name: '通知工具',
            description: '',
            trust_level: 'TRUSTED',
            status: 'PUBLISHED',
            revision: 1,
            default_version_id: '201',
            created_at: null,
            updated_at: null
          }
        ]
      }
    if (apiPath === '/capabilities/definitions/versions' && method === 'GET')
      return {
        data: [
          {
            id: '201',
            definition_id: '200',
            tenant_id: mock.user.tenant_id,
            capability_type: 'TOOL',
            version_key: '1.0.0',
            name: '通知工具',
            description: '',
            trust_level: 'TRUSTED',
            status: 'PUBLISHED',
            content_hash: 'mock-only',
            created_at: null,
            published_at: '2026-10-24T01:00:00Z',
            descriptor: {
              tool_name: 'send_notice',
              input_schema: {
                type: 'object',
                required: ['recipient'],
                additionalProperties: false,
                properties: { recipient: { type: 'string', title: '收件地址' } }
              },
              output_schema: { type: 'object', properties: { accepted: { type: 'boolean' } } }
            }
          }
        ]
      }
    return undefined
  })
  return {
    ...mock,
    state: () => ({ draft, layout, viewport }),
    async evidence(info: TestInfo, name: string) {
      await page.screenshot({ path: info.outputPath(`${name}.png`), fullPage: true })
      const receipt = {
        boundary:
          '真实Chromium与实际Vue页面；全部业务API由mockConsole拦截，无真实Runtime/后端/供应商',
        requests: mock.requests,
        draft,
        errors
      }
      await writeFile(info.outputPath(`${name}.json`), JSON.stringify(receipt, null, 2))
      await info.attach('mock-boundary', {
        path: info.outputPath(`${name}.json`),
        contentType: 'application/json'
      })
      expect(errors).toEqual([])
      mock.assertHandled()
    }
  }
}
async function selectNode(page: Page, name: string, id: string) {
  const picker = page.getByRole('combobox', { name: '定位节点', exact: true })
  await picker.click()
  await page.getByRole('option', { name: `${name} · ${id}`, exact: true }).click()
}
async function returnToCanvas(page: Page) {
  const close = page.getByRole('button', { name: '收起配置', exact: true })
  if (await close.isVisible()) await close.click()
  await expect(page.getByRole('region', { name: '工作流画布', exact: true })).toBeVisible()
}
async function selectVariable(page: Page, picker: Locator, variable: string) {
  await picker.click()
  const popup = page.locator('.el-popper.workflow-variable-popper:visible')
  const option = popup.locator('.variable-option').filter({
    has: page
      .locator('.variable-path')
      .filter({ hasText: new RegExp('^' + variable.replaceAll('.', '\\.') + '$') })
  })
  await expect(option).toHaveCount(1)
  await option.click()
}
async function save(page: Page, state: () => { draft: { revision: number } }) {
  const revision = state().draft.revision
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect.poll(() => state().draft.revision).toBe(revision + 1)
  await expect(page.locator('.workflow-status')).toContainText('草稿已保存')
}
test('上游嵌套输出直选到工具参数，输出和提示词按光标插入，保存后引用身份准确', async ({
  page
}, info) => {
  const mock = await fixture(page, info.project.use.colorScheme === 'dark' ? 'dark' : 'light')
  await page.goto('/admin/workflows/dataflow-browser')
  await selectNode(page, '通知客户', 'notify')
  const argument = page
    .getByRole('region', { name: '工具参数', exact: true })
    .locator('.argument-row')
    .first()
  await argument.getByText('引用变量', { exact: true }).click()
  const picker = page.getByRole('combobox', { name: '工具参数 · recipient变量', exact: true })
  await picker.click()
  const popup = page.locator('.el-popper.workflow-variable-popper:visible')
  await expect(popup).toContainText('查询订单 · fetch')
  await expect(popup).toContainText('客户邮箱')
  await expect(popup).toContainText('string')
  await expect(popup).not.toContainText('state.orders.items.0')
  await expect(popup.locator('[role="tree"] > [role="treeitem"]').first()).toContainText(
    '查询订单 · fetch'
  )
  await expect(popup.locator('[data-key="group:运行上下文"]')).toHaveAttribute(
    'aria-expanded',
    'false'
  )
  const leaf = popup.locator('.variable-option').filter({
    has: page
      .locator('.variable-path')
      .filter({ hasText: new RegExp('^' + path.replaceAll('.', '\\.') + '$') })
  })
  const styleEvidence = await leaf.evaluate((element) => {
    const item = element.closest('[role="treeitem"]')
    return {
      outerHTML: item?.outerHTML.slice(0, 6000),
      disabled: item?.getAttribute('aria-disabled'),
      classes: item?.className,
      color: getComputedStyle(element).color,
      headingColor: getComputedStyle(element.querySelector('.variable-heading > span')!).color,
      opacity: getComputedStyle(element).opacity
    }
  })
  await writeFile(info.outputPath('picker-leaf-style.json'), JSON.stringify(styleEvidence, null, 2))
  await info.attach('picker-leaf-style', {
    path: info.outputPath('picker-leaf-style.json'),
    contentType: 'application/json'
  })
  await page.screenshot({ path: info.outputPath('nested-source-picker.png'), fullPage: true })
  await page.keyboard.press('Escape')
  await selectVariable(page, picker, path)
  await selectNode(page, '业务回复', 'reply')
  const message = page.getByRole('textbox', { name: '输出消息', exact: true })
  await message.focus()
  await message.press('Home')
  await message.press('ArrowRight')
  await message.press('ArrowRight')
  await selectVariable(
    page,
    page.getByRole('combobox', { name: '向输出消息插入变量', exact: true }),
    path
  )
  await expect(message).toHaveValue('前文${' + path + '} 后文')
  await expect(message).toBeFocused()
  await selectNode(page, '整理说明', 'prompt')
  const editor = page.getByRole('textbox', { name: '节点提示词', exact: true })
  await editor.focus()
  await editor.press('Home')
  await editor.press('ArrowRight')
  await editor.press('ArrowRight')
  await selectVariable(
    page,
    page.getByRole('combobox', { name: '向提示词插入变量', exact: true }),
    path
  )
  await expect(editor).toHaveText('开头${' + path + '} 结尾')
  await save(page, mock.state)
  const nodes = mock.state().draft.definition.nodes
  expect(nodes.find((node) => node.id === 'notify')?.args).toEqual({ recipient: '${' + path + '}' })
  expect(nodes.find((node) => node.id === 'reply')?.message).toBe('前文${' + path + '} 后文')
  expect(nodes.find((node) => node.id === 'prompt')?.prompt).toBe('开头${' + path + '} 结尾')
  await mock.evidence(info, 'nested-reference-saved')
})

test('没有可达上游时提示来源缺失，原引用保留，不提供不相连节点的字段', async ({ page }, info) => {
  const mock = await fixture(page, info.project.use.colorScheme === 'dark' ? 'dark' : 'light', true)
  await page.goto('/admin/workflows/dataflow-browser')
  await selectNode(page, '通知客户', 'notify')
  await expect(page.locator('.workflow-properties')).toContainText(
    '暂无可引用的上游节点输出，可使用工作流输入或上下文变量。'
  )
  const picker = page.getByRole('combobox', { name: '工具参数 · recipient变量', exact: true })
  await picker.focus()
  await picker.press('ArrowDown')
  const popup = page.locator('.el-popper.workflow-variable-popper:visible')
  await expect(popup).toContainText('原引用（当前未声明）')
  await expect(popup).toContainText(path)
  await expect(popup).not.toContainText('查询订单 · fetch')
  await picker.press('Escape')
  await save(page, mock.state)
  expect(mock.state().draft.definition.nodes.find((node) => node.id === 'notify')?.args).toEqual({
    recipient: '${' + path + '}'
  })
  expect(
    mock.requests.filter((request) => request.method !== 'GET').map((request) => request.path)
  ).toEqual(['/builder/workflows/draft', '/builder/workflows/draft/snapshot'])
  await page.getByRole('tab', { name: /^校验与变更/ }).click()
  const warning = page.locator('.workflow-problem').filter({ hasText: path }).first()
  await expect(warning).toContainText('提醒')
  await warning.getByRole('button', { name: '定位问题', exact: true }).click()
  await expect(page.getByRole('tab', { name: '流程设计', exact: true })).toHaveAttribute(
    'aria-selected',
    'true'
  )
  await expect(page.getByRole('combobox', { name: '调用工具', exact: true })).toBeVisible()
  await mock.evidence(info, 'unreachable-source-retained')
})

test('画布添加下一步和连接插入节点保留条件与优先级，键盘可操作', async ({ page }, info) => {
  const mock = await fixture(page, info.project.use.colorScheme === 'dark' ? 'dark' : 'light')
  await page.goto('/admin/workflows/dataflow-browser')
  const canvas = page.getByRole('region', { name: '工作流画布', exact: true })
  await selectNode(page, '通知客户', 'notify')
  await returnToCanvas(page)
  await canvas.getByRole('button', { name: '添加下一步', exact: true }).click()
  let dialog = page.getByRole('dialog', { name: '添加后续节点', exact: true })
  await dialog.getByRole('combobox', { name: '新节点类型', exact: true }).click()
  await page.getByRole('option', { name: '输出', exact: true }).click()
  await dialog.getByRole('button', { name: '添加并连接', exact: true }).click()
  await expect(dialog).toBeHidden()
  await save(page, mock.state)
  const after = mock.state().draft.definition
  const added = after.nodes.find(
    (node) => !definition().nodes.some((original) => original.id === node.id)
  )!
  expect(added.type).toBe('output')
  expect(after.edges).toEqual(
    expect.arrayContaining([
      { from: 'notify', to: added.id },
      { from: added.id, to: 'reply' }
    ])
  )
  await selectNode(page, '查询订单', 'fetch')
  await returnToCanvas(page)
  const edge = canvas.getByRole('button', {
    name: '编辑连接：查询订单 → 通知客户（有条件）',
    exact: true
  })
  await edge.focus()
  await edge.press('Enter')
  const condition = page
    .getByRole('tabpanel', { name: /^连接与条件/ })
    .locator('.el-table__body tbody tr')
    .nth(1)
  await selectVariable(
    page,
    condition.getByRole('combobox', { name: '连接条件左侧变量', exact: true }),
    'state.orders.order.total'
  )
  await expect(condition.getByRole('textbox', { name: '连接条件比较值', exact: true })).toHaveValue(
    '100'
  )
  await page.getByRole('tab', { name: '流程设计', exact: true }).click()
  await canvas.getByRole('button', { name: '在连线中插入节点', exact: true }).click()
  dialog = page.getByRole('dialog', { name: '在连接中插入节点', exact: true })
  await dialog.getByRole('combobox', { name: '新节点类型', exact: true }).click()
  await page.getByRole('option', { name: '输出', exact: true }).click()
  await dialog.getByRole('button', { name: '添加并连接', exact: true }).focus()
  await page.keyboard.press('Enter')
  await expect(dialog).toBeHidden()
  await save(page, mock.state)
  const inserted = mock
    .state()
    .draft.definition.nodes.find(
      (node) => !after.nodes.some((original) => original.id === node.id)
    )!
  expect(mock.state().draft.definition.edges).toEqual(
    expect.arrayContaining([
      {
        from: 'fetch',
        to: inserted.id,
        condition: '${state.orders.order.total} > 100',
        priority: 7
      },
      { from: inserted.id, to: 'notify' }
    ])
  )
  expect(
    mock
      .state()
      .draft.definition.edges.some((edge) => edge.from === 'fetch' && edge.to === 'notify')
  ).toBe(false)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(
    mock.state().draft.definition.edges.find((edge) => edge.from === inserted.id)?.priority ?? 0
  ).toBe(0)
  await returnToCanvas(page)
  await mock.evidence(info, 'canvas-insertion-saved')
})

test('视口工作台填宽，配置收起和专注切换保留草稿、选中与视口且不写入', async ({ page }, info) => {
  const mock = await fixture(page, info.project.use.colorScheme === 'dark' ? 'dark' : 'light')
  await page.goto('/admin/workflows/dataflow-browser')
  const canvas = page.getByRole('region', { name: '工作流画布', exact: true })
  const workspace = page.locator('.workflow-workspace')
  const properties = page.locator('.workflow-properties')
  const surface = canvas.locator('.workflow-canvas-surface')
  const world = canvas.locator('.workflow-canvas-world')
  await expect(canvas.locator('[data-node-id]')).toHaveCount(6)
  await expect(properties).toBeHidden()
  if (page.viewportSize()!.width <= 600) {
    const more = page.getByRole('button', { name: '更多与帮助', exact: true })
    await more.click()
    const help = page
      .locator('.el-popover:visible')
      .filter({ has: page.locator('.workflow-mobile-actions') })
    for (const name of ['撤销', '重做', '自动排列']) {
      await expect(help.getByRole('button', { name, exact: true })).toBeVisible()
    }
    await more.click()
    await expect(help).toHaveCount(0)
  }
  const measure = async () => {
    const bounds = await surface.boundingBox()
    expect(bounds).not.toBeNull()
    return { ...bounds!, area: bounds!.width * bounds!.height }
  }
  const normal = await measure()
  const workspaceBounds = await workspace.boundingBox()
  expect(normal.width).toBeGreaterThan(workspaceBounds!.width - 4)
  await page.screenshot({ path: info.outputPath('workbench-normal.png'), fullPage: true })
  const originalDefinition = structuredClone(mock.state().draft.definition)
  const originalViewport = structuredClone(mock.state().viewport)
  await selectNode(page, '业务回复', 'reply')
  await expect(properties).toBeVisible()
  const message = page.getByRole('textbox', { name: '输出消息', exact: true })
  await message.fill('尚未保存的工作台输出草稿')
  const narrow = page.viewportSize()!.width <= 900
  if (narrow) await expect(canvas).toBeHidden()
  else expect((await measure()).width).toBeLessThan(normal.width)
  await page.screenshot({ path: info.outputPath('workbench-properties.png'), fullPage: true })
  await page.getByRole('button', { name: '收起配置', exact: true }).click()
  await expect(canvas).toBeVisible()
  await expect(properties).toBeHidden()
  await expect(canvas.locator('[data-node-id="reply"]')).toHaveAttribute('aria-pressed', 'true')
  const collapsed = await measure()
  await page.getByRole('button', { name: '节点配置', exact: true }).click()
  await expect(message).toHaveValue('尚未保存的工作台输出草稿')
  await expect(properties).toBeVisible()
  await properties.press('Escape')
  await expect(canvas).toBeVisible()
  await expect(properties).toBeHidden()
  const transform = await world.evaluate((element) => (element as HTMLElement).style.transform)
  await page.getByRole('button', { name: '专注画布', exact: true }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('button', { name: '退出专注', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true'
  )
  await expect(page.locator('.admin-topbar')).toBeHidden()
  await expect(page.locator('.admin-sidebar')).toBeHidden()
  await expect(canvas.locator('[data-node-id="reply"]')).toHaveAttribute('aria-pressed', 'true')
  expect(await world.evaluate((element) => (element as HTMLElement).style.transform)).toBe(
    transform
  )
  const focused = await measure()
  expect(focused.area).toBeGreaterThan(collapsed.area)
  await page.screenshot({ path: info.outputPath('workbench-focused.png'), fullPage: true })
  await page.getByRole('button', { name: '退出专注', exact: true }).click()
  await expect(page.locator('.admin-topbar')).toBeVisible()
  await expect(canvas.locator('[data-node-id="reply"]')).toHaveAttribute('aria-pressed', 'true')
  expect(await world.evaluate((element) => (element as HTMLElement).style.transform)).toBe(
    transform
  )
  const exited = await measure()
  await page.getByRole('button', { name: '节点配置', exact: true }).click()
  await expect(message).toHaveValue('尚未保存的工作台输出草稿')
  expect(mock.state().draft.definition).toEqual(originalDefinition)
  expect(mock.state().viewport).toEqual(originalViewport)
  expect(mock.requests.filter((request) => request.method !== 'GET')).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const areas = {
    viewport: page.viewportSize(),
    normal,
    collapsed,
    focused,
    exited,
    transform,
    savedRevision: mock.state().draft.revision,
    businessWrites: 0
  }
  await writeFile(info.outputPath('workbench-areas.json'), JSON.stringify(areas, null, 2))
  await info.attach('workbench-areas', {
    path: info.outputPath('workbench-areas.json'),
    contentType: 'application/json'
  })
  await mock.evidence(info, 'workbench-draft-retained')
})
