import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { Buffer } from 'node:buffer'
import { createHash } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { mockConsole } from './support/mock-console'

type Definition = {
  version: string
  entry: string
  nodes: Array<Record<string, unknown> & { id: string; type: string }>
  edges: Array<Record<string, unknown> & { from: string; to: string }>
  meta: Record<string, unknown>
  resources: Array<{ path: string; content_base64: string; sha256: string }>
  [key: string]: unknown
}
function definition(message = '当前草稿内容'): Definition {
  const script = 'export default async () => ({ ready: true })\n'
  return {
    version: '1.0',
    entry: 'start',
    future_contract: { revision: 2, enabled: false, count: 0 },
    nodes: [
      { id: 'start', type: 'start', name: '开始' },
      {
        id: 'script',
        type: 'script',
        name: '准备数据',
        result_to: 'state.prepared',
        args: {
          script: 'scripts/prepare.js',
          input: {},
          output_schema: { type: 'object', properties: { ready: { type: 'boolean' } } }
        }
      },
      {
        id: 'reply',
        type: 'output',
        name: '业务回复',
        message,
        future_node: { style: 'brief', tags: ['保留'] }
      },
      { id: 'end', type: 'end', name: '结束' }
    ],
    edges: [
      { from: 'start', to: 'script' },
      { from: 'script', to: 'reply', future_edge: { audit: true, weight: 0 } },
      { from: 'reply', to: 'end' }
    ],
    meta: {
      name: 'lifecycle-browser',
      display_name: '工作流生命周期验收',
      version: '1.0.0',
      description: '仅验证浏览器生命周期与请求身份',
      future_meta: { owner: 'fixture', flags: [false, 0] },
      capability: {
        model_invocable: true,
        user_invocable: true,
        input_schema: { type: 'object', properties: {} },
        output_schema: { type: 'object', properties: {} },
        supports_hitl: false,
        export_fields: [],
        timeout_ms: 30000,
        concurrency_safe: false
      }
    },
    resources: [
      {
        path: 'scripts/prepare.js',
        content_base64: Buffer.from(script).toString('base64'),
        sha256: createHash('sha256').update(script).digest('hex')
      }
    ]
  }
}
async function fixture(
  page: Page,
  info: TestInfo,
  options: { archived?: boolean; conflict?: boolean; groupLayout?: boolean } = {}
) {
  const mock = await mockConsole(page, 'admin')
  let draft = {
    workflow_id: 'lifecycle-browser',
    status: options.archived ? 'archived' : 'draft',
    revision: 7,
    active_definition_version_id: '41',
    definition: definition(),
    problems: []
  }
  let viewport = { zoom: 1, panX: 24, panY: 24 }
  const layout = Object.fromEntries(
    draft.definition.nodes.map((node, index) => [
      node.id,
      { x: options.groupLayout && node.id === 'reply' ? 200 : 40, y: 30 + 160 * index }
    ])
  )
  const publications = [
    {
      definition_version_id: '31',
      definition_version: 1,
      capability_version_id: null,
      created_at: '2026-10-23T01:00:00Z',
      current: false,
      definition: definition('历史发布内容')
    },
    {
      definition_version_id: '41',
      definition_version: 2,
      capability_version_id: null,
      created_at: '2026-10-24T01:00:00Z',
      current: true,
      definition: definition('当前发布内容')
    }
  ]
  const mutations: Array<{ path: string; ifMatch?: string; body: unknown }> = []
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  mock.setHook(async (request, url) => {
    const path = url.pathname.slice('/api/v1'.length),
      method = request.method()
    if (path.endsWith('/foundation/catalog/preferences'))
      return {
        data: {
          theme: info.project.use.colorScheme === 'dark' ? 'dark' : 'light',
          locale: 'zh-CN',
          page_size: 20,
          compact_navigation: false,
          revision: 7
        }
      }
    if (method !== 'GET')
      mutations.push({ path, ifMatch: request.headers()['if-match'], body: request.postDataJSON() })
    if (path === '/builder/workflows/draft' && method === 'GET') return { data: draft }
    if (path === '/builder/workflows/draft' && method === 'PUT') {
      expect(request.headers()['if-match']).toBe(String(options.conflict ? 7 : draft.revision))
      const body = request.postDataJSON()
      expect(body.workflow_id).toBe(draft.workflow_id)
      if (options.conflict) {
        draft = { ...draft, revision: 8, definition: definition('服务端已保存内容') }
        return { status: 409, code: 'WORKFLOW_REVISION_CONFLICT', data: null }
      }
      expect(options.archived).not.toBe(true)
      draft = {
        ...draft,
        revision: draft.revision + 1,
        definition: structuredClone(body.payload.definition)
      }
      return { data: draft }
    }
    if (path === '/builder/workflows/draft/snapshot' && method === 'GET')
      return {
        data: { workflow_id: draft.workflow_id, draft_revision: draft.revision, layout, viewport }
      }
    if (path === '/builder/workflows/draft/snapshot' && method === 'PUT') {
      expect(options.conflict).not.toBe(true)
      expect(request.headers()['if-match']).toBe(String(draft.revision))
      viewport = structuredClone(request.postDataJSON().payload.viewport)
      return {
        data: {
          workflow_id: draft.workflow_id,
          draft_revision: draft.revision,
          layout: request.postDataJSON().payload.layout,
          viewport
        }
      }
    }
    if (path === '/builder/workflows/draft/validate' && method === 'POST')
      return { data: { revision: draft.revision, problems: [] } }
    if (path === '/builder/workflows/save-and-publish' && method === 'POST') {
      const body = request.postDataJSON()
      expect(body.revision).toBe(draft.revision)
      draft = {
        ...draft,
        revision: draft.revision + 1,
        status: 'published',
        active_definition_version_id: '42',
        definition: structuredClone(body.definition)
      }
      Object.assign(layout, body.layout)
      viewport = structuredClone(body.viewport)
      return {
        data: {
          request_id: body.request_id,
          workflow_id: body.workflow_id,
          status: 'SUCCEEDED',
          publication: {
            workflow_id: body.workflow_id,
            draft_revision: draft.revision,
            definition_version_id: '42',
            definition_version: 3,
            capability_version_id: '77'
          }
        }
      }
    }
    if (path === '/builder/workflows/publish' && method === 'POST') {
      expect(request.headers()['if-match']).toBe(String(draft.revision))
      draft = { ...draft, status: 'published', active_definition_version_id: '42' }
      return {
        data: {
          workflow_id: draft.workflow_id,
          draft_revision: draft.revision,
          definition_version_id: '42',
          definition_version: 3
        }
      }
    }
    if (path === '/builder/workflows/history' && method === 'GET')
      return {
        data: {
          items: publications.map((publication) => ({
            definition_version_id: publication.definition_version_id,
            definition_version: publication.definition_version,
            capability_version_id: publication.capability_version_id,
            created_at: publication.created_at,
            current: publication.current
          }))
        }
      }
    if (path === '/builder/workflows/history/detail' && method === 'GET') {
      const publication = publications.find(
        (value) =>
          String(value.definition_version_id) === url.searchParams.get('definitionVersionId')
      )
      if (url.searchParams.get('workflowId') !== draft.workflow_id || !publication)
        return { status: 404, code: 'WORKFLOW_PUBLICATION_NOT_FOUND', data: null }
      return { data: { workflow_id: draft.workflow_id, ...publication } }
    }
    if (path === '/builder/workflows/draft/restore' && method === 'POST') {
      expect(options.archived).not.toBe(true)
      expect(request.headers()['if-match']).toBe(String(draft.revision))
      expect(request.postDataJSON()).toEqual({
        workflow_id: draft.workflow_id,
        definition_version_id: '31'
      })
      draft = {
        ...draft,
        status: 'draft',
        revision: draft.revision + 1,
        definition: structuredClone(publications[0]!.definition)
      }
      return { data: draft }
    }
    return undefined
  })
  return {
    ...mock,
    state: () => ({ draft, viewport, mutations }),
    async evidence(name: string) {
      await page.screenshot({ path: info.outputPath(`${name}.png`), fullPage: true })
      const canvas = page.getByRole('region', { name: '工作流画布', exact: true })
      const canvasSurface = (await canvas.isVisible())
        ? await canvas.locator('.workflow-canvas-surface').boundingBox()
        : null
      await writeFile(
        info.outputPath(`${name}.json`),
        JSON.stringify(
          {
            boundary:
              '真实Chromium与Vue页面，所有API为context mock；未执行真实后端、Runtime或供应商',
            requests: mock.requests,
            mutations,
            draft,
            viewport,
            canvasSurface,
            errors
          },
          null,
          2
        )
      )
      expect(errors).toEqual([])
      mock.assertHandled()
    }
  }
}
async function editReply(page: Page, value?: string) {
  const design = page.getByRole('tab', { name: '流程设计', exact: true })
  for (let step = 0; step < 5 && (await design.getAttribute('aria-selected')) !== 'true'; step++) {
    await page.getByRole('tab', { selected: true }).press('ArrowLeft')
  }
  await expect(design).toHaveAttribute('aria-selected', 'true')
  await page.getByRole('combobox', { name: '定位节点', exact: true }).click()
  await page.getByRole('option', { name: '业务回复 · reply', exact: true }).click()
  const input = page.getByRole('textbox', { name: '输出消息', exact: true })
  if (value !== undefined) await input.fill(value)
  return input
}
async function more(page: Page) {
  const button = page.getByRole('button', { name: '更多与帮助', exact: true })
  if (!(await page.getByRole('button', { name: '导出定义', exact: true }).isVisible()))
    await button.click()
}
async function download(page: Page, info: TestInfo, name: string, action = '导出定义') {
  if (action === '导出定义') await more(page)
  const pending = page.waitForEvent('download')
  await page.getByRole('button', { name: action, exact: true }).click()
  const file = await pending
  await file.saveAs(info.outputPath(`${name}.json`))
  const value = JSON.parse(await readFile(info.outputPath(`${name}.json`), 'utf8')) as Definition
  if (action === '导出定义') {
    await page.getByRole('button', { name: '更多与帮助', exact: true }).click()
    await expect(
      page
        .locator('.el-popover:visible')
        .filter({ has: page.locator('button').filter({ hasText: /^导出定义$/ }) })
    ).toHaveCount(0)
  }
  return value
}
async function upload(page: Page, value: string) {
  await more(page)
  const choosing = page.waitForEvent('filechooser')
  await page
    .locator('button')
    .filter({ hasText: /^导入定义$/ })
    .click()
  const chooser = await choosing
  await chooser.setFiles({
    name: 'same-workflow.json',
    mimeType: 'application/json',
    buffer: Buffer.from(value)
  })
  const confirmation = page.getByRole('dialog', { name: '导入工作流定义', exact: true })
  await expect(confirmation).toBeVisible()
  await confirmation.getByRole('button', { name: '替换并导入', exact: true }).click()
  await expect(confirmation).toBeHidden()
  const moreButton = page.getByRole('button', { name: '更多与帮助', exact: true })
  if (await page.getByRole('button', { name: '导出定义', exact: true }).isVisible())
    await moreButton.click()
}
async function positions(page: Page) {
  return page.locator('.workflow-canvas-node').evaluateAll((elements) =>
    Object.fromEntries(
      elements.map((element) => {
        const node = element.querySelector<HTMLElement>('[data-node-id]')!
        const wrapper = element as HTMLElement
        return [
          node.dataset.nodeId!,
          { x: parseFloat(wrapper.style.left), y: parseFloat(wrapper.style.top) }
        ]
      })
    )
  )
}
async function openBatch(page: Page) {
  const menu = page
    .locator('.el-popover:visible')
    .filter({ has: page.getByRole('button', { name: '全选节点', exact: true }) })
  if (!(await menu.isVisible()))
    await page
      .getByRole('region', { name: '工作流画布', exact: true })
      .getByRole('button', { name: '批量编辑', exact: true })
      .click()
  await expect(menu).toBeVisible()
  return menu
}
async function batchAction(page: Page, name: string) {
  const menu = await openBatch(page)
  await menu.getByRole('button', { name, exact: true }).click()
  await expect(menu).toHaveCount(0)
  await expect
    .poll(() =>
      page.evaluate(() => {
        const active = document.activeElement as HTMLElement | null
        return (
          !!active?.closest('.workflow-canvas-shell') &&
          !active.closest('.el-popover') &&
          active.getClientRects().length > 0
        )
      })
    )
    .toBe(true)
}

