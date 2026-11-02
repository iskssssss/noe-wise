import { test, expect } from '@playwright/test'
import { assertVisibleDescriptions, mockConsole } from './support/mock-console'

test('机构共享定义标签可用回车添加', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin', [
    'tenant.agent_definitions.read',
    'tenant.agent_definitions.create'
  ])
  fixture.setHook(async (_request, url) => {
    if (url.pathname === '/api/v1/tenant/agent-definitions')
      return { data: { items: [], total: 0 } }
    if (url.pathname === '/api/v1/settings/available-models') return { data: [] }
    if (url.pathname.includes('/capabilities/definitions')) return { data: [] }
    return undefined
  })
  await page.goto('/admin/agent-definitions')
  await page.getByRole('button', { name: '新建共享定义' }).click()
  const drawer = page.getByRole('dialog', { name: '新建机构共享定义' })
  const input = drawer.getByRole('combobox', { name: '标签' })
  await input.fill('业务标签')
  await expect(page.getByRole('option', { name: '业务标签' })).toBeVisible()
  await input.press('Enter')
  await expect(drawer.locator('.el-tag').getByText('业务标签', { exact: true })).toBeVisible()
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
})

test('工作流提取字段可用回车添加', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  await page.goto('/admin/workflows/create')
  await page.getByRole('tab', { name: '流程设计', exact: true }).click()
  await page.getByRole('button', { name: '添加节点', exact: true }).click()
  const panel = page.locator('aside[aria-label="节点配置"]')
  const input = panel.getByRole('combobox', { name: '提取字段' })
  await input.fill('order_id')
  await expect(page.getByRole('option', { name: 'order_id', exact: true })).toBeVisible()
  await input.dispatchEvent('keydown', {
    key: 'Enter',
    code: 'Enter',
    isComposing: true,
    keyCode: 229
  })
  await expect(panel.locator('.el-tag').getByText('order_id', { exact: true })).toHaveCount(0)
  await input.press('Enter')
  await expect(panel.locator('.el-tag').getByText('order_id', { exact: true })).toBeVisible()
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
})

test('市场分类可用回车选择自定义值', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  fixture.setHook(async (_request, url) =>
    url.pathname === '/api/v1/marketplace/search' ? { data: { items: [], total: 0 } } : undefined
  )
  await page.goto('/admin/marketplace')
  const input = page.getByRole('combobox', { name: '工作流分类' })
  await input.fill('订单&流程')
  await expect(page.getByRole('option', { name: '订单&流程', exact: true })).toBeVisible()
  await input.press('Enter')
  await expect(page.locator('.el-select__selected-item').getByText('订单&流程')).toBeVisible()
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect
    .poll(() => fixture.requests.filter((item) => item.path === '/marketplace/search').length)
    .toBe(2)
  expect(new URLSearchParams(fixture.requests.at(-1)?.query).get('category')).toBe('订单&流程')
})

for (const kind of ['agents', 'teams'] as const) {
  test(`${kind === 'agents' ? '智能体' : '团队'}目录读取失败可刷新恢复`, async ({ page }) => {
    const fixture = await mockConsole(page, 'admin')
    let reads = 0
    fixture.setHook(async (_request, url) => {
      if (url.pathname !== `/api/v1/${kind}`) return undefined
      reads++
      if (reads === 1 || reads === 3) return { status: 503, code: 'TEMPORARY' }
      return kind === 'agents'
        ? { data: { total: 1, items: [fixture.agents[1]] } }
        : {
            data: [
              {
                id: 'team-1',
                name: '服务团队',
                description: '',
                status: 'published',
                revision: 1,
                primary_agent_id: 'agent-1',
                members: [{ agent_id: 'agent-1', name: '订单助手' }]
              }
            ]
          }
    })
    const name = kind === 'agents' ? '智能体' : '团队'
    await page.goto(`/admin/${kind}`)
    await expect(page.getByText(`${name}列表尚未读取成功，请点击刷新重试`)).toBeVisible()
    await page.getByRole('button', { name: '刷新', exact: true }).click()
    await expect(page.locator('.el-table__body tbody tr')).toHaveCount(1)
    await page.getByRole('button', { name: '刷新', exact: true }).click()
    await expect(page.getByText(`${name}列表刷新失败，以下保留上次读取的结果。`)).toBeVisible()
    await expect(page.locator('.el-table__body tbody tr')).toHaveCount(1)
    await page.getByRole('button', { name: '刷新', exact: true }).click()
    await expect(page.getByText(`${name}列表刷新失败，以下保留上次读取的结果。`)).toHaveCount(0)
    expect(reads).toBe(4)
    expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
    fixture.assertHandled()
  })
}

