<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { ElButton, ElPopover, ElTag } from 'element-plus'
import { WORKFLOW_NODE_CATALOG, type BuilderEdge, type BuilderNode } from '../../utils/workflow'

const nodes = defineModel<BuilderNode[]>('nodes', { required: true })
const edges = defineModel<BuilderEdge[]>('edges', { required: true })
const selectedId = defineModel<string>('selectedId', { default: '' })
const selectedIds = defineModel<string[]>('selectedIds', { default: () => [] })
const viewport = defineModel<{ zoom: number; panX: number; panY: number }>('viewport', {
  default: () => ({ zoom: 1, panX: 24, panY: 24 })
})
const props = defineProps<{ readonly?: boolean; busy?: boolean; canPaste?: boolean }>()
const emit = defineEmits<{
  'edit-node': [id: string]
  'edit-edge': [id: string]
  'remove-node': [id: string]
  'copy-nodes': [ids: string[]]
  'paste-nodes': []
  'remove-nodes': [ids: string[]]
  'add-next': [id: string]
  'insert-node': [id: string]
}>()
const surface = ref<HTMLElement>()
const notice = ref('')
const connecting = ref('')
const selectedEdge = ref('')
const selectionMode = ref(false)
const batchMenuOpen = ref(false)
const locked = computed(() => props.readonly || props.busy)
const selectedNodes = computed(() =>
  nodes.value.filter((node) => selectedIds.value.includes(node.id))
)
const activeNode = computed(() => nodes.value.find((node) => node.id === selectedId.value))
const activeEdge = computed(() => connections.value.find((edge) => edge.id === selectedEdge.value))
const pointer = ref({ x: 0, y: 0 })
const preview = ref<Map<string, { x: number; y: number }>>()
const nodeWidth = 220
const nodeHeight = 96
const arrowId = `workflow-arrow-${useId()}`
// 箭头停在输入端口外侧，避免被端口与节点背景遮住。
const inputArrowGap = 18
let gesture:
  | {
      kind: 'node'
      id: string
      pointerId: number
      clientX: number
      clientY: number
      origins: { id: string; x: number; y: number }[]
    }
  | { kind: 'pan'; pointerId: number; clientX: number; clientY: number; x: number; y: number }
  | { kind: 'connect'; pointerId: number }
  | undefined
let captured: HTMLElement | undefined
let suppressOutputClick = false
let suppressNodeClick = ''

