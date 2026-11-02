import { effectScope, nextTick, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { appearanceTheme, brandThemeVariables, useFoundationAppearance } from './appearance'
import type { HttpClient } from '../utils/http'
import type { Branding, PersonalPreference } from './catalog/types'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
const personal: PersonalPreference = {
  theme: 'dark',
  locale: 'zh-CN',
  page_size: 50,
  compact_navigation: true,
  revision: 7
}
const branding: Branding = {
  display_name: '第一机构',
  logo_url: 'https://example.com/logo.png',
  primary_color: '#123456',
  contact_email: null,
  support_url: null,
  revision: 3
}
const permissions = () => ['foundation.preferences.manage', 'foundation.settings.read']

describe('服务端展示设置', () => {
  it('平台只读取本人偏好，主题保存携带完整当前修订且失败不污染已保存视图', async () => {
    let reject = false
    const request = vi.fn(async (method: string, path: string, body?: unknown) => {
      if (method === 'GET') return personal
      if (reject) throw new Error('修订冲突')
      return { ...(body as object), revision: 8 }
    })
    const scope = effectScope()
    const state = scope.run(() =>
      useFoundationAppearance(
        request as HttpClient['request'],
        '/admin',
        () => 'platform:1',
        permissions
      )
    )!
    await nextTick()
    await Promise.resolve()
    expect(request.mock.calls.map((call) => call[1])).toEqual([
      '/admin/foundation/catalog/preferences'
    ])
    expect(state.branding.value).toBeNull()
    const command = {
      theme: 'light',
      locale: personal.locale,
      page_size: personal.page_size,
      compact_navigation: personal.compact_navigation,
      expected_revision: personal.revision
    }
    await state.savePersonal(command)
    expect(request).toHaveBeenLastCalledWith(
      'PUT',
      '/admin/foundation/catalog/preferences',
      command,
      false,
      { silent: true }
    )
    expect(state.personal.value?.theme).toBe('light')
    expect(state.personal.value?.revision).toBe(8)
    reject = true
    await expect(
      state.savePersonal({ ...command, theme: 'dark', expected_revision: 8 })
    ).rejects.toThrow('修订冲突')
    expect(state.personal.value?.theme).toBe('light')
    expect(state.error.value).toBe('修订冲突')
    scope.stop()
  })

  it('机构同时读取品牌和本人偏好，账号变化后的旧读取无界面写权', async () => {
    const identity = ref('tenant:11:101')
    const oldPersonal = deferred<PersonalPreference>()
    const oldBranding = deferred<Branding>()
    let firstPersonal = true,
      firstBranding = true
    const request = vi.fn(async (_method: string, path: string) => {
      if (path.endsWith('/preferences')) {
        if (firstPersonal) {
          firstPersonal = false
          return oldPersonal.promise
        }
        return { ...personal, theme: 'light', page_size: 10, revision: 1 }
      }
      if (firstBranding) {
        firstBranding = false
        return oldBranding.promise
      }
      return { ...branding, display_name: '第二机构', revision: 1 }
    })
    const scope = effectScope()
    const state = scope.run(() =>
      useFoundationAppearance(
        request as HttpClient['request'],
        '/tenant',
        () => identity.value,
        permissions
      )
    )!
    identity.value = 'tenant:22:202'
    await nextTick()
    await Promise.resolve()
    expect(state.personal.value?.theme).toBe('light')
    expect(state.branding.value?.display_name).toBe('第二机构')
    oldPersonal.resolve(personal)
    oldBranding.resolve(branding)
    await Promise.resolve()
    await Promise.resolve()
    expect(state.personal.value?.page_size).toBe(10)
    expect(state.branding.value?.display_name).toBe('第二机构')
    scope.stop()
  })

  it('主题保存的迟到成功不覆盖新账号，即使请求方没有取消网络写入', async () => {
    const identity = ref('platform:1')
    const saved = deferred<PersonalPreference>()
    const request = vi.fn(async (method: string) =>
      method === 'PUT'
        ? saved.promise
        : { ...personal, theme: identity.value === 'platform:1' ? 'dark' : 'system' }
    )
    const scope = effectScope()
    const state = scope.run(() =>
      useFoundationAppearance(
        request as HttpClient['request'],
        '/admin',
        () => identity.value,
        permissions
      )
    )!
    await nextTick()
    await Promise.resolve()
    const pending = state.savePersonal({
      theme: 'light',
      locale: 'zh-CN',
      page_size: 50,
      compact_navigation: true,
      expected_revision: 7
    })
    identity.value = 'platform:2'
    await nextTick()
    await Promise.resolve()
    saved.resolve({ ...personal, theme: 'light', revision: 8 })
    await expect(pending).rejects.toThrow('账号已变化')
    expect(state.personal.value?.theme).toBe('system')
    expect(state.saving.value).toBe(false)
    scope.stop()
  })

  it('品牌CSS只接受六位十六进制色且主题只接受允许枚举', () => {
    expect(brandThemeVariables('red;display:none')).toEqual({})
    expect(brandThemeVariables('#123456')['--el-color-primary']).toBe('#123456')
    expect(brandThemeVariables('#000000')['--el-color-primary-light-5']).toBe('#808080')
    expect(appearanceTheme(null)).toBe('system')
    expect(appearanceTheme({ ...personal, theme: 'arbitrary' })).toBe('system')
  })
  it('品牌在明暗表面及实心按钮中保持可读，深色派生色不会生成白色浅底', () => {
    function luminance(hex: string) {
      const values = [1, 3, 5].map((offset) => parseInt(hex.slice(offset, offset + 2), 16) / 255)
      const linear = values.map((value) =>
        value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
      )
      return linear[0]! * 0.2126 + linear[1]! * 0.7152 + linear[2]! * 0.0722
    }
    function ratio(a: string, b: string) {
      const values = [luminance(a), luminance(b)].sort((a, b) => b - a)
      return (values[0]! + 0.05) / (values[1]! + 0.05)
    }
    for (const brand of ['#0f766e', '#ffffff', '#000000', '#ffff00']) {
      for (const dark of [false, true]) {
        const variables = brandThemeVariables(brand, dark)
        const primary = variables['--el-color-primary']!
        const foreground = variables['--admin-primary-on']!
        expect(ratio(primary, dark ? '#1d1e1f' : '#ffffff')).toBeGreaterThanOrEqual(4.5)
        expect(ratio(primary, foreground)).toBeGreaterThanOrEqual(4.5)
        expect(ratio(variables['--el-color-primary-dark-2']!, foreground)).toBeGreaterThanOrEqual(
          4.5
        )
        if (dark) expect(luminance(variables['--el-color-primary-light-9']!)).toBeLessThan(0.1)
      }
    }
  })
})
