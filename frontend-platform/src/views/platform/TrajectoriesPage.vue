<script setup lang="ts">
import { computed } from 'vue'
import { AdminPage } from '@skillforge/shared/admin'
import TrajectoryListView from '@skillforge/shared/trajectory-list/TrajectoryListView.vue'
import { api } from '../../api'
import { loadTenantOptions, resolveTenantOption } from '../../api/entityOptions'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const identity = computed(() => `${auth.sessionIdentity}:${auth.identityVersion}`)
</script>

<template>
  <AdminPage title="Agent 轨迹" description="按机构和执行事实检索轨迹，查看问答、调用及异常位置。">
    <TrajectoryListView
      :load="api.trajectories.list"
      :identity="auth.sessionIdentity ? identity : ''"
      detail-path="/platform/trajectories/detail"
      :load-tenants="loadTenantOptions"
      :resolve-tenant="resolveTenantOption"
      show-tenant
    />
  </AdminPage>
</template>
