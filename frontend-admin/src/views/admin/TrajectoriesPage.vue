<script setup lang="ts">
import { computed } from 'vue'
import { AdminPage } from '@skillforge/shared/admin'
import TrajectoryListView from '@skillforge/shared/trajectory-list/TrajectoryListView.vue'
import { api } from '../../api'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const identity = computed(() => `${auth.sessionIdentity}:${auth.identityVersion}`)
</script>

<template>
  <AdminPage title="Agent 轨迹" description="查找当前机构有权查看的输入、执行过程与结果。">
    <TrajectoryListView
      :load="api.trajectories.list"
      :identity="auth.sessionIdentity ? identity : ''"
      detail-path="/admin/trajectories/detail"
    />
  </AdminPage>
</template>
