/** 仅UI空态的只读mock证据；不证明真实导入、校验或发布。真实链见verify_skill_workbench_p0.mjs。 */
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const require = createRequire(path.join(root, 'frontend-admin/package.json'))
const { chromium, expect } = require('@playwright/test')
const { createServer } = await import(pathToFileURL(require.resolve('vite')).href)
const { mockConsole, fullPermissions } = await import(pathToFileURL(path.join(root, 'frontend-admin/e2e/support/mock-console.ts')).href)
assert(process.argv[2], 'Private evidence directory required')
const output = path.resolve(process.argv[2])
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true })
const results = []
try {
  for (const kind of ['admin', 'platform']) {
    process.env.E2E_BACKEND_URL = 'http://127.0.0.1:1'
    const appRoot = path.join(root, `frontend-${kind}`)
    const server = await createServer({ root: appRoot, configFile: path.join(appRoot, 'vite.config.ts'),
      logLevel: 'error', server: { host: '127.0.0.1', port: 0, strictPort: true, open: false } })
    try {
      await server.listen()
      const origin = `http://127.0.0.1:${server.httpServer.address().port}`
      for (const scenario of ['empty', 'error', 'permissions', 'loading', 'workbench']) {
        const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
        try {
          const page = await context.newPage()
          const permissions = scenario === 'permissions'
            ? fullPermissions.filter(code => code !== 'foundation.capabilities.create' && code !== 'foundation.capabilities.read') : fullPermissions
          const fixture = await mockConsole(page, kind, permissions)
          let release
          const barrier = new Promise(resolve => { release = resolve })
          fixture.setHook(async (_request, url) => {
            if (scenario === 'workbench') {
              if (url.pathname.endsWith('/definitions/detail')) return { data: {
                id: '3', tenant_id: kind === 'admin' ? '9007199254740993' : null,
                capability_type: 'SKILL', capability_key: 'visual-workbench', name: '技能文件与发布设置间距验收',
                description: '仅用于检查真实组件排版的只读视觉夹具', trust_level: kind === 'admin' ? 'TENANT' : 'TRUSTED', revision: 1, status: 'DRAFT', default_version_id: null
              } }
              if (url.pathname.endsWith('/definitions/versions')) return { data: [] }
              if (url.pathname.endsWith('/skill-drafts')) return { data: {
                id: '7', definition_id: '3', base_version_id: null, revision: 2, version_key: 'visual-v1',
                model_invocable: true, user_invocable: true, activation_mode: 'ON_DEMAND', conflict_group: null,
                required_capabilities: [], published_version_id: null, updated_at: '2026-10-24T00:00:00Z',
                files: { 'SKILL.md': Buffer.from('---\nname: visual-workbench\ndescription: 只读视觉夹具\n---\n\n# 文件边距检查\n\n使用当前任务与可信来源完成交付。\n').toString('base64'),
                  'references/guide.md': Buffer.from('# 参考资料\n\n仅用于观察文件树与正文编辑区的留白。\n').toString('base64') }
              } }
            }
            if (url.pathname.endsWith('/capabilities/definitions')) {
              if (scenario === 'loading') await barrier
              return scenario === 'error' ? { status: 503, code: 'CONTROLLED_READ_FAILURE' } : { data: [] }
            }
          })
          await page.goto(`${origin}/${kind}/skills${scenario === 'workbench' ? '/3' : ''}`)
          if (scenario === 'workbench') {
            await expect(page.locator('.package-workbench')).toBeVisible()
            for (const width of [1440, 390]) {
              await page.setViewportSize({ width, height: 1000 })
              if (kind === 'admin') {
                await page.getByRole('tab', { name: '技能文件', exact: true }).click()
                const history = page.locator('.el-collapse-item__header').filter({ hasText: '历史版本与技能包导入' })
                if (await history.getAttribute('aria-expanded') !== 'true') await history.click()
                await expect(history).toHaveAttribute('aria-expanded', 'true')
                await expect(page.getByRole('button', { name: '导入 ZIP', exact: true }).last()).toBeVisible()
                await expect.poll(async () => (await page.locator('.el-collapse-item__wrap').first().boundingBox())?.height ?? 0).toBeGreaterThan(100)
              }
              assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
              await page.screenshot({ path: path.join(output, `${kind}-mock-workbench-files-${width}.png`), fullPage: true })
              const editorPadding = await page.locator('.package-workbench .file-content').evaluate(element => getComputedStyle(element).paddingLeft)
              assert.equal(editorPadding, '12px')
              await page.locator('.package-workbench .file-content').scrollIntoViewIfNeeded()
              await page.screenshot({ path: path.join(output, `${kind}-mock-workbench-editor-${width}.png`), fullPage: true })
              if (kind === 'admin') {
                await page.getByRole('tab', { name: '发布设置', exact: true }).click()
                assert.equal(await page.locator('.el-tab-pane:visible').evaluate(element => getComputedStyle(element).paddingLeft), '20px')
              } else {
                assert.equal(await page.locator('.el-card__body').first().evaluate(element => getComputedStyle(element).paddingLeft), '20px')
              }
              await page.screenshot({ path: path.join(output, `${kind}-mock-workbench-settings-${width}.png`), fullPage: true })
            }
          } else if (scenario === 'loading') {
            await expect(page.locator('.skill-empty')).toContainText('正在读取技能')
            await expect(page.locator('.skill-empty button')).toHaveCount(0)
            await page.screenshot({ path: path.join(output, `${kind}-mock-loading.png`), fullPage: true })
            release()
          } else if (scenario === 'error') {
            await expect(page.locator('.skill-empty')).toContainText('技能目录读取失败')
            await expect(page.getByRole('button', { name: '重新加载', exact: true })).toBeVisible()
            await page.screenshot({ path: path.join(output, `${kind}-mock-error.png`), fullPage: true })
          } else {
            await expect(page.locator('.skill-empty')).toContainText('尚无技能')
            if (scenario === 'permissions') {
              await expect(page.locator('.skill-empty button')).toHaveCount(0)
              await page.screenshot({ path: path.join(output, `${kind}-mock-permissions.png`), fullPage: true })
            } else {
              for (const [width, theme] of [[1440, 'light'], [390, 'light'], [390, 'dark']]) {
                await page.setViewportSize({ width, height: 844 })
                await page.evaluate(dark => document.documentElement.classList.toggle('dark', dark), theme === 'dark')
                const description = page.locator('.skill-empty .el-empty__description p')
                assert.equal(await description.evaluate(element => getComputedStyle(element).lineHeight), '22.4px')
                assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
                const action = page.locator('.skill-empty button').last()
                await expect(action).toBeInViewport()
                await action.focus()
                await expect(action).toBeFocused()
                await page.screenshot({ path: path.join(output, `${kind}-mock-empty-${width}-${theme}.png`), fullPage: true })
              }
              await page.getByRole('textbox', { name: '搜索技能', exact: true }).fill('不存在')
              await expect(page.locator('.skill-empty')).toContainText('没有匹配的技能')
              await page.getByRole('button', { name: '清空筛选', exact: true }).click()
              await expect(page.locator('.skill-empty')).toContainText('尚无技能')
            }
          }
          fixture.assertHandled()
          assert(fixture.requests.every(request => request.method === 'GET'), 'UI mock verification must not write')
          results.push({ kind, scenario, status: 'PASSED' })
        } finally { await context.close() }
      }
    } finally { await server.close() }
  }
} finally {
  await browser.close()
  await writeFile(path.join(output, 'empty-ui-mock.json'), JSON.stringify({ mode: 'UI_MOCK_READ_ONLY', results }, null, 2))
}
console.log(JSON.stringify({ mode: 'UI_MOCK_READ_ONLY', passed: results.length, evidenceDirectory: output }))