test('编辑后一次保存校验并确认发布当前修订', async ({ page }, info) => {
  const mock = await fixture(page, info)
  await page.goto('/admin/workflows/lifecycle-browser')
  await expect(page.locator('[data-node-id]')).toHaveCount(4)
  await editReply(page, '一次发布的输出')
  await page.getByRole('button', { name: '保存并发布', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '保存并发布工作流', exact: true })
  await expect(confirmation).toBeVisible()
  expect(mock.state().mutations).toEqual([])
  await confirmation.getByRole('button', { name: '保存并发布', exact: true }).click()
  await expect.poll(() => mock.state().draft.status).toBe('published')
  expect(mock.state().mutations.at(-1)).toMatchObject({
    path: '/builder/workflows/save-and-publish'
  })
  expect(mock.state().draft.definition.nodes.find((node) => node.id === 'reply')?.message).toBe(
    '一次发布的输出'
  )
  await mock.evidence('save-publish')
})

test('定义下载修改后导入保存，未知层级和脚本资源完整往返，同文件可重复选择', async ({
  page
}, info) => {
  const mock = await fixture(page, info)
  await page.goto('/admin/workflows/lifecycle-browser')
  await expect(page.locator('[data-node-id]')).toHaveCount(4)
  const exported = await download(page, info, 'original-export')
  expect(exported.future_contract).toEqual(definition().future_contract)
  const imported = structuredClone(exported)
  imported.nodes.find((node) => node.id === 'reply')!.message = '从下载文件修改的输出'
  imported.meta.description = '导出修改后重新导入'
  for (const revision of [8, 9]) {
    await editReply(page, '本地待替换草稿 ' + revision)
    await upload(page, JSON.stringify(imported))
    await expect(await editReply(page)).toHaveValue('从下载文件修改的输出')
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    await expect.poll(() => mock.state().draft.revision).toBe(revision)
    expect(mock.state().draft.definition).toEqual(imported)
  }
  expect(mock.state().draft.definition.resources).toEqual(exported.resources)
  expect(
    mock
      .state()
      .mutations.filter((entry) => entry.path === '/builder/workflows/draft')
      .map((entry) => entry.ifMatch)
  ).toEqual(['7', '8'])
  await mock.evidence('roundtrip-saved')
})

