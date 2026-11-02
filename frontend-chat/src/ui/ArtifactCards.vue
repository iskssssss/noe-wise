<script setup lang="ts">
import { FileText as IconFileText, ArrowUpRight as IconArrowUpRight } from 'lucide-vue-next'
import { computed } from 'vue'
import type { Deliverable } from '../protocol'
import { artifactsForRuns } from './artifacts'

const props = defineProps<{ artifacts: Deliverable[]; runIds: string[] }>()
const emit = defineEmits<{ open: [id: string] }>()
const items = computed(() => artifactsForRuns(props.artifacts, props.runIds))
</script>

<template>
  <div v-if="items.length" class="artifact-cards" aria-label="本轮成果">
    <button
      v-for="artifact in items"
      :key="artifact.id"
      type="button"
      class="artifact-card"
      @click="emit('open', artifact.id)"
    >
      <span class="artifact-format" aria-hidden="true">
        <IconFileText />
      </span>
      <span class="artifact-summary">
        <strong>{{ artifact.name }}</strong>
        <small
          >{{ artifact.files[0]?.format.toUpperCase() || '文件' }} · v{{ artifact.revision }} ·
          {{ artifact.current ? '当前版本' : '历史版本' }} · {{ artifact.files.length }} 个文件 ·
          {{ artifact.status === 'ready' ? '已发布 · 可下载' : '暂不可用' }}</small
        >
      </span>
      <span class="artifact-open"
        ><span>查看成果</span><IconArrowUpRight aria-hidden="true"
      /></span>
    </button>
  </div>
</template>

<style scoped>
.artifact-cards {
  display: grid;
  gap: 10px;
  margin: 24px 0 8px;
  max-width: 540px;
}
.artifact-card {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  text-align: left;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--text);
  padding: 16px 18px;
  cursor: pointer;
  transition:
    background var(--duration-fast) var(--ease),
    border-color var(--duration-fast) var(--ease);
}
.artifact-card:hover {
  border-color: var(--accent);
}
.artifact-card:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
}
svg {
  width: 18px;
  height: 18px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.artifact-format {
  display: grid;
  place-items: center;
  width: 36px;
  height: 40px;
  flex-shrink: 0;
  background: var(--accent-soft);
  border-radius: var(--radius);
  color: var(--accent);
}
.artifact-summary {
  display: grid;
  gap: 3px;
  min-width: 0;
  flex: 1;
}
.artifact-summary strong {
  font-size: 14px;
  overflow-wrap: anywhere;
  font-weight: 500;
}
.artifact-summary small,
.artifact-open {
  color: var(--muted);
  font-size: 11px;
}
.artifact-open {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  color: var(--accent);
}
.artifact-open svg {
  width: 15px;
  height: 15px;
}
@media (max-width: 480px) {
  .artifact-card {
    gap: 10px;
    padding: 12px;
  }
  .artifact-open span {
    display: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .artifact-card {
    transition: none;
  }
}
</style>
