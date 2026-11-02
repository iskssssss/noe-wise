<script setup lang="ts">
defineOptions({ name: 'ChatMarkdown' })
import { computed } from 'vue'
import RichChart from './RichChart.vue'
import MermaidBlock from './MermaidBlock.vue'
import { splitMarkdown } from './richContent'
import { useStreamingText } from './streamingText'

const props = defineProps<{ text?: string; streaming?: boolean }>()
const displayedText = useStreamingText(props)
// 块序号只对应顶层可见内容；相同块的 HTML 不变时 Vue 保留原节点，不重复改写历史正文。
const blocks = computed(() => splitMarkdown(displayedText.value, props.streaming))
</script>

<template>
  <div class="markdown" :aria-busy="streaming || undefined">
    <template v-for="(block, index) in blocks" :key="`${index}:${block.kind}`">
      <MermaidBlock v-if="block.kind === 'mermaid'" :source="block.source" />
      <RichChart v-else-if="block.kind === 'chart'" :source="block.source" />
      <pre v-else-if="block.kind === 'text'" class="markdown-pending">{{ block.text }}</pre>
      <div v-else class="markdown-section" v-html="block.html" />
    </template>
  </div>
</template>

<style scoped>
.markdown-section {
  display: contents;
}
.markdown-pending {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-family: var(--font-mono);
}
.markdown-section:first-child > :deep(:first-child) {
  margin-top: 0;
}
.markdown-section:last-child > :deep(:last-child) {
  margin-bottom: 0;
}
</style>
