import { config } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import { adminMessages } from '../i18n/zh-CN'
import { commonZhCN } from '@skillforge/shared/i18n'
if (!window.matchMedia)
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (media: string) => ({
      media,
      matches: false,
      addEventListener() {},
      removeEventListener() {},
      addListener() {},
      removeListener() {},
      dispatchEvent() {
        return false
      }
    })
  })
if (!globalThis.ResizeObserver)
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
const pinia = createPinia()
setActivePinia(pinia)
config.global.plugins = [
  pinia,
  ElementPlus,
  createI18n({
    legacy: false,
    locale: 'zh-CN',
    messages: { 'zh-CN': { common: commonZhCN, ...adminMessages['zh-CN'] } }
  })
]
config.global.directives.permission = { mounted() {} }
