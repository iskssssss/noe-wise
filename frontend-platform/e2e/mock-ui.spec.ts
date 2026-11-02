import { test, expect } from '@playwright/test'
import {
  assertVisibleDescriptions,
  mockConsole
} from '../../frontend-admin/e2e/support/mock-console'

test('模型标识支持直接回车输入并建议名称', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'foundation.models.create',
    'foundation.models.read'
  ])
  await page.goto('/platform/models/new')
  const input = page.getByRole('combobox', { name: '模型标识' })
  await input.fill('deepseek-flash')
  await expect(page.getByRole('option', { name: 'deepseek-flash' })).toBeVisible()
  await input.press('Enter')
  await expect(page.locator('.el-select__selected-item').getByText('deepseek-flash')).toBeVisible()
  await expect(page.getByLabel('名称', { exact: true })).toHaveValue('deepseek-flash')
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
})

test('模板标签支持直接回车添加', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'platform.agent_definitions.read',
    'platform.agent_definitions.create',
    'foundation.models.read'
  ])
  await page.goto('/platform/agent-templates/new')
  await page.getByRole('button', { name: /目录展示/ }).click()
  const input = page.getByRole('combobox', { name: '标签' })
  await input.fill('业务标签')
  await expect(page.getByRole('option', { name: '业务标签' })).toBeVisible()
  await input.press('Enter')
  await expect(page.locator('.el-tag').getByText('业务标签', { exact: true })).toBeVisible()
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
})

test('命名空间候选支持直接回车选择', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'platform.institutions.read',
    'platform.institutions.associate'
  ])
  fixture.setHook(async (_request, url) => {
    if (url.pathname.endsWith('/platform/institutions')) return { data: { items: [], total: 0 } }
    if (url.pathname.endsWith('/platform/operation-options/namespaces'))
      return { data: { items: [], total: 0, allow_create: true } }
    return undefined
  })
  await page.goto('/platform/institutions')
  await page.getByRole('button', { name: '关联已有机构' }).click()
  const drawer = page.getByRole('dialog', { name: '关联已有机构' })
  const input = drawer.getByRole('combobox', { name: '命名空间' })
  await input.fill('keyboard-example')
  await expect(page.getByRole('option', { name: 'keyboard-example' })).toBeVisible()
  await input.dispatchEvent('keydown', {
    key: 'Enter',
    code: 'Enter',
    isComposing: true,
    keyCode: 229
  })
  await expect(input).toHaveAttribute('aria-expanded', 'true')
  expect(fixture.requests.filter((item) => item.method === 'POST')).toHaveLength(0)
  await input.press('Enter')
  await expect(
    drawer.locator('.el-select__selected-item').getByText('keyboard-example')
  ).toBeVisible()
  await expect(input).toBeFocused()
  expect(fixture.requests.filter((item) => item.method === 'POST')).toHaveLength(0)
})

test('管理身份多选命名空间可直接回车添加', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'platform.management_principals.read',
    'platform.management_principals.create',
    'platform.institutions.provision'
  ])
  fixture.setHook(async (_request, url) => {
    if (url.pathname.endsWith('/platform/management-principals'))
      return { data: { items: [], total: 0 } }
    if (url.pathname.includes('/platform/operation-options/'))
      return { data: { items: [], total: 0, allow_create: true } }
    return undefined
  })
  await page.goto('/platform/management-principals')
  await page.getByRole('button', { name: '创建管理身份' }).click()
  const drawer = page.getByRole('dialog')
  const input = drawer.getByRole('combobox', { name: '目标命名空间' })
  await input.fill('keyboard-example')
  await expect(page.getByRole('option', { name: 'keyboard-example' })).toBeVisible()
  await input.press('Enter')
  await expect(
    drawer.locator('.el-tag').getByText('keyboard-example', { exact: true })
  ).toBeVisible()
  await expect(input).toBeFocused()
  await input.fill('second-space')
  await expect(page.getByRole('option', { name: 'second-space' })).toBeVisible()
  await input.press('Enter')
  await expect(drawer.locator('.el-tag').getByText('second-space', { exact: true })).toBeVisible()
  await expect(input).toBeFocused()
  expect(fixture.requests.filter((item) => item.method === 'POST')).toHaveLength(0)
})

