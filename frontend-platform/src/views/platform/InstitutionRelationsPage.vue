<script setup lang="ts">
import { AdminPage } from '@skillforge/shared/admin'
import InstitutionHierarchyPanel from './InstitutionHierarchyPanel.vue'
</script>

<template>
  <AdminPage
    title="机构层级"
    description="按当前明确管理范围查看机构树，挂接、迁移或解除机构关系。"
  >
    <InstitutionHierarchyPanel />
  </AdminPage>
</template>