test('团队按职责选择同名成员并确认主智能体身份', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  fixture.setHook(async (_request, url) => {
    if (url.pathname.endsWith('/teams')) return { data: [] }
    if (url.pathname.endsWith('/agents'))
      return {
        data: {
          items: [
            { ...fixture.agents[1], name: '客服', role_description: '查询订单' },
            { ...fixture.agents[2], name: '客服', role_description: '查询患者' }
          ]
        }
      }
    return undefined
  })
  await page.goto('/admin/teams')
  await page.getByRole('button', { name: '新增团队' }).click()
  const drawer = page.getByRole('dialog')
  const members = drawer
    .locator('.el-form-item')
    .filter({ has: page.getByText('团队成员', { exact: true }) })
  await members.locator('.el-select__wrapper').click()
  await members.locator('input').fill('查询患者')
  const patient = page.getByRole('option').filter({ hasText: '查询患者' })
  await expect(patient).toBeVisible()
  await expect(page.getByRole('option').filter({ hasText: '查询订单' })).toHaveCount(0)
  await patient.click()
  await expect(
    drawer.locator('.el-form-item').filter({ has: page.getByText('主智能体', { exact: true }) })
  ).toContainText(fixture.agents[2]!.id)
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('智能体草稿中刷新模型选项并选择新模型', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  const agent = fixture.agents[1]!
  let modelReads = 0
  fixture.setHook(async (_request, url) => {
    if (url.pathname === '/api/v1/agents/detail')
      return {
        data: {
          ...agent,
          system_prompt: '处理任务',
          model_config_id: '101',
          execution_config: {},
          delegation_config: { enabled: false, target_agent_ids: [] },
          mount_set_generation: 1,
          mounts: []
        }
      }
    if (url.pathname === '/api/v1/settings/available-models') {
      modelReads++
      const models = [
        { id: '101', name: '原模型', kind: 'LLM', model_id: 'deepseek-flash', revision: 1 }
      ]
      if (modelReads > 1)
        models.push({ id: '102', name: '新模型', kind: 'LLM', model_id: 'new-model', revision: 1 })
      return { data: models }
    }
    return undefined
  })
  await page.goto(`/admin/agents/${agent.id}`)
  const name = page.getByLabel('名称', { exact: true })
  await name.fill('保留的草稿')
  await page.getByRole('button', { name: '刷新模型选项' }).click()
  await expect.poll(() => modelReads).toBe(2)
  await expect(name).toHaveValue('保留的草稿')
  const field = page.locator('.el-form-item').filter({ hasText: '使用模型' })
  await field.locator('.el-select__wrapper').click()
  await page.getByRole('option').filter({ hasText: 'new-model' }).click()
  await expect(field).toContainText('new-model')
  await expect(name).toHaveValue('保留的草稿')
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('智能体配置按标识筛选同名模型', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  const agent = fixture.agents[1]!
  fixture.setHook(async (_request, url) => {
    if (url.pathname === '/api/v1/agents/detail')
      return {
        data: {
          ...agent,
          system_prompt: '处理任务',
          model_config_id: null,
          execution_config: {},
          delegation_config: { enabled: false, target_agent_ids: [] },
          mount_set_generation: 1,
          mounts: []
        }
      }
    if (url.pathname === '/api/v1/settings/available-models')
      return {
        data: [
          { id: '101', name: '对话模型', kind: 'LLM', model_id: 'deepseek-flash', revision: 1 },
          { id: '102', name: '对话模型', kind: 'LLM', model_id: 'other-model', revision: 1 }
        ]
      }
    return undefined
  })
  await page.goto(`/admin/agents/${agent.id}`)
  const field = page.locator('.el-form-item').filter({ hasText: '使用模型' })
  await field.locator('.el-select__wrapper').click()
  await field.locator('input').fill('deepseek-flash')
  const target = page.getByRole('option').filter({ hasText: 'deepseek-flash' })
  await expect(target).toContainText('标识 101')
  await expect(page.getByRole('option').filter({ hasText: 'other-model' })).toHaveCount(0)
  await target.click()
  await expect(field).toContainText('deepseek-flash')
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('Agent 按用途筛选同名知识库并读取所选身份', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  const agent = fixture.agents[1]!
  let baseReads = 0
  fixture.setHook(async (_request, url) => {
    const path = url.pathname
    if (path === '/api/v1/agents/detail')
      return {
        data: {
          ...agent,
          system_prompt: '处理任务',
          model_config_id: null,
          execution_config: {},
          delegation_config: { enabled: false, target_agent_ids: [] },
          mount_set_generation: 1,
          mounts: []
        }
      }
    if (path === '/api/v1/settings/available-models') return { data: [] }
    if (path === '/api/v1/capabilities/definitions') return { data: [] }
    if (path === '/api/v1/knowledge/bases') {
      baseReads++
      return {
        data: {
          total: baseReads > 1 ? 3 : 2,
          items: [
            { id: 'kb-1', name: '帮助中心', description: '订单查询' },
            { id: 'kb-2', name: '帮助中心', description: '售后服务' },
            ...(baseReads > 1 ? [{ id: 'kb-3', name: '新知识库', description: '新的服务' }] : [])
          ]
        }
      }
    }
    if (path === '/api/v1/knowledge/bases/mount-option')
      return {
        data: {
          kb_id: url.searchParams.get('kbId'),
          active_revision_id: null,
          definition_id: null,
          version_id: null,
          version_key: null
        }
      }
    if (path === '/api/v1/knowledge/bases/detail')
      return { data: { id: url.searchParams.get('kbId'), active_revision_id: null } }
    if (path === '/api/v1/knowledge/bases/revisions') return { data: { total: 0, items: [] } }
    return undefined
  })
  await page.goto(`/admin/agents/${agent.id}`)
  await page.getByRole('tab', { name: /能力挂载/ }).click()
  await page.getByRole('button', { name: '添加能力', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '添加能力挂载' })
  await drawer
    .locator('.el-form-item')
    .filter({ hasText: '能力类型' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: '知识来源', exact: true }).click()
  const knowledge = drawer
    .locator('.el-form-item')
    .filter({ hasText: '按名称、用途或标识查找知识库' })
  await knowledge.locator('.el-select__wrapper').click()
  await knowledge.locator('input').fill('kb-1')
  await expect(page.getByRole('option').filter({ hasText: '订单查询' })).toBeVisible()
  await expect(page.getByRole('option').filter({ hasText: '售后服务' })).toHaveCount(0)
  await knowledge.locator('input').fill('售后服务')
  const option = page.getByRole('option').filter({ hasText: '售后服务' })
  await expect(option).toBeVisible()
  await expect(page.getByRole('option').filter({ hasText: '订单查询' })).toHaveCount(0)
  await option.click()
  await expect
    .poll(
      () =>
        fixture.requests.filter((item) => item.path === '/knowledge/bases/mount-option').at(-1)
          ?.query
    )
    .toContain('kbId=kb-2')
  await expect(drawer.getByText('该知识库尚未发布')).toBeVisible()
  await drawer.getByRole('button', { name: '刷新知识库选项' }).click()
  await expect.poll(() => baseReads).toBe(2)
  await expect(drawer.getByText('该知识库尚未发布')).toBeVisible()
  expect(
    fixture.requests.filter((item) => item.path === '/knowledge/bases/mount-option')
  ).toHaveLength(2)
  expect(
    fixture.requests.filter((item) => item.path === '/knowledge/bases/revisions')
  ).toHaveLength(0)
  await knowledge.locator('.el-select__wrapper').click()
  await knowledge.locator('input').fill('新的服务')
  await expect(page.getByRole('option').filter({ hasText: '新的服务' })).toBeVisible()
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('按成员名称找到团队并查看成员', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  fixture.setHook(async (_request, url) => {
    if (url.pathname.endsWith('/teams'))
      return {
        data: [
          {
            id: 'team-1',
            name: '服务团队',
            description: '客户服务',
            status: 'published',
            revision: 1,
            primary_agent_id: 'a',
            members: [{ agent_id: 'a', name: '订单助手' }]
          },
          {
            id: 'team-2',
            name: '分析团队',
            description: '数据分析',
            status: 'draft',
            revision: 1,
            primary_agent_id: 'b',
            members: [{ agent_id: 'b', name: '报表助手' }]
          }
        ]
      }
    return undefined
  })
  await page.goto('/admin/teams')
  await page.getByRole('textbox', { name: '搜索团队' }).fill('订单助手')
  const rows = page.locator('.el-table__body tbody tr')
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText('服务团队')
  await expect(rows.first()).toContainText('订单助手')
  fixture.assertHandled()
})

test('新渠道选择单个智能体后可清除默认并按当前选择保存', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  const channel = {
    id: 'channel-1',
    name: '新渠道',
    type: 'openapi',
    status: 'active',
    agent_bindings: []
  }
  fixture.setHook(async (request, url) => {
    if (url.pathname.endsWith('/channels/detail')) return { data: channel }
    if (url.pathname.endsWith('/channels/agents') && request.method() === 'PUT')
      return { data: { status: 'bound', count: 1 } }
    if (url.pathname.endsWith('/channels')) return { data: { total: 1, items: [channel] } }
    return undefined
  })
  await page.goto('/admin/channels')
  await page.getByRole('button', { name: '绑定智能体' }).click()
  const drawer = page.getByRole('dialog', { name: '绑定智能体 · 新渠道' })
  const selects = drawer.locator('.el-select')
  await selects.nth(0).click()
  await page.getByRole('option').filter({ hasText: fixture.agents[1]!.name }).click()
  await expect(selects.nth(1)).toContainText(fixture.agents[1]!.name)
  await page.keyboard.press('Escape')
  await selects.nth(1).hover()
  await selects.nth(1).locator('.el-select__clear').click()
  await drawer.getByRole('button', { name: '保存', exact: true }).click()
  await expect
    .poll(() => fixture.requests.filter((item) => item.path === '/channels/agents').length)
    .toBe(1)
  expect(fixture.requests.find((item) => item.path === '/channels/agents')?.body).toEqual({
    channel_id: 'channel-1',
    payload: [{ agent_id: fixture.agents[1]!.id, priority: 0, is_default: false }]
  })
  fixture.assertHandled()
})

test('渠道绑定按角色说明查找同名智能体', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  const channel = {
    id: 'channel-1',
    name: '新渠道',
    type: 'openapi',
    status: 'active',
    agent_bindings: []
  }
  fixture.setHook(async (request, url) => {
    if (url.pathname.endsWith('/channels/detail')) return { data: channel }
    if (url.pathname.endsWith('/channels/agents') && request.method() === 'PUT')
      return { data: { status: 'bound', count: 1 } }
    if (url.pathname.endsWith('/channels')) return { data: { total: 1, items: [channel] } }
    if (url.pathname.endsWith('/agents'))
      return {
        data: {
          total: 2,
          items: [
            {
              ...fixture.agents[1],
              id: '9100000000000001',
              name: '客服',
              agent_key: 'orders',
              role_description: '查询订单资料'
            },
            {
              ...fixture.agents[2],
              id: '9100000000000002',
              name: '客服',
              agent_key: 'patients',
              role_description: '查询患者资料'
            }
          ]
        }
      }
    return undefined
  })
  await page.goto('/admin/channels')
  await page.getByRole('button', { name: '绑定智能体' }).click()
  const drawer = page.getByRole('dialog', { name: '绑定智能体 · 新渠道' })
  const select = drawer.locator('.el-select').first()
  await select.click()
  await select.locator('input').fill('患者资料')
  const patient = page.getByRole('option').filter({ hasText: '查询患者资料' })
  await expect(patient).toBeVisible()
  await expect(page.getByRole('option').filter({ hasText: '查询订单资料' })).toHaveCount(0)
  await patient.click()
  await drawer.getByRole('button', { name: '保存', exact: true }).click()
  await expect
    .poll(() => fixture.requests.filter((item) => item.path === '/channels/agents').length)
    .toBe(1)
  expect(fixture.requests.find((item) => item.path === '/channels/agents')?.body).toEqual({
    channel_id: 'channel-1',
    payload: [{ agent_id: '9100000000000002', priority: 0, is_default: true }]
  })
  fixture.assertHandled()
})

test('审核成功后读取失败保留事实，查询恢复前不重复审核', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  let reviewed = false,
    failRefresh = false
  fixture.setHook(async (request, url) => {
    if (url.pathname.endsWith('/memories/review') && request.method() === 'POST') {
      reviewed = true
      failRefresh = true
      return { data: {} }
    }
    if (url.pathname.endsWith('/memories') && reviewed) {
      if (failRefresh) {
        failRefresh = false
        return { status: 503, code: 'TEMPORARY' }
      }
      return {
        data: [{ ...fixture.facts[0], revision: 5, review_required: false, status: 'ACTIVE' }]
      }
    }
    return undefined
  })
  await page.goto('/admin/memory')
  const search = page.getByRole('textbox', { name: '筛选当前记忆' })
  await search.fill('关系-000')
  const row = page.locator('.el-table__body tbody tr').filter({ hasText: '关系-000' })
  await row.getByRole('button', { name: '批准', exact: true }).click()
  await expect(
    page.getByText('操作已完成，但当前列表尚未更新。请点击“查询”读取最新状态后继续修改。')
  ).toBeVisible()
  await expect(row.getByRole('button', { name: '批准', exact: true })).toBeDisabled()
  await expect(row.getByRole('button', { name: '拒绝', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(
    page.getByText('操作已完成，但当前列表尚未更新。请点击“查询”读取最新状态后继续修改。')
  ).toHaveCount(0)
  await expect(row.getByRole('button', { name: '批准', exact: true })).toHaveCount(0)
  expect(fixture.requests.filter((item) => item.path === '/memories/review')).toHaveLength(1)
  fixture.assertHandled()
})

test('记忆初次读取失败不误报空列表，可查询恢复', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  let fail = true
  fixture.setHook(async (_request, url) => {
    if (url.pathname.endsWith('/memories') && fail) {
      fail = false
      return { status: 503, code: 'TEMPORARY' }
    }
    return undefined
  })
  await page.goto('/admin/memory')
  await expect(page.getByText('记忆列表尚未读取', { exact: true })).toBeVisible()
  await expect(page.getByText('没有找到记忆，可调整查询范围')).toHaveCount(0)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByText(/当前显示 \d+ 条记忆/)).toBeVisible()
  fixture.assertHandled()
})

test('新建连接器后直接进入对应工具接入', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  let created = false
  fixture.setHook(async (request, url) => {
    if (
      url.pathname.endsWith('/connectors/instances/create-with-receipt') &&
      request.method() === 'POST'
    ) {
      created = true
      return { data: { request_id: request.postDataJSON().request_id, instance_id: 'new-server' } }
    }
    if (url.pathname.endsWith('/connectors/instances')) {
      return {
        data: {
          items: created
            ? [
                {
                  id: '42',
                  instance_id: 'new-server',
                  name: '订单服务',
                  base_url: 'https://example.com/mcp',
                  executor_type: 'mcp',
                  status: 'active'
                }
              ]
            : []
        }
      }
    }
    if (url.pathname.endsWith('/tools/available')) return { data: { items: [] } }
    if (url.pathname.endsWith('/connectors/instances/mcp-tools'))
      return {
        data: { items: [{ name: 'query_order', description: '查询订单', input_schema: {} }] }
      }
    return undefined
  })
  await page.goto('/admin/connectors')
  await page.getByRole('button', { name: '新增连接器' }).click()
  const drawer = page.getByRole('dialog', { name: '新增 MCP 连接器' })
  await drawer.getByLabel('名称', { exact: true }).fill('订单服务')
  await drawer.getByLabel('服务地址', { exact: true }).fill('https://example.com/mcp')
  await drawer.getByRole('button', { name: '保存并接入工具' }).click()
  await expect(page).toHaveURL(/\/admin\/tools\?connectorId=new-server/)
  await expect(page.locator('.tool-onboarding .el-select').first()).toContainText('订单服务')
  await expect(page.getByText('query_order', { exact: true })).toBeVisible()
  expect(
    fixture.requests.filter(
      (item) => item.path === '/connectors/instances/create-with-receipt' && item.method === 'POST'
    )
  ).toHaveLength(1)
  expect(
    fixture.requests.find((item) => item.path === '/connectors/instances/mcp-tools')?.query
  ).toContain('instanceId=new-server')
  fixture.assertHandled()
})