test('JSON、节点结构和脚本资源导入失败均保留原图与本地草稿', async ({ page }, info) => {
  const mock = await fixture(page, info)
  await page.goto('/admin/workflows/lifecycle-browser')
  await editReply(page, '不可丢失的本地草稿')
  const before = await download(page, info, 'before-invalid-import')
  const malformed = { ...before, nodes: [{ id: 'start', type: 42 }, ...before.nodes.slice(1)] }
  const badResources = {
    ...before,
    resources: [{ path: '../escape.js', content_base64: '', sha256: 'invalid' }]
  }
  for (const [value, message] of [
    ['{ invalid JSON', /JSON/],
    [JSON.stringify(malformed), /每个节点必须包含字符串 id 和 type/],
    [JSON.stringify(badResources), /资源|scripts|路径/]
  ] as const) {
    await upload(page, value)
    await expect(page.getByRole('alert').filter({ hasText: message })).toBeVisible()
    await expect(await editReply(page)).toHaveValue('不可丢失的本地草稿')
    expect(await download(page, info, 'after-invalid-' + message.source.length)).toEqual(before)
    await expect(page.locator('[data-node-id]')).toHaveCount(4)
  }
  expect(mock.state().mutations).toEqual([])
  await mock.evidence('invalid-import-retained')
})

