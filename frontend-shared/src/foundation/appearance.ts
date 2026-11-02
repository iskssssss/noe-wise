import { getCurrentInstance, inject, onScopeDispose, provide, ref, watch } from 'vue'
import type { InjectionKey, Ref } from 'vue'
import type { HttpClient } from '../utils/http'
import type { Branding, PersonalPreference } from './catalog/types'

export type ThemePreference = 'system' | 'light' | 'dark'
export type SavePersonalPreference = Omit<PersonalPreference, 'revision'> & {
  expected_revision: number
}
export type SaveBranding = Omit<Branding, 'revision'> & { expected_revision: number }
export interface FoundationAppearance {
  personal: Ref<PersonalPreference | null>
  branding: Ref<Branding | null>
  loading: Ref<boolean>
  saving: Ref<boolean>
  error: Ref<string>
  reload: () => Promise<void>
  savePersonal: (command: SavePersonalPreference) => Promise<PersonalPreference>
  saveBranding: (command: SaveBranding) => Promise<Branding>
}
const appearanceKey: InjectionKey<FoundationAppearance> = Symbol('foundation-appearance')

/** 同一宿主壳提供本人偏好的唯一已保存视图；身份变化撤销旧响应的界面更新权。 */
export function useFoundationAppearance(
  request: HttpClient['request'],
  prefix: '/admin' | '/tenant',
  identity: () => string,
  permissions: () => readonly string[]
): FoundationAppearance {
  const personal = ref<PersonalPreference | null>(null)
  const branding = ref<Branding | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const error = ref('')
  let generation = 0
  let readController: AbortController | undefined
  const base = `${prefix}/foundation/catalog`
  function message(cause: unknown) {
    return cause instanceof Error ? cause.message : '展示设置操作失败'
  }
  async function reload() {
    const version = ++generation
    readController?.abort()
    const controller = new AbortController()
    readController = controller
    personal.value = null
    branding.value = null
    error.value = ''
    saving.value = false
    if (!identity()) {
      loading.value = false
      return
    }
    loading.value = true
    const options = { signal: controller.signal, silent: true }
    const results = await Promise.allSettled([
      permissions().includes('foundation.preferences.manage')
        ? request<PersonalPreference>('GET', `${base}/preferences`, undefined, false, options)
        : Promise.resolve(null),
      prefix === '/tenant' && permissions().includes('foundation.settings.read')
        ? request<Branding>('GET', `${base}/branding`, undefined, false, options)
        : Promise.resolve(null)
    ])
    if (version !== generation || controller.signal.aborted) return
    const [preferenceResult, brandingResult] = results
    if (preferenceResult.status === 'fulfilled') personal.value = preferenceResult.value
    if (brandingResult.status === 'fulfilled') branding.value = brandingResult.value
    error.value = results
      .filter((result): result is PromiseRejectedResult => result.status === 'rejected')
      .map((result) => message(result.reason))
      .join('；')
    loading.value = false
  }
  /** 完整 CAS 不合并旧修订；成功后壳与设置页共享服务端返回的事实。 */
  async function savePersonal(command: SavePersonalPreference) {
    if (saving.value) throw new Error('展示设置正在保存，请等待本次保存结果')
    const version = generation
    saving.value = true
    error.value = ''
    try {
      const result = await request<PersonalPreference>(
        'PUT',
        `${base}/preferences`,
        { ...command },
        false,
        { silent: true }
      )
      if (version !== generation) throw new Error('账号已变化，请在当前账号重新操作')
      personal.value = result
      return result
    } catch (cause) {
      if (version === generation) error.value = message(cause)
      throw cause
    } finally {
      if (version === generation) saving.value = false
    }
  }
  /** 机构品牌保存仅在当前机构完成；迟到结果不得污染另一身份的壳。 */
  async function saveBranding(command: SaveBranding) {
    if (saving.value) throw new Error('展示设置正在保存，请等待本次保存结果')
    const version = generation
    saving.value = true
    error.value = ''
    try {
      const result = await request<Branding>('PUT', `${base}/branding`, { ...command }, false, {
        silent: true
      })
      if (version !== generation) throw new Error('账号已变化，请在当前账号重新操作')
      branding.value = result
      return result
    } catch (cause) {
      if (version === generation) error.value = message(cause)
      throw cause
    } finally {
      if (version === generation) saving.value = false
    }
  }
  const appearance = {
    personal,
    branding,
    loading,
    saving,
    error,
    reload,
    savePersonal,
    saveBranding
  }
  if (getCurrentInstance()) provide(appearanceKey, appearance)
  watch(() => `${identity()}:${permissions().join(',')}`, reload, { immediate: true })
  onScopeDispose(() => {
    generation++
    readController?.abort()
  })
  return appearance
}

/** 设置页读取所属宿主提供的已保存偏好，不创建并行浏览器存储。 */
export function useFoundationAppearanceState() {
  return inject(appearanceKey, undefined)
}

/** 每个列表取得本人服务端默认的独立页大小，列表内改变不覆盖个人默认。 */
export function useFoundationPageSize() {
  const appearance = useFoundationAppearanceState()
  const size = appearance?.personal.value?.page_size ?? 20
  return ref([10, 20, 50, 100].includes(size) ? size : 20)
}

/** 壳以允许的服务端主题枚举渲染，不接受任意 CSS 或浏览器持久化值。 */
export function appearanceTheme(personal: PersonalPreference | null): ThemePreference {
  return personal?.theme === 'light' || personal?.theme === 'dark' ? personal.theme : 'system'
}

/** 品牌色只接受服务端同款十六进制值，并生成 Element Plus 的实际主题变量。 */
export function brandThemeVariables(color: string | undefined, dark = false) {
  if (!color || !/^#[0-9a-fA-F]{6}$/.test(color)) return {}
  const original = [1, 3, 5].map((offset) => Number.parseInt(color.slice(offset, offset + 2), 16))
  const luminance = (channels: number[]) =>
    channels.reduce((sum, channel, index) => {
      const value = channel / 255
      return (
        sum +
        (value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4) *
          [0.2126, 0.7152, 0.0722][index]!
      )
    }, 0)
  const contrast = (first: number, second: number) =>
    (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05)
  const blend = (source: number[], target: number, ratio: number) =>
    source.map((channel) => Math.round(channel * (1 - ratio) + target * ratio))
  const hex = (channels: number[]) =>
    `#${channels.map((channel) => channel.toString(16).padStart(2, '0')).join('')}`
  // 品牌原值仍由服务端保存；显示色按主题取得可读明度，避免深品牌在暗色表面消失。
  const background = luminance(dark ? [29, 30, 31] : [255, 255, 255])
  let channels = original
  for (let step = 1; contrast(luminance(channels), background) < 4.5 && step <= 100; step++) {
    channels = blend(original, dark ? 255 : 0, step / 100)
  }
  const value = hex(channels)
  const mix = (target: number, ratio: number) => hex(blend(channels, target, ratio))
  const surface = dark ? 20 : 255
  return {
    '--el-color-primary': value,
    '--el-color-primary-light-3': mix(surface, 0.3),
    '--el-color-primary-light-5': mix(surface, 0.5),
    '--el-color-primary-light-7': mix(surface, 0.7),
    '--el-color-primary-light-8': mix(surface, 0.8),
    '--el-color-primary-light-9': mix(surface, 0.9),
    '--el-color-primary-dark-2': mix(dark ? 255 : 0, 0.2),
    '--admin-primary-on': contrast(luminance(channels), 1) >= 4.5 ? '#ffffff' : '#141414'
  }
}
