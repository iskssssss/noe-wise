<script setup lang="ts">
import FeedbackNotice from '../feedback/FeedbackNotice.vue'
import { computed, nextTick, ref, watch } from 'vue'
import { ElButton, ElInput, ElTreeV2, type TreeV2Instance } from 'element-plus'
import { Search } from 'lucide-vue-next'
import { statusLabel } from '../admin/presentation'
import {
  buildInstitutionTree,
  filterInstitutionTree,
  institutionAncestors,
  matchesInstitution,
  type InstitutionNode,
  type InstitutionTreeNode
} from './workspace'

const props = withDefaults(
  defineProps<{
    nodes: InstitutionNode[]
    selectedId: string
    query: string
    loading?: boolean
    error?: string
    loadedCount?: number
    total?: number
    directoryComplete?: boolean
  }>(),
  { loading: false, error: '', directoryComplete: true }
)
const emit = defineEmits<{
  select: [id: string]
  refresh: []
  updateQuery: [value: string]
}>()
const tree = ref<TreeV2Instance>()
const region = ref<HTMLElement>()
const expanded = ref(new Set<string>())
const focusedId = ref(props.selectedId)
const directory = computed(() => {
  try {
    return { roots: buildInstitutionTree(props.nodes), error: '' }
  } catch (reason) {
    return { roots: [], error: reason instanceof Error ? reason.message : '机构目录关系无法读取' }
  }
})
const filtered = computed(() => filterInstitutionTree(directory.value.roots, props.query))
const matches = computed(() => props.nodes.filter((node) => matchesInstitution(node, props.query)))
const visible = computed(() => {
  const result: InstitutionTreeNode[] = []
  function visit(nodes: InstitutionTreeNode[]) {
    for (const node of nodes) {
      result.push(node)
      if (expanded.value.has(node.id)) visit(node.children)
    }
  }
  visit(filtered.value)
  return result
})
const treeHeight = computed(() => Math.min(520, Math.max(160, visible.value.length * 40)))
const count = computed(() => props.loadedCount ?? props.nodes.length)
const directoryError = computed(() => props.error || directory.value.error)
const currentFocusId = computed(() =>
  visible.value.some((node) => node.id === focusedId.value)
    ? focusedId.value
    : (visible.value[0]?.id ?? '')
)

watch(
  () => [props.nodes, props.selectedId, props.query] as const,
  async () => {
    const next = new Set(expanded.value)
    for (const id of institutionAncestors(props.nodes, props.selectedId)) next.add(id)
    if (props.query.trim()) {
      for (const node of matches.value)
        for (const id of institutionAncestors(props.nodes, node.id)) next.add(id)
    }
    expanded.value = next
    focusedId.value = props.selectedId
    await nextTick()
    tree.value?.setExpandedKeys([...next])
    tree.value?.setCurrentKey(props.selectedId)
    if (!props.query.trim() && props.selectedId) tree.value?.scrollToNode(props.selectedId)
  },
  { immediate: true }
)

/** 点击只提交选择意图；父级导航未接受时恢复原对象，不能留下假的选中高亮。 */
async function choose(node: InstitutionNode) {
  emit('select', node.id)
  await nextTick()
  tree.value?.setCurrentKey(props.selectedId)
}
function chooseId(id: unknown) {
  const node = props.nodes.find((item) => item.id === id)
  if (node) void choose(node)
}
function searchKeydown(event: KeyboardEvent | Event) {
  if (!(event instanceof KeyboardEvent) || event.key !== 'Escape') return
  if (event.isComposing) {
    event.stopPropagation()
    return
  }
  if (props.query) {
    event.stopPropagation()
    emit('updateQuery', '')
  }
}
function expand(id: string, value: boolean) {
  const next = new Set(expanded.value)
  if (value) next.add(id)
  else next.delete(id)
  expanded.value = next
}
async function focusNode(id: string) {
  focusedId.value = id
  tree.value?.scrollToNode(id)
  await nextTick()
  const button = [
    ...(region.value?.querySelectorAll<HTMLButtonElement>('[data-institution-id]') ?? [])
  ].find((element) => element.dataset.institutionId === id)
  button?.focus()
}
/** 虚拟树只渲染视口，键盘先滚动到目标再将焦点交给已挂载的名称按钮。 */
function keydown(event: KeyboardEvent, data: InstitutionTreeNode) {
  const index = visible.value.findIndex((node) => node.id === data.id)
  let destination: string | undefined
  if (event.key === 'ArrowDown') destination = visible.value[index + 1]?.id
  else if (event.key === 'ArrowUp') destination = visible.value[index - 1]?.id
  else if (event.key === 'Home') destination = visible.value[0]?.id
  else if (event.key === 'End') destination = visible.value.at(-1)?.id
  else if (event.key === 'ArrowRight' && data.children.length) {
    if (expanded.value.has(data.id)) destination = data.children[0]?.id
    else {
      const node = tree.value?.getNode(data.id)
      if (node) tree.value?.expandNode(node)
      expand(data.id, true)
    }
  } else if (event.key === 'ArrowLeft') {
    if (expanded.value.has(data.id) && data.children.length) {
      const node = tree.value?.getNode(data.id)
      if (node) tree.value?.collapseNode(node)
      expand(data.id, false)
    } else if (data.parentId && visible.value.some((node) => node.id === data.parentId)) {
      destination = data.parentId
    }
  } else return
  event.preventDefault()
  event.stopPropagation()
  if (destination) void focusNode(destination)
}
</script>

