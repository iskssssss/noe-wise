/// <reference types="node" />
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { verifyWorkflowResourceIntegrity } from './workflowResourceIntegrity'

const unicodeResource = {
  path: 'scripts/unicode.js',
  content_base64: '5Lit5paH8J+MjQ==',
  sha256: 'a0e62e8848f5dc932c62bc6eb448ceee71268b15ed0031ebf74e02621bbc42c2'
}

beforeEach(() => vi.stubGlobal('crypto', webcrypto))
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('工作流导入资源真实字节完整性', () => {
  it('空资源不依赖摘要引擎，允许未提供或空数组', async () => {
    vi.stubGlobal('crypto', undefined)
    await expect(verifyWorkflowResourceIntegrity(undefined)).resolves.toBeUndefined()
    await expect(verifyWorkflowResourceIntegrity([])).resolves.toBeUndefined()
  })

  it('按规范Base64解码后的Unicode原始字节计算真实SHA-256，不改变原资源', async () => {
    const digest = vi.spyOn(webcrypto.subtle, 'digest')
    const resources = Object.freeze([Object.freeze({ ...unicodeResource })])
    await expect(verifyWorkflowResourceIntegrity(resources)).resolves.toBeUndefined()
    expect(digest).toHaveBeenCalledExactlyOnceWith(
      'SHA-256',
      new Uint8Array([0xe4, 0xb8, 0xad, 0xe6, 0x96, 0x87, 0xf0, 0x9f, 0x8c, 0x8d])
    )
    expect(resources).toEqual([unicodeResource])
  })

  it('空文件使用真实空字节摘要，不能把空文件当成缺失资源', async () => {
    await expect(
      verifyWorkflowResourceIntegrity([
        {
          path: 'scripts/empty.py',
          content_base64: '',
          sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
        }
      ])
    ).resolves.toBeUndefined()
  })

  it('逐个检查资源，后续摘要不匹配时只报告路径和中文错误', async () => {
    const source = 'PRIVATE_SOURCE_CONTENT'
    const resources = [
      unicodeResource,
      {
        path: 'scripts/mismatch.py',
        content_base64: btoa(source),
        sha256: '0'.repeat(64)
      }
    ]
    await expect(verifyWorkflowResourceIntegrity(resources)).rejects.toThrow(
      new Error('脚本资源 SHA-256 摘要与内容不一致：scripts/mismatch.py')
    )
    expect(resources[1]).toEqual({
      path: 'scripts/mismatch.py',
      content_base64: btoa(source),
      sha256: '0'.repeat(64)
    })
  })

  it('资源格式、重复路径与规范Base64仍由原结构契约拒绝，未开始摘要计算', async () => {
    const digest = vi.spyOn(webcrypto.subtle, 'digest')
    for (const [value, message] of [
      [null, 'resources 必须为脚本资源数组'],
      [
        [{ ...unicodeResource, content_base64: 'not base64!' }],
        '脚本资源必须使用规范 Base64：scripts/unicode.js'
      ],
      [
        [{ ...unicodeResource, sha256: 'invalid' }],
        '脚本摘要必须为 SHA-256 小写十六进制：scripts/unicode.js'
      ],
      [[unicodeResource, unicodeResource], '脚本资源路径重复：scripts/unicode.js']
    ] as const) {
      await expect(verifyWorkflowResourceIntegrity(value)).rejects.toThrow(message)
    }
    expect(digest).not.toHaveBeenCalled()
  })

  it('摘要引擎失败只报告当前路径，不传播可能包含资源内容的底层异常', async () => {
    vi.spyOn(webcrypto.subtle, 'digest').mockRejectedValueOnce(new Error('PRIVATE_SOURCE_CONTENT'))
    await expect(verifyWorkflowResourceIntegrity([unicodeResource])).rejects.toThrow(
      new Error('无法校验脚本资源摘要：scripts/unicode.js')
    )
  })
})
