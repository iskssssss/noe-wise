<script setup lang="ts">
import { computed, onMounted, onScopeDispose, provide, ref, watch } from 'vue'
import { ElButton, ElDrawer, ElTabPane, ElTabs, ElTag } from 'element-plus'
import { Building2 } from 'lucide-vue-next'
import { statusLabel } from '../admin/presentation'
import InstitutionDirectory from './InstitutionDirectory.vue'
import FeedbackNotice from '../feedback/FeedbackNotice.vue'
import {
  institutionWorkspaceEmbeddedKey,
  type InstitutionNode,
  type InstitutionSection
} from './workspace'

const props = withDefaults(
  defineProps<{
    nodes: InstitutionNode[]
    selectedId: string
    sections: InstitutionSection[]
    section: string
    title?: string
    actorLabel: string
    loading?: boolean
    error?: string
    loadedCount?: number
    total?: number
    directoryComplete?: boolean
  }>(),
  { title: '机构工作台', loading: false, error: '', directoryComplete: true }
)
const emit = defineEmits<{
  select: [id: string]
  section: [key: string]
  refresh: []
}>()
provide(institutionWorkspaceEmbeddedKey, true)
const root = ref<HTMLElement>()
const narrow = ref(false)
const directoryOpen = ref(false)
const query = ref('')
const selected = computed(() => props.nodes.find((node) => node.id === props.selectedId))
const hasSelectedSection = computed(() => props.sections.some((item) => item.key === props.section))
let observer: ResizeObserver | undefined

/** 以工作台实际可用宽度收拢目录，不把浏览器宽度当成内容区宽度。 */
function measure(width: number) {
  if (width <= 0) return
  narrow.value = width < 880
  if (!narrow.value) directoryOpen.value = false
}
onMounted(() => {
  if (root.value) measure(root.value.getBoundingClientRect().width)
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry) measure(entry.contentRect.width)
    })
    if (root.value) observer.observe(root.value)
  }
})
onScopeDispose(() => observer?.disconnect())
watch(
  () => props.selectedId,
  (id, prior) => {
    if (id !== prior) directoryOpen.value = false
  }
)
/** Tabs 先提交导航意图；只有父级接受后的 prop 更新才真正切换内容和高亮。 */
function beforeSectionChange(value: string | number) {
  const key = String(value)
  if (key === props.section) return true
  const section = props.sections.find((item) => item.key === key)
  if (!section || section.disabled) return false
  emit('section', key)
  return false
}
function select(id: string) {
  emit('select', id)
  if (id === props.selectedId) directoryOpen.value = false
}
</script>

