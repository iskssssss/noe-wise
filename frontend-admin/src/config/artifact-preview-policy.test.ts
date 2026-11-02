import { describe, expect, it } from 'vitest'
import {
  artifactPreviewCsp,
  artifactPreviewOrigin,
  isArtifactPreviewUrlAllowed
} from './artifact-preview-policy'

describe('artifact preview policy', () => {
  it('未配置预览 origin 时仅允许本地代码预览 blob', () => {
    expect(artifactPreviewCsp(undefined)).toBe('frame-src blob:')
  })

  it('只接受规范的 http(s) origin', () => {
    expect(artifactPreviewOrigin('https://preview.example.test')).toBe(
      'https://preview.example.test'
    )
    for (const invalid of [
      'ftp://preview.example.test',
      'https://user:pass@preview.example.test',
      'https://preview.example.test/path',
      'https://preview.example.test?ticket=value',
      'https://preview.example.test/'
    ]) {
      expect(() => artifactPreviewOrigin(invalid)).toThrow('VITE_ARTIFACT_PREVIEW_ORIGIN')
    }
  })

  it('仅允许配置 origin 的跨 host 预览票据', () => {
    const origin = artifactPreviewOrigin('https://preview.example.test')
    expect(
      isArtifactPreviewUrlAllowed(
        'https://preview.example.test/p/ticket/index.html',
        origin,
        'https://app.example.test'
      )
    ).toBe(true)
    expect(
      isArtifactPreviewUrlAllowed(
        'https://other.example.test/p/ticket/index.html',
        origin,
        'https://app.example.test'
      )
    ).toBe(false)
    expect(
      isArtifactPreviewUrlAllowed(
        'https://preview.example.test/p/ticket/index.html',
        origin,
        'https://preview.example.test'
      )
    ).toBe(false)
  })
})
