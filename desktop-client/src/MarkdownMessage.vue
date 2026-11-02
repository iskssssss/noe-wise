<script setup lang="ts">
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import { Marked } from 'marked'

const props = defineProps<{ text: string }>()
const emit = defineEmits<{ openLink: [url: string] }>()
function escapeHtml(text: string): string {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

// 模型输出只生成有限 Markdown 结构；原 HTML 显示为文本，图片不发起远程加载。
const markdown = new Marked({
  gfm: true,
  breaks: false,
  renderer: {
    html: ({ text }) => escapeHtml(text),
    image: ({ text }) => escapeHtml(`[图片：${text}]`),
    link({ href, title, tokens }) {
      let destination: URL
      try {
        destination = new URL(href)
      } catch {
        return this.parser.parseInline(tokens)
      }
      if (
        !['https:', 'http:'].includes(destination.protocol) ||
        destination.username ||
        destination.password
      )
        return this.parser.parseInline(tokens)
      return `<a href="${escapeHtml(destination.href)}"${title ? ` title="${escapeHtml(title)}"` : ''}>${this.parser.parseInline(tokens)}</a>`
    }
  }
})
const html = computed(() =>
  DOMPurify.sanitize(markdown.parse(props.text, { async: false }), {
    ALLOWED_TAGS: [
      'p',
      'br',
      'hr',
      'blockquote',
      'ul',
      'ol',
      'li',
      'strong',
      'em',
      'del',
      'pre',
      'code',
      'h1',
      'h2',
      'h3',
      'h4',
      'h5',
      'h6',
      'table',
      'thead',
      'tbody',
      'tr',
      'th',
      'td',
      'a'
    ],
    ALLOWED_ATTR: ['href', 'title'],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false
  })
)
function openLink(event: MouseEvent) {
  if (!(event.target instanceof Element)) return
  const link = event.target.closest('a')
  if (!link) return
  event.preventDefault()
  const destination = link.getAttribute('href')
  if (destination) emit('openLink', destination)
}
</script>

<template><div class="markdown-message" v-html="html" @click="openLink" /></template>

<style scoped>
.markdown-message {
  font-size: 14px;
  line-height: 1.8;
  overflow-wrap: anywhere;
  min-width: 0;
  white-space: normal;
}
.markdown-message :deep(> :first-child) {
  margin-top: 0;
}
.markdown-message :deep(> :last-child) {
  margin-bottom: 0;
}
.markdown-message :deep(p) {
  margin: 0 0 var(--space-2);
}
.markdown-message :deep(h1),
.markdown-message :deep(h2),
.markdown-message :deep(h3),
.markdown-message :deep(h4),
.markdown-message :deep(h5),
.markdown-message :deep(h6) {
  color: var(--text);
  line-height: 1.45;
  margin: var(--space-3) 0 var(--space-2);
  font-weight: 600;
  letter-spacing: 0;
}
.markdown-message :deep(h1) {
  font-size: 24px;
}
.markdown-message :deep(h2) {
  font-size: 20px;
}
.markdown-message :deep(h3) {
  font-size: 16px;
}
.markdown-message :deep(h4),
.markdown-message :deep(h5),
.markdown-message :deep(h6) {
  font-size: 14px;
}
.markdown-message :deep(ul),
.markdown-message :deep(ol) {
  margin: var(--space-1) 0 var(--space-2);
  padding-left: var(--space-3);
}
.markdown-message :deep(li) {
  margin: var(--space-1) 0;
}
.markdown-message :deep(pre) {
  background: var(--canvas);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: var(--space-2);
  margin: var(--space-2) 0;
  max-width: 100%;
  overflow: auto;
  white-space: pre;
  overflow-wrap: normal;
  font-size: 12px;
}
.markdown-message :deep(code) {
  font-family: var(--mono);
  background: var(--soft);
  padding: 2px 4px;
  border-radius: 4px;
  font-size: 0.95em;
}
.markdown-message :deep(pre code) {
  background: transparent;
  padding: 0;
  border-radius: 0;
  font-size: inherit;
}
.markdown-message :deep(blockquote) {
  border-left: 3px solid var(--border-strong);
  color: var(--muted);
  margin: var(--space-2) 0;
  padding-left: var(--space-2);
}
.markdown-message :deep(a) {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 3px;
}
.markdown-message :deep(table) {
  display: block;
  max-width: 100%;
  overflow: auto;
  border-collapse: collapse;
  margin: var(--space-2) 0;
}
.markdown-message :deep(td),
.markdown-message :deep(th) {
  padding: var(--space-1) 12px;
  border: 1px solid var(--border);
  text-align: left;
}
.markdown-message :deep(th) {
  background: var(--surface);
  font-weight: 600;
}
.markdown-message :deep(hr) {
  margin: var(--space-3) 0;
  border: 0;
  border-top: 1px solid var(--border);
}
</style>
