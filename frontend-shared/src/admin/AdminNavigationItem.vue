<script setup lang="ts">
import { computed, nextTick, ref, useId } from 'vue'
import { RouterLink } from 'vue-router'
import { ElIcon, ElMenuItem, ElSubMenu, ElTooltip } from 'element-plus'
import type { SubMenuInstance } from 'element-plus'
import { Folder } from 'lucide-vue-next'
import type { AdminMenu } from './types'
import { navigationIcon } from './navigationIcons'
import { navigationKeys } from './navigationTree'

const props = defineProps<{
  item: AdminMenu
  index: string
  collapsed: boolean
  activePath?: string
  home: string
}>()
const childKeys = computed(() => navigationKeys(props.item.children ?? [], props.index))
const emit = defineEmits<{
  activate: [event: MouseEvent, path: string]
  groupOpen: [index: string, open: boolean]
}>()
// Element Plus 的 expose({ opened }) 未包含在当前 SubMenuInstance 声明中。
const subMenu = ref<SubMenuInstance & { opened: boolean }>()
const groupButton = ref<HTMLButtonElement>()
const groupId = useId()

async function openGroup(open: boolean, focusChild = false) {
  emit('groupOpen', props.index, open)
  if (open && focusChild) {
    await nextTick()
    // 收起侧栏的子项被传送到浮层，键盘展开后直接进入该组第一个真实入口。
    document
      .querySelector<HTMLElement>(
        `[data-admin-nav-parent="${groupId}"] .admin-menu-link, [data-admin-nav-parent="${groupId}"] .admin-nav-group-button`
      )
      ?.focus()
  }
}
function closeGroup() {
  emit('groupOpen', props.index, false)
  groupButton.value?.focus()
}
</script>

<template>
  <ElSubMenu
    v-if="item.children?.length"
    ref="subMenu"
    :index="index"
    :aria-label="item.title"
    :teleported="true"
    popper-class="admin-nav-popup"
  >
    <template #title>
      <button
        ref="groupButton"
        type="button"
        class="admin-nav-group-button"
        :aria-label="item.title"
        :aria-expanded="!!subMenu?.opened"
        :title="collapsed ? item.title : undefined"
        @click.stop="openGroup(!subMenu?.opened, $event.detail === 0)"
        @keydown.down.prevent.stop="openGroup(true, true)"
        @keydown.esc.prevent.stop="closeGroup"
      >
        <ElIcon class="admin-nav-mark" aria-hidden="true"
          ><Folder :size="18" :stroke-width="1.7"
        /></ElIcon>
        <span v-if="!collapsed" class="admin-nav-group-title">{{ item.title }}</span>
      </button>
    </template>
    <AdminNavigationItem
      v-for="(child, childIndex) in item.children"
      :key="childKeys[childIndex]"
      :item="child"
      :index="childKeys[childIndex]!"
      :collapsed="false"
      :active-path="activePath"
      :home="home"
      :data-admin-nav-parent="groupId"
      @activate="(event, path) => emit('activate', event, path)"
      @group-open="(key, open) => emit('groupOpen', key, open)"
      @keydown.esc.prevent.stop="closeGroup"
    />
  </ElSubMenu>
  <ElMenuItem v-else :index="item.path ?? home" :aria-label="item.title">
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
