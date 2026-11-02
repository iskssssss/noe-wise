import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { _electron as electron } from 'playwright'
import executablePath from 'electron'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const { version } = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'))
const temporary = await mkdtemp(path.join(os.tmpdir(), 'noewise-electron-java-'))
const workspace = path.join(temporary, 'sample-project')
await mkdir(workspace)
await mkdir(path.join(root, 'test-results'), { recursive: true })
await writeFile(path.join(workspace, 'answer.cjs'), 'module.exports = value => value + 1\n')
await writeFile(
  path.join(workspace, 'answer.test.cjs'),
  "const assert = require('node:assert/strict'); const test = require('node:test'); const answer = require('./answer.cjs'); test('double', () => assert.equal(answer(3), 6));\n"
)
const credential = 'desktop-combination-key-not-real'
let calls = 0
let providerFailure
const server = createServer(async (request, response) => {
  try {
    assert.equal(request.url, '/chat/completions')
    assert.equal(request.headers.authorization, `Bearer ${credential}`)
    let body = ''
    for await (const chunk of request) {
      body += chunk.toString('utf8')
      if (body.length > 2_000_000) throw new Error('受控请求超出容量')
    }
    const input = JSON.parse(body)
    assert.equal(input.tool_choice, 'auto')
    assert.ok(input.tools.every((tool) => tool.function.name !== 'final_answer'))
    const steps = [
      ['read_file', { path: 'answer.cjs' }],
      ['edit_file', { path: 'answer.cjs', oldText: 'value + 1', newText: 'value * 2' }],
      ['run_command', { command: 'node --test answer.test.cjs' }]
    ]
    const step = steps[calls++]
    const name = step
      ? input.tools.find((tool) => {
          const metadata = tool.function.description
            .split(/\r?\n/)
            .filter((line) => line.startsWith('title='))
          assert.ok(metadata.length <= 1, '工具定义包含重复 title')
          return (
            metadata.length === 1 &&
            JSON.parse(metadata[0].slice('title='.length)) === step[0]
          )
        })?.function.name
      : undefined
    assert.ok(!step || name, '真实模型请求未声明预期工具')
    assert.ok(calls <= 4, '不允许额外模型调用或重试')
    const result = {
      id: `combination-${calls}`,
      model: 'controlled-model',
      choices: [
        {
          index: 0,
          finish_reason: step ? 'tool_calls' : 'stop',
          message: step ? {
            role: 'assistant',
            content: null,
            tool_calls: [
              {
                id: `call-${calls}`,
                type: 'function',
                function: { name, arguments: JSON.stringify(step[1]) }
              }
            ]
          } : { role: 'assistant', content: '已修复文件，实际本机测试通过。' }
        }
      ],
      usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 }
    }
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify(result))
  } catch (error) {
    providerFailure = error
    response.writeHead(500)
    response.end()
  }
})
await new Promise((resolve, reject) => {
  server.once('error', reject)
  server.listen(0, '127.0.0.1', resolve)
})
const address = server.address()
if (!address || typeof address === 'string') throw new Error('受控服务未监听 TCP')
let application
try {
  application = await electron.launch({
    executablePath,
    args: [path.join(root, 'tests', 'fixtures', 'java-entry.cjs')],
    env: {
      ...process.env,
      NOEWISE_TEST_DATA: path.join(temporary, 'app-data'),
      NOEWISE_TEST_WORKSPACE: workspace
    },
    timeout: 30000
  })
  const page = await application.firstWindow()
  await page.getByText('本地 Agent 已就绪', { exact: true }).waitFor({ timeout: 30000 })
  await page.getByRole('button', { name: /模型设置/ }).click()
  await page.locator('select').selectOption('custom')
  await page.locator('input[type="url"]').fill(`http://127.0.0.1:${address.port}`)
  await page.locator('input[maxlength="256"]').fill('controlled-model')
  await page.getByLabel('API Key', { exact: true }).fill(credential)
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByText(/配置已保存/).waitFor()
  await page.getByRole('button', { name: '关闭模型设置' }).click()
  await page.getByRole('button', { name: /创建会话，开始工作/ }).click()
  await page.getByRole('textbox', { name: '任务内容' }).fill('修复double函数并运行本机测试')
  await page.getByRole('button', { name: '发送任务' }).click()
  await page.getByText('确认执行命令', { exact: true }).waitFor({ timeout: 120000 })
  assert.equal(
    await readFile(path.join(workspace, 'answer.cjs'), 'utf8'),
    'module.exports = value => value * 2\n'
  )
  await page.getByRole('button', { name: '允许这次执行' }).click()
  await page
    .getByText('已修复文件，实际本机测试通过。', { exact: true })
    .waitFor({ timeout: 120000 })
  await page
    .locator('.tool-card')
    .filter({ hasText: 'run_command' })
    .locator('summary')
    .first()
    .click()
  await page.getByText('退出码 0', { exact: true }).waitFor()
  assert.match(
    await page.locator('.tool-card').filter({ hasText: 'run_command' }).textContent(),
    /pass 1|tests 1/
  )
  await page.getByRole('tab', { name: /文件变更/ }).click()
  await page.getByText('文件差异', { exact: true }).waitFor()
  await page.screenshot({ path: path.join(root, 'test-results', `java-combination-${version}.png`) })
  assert.equal(calls, 4)
  if (providerFailure) throw providerFailure
  console.log(
    'PASS: 真实 Electron → 私有 stdio → Java Host → SQLite/正式 Runtime/HTTP Provider → 本机文件修改/命令确认/node --test/最终回答（受控模型，无付费调用）'
  )
} catch (error) {
  if (application) {
    const page = await application.firstWindow().catch(() => null)
    if (page)
      await page
        .screenshot({ path: path.join(root, 'test-results', `java-failure-${version}.png`) })
        .catch(() => {})
  }
  throw error
} finally {
  if (application) await application.close()
  server.closeAllConnections()
  await new Promise((resolve) => server.close(resolve))
  await rm(temporary, { recursive: true, force: true })
}