test('历史定义只读查看，确认后按当前修订恢复为草稿', async ({ page }, info) => {
  const mock = await fixture(page, info)
  await page.goto('/admin/workflows/lifecycle-browser')
  await page.getByRole('tab', { name: '发布历史', exact: true }).click()
  const old = page
    .getByRole('tabpanel', { name: '发布历史', exact: true })
    .locator('.el-table__body tbody tr')
    .filter({ hasText: '历史发布' })
  await old.getByRole('button', { name: '查看定义', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '工作流发布修订 1', exact: true })
  await expect(drawer).toContainText('历史发布内容')
  await expect(drawer.locator('input, textarea, [contenteditable="true"]')).toHaveCount(0)
  expect(mock.state().mutations).toEqual([])
  await page.keyboard.press('Escape')
  await expect(drawer).toBeHidden()
  await old.getByRole('button', { name: '恢复为草稿', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '恢复工作流草稿', exact: true })
  await confirmation.getByRole('button', { name: '恢复为草稿', exact: true }).click()
  await expect.poll(() => mock.state().draft.revision).toBe(8)
  await expect(await editReply(page)).toHaveValue('历史发布内容')
  expect(mock.state().mutations).toEqual([
    {
      path: '/builder/workflows/draft/restore',
      ifMatch: '7',
      body: { workflow_id: 'lifecycle-browser', definition_version_id: '31' }
    }
  ])
  await mock.evidence('history-restored')
})