test('已有命名空间仍使用候选项身份', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'platform.institutions.read',
    'platform.institutions.associate'
  ])
  fixture.setHook(async (_request, url) => {
    if (url.pathname.endsWith('/platform/institutions')) return { data: { items: [], total: 0 } }
    if (url.pathname.endsWith('/platform/operation-options/namespaces'))
      return {
        data: {
          items: [{ id: 'existing-id', label: '已有命名空间' }],
          total: 1,
          allow_create: false
        }
      }
    return undefined
  })
  await page.goto('/platform/institutions')
  await page.getByRole('button', { name: '关联已有机构' }).click()
  const drawer = page.getByRole('dialog', { name: '关联已有机构' })
  const input = drawer.getByRole('combobox', { name: '命名空间' })
  await input.fill('已有命名空间')
  await expect(page.getByRole('option', { name: '已有命名空间' })).toBeVisible()
  await expect(input).toHaveAttribute('aria-activedescendant', /.+/)
  await input.press('Enter')
  await expect(drawer.locator('.el-select__selected-item').getByText('已有命名空间')).toBeVisible()
})

test('机构原生智能体部署自动生成标识', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'platform.agent_deployments.read',
    'platform.agent_deployments.create',
    'platform.agent_grants.read'
  ])
  fixture.setHook(async (request, url) => {
    const path = url.pathname
    if (path === '/api/v1/platform/operation-options/tenants')
      return {
        data: { items: [{ id: '41', label: '验收机构' }], total: 1, page: 1, page_size: 200 }
      }
    if (path === '/api/v1/platform/operation-options/integrations')
      return { data: { items: [], total: 0, page: 1, page_size: 200 } }
    if (path === '/api/v1/platform/agent-deployments/definitions')
      return { data: { items: [{ id: '31', name: '公共助手', version: '1.0.0' }], total: 1 } }
    if (path === '/api/v1/platform/agent-deployments/grants')
      return {
        data: {
          items: [
            {
              grant_id: '21',
              target_tenant_id: '41',
              definition_id: '31',
              definition_name: '公共助手',
              actions: ['deploy', 'execute'],
              status: 'active',
              expires_at: null
            }
          ],
          total: 1
        }
      }
    if (path === '/api/v1/platform/agent-deployments' && request.method() === 'POST') {
      const body = request.postDataJSON()
      return {
        data: {
          ...body.payload,
          tenant_id: body.tenant_id,
          agent_id: '61',
          deployment_id: '51',
          definition_version: '1.0.0',
          deployment_revision: 1,
          ready: true,
          issues: []
        }
      }
    }
    if (path === '/api/v1/platform/agent-deployments' && request.method() === 'GET')
      return { data: { items: [], total: 0 } }
    return undefined
  })
  await page.goto('/platform/tenants/41?section=agents&view=setup')
  const definition = page.locator('.el-form-item').filter({ hasText: '公共定义' })
  await definition.locator('.el-select__wrapper').click()
  await page.getByRole('option').filter({ hasText: '公共助手' }).click()
  await expect(page.getByText('已预选唯一有效授权，提交时将使用此授权。')).toBeVisible()
  await expect(page.getByText('未选择外部接入时，系统会自动生成实例标识。')).toBeVisible()
  await page.getByRole('button', { name: '部署智能体', exact: true }).click()
  await expect
    .poll(
      () =>
        fixture.requests.filter(
          (item) => item.method === 'POST' && item.path === '/platform/agent-deployments'
        ).length
    )
    .toBe(1)
  const write = fixture.requests.find(
    (item) => item.method === 'POST' && item.path === '/platform/agent-deployments'
  )!
  expect(write.body).toMatchObject({
    tenant_id: '41',
    payload: { definition_id: '31', grant_id: '21', integration_id: null }
  })
  expect(
    (write.body as { payload: { external_agent_ref: string } }).payload.external_agent_ref
  ).toMatch(/^agent-[0-9a-f-]{36}$/)
  fixture.assertHandled()
})

