<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { CornerDownLeft, FileText, Link, X } from 'lucide-vue-next'
import type { Client } from '../client'
import type { Attachment, Media, Reference } from '../protocol'
import type { PastedTextAttachment } from '../pastedText'
import { ChatButton, ChatDrawer } from './controls'
import MediaImage from './MediaImage.vue'
import { fileSize } from './artifacts'

const props = defineProps<{
  pastedTexts: PastedTextAttachment[]
  attachments: Attachment[]
  images: Media[]
  references: Reference[]
  attachmentFiles: Record<string, File>
  attachmentTexts: Record<string, string>
  client: Client
  session: string
  locked: boolean
}>()
const emit = defineEmits<{
  removePasted: [id: string]
  removeAttachment: [id: string]
  removeImage: [index: number]
  removeReference: [index: number]
  restore: [id: string, confirmed: boolean]
}>()
const preview = ref<{ id: string; confirmed: boolean; name: string; size: number }>()
const previewText = ref(''),
  previewError = ref(''),
  previewNotice = ref(''),
  previewLoading = ref(false)
let previewRequest = 0
const canRestore = computed(() =>
  Boolean(
    preview.value &&
    (!preview.value.confirmed || Object.hasOwn(props.attachmentTexts, preview.value.id))
  )
)

/** 预览只读取当前草稿的本地原文，不解析 HTML，也不额外上传。 */
async function openPreview(id: string, confirmed: boolean) {
  const item = confirmed
    ? props.attachments.find((item) => item.id === id)
    : props.pastedTexts.find((item) => item.id === id)
  if (!item) return
  const request = ++previewRequest
  preview.value = { id, confirmed, name: item.name, size: item.size }
  previewText.value = ''
  previewError.value = ''
  previewNotice.value = ''
  previewLoading.value = false
  const extension = item.name.split('.').pop()?.toLowerCase()
  if (
    confirmed &&
    !Object.hasOwn(props.attachmentTexts, id) &&
    !['txt', 'csv', 'tsv', 'md', 'json'].includes(extension ?? '')
  ) {
    previewNotice.value = '当前格式暂不支持文本预览；附件已添加，将随消息发送。'
    return
  }
  previewLoading.value = true
  try {
    const localText = confirmed
      ? props.attachmentTexts[id]
      : props.pastedTexts.find((item) => item.id === id)?.text
    const file = props.attachmentFiles[id]
    if (localText === undefined && !file) throw new Error('当前草稿的原文件不可用')
    const text = localText ?? (await file!.text())
    if (request === previewRequest) previewText.value = text
  } catch {
    if (request === previewRequest)
      previewError.value = '暂时无法读取附件原文，请移除后重新选择文件。'
  } finally {
    if (request === previewRequest) previewLoading.value = false
  }
}

/** 关闭会使迟到的文件读取失效，避免跨草稿泄漏预览。 */
function closePreview() {
  previewRequest++
  preview.value = undefined
  previewText.value = ''
  previewError.value = ''
  previewNotice.value = ''
  previewLoading.value = false
}

/** 等弹窗归还焦点后再还原正文，光标由输入框所有者恢复。 */
async function restore(id: string, confirmed: boolean) {
  if (props.locked) return
  closePreview()
  await nextTick()
  emit('restore', id, confirmed)
}
watch(() => [props.client, props.session], closePreview)
watch(
  () => [
    props.pastedTexts.map((item) => item.id).join('|'),
    props.attachments.map((item) => item.id).join('|')
  ],
  () => {
    const item = preview.value
    if (
      item &&
      !(item.confirmed ? props.attachments : props.pastedTexts).some((file) => file.id === item.id)
    )
      closePreview()
  }
)
onBeforeUnmount(closePreview)
</script>

