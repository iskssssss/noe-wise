<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AdminPage from '../admin/AdminPage.vue'
import { confirmPageLeave } from '../admin/leave'
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
const assetTab = ref(
  props.area !== 'jobs' && props.permissions.includes('foundation.files.read') ? 'files' : 'jobs'
)
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
watch(
  () => [props.area, props.permissions.join(',')],
  () => {
    assetTab.value =
      props.area === 'jobs' || !props.permissions.includes('foundation.files.read')
        ? 'jobs'
        : 'files'
  }
)
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
    :title="assetTab === 'files' ? '公共文件' : '管理任务'"
    aria-label="文件和管理任务"
  >
    <ElTabs v-model="assetTab" :before-leave="confirmPageLeave">
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
          :permissions="permissions"
      /></ElTabPane>
    </ElTabs>
  </AdminPage>
  <OperationsPage
    v-else-if="area === 'operations'"
    :request="request"
    :api-base="`${base}/foundation/operations`"
    :audit-options-path="platform ? undefined : '/audit/options'"
    :permissions="permissions"
  />
</template>
