import { createApp, computed } from 'vue'
import { initializeAdminTheme } from '@skillforge/shared/admin'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import { configureFeedback } from '@skillforge/shared/feedback'
import { commonZhCN } from '@skillforge/shared/i18n'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import { useAuthStore } from './stores/auth'
import { adminMessages } from './i18n/zh-CN'
import App from './App.vue'
import router from './router'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './style.css'
export async function bootstrap() {
  initializeAdminTheme()
  const app = createApp(App)
  const i18n = createI18n({
    legacy: false,
    locale: 'zh-CN',
    messages: { 'zh-CN': { common: commonZhCN, ...adminMessages['zh-CN'] } }
  })
  app.use(createPinia()).use(i18n).use(ElementPlus).use(router)
  app.directive(
    'permission',
    createPermissionDirective(() => computed(() => useAuthStore().user?.permissions))
  )
  configureFeedback(app._context, (key, params) => i18n.global.t(key, params ?? {}))
  await router.isReady()
  app.mount('#app')
}
