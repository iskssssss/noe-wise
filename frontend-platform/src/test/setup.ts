import { config } from '@vue/test-utils'
import ElementPlus from 'element-plus'
if (!window.matchMedia)
  Object.defineProperty(window, 'matchMedia', {
    value: () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  })
if (!globalThis.ResizeObserver)
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
config.global.plugins = [ElementPlus]
