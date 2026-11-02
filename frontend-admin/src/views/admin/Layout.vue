<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { AdminLayout } from '@skillforge/shared/admin'
import { useFoundationNavigation } from '@skillforge/shared/foundation/navigation'
import { institutionNavigation } from '@skillforge/shared/foundation/institutionNavigation'
import { appearanceTheme, useFoundationAppearance } from '@skillforge/shared/foundation/appearance'
import type { ThemePreference } from '@skillforge/shared/foundation/appearance'
import { foundationRequest } from '../../api'
import { useAuthStore } from '../../stores/auth'
const auth = useAuthStore(),
  router = useRouter()
const identity = computed(() =>
  auth.user ? `${auth.user.session_id}:${auth.user.sub}:${auth.user.authorization_version}` : ''
)
const { menus, error, reload } = useFoundationNavigation(
  foundationRequest,
  '/tenant',
  () => identity.value
)
const workspaceMenus = computed(() =>
  institutionNavigation(menus.value, false, auth.user?.permissions)
)
const {
  personal,
  branding,
  loading: appearanceLoading,
  saving: appearanceSaving,
  error: appearanceError,
  reload: reloadAppearance,
  savePersonal
} = useFoundationAppearance(
  foundationRequest,
  '/tenant',
  () => identity.value,
  () => auth.user?.permissions ?? []
)
// 初次装配等待展示设置；后续重读保留当前页面和未保存草稿。
const appearanceReady = ref(false)
watch(
  appearanceLoading,
  (loading) => {
    if (!loading) appearanceReady.value = true
  },
  { immediate: true }
)
const theme = computed(() => appearanceTheme(personal.value))
const title = computed(() => branding.value?.display_name || '灵知 机构管理')
/** 使用服务端本人完整修订保存主题/导航，失败不把未保存选择伪装成当前设置。 */
async function saveDisplay(change: { theme?: ThemePreference; compact_navigation?: boolean }) {
  const current = personal.value
  if (!current) return
  try {
    await savePersonal({
      theme: current.theme,
      locale: current.locale,
      page_size: current.page_size,
      compact_navigation: current.compact_navigation,
      ...change,
      expected_revision: current.revision
    })
  } catch {
    /* 壳展示具体错误并允许重新读取当前修订。 */
  }
}
function logout() {
  auth.logout()
  void router.replace('/login')
}
watch(
  () => auth.isAuthenticated,
  (value) => {
    if (!value) void router.replace('/login')
  }
)
</script>
<template>
  <AdminLayout
    :title="title"
    :logo-url="branding?.logo_url"
    :primary-color="branding?.primary_color"
    :theme="theme"
    :compact-navigation="personal?.compact_navigation ?? false"
    :appearance-enabled="personal !== null"
    :appearance-saving="appearanceSaving"
    :user-name="auth.user?.username ?? ''"
    :menus="workspaceMenus"
    home="/admin/home"
    @theme-change="(value) => saveDisplay({ theme: value })"
    @compact-change="(value) => saveDisplay({ compact_navigation: value })"
    @logout="logout"
  >
    <FeedbackNotice v-if="error" :title="error" type="error" :closable="false"
      ><ElButton @click="reload">重新加载菜单</ElButton></FeedbackNotice
    >
    <FeedbackNotice v-if="appearanceError" :title="appearanceError" type="error" :closable="false"
      ><ElButton :disabled="appearanceSaving" @click="reloadAppearance"
        >重新读取展示设置</ElButton
      ></FeedbackNotice
    >
    <p v-if="appearanceLoading" role="status">正在读取展示设置…</p>
    <RouterView
      v-if="appearanceReady"
      :key="`${identity}:${$route.meta.institutionWorkspace ? 'institution-workspace' : $route.fullPath}`"
    />
  </AdminLayout>
</template>