<template>
  <div class="draft-resources" aria-label="消息附件">
    <div v-for="(image, index) in images" :key="image.media_id" class="draft-image">
      <MediaImage :client="client" :session="session" :media="image.media_id" />
      <ChatButton
        text
        class="draft-remove"
        :disabled="locked"
        :aria-label="`移除图片 ${index + 1}`"
        title="移除图片"
        @click="emit('removeImage', index)"
        ><X :size="14" aria-hidden="true"
      /></ChatButton>
    </div>
    <article v-for="item in pastedTexts" :key="item.id" class="draft-file">
      <button
        type="button"
        class="draft-file-open"
        :aria-label="`预览 ${item.name}`"
        :title="item.name"
        @click="openPreview(item.id, false)"
      >
        <span class="draft-file-icon"><FileText :size="24" aria-hidden="true" /></span>
        <span class="draft-file-info"
          ><strong>{{ item.name }}</strong
          ><small>TXT · {{ fileSize(item.size) }}</small></span
        >
      </button>
      <button
        type="button"
        class="draft-file-restore"
        :disabled="locked"
        title="粘贴原文至输入框"
        @click="restore(item.id, false)"
      >
        <CornerDownLeft :size="12" aria-hidden="true" />粘贴原文至输入框
      </button>
      <ChatButton
        text
        class="draft-remove"
        :disabled="locked"
        :aria-label="`移除附件 ${item.name}`"
        title="移除附件"
        @click="emit('removePasted', item.id)"
        ><X :size="14" aria-hidden="true"
      /></ChatButton>
    </article>
    <article
      v-for="item in attachments"
      :key="item.id"
      class="draft-file"
      :class="{ 'has-restore': Object.hasOwn(attachmentTexts, item.id) }"
    >
      <button
        type="button"
        class="draft-file-open"
        :aria-label="`预览 ${item.name}`"
        :title="item.name"
        @click="openPreview(item.id, true)"
      >
        <span class="draft-file-icon"><FileText :size="24" aria-hidden="true" /></span>
        <span class="draft-file-info"
          ><strong>{{ item.name }}</strong
          ><small
            >{{ item.name.split('.').pop()?.toUpperCase() }} · {{ fileSize(item.size) }}</small
          ></span
        >
      </button>
      <button
        v-if="Object.hasOwn(attachmentTexts, item.id)"
        type="button"
        class="draft-file-restore"
        :disabled="locked"
        title="粘贴原文至输入框"
        @click="restore(item.id, true)"
      >
        <CornerDownLeft :size="12" aria-hidden="true" />粘贴原文至输入框
      </button>
      <ChatButton
        text
        class="draft-remove"
        :disabled="locked"
        :aria-label="`移除附件 ${item.name}`"
        title="移除附件"
        @click="emit('removeAttachment', item.id)"
        ><X :size="14" aria-hidden="true"
      /></ChatButton>
    </article>
    <div v-for="(item, index) in references" :key="index" class="draft-reference">
      <Link :size="14" aria-hidden="true" /><span :title="item.label">{{ item.label }}</span>
      <ChatButton
        text
        class="draft-reference-remove"
        :disabled="locked"
        aria-label="移除引用"
        title="移除引用"
        @click="emit('removeReference', index)"
        ><X :size="14" aria-hidden="true"
      /></ChatButton>
    </div>
  </div>
  <ChatDrawer
    :model-value="Boolean(preview)"
    :title="preview?.name"
    centered
    size="min(760px, calc(100vw - 32px))"
    class="attachment-preview"
    @update:model-value="closePreview"
  >
    <p class="attachment-preview-meta">
      {{ preview?.name.split('.').pop()?.toUpperCase() }} · {{ fileSize(preview?.size ?? 0)
      }}<span v-if="!previewLoading && !previewError && !previewNotice">
        · {{ previewText.length.toLocaleString() }} 字符</span
      >
    </p>
    <p v-if="previewLoading" role="status" class="muted">正在读取原文…</p>
    <p v-else-if="previewError" role="alert">{{ previewError }}</p>
    <p v-else-if="previewNotice" role="status">{{ previewNotice }}</p>
    <pre v-else class="attachment-preview-text">{{ previewText }}</pre>
    <template #footer>
      <ChatButton
        v-if="canRestore && preview"
        :disabled="locked"
        @click="restore(preview.id, preview.confirmed)"
        ><CornerDownLeft :size="16" aria-hidden="true" />粘贴原文至输入框</ChatButton
      >
      <ChatButton @click="closePreview">关闭</ChatButton>
    </template>
  </ChatDrawer>