const extent = computed(() => ({
  width: Math.max(2000, ...nodes.value.map((node) => node.x + 600)),
  height: Math.max(1400, ...nodes.value.map((node) => node.y + 600))
}))
function position(node: BuilderNode) {
  return preview.value?.get(node.id) ?? node
}
function curve(source: { x: number; y: number }, target: { x: number; y: number }) {
  const distance = Math.max(50, Math.abs(target.y - source.y) / 2)
  return `M ${source.x} ${source.y} C ${source.x} ${source.y + distance}, ${target.x} ${target.y - distance}, ${target.x} ${target.y}`
}
const connections = computed(() =>
  edges.value.flatMap((edge) => {
    const source = nodes.value.find((node) => node.id === edge.source)
    const target = nodes.value.find((node) => node.id === edge.target)
    if (!source || !target) return []
    const a = position(source),
      b = position(target)
    return [
      {
        ...edge,
        path: curve(
          { x: a.x + nodeWidth / 2, y: a.y + nodeHeight },
          { x: b.x + nodeWidth / 2, y: b.y - inputArrowGap }
        ),
        label: `${source.label || source.id} → ${target.label || target.id}${edge.condition ? '（有条件）' : ''}`
      }
    ]
  })
)
const pendingPath = computed(() => {
  const source = nodes.value.find((node) => node.id === connecting.value)
  if (!source) return ''
  const point = position(source)
  return curve({ x: point.x + nodeWidth / 2, y: point.y + nodeHeight }, pointer.value)
})
function toWorld(clientX: number, clientY: number) {
  const rect = surface.value!.getBoundingClientRect()
  return {
    x: (clientX - rect.left - viewport.value.panX) / viewport.value.zoom,
    y: (clientY - rect.top - viewport.value.panY) / viewport.value.zoom
  }
}
function capture(event: PointerEvent) {
  captured = event.currentTarget as HTMLElement
  captured.setPointerCapture?.(event.pointerId)
}
function release() {
  if (gesture && captured?.hasPointerCapture?.(gesture.pointerId))
    captured.releasePointerCapture(gesture.pointerId)
  captured = undefined
  gesture = undefined
  preview.value = undefined
}
onBeforeUnmount(release)
watch(locked, (value) => {
  if (value) cancel()
})
function setSelection(ids: string[], primary = selectedId.value) {
  const available = new Set(nodes.value.map((node) => node.id))
  const next = [...new Set(ids)].filter((id) => available.has(id))
  if (next.join('\0') !== selectedIds.value.join('\0')) selectedIds.value = next
  selectedId.value = next.includes(primary) ? primary : (next.at(-1) ?? '')
  if (next.length) selectedEdge.value = ''
  return next
}
watch(
  selectedId,
  (id, previous) => {
    if (id && !selectedIds.value.includes(id)) setSelection([id], id)
    else if (!id && previous && selectedIds.value.length) setSelection([])
  },
  { immediate: true }
)
watch(
  [selectedIds, () => nodes.value.map((node) => node.id)],
  () => {
    setSelection(selectedIds.value)
  },
  { immediate: true }
)
watch(nodes, () => {
  if (gesture?.kind === 'node') release()
})
function editEdge(id: string) {
  setSelection([])
  selectedEdge.value = id
  emit('edit-edge', id)
}
function addNext(id: string) {
  if (locked.value || !nodes.value.some((node) => node.id === id && node.type !== 'end')) return
  cancelConnection()
  emit('add-next', id)
}
function insertNode(id: string) {
  if (locked.value || !edges.value.some((edge) => edge.id === id)) return
  cancelConnection()
  emit('insert-node', id)
}
function cancelConnection() {
  release()
  connecting.value = ''
}
function selectNode(event: MouseEvent, id: string) {
  if (suppressNodeClick === id) {
    suppressNodeClick = ''
    return
  }
  if (selectionMode.value || event.shiftKey || event.ctrlKey || event.metaKey) {
    setSelection(
      selectedIds.value.includes(id)
        ? selectedIds.value.filter((item) => item !== id)
        : [...selectedIds.value, id],
      id
    )
    return
  }
  const ids = selectedIds.value.includes(id) ? selectedIds.value : setSelection([id], id)
  if (ids.includes(id)) selectedId.value = id
  if (ids.length === 1 && !props.busy) emit('edit-node', id)
}
function startNode(event: PointerEvent, node: BuilderNode) {
  if (event.button !== 0) return
  suppressNodeClick = ''
  if (
    event.shiftKey ||
    event.ctrlKey ||
    event.metaKey ||
    (selectionMode.value && !selectedIds.value.includes(node.id))
  )
    return
  const group = selectedIds.value.includes(node.id) ? selectedNodes.value : [node]
  if (!selectedIds.value.includes(node.id)) setSelection([node.id], node.id)
  else selectedId.value = node.id
  if (locked.value || connecting.value) return
  gesture = {
    kind: 'node',
    id: node.id,
    pointerId: event.pointerId,
    clientX: event.clientX,
    clientY: event.clientY,
    origins: group.map(({ id, x, y }) => ({ id, x, y }))
  }
  capture(event)
}
function startPan(event: PointerEvent) {
  if (event.button !== 0 && event.button !== 1) return
  const target = event.target as Element
  if (target.closest('.workflow-canvas-node, .workflow-canvas-tools, .workflow-canvas-edge')) return
  event.preventDefault()
  surface.value?.focus({ preventScroll: true })
  gesture = {
    kind: 'pan',
    pointerId: event.pointerId,
    clientX: event.clientX,
    clientY: event.clientY,
    x: viewport.value.panX,
    y: viewport.value.panY
  }
  capture(event)
}
function startConnection(event: PointerEvent, node: BuilderNode) {
  if (locked.value || event.button !== 0) return
  connecting.value = node.id
  suppressOutputClick = false
  pointer.value = toWorld(event.clientX, event.clientY)
  notice.value = '拖到目标节点的输入端口，或点击目标输入端口；Esc 取消。'
  gesture = { kind: 'connect', pointerId: event.pointerId }
  capture(event)
}
function chooseOutput(node: BuilderNode) {
  if (suppressOutputClick) {
    suppressOutputClick = false
    return
  }
  if (locked.value) return
  connecting.value = node.id
  pointer.value = { x: node.x + nodeWidth / 2, y: node.y + nodeHeight + 60 }
  notice.value = `已选择“${node.label}”输出，选择目标输入端口。`
}
function connect(targetId: string) {
  if (locked.value || !connecting.value) return
  const source = nodes.value.find((node) => node.id === connecting.value)
  const target = nodes.value.find((node) => node.id === targetId)
  if (
    !source ||
    !target ||
    source.id === target.id ||
    source.type === 'end' ||
    target.type === 'start'
  ) {
    notice.value = '请选择其他节点的有效输入端口。'
    return
  }
  if (edges.value.some((edge) => edge.source === source.id && edge.target === target.id)) {
    notice.value = '这两个节点已有连接，可点击连线编辑条件。'
    connecting.value = ''
    return
  }
  const id = `edge-${crypto.randomUUID()}`
  edges.value = [
    ...edges.value,
    { id, source: source.id, target: target.id, condition: '', priority: 0 }
  ]
  connecting.value = ''
  selectedEdge.value = id
  notice.value = '连接已添加，可编辑条件和优先级。'
  emit('edit-edge', id)
}
function move(event: PointerEvent) {
  if (connecting.value) pointer.value = toWorld(event.clientX, event.clientY)
  if (!gesture || gesture.pointerId !== event.pointerId) return
  if (gesture.kind === 'pan') {
    viewport.value = {
      ...viewport.value,
      panX: gesture.x + event.clientX - gesture.clientX,
      panY: gesture.y + event.clientY - gesture.clientY
    }
  } else if (gesture.kind === 'node' && !locked.value) {
    const dx = event.clientX - gesture.clientX,
      dy = event.clientY - gesture.clientY
    if (!preview.value && Math.hypot(dx, dy) < 4) return
    preview.value = translated(
      gesture.origins,
      Math.round(dx / viewport.value.zoom / 8) * 8,
      Math.round(dy / viewport.value.zoom / 8) * 8
    )
  }
}
function finish(event: PointerEvent) {
  if (!gesture || gesture.pointerId !== event.pointerId) return
  if (gesture.kind === 'node' && preview.value && !locked.value) {
    suppressNodeClick = gesture.id
    applyPositions(preview.value)
  } else if (gesture.kind === 'connect') {
    const input = document
      .elementFromPoint?.(event.clientX, event.clientY)
      ?.closest<HTMLElement>('[data-input-node]')
    if (input?.dataset.inputNode) {
      connect(input.dataset.inputNode)
      suppressOutputClick = true
    }
  }
  release()
}
function zoomTo(value: number, clientX?: number, clientY?: number) {
  if (!surface.value) return
  const rect = surface.value.getBoundingClientRect()
  const x = clientX === undefined ? rect.width / 2 : clientX - rect.left
  const y = clientY === undefined ? rect.height / 2 : clientY - rect.top
  const zoom = Math.max(0.4, Math.min(1.75, value))
  viewport.value = {
    zoom,
    panX: x - ((x - viewport.value.panX) * zoom) / viewport.value.zoom,
    panY: y - ((y - viewport.value.panY) * zoom) / viewport.value.zoom
  }
}
function wheel(event: WheelEvent) {
  if (event.ctrlKey || event.metaKey)
    zoomTo(viewport.value.zoom * (event.deltaY < 0 ? 1.1 : 1 / 1.1), event.clientX, event.clientY)
  else
    viewport.value = {
      ...viewport.value,
      panX: viewport.value.panX - event.deltaX,
      panY: viewport.value.panY - event.deltaY
    }
}
function fitView() {
  if (!surface.value || !nodes.value.length) return
  const rect = surface.value.getBoundingClientRect()
  const minX = Math.min(...nodes.value.map((node) => node.x)),
    minY = Math.min(...nodes.value.map((node) => node.y))
  const width = Math.max(...nodes.value.map((node) => node.x + nodeWidth)) - minX
  const height = Math.max(...nodes.value.map((node) => node.y + nodeHeight)) - minY
  const zoom = Math.min(
    1,
    Math.max(0.4, Math.min((rect.width - 80) / width, (rect.height - 120) / height))
  )
  viewport.value = {
    zoom,
    panX: (rect.width - width * zoom) / 2 - minX * zoom,
    panY: 70 - minY * zoom
  }
}
async function focusNode(id: string, preserveSelection = false) {
  const node = nodes.value.find((item) => item.id === id)
  if (!node || !surface.value) return
  if (!preserveSelection || !selectedIds.value.includes(id)) setSelection([id], id)
  const rect = surface.value.getBoundingClientRect()
  viewport.value = {
    ...viewport.value,
    panX: rect.width / 2 - (node.x + nodeWidth / 2) * viewport.value.zoom,
    panY: rect.height / 2 - (node.y + nodeHeight / 2) * viewport.value.zoom
  }
  await nextTick()
  const button = Array.from(surface.value.querySelectorAll<HTMLElement>('[data-node-id]')).find(
    (item) => item.dataset.nodeId === id
  )
  button?.focus()
}
function focusCanvas() {
  surface.value?.focus({ preventScroll: true })
}
function translated(origins: { id: string; x: number; y: number }[], dx: number, dy: number) {
  const x = Math.max(dx, -Math.min(...origins.map((node) => node.x)))
  const y = Math.max(dy, -Math.min(...origins.map((node) => node.y)))
  return new Map(origins.map((node) => [node.id, { x: node.x + x, y: node.y + y }]))
}
function applyPositions(positions: Map<string, { x: number; y: number }>) {
  if (
    locked.value ||
    !nodes.value.some((node) => {
      const next = positions.get(node.id)
      return next && (next.x !== node.x || next.y !== node.y)
    })
  )
    return
  nodes.value = nodes.value.map((node) => {
    const next = positions.get(node.id)
    return next ? { ...node, ...next } : node
  })
}
function alignSelection(mode: 'left' | 'top' | 'horizontal' | 'vertical') {
  const group = selectedNodes.value
  const distribute = mode === 'horizontal' || mode === 'vertical'
  if (locked.value || group.length < (distribute ? 3 : 2)) return
  const axis = mode === 'left' || mode === 'horizontal' ? 'x' : 'y'
  const ordered = [...group].sort((a, b) => a[axis] - b[axis])
  const first = ordered[0]![axis],
    last = ordered.at(-1)![axis]
  const step = distribute ? (last - first) / (ordered.length - 1) : 0
  applyPositions(
    new Map(
      ordered.map((node, index) => [
        node.id,
        {
          x: node.x,
          y: node.y,
          [axis]: Math.max(0, first + step * index)
        }
      ])
    )
  )
}
function copySelection() {
  if (locked.value || !selectedIds.value.length) return
  emit('copy-nodes', [...selectedIds.value])
}
function pasteSelection() {
  if (!locked.value && props.canPaste) emit('paste-nodes')
}
function removeSelection() {
  removeIds(selectedIds.value)
}
function removeIds(ids: string[]) {
  if (locked.value || !ids.length) return
  if (ids.length === 1) emit('remove-node', ids[0]!)
  else emit('remove-nodes', [...ids])
}
function canvasKey(event: KeyboardEvent) {
  const target = event.target as HTMLElement
  if (!surface.value?.contains(target)) return
  if (
    event.isComposing ||
    event.keyCode === 229 ||
    target.closest(
      'input, textarea, select, [contenteditable]:not([contenteditable="false"]), [role="textbox"]'
    )
  )
    return
  const modifier = event.ctrlKey || event.metaKey
  if (modifier && !event.altKey) {
    const key = event.key.toLowerCase()
    if (key === 'a') {
      event.preventDefault()
      setSelection(nodes.value.map((node) => node.id))
    } else if (!locked.value && (key === 'c' || key === 'v')) {
      event.preventDefault()
      if (key === 'c') copySelection()
      else pasteSelection()
    }
    return
  }
  if (event.key === 'Escape') {
    event.stopPropagation()
    cancel()
    return
  }
  if (locked.value || modifier || event.altKey) return
  const focusedNode = target.closest<HTMLElement>('[data-node-id]')?.dataset.nodeId
  const ids =
    focusedNode && !selectedIds.value.includes(focusedNode)
      ? setSelection([focusedNode], focusedNode)
      : selectedIds.value
  if (!ids.length) return
  const delta = event.shiftKey ? 32 : 8
  const directions: Record<string, [number, number]> = {
    ArrowLeft: [-delta, 0],
    ArrowRight: [delta, 0],
    ArrowUp: [0, -delta],
    ArrowDown: [0, delta]
  }
  const change = directions[event.key]
  if (change) {
    event.preventDefault()
    applyPositions(
      translated(
        nodes.value.filter((node) => ids.includes(node.id)),
        change[0],
        change[1]
      )
    )
  } else if (event.key === 'Delete' || event.key === 'Backspace') {
    event.preventDefault()
    removeIds(ids)
  }
}
function cancel() {
  release()
  connecting.value = ''
  notice.value = '已取消画布操作。'
}
defineExpose({ focusNode, focusCanvas, fitView })
</script>

