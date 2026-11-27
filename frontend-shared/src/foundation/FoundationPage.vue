<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPage from '../admin/AdminPage.vue'
import type { HttpClient, RequestOptions } from '../utils/http'
import AccessPage from './access/AccessPage.vue'
import CatalogPage from './catalog/CatalogPage.vue'
import MessagingPage from './messaging/MessagingPage.vue'
import FoundationFilesPage from './assets/FoundationFilesPage.vue'
import FoundationJobsPage from './assets/FoundationJobsPage.vue'
import OperationsPage from './operations/OperationsPage.vue'
import SecuritySettingsPage from './security/SecuritySettingsPage.vue'
import type { SecurityApi, SecurityAuthApi, SecurityAuthTokens } from './security/api'

const props = defineProps<{
  request: HttpClient['request']
  platform: boolean
  area: string
  permissions: string[]
  securityApi: SecurityApi
  securityAuthApi: SecurityAuthApi
  onAuthentication: (tokens: SecurityAuthTokens) => Promise<void>
  onLogout: () => void
}>()
const base = computed(() => (props.platform ? '/admin' : '/tenant'))
const route = useRoute()
const router = useRouter()
const assetTab = computed(() => {
  const available = ['files', 'jobs'].filter((tab) =>
    props.permissions.includes(`foundation.${tab}.read`)
  )
  return (
    available.find((tab) => tab === route.query.tab) ||
    (props.area === 'jobs' && available.includes('jobs') ? 'jobs' : available[0])
  )
})
/** 路由守卫确认离开后才切页签；取消时保留高亮和原表单。 */
async function changeAssetTab(value: string | number) {
  return !(await router.replace({ query: { ...route.query, tab: String(value) } }))
}
function localRequest<T>(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  body?: unknown
): Promise<T> {
  return props.request<T>(method, `${base.value}/${path}`, body)
}
function messagingRequest<T>(
  method: 'GET' | 'POST' | 'PUT',
  path: string,
  body?: unknown,
  options?: RequestOptions
): Promise<T> {
  return props.request<T>(method, path, body, false, options)
}
</script>

<template>
  <AccessPage
    v-if="area === 'access'"
    :request="localRequest"
    :platform="platform"
    :permissions="permissions"
  />
  <CatalogPage
    v-else-if="area === 'catalog'"
    :request="localRequest"
    :platform="platform"
    :permissions="permissions"
  />
  <SecuritySettingsPage
    v-else-if="area === 'security'"
    :platform="platform"
    :request="localRequest"
    :api="securityApi"
    :auth-api="securityAuthApi"
    :permissions="permissions"
    :on-authentication="onAuthentication"
    :on-logout="onLogout"
  />
  <MessagingPage
    v-else-if="area === 'messaging'"
    :request="messagingRequest"
    :base-path="`${base}/foundation/messaging`"
    :permissions="permissions"
  />
  <AdminPage
    v-else-if="area === 'assets' || area === 'jobs'"
    :title="area === 'jobs' ? '管理任务' : '文件与管理任务'"
    aria-label="文件和管理任务"
  >
    <ElTabs :model-value="assetTab" :before-leave="changeAssetTab">
      <ElTabPane v-if="permissions.includes('foundation.files.read')" label="公共文件" name="files"
        ><FoundationFilesPage
          v-if="assetTab === 'files'"
          :request="request"
          :api-base="`${base}/foundation`"
          :permissions="permissions"
      /></ElTabPane>
      <ElTabPane
        v-if="permissions.includes('foundation.jobs.read')"
        label="导入导出与任务"
        name="jobs"
        ><FoundationJobsPage
          v-if="assetTab === 'jobs'"
          :request="request"
          :api-base="`${base}/foundation`"
          :catalog-href="`${platform ? '/platform' : '/admin'}/foundation/catalog?tab=dictionaries`"
          :permissions="permissions"
      /></ElTabPane>
    </ElTabs>
  </AdminPage>
  <OperationsPage
    v-else-if="area === 'operations'"
    :request="request"
    :api-base="`${base}/foundation/operations`"
    :permissions="permissions"
  />
</template>