test('草稿未保存时可调试已发布系统智能体', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'foundation.system_agents.read',
    'foundation.system_agents.update',
    'foundation.system_agents.run',
    'foundation.models.read'
  ])
  const config = {
    name: '订单助手',
    description: '',
    system_prompt: '原始提示',
    model_config_id: null,
    timeout_seconds: 30,
    max_business_model_calls: 2,
    mounts: []
  }
  fixture.setHook(async (_request, url) => {
    if (url.pathname.endsWith('/admin/system-agents/detail'))
      return {
        data: {
          id: '901',
          agent_key: 'agent',
          execution_mode: 'AGENT_RUN',
          status: 'published',
          revision: 7,
          published_version: 3,
          draft: config,
          published: config
        }
      }
    if (
      url.pathname.endsWith('/admin/system-agents/versions') ||
      url.pathname.endsWith('/admin/system-agents/runs')
    )
      return { data: [] }
    if (url.pathname.endsWith('/admin/system-agents/mount-options'))
      return { data: { skills: [], tools: [], deployments: [] } }
    if (url.pathname.endsWith('/admin/system-agents/debug'))
      return {
        data: {
          id: 'run-1',
          agent_id: '901',
          status: 'COMPLETED',
          output: '模拟结果',
          duration_ms: 1,
          input_tokens: 1,
          output_tokens: 1
        }
      }
    return undefined
  })
  await page.goto('/platform/system-agents/901')
  const name = page
    .locator('.el-form-item')
    .filter({ has: page.getByText('名称', { exact: true }) })
    .locator('input')
  await name.fill('未保存名称')
  await page.getByRole('tab', { name: '调试与运行' }).click()
  await page.getByRole('textbox', { name: '调试输入' }).fill('测试输入')
  await expect(page.getByRole('button', { name: '运行调试' })).toBeDisabled()
  await page.getByText('已发布版本', { exact: true }).click()
  await expect(page.getByText('本次调试使用已发布版本，不包含当前未保存的草稿修改。')).toBeVisible()
  await page.getByRole('button', { name: '运行调试' }).click()
  await expect(page.getByText('模拟结果')).toBeVisible()
  const writes = fixture.requests.filter((item) => item.method !== 'GET')
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({
    path: '/admin/system-agents/debug',
    body: { agent_id: '901', expected_revision: 7, source: 'PUBLISHED', input: '测试输入' }
  })
  await page.getByRole('tab', { name: '配置草稿' }).click()
  await expect(name).toHaveValue('未保存名称')
  fixture.assertHandled()
})

