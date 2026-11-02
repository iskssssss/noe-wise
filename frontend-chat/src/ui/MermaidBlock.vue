<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { renderMermaid } from './mermaidRenderer'

const props = defineProps<{ source: string }>()
const svg = ref('')
const failed = ref(false)
let revision = 0

watch(
  () => props.source,
  async (source) => {
    const current = ++revision
    svg.value = ''
    failed.value = false
    try {
      const rendered = await renderMermaid(source)
      if (current === revision) svg.value = rendered
    } catch {
      if (current === revision) failed.value = true
    }
  },
  { immediate: true }
)
onBeforeUnmount(() => revision++)
</script>

<template>
  <figure class="rich-diagram">
    <figcaption>流程图</figcaption>
    <div v-if="svg" class="diagram-canvas" role="img" aria-label="流程图" v-html="svg" />
    <p v-else-if="!failed" class="diagram-placeholder" role="status">正在绘制流程图…</p>
    <p v-else class="diagram-hint">流程图暂时无法显示，已保留原文。</p>
    <details :open="failed">
      <summary>查看流程图源码</summary>
      <pre><code>{{ source }}</code></pre>
    </details>
  </figure>
</template>

<style scoped>
.rich-diagram {
  margin: 24px 0;
  min-width: 0;
}
figcaption {
  font-weight: 650;
  margin-bottom: 14px;
}
.diagram-canvas {
  max-width: 100%;
  overflow-x: auto;
}
.diagram-canvas :deep(svg) {
  display: block;
  width: auto;
  max-width: 100%;
  height: auto;
  margin: auto;
  overflow: visible;
  font: inherit;
}
.diagram-canvas :deep(.node rect),
.diagram-canvas :deep(.node circle),
.diagram-canvas :deep(.node ellipse),
.diagram-canvas :deep(.node polygon),
.diagram-canvas :deep(.node path),
.diagram-canvas :deep(.cluster rect) {
  fill: var(--soft);
  stroke: var(--border);
  stroke-width: 1.5px;
}
.diagram-canvas :deep(text),
.diagram-canvas :deep(.label) {
  fill: var(--text);
  color: var(--text);
  font-family: inherit;
  font-size: 15px;
}
.diagram-canvas :deep(text) {
  text-anchor: middle;
}
.diagram-canvas :deep(.flowchart-link) {
  stroke: var(--muted);
  stroke-width: 1.5px;
  fill: none;
}
.diagram-canvas :deep(marker path) {
  fill: var(--muted);
  stroke: var(--muted);
}
.diagram-canvas :deep(.edgeLabel rect),
.diagram-canvas :deep(.labelBkg) {
  fill: var(--surface);
  opacity: 1;
}
.diagram-placeholder {
  min-height: 100px;
  display: grid;
  place-items: center;
  color: var(--muted);
}
.diagram-hint,
summary {
  color: var(--muted);
  font-size: 12px;
}
details {
  margin-top: 12px;
}
summary {
  cursor: pointer;
  width: fit-content;
}
summary:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
}
</style>