test('归档工作流仍可读历史定义，恢复入口禁用且无写请求', async ({ page }, info) => {
  const mock = await fixture(page, info, { archived: true })
  await page.goto('/admin/workflows/lifecycle-browser')
  await page.getByRole('tab', { name: '发布历史', exact: true }).click()
  const history = page.getByRole('tabpanel', { name: '发布历史', exact: true })
  await expect(
    history.getByRole('button', { name: '恢复为草稿', exact: true }).first()
  ).toBeDisabled()
  await history.getByRole('button', { name: '查看定义', exact: true }).first().click()
  await expect(page.getByRole('dialog', { name: '工作流发布修订 1', exact: true })).toContainText(
    '历史发布内容'
  )
  expect(mock.state().mutations).toEqual([])
  await mock.evidence('archived-history-readonly')
  await page.keyboard.press('Escape')
  await page.getByRole('tab', { name: '流程设计', exact: true }).click()
  const canvas = page.getByRole('region', { name: '工作流画布', exact: true })
  await batchAction(page, '全选节点')
  const menu = await openBatch(page)
  for (const name of ['复制节点', '粘贴节点', '删除节点', '左对齐', '顶对齐']) {
    await expect(menu.getByRole('button', { name, exact: true })).toBeDisabled()
  }
  await canvas.getByRole('button', { name: '批量编辑', exact: true }).click()
  const before = await positions(page)
  const surface = canvas.locator('.workflow-canvas-surface')
  await surface.focus()
  for (const key of ['ArrowRight', 'Delete', 'Control+c', 'Control+v']) await surface.press(key)
  expect(await positions(page)).toEqual(before)
  await expect(canvas.locator('[data-node-id]')).toHaveCount(4)
  expect(mock.state().mutations).toEqual([])
  await mock.evidence('archived-group-readonly')
})