test('选择系统工具时仅唯一可用部署自动选中，多环境仍由用户选择', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'foundation.system_agents.read',
    'foundation.system_agents.update',
    'foundation.models.read'
  ])
  fixture.setHook(async (_request, url) => {
    if (
      url.pathname.endsWith('/admin/system-agents/versions') ||
      url.pathname.endsWith('/admin/system-agents/runs')
    )
      return { data: [] }
    if (url.pathname.endsWith('/admin/system-agents/detail'))
      return {
        data: {
          id: '901',
          agent_key: 'agent',
          execution_mode: 'AGENT_RUN',
          status: 'draft',
          revision: 7,
          draft: {
            name: '智能体',
            description: '',
            system_prompt: '原提示词',
            model_config_id: null,
            timeout_seconds: 30,
            max_business_model_calls: 2,
            mounts: [
              {
                capability_type: 'TOOL',
                capability_definition_id: '',
                priority: 0,
                token_budget: null,
                activation_mode: null,
                tool_deployment_id: null
              }
            ]
          },
          published: null
        }
      }
    if (url.pathname.endsWith('/admin/system-agents/mount-options'))
      return {
        data: {
          skills: [],
          tools: [
            { id: '21', name: '订单查询' },
            { id: '22', name: '客户查询' }
          ],
          deployments: [
            {
              id: '101',
              name: '订单生产',
              tool_definition_id: '21',
              tool_version_id: '31',
              environment: 'PRODUCTION'
            },
            {
              id: '102',
              name: '客户测试',
              tool_definition_id: '22',
              tool_version_id: '32',
              environment: 'TEST'
            },
            {
              id: '103',
              name: '客户生产',
              tool_definition_id: '22',
              tool_version_id: '32',
              environment: 'PRODUCTION'
            }
          ]
        }
      }
    return undefined
  })
  await page.goto('/platform/system-agents/901')
  const capability = page.locator('.el-form-item').filter({ hasText: '能力（跟随当前发布）' })
  const deployment = page.locator('.el-form-item').filter({ hasText: '工具部署' }).first()
  await capability.getByRole('combobox').click()
  await page.getByRole('option', { name: '订单查询', exact: true }).click()
  await expect(deployment).toContainText('订单生产 · PRODUCTION')
  await capability.getByRole('combobox').click()
  await page.getByRole('option', { name: '客户查询', exact: true }).click()
  await expect(deployment).not.toContainText('订单生产 · PRODUCTION')
  await deployment.locator('.el-select__wrapper').click()
  await expect(page.getByRole('option', { name: '客户测试 · TEST', exact: true })).toBeVisible()
  await expect(
    page.getByRole('option', { name: '客户生产 · PRODUCTION', exact: true })
  ).toBeVisible()
  expect(fixture.requests.filter((item) => item.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('系统智能体编辑中配置工具部署后明确回填，保留未保存草稿', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'foundation.system_agents.read',
    'foundation.system_agents.update',
    'foundation.system_agent_tools.read',
    'foundation.system_agent_tools.create',
    'foundation.system_agent_tools.update',
    'foundation.system_agent_tools.publish',
    'foundation.capabilities.read',
    'foundation.models.read'
  ])
  const draft = {
    name: '智能体',
    description: '',
    system_prompt: '原提示词',
    model_config_id: null,
    timeout_seconds: 30,
    max_business_model_calls: 2,
    mounts: [
      {
        capability_type: 'TOOL',
        capability_definition_id: '21',
        priority: 0,
        token_budget: null,
        activation_mode: null,
        tool_deployment_id: null
      }
    ]
  }
  const deployment = {
    id: '101',
    name: '查询工具 · 测试',
    revision: 1,
    tool_definition_id: '21',
    tool_version_id: '31',
    provider_type: 'PLATFORM',
    provider_ref: 'customer_search',
    environment: 'TEST',
    active: true,
    has_unpublished_changes: true,
    published_revision: null
  }
  let published = false
  fixture.setHook(async (request, url) => {
    const path = url.pathname
    if (path.endsWith('/admin/system-agents/detail'))
      return {
        data: {
          id: '901',
          agent_key: 'agent',
          execution_mode: 'AGENT_RUN',
          status: 'draft',
          revision: 7,
          draft,
          published: null
        }
      }
    if (path.endsWith('/admin/system-agents/mount-options'))
      return {
        data: {
          skills: [],
          tools: [{ id: '21', name: '查询工具' }],
          deployments: published ? [deployment] : []
        }
      }
    if (
      path.endsWith('/admin/system-agents/versions') ||
      path.endsWith('/admin/system-agents/runs')
    )
      return { data: [] }
    if (path.endsWith('/admin/capabilities/definitions/detail'))
      return {
        data: {
          id: '21',
          tenant_id: null,
          capability_type: 'TOOL',
          capability_key: 'search',
          name: '查询工具',
          status: 'PUBLISHED',
          revision: 1,
          default_version_id: '31'
        }
      }
    if (path.endsWith('/admin/capabilities/versions/detail'))
      return {
        data: {
          id: '31',
          definition_id: '21',
          tenant_id: null,
          capability_type: 'TOOL',
          version_key: 'v1',
          name: '查询工具',
          status: 'PUBLISHED',
          descriptor: { tool_name: 'customer_search' }
        }
      }
    if (path.endsWith('/admin/system-agent-tools/publish')) {
      published = true
      return { data: { ...deployment, has_unpublished_changes: false, published_revision: 1 } }
    }
    if (path.endsWith('/admin/system-agent-tools'))
      return { data: request.method() === 'POST' ? deployment : [] }
    return undefined
  })
  await page.goto('/platform/system-agents/901')
  const prompt = page.getByRole('textbox', { name: '系统提示词' })
  await prompt.fill('尚未保存的提示词')
  await page.getByRole('button', { name: '配置此工具部署' }).click()
  const drawer = page.locator('.el-drawer').first()
  await expect(drawer.getByText('平台工具：customer_search')).toBeVisible()
  await drawer.getByRole('button', { name: '保存并继续发布' }).click()
  await page
    .getByRole('dialog', { name: '发布工具部署' })
    .getByRole('button', { name: '发布', exact: true })
    .click()
  await expect(drawer.getByText('当前草稿与发布一致。')).toBeVisible()
  await drawer.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.getByRole('button', { name: '使用此部署' })).toBeVisible()
  await expect(prompt).toHaveValue('尚未保存的提示词')
  await page.getByRole('button', { name: '使用此部署' }).click()
  await expect(
    page.getByRole('tabpanel', { name: '配置草稿' }).getByText('查询工具 · 测试 · TEST')
  ).toBeVisible()
  expect(
    fixture.requests.filter(
      (r) => r.path === '/admin/system-agents/update' || r.path === '/admin/system-agents/publish'
    )
  ).toEqual([])
  fixture.assertHandled()
})

