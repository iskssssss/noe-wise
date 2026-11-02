import { describe, expect, it, vi } from 'vitest'
import {
  createSkillDraftApi,
  type SaveSkillDraftInput
} from '@skillforge/shared/capabilities/skillDrafts'

const wire = {
  id: '12',
  definition_id: '11',
  base_version_id: null,
  revision: 4,
  version_key: '',
  files: { 'assets/font.woff2': '/wAB' },
  model_invocable: true,
  user_invocable: false,
  activation_mode: 'ON_DEMAND',
  conflict_group: null,
  required_capabilities: [{ type: 'TOOL', capability_id: '41', version_id: '42' }],
  published_version_id: null,
  requires_plan: null,
  updated_at: '2026-09-22T12:00:00+08:00'
}
const input: SaveSkillDraftInput = {
  draftId: '12',
  revision: 4,
  versionKey: '',
  files: wire.files,
  modelInvocable: true,
  userInvocable: false,
  activationMode: 'ON_DEMAND',
  conflictGroup: null,
  requiredCapabilities: [{ type: 'TOOL', capabilityId: '41', versionId: '42' }]
}
function fixture(prefix = '/admin/capabilities') {
  const http = { get: vi.fn(), post: vi.fn(), put: vi.fn() }
  return { http, api: createSkillDraftApi(http, prefix) }
}

// 期望 wire 字节由浏览器 URI 编码生成，不依赖生产 TextEncoder 实现或 Node 类型。
const utf8Base64 = (value: string) =>
  btoa(
    encodeURIComponent(value).replace(/%([0-9A-F]{2})/g, (_match, byte: string) =>
      String.fromCharCode(parseInt(byte, 16))
    )
  )

