import { createApp } from 'vue'
import { computed } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import { configureFeedback } from '@skillforge/shared/feedback'
import { commonZhCN } from '@skillforge/shared/i18n/common.zh-CN'
import './style.css'
import App from './App.vue'
import { initializeAdminTheme } from '@skillforge/shared/admin'
initializeAdminTheme()
import router from './router'
import { createPermissionDirective } from '@skillforge/shared/directives/permission'
import { useAuthStore } from './stores/auth'
const app = createApp(App)
app.use(createPinia())
const auth = useAuthStore()
app.directive(
  'permission',
  createPermissionDirective(() => computed(() => auth.user?.permissions))
)
app.use(ElementPlus, { locale: zhCn })
configureFeedback(app._context, (key, params) => {
  let value: unknown = { common: commonZhCN }
  for (const part of key.split('.'))
    value =
      value && typeof value === 'object' ? (value as Record<string, unknown>)[part] : undefined
  return typeof value === 'string'
    ? value.replace(/\{(\w+)\}/g, (match, name: string) =>
        params?.[name] === undefined ? match : String(params[name])
      )
    : key
})
app.use(router)
app.mount('#app')