test('系统工具部署保存后在原抽屉确认发布原修订', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'foundation.system_agent_tools.read',
    'foundation.system_agent_tools.create',
    'foundation.system_agent_tools.update',
    'foundation.system_agent_tools.publish',
    'foundation.system_agents.read',
    'foundation.capabilities.read'
  ])
  const draft = {
    id: '101',
    name: '查询客户 · 测试',
    revision: 4,
    tool_definition_id: '21',
    tool_version_id: '31',
    provider_type: 'PLATFORM',
    provider_ref: 'customer_search',
    environment: 'TEST',
    active: true,
    has_unpublished_changes: true,
    published_revision: null
  }
  fixture.setHook(async (request, url) => {
    const path = url.pathname
    if (path.endsWith('/admin/system-agents/mount-options'))
      return { data: { skills: [], tools: [{ id: '21', name: '查询客户' }], deployments: [] } }
    if (path.endsWith('/admin/capabilities/definitions/detail'))
      return {
        data: {
          id: '21',
          tenant_id: null,
          capability_type: 'TOOL',
          capability_key: 'search',
          name: '查询客户',
          status: 'PUBLISHED',
          revision: 1,
          default_version_id: '31'
        }
      }
    if (path.endsWith('/admin/capabilities/versions/detail'))
      return {
        data: {
          id: '31',
          definition_id: '21',
          tenant_id: null,
          capability_type: 'TOOL',
          version_key: 'v1',
          name: '查询客户',
          status: 'PUBLISHED',
          descriptor: { tool_name: 'customer_search' }
        }
      }
    if (path.endsWith('/admin/system-agent-tools/publish'))
      return { data: { ...draft, has_unpublished_changes: false, published_revision: 1 } }
    if (path.endsWith('/admin/system-agent-tools'))
      return { data: request.method() === 'POST' ? draft : [] }
    return undefined
  })
  await page.goto('/platform/system-agent-tools')
  await page.getByRole('button', { name: '新增部署' }).click()
  const drawer = page.locator('.el-drawer').first()
  await drawer
    .getByRole('combobox', { name: '工具（跟随当前发布）' })
    .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")]')
    .click()
  await page.getByRole('option', { name: '查询客户' }).click()
  await expect(drawer.getByText('平台工具：customer_search')).toBeVisible()
  await drawer.getByRole('button', { name: '保存并继续发布' }).click()
  const confirmation = page.getByRole('dialog', { name: '发布工具部署' })
  await expect(confirmation).toContainText('测试环境的“查询客户 · 测试”')
  await confirmation.getByRole('button', { name: '取消' }).click()
  await expect(drawer.getByText(/草稿修订 4/)).toBeVisible()
  await drawer.getByRole('button', { name: '继续发布' }).click()
  await page
    .getByRole('dialog', { name: '发布工具部署' })
    .getByRole('button', { name: '发布', exact: true })
    .click()
  await expect(drawer.getByText(/当前草稿与发布一致/)).toBeVisible()
  expect(fixture.requests.filter((r) => r.method === 'POST').map((r) => [r.path, r.body])).toEqual([
    ['/admin/system-agent-tools', expect.objectContaining({ tool_definition_id: '21' })],
    ['/admin/system-agent-tools/publish', { id: '101', expected_revision: 4 }]
  ])
  fixture.assertHandled()
})

