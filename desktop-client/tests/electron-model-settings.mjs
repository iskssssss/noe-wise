import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, readFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { _electron as electron } from 'playwright'
import executablePath from 'electron'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const temporary = await mkdtemp(path.join(os.tmpdir(), 'noewise-output-setting-'))
const workspace = path.join(temporary, 'sample-project')
await mkdir(workspace)
await mkdir(path.join(root, 'test-results'), { recursive: true })
let application
try {
  application = await electron.launch({
    executablePath,
    args: [path.join(root, 'tests', 'fixtures', 'electron-entry.cjs')],
    env: {
      ...process.env,
      NOEWISE_TEST_DATA: path.join(temporary, 'app-data'),
      NOEWISE_TEST_NODE: process.execPath,
      NOEWISE_TEST_WORKSPACE: workspace
    },
    timeout: 30000
  })
  const page = await application.firstWindow()
  await page.getByText('本地 Agent 已就绪', { exact: true }).waitFor()
  await page.getByRole('button', { name: /模型设置/ }).click()
  const cap = page.getByRole('spinbutton', { name: '单次最大输出 token', exact: true })
  const model = page.locator('input[maxlength="256"]')
  assert.equal(await model.inputValue(), 'deepseek-flash')
  await model.fill('user-selected-deepseek-model')
  assert.equal(await cap.inputValue(), '')
  await cap.fill('512')
  await page.getByLabel('API Key', { exact: true }).fill('settings-fixture-key-not-real')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByText(/配置已保存/).waitFor()
  await page.getByRole('button', { name: '关闭模型设置' }).click()
  await page.getByRole('button', { name: /创建会话，开始工作/ }).click()
  async function submit(number, expected) {
    await page.getByRole('textbox', { name: '任务内容' }).fill(`输出上限检查${number}`)
    await page.getByRole('button', { name: '发送任务' }).click()
    await page
      .locator('.message.assistant')
      .getByText(`本次单次上限：${expected}`, { exact: true })
      .waitFor()
  }
  await submit(1, 512)
  await page.getByRole('button', { name: /模型设置/ }).click()
  assert.equal(await model.inputValue(), 'user-selected-deepseek-model')
  await page.locator('select').selectOption('custom')
  await page.locator('select').selectOption('deepseek')
  assert.equal(await model.inputValue(), 'user-selected-deepseek-model')
  assert.equal(await cap.inputValue(), '512')
  await cap.fill('0')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  assert.equal(await cap.evaluate((element) => element.validity.rangeUnderflow), true)
  const preserved = await page.evaluate(() => window.desktop.getModel())
  assert.equal(preserved.value.outputTokens, 512)
  await cap.fill('1024')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByText(/配置已保存/).waitFor()
  await page.getByRole('button', { name: '关闭模型设置' }).click()
  await submit(2, 1024)
  assert.equal(
    await page
      .locator('.message.assistant')
      .getByText('本次单次上限：512', { exact: true })
      .count(),
    1
  )
  await page.getByRole('button', { name: /模型设置/ }).click()
  await cap.fill('')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByText(/配置已保存/).waitFor()
  await page.getByRole('button', { name: '关闭模型设置' }).click()
  await submit(3, '宿主默认')
  const cleared = await page.evaluate(() => window.desktop.getModel())
  assert.equal(Object.hasOwn(cleared.value, 'outputTokens'), false)
  await page.setViewportSize({ width: 1000, height: 700 })
  await page.getByRole('button', { name: /模型设置/ }).click()
  await page.getByRole('button', { name: '保存配置', exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: path.join(root, 'test-results', 'model-output-setting-candidate.png') })
  await page.getByRole('button', { name: '关闭模型设置' }).click()
  const settingsFile = path.join(temporary, 'app-data', 'model-settings.json')
  const savedBeforeFailure = JSON.parse(await readFile(settingsFile, 'utf8'))
  await mkdir(`${settingsFile}.tmp`)
  await page.getByRole('button', { name: /模型设置/ }).click()
  await model.fill('applied-but-not-persisted')
  await cap.fill('777')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByText(/配置已在本次运行中应用，但未能保存到磁盘/).waitFor()
  const applied = await page.evaluate(() => window.desktop.getModel())
  assert.equal(applied.value.model, 'applied-but-not-persisted')
  assert.equal(applied.value.configurationSaved, false)
  assert.equal(applied.value.outputTokens, 777)
  assert.equal((await page.getByLabel('API Key', { exact: true }).inputValue()), '')
  assert.deepEqual(JSON.parse(await readFile(settingsFile, 'utf8')), savedBeforeFailure)
  await page.getByRole('button', { name: '关闭模型设置' }).click()
  await submit(4, 777)
  await page.getByRole('button', { name: /模型设置/ }).click()
  assert.equal(await model.inputValue(), 'applied-but-not-persisted')
  await page.getByText(/当前配置已在本次运行中应用，但尚未保存到磁盘/).waitFor()
  await rm(`${settingsFile}.tmp`, { recursive: true })
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByText(/配置已保存/).waitFor()
  assert.equal((await page.evaluate(() => window.desktop.getModel())).value.configurationSaved, true)
  await page.getByRole('button', { name: '关闭模型设置' }).click()
  console.log(
    'PASS: DeepSeek默认flash/已保存型号保留，可选输出上限回显/非法值拒绝/512→1024→空、既有回复保留、窄窗口设置（受控Host）'
  )
} finally {
  if (application) await application.close()
  await rm(temporary, { recursive: true, force: true })
}