describe('技能草稿 HTTP 契约', () => {
  it('一步创建技能用metadata命令并复用既有草稿内容映射', async () => {
    const { http, api } = fixture('/capabilities')
    http.post.mockResolvedValue({
      ...wire,
      id: '9223372036854775806',
      definition_id: '9223372036854775807',
      files: { 'SKILL.md': utf8Base64('正文\n') }
    })
    const result = await api.createSkill({
      capabilityKey: 'service',
      name: '客服',
      description: '处理咨询'
    })
    expect(http.post).toHaveBeenCalledExactlyOnceWith(
      '/capabilities/skill-drafts/new',
      { capability_key: 'service', name: '客服', description: '处理咨询' },
      undefined
    )
    expect(result).toEqual(
      expect.objectContaining({
        id: '9223372036854775806',
        definitionId: '9223372036854775807',
        revision: 4,
        files: { 'SKILL.md': '正文\n' }
      })
    )
  })

  it('平台一步创建沿admin前缀调用new且保留精确原工作草稿身份', async () => {
    const { http, api } = fixture('/admin/capabilities')
    http.post.mockResolvedValue({
      ...wire,
      id: '9223372036854775806',
      definition_id: '9223372036854775807',
      files: {}
    })
    const created = await api.createSkill({
      capabilityKey: 'platform-service',
      name: '平台客服技能',
      description: '平台技能说明'
    })
    expect(http.post).toHaveBeenCalledExactlyOnceWith(
      '/admin/capabilities/skill-drafts/new',
      { capability_key: 'platform-service', name: '平台客服技能', description: '平台技能说明' },
      undefined
    )
    expect(created).toEqual(
      expect.objectContaining({
        id: '9223372036854775806',
        definitionId: '9223372036854775807',
        revision: 4,
        files: {}
      })
    )
  })

  it('把204映射为无活动草稿并保留版本只读查询', async () => {
    const { http, api } = fixture()
    http.get.mockResolvedValueOnce(undefined).mockResolvedValueOnce(wire)
    expect(await api.getActive('11')).toBeUndefined()
    const version = await api.getVersion('22')
    expect(http.get).toHaveBeenNthCalledWith(
      1,
      '/admin/capabilities/skill-drafts?definitionId=11',
      undefined
    )
    expect(http.get).toHaveBeenNthCalledWith(
      2,
      '/admin/capabilities/skill-drafts/version?versionId=22',
      undefined
    )
    expect(version.files['assets/font.woff2']).toBe('/wAB')
    expect(version.requiredCapabilities[0]).toEqual({
      type: 'TOOL',
      capabilityId: '41',
      versionId: '42'
    })
  })

  it('保存未完成包时传递snake_case正文和草稿If-Match', async () => {
    const { http, api } = fixture()
    http.put.mockResolvedValue({ ...wire, revision: 5 })
    const saved = await api.save(input, { headers: { 'X-Test': 'retained' } })
    const [url, body, options] = http.put.mock.calls[0]!
    expect(url).toBe('/admin/capabilities/skill-drafts')
    expect(body).toEqual({
      draft_id: '12',
      version_key: '',
      files: wire.files,
      activation_mode: 'ON_DEMAND',
      model_invocable: true,
      user_invocable: false,
      conflict_group: null,
      required_capabilities: [{ type: 'TOOL', capability_id: '41', version_id: '42' }]
    })
    expect(new Headers(options.headers).get('If-Match')).toBe('4')
    expect(new Headers(options.headers).get('X-Test')).toBe('retained')
    expect(saved.revision).toBe(5)
  })

  it.each(['/capabilities', '/admin/capabilities'])(
    '%s文本保存、回读和客户端重建逐字保留正文及版本修订',
    async (prefix) => {
      const { http, api } = fixture(prefix)
      const text =
        '\uFEFF---\r\nname: owned-skill\r\ndescription: 中文 😀\r\n---\r\n\r\n保留空白  \n下一行\r\n'
      const script = 'print("你好 😀")\r\n'
      const draftInput = {
        ...input,
        versionKey: '1.0.0',
        files: { 'SKILL.md': text, 'scripts/main.PY': script, ...wire.files }
      }
      const storedWire = {
        ...wire,
        revision: input.revision + 1,
        version_key: draftInput.versionKey,
        files: {
          'SKILL.md': utf8Base64(text),
          'scripts/main.PY': utf8Base64(script),
          ...wire.files
        }
      }
      http.put.mockResolvedValue(storedWire)
      http.get.mockResolvedValue(storedWire)

      const saved = await api.save(draftInput)
      const [url, body, options] = http.put.mock.calls[0]!
      expect(url).toBe(`${prefix}/skill-drafts`)
      expect(body.files).toEqual(storedWire.files)
      expect(body.version_key).toBe('1.0.0')
      expect(new Headers(options.headers).get('If-Match')).toBe('4')
      expect(saved.files).toEqual(draftInput.files)
      expect(saved.versionKey).toBe('1.0.0')
      expect(saved.revision).toBe(5)
      expect(await api.get('12')).toEqual(saved)

      const rebuilt = createSkillDraftApi(http, prefix)
      expect(await rebuilt.getActive('11')).toEqual(saved)
      await rebuilt.save({ ...draftInput, revision: saved.revision, files: saved.files })
      expect(http.put.mock.calls[1]![1].files).toEqual(storedWire.files)
      expect(new Headers(http.put.mock.calls[1]![2].headers).get('If-Match')).toBe('5')
      expect(draftInput.files['SKILL.md']).toBe(text)
      expect(draftInput.files['assets/font.woff2']).toBe('/wAB')
    }
  )

  it('文本路径拒绝非法UTF-8，不把原Base64当作正文兜底', async () => {
    const { http, api } = fixture()
    http.get.mockResolvedValue({ ...wire, files: { 'SKILL.md': '/wAB' } })
    await expect(api.get('12')).rejects.toThrow()
  })

  it('不修补非法路径或空版本，422原样交给页面并保留输入', async () => {
    const { http, api } = fixture()
    const rejected = Object.assign(new Error('validation error'), { status: 422 })
    const invalidInput = { ...input, files: { '../escape.md': '保留原输入\n' } }
    http.put.mockRejectedValue(rejected)
    await expect(api.save(invalidInput)).rejects.toBe(rejected)
    const body = http.put.mock.calls[0]![1]
    expect(body.version_key).toBe('')
    expect(body.files).toEqual({
      '../escape.md': utf8Base64(invalidInput.files['../escape.md'])
    })
    expect(invalidInput.files['../escape.md']).toBe('保留原输入\n')
    expect(http.put).toHaveBeenCalledTimes(1)
  })

  it('冲突直接交给调用页面，不重试覆盖也不修改本地包', async () => {
    const { http, api } = fixture()
    const conflict = Object.assign(new Error('revision conflict'), { status: 409 })
    http.put.mockRejectedValue(conflict)
    const original = JSON.stringify(input)
    await expect(api.save(input)).rejects.toBe(conflict)
    expect(http.put).toHaveBeenCalledTimes(1)
    expect(JSON.stringify(input)).toBe(original)
  })

  it('ZIP使用FormData普通draft_id字段并携带revision', async () => {
    const { http, api } = fixture()
    http.post.mockResolvedValue({ ...wire, revision: 5 })
    const archive = new File([new Uint8Array([80, 75])], 'skill.zip', { type: 'application/zip' })
    await api.importZip('12', 4, archive)
    const [url, body, options] = http.post.mock.calls[0]!
    expect(url).toBe('/admin/capabilities/skill-drafts/import-zip')
    expect(body).toBeInstanceOf(FormData)
    expect(body.get('draft_id')).toBe('12')
    expect((body.get('archive') as File).name).toBe('skill.zip')
    expect(new Headers(options.headers).get('If-Match')).toBe('4')
    expect(new Headers(options.headers).has('Content-Type')).toBe(false)
  })

  it('校验绑定保存revision，发布保留页面定义revision与回执', async () => {
    const { http, api } = fixture()
    const issue = {
      path: 'SKILL.md',
      line: 3,
      severity: 'ERROR',
      code: 'SKILL_PACKAGE_INVALID',
      message: 'invalid YAML'
    }
    http.post
      .mockResolvedValueOnce({ valid: false, issues: [issue] })
      .mockResolvedValueOnce({ version_id: '31', definition_revision: 9 })
    expect(await api.validate('12', 4)).toEqual({ valid: false, issues: [issue] })
    expect(await api.publish('12', 4, 8)).toEqual({ versionId: '31', definitionRevision: 9 })
    expect(http.post).toHaveBeenNthCalledWith(
      1,
      '/admin/capabilities/skill-drafts/validate',
      { draft_id: '12', revision: 4 },
      undefined
    )
    expect(http.post).toHaveBeenNthCalledWith(
      2,
      '/admin/capabilities/skill-drafts/publish',
      { draft_id: '12', revision: 4, definition_revision: 8 },
      undefined
    )
    expect(http.get).not.toHaveBeenCalled()
  })

  it('格式有效时保留未支持扩展和未核实环境的提示级别', async () => {
    const { http, api } = fixture()
    const issues = [
      {
        path: 'SKILL.md',
        line: null,
        severity: 'WARNING',
        code: 'SKILL_EXECUTION_EXTENSION_UNSUPPORTED',
        message: 'hooks 已原样保留；不解释或执行'
      },
      {
        path: 'SKILL.md',
        line: null,
        severity: 'INFO',
        code: 'SKILL_COMPATIBILITY_DECLARED',
        message: '兼容性仅为包作者声明'
      }
    ]
    http.post.mockResolvedValue({ valid: true, issues })
    expect(await api.validate('12', 4)).toEqual({ valid: true, issues })
    expect(http.post).toHaveBeenCalledTimes(1)
    expect(http.put).not.toHaveBeenCalled()
  })
})

it.each([true, false, null])(
  'maps exact read-only plan requirement %s and excludes it from the save wire',
  async (requiresPlan) => {
    const { http, api } = fixture()
    http.get.mockResolvedValue({ ...wire, requires_plan: requiresPlan })
    const read = await api.getVersion('22')
    expect(read.requiresPlan).toBe(requiresPlan)
    http.put.mockResolvedValue({ ...wire, requires_plan: null })
    const saved = await api.save({ ...read, draftId: read.id })
    expect(http.put.mock.calls[0]![1]).not.toHaveProperty('requires_plan')
    expect(http.put.mock.calls[0]![1]).not.toHaveProperty('requiresPlan')
    expect(saved.requiresPlan).toBeNull()
  }
)
