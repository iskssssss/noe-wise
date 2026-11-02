const fs = require('node:fs/promises')
const path = require('node:path')

/** 普通配置不持有明文密钥；系统加密不可用时仅保存在当前主进程内存。 */
class ModelSettings {
  constructor(directory, encryption) {
    this.file = path.join(directory, 'model-settings.json')
    this.encryption = encryption
    this.settings = null
    this.key = ''
    this.keySaved = false
    this.configurationSaved = false
  }

  async load() {
    try {
      const saved = JSON.parse(await fs.readFile(this.file, 'utf8'))
      this.settings = validateSettings({ ...saved, apiKey: '' })
      this.configurationSaved = true
      if (typeof saved.encryptedKey === 'string' && this.canEncrypt()) {
        try {
          this.key = this.encryption.decryptString(Buffer.from(saved.encryptedKey, 'base64'))
          this.keySaved = true
        } catch {
          this.key = ''
          this.keySaved = false
        }
      }
    } catch (error) {
      if (error.code !== 'ENOENT') throw new Error('无法读取模型设置，请在模型设置中重新保存配置')
    }
    return this.publicSettings()
  }

  canEncrypt() {
    return (
      this.encryption.isEncryptionAvailable() &&
      this.encryption.getSelectedStorageBackend?.() !== 'basic_text'
    )
  }

  publicSettings() {
    return {
      ...(this.settings ?? {
        providerId: 'deepseek',
        baseUrl: 'https://api.deepseek.com',
        model: 'deepseek-flash'
      }),
      apiKey: undefined,
      hasApiKey: this.key.length > 0,
      keySaved: this.keySaved,
      configurationSaved: this.configurationSaved,
      encryptionAvailable: this.canEncrypt()
    }
  }

  configuration() {
    return this.settings && this.key ? { ...this.settings, apiKey: this.key } : null
  }

  prepare(input) {
    const settings = validateSettings(input)
    if (
      !settings.apiKey &&
      this.key &&
      this.settings &&
      (settings.providerId !== this.settings.providerId ||
        settings.baseUrl !== this.settings.baseUrl)
    ) {
      throw new Error('更换供应商或服务地址时，请重新输入对应的 API Key')
    }
    const apiKey = settings.apiKey || this.key
    if (!apiKey) throw new Error('请输入 API Key')
    return { ...settings, apiKey }
  }

  /** Host 已确认应用后同步当前进程；磁盘失败不能让公开配置退回旧值。 */
  apply(prepared) {
    const { apiKey, ...settings } = prepared
    this.settings = settings
    this.key = apiKey
    this.keySaved = false
    this.configurationSaved = false
  }

  async save(input) {
    const prepared = this.prepare(input)
    const { apiKey, ...settings } = prepared
    const keySaved = this.canEncrypt()
    const saved = {
      ...settings,
      ...(keySaved
        ? { encryptedKey: this.encryption.encryptString(apiKey).toString('base64') }
        : {})
    }
    await fs.mkdir(path.dirname(this.file), { recursive: true })
    const temporary = `${this.file}.tmp`
    await fs.writeFile(temporary, JSON.stringify(saved), { encoding: 'utf8', mode: 0o600 })
    await fs.rename(temporary, this.file)
    this.settings = settings
    this.key = apiKey
    this.keySaved = keySaved
    this.configurationSaved = true
    return this.publicSettings()
  }
}

function validateSettings(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('模型设置无效')
  const { providerId, baseUrl, model, apiKey, outputTokens } = input
  if (!['deepseek', 'custom'].includes(providerId)) throw new Error('不支持的模型供应商')
  if (typeof baseUrl !== 'string' || baseUrl.length > 2048) throw new Error('请输入有效的服务地址')
  let url
  try {
    url = new URL(baseUrl)
  } catch {
    throw new Error('请输入完整的模型服务地址')
  }
  if (
    !['https:', 'http:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash
  )
    throw new Error('模型地址只能使用 HTTP(S)，且不能包含凭据、查询参数或片段')
  if (url.protocol === 'http:' && !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))
    throw new Error('远程模型服务必须使用 HTTPS')
  if (typeof model !== 'string' || !model.trim() || model.length > 256)
    throw new Error('请输入有效的模型名称')
  if (typeof apiKey !== 'string' || apiKey.length > 8192 || /[\r\n]/.test(apiKey))
    throw new Error('API Key 格式无效')
  if (
    Object.hasOwn(input, 'outputTokens') &&
    (!Number.isSafeInteger(outputTokens) || outputTokens <= 0)
  )
    throw new Error('单次最大输出 token 必须是正整数；留空使用宿主默认值')
  return {
    providerId,
    baseUrl: baseUrl.replace(/\/$/, ''),
    model: model.trim(),
    apiKey,
    ...(Object.hasOwn(input, 'outputTokens') ? { outputTokens } : {})
  }
}

module.exports = { ModelSettings, validateSettings }