test('可用工具目录读取在途时仍能选择连接器并发现工具', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  let release!: () => void
  const waitForCatalog = new Promise<void>((resolve) => {
    release = resolve
  })
  let catalogPending = false
  fixture.setHook(async (_request, url) => {
    if (url.pathname.endsWith('/tools/available')) {
      catalogPending = true
      await waitForCatalog
      return { data: { items: [] } }
    }
    if (url.pathname.endsWith('/connectors/instances'))
      return {
        data: { items: [{ instance_id: 'server', name: '订单服务', executor_type: 'mcp' }] }
      }
    if (url.pathname.endsWith('/connectors/instances/mcp-tools'))
      return {
        data: { items: [{ name: 'query_order', description: '查询订单', input_schema: {} }] }
      }
    return undefined
  })
  try {
    await page.goto('/admin/tools?connectorId=server')
    await expect.poll(() => catalogPending).toBe(true)
    await expect(page.getByText('query_order', { exact: true })).toBeVisible()
    expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
  } finally {
    release()
  }
  fixture.assertHandled()
})

test('按标识选择同名连接器并说明成功读取的空目录', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  fixture.setHook(async (_request, url) => {
    if (url.pathname.endsWith('/tools/available')) return { data: { items: [] } }
    if (url.pathname.endsWith('/connectors/instances'))
      return {
        data: {
          items: [
            { instance_id: 'east', name: '订单服务', executor_type: 'mcp' },
            { instance_id: 'west', name: '订单服务', executor_type: 'mcp' }
          ]
        }
      }
    if (url.pathname.endsWith('/connectors/instances/mcp-tools')) return { data: { items: [] } }
    return undefined
  })
  await page.goto('/admin/tools')
  await page.getByRole('button', { name: '接入工具', exact: true }).click()
  const select = page.getByRole('combobox', { name: '工具来源连接器' })
  await select.click()
  await select.fill('west')
  const option = page.getByRole('option').filter({ hasText: '标识：west' })
  await expect(option).toBeVisible()
  await expect(page.getByRole('option').filter({ hasText: '标识：east' })).toHaveCount(0)
  await option.click()
  await expect(page.getByText('此连接器未返回工具，请检查服务提供的工具目录')).toBeVisible()
  expect(
    fixture.requests.find((item) => item.path === '/connectors/instances/mcp-tools')?.query
  ).toContain('instanceId=west')
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('文档首次读取失败不误报空知识库，刷新后展示文档', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  let documentReads = 0
  fixture.setHook(async (_request, url) => {
    if (url.pathname === '/api/v1/knowledge/bases/detail')
      return { data: { id: 'kb-1', name: '知识库' } }
    if (url.pathname === '/api/v1/knowledge/bases/revisions') return { data: { items: [] } }
    if (url.pathname === '/api/v1/knowledge/bases/rebuilds/detail')
      return { data: { status: 'IDLE', write_locked: false } }
    if (url.pathname === '/api/v1/knowledge/bases/documents') {
      documentReads++
      return documentReads === 1
        ? { status: 503, code: 'TEMPORARY' }
        : {
            data: {
              total: 1,
              items: [{ id: 'doc-1', title: '已有文档', status: 'ready', source_version: 'v1' }]
            }
          }
    }
    return undefined
  })
  await page.goto('/admin/knowledge/kb-1')
  await expect(page.getByText('文档列表尚未读取成功，请点击刷新重试')).toBeVisible()
  await expect(page.getByText('尚无文档，点击“上传文件”添加知识内容')).toHaveCount(0)
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByText('已有文档', { exact: true })).toBeVisible()
  expect(documentReads).toBe(2)
  expect(fixture.requests.filter((request) => request.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('上传后列表读取失败可用顶部刷新恢复，且不重复上传', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  fixture.setHook(async (_request, url) => {
    if (url.pathname === '/api/v1/knowledge/bases/detail')
      return { data: { id: 'kb-1', name: '知识库' } }
    if (url.pathname === '/api/v1/knowledge/bases/revisions') return { data: { items: [] } }
    if (url.pathname === '/api/v1/knowledge/bases/rebuilds/detail')
      return { data: { status: 'IDLE', write_locked: false } }
    return undefined
  })
  let uploads = 0,
    failNextRead = false
  await page.route('**/api/v1/knowledge/bases/documents**', async (route) => {
    const request = route.request()
    if (request.method() === 'POST') {
      uploads++
      failNextRead = true
      await route.fulfill({
        json: {
          code: 'OK',
          message: 'OK',
          trace_id: 'fixture',
          data: {
            request_id: new URL(request.url()).searchParams.get('request_id'),
            status: 'SUCCEEDED',
            document: { document_id: 'new', source_version: 'v1', chunks: 1 }
          }
        }
      })
    } else if (failNextRead) {
      failNextRead = false
      await route.fulfill({ status: 503, json: { code: 'TEMPORARY', message: '读取失败' } })
    } else {
      await route.fulfill({
        json: {
          code: 'OK',
          message: 'OK',
          trace_id: 'fixture',
          data: {
            total: 1,
            items: [
              {
                id: 'doc-1',
                title: uploads ? '新文件' : '原文档',
                status: 'ready',
                source_version: 'v1'
              }
            ]
          }
        }
      })
    }
  })
  await page.goto('/admin/knowledge/kb-1')
  await expect(page.getByText('原文档', { exact: true })).toBeVisible()
  await page
    .locator('input[type="file"]')
    .setInputFiles({ name: '新文件.txt', mimeType: 'text/plain', buffer: Buffer.from('内容') })
  await expect(page.getByText('文件已上传，最新文档列表尚未读取。')).toBeVisible()
  const upload = page.getByRole('button', { name: '上传文件' }).and(page.locator('button'))
  await expect(upload).toBeDisabled()
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByText('文件已上传，最新文档列表尚未读取。')).toHaveCount(0)
  await expect(page.getByText('新文件', { exact: true })).toBeVisible()
  await expect(upload).toBeEnabled()
  expect(uploads).toBe(1)
  fixture.assertHandled()
})

test('上传响应中断后刷新页面，可按原请求核对成功回执', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  fixture.setHook(async (_request, url) => {
    if (url.pathname === '/api/v1/knowledge/bases/detail')
      return { data: { id: 'kb-1', name: '知识库' } }
    if (url.pathname === '/api/v1/knowledge/bases/revisions') return { data: { items: [] } }
    if (url.pathname === '/api/v1/knowledge/bases/rebuilds/detail')
      return { data: { status: 'IDLE', write_locked: false } }
    if (url.pathname === '/api/v1/knowledge/bases/documents')
      return { data: { total: 0, items: [] } }
    return undefined
  })
  let requestId = '',
    uploads = 0
  await page.route('**/api/v1/knowledge/bases/documents/upload**', async (route) => {
    const url = new URL(route.request().url())
    if (route.request().method() === 'POST') {
      uploads++
      requestId = url.searchParams.get('request_id') ?? ''
      await route.fulfill({ status: 503, json: { code: 'TEMPORARY', message: '响应中断' } })
    } else {
      expect(url.searchParams.get('request_id')).toBe(requestId)
      await route.fulfill({
        json: {
          code: 'OK',
          message: 'OK',
          trace_id: 'fixture',
          data: {
            request_id: requestId,
            status: 'SUCCEEDED',
            document: { document_id: 'doc-1', source_version: 'v1', chunks: 1 }
          }
        }
      })
    }
  })
  await page.goto('/admin/knowledge/kb-1')
  await page
    .locator('input[type="file"]')
    .setInputFiles({ name: '记录.txt', mimeType: 'text/plain', buffer: Buffer.from('内容') })
  await expect(page.getByText(/上传结果尚未确认/)).toBeVisible()
  await page.reload()
  await expect(page.getByText(/上传结果尚未确认/)).toBeVisible()
  await page.getByRole('button', { name: '核对上传回执' }).click()
  await expect(page.getByText(/上传结果尚未确认/)).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '上传文件' }).and(page.locator('button'))
  ).toBeEnabled()
  expect(uploads).toBe(1)
  fixture.assertHandled()
})