test('平台凭证签发前逐项展示权限，取消保留选择', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'platform.institution_credentials.read',
    'platform.institution_credentials.issue'
  ])
  fixture.setHook(async (request, url) => {
    if (
      url.pathname.endsWith('/platform/institution-credentials/issue') &&
      request.method() === 'POST'
    )
      return { data: { principal: {} } }
    if (url.pathname.endsWith('/platform/institution-credentials'))
      return { data: { items: [], total: 0, page: 1, page_size: 20 } }
    if (url.pathname.includes('/platform/operation-options/'))
      return { data: { items: [], total: 0, page: 1, page_size: 200 } }
    return undefined
  })
  await page.goto('/platform/institution-credentials?tenant_id=77&integration_id=i-1')
  await expect(page.getByText('当前已查询执行域：77 / i-1')).toBeVisible()
  await page
    .getByRole('combobox', { name: '执行权限' })
    .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")]')
    .click()
  await page.getByRole('option', { name: '读取会话 · sessions.read' }).click()
  await page.getByRole('option', { name: '读取用量 · usage.read' }).click()
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '签发执行凭证' }).click()
  const box = page.getByRole('dialog', { name: '签发机构执行凭证' })
  const expectCentered = async () => {
    await expect
      .poll(async () => {
        const rect = await box.boundingBox()
        const viewport = page.viewportSize()!
        return rect
          ? Math.max(
              Math.abs(rect.x + rect.width / 2 - viewport.width / 2),
              Math.abs(rect.y + rect.height / 2 - viewport.height / 2)
            )
          : Infinity
      })
      .toBeLessThan(3)
  }
  await expect(box).toContainText('机构 77 的接入 i-1')
  await expect(box).toContainText('读取会话（sessions.read）')
  await expect(box).toContainText('读取用量（usage.read）')
  const lines = await box.locator('.el-message-box__message').evaluate((el) => {
    const text = el.textContent ?? ''
    const node = el.querySelector('p')!.firstChild!
    const range = document.createRange()
    const first = text.indexOf('读取会话'),
      second = text.indexOf('读取用量')
    range.setStart(node, first)
    range.setEnd(node, first + 1)
    const top = range.getBoundingClientRect().top
    range.setStart(node, second)
    range.setEnd(node, second + 1)
    return {
      whiteSpace: getComputedStyle(el).whiteSpace,
      separated: range.getBoundingClientRect().top > top
    }
  })
  expect(lines).toEqual({ whiteSpace: 'pre-wrap', separated: true })
  await expectCentered()
  await expect(box.getByRole('button', { name: '签发', exact: true })).toBeInViewport()
  await expect(box.getByRole('button', { name: '取消' })).toBeInViewport()
  await box.getByRole('button', { name: '取消' }).click()
  await expect(page.locator('#main-content').getByText('读取会话 · sessions.read')).toBeVisible()
  await expect(page.locator('#main-content').getByText('读取用量 · usage.read')).toBeVisible()
  await page.setViewportSize({ width: 390, height: 600 })
  await page
    .getByRole('combobox', { name: '执行权限' })
    .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")]')
    .click()
  for (const option of await page.getByRole('option').all()) {
    if ((await option.getAttribute('aria-selected')) !== 'true') await option.click()
  }
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '签发执行凭证' }).click()
  const longBox = page.getByRole('dialog', { name: '签发机构执行凭证' })
  await expectCentered()
  await expect(longBox).toContainText('登记或退役群发送端（groups.senders.manage）')
  await expect(longBox.getByRole('button', { name: '签发', exact: true })).toBeInViewport()
  await expect(longBox.getByRole('button', { name: '取消' })).toBeInViewport()
  const scrollArea = longBox.locator('.el-message-box__content')
  expect(await scrollArea.evaluate((el) => el.scrollHeight > el.clientHeight)).toBe(true)
  await scrollArea.evaluate((el) => {
    el.scrollTop = el.scrollHeight
  })
  await expect(
    longBox.getByText('登记或退役群发送端（groups.senders.manage）', { exact: false })
  ).toBeVisible()
  await longBox.getByRole('button', { name: '取消' }).click()
  expect(fixture.requests.filter((r) => r.method !== 'GET')).toEqual([])
  await page.getByRole('button', { name: '签发执行凭证' }).click()
  await page
    .getByRole('dialog', { name: '签发机构执行凭证' })
    .getByRole('button', { name: '签发', exact: true })
    .click()
  await expect(page.getByRole('dialog', { name: '一次性交付执行凭证' })).toBeVisible()
  expect(
    fixture.requests.filter((r) => r.path === '/platform/institution-credentials/issue')
  ).toHaveLength(1)
  fixture.assertHandled()
})