</template>

<style scoped>
.draft-resources {
  display: flex;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 8px;
  grid-column: 1 / -1;
  grid-row: 1;
  min-width: 0;
  max-height: 192px;
  overflow: auto;
  padding: 2px;
}
.draft-file {
  position: relative;
  flex: 0 1 276px;
  width: 276px;
  min-width: 0;
  min-height: 80px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface);
  transition:
    background var(--duration-fast) var(--ease),
    border-color var(--duration-fast) var(--ease);
}
.draft-file:hover {
  background: var(--soft);
  border-color: var(--border-strong, var(--border));
}
.draft-file-open {
  display: flex;
  width: 100%;
  min-width: 0;
  align-items: center;
  gap: 12px;
  padding: 0 20px 0 0;
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
}
.draft-file-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 32px;
  height: 40px;
  color: var(--accent);
}
.draft-file-info {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.draft-file-info strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 550;
  line-height: 1.5;
  color: var(--text);
}
.draft-file-info small {
  font-size: 11px;
  line-height: 1.5;
  color: var(--muted);
}
.draft-file-restore {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 2px 0 0 44px;
  padding: 0;
  border: 0;
  border-radius: 3px;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.6;
  cursor: pointer;
}
.draft-file-restore:hover {
  color: var(--accent);
}
.draft-file-restore:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.draft-remove.chat-button {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 24px;
  height: 24px;
  min-width: 24px;
  min-height: 24px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  color: var(--muted);
  background: transparent;
}
.draft-remove.chat-button:hover:not(:disabled) {
  color: var(--text);
  background: var(--soft);
}
.draft-image {
  position: relative;
  width: 80px;
  height: 80px;
  flex: 0 0 80px;
  display: grid;
  place-items: center;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--soft);
  font-size: 11px;
  color: var(--muted);
}
.draft-image :deep(.chat-image) {
  width: 100%;
  height: 100%;
  margin: 0;
}
.draft-image :deep(.chat-image > button),
.draft-image :deep(.chat-image > button img) {
  width: 100%;
  height: 100%;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}
.draft-image :deep(.chat-alert) {
  padding: 4px;
  font-size: 10px;
}
.draft-reference {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--muted);
  font-size: 12px;
}
.draft-reference > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.draft-reference > svg {
  flex: none;
}
.draft-reference-remove.chat-button {
  flex: none;
  width: 24px;
  height: 24px;
  min-width: 24px;
  min-height: 24px;
  padding: 0;
  border: 0;
  background: transparent;
}
.attachment-preview-meta {
  margin: 0 0 16px;
  color: var(--muted);
  font-size: 12px;
}
.attachment-preview :deep(.chat-drawer__header h2) {
  min-width: 0;
  overflow-wrap: anywhere;
  line-height: 1.5;
}
.attachment-preview-text {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  tab-size: 4;
}
@media (max-width: 480px) {
  .draft-file {
    flex-basis: 100%;
    width: 100%;
  }
  .draft-resources {
    max-height: 180px;
  }
}
@media (pointer: coarse) {
  .draft-remove.chat-button {
    width: 32px;
    height: 32px;
    min-width: 32px;
    min-height: 32px;
  }
  .draft-file-open {
    padding-right: 28px;
  }
  .draft-file-restore {
    min-height: 28px;
  }
}
</style>
