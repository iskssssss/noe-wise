<script setup lang="ts">
import { computed, inject } from 'vue'
import { institutionWorkspaceEmbeddedKey } from '../institution/workspace'

const props = withDefaults(
  defineProps<{ title: string; description?: string; embedded?: boolean }>(),
  { embedded: undefined }
)
const inheritedEmbedded = inject(institutionWorkspaceEmbeddedKey, false)
const isEmbedded = computed(() => props.embedded ?? inheritedEmbedded)
</script>

<template>
  <section class="admin-page" :class="{ 'admin-page--embedded': isEmbedded }">
    <header class="admin-page__header">
      <div>
        <component :is="isEmbedded ? 'h2' : 'h1'">{{ title }}</component>
        <p v-if="description">{{ description }}</p>
      </div>
      <div v-if="$slots.extra" class="admin-actions"><slot name="extra" /></div>
    </header>
    <div class="admin-page__body"><slot /></div>
  </section>
</template>

<style scoped>
.admin-page--embedded > .admin-page__header {
  margin-bottom: 16px;
  gap: 16px;
}
.admin-page--embedded > .admin-page__header h2 {
  margin: 0 0 6px;
  font-size: 18px;
  line-height: 1.5;
  font-weight: 600;
}
.admin-page--embedded > .admin-page__body {
  gap: 16px;
}
</style>