test('多选整组移动与对齐，复制排除开始节点，批量删除一次撤销恢复', async ({ page }, info) => {
  const mock = await fixture(page, info, { groupLayout: true })
  await page.goto('/admin/workflows/lifecycle-browser')
  const canvas = page.getByRole('region', { name: '工作流画布', exact: true })
  await canvas.getByRole('button', { name: '适应画布', exact: true }).click()
  const escaped = await openBatch(page)
  await escaped.getByRole('button', { name: '全选节点', exact: true }).focus()
  await page.keyboard.press('Escape')
  await expect(escaped).toHaveCount(0)
  await expect(canvas.getByRole('button', { name: '批量编辑', exact: true })).toBeFocused()
  await batchAction(page, '全选节点')
  await expect(canvas.locator('.workflow-canvas-count')).toContainText('已选 4 个节点')
  await batchAction(page, '复制节点')
  await expect(
    page.getByText('开始节点不能复制，请取消选择开始节点', { exact: true })
  ).toBeVisible()
  await expect(
    (await openBatch(page)).getByRole('button', { name: '粘贴节点', exact: true })
  ).toBeDisabled()
  await batchAction(page, '清除选择')
  await canvas.getByRole('button', { name: '多选模式', exact: true }).click()
  await canvas.locator('[data-node-id="script"]').click()
  await canvas.locator('[data-node-id="reply"]').click()
  await expect(canvas.locator('[data-node-id][aria-pressed="true"]')).toHaveCount(2)
  const before = await positions(page)
  await canvas.locator('[data-node-id="reply"]').focus()
  await canvas.locator('[data-node-id="reply"]').press('ArrowRight')
  await expect.poll(async () => (await positions(page)).script!.x).toBe(before.script!.x + 8)
  expect((await positions(page)).reply!.x).toBe(before.reply!.x + 8)
  expect((await positions(page)).start).toEqual(before.start)
  await batchAction(page, '左对齐')
  await expect.poll(async () => (await positions(page)).reply!.x).toBe(before.script!.x + 8)
  await batchAction(page, '复制节点')
  await batchAction(page, '粘贴节点')
  await expect(page.getByText('开始节点不能复制，请取消选择开始节点', { exact: true })).toHaveCount(
    0
  )
  await expect(canvas.locator('[data-node-id]')).toHaveCount(6)
  await expect(canvas.locator('[data-node-id][aria-pressed="true"]')).toHaveCount(2)
  const pasted = await download(page, info, 'pasted-subgraph')
  const clones = pasted.nodes.filter(
    (node) => !definition().nodes.some((original) => original.id === node.id)
  )
  expect(clones.map((node) => node.type).sort()).toEqual(['output', 'script'])
  expect(pasted.nodes.filter((node) => node.type === 'start')).toHaveLength(1)
  const source = clones.find((node) => node.type === 'script')!,
    target = clones.find((node) => node.type === 'output')!
  expect(pasted.edges).toContainEqual({
    from: source.id,
    to: target.id,
    future_edge: { audit: true, weight: 0 }
  })
  const pastedPositions = await positions(page)
  await batchAction(page, '删除节点')
  await expect(canvas.locator('[data-node-id]')).toHaveCount(4)
  if (page.viewportSize()!.width <= 600) await more(page)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  if (await page.getByRole('button', { name: '导出定义', exact: true }).isVisible())
    await page.getByRole('button', { name: '更多与帮助', exact: true }).click()
  await expect(canvas.locator('[data-node-id]')).toHaveCount(6)
  expect(await positions(page)).toEqual(pastedPositions)
  expect(await download(page, info, 'undo-restored-subgraph')).toEqual(pasted)
  expect(mock.state().mutations).toEqual([])
  await mock.evidence('group-edit-undo')
})

