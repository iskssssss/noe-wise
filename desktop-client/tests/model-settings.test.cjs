const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const { ModelSettings, validateSettings } = require('../electron/model-settings.cjs')
const input = {
  providerId: 'deepseek',
  baseUrl: 'https://api.deepseek.com',
  model: 'deepseek-chat',
  apiKey: 'test-key-not-a-real-credential'
}

async function directory(t) {
  const value = await fs.mkdtemp(path.join(os.tmpdir(), 'noewise-settings-'))
  t.after(() => fs.rm(value, { recursive: true, force: true }))
  return value
}

test('系统加密不可用时密钥只留内存，重启后不恢复', async (t) => {
  const root = await directory(t)
  const encryption = { isEncryptionAvailable: () => false }
  const settings = new ModelSettings(root, encryption)
  assert.equal((await settings.load()).model, 'deepseek-flash')
  const result = await settings.save(input)
  assert.equal(result.keySaved, false)
  assert.equal(result.hasApiKey, true)
  assert.equal(result.apiKey, undefined)
  const saved = await fs.readFile(settings.file, 'utf8')
  assert.equal(saved.includes(input.apiKey), false)
  assert.equal(saved.includes('encryptedKey'), false)
  const reopened = new ModelSettings(root, encryption)
  const restored = await reopened.load()
  assert.equal(restored.hasApiKey, false)
  assert.equal(restored.model, input.model)
  assert.equal(reopened.configuration(), null)
})

test('持久化密钥只通过系统加密接口，公开设置不回传秘密', async (t) => {
  const root = await directory(t)
  const encryption = {
    isEncryptionAvailable: () => true,
    encryptString: (value) => Buffer.from(value.split('').reverse().join('')),
    decryptString: (value) => value.toString().split('').reverse().join('')
  }
  const settings = new ModelSettings(root, encryption)
  await settings.save(input)
  assert.equal((await fs.readFile(settings.file, 'utf8')).includes(input.apiKey), false)
  const reopened = new ModelSettings(root, encryption)
  assert.equal((await reopened.load()).hasApiKey, true)
  assert.equal(reopened.configuration().apiKey, input.apiKey)
  assert.equal(JSON.stringify(reopened.publicSettings()).includes(input.apiKey), false)
})

test('拒绝远端明文 HTTP、地址凭据与换行密钥', () => {
  assert.throws(() => validateSettings({ ...input, baseUrl: 'http://example.com' }), /HTTPS/)
  assert.throws(
    () => validateSettings({ ...input, baseUrl: 'https://user:password@example.com' }),
    /凭据/
  )
  assert.throws(() => validateSettings({ ...input, apiKey: 'a\nb' }), /格式/)
  assert.equal(
    validateSettings({ ...input, baseUrl: 'http://127.0.0.1:12345/v1' }).baseUrl,
    'http://127.0.0.1:12345/v1'
  )
})

test('已有密钥不能在未重新输入时转发到其他供应商或端点', async (t) => {
  const root = await directory(t)
  const settings = new ModelSettings(root, { isEncryptionAvailable: () => false })
  await settings.save(input)
  assert.throws(
    () =>
      settings.prepare({
        ...input,
        apiKey: '',
        providerId: 'custom',
        baseUrl: 'https://example.com/v1'
      }),
    /重新输入/
  )
  assert.equal(settings.prepare({ ...input, apiKey: '' }).apiKey, input.apiKey)
})

test('输出上限是可选正整数，缺失保持省略并拒绝显式非法值', () => {
  assert.equal(Object.hasOwn(validateSettings(input), 'outputTokens'), false)
  assert.equal(validateSettings({ ...input, outputTokens: 512 }).outputTokens, 512)
  for (const outputTokens of [
    null,
    undefined,
    0,
    -1,
    1.5,
    '512',
    NaN,
    Infinity,
    Number.MAX_SAFE_INTEGER + 1
  ]) {
    assert.throws(() => validateSettings({ ...input, outputTokens }), /正整数/)
  }
})

test('输出配置完整持久化并转交后续配置，清空后不在客户端补默认', async (t) => {
  const root = await directory(t)
  const encryption = {
    isEncryptionAvailable: () => true,
    encryptString: (value) => Buffer.from(value.split('').reverse().join('')),
    decryptString: (value) => value.toString().split('').reverse().join('')
  }
  const settings = new ModelSettings(root, encryption)
  await settings.save({ ...input, outputTokens: 512 })
  const reopened = new ModelSettings(root, encryption)
  assert.equal((await reopened.load()).outputTokens, 512)
  assert.equal(reopened.configuration().outputTokens, 512)
  await reopened.save({ ...input, apiKey: '', outputTokens: 1024 })
  assert.equal(reopened.configuration().outputTokens, 1024)
  await reopened.save({ ...input, apiKey: '' })
  assert.equal(Object.hasOwn(reopened.publicSettings(), 'outputTokens'), false)
  assert.equal(Object.hasOwn(reopened.configuration(), 'outputTokens'), false)
  assert.equal(
    Object.hasOwn(JSON.parse(await fs.readFile(reopened.file, 'utf8')), 'outputTokens'),
    false
  )
})


test('Host已应用但磁盘失败时公开当前配置，原磁盘配置保持可恢复', async (t) => {
  const root = await directory(t)
  const encryption = { isEncryptionAvailable: () => false }
  const settings = new ModelSettings(root, encryption)
  await settings.save(input)
  const changed = { ...input, model: 'applied-model', apiKey: 'new-memory-secret' }
  settings.apply(changed)
  await fs.mkdir(`${settings.file}.tmp`)
  await assert.rejects(settings.save(changed))
  const current = settings.publicSettings()
  assert.equal(current.model, changed.model)
  assert.equal(current.configurationSaved, false)
  assert.equal(current.keySaved, false)
  assert.equal(current.hasApiKey, true)
  assert.equal(settings.configuration().apiKey, changed.apiKey)
  assert.equal(JSON.stringify(current).includes(changed.apiKey), false)
  const reopened = new ModelSettings(root, encryption)
  assert.equal((await reopened.load()).model, input.model)
  assert.equal(reopened.publicSettings().configurationSaved, true)
  await fs.rmdir(`${settings.file}.tmp`)
  const persisted = await settings.save({ ...changed, apiKey: '' })
  assert.equal(persisted.configurationSaved, true)
  assert.equal(persisted.model, changed.model)
})