<template>
  <section class="workflow-canvas-shell" aria-label="工作流画布" @keydown="canvasKey">
    <div class="workflow-canvas-tools">
      <span class="workflow-canvas-hint">拖动空白处移动画布</span>
      <ElButton
        aria-label="缩小画布"
        :disabled="viewport.zoom <= 0.4"
        @click="zoomTo(viewport.zoom - 0.1)"
        >−</ElButton
      >
      <ElButton aria-label="恢复画布原始比例" @click="zoomTo(1)"
        >{{ Math.round(viewport.zoom * 100) }}%</ElButton
      >
      <ElButton
        aria-label="放大画布"
        :disabled="viewport.zoom >= 1.75"
        @click="zoomTo(viewport.zoom + 0.1)"
        >+</ElButton
      >
      <ElButton :disabled="!nodes.length" @click="fitView">适应画布</ElButton>
      <ElButton :aria-pressed="selectionMode" @click="selectionMode = !selectionMode"
        >多选模式</ElButton
      >
      <ElPopover
        v-model:visible="batchMenuOpen"
        trigger="click"
        :width="280"
        placement="bottom-end"
      >
        <template #reference><ElButton :aria-expanded="batchMenuOpen">批量编辑</ElButton></template>
        <div
          class="workflow-canvas-batch-menu"
          role="group"
          aria-label="节点选择与编辑"
          @click="batchMenuOpen = false"
        >
          <ElButton :disabled="!nodes.length" @click="setSelection(nodes.map((node) => node.id))"
            >全选节点</ElButton
          >
          <ElButton :disabled="!selectedIds.length" @click="setSelection([])">清除选择</ElButton>
          <ElButton :disabled="locked || !selectedIds.length" @click="copySelection"
            >复制节点</ElButton
          >
          <ElButton :disabled="locked || !canPaste" @click="pasteSelection">粘贴节点</ElButton>
          <ElButton
            :disabled="locked || !selectedIds.length"
            type="danger"
            plain
            @click="removeSelection"
            >删除节点</ElButton
          >
          <template v-if="selectedIds.length > 1">
            <ElButton :disabled="locked || selectedIds.length < 2" @click="alignSelection('left')"
              >左对齐</ElButton
            >
            <ElButton :disabled="locked || selectedIds.length < 2" @click="alignSelection('top')"
              >顶对齐</ElButton
            >
            <ElButton
              :disabled="locked || selectedIds.length < 3"
              @click="alignSelection('horizontal')"
              >水平等距</ElButton
            >
            <ElButton
              :disabled="locked || selectedIds.length < 3"
              @click="alignSelection('vertical')"
              >垂直等距</ElButton
            >
          </template>
        </div>
      </ElPopover>
      <span class="workflow-canvas-count" role="status">已选 {{ selectedIds.length }} 个节点</span>
    </div>
    <div
      v-if="!locked && (activeEdge || (activeNode && selectedIds.length === 1))"
      class="workflow-canvas-context"
    >
      <template v-if="activeEdge">
        <span>{{ activeEdge.label }}</span>
        <ElButton @click="insertNode(activeEdge.id)">在连线中插入节点</ElButton>
      </template>
      <template v-else-if="activeNode">
        <span>已选择：{{ activeNode.label || activeNode.id }}</span>
        <ElButton v-if="activeNode.type !== 'end'" @click="addNext(activeNode.id)">
          添加下一步
        </ElButton>
      </template>
    </div>
    <div
      ref="surface"
      class="workflow-canvas-surface"
      tabindex="0"
      aria-label="节点画布，方向键移动所选节点，Shift 加点击多选，Control 或 Command 加 C 复制、加 V 粘贴，加滚轮缩放"
      @pointerdown="startPan"
      @pointermove="move"
      @pointerup="finish"
      @pointercancel="cancel"
      @wheel.prevent="wheel"
    >
      <div
        class="workflow-canvas-world"
        :style="{
          width: extent.width + 'px',
          height: extent.height + 'px',
          transform: `translate(${viewport.panX}px, ${viewport.panY}px) scale(${viewport.zoom})`
        }"
      >
        <svg
          class="workflow-canvas-lines"
          :width="extent.width"
          :height="extent.height"
          aria-label="工作流连接"
        >
          <defs>
            <marker
              :id="arrowId"
              viewBox="0 -6 12 12"
              refX="10"
              refY="0"
              markerWidth="12"
              markerHeight="12"
              markerUnits="userSpaceOnUse"
              orient="auto"
            >
              <path d="M 0 -5 L 10 0 L 0 5 Z" fill="context-stroke" />
            </marker>
          </defs>
          <g
            v-for="edge in connections"
            :key="edge.id"
            class="workflow-canvas-edge"
            :class="{ selected: selectedEdge === edge.id }"
          >
            <path
              :d="edge.path"
              class="workflow-canvas-edge-line"
              :marker-end="`url(#${arrowId})`"
            />
            <path
              :d="edge.path"
              class="workflow-canvas-edge-hit"
              tabindex="0"
              role="button"
              :aria-label="`编辑连接：${edge.label}`"
              @click.stop="editEdge(edge.id)"
              @keydown.enter.prevent="editEdge(edge.id)"
              @keydown.space.prevent="editEdge(edge.id)"
            >
              <title>{{ edge.label }}</title>
            </path>
          </g>
          <path
            v-if="pendingPath"
            :d="pendingPath"
            class="workflow-canvas-pending"
            :marker-end="`url(#${arrowId})`"
          />
        </svg>
        <div
          v-for="node in nodes"
          :key="node.id"
          class="workflow-canvas-node"
          :class="{ selected: selectedIds.includes(node.id) }"
          :style="{ left: position(node).x + 'px', top: position(node).y + 'px' }"
        >
          <button
            v-if="node.type !== 'start'"
            type="button"
            class="workflow-canvas-port input"
            :data-input-node="node.id"
            :aria-label="`${node.label}的输入端口`"
            :disabled="locked"
            @pointerdown.stop
            @click.stop="connect(node.id)"
          />
          <button
            type="button"
            class="workflow-canvas-node-body"
            :data-node-id="node.id"
            :aria-pressed="selectedIds.includes(node.id)"
            @pointerdown.stop="startNode($event, node)"
            @click="selectNode($event, node.id)"
          >
            <strong>{{ node.label || node.id }}</strong>
            <span>{{
              WORKFLOW_NODE_CATALOG.find((kind) => kind.type === node.type)?.label || node.type
            }}</span>
            <small>{{ node.id }}</small>
          </button>
          <button
            v-if="node.type !== 'end'"
            type="button"
            class="workflow-canvas-port output"
            :aria-label="`${node.label}的输出端口`"
            :disabled="locked"
            @pointerdown.stop="startConnection($event, node)"
            @click.stop="chooseOutput(node)"
          />
          <button
            v-if="!locked && node.type !== 'end'"
            type="button"
            class="workflow-canvas-add"
            :aria-label="`在${node.label || node.id}后添加节点`"
            @pointerdown.stop
            @click.stop="addNext(node.id)"
          >
            +
          </button>
        </div>
      </div>
    </div>
    <div
      class="workflow-canvas-status"
      :class="{ 'has-notice': notice || locked || connecting || selectionMode }"
      role="status"
    >
      <ElTag v-if="readonly" type="info">只读</ElTag
      ><ElTag v-else-if="busy" type="info">处理中</ElTag
      >{{
        notice ||
        (selectionMode
          ? '多选模式：逐个点击切换选择，拖动已选节点移动整组；空白处仍可平移。'
          : '连线决定执行顺序；在节点参数中选择上游输出。点击节点配置，Shift / Ctrl / Command 加点击多选。')
      }}<ElButton v-if="connecting" link @click="cancel">取消连线</ElButton>
    </div>
  </section>