test('从运行页保存无效智能体时定位到可见的必填字段', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 500 })
  const fixture = await mockConsole(page, 'admin')
  const agent = fixture.agents[0]!
  fixture.setHook(async (_request, url) => {
    if (url.pathname === '/api/v1/agents/detail')
      return {
        data: {
          ...agent,
          system_prompt: '处理机构任务',
          model_config_id: null,
          execution_config: {},
          delegation_config: { enabled: false, target_agent_ids: [] },
          mount_set_generation: 1,
          mounts: []
        }
      }
    if (url.pathname === '/api/v1/settings/available-models') return { data: [] }
    return undefined
  })
  await page.goto(`/admin/agents/${agent.id}`)
  const name = page
    .locator('.el-form-item')
    .filter({ has: page.getByText('名称', { exact: true }) })
    .first()
  await name.locator('input').fill('')
  await page.getByRole('tab', { name: '运行与协作' }).click()
  await page.getByRole('button', { name: '保存草稿' }).click()
  await expect(page.getByRole('tab', { name: '基本配置' })).toHaveAttribute('aria-selected', 'true')
  await expect(name.getByText('请输入名称')).toBeVisible()
  const rect = await name.boundingBox()
  expect(rect).not.toBeNull()
  expect(rect!.y).toBeGreaterThanOrEqual(0)
  expect(rect!.y + rect!.height).toBeLessThanOrEqual(500)
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('机构复杂目录：47个多版本Agent、筛选、真实控件帮助和不写入的抽屉校验', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  await page.goto('/admin/agents')
  await expect(page.getByRole('heading', { name: '智能体', exact: true })).toBeVisible()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(47)
  await expect(page.getByText('尚未发布', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('待发布', { exact: true }).first()).toBeVisible()
  await assertVisibleDescriptions(page.locator('.admin-filters'))
  await page.getByPlaceholder('搜索名称或标识').fill('智能体-3')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(10)
  await expect(page.locator('.el-table__body tbody tr').first()).toContainText(
    fixture.agents[30]!.name
  )
  await page.getByRole('button', { name: '新建智能体', exact: true }).click()
  const drawer = page.getByRole('dialog')
  await expect(drawer).toBeVisible()
  await assertVisibleDescriptions(drawer)
  await drawer.getByRole('button', { name: '创建并配置', exact: true }).click()
  await expect(drawer.getByText('请输入智能体名称', { exact: true })).toBeVisible()
  expect(fixture.requests.filter((r) => r.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('记忆复杂结构：137事实上限、三维交集、嵌套JSON纠正冲突保留草稿', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  await page.goto('/admin/memory')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(100)
  const toolbar = page.locator('.admin-filters'),
    selects = toolbar.locator('.el-select')
  await assertVisibleDescriptions(toolbar)
  await selects.nth(0).locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '用户与智能体', exact: true }).click()
  await selects.nth(1).locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: fixture.agents[0]!.name, exact: true }).click()
  await selects.nth(2).locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: fixture.endUsers[0]!.display_name, exact: true }).click()
  await expect(page.getByText('筛选条件已更改，请点击“查询”应用。')).toBeVisible()
  await expect(page.getByText(/当前结果：范围 全部 · 智能体 全部/)).toBeVisible()
  await toolbar.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(2)
  await expect(page.getByText('筛选条件已更改，请点击“查询”应用。')).toHaveCount(0)
  await expect(page.getByText(new RegExp('智能体 ' + fixture.agents[0]!.id))).toBeVisible()
  const query = fixture.requests.filter((r) => r.path === '/memories').at(-1)!.query
  expect(new URLSearchParams(query).get('scope')).toBe('USER_AGENT')
  expect(new URLSearchParams(query).get('agent_id')).toBe(fixture.agents[0]!.id)
  expect(new URLSearchParams(query).get('end_user_id')).toBe('user-0')
  await page
    .locator('.el-table__body tbody tr')
    .filter({ hasText: '关系-000' })
    .getByRole('button', { name: '纠正', exact: true })
    .click()
  const drawer = page.getByRole('dialog')
  await assertVisibleDescriptions(drawer)
  const draft =
    '{"preferences":{"projects":[{"code":"A","tags":["中文","审计"]},{"code":"B","tags":[]}]},"version":8}'
  await drawer.locator('.cm-content').fill(draft)
  fixture.setHook(async (_request, url) =>
    url.pathname.endsWith('/memories/correct')
      ? { status: 409, code: 'REVISION_CONFLICT' }
      : undefined
  )
  await drawer.getByRole('button', { name: '保存纠正', exact: true }).click()
  await expect(drawer).toBeVisible()
  await expect(drawer.locator('.cm-content')).toHaveText(draft)
  const command = fixture.requests.find((r) => r.path === '/memories/correct')!
  expect(command.body).toMatchObject({
    fact_id: 'fact-0',
    payload: { expected_revision: 4, object_json: draft }
  })
  expect(fixture.requests.filter((r) => r.path === '/memories/correct')).toHaveLength(1)
  fixture.assertHandled()
})