test('409后保留本地草稿，比较和导出只读，不重发；重新读取须明确放弃', async ({ page }, info) => {
  const mock = await fixture(page, info, { conflict: true })
  await page.goto('/admin/workflows/lifecycle-browser')
  await editReply(page, '本地未保存冲突内容')
  const local = await download(page, info, 'local-before-conflict')
  const world = page.locator('.workflow-canvas-world')
  const transform = await world.evaluate((element) => (element as HTMLElement).style.transform)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByText('工作流状态已变化，本地内容已保留', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '查看最新差异', exact: true }).click()
  await expect(
    page
      .getByRole('status')
      .filter({ hasText: '比较对象：当前本地内容相对服务端最新草稿修订 8；读取不会覆盖本地草稿。' })
  ).toBeVisible()
  const changes = page.getByRole('tabpanel', { name: /^校验与变更/ })
  await expect(changes).toContainText('服务端已保存内容')
  await expect(changes).toContainText('本地未保存冲突内容')
  expect(mock.state().mutations).toHaveLength(1)
  expect(await download(page, info, 'conflict-local-export', '导出当前内容')).toEqual(local)
  await expect(await editReply(page)).toHaveValue('本地未保存冲突内容')
  expect(await world.evaluate((element) => (element as HTMLElement).style.transform)).toBe(
    transform
  )
  await page.getByRole('button', { name: '重新读取', exact: true }).first().click()
  let confirmation = page.getByRole('dialog', { name: '重新读取工作流', exact: true })
  await confirmation.getByRole('button', { name: '取消', exact: true }).click()
  await expect(await editReply(page)).toHaveValue('本地未保存冲突内容')
  await page.getByRole('button', { name: '重新读取', exact: true }).first().click()
  confirmation = page.getByRole('dialog', { name: '重新读取工作流', exact: true })
  await confirmation.getByRole('button', { name: '放弃并读取', exact: true }).click()
  await expect(await editReply(page)).toHaveValue('服务端已保存内容')
  await expect(page.getByText('工作流状态已变化，本地内容已保留', { exact: true })).toHaveCount(0)
  expect(mock.state().mutations).toEqual([
    {
      path: '/builder/workflows/draft',
      ifMatch: '7',
      body: { workflow_id: 'lifecycle-browser', payload: { definition: local } }
    }
  ])
  await mock.evidence('conflict-readonly-then-reread')
})

test('组编辑画布与批量菜单视觉补证据', async ({ page }, info) => {
  const mock = await fixture(page, info, { groupLayout: true })
  await page.goto('/admin/workflows/lifecycle-browser')
  const canvas = page.getByRole('region', { name: '工作流画布', exact: true })
  await canvas.getByRole('button', { name: '适应画布', exact: true }).click()
  await canvas.getByRole('button', { name: '多选模式', exact: true }).click()
  await canvas.locator('[data-node-id="script"]').click()
  await canvas.locator('[data-node-id="reply"]').click()
  await expect(canvas.locator('[data-node-id][aria-pressed="true"]')).toHaveCount(2)
  await expect(page.locator('.el-popover:visible')).toHaveCount(0)
  const normal = await canvas.locator('.workflow-canvas-surface').boundingBox()
  await page.screenshot({ path: info.outputPath('group-canvas-clean.png'), fullPage: true })
  await page.getByRole('button', { name: '专注画布', exact: true }).click()
  const focused = await canvas.locator('.workflow-canvas-surface').boundingBox()
  await page.screenshot({ path: info.outputPath('group-canvas-focused.png'), fullPage: true })
  const menu = await openBatch(page)
  await expect(menu.getByRole('button', { name: '左对齐', exact: true })).toBeVisible()
  await expect(menu).toHaveCSS('opacity', '1')
  await page.screenshot({
    path: info.outputPath('group-batch-menu.png'),
    fullPage: true,
    animations: 'disabled'
  })
  await canvas.getByRole('button', { name: '批量编辑', exact: true }).click()
  await expect(menu).toHaveCount(0)
  await writeFile(
    info.outputPath('group-surface-areas.json'),
    JSON.stringify({ viewport: page.viewportSize(), normal, focused, selectedNodes: 2 }, null, 2)
  )
  expect(mock.state().mutations).toEqual([])
  await mock.evidence('group-layout-visual')
})