</template>

<style scoped>
.workflow-canvas-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  min-width: 0;
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  overflow: hidden;
  background: var(--el-bg-color);
}
.workflow-canvas-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 10px;
  border-bottom: 1px solid var(--el-border-color);
}
.workflow-canvas-tools .el-button + .el-button {
  margin-left: 0;
}
.workflow-canvas-batch-menu {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.workflow-canvas-batch-menu .el-button {
  margin-left: 0;
}
.workflow-canvas-count {
  color: var(--el-text-color-regular);
  font-size: 12px;
}
.workflow-canvas-context {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color);
  color: var(--el-text-color-regular);
  font-size: 12px;
}
.workflow-canvas-context span {
  overflow-wrap: anywhere;
  min-width: 0;
}
.workflow-canvas-add {
  position: absolute;
  top: 30px;
  right: -42px;
  width: 32px;
  height: 32px;
  border: 1px solid var(--el-border-color);
  border-radius: 50%;
  background: var(--el-bg-color);
  color: var(--el-color-primary);
  font-size: 20px;
  cursor: pointer;
}
.workflow-canvas-add:hover {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.workflow-canvas-add:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 3px;
}
.workflow-canvas-hint {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-right: auto;
  color: var(--el-text-color-regular);
  font-size: 12px;
}
.workflow-canvas-surface {
  flex: 1;
  min-height: 240px;
  position: relative;
  overflow: hidden;
  touch-action: none;
  background-image: radial-gradient(var(--el-border-color) 1px, transparent 1px);
  background-size: 20px 20px;
  cursor: grab;
}
.workflow-canvas-surface:active {
  cursor: grabbing;
}
.workflow-canvas-world {
  position: absolute;
  transform-origin: 0 0;
}
.workflow-canvas-lines {
  position: absolute;
  inset: 0;
  overflow: visible;
}
.workflow-canvas-edge-line {
  fill: none;
  stroke: var(--el-text-color-secondary);
  stroke-width: 2;
  pointer-events: none;
}
.workflow-canvas-edge-hit {
  fill: none;
  stroke: transparent;
  stroke-width: 18;
  cursor: pointer;
}
.workflow-canvas-edge:hover .workflow-canvas-edge-line,
.workflow-canvas-edge.selected .workflow-canvas-edge-line,
.workflow-canvas-edge:focus-within .workflow-canvas-edge-line {
  stroke: var(--el-color-primary);
  stroke-width: 3;
}
.workflow-canvas-pending {
  fill: none;
  stroke: var(--el-color-primary);
  stroke-width: 2;
  stroke-dasharray: 6 4;
  pointer-events: none;
}
.workflow-canvas-node {
  position: absolute;
  width: 220px;
  height: 96px;
  border-radius: 10px;
  border: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
  box-sizing: border-box;
}
.workflow-canvas-node.selected {
  border: 2px solid var(--el-color-primary);
  box-shadow: 0 0 0 3px var(--el-color-primary-light-9);
}
.workflow-canvas-node-body {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 5px;
  border: 0;
  border-radius: inherit;
  padding: 12px 18px;
  background: transparent;
  color: var(--el-text-color-primary);
  cursor: grab;
  text-align: left;
  touch-action: none;
}
.workflow-canvas-node-body strong,
.workflow-canvas-node-body small {
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.workflow-canvas-node-body span,
.workflow-canvas-node-body small {
  color: var(--el-text-color-regular);
  font-size: 12px;
}
.workflow-canvas-node-body:focus-visible,
.workflow-canvas-port:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 3px;
}
.workflow-canvas-port {
  position: absolute;
  z-index: 1;
  left: calc(50% - 14px);
  width: 28px;
  height: 28px;
  border: 6px solid var(--el-bg-color);
  border-radius: 50%;
  padding: 0;
  background: var(--el-color-primary);
  cursor: crosshair;
  touch-action: none;
}
.workflow-canvas-port.input {
  top: -14px;
}
.workflow-canvas-port.output {
  bottom: -14px;
}
.workflow-canvas-port:disabled {
  background: var(--el-text-color-placeholder);
  cursor: default;
}
.workflow-canvas-status {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 38px;
  padding: 8px 12px;
  border-top: 1px solid var(--el-border-color);
  font-size: 12px;
  color: var(--el-text-color-regular);
}
@media (max-width: 600px) {
  .workflow-canvas-hint,
  .workflow-canvas-status:not(.has-notice) {
    display: none;
  }
}
@media (max-width: 700px) {
  .workflow-canvas-surface {
    height: 400px;
  }
  .workflow-canvas-hint {
    width: 100%;
  }
}
</style>