test('机构只读复杂记忆：嵌套事实可读，保存审核状态写入口无权且无副作用', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin', ['foundation.memory.read'])
  await page.goto('/admin/memory')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(100)
  await expect(page.getByRole('button', { name: '批准', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '拒绝', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '遗忘', exact: true })).toHaveCount(0)
  await page
    .locator('.el-table__body tbody tr')
    .first()
    .getByRole('button', { name: '纠正', exact: true })
    .click()
  await expect(page.getByRole('dialog').locator('.cm-content')).toContainText('projects')
  await expect(page.getByRole('button', { name: '保存纠正', exact: true })).toHaveCount(0)
  expect(fixture.requests.filter((r) => r.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('记忆审核复杂序列：28待审事实批准拒绝、修订与幂等头精确，刷新不重发', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  fixture.setHook(async (request, url) => {
    if (!url.pathname.endsWith('/memories/review')) return undefined
    const command = request.postDataJSON() as {
      fact_id: string
      payload: { expected_revision: number; approved: boolean }
    }
    expect(request.headers()['idempotency-key']).toBeTruthy()
    const fact = fixture.facts.find((item) => item.ref.fact_id === command.fact_id)!
    expect(command.payload.expected_revision).toBe(fact.revision)
    fact.review_required = false
    fact.status = command.payload.approved ? 'ACTIVE' : 'REJECTED'
    fact.revision++
    return { data: fact }
  })
  await page.goto('/admin/memory')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(100)
  const toolbar = page.locator('.admin-filters')
  await toolbar.locator('.el-switch__core').click()
  await toolbar.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(28)
  await page
    .locator('.el-table__body tbody tr')
    .filter({ hasText: '关系-000' })
    .getByRole('button', { name: '批准', exact: true })
    .click()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(27)
  await page
    .locator('.el-table__body tbody tr')
    .filter({ hasText: '关系-005' })
    .getByRole('button', { name: '拒绝', exact: true })
    .click()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(26)
  await toolbar.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(26)
  expect(fixture.facts[0]).toMatchObject({ review_required: false, revision: 5, status: 'ACTIVE' })
  expect(fixture.facts[5]).toMatchObject({
    review_required: false,
    revision: 6,
    status: 'REJECTED'
  })
  expect(fixture.requests.filter((request) => request.path === '/memories/review')).toHaveLength(2)
  fixture.assertHandled()
})

test('记忆乱序复杂查询：USER请求在途离页，迟到45条不能污染回访100条目录', async ({ page }) => {
  const fixture = await mockConsole(page, 'admin')
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  fixture.setHook(async (_request, url) => {
    if (url.pathname.endsWith('/memories') && url.searchParams.get('scope') === 'USER') {
      await gate
      return { data: fixture.facts.filter((fact) => fact.scope === 'USER') }
    }
    return undefined
  })
  await page.goto('/admin/memory')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(100)
  const toolbar = page.locator('.admin-filters'),
    scope = toolbar.locator('.el-select').first()
  await scope.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '用户', exact: true }).click()
  await toolbar.getByRole('button', { name: '查询', exact: true }).click()
  await expect
    .poll(() =>
      fixture.requests.some((r) => r.path === '/memories' && r.query.includes('scope=USER&'))
    )
    .toBe(true)
  try {
    await page.getByRole('menuitem', { name: '智能体', exact: true }).click()
    await expect(page.locator('.el-table__body tbody tr')).toHaveCount(47)
    await page.getByRole('menuitem', { name: '记忆管理', exact: true }).click()
    await expect(page.locator('.el-table__body tbody tr')).toHaveCount(100)
    const oldResponse = page.waitForResponse((response) => response.url().includes('scope=USER&'))
    release()
    await (await oldResponse).finished()
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    )
    await expect(page.locator('.el-table__body tbody tr').first()).toContainText('关系-000')
    await expect(page.locator('.el-table__body tbody tr')).toHaveCount(100)
    await expect(toolbar.getByRole('button', { name: '查询', exact: true })).not.toHaveClass(
      /is-loading/
    )
    fixture.assertHandled()
  } finally {
    release()
  }
})
