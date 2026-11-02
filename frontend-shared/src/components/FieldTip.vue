<script setup lang="ts">
import { Info } from 'lucide-vue-next'
defineProps<{ id: string }>()
</script>

<template>
  <p :id="id" class="field-tip" role="note">
    <Info :size="14" aria-hidden="true" />
    <span><slot /></span>
  </p>
</template>

<style scoped>
.field-tip {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  width: 100%;
  margin: 6px 0 0;
  color: var(--el-text-color-regular);
  font-size: var(--el-font-size-small);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.field-tip svg {
  flex-shrink: 0;
  margin-top: 3px;
}
</style>