<template>
  <section
    ref="root"
    class="institution-workspace"
    :class="{ 'institution-workspace--narrow': narrow }"
  >
    <FeedbackNotice v-if="error" :title="error" type="error" />
    <header class="institution-workspace__header">
      <div>
        <h1>{{ title }}</h1>
        <p class="institution-workspace__actor">当前操作身份：{{ actorLabel }}</p>
      </div>
      <div class="institution-workspace__actions">
        <ElButton v-if="narrow" :aria-expanded="directoryOpen" @click="directoryOpen = true">
          <Building2 :size="16" aria-hidden="true" />选择机构
        </ElButton>
        <slot name="extra" />
      </div>
    </header>
    <div class="institution-workspace__layout">
      <aside v-if="!narrow" class="institution-workspace__directory" aria-label="机构导航">
        <InstitutionDirectory
          :nodes="nodes"
          :selected-id="selectedId"
          :query="query"
          :loading="loading"
          :error="error"
          :loaded-count="loadedCount"
          :total="total"
          :directory-complete="directoryComplete"
          @update-query="query = $event"
          @select="select"
          @refresh="emit('refresh')"
        />
      </aside>
      <div class="institution-workspace__main">
        <div class="institution-workspace__summary">
          <div class="institution-workspace__target">
            <div>
              <p class="institution-workspace__caption">当前维护目标</p>
              <h2>{{ selected?.label ?? (selectedId ? '所选机构' : '尚未选择机构') }}</h2>
            </div>
            <ElTag v-if="selected?.status" effect="plain">{{ statusLabel(selected.status) }}</ElTag>
          </div>
          <p v-if="selectedId" class="institution-workspace__identity">
            机构标识：{{ selectedId }}
          </p>
          <p v-if="selected?.description" class="institution-workspace__description">
            {{ selected.description }}
          </p>
          <slot name="summary" />
        </div>
        <ElTabs
          v-if="sections.length"
          class="institution-workspace__sections"
          :model-value="section"
          :before-leave="beforeSectionChange"
        >
          <ElTabPane
            v-for="item in sections"
            :key="item.key"
            :name="item.key"
            :disabled="item.disabled"
          >
            <template #label
              ><span :title="item.reason"
                >{{ item.label }}<span v-if="item.disabled">（受限）</span></span
              ></template
            >
            <slot v-if="item.key === section" />
          </ElTabPane>
        </ElTabs>
        <div v-if="!hasSelectedSection" class="institution-workspace__content">
          <slot />
        </div>
      </div>
    </div>
    <ElDrawer
      v-if="narrow"
      v-model="directoryOpen"
      title="选择机构"
      direction="ltr"
      size="360px"
      class="institution-workspace-drawer"
      append-to-body
      destroy-on-close
    >
      <InstitutionDirectory
        :nodes="nodes"
        :selected-id="selectedId"
        :query="query"
        :loading="loading"
        :error="error"
        :loaded-count="loadedCount"
        :total="total"
        :directory-complete="directoryComplete"
        @update-query="query = $event"
        @select="select"
        @refresh="emit('refresh')"
      />
    </ElDrawer>
  </section>
</template>

<style scoped>
.institution-workspace {
  min-width: 0;
}
.institution-workspace__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 20px;
}
.institution-workspace__header h1 {
  font-size: 24px;
  line-height: 1.4;
  font-weight: 600;
  margin: 0 0 8px;
}
.institution-workspace__actor {
  margin: 0;
  color: var(--admin-muted);
  font-size: 14px;
  overflow-wrap: anywhere;
}
.institution-workspace__actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.institution-workspace__actions .el-button + .el-button {
  margin-left: 0;
}
.institution-workspace__actions .el-button svg {
  margin-right: 6px;
}
.institution-workspace__layout {
  display: grid;
  grid-template-columns: minmax(220px, 270px) minmax(0, 1fr);
  align-items: start;
  gap: 20px;
}
.institution-workspace__directory {
  min-width: 0;
  border: 1px solid var(--admin-border);
  background: var(--admin-surface);
  border-radius: 10px;
  padding: 16px;
}
.institution-workspace__main {
  min-width: 0;
}
.institution-workspace__summary {
  border-bottom: 1px solid var(--admin-border);
  padding: 0 0 18px;
  margin-bottom: 10px;
}
.institution-workspace__target {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.institution-workspace__target h2 {
  margin: 4px 0 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.institution-workspace__caption {
  color: var(--admin-muted);
  margin: 0;
  font-size: 13px;
}
.institution-workspace__identity,
.institution-workspace__description {
  color: var(--admin-muted);
  font-size: 13px;
  line-height: 1.6;
  margin: 6px 0 0;
  overflow-wrap: anywhere;
}
.institution-workspace__identity {
  font-variant-numeric: tabular-nums;
}
.institution-workspace__sections {
  min-width: 0;
}
.institution-workspace__sections :deep(.el-tabs__header) {
  margin-bottom: 20px;
}
.institution-workspace__sections :deep(.el-tabs__content) {
  overflow: visible;
}
.institution-workspace__sections :deep(.el-tab-pane) {
  min-width: 0;
}
.institution-workspace--narrow .institution-workspace__layout {
  grid-template-columns: minmax(0, 1fr);
}
</style>

<style>
.institution-workspace-drawer {
  max-width: calc(100vw - 24px);
}
.institution-workspace-drawer .el-drawer__header {
  margin-bottom: 0;
  padding: 20px;
  border-bottom: 1px solid var(--admin-border);
}
.institution-workspace-drawer .el-drawer__body {
  padding: 20px;
}
</style>
