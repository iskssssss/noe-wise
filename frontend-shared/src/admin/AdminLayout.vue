<script setup lang="ts">
import { computed, ref, watch, onScopeDispose } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import {
  ElBreadcrumb,
  ElBreadcrumbItem,
  ElButton,
  ElDropdown,
  ElDropdownMenu,
  ElDropdownItem,
  ElDrawer
} from 'element-plus'
import { Menu, Sun, Moon, Monitor, ChevronRight, LogOut } from 'lucide-vue-next'
import type { AdminMenu } from './types'
import AdminNavigation from './AdminNavigation.vue'
import { navigationLeaves } from './navigationTree'
import { confirmPageLeave } from './leave'
import { brandThemeVariables } from '../foundation/appearance'
import type { ThemePreference } from '../foundation/appearance'

const props = withDefaults(
  defineProps<{
    title: string
    userName: string
    menus: AdminMenu[]
    home: string
    theme?: ThemePreference
    compactNavigation?: boolean
    logoUrl?: string | null
    primaryColor?: string
    appearanceSaving?: boolean
    appearanceEnabled?: boolean
  }>(),
  { theme: 'system', compactNavigation: false, appearanceSaving: false, appearanceEnabled: false }
)
const emit = defineEmits<{
  logout: []
  themeChange: [theme: ThemePreference]
  compactChange: [compact: boolean]
}>()
const route = useRoute()
const collapsed = computed(() => props.compactNavigation)
const theme = computed(() => props.theme)
const navigationMedia = window.matchMedia('(max-width: 760px)')
const narrow = ref(navigationMedia.matches)
const mobileNavigationOpen = ref(false)
const navigationToggle = ref<{ $el: HTMLButtonElement }>()
const updateNavigationViewport = () => {
  narrow.value = navigationMedia.matches
  if (!narrow.value) mobileNavigationOpen.value = false
}
navigationMedia.addEventListener('change', updateNavigationViewport)
watch(
  () => route.fullPath,
  () => {
    if (narrow.value) mobileNavigationOpen.value = false
  }
)
const media = window.matchMedia('(prefers-color-scheme: dark)')
const systemDark = ref(media.matches)
const dark = computed(
  () => theme.value === 'dark' || (theme.value === 'system' && systemDark.value)
)
const themeLabel = computed(
  () => ({ light: '浅色', dark: '深色', system: '跟随系统' })[theme.value] ?? '跟随系统'
)
const updateSystemTheme = () => {
  systemDark.value = media.matches
}
media.addEventListener('change', updateSystemTheme)
const themeVariables = [
  '--el-color-primary',
  '--el-color-primary-light-3',
  '--el-color-primary-light-5',
  '--el-color-primary-light-7',
  '--el-color-primary-light-8',
  '--el-color-primary-light-9',
  '--el-color-primary-dark-2',
  '--admin-primary-on'
]
const previousVariables = new Map(
  themeVariables.map((name) => [name, document.documentElement.style.getPropertyValue(name)])
)
// 未配置品牌时也处理组件库实际默认色，不复制另一份默认配色配置。
const defaultPrimaryColor = getComputedStyle(document.documentElement)
  .getPropertyValue('--el-color-primary')
  .trim()
watch(
  () => [props.primaryColor, dark.value] as const,
  ([color, isDark]) => {
    const variables = brandThemeVariables(color ?? defaultPrimaryColor, isDark)
    for (const name of themeVariables) {
      const value =
        Object.entries(variables).find(([key]) => key === name)?.[1] ?? previousVariables.get(name)
      if (value) document.documentElement.style.setProperty(name, value)
      else document.documentElement.style.removeProperty(name)
    }
  },
  { immediate: true }
)
onScopeDispose(() => {
  navigationMedia.removeEventListener('change', updateNavigationViewport)
  media.removeEventListener('change', updateSystemTheme)
  for (const [name, value] of previousVariables) {
    if (value) document.documentElement.style.setProperty(name, value)
    else document.documentElement.style.removeProperty(name)
  }
  document.documentElement.classList.toggle('dark', media.matches)
})
watch(dark, (value) => document.documentElement.classList.toggle('dark', value), {
  immediate: true
})
const activeMenu = computed(
  () =>
    navigationLeaves(props.menus)
      .filter(
        (item) => item.path && (route.path === item.path || route.path.startsWith(`${item.path}/`))
      )
      .sort((a, b) => b.path!.length - a.path!.length)[0]
)
const pageTitle = computed(() => String(route.meta.title ?? activeMenu.value?.title ?? props.title))
watch(
  () => `${pageTitle.value} · ${props.title}`,
  (value) => {
    document.title = value
  },
  { immediate: true }
)
function setTheme(value: ThemePreference) {
  emit('themeChange', value)
}
async function logout() {
  if (await confirmPageLeave()) emit('logout')
}
function toggleNavigation() {
  if (narrow.value) mobileNavigationOpen.value = !mobileNavigationOpen.value
  else emit('compactChange', !collapsed.value)
}
function restoreNavigationFocus() {
  if (narrow.value) navigationToggle.value?.$el.focus()
}
</script>

