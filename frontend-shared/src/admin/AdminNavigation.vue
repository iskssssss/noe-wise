<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElInput, ElMenu } from 'element-plus'
import { Search } from 'lucide-vue-next'
import type { MenuInstance } from 'element-plus'
import type { AdminMenu } from './types'
import AdminNavigationItem from './AdminNavigationItem.vue'
import {
  expandedNavigation,
  filterNavigation,
  navigationKeys,
  navigationLeaves
} from './navigationTree'

const props = defineProps<{
  title: string
  home: string
  menus: AdminMenu[]
  collapsed: boolean
  activePath?: string
  logoUrl?: string | null
}>()
const emit = defineEmits<{ navigated: [] }>()
const router = useRouter()
const route = useRoute()
const menu = ref<MenuInstance>()
const logoFailed = ref(false)
const query = ref('')
const groups = computed(() => filterNavigation(props.menus, query.value))
const keys = computed(() => navigationKeys(groups.value))
const resultCount = computed(() => navigationLeaves(groups.value).length)
let syncedSignature = ''
watch(
  () => [props.activePath, groups.value, props.collapsed],
  async () => {
    await nextTick()
    if (props.collapsed) return
    // 路由守卫刷新用户信息会重建菜单数组但路径未变，跳过以免先关闭当前分组。
    const signature = `${props.activePath ?? ''}|${query.value.trim()}|${keys.value.join('|')}`
    if (signature === syncedSignature) return
    syncedSignature = signature
    const open = expandedNavigation(groups.value, props.activePath, !!query.value.trim())
    // 非搜索时 unique-opened 会在打开当前分组的同时原子关闭其他分组，不产生空帧。
    if (!open.length) for (const key of keys.value) menu.value?.close(key)
    for (const key of open) menu.value?.open(key)
  },
  { immediate: true, flush: 'post' }
)
watch(
  () => props.collapsed,
  (collapsed) => {
    if (collapsed) query.value = ''
  }
)
watch(
  () => props.logoUrl,
  () => {
    logoFailed.value = false
  }
)

async function select(path: string) {
  try {
    const failure = await router.push(path)
    if (!failure || route.path === path) emit('navigated')
  } finally {
    await nextTick()
    // Element Plus 的键盘选择先更新高亮；路由守卫取消后恢复实际页面。
    menu.value?.updateActiveIndex(props.activePath ?? '')
  }
}
function followLink(event: MouseEvent, path: string) {
  if (
    event.defaultPrevented ||
    event.button !== 0 ||
    event.ctrlKey ||
    event.metaKey ||
    event.shiftKey ||
    event.altKey
  )
    return
  event.preventDefault()
  void select(path)
}
</script>

<template>
  <div class="admin-navigation">
    <RouterLink :to="home" :title="title" class="admin-brand"
      ><span class="admin-brand__icon"
        ><img
          v-if="logoUrl && !logoFailed"
          :src="logoUrl"
          alt=""
          referrerpolicy="no-referrer"
          @error="logoFailed = true"
        /><template v-else>{{ title.slice(0, 1) }}</template></span
      ><strong v-if="!collapsed">{{ title }}</strong></RouterLink
    >
    <div v-if="!collapsed" class="admin-nav-search">
      <ElInput
        v-model="query"
        aria-label="查找功能"
        placeholder="查找功能"
        clearable
        @keydown.esc.stop="query = ''"
      >
        <template #prefix><Search :size="16" aria-hidden="true" /></template>
      </ElInput>
      <p v-if="query.trim()" class="admin-field-help" role="status">
        {{ resultCount ? `找到 ${resultCount} 个入口` : '没有匹配的功能，请更换关键词' }}
      </p>
    </div>
    <nav aria-label="主导航">
      <ElMenu
        ref="menu"
        :default-active="activePath"
        :collapse="collapsed"
        :collapse-transition="false"
        :unique-opened="!query.trim()"
        @select="select"
      >
        <AdminNavigationItem
          v-for="(item, index) in groups"
          :key="keys[index]"
          :item="item"
          :index="keys[index]!"
          :collapsed="collapsed"
          :active-path="activePath"
          :home="home"
          @activate="followLink"
        />
      </ElMenu>
    </nav>
  </div>
</template>

<style scoped>
.admin-brand__icon img {
  width: 32px;
  height: 32px;
  object-fit: contain;
  border-radius: 9px;
}
.admin-brand strong {
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
