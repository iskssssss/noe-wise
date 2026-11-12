import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少技能验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('技能挂载只读取完整发布摘要', async ({ page }) => {
  test.setTimeout(180_000)
  const mode = required('E2E_AGENT_SKILL_OPTIONS')
  expect(['baseline', 'candidate']).toContain(mode)
  const agent = required('E2E_AGENT_ID')
  const evidence = required('E2E_ARTIFACT_DIR')
  const marker = required('E2E_SKILL_MARKER')
  let skillPublishes = 0
  let mountPosts = 0
  let definitions = 0
  let dropped = 0
  let options = 0
  let optionBody:
    { data: Array<{ definition_id: string; skill_estimated_tokens?: number }> } | undefined
  const network: { path: string; status: number }[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (request.method() === 'POST' && url.pathname === '/api/v1/agents/capability-mounts')
      mountPosts++
    if (
      request.method() === 'GET' &&
      url.pathname === '/api/v1/capabilities/definitions' &&
      url.searchParams.get('capability_type') === 'SKILL'
    )
      definitions++
  })
  page.on('response', async (response) => {
    const url = new URL(response.url())
    if (
      url.pathname === '/api/v1/capabilities/requirements/options' &&
      url.searchParams.get('capability_type') === 'SKILL'
    ) {
      options++
      network.push({ path: url.pathname + url.search, status: response.status() })
      if (response.ok()) optionBody = await response.json()
    }
  })
  await page.goto('/login?redirect=%2Fadmin%2Fskills')
  await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
  await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
  await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/skills$/)
  const skill = page
  skill.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/capabilities/skill-drafts/save-and-publish'
    )
      skillPublishes++
  })
  await skill.getByRole('button', { name: '新建技能', exact: true }).first().click()
  const create = skill.getByRole('dialog', { name: '新建技能' })
  await create.getByLabel('名称', { exact: true }).fill('跨页发布验收技能')
  await create.getByLabel('说明', { exact: true }).fill('验证机构内跨页发布和挂载')
  const created = skill.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/capabilities/skill-drafts/create-with-receipt'
  )
  await create.getByRole('button', { name: '创建并进入工作草稿', exact: true }).click()
  const createResponse = await created
  expect(createResponse.status()).toBe(200)
  const creation = (await createResponse.json()).data
  const definition = String(creation.definition_id)
  expect(definition).toMatch(/^[1-9]\d*$/)
  await expect(skill).toHaveURL(new RegExp(`/admin/skills/${definition}$`))
  await skill.getByRole('button', { name: '新建文件', exact: true }).first().click()
  await skill.getByPlaceholder('scripts/main.py').fill('SKILL.md')
  await skill.getByRole('button', { name: '创建文件', exact: true }).click()
  await skill
    .getByRole('textbox', { name: '文件 SKILL.md' })
    .fill(
      `---\nname: cross-tab-skill\ndescription: Isolated skill for actual runtime verification\n---\n${marker}\n`
    )
  if (process.env.E2E_SKILL_RESOURCE_BUDGET) {
    await skill.getByRole('button', { name: '新建文件', exact: true }).first().click()
    await skill.getByPlaceholder('scripts/main.py').fill('references/guide.md')
    await skill.getByRole('button', { name: '创建文件', exact: true }).click()
    await skill
      .getByRole('textbox', { name: '文件 references/guide.md' })
      .fill('RESOURCE_' + marker + '\n' + '详细资料内容 '.repeat(400))
  }
  await skill.getByRole('tab', { name: '发布设置' }).click()
  await skill.locator('.el-form-item').filter({ hasText: '版本号' }).locator('input').fill('v1')
  await skill
    .locator('.el-form-item')
    .filter({ hasText: '激活方式' })
    .locator('.el-select__wrapper')
    .click()
  await skill
    .getByRole('option', {
      name: process.env.E2E_SKILL_RESOURCE_BUDGET ? '按需激活' : '始终激活',
      exact: true
    })
    .click()
  const validated = skill.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/capabilities/skill-drafts/validate-content'
  )
  await skill.getByRole('button', { name: '校验', exact: true }).click()
  expect((await (await validated).json()).data.valid).toBe(true)
  const submitted = skill.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/capabilities/skill-drafts/save-and-publish'
  )
  await skill.getByRole('button', { name: '保存并发布', exact: true }).click()
  await skill.getByRole('button', { name: '保存并发布', exact: true }).last().click()
  const publishedResponse = await submitted
  expect(publishedResponse.status()).toBe(200)
  const receipt = (await publishedResponse.json()).data
  expect(receipt.status).toBe('SUCCEEDED')
  expect(String(receipt.definition_id)).toBe(definition)
  const version = String(receipt.publication.version_id)
  expect(version).toMatch(/^[1-9]\d*$/)
  await expect(skill.getByText(/技能发布已确认：版本/)).toBeVisible()
  expect(skillPublishes).toBe(1)
  await skill.screenshot({ path: join(evidence, 'skill-published-tab.png'), fullPage: true })
  const definitionsBefore = definitions
  const optionsBefore = options
  await page.route(
    (url) =>
      url.pathname === '/api/v1/capabilities/definitions' &&
      url.searchParams.get('capability_type') === 'SKILL',
    async (route) => {
      const upstream = await route.fetch({ timeout: 30_000 })
      const body = await upstream.json()
      await writeFile(
        join(evidence, 'definitions-upstream.json'),
        JSON.stringify({ status: upstream.status(), body }, null, 2)
      )
      expect(upstream.status()).toBe(200)
      await route.abort('failed')
      dropped++
    }
  )
  await page.goto(`/admin/agents/${agent}`)
  await page.getByRole('tab', { name: /能力挂载/ }).click()
  await page.getByRole('button', { name: '添加能力', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '添加能力挂载' })
  await expect.poll(() => options - optionsBefore).toBe(1)
  await expect
    .poll(() => optionBody?.data.some((item) => String(item.definition_id) === definition))
    .toBe(true)
  await writeFile(join(evidence, 'options.json'), JSON.stringify(optionBody, null, 2))
  let mount: unknown
  if (mode === 'baseline') {
    await expect.poll(() => dropped).toBe(1)
    await expect(drawer).toHaveCount(0)
    expect(mountPosts).toBe(0)
  } else {
    await expect(drawer).toBeVisible()
    expect(definitions - definitionsBefore).toBe(0)
    expect(dropped).toBe(0)
    const selection = drawer.locator('.el-form-item').filter({ hasText: '按名称、用途或标识查找' })
    await selection.locator('.el-select__wrapper').click()
    await page.getByRole('option').filter({ hasText: 'cross-tab-skill' }).click()
    const option = optionBody!.data.find((item) => String(item.definition_id) === definition)!
    expect(Number(option.skill_estimated_tokens)).toBeGreaterThan(0)
    await expect(drawer.getByText(/技能入口估算/)).toBeVisible()
    const mounted = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/agents/capability-mounts'
    )
    await drawer.getByRole('button', { name: '添加能力', exact: true }).click()
    const response = await mounted
    const command = response.request().postDataJSON()
    const body = await response.json()
    await writeFile(
      join(evidence, 'browser-mount.json'),
      JSON.stringify({ status: response.status(), command, body }, null, 2)
    )
    expect(response.status()).toBe(201)
    expect(String(command.payload.capability_definition_id)).toBe(definition)
    mount = body.data.mounts.find(
      (item: { capability_definition_id: string }) =>
        String(item.capability_definition_id) === definition
    )
    expect(String((mount as { capability_version_id: string }).capability_version_id)).toBe(version)
    expect(mountPosts).toBe(1)
  }
  await page.screenshot({ path: join(evidence, `skill-options-${mode}.png`), fullPage: true })
  await writeFile(
    join(evidence, 'result.json'),
    JSON.stringify(
      {
        mode,
        definition,
        version,
        mount,
        definitions: definitions - definitionsBefore,
        options: options - optionsBefore,
        dropped,
        mountPosts,
        skillPublishes,
        network
      },
      null,
      2
    )
  )
})
