<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
const props = defineProps<{ content: string }>()
const html = computed(() =>
  DOMPurify.sanitize(marked.parse(props.content, { async: false }), {
    FORBID_TAGS: ['style', 'iframe', 'form', 'input'],
    FORBID_ATTR: ['style']
  })
)
</script>
<template><div class="markdown-content" v-html="html" /></template>
<style scoped>
.markdown-content {
  line-height: 1.75;
  color: var(--admin-text, #202532);
  min-width: 0;
  overflow-wrap: anywhere;
}
.markdown-content :deep(:first-child) {
  margin-top: 0;
}
.markdown-content :deep(:last-child) {
  margin-bottom: 0;
}
.markdown-content :deep(h1),
.markdown-content :deep(h2),
.markdown-content :deep(h3) {
  line-height: 1.4;
  margin-top: 1.5em;
  margin-bottom: 0.65em;
}
.markdown-content :deep(h1) {
  font-size: 24px;
}
.markdown-content :deep(h2) {
  font-size: 20px;
}
.markdown-content :deep(h3) {
  font-size: 16px;
}
.markdown-content :deep(p),
.markdown-content :deep(ul),
.markdown-content :deep(ol) {
  margin-block: 0.8em;
}
.markdown-content :deep(li + li) {
  margin-top: 0.35em;
}
.markdown-content :deep(a) {
  color: var(--admin-accent, #4f46e5);
  text-decoration: underline;
  text-underline-offset: 3px;
}
.markdown-content :deep(a:focus-visible) {
  outline: 2px solid var(--admin-accent, #4f46e5);
  outline-offset: 3px;
}
.markdown-content :deep(code) {
  font-family: Consolas, monospace;
  font-size: 0.9em;
}
.markdown-content :deep(:not(pre) > code) {
  padding: 2px 5px;
  border-radius: 4px;
  background: var(--admin-hover, #f1f3f8);
}
.markdown-content :deep(pre) {
  padding: 16px;
  border-radius: 6px;
  background: var(--admin-bg, #f5f6f8);
  overflow: auto;
  max-width: 100%;
}
.markdown-content :deep(img) {
  max-width: 100%;
  height: auto;
}
.markdown-content :deep(table) {
  border-collapse: collapse;
  max-width: 100%;
  display: block;
  overflow: auto;
}
.markdown-content :deep(td),
.markdown-content :deep(th) {
  border: 1px solid var(--admin-border, #e5e8ee);
  padding: 8px;
}
.markdown-content :deep(th) {
  text-align: left;
  background: var(--admin-bg, #f5f6f8);
}
.markdown-content :deep(blockquote) {
  border-left: 3px solid var(--admin-border, #e5e8ee);
  margin-left: 0;
  padding-left: 16px;
  color: var(--admin-muted, #687181);
}
</style>
