import { test, expect } from '@playwright/test'
import { fullPermissions, mockConsole } from '../../frontend-admin/e2e/support/mock-console'
test.use({ trace: 'retain-on-failure', screenshot: 'only-on-failure', actionTimeout: 10_000 })

// 独立运行：playwright test e2e/capability-schema.spec.ts --workers=1；复用已就绪的前端服务。
for (const scenario of [
  { name: '宽窗口浅色', viewport: { width: 1440, height: 900 }, colorScheme: 'light' as const },
  { name: '窄窗口深色', viewport: { width: 640, height: 800 }, colorScheme: 'dark' as const }
]) {
  test.describe(scenario.name, () => {
    test.use({
      baseURL: process.env.CAPABILITY_SCHEMA_BASE_URL ?? 'http://127.0.0.1:23441',
      viewport: scenario.viewport,
      colorScheme: scenario.colorScheme
    })
    test('能力Schema真实编辑：字段、必填、复杂定义保留及保存前验证发布保护', async ({
      page
    }, info) => {
      test.setTimeout(60_000)
      const fixture = await mockConsole(page, 'platform', [
        ...fullPermissions,
        'foundation.preferences.manage'
      ])
      const definitionId = '9223372036854775801',
        versionId = '9223372036854775802'
      const nested = {
        type: 'array',
        items: { anyOf: [{ $ref: '#/$defs/record' }, { type: 'null' }] },
        'x-complex': { preserve: ['原始规则', 2] }
      }
      const inputSchema = {
        type: 'object',
        additionalProperties: false,
        'x-contract': { revision: '精确保留-v7' },
        $defs: {
          record: {
            type: 'object',
            properties: { count: { type: 'integer', minimum: 1 } },
            required: ['count']
          }
        },
        properties: {
          label: { type: 'string', description: '对象名称', enum: ['甲', '乙'], default: '甲' },
          records: nested
        },
        required: ['label']
      }
      const outputSchema = {
        anyOf: [{ type: 'object', properties: { result: { type: 'string' } } }, { type: 'null' }],
        'x-output': ['完整保留']
      }
      const descriptor: Record<string, unknown> = {
        model_invocable: true,
        user_invocable: false,
        tool_name: 'fixture_schema_tool',
        input_schema: inputSchema,
        output_schema: outputSchema,
        annotations: { readOnlyHint: true },
        icons: [],
        effect: 'READ_ONLY',
        idempotent: true,
        confirmation_policy: 'POLICY',
        timeout_ms: 30000,
        sensitive_fields: [],
        provider_requirements: [],
        parallel_safe: false
      }
      const definition = {
        id: definitionId,
        tenant_id: null,
        capability_type: 'TOOL',
        capability_key: 'fixture-schema',
        name: '受控字段契约工具',
        description: '仅供浏览器夹具编辑',
        trust_level: 'TRUSTED',
        status: 'DRAFT',
        revision: 7,
        default_version_id: null
      }
      let version = {
        id: versionId,
        definition_id: definitionId,
        tenant_id: null,
        capability_type: 'TOOL',
        version_key: 'v1',
        name: definition.name,
        description: definition.description,
        trust_level: 'TRUSTED',
        descriptor,
        content_hash: 'fixture-before-save',
        status: 'DRAFT'
      }
      let savedDescriptor: Record<string, unknown> | undefined
      let savedHash = ''
      const errors: string[] = []
      page.on('pageerror', (error) => errors.push(error.message))
      fixture.setHook(async (request, url) => {
        const path = url.pathname.slice('/api/v1'.length)
        if (request.method() === 'GET') {
          if (path === '/admin/foundation/catalog/preferences')
            return {
              data: {
                theme: scenario.colorScheme,
                locale: 'zh-CN',
                page_size: 20,
                compact_navigation: false,
                revision: 7
              }
            }
          if (path === '/admin/foundation/catalog/navigation/current')
            return {
              data: [
                {
                  title: '能力目录',
                  path: '/platform/capabilities',
                  permission: 'foundation.capabilities.read',
                  children: []
                }
              ]
            }
          if (
            path === '/admin/capabilities/definitions/detail' &&
            url.searchParams.get('definitionId') === definitionId
          )
            return { data: definition }
          if (
            path === '/admin/capabilities/definitions/versions' &&
            url.searchParams.get('definitionId') === definitionId
          )
            return { data: [version] }
        }
        if (
          request.method() === 'PUT' &&
          path === '/admin/capabilities/definitions/versions/update'
        ) {
          const command = request.postDataJSON() as {
            definition_id: string
            version_id: string
            payload: {
              name: string
              description: string
              descriptor: Record<string, unknown>
              content_hash: string
            }
          }
          expect(command.definition_id).toBe(definitionId)
          expect(command.version_id).toBe(versionId)
          expect(request.headers()['if-match']).toBe('7')
          expect(command.payload.descriptor.input_schema).toEqual({
            ...inputSchema,
            properties: {
              ...inputSchema.properties,
              ticket: { type: 'integer', description: '受理编号' }
            },
            required: ['label', 'ticket']
          })
          expect(command.payload.descriptor.output_schema).toEqual(outputSchema)
          savedDescriptor = command.payload.descriptor
          savedHash = command.payload.content_hash
          version = { ...version, ...command.payload }
          definition.revision++
          return { data: version }
        }
        return undefined
      })

      await page.goto('/platform/capabilities/' + definitionId)
      await expect(page.getByRole('heading', { name: definition.name, exact: true })).toBeVisible()
      await expect(
        page.getByRole('button', {
          name: scenario.colorScheme === 'dark' ? '切换主题，当前深色' : '切换主题，当前浅色',
          exact: true
        })
      ).toBeVisible()
      await page.getByRole('button', { name: '编辑工作草稿', exact: true }).click()
      const input = page.getByRole('region', { name: '输入 JSON Schema', exact: true })
      const output = page.getByRole('region', { name: '输出 JSON Schema', exact: true })
      await expect(
        input.getByText('复杂字段，原定义完整保留；请切换原始 JSON 查看或修改。', { exact: true })
      ).toBeVisible()
      await expect(
        input
          .getByRole('group', { name: '输入 JSON Schema字段 label', exact: true })
          .getByRole('combobox')
      ).toBeDisabled()
      await expect(
        output.getByRole('textbox', { name: '输出 JSON Schema', exact: true })
      ).toBeVisible()
      await expect(output.getByRole('button', { name: '字段编辑', exact: true })).toBeDisabled()

      await input
        .getByRole('textbox', { name: '输入 JSON Schema新字段名', exact: true })
        .fill('ticket')
      await input.getByRole('button', { name: '添加字段', exact: true }).click()
      const ticket = input.getByRole('group', { name: '输入 JSON Schema字段 ticket', exact: true })
      await ticket.locator('.el-select__wrapper').click()
      await page.getByRole('option', { name: '整数 · integer', exact: true }).click()
      await ticket.getByLabel('说明', { exact: true }).fill('受理编号')
      await ticket.getByText('必填', { exact: true }).click()
      await expect(ticket.getByRole('checkbox', { name: '必填', exact: true })).toBeChecked()
      await expect(ticket.locator('label.el-checkbox')).toHaveClass(/is-checked/)
      await expect(page.getByRole('button', { name: '验证', exact: true })).toBeDisabled()
      await expect(page.getByRole('button', { name: '发布', exact: true })).toBeDisabled()
      await expect(
        page.getByText('当前修改尚未保存，请先保存版本草稿，再验证或发布。', { exact: true })
      ).toBeVisible()
      expect(fixture.requests.filter((request) => request.method !== 'GET')).toEqual([])
      const fieldsScreenshot = info.outputPath('schema-fields.png')
      await page.screenshot({ path: fieldsScreenshot, fullPage: true, animations: 'disabled' })
      await info.attach('结构字段编辑', { path: fieldsScreenshot, contentType: 'image/png' })
      const ticketScreenshot = info.outputPath('schema-added-field.png')
      await ticket.screenshot({ path: ticketScreenshot, animations: 'disabled' })
      await info.attach('新增字段与必填', { path: ticketScreenshot, contentType: 'image/png' })

      await input.getByRole('button', { name: '原始 JSON（专业）', exact: true }).click()
      const professional = input.getByRole('textbox', { name: '输入 JSON Schema', exact: true })
      // CodeMirror 按视口虚拟渲染，DOM 文本不是完整文档；完整保留由下方实际 PUT wire 断言。
      await expect(professional).toContainText('"x-contract"')
      await expect(professional).toContainText('精确保留-v7')
      await input.getByRole('button', { name: '字段编辑', exact: true }).click()
      await expect(ticket.getByRole('checkbox', { name: '必填', exact: true })).toBeChecked()
      await expect(ticket.getByLabel('说明', { exact: true })).toHaveValue('受理编号')
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 2)
      ).toBe(true)

      await page.getByRole('button', { name: '保存版本草稿', exact: true }).click()
      await expect.poll(() => savedDescriptor).toBeDefined()
      await expect(page.getByRole('button', { name: '验证', exact: true })).toBeEnabled()
      await expect(page.getByRole('button', { name: '发布', exact: true })).toBeEnabled()
      expect(
        fixture.requests
          .filter((request) => request.method !== 'GET')
          .map((request) => [request.method, request.path])
      ).toEqual([['PUT', '/admin/capabilities/definitions/versions/update']])
      const expectedHash = await page.evaluate(async (value) => {
        const bytes = await crypto.subtle.digest(
          'SHA-256',
          new TextEncoder().encode(JSON.stringify(value))
        )
        return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, '0')).join(
          ''
        )
      }, savedDescriptor!)
      expect(savedHash).toBe(expectedHash)
      expect(errors).toEqual([])
      fixture.assertHandled()
    })
  })
}
