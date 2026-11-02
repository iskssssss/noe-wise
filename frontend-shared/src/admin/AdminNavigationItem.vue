<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ElIcon, ElMenuItem, ElSubMenu, ElTooltip } from 'element-plus'
import { Folder } from 'lucide-vue-next'
import type { AdminMenu } from './types'
import { navigationIcon } from './navigationIcons'

defineProps<{
  item: AdminMenu
  index: string
  collapsed: boolean
  activePath?: string
  home: string
}>()
const emit = defineEmits<{ activate: [event: MouseEvent, path: string] }>()
</script>

<template>
  <ElSubMenu
    v-if="item.children?.length"
    :index="index"
    :teleported="true"
    popper-class="admin-nav-popup"
  >
    <template #title>
      <ElIcon class="admin-nav-mark" aria-hidden="true"
        ><Folder :size="18" :stroke-width="1.7"
      /></ElIcon>
      <span class="admin-nav-group-title">{{ item.title }}</span>
    </template>
    <AdminNavigationItem
      v-for="(child, childIndex) in item.children"
      :key="child.path ?? `${index}.${childIndex}`"
      :item="child"
      :index="`${index}.${childIndex}`"
      :collapsed="false"
      :active-path="activePath"
      :home="home"
      @activate="(event, path) => emit('activate', event, path)"
    />
  </ElSubMenu>
  <ElMenuItem v-else :index="item.path ?? home">
    <ElTooltip
      :content="item.title"
      :disabled="!collapsed"
      placement="right"
      :trigger="['hover', 'focus']"
    >
      <RouterLink v-slot="{ href }" :to="item.path ?? home" custom>
        <a
          :href="href"
          :title="item.title"
          :aria-current="activePath === item.path ? 'page' : undefined"
          class="admin-menu-link"
          @click.stop="emit('activate', $event, item.path ?? home)"
        >
          <span class="admin-nav-mark" aria-hidden="true"
            ><component :is="navigationIcon(item.path)" :size="18" :stroke-width="1.7"
          /></span>
          <span v-if="!collapsed" class="admin-menu-label">{{ item.title }}</span>
        </a>
      </RouterLink>
    </ElTooltip>
  </ElMenuItem>
</template>