<template>
  <div ref="region" class="institution-directory">
    <header class="institution-directory__header">
      <h2>选择机构</h2>
      <ElButton text :loading="loading" @click="emit('refresh')">刷新机构目录</ElButton>
    </header>
    <ElInput
      :model-value="query"
      aria-label="搜索机构名称或标识"
      placeholder="搜索机构名称或标识"
      clearable
      @update:model-value="emit('updateQuery', $event)"
      @keydown="searchKeydown"
      ><template #prefix><Search :size="16" aria-hidden="true" /></template
    ></ElInput>
    <p class="institution-directory__status" role="status">
      <template v-if="loading && !nodes.length">正在读取机构目录…</template>
      <template v-else-if="loading">正在刷新，仍显示上次读取的机构目录。</template>
      <template v-else-if="!directoryComplete">
        已读取 {{ count
        }}<template v-if="total !== undefined"> / {{ total }}</template> 个机构，目录尚未加载完整。
      </template>
      <template v-else-if="query.trim()">匹配 {{ matches.length }} 个机构</template>
      <template v-else>当前可见 {{ nodes.length }} 个机构</template>
    </p>
    <FeedbackNotice v-if="!error && directory.error" :title="directory.error" type="error" />
    <ElTreeV2
      v-if="filtered.length"
      ref="tree"
      class="institution-directory__tree"
      :data="filtered"
      :height="treeHeight"
      :item-size="40"
      :current-node-key="selectedId"
      :expand-on-click-node="false"
      highlight-current
      aria-label="机构目录"
      @node-click="(node) => chooseId(node.id)"
      @node-expand="(node) => expand(node.id, true)"
      @node-collapse="(node) => expand(node.id, false)"
    >
      <template #default="{ data }">
        <button
          type="button"
          class="institution-directory__node"
          :class="{ 'is-selected': selectedId === data.id }"
          :title="[data.label, data.id, data.description].filter(Boolean).join(' · ')"
          :data-institution-id="data.id"
          :aria-current="selectedId === data.id ? 'true' : undefined"
          :tabindex="currentFocusId === data.id ? 0 : -1"
          @focus="focusedId = data.id"
          @click.stop="choose(data)"
          @keydown="keydown($event, data)"
        >
          <span class="institution-directory__name">{{ data.label }}</span>
          <span v-if="data.status" class="institution-directory__node-status">{{
            statusLabel(data.status)
          }}</span>
        </button>
      </template>
    </ElTreeV2>
    <p v-else-if="!loading && !directoryError" class="institution-directory__empty">
      {{ query.trim() ? '没有匹配的机构，请更换关键词。' : '当前没有可展示的机构。' }}
    </p>
    <p
      v-if="selectedId && !nodes.some((node) => node.id === selectedId)"
      class="institution-directory__status"
    >
      当前目标尚未出现在目录中。目录可见范围与业务操作权限分别校验。
    </p>
  </div>
</template>

<style scoped>
.institution-directory {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.institution-directory__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.institution-directory__header h2 {
  font-size: 16px;
  margin: 0;
}
.institution-directory__header .el-button {
  padding-inline: 4px;
  flex-shrink: 0;
}
.institution-directory__status {
  color: var(--admin-muted);
  font-size: 13px;
  line-height: 1.6;
  margin: 0;
  overflow-wrap: anywhere;
}
.institution-directory__tree {
  min-width: 0;
  --el-tree-node-hover-bg-color: var(--admin-hover);
  background: transparent;
}
.institution-directory__tree :deep(.el-tree-node__content) {
  padding-right: 6px;
}
.institution-directory__node {
  min-width: 0;
  width: 100%;
  display: flex;
  gap: 8px;
  align-items: center;
  border: 0;
  border-radius: 5px;
  padding: 6px;
  color: var(--admin-text);
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.institution-directory__node.is-selected {
  color: var(--admin-accent);
  font-weight: 600;
}
.institution-directory__node:focus-visible {
  outline: 2px solid var(--admin-accent);
  outline-offset: -2px;
}
.institution-directory__name {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.institution-directory__node-status {
  flex-shrink: 0;
  color: var(--admin-muted);
  font-weight: 400;
  font-size: 12px;
}
.institution-directory__empty {
  margin: 8px 0;
  color: var(--admin-muted);
  line-height: 1.6;
}
</style>