test('平台机构部署：唯一可部署定义与授权自动选择', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'platform.agent_deployments.read',
    'platform.agent_deployments.create'
  ])
  fixture.setHook(async (_request, url) => {
    if (url.pathname.endsWith('/platform/operation-options/tenants'))
      return {
        data: { items: [{ id: '77', label: '测试机构' }], total: 1, page: 1, page_size: 200 }
      }
    if (url.pathname.endsWith('/platform/operation-options/integrations'))
      return { data: { items: [], total: 0, page: 1, page_size: 200 } }
    if (url.pathname.endsWith('/platform/agent-deployments/definitions'))
      return {
        data: {
          items: [
            { id: '91', name: '可用助手', version: '1' },
            { id: '92', name: '未授权助手', version: '1' }
          ],
          total: 2
        }
      }
    if (url.pathname.endsWith('/platform/agent-deployments/grants'))
      return {
        data: {
          items: [
            {
              grant_id: 'g1',
              definition_id: '91',
              definition_name: '可用助手',
              target_tenant_id: '77',
              status: 'active',
              actions: ['deploy', 'execute'],
              expires_at: null
            }
          ],
          total: 1
        }
      }
    if (url.pathname.endsWith('/platform/agent-deployments'))
      return { data: { items: [], total: 0 } }
    return undefined
  })
  await page.goto('/platform/agent-deployments')
  await page.getByRole('combobox', { name: '目标机构' }).click()
  await page.getByRole('option', { name: '测试机构' }).click()
  await expect(page.getByText('已预选唯一有有效授权的公共定义。')).toBeVisible()
  await expect(page.getByText('已预选唯一有效授权，提交时将使用此授权。')).toBeVisible()
  expect(fixture.requests.filter((r) => r.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('平台机构部署：唯一有效授权自动选择，多条授权和切换定义须重新选择', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', [
    'platform.agent_deployments.read',
    'platform.agent_deployments.create'
  ])
  const grant = (id: string, definition: string) => ({
    grant_id: id,
    definition_id: definition,
    definition_name: definition === '91' ? '单授权助手' : '多授权助手',
    target_tenant_id: '77',
    status: 'active',
    actions: ['deploy', 'execute'],
    expires_at: null
  })
  fixture.setHook(async (_request, url) => {
    if (url.pathname.endsWith('/platform/operation-options/tenants'))
      return {
        data: { items: [{ id: '77', label: '测试机构' }], total: 1, page: 1, page_size: 200 }
      }
    if (url.pathname.endsWith('/platform/operation-options/integrations'))
      return { data: { items: [], total: 0, page: 1, page_size: 200 } }
    if (url.pathname.endsWith('/platform/agent-deployments/definitions'))
      return {
        data: {
          items: [
            { id: '91', name: '单授权助手', version: '1' },
            { id: '92', name: '多授权助手', version: '1' }
          ],
          total: 2
        }
      }
    if (url.pathname.endsWith('/platform/agent-deployments/grants'))
      return {
        data: { items: [grant('g1', '91'), grant('g2', '92'), grant('g3', '92')], total: 3 }
      }
    if (url.pathname.endsWith('/platform/agent-deployments'))
      return { data: { items: [], total: 0 } }
    return undefined
  })
  await page.goto('/platform/agent-deployments')
  await page.getByRole('combobox', { name: '目标机构' }).click()
  await page.getByRole('option', { name: '测试机构' }).click()
  await expect
    .poll(
      () =>
        fixture.requests.filter(
          (r) => r.method === 'GET' && r.path === '/platform/agent-deployments'
        ).length
    )
    .toBe(1)
  const field = (label: string) =>
    page
      .locator('.el-form-item')
      .filter({ has: page.locator('.el-form-item__label', { hasText: label }) })
  const definition = field('公共定义')
  const authorization = field('直接授权')
  await definition.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '单授权助手 · 1' }).click()
  await expect(authorization).toContainText('单授权助手 · g1')
  await expect(authorization).toContainText('已预选唯一有效授权')
  await definition.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '多授权助手 · 1' }).click()
  await expect(authorization).not.toContainText('单授权助手 · g1')
  await expect(authorization).not.toContainText('已预选唯一有效授权')
  await authorization.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '多授权助手 · g3' }).click()
  await expect(authorization).toContainText('多授权助手 · g3')
  await definition.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '单授权助手 · 1' }).click()
  await expect(authorization).toContainText('单授权助手 · g1')
  expect(fixture.requests.filter((r) => r.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('平台复杂机构：47行分页保持已提交条件，未提交输入不改变第2页', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform')
  await page.goto('/platform/tenants')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(20)
  await assertVisibleDescriptions(page.locator('.admin-filters'))
  await page.getByPlaceholder('机构名称或标识').fill('尚未提交')
  await page.locator('.el-pagination').getByRole('button', { name: '下一页', exact: true }).click()
  await expect(page.locator('.el-table__body tbody tr').first()).toContainText(
    fixture.tenants[20]!.name
  )
  expect(fixture.requests.filter((r) => r.path === '/admin/tenants').at(-1)!.query).toContain(
    'page=2'
  )
  expect(fixture.requests.filter((r) => r.path === '/admin/tenants').at(-1)!.query).not.toContain(
    '尚未提交'
  )
  await page.getByPlaceholder('机构名称或标识').fill('复杂机构-4')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(7)
  await expect(page.locator('.el-table__body tbody tr').first()).toContainText(
    fixture.tenants[40]!.name
  )
  await page.getByRole('button', { name: '重置', exact: true }).click()
  await expect(page.locator('.el-table__body tbody tr').first()).toContainText(
    fixture.tenants[0]!.name
  )
  fixture.assertHandled()
})

test('平台多模型版本：37项三类型与提供方交集、重置、深链字段及安全校验', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform')
  await page.goto('/platform/models')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(37)
  await assertVisibleDescriptions(page.locator('.admin-filters'))
  await page.getByPlaceholder('搜索模型名称、标识或提供方').fill('provider-b')
  await page.locator('.admin-filters .el-select').first().locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '向量模型（Embedding）', exact: true }).click()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(6)
  await page.getByRole('button', { name: '重置', exact: true }).click()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(37)
  await page.getByRole('button', { name: '新增模型', exact: true }).click()
  await expect(page.getByRole('heading', { name: '新建模型', exact: true })).toBeVisible()
  const form = page.locator('form')
  await assertVisibleDescriptions(form)
  await form.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByText('请填写名称、提供方和模型标识', { exact: true })).toBeVisible()
  expect(fixture.requests.filter((r) => r.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('平台只读身份：菜单深链与创建动作受显式权限限制，切领域不发越权请求', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform', ['foundation.tenants.read'])
  await page.goto('/platform/tenants')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(20)
  await expect(page.getByRole('button', { name: '新增机构', exact: true })).toHaveCount(0)
  await page.goto('/platform/models/new')
  await expect(page).toHaveURL(/\/403$/)
  expect(fixture.requests.some((r) => r.path === '/admin/models/providers')).toBe(false)
  expect(fixture.requests.filter((r) => r.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('平台新建机构复杂抽屉：同文档导航、字段帮助、取消不提交与焦点恢复', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform')
  await page.goto('/platform/models/new')
  await expect(page.getByRole('heading', { name: '新建模型', exact: true })).toBeVisible()
  const marker = await page.evaluate(() => {
    const w = window as Window & { testMarker?: string }
    return (w.testMarker = crypto.randomUUID())
  })
  await page.getByRole('menuitem', { name: '机构管理', exact: true }).click()
  await expect(page.getByRole('heading', { name: '机构管理', exact: true })).toBeVisible()
  expect(await page.evaluate(() => (window as Window & { testMarker?: string }).testMarker)).toBe(
    marker
  )
  const create = page.getByRole('button', { name: '新增机构', exact: true })
  await create.click()
  const drawer = page.getByRole('dialog')
  await expect(drawer.getByText('管理员初始密码', { exact: true })).toBeVisible()
  await assertVisibleDescriptions(drawer)
  await drawer.getByRole('button', { name: '取消', exact: true }).click()
  await expect(drawer).toBeHidden()
  await expect(create).toBeFocused()
  expect(fixture.requests.filter((r) => r.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})

test('平台模型目录失败恢复：37个版本投影保留、明确错误且刷新只重读', async ({ page }) => {
  const fixture = await mockConsole(page, 'platform')
  await page.goto('/platform/models')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(37)
  fixture.setHook(async (_request, url) =>
    url.pathname.endsWith('/admin/models/summaries')
      ? { status: 503, code: 'CATALOG_UNAVAILABLE' }
      : undefined
  )
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(
    page.getByRole('status').filter({ hasText: '上次成功读取 37 个模型，刷新失败' })
  ).toBeVisible()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(37)
  fixture.setHook(undefined)
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: '当前 37 个模型' })).toBeVisible()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(37)
  expect(fixture.requests.filter((r) => r.method !== 'GET')).toEqual([])
  fixture.assertHandled()
})
