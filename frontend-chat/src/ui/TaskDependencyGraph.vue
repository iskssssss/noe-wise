<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import type { WorkTask } from '../protocol'
import { taskStatus } from '../workPlans'

const props = defineProps<{
  tasks: WorkTask[]
  disabled?: boolean
  position?: { left: number; top: number }
}>()
const emit = defineEmits<{
  select: [task: WorkTask]
  viewport: [position: { left: number; top: number }]
}>()
const container = ref<HTMLElement>()
onMounted(() => {
  if (container.value && props.position) {
    container.value.scrollLeft = props.position.left
    container.value.scrollTop = props.position.top
  }
})
onBeforeUnmount(() => {
  if (container.value)
    emit('viewport', { left: container.value.scrollLeft, top: container.value.scrollTop })
})
const arrow = `task-edge-${useId()}`
/** 坐标仅由真实任务顺序和依赖层级决定；状态变化不排序，也不执行视口适配。 */
const graph = computed(() => {
  const tasks = new Map(props.tasks.map((task) => [task.task_id, task]))
  const incoming = new Map<string, number>(),
    levels = new Map<string, number>()
  const edges: { source: string; target: string }[] = []
  for (const task of props.tasks) {
    const dependencies = [...new Set(task.depends_on ?? [])].filter((id) => tasks.has(id))
    incoming.set(task.task_id, dependencies.length)
    levels.set(task.task_id, 0)
    for (const source of dependencies) edges.push({ source, target: task.task_id })
  }
  const ready = props.tasks
    .filter((task) => !incoming.get(task.task_id))
    .map((task) => task.task_id)
  let consumed = 0
  for (let cursor = 0; cursor < ready.length; cursor++) {
    const id = ready[cursor]!
    consumed++
    for (const edge of edges.filter((edge) => edge.source === id)) {
      levels.set(edge.target, Math.max(levels.get(edge.target)!, levels.get(id)! + 1))
      incoming.set(edge.target, incoming.get(edge.target)! - 1)
      if (incoming.get(edge.target) === 0) ready.push(edge.target)
    }
  }
  const rows = new Map<number, number>()
  const nodes = props.tasks.map((task) => {
    const level = levels.get(task.task_id) ?? 0,
      row = rows.get(level) ?? 0
    rows.set(level, row + 1)
    return { task, x: 16 + level * 240, y: 16 + row * 92 }
  })
  const locations = new Map(nodes.map((node) => [node.task.task_id, node]))
  return {
    invalid: consumed !== tasks.size || tasks.size !== props.tasks.length,
    nodes,
    edges: edges.map((edge) => {
      const from = locations.get(edge.source)!,
        to = locations.get(edge.target)!
      const x = from.x + 192,
        y = from.y + 32,
        endX = to.x,
        endY = to.y + 32
      return {
        ...edge,
        key: JSON.stringify([edge.source, edge.target]),
        path: `M${x},${y} C${x + 24},${y} ${endX - 24},${endY} ${endX},${endY}`
      }
    }),
    width: Math.max(224, ...nodes.map((node) => node.x + 208)),
    height: Math.max(96, ...nodes.map((node) => node.y + 80))
  }
})
function select(task: WorkTask) {
  if (!props.disabled) emit('select', task)
}
</script>

<template>
  <figure class="task-dependency-graph">
    <figcaption>任务依赖图 · 点击步骤查看详情</figcaption>
    <p v-if="graph.invalid" role="status">
      步骤标识或依赖存在冲突，图形暂不可用，请核对下方依赖文字。
    </p>
    <div
      v-else
      ref="container"
      class="task-graph-scroll"
      tabindex="0"
      aria-label="任务依赖图，可滚动查看"
    >
      <svg
        :width="graph.width"
        :height="graph.height"
        :viewBox="`0 0 ${graph.width} ${graph.height}`"
        role="group"
        aria-label="真实步骤依赖"
      >
        <defs>
          <marker :id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
            <path d="M0,0 L8,4 L0,8 Z" />
          </marker>
        </defs>
        <path
          v-for="edge in graph.edges"
          :key="edge.key"
          class="task-graph-edge"
          :data-edge-source="edge.source"
          :data-edge-target="edge.target"
          :d="edge.path"
          :marker-end="`url(#${arrow})`"
        />
        <g
          v-for="node in graph.nodes"
          :key="node.task.task_id"
          role="button"
          :tabindex="disabled ? -1 : 0"
          :aria-disabled="disabled || undefined"
          :aria-label="`${node.task.title}，${taskStatus(node.task.status)}`"
          :data-task-id="node.task.task_id"
          :data-task-status="node.task.status"
          :transform="`translate(${node.x},${node.y})`"
          class="task-graph-node"
          @click="select(node.task)"
          @keydown.enter.prevent="select(node.task)"
          @keydown.space.prevent="select(node.task)"
        >
          <title>{{ node.task.title }} · {{ taskStatus(node.task.status) }}</title>
          <rect width="192" height="64" rx="8" />
          <text x="12" y="24">
            {{ [...node.task.title].slice(0, 14).join('')
            }}{{ [...node.task.title].length > 14 ? '…' : '' }}
          </text>
          <text x="12" y="46" class="task-graph-status">{{ taskStatus(node.task.status) }}</text>
        </g>
      </svg>
    </div>
  </figure>
</template>

<style scoped>
.task-dependency-graph {
  margin: 16px 0;
  min-width: 0;
}
figcaption {
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 8px;
}
.task-graph-scroll {
  overflow: auto;
  max-height: 480px;
  border: 1px solid var(--border);
  border-radius: 8px;
}
svg {
  display: block;
  max-width: none;
  font: inherit;
}
marker path {
  fill: var(--muted);
}
.task-graph-edge {
  fill: none;
  stroke: var(--muted);
  stroke-width: 1.5;
}
.task-graph-node {
  cursor: pointer;
  outline: none;
}
.task-graph-node rect {
  fill: var(--soft);
  stroke: var(--border);
}
.task-graph-node text {
  fill: var(--text);
  font-size: 12px;
  pointer-events: none;
}
.task-graph-node .task-graph-status {
  fill: var(--muted);
}
.task-graph-node:focus-visible rect,
.task-graph-node:hover rect {
  stroke: var(--accent);
  stroke-width: 2;
}
.task-graph-node[data-task-status='failed'] rect,
.task-graph-node[data-task-status='outcome_unknown'] rect {
  stroke: var(--error);
}
.task-graph-node[aria-disabled='true'] {
  cursor: default;
}
</style>
