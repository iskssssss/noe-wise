import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw Error(`缺少技能文件验收参数：${name}`)
  return value
}

test.describe.configure({ retries: 0 })
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('文本编辑发布保留非文本资源的原始字节', async ({ page }) => {
  test.setTimeout(150_000)
  const mode = required('E2E_SKILL_PACKAGE_ENCODING')
  expect(['baseline', 'candidate']).toContain(mode)
  const evidence = required('E2E_ARTIFACT_DIR')
  const definition = required('E2E_SKILL_DEFINITION')
  const original = required('E2E_IMAGE_BASE64')
  const guide = required('E2E_GUIDE_TEXT')
  let writes = 0,
    publications = 0,
    opened = false
  const errors: string[] = []
  const network: { path: string; method: string; status: number }[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/capabilities/skill-drafts' && request.method() === 'PUT') writes++
    if (path === '/api/v1/capabilities/skill-drafts/save-and-publish') publications++
  })
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname
    if (path.startsWith('/api/v1/capabilities/'))
      network.push({ path, method: response.request().method(), status: response.status() })
  })
  try {
    await page.goto(`/login?redirect=${encodeURIComponent('/admin/skills/' + definition)}`)
    await page.locator('input[autocomplete="organization"]').fill(required('E2E_TENANT_SLUG'))
    await page.locator('input[autocomplete="username"]').fill('runtime-e2e-admin')
    await page.locator('input[autocomplete="current-password"]').fill(required('E2E_PASSWORD'))
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`/admin/skills/${definition}$`))
    const workbench = page.getByRole('region', { name: '包文件工作台', exact: true })
    await expect(workbench).toBeVisible()
    opened = true
    await page.getByRole('tab', { name: '发布设置', exact: true }).click()
    await page.locator('.el-form-item').filter({ hasText: '版本号' }).locator('input').fill('v1')
    await page
      .locator('.el-form-item')
      .filter({ hasText: '激活方式' })
      .locator('.el-select__wrapper')
      .click()
    await page.getByRole('option', { name: '始终激活', exact: true }).click()
    await page.getByRole('tab', { name: '技能文件', exact: true }).click()
    await workbench.locator('.file-node[title="assets/image.png"]').click()
    const image = page.getByRole('textbox', { name: '文件 assets/image.png', exact: true })
    await expect(image).toHaveText(original)
    if (mode === 'baseline') {
      await expect(image).toHaveAttribute('aria-readonly', 'false')
      await image.fill('这是一张图片的正常文字说明。')
      const rejected = page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/capabilities/skill-drafts' &&
          response.request().method() === 'PUT'
      )
      await page.getByRole('button', { name: '保存草稿', exact: true }).click()
      const response = await rejected
      await writeFile(
        join(evidence, 'invalid-save.json'),
        JSON.stringify(
          {
            status: response.status(),
            body: await response.json(),
            request: response.request().postDataJSON()
          },
          null,
          2
        )
      )
      expect(response.status()).toBe(422)
      expect(response.request().postDataJSON().files['assets/image.png']).toBe(
        '这是一张图片的正常文字说明。'
      )
      await page.screenshot({
        path: join(evidence, 'baseline-invalid-resource.png'),
        fullPage: true
      })
      await image.fill(original)
    } else {
      await expect(image).toHaveAttribute('aria-readonly', 'true')
      await expect(image).toHaveAttribute('contenteditable', 'false')
      await expect(workbench.getByText(/完整 ZIP/)).toBeVisible()
      await page.screenshot({
        path: join(evidence, 'candidate-readonly-resource.png'),
        fullPage: true
      })
      await workbench.getByRole('button', { name: '新建文件', exact: true }).click()
      await workbench.getByPlaceholder('scripts/main.py').fill('references/config.toml')
      await workbench.getByRole('button', { name: '创建文件', exact: true }).click()
      await expect(workbench.locator('.file-create').getByRole('alert')).toContainText('完整 ZIP')
      await expect(workbench.locator('.file-node[title="references/config.toml"]')).toHaveCount(0)
      await page.screenshot({
        path: join(evidence, 'candidate-new-file-feedback.png'),
        fullPage: true
      })
      await workbench
        .locator('.file-create')
        .getByRole('button', { name: '取消', exact: true })
        .click()
      expect(writes).toBe(0)
      expect(publications).toBe(0)
    }
    await workbench.locator('.file-node[title="references/guide.md"]').click()
    await page.getByRole('textbox', { name: '文件 references/guide.md', exact: true }).fill(guide)
    const published = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/capabilities/skill-drafts/save-and-publish' &&
        response.request().method() === 'POST'
    )
    await page.getByRole('button', { name: '保存并发布', exact: true }).click()
    await page.getByRole('button', { name: '保存并发布', exact: true }).last().click()
    const response = await published
    const body = await response.json()
    await writeFile(
      join(evidence, 'publication.json'),
      JSON.stringify(
        { status: response.status(), body, request: response.request().postDataJSON() },
        null,
        2
      )
    )
    expect(response.status()).toBe(200)
    expect(body.data.status).toBe('SUCCEEDED')
    expect(String(body.data.definition_id)).toBe(definition)
    await expect(page.getByText(/技能发布已确认：版本/)).toBeVisible()
    expect(writes).toBe(mode === 'baseline' ? 1 : 0)
    expect(publications).toBe(1)
    expect(errors).toEqual([])
    await writeFile(
      join(evidence, 'result.json'),
      JSON.stringify(
        {
          mode,
          definition,
          version: String(body.data.publication.version_id),
          writes,
          publications,
          errors
        },
        null,
        2
      )
    )
  } finally {
    await writeFile(
      join(evidence, 'browser-state.json'),
      JSON.stringify({ mode, writes, publications, errors, network }, null, 2)
    )
    if (opened) {
      await writeFile(join(evidence, 'page.html'), await page.locator('main').innerHTML())
      await page.screenshot({ path: join(evidence, 'published.png'), fullPage: true })
    }
  }
})