<template>
  <div
    class="admin-layout"
    :class="{ 'admin-layout--collapsed': collapsed, 'admin-layout--narrow': narrow }"
  >
    <a class="admin-skip-link" href="#main-content">跳到页面内容</a>
    <aside v-if="!narrow" class="admin-sidebar">
      <AdminNavigation
        :title="title"
        :home="home"
        :menus="menus"
        :collapsed="collapsed"
        :active-path="activeMenu?.path"
        :logo-url="logoUrl"
      />
    </aside>
    <div class="admin-main">
      <header class="admin-topbar">
        <ElButton
          ref="navigationToggle"
          text
          :aria-label="
            narrow
              ? mobileNavigationOpen
                ? '关闭导航'
                : '打开导航'
              : collapsed
                ? '展开导航'
                : '收起导航'
          "
          :aria-expanded="narrow ? mobileNavigationOpen : !collapsed"
          :aria-controls="narrow ? 'mobile-main-navigation' : undefined"
          :disabled="!narrow && (appearanceSaving || !appearanceEnabled)"
          @click="toggleNavigation"
          ><Menu :size="20"
        /></ElButton>
        <ElBreadcrumb aria-label="面包屑" class="admin-breadcrumb" :separator-icon="ChevronRight">
          <ElBreadcrumbItem v-if="route.path !== home"
            ><RouterLink :to="home">工作台</RouterLink></ElBreadcrumbItem
          >
          <ElBreadcrumbItem
            v-if="
              activeMenu?.path &&
              activeMenu.path !== home &&
              route.path !== activeMenu.path &&
              activeMenu.title !== pageTitle
            "
            ><RouterLink :to="activeMenu.path">{{ activeMenu.title }}</RouterLink></ElBreadcrumbItem
          >
          <ElBreadcrumbItem
            ><span aria-current="page">{{ pageTitle }}</span></ElBreadcrumbItem
          >
        </ElBreadcrumb>
        <div class="admin-topbar__actions">
          <slot name="actions" />
          <ElDropdown v-if="appearanceEnabled" :disabled="appearanceSaving" @command="setTheme">
            <ElButton text :disabled="appearanceSaving" :aria-label="`切换主题，当前${themeLabel}`"
              ><Monitor v-if="theme === 'system'" :size="18" /><Moon
                v-else-if="dark"
                :size="18" /><Sun v-else :size="18"
            /></ElButton>
            <template #dropdown
              ><ElDropdownMenu>
                <ElDropdownItem command="light" :disabled="theme === 'light'">浅色</ElDropdownItem>
                <ElDropdownItem command="dark" :disabled="theme === 'dark'">深色</ElDropdownItem>
                <ElDropdownItem command="system" :disabled="theme === 'system'"
                  >跟随系统</ElDropdownItem
                >
              </ElDropdownMenu></template
            >
          </ElDropdown>
          <ElDropdown @command="logout"
            ><ElButton text class="admin-user-menu" :title="userName">{{ userName }}</ElButton
            ><template #dropdown
              ><ElDropdownMenu
                ><ElDropdownItem command="logout"
                  ><LogOut :size="16" />退出登录</ElDropdownItem
                ></ElDropdownMenu
              ></template
            ></ElDropdown
          >
        </div>
      </header>
      <main id="main-content" class="admin-content" tabindex="-1">
        <slot><RouterView /></slot>
      </main>
    </div>
    <ElDrawer
      v-if="narrow"
      id="mobile-main-navigation"
      v-model="mobileNavigationOpen"
      title="主导航"
      direction="ltr"
      size="280px"
      class="admin-navigation-drawer"
      @closed="restoreNavigationFocus"
    >
      <AdminNavigation
        class="admin-sidebar admin-sidebar--mobile"
        :title="title"
        :home="home"
        :menus="menus"
        :collapsed="false"
        :active-path="activeMenu?.path"
        :logo-url="logoUrl"
        @navigated="mobileNavigationOpen = false"
      />
    </ElDrawer>
  </div>
</template>
