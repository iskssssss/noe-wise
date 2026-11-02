<script setup lang="ts">
import {
  FileText as IconFileText,
  RefreshCw as IconRefreshCw,
  X as IconX,
  Quote as IconQuote,
  ChevronRight as IconChevronRight,
  Maximize2 as IconMaximize,
  Minimize2 as IconMinimize,
  LoaderCircle as IconLoader
} from 'lucide-vue-next'
import FieldTip from './FieldTip.vue'
import { ChatSelect, ChatMessageNotice } from './controls'

import { computed, nextTick, onBeforeUnmount, ref, watch, useId } from 'vue'
import type { Client } from '../client'
import type { Deliverable, Reference } from '../protocol'
import { artifactFileReference, artifactUrl, canPreview, fileSize } from './artifacts'
import Markdown from './Markdown.vue'

const props = defineProps<{
  modelValue: boolean
  client: Client
  session: string
  artifacts: Deliverable[]
  selectedId?: string
  loading?: boolean
  error?: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'update:selectedId': [value: string]
  reference: [value: Reference]
  refresh: []
}>()
const selected = ref(''),
  busy = ref(false),
  operationError = ref(''),
  feedback = ref('')
const panel = ref<HTMLElement>()
const previewElement = ref<HTMLElement>(),
  previewFullscreen = ref(false),
  previewLoad = ref<'idle' | 'loading' | 'slow' | 'loaded' | 'error'>('idle')
let loadTimer: ReturnType<typeof setTimeout> | undefined,
  previewSequence = 0
const artifact = computed(() => props.artifacts.find((item) => item.id === selected.value))
const preview = ref<{
  url: string
  kind: string
  text?: string
  name: string
  artifactId: string
  fileId: string
  loadId: number
}>()
const baseFile = ref(''),
  targetFile = ref('')
type FileDifference = {
  status: 'comparable' | 'binary' | 'too_large' | 'too_many_lines'
  changes: { type: string; text: string }[]
}
const differences = ref<FileDifference>()
const differenceStatus = computed(() => {
  if (!differences.value) return ''
  const messages: Record<FileDifference['status'], string> = {
    comparable: differences.value.changes.length
      ? '文件文本差异如下。'
      : '两个文件的文本内容一致。',
    binary: '所选文件包含非文本内容，无法显示文本差异；请下载后比较。',
    too_large: '文件超过在线比较大小限制，请下载后比较。',
    too_many_lines: '文件行数超过在线比较限制，请下载后比较。'
  }
  return messages[differences.value.status]
})
watch([baseFile, targetFile], () => {
  differences.value = undefined
})
const allFiles = computed(() =>
  props.artifacts
    .filter((item) => item.status === 'ready')
    .flatMap((item) =>
      item.files.map((file) => ({
        key: `${item.id}:${file.file_id}`,
        label: `${item.name} v${item.revision} / ${file.download_name}`,
        artifact_id: file.artifact_id,
        path: file.path
      }))
    )
)
let request = new AbortController(),
  generation = 0,
  disposed = false
const media = window.matchMedia?.('(max-width: 800px)')
const mobile = ref(media?.matches ?? false)
const mediaChanged = (event: MediaQueryListEvent) => {
  mobile.value = event.matches
}
media?.addEventListener('change', mediaChanged)
let returnFocus: HTMLElement | undefined
const isolated = new Map<HTMLElement, boolean>()

/** 还原页面内布局，网页仍使用同一 iframe，保留其滚动与交互状态。 */
function exitPreviewFullscreen(restoreFocus = true) {
  const element = previewElement.value
  previewFullscreen.value = false
  if (restoreFocus && !disposed)
    void nextTick(() => element?.querySelector<HTMLElement>('.preview-fullscreen-toggle')?.focus())
}

/** 只铺满当前页面的可视区域，浏览器和桌面窗口不参与切换。 */
function togglePreviewFullscreen() {
  if (previewFullscreen.value) {
    exitPreviewFullscreen()
    return
  }
  if (!previewElement.value || preview.value?.kind !== 'html') return
  previewFullscreen.value = true
}

function clearLoadTimer() {
  clearTimeout(loadTimer)
  loadTimer = undefined
}
function previewLoaded(event: Event) {
  if (disposed || !props.modelValue) return
  if ((event.target as HTMLElement).dataset.loadId !== String(preview.value?.loadId)) return
  clearLoadTimer()
  // iframe 的 load 仅表示导航结束，不代表页面业务或 HTTP 状态成功。
  previewLoad.value = 'loaded'
}
function previewFailed(event: Event) {
  if (disposed || !props.modelValue) return
  if ((event.target as HTMLElement).dataset.loadId !== String(preview.value?.loadId)) return
  clearLoadTimer()
  previewLoad.value = 'error'
}
watch(preview, (shown, previous) => {
  clearLoadTimer()
  previewLoad.value = shown?.kind === 'html' ? 'loading' : 'idle'
  if (shown?.kind === 'html')
    loadTimer = setTimeout(() => {
      if (!disposed && props.modelValue && preview.value === shown) previewLoad.value = 'slow'
    }, 15_000)
  if (!shown || shown.artifactId !== previous?.artifactId || shown.fileId !== previous?.fileId)
    exitPreviewFullscreen(false)
})

function clearOperationFeedback() {
  operationError.value = ''
  feedback.value = ''
}
function cancelRead() {
  generation++
  request.abort()
  request = new AbortController()
  busy.value = false
  clearOperationFeedback()
}
function releaseFocus() {
  for (const [element, inert] of isolated) element.inert = inert
  isolated.clear()
}
async function requestClose() {
  exitPreviewFullscreen(false)
  cancelRead()
  preview.value = undefined
  emit('update:modelValue', false)
  return true
}
defineExpose({ requestClose })

function collapsePreview() {
  cancelRead()
  exitPreviewFullscreen(false)
  preview.value = undefined
}

async function retryPreview() {
  const shown = preview.value,
    chosen = artifact.value
  if (!shown || shown.artifactId !== chosen?.id) return
  const file = chosen.files.find((item) => item.file_id === shown.fileId)
  if (file) await fileAction(file, 'preview')
}

// 窄屏成果和全屏预览隔离背景，普通桌面成果仍允许继续输入。
watch(
  () => [props.modelValue, mobile.value, previewFullscreen.value] as const,
  async ([open], previous) => {
    releaseFocus()
    if (open && !previous?.[0]) returnFocus = document.activeElement as HTMLElement
    if (!open) {
      exitPreviewFullscreen(false)
      clearLoadTimer()
      cancelRead()
      if (previous?.[0] && returnFocus?.isConnected) returnFocus.focus()
      return
    }
    await nextTick()
    if (!props.modelValue || (!mobile.value && !previewFullscreen.value)) return
    const scope = previewFullscreen.value ? previewElement.value : panel.value
    if (!scope) return
    let current: HTMLElement = scope
    while (current.parentElement) {
      for (const sibling of current.parentElement.children) {
        if (
          sibling instanceof HTMLElement &&
          sibling !== current &&
          !sibling.classList.contains('artifact-backdrop')
        ) {
          isolated.set(sibling, sibling.inert)
          sibling.inert = true
        }
      }
      if (current.parentElement === document.body) break
      current = current.parentElement
    }
    // 仅缩放时保留当前 scope 内的焦点，包含父页面可见的 iframe 元素。
    if (
      previous?.[0] &&
      previous[1] !== mobile.value &&
      previous[2] === previewFullscreen.value &&
      scope.contains(document.activeElement)
    )
      return
    if (previewFullscreen.value)
      scope.querySelector<HTMLElement>('.preview-fullscreen-toggle')?.focus()
    else scope.focus()
  },
  { immediate: true }
)

function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    if (previewFullscreen.value) exitPreviewFullscreen()
    else void requestClose()
  }
  const scope = previewFullscreen.value ? previewElement.value : panel.value
  if ((!mobile.value && !previewFullscreen.value) || event.key !== 'Tab' || !scope) return
  const elements = [
    ...scope.querySelectorAll<HTMLElement>(
      'button:not(:disabled), a[href], select:not(:disabled), summary, iframe, [tabindex="0"]'
    )
  ].filter((item) => item.getClientRects().length)
  const first = elements[0],
    last = elements.at(-1)
  if (!first || !last) {
    event.preventDefault()
    return
  }
  if (event.shiftKey && (document.activeElement === first || document.activeElement === scope)) {
    event.preventDefault()
    last.focus()
  } else if (
    !event.shiftKey &&
    (document.activeElement === last || document.activeElement === scope)
  ) {
    event.preventDefault()
    first.focus()
  }
}

async function operation(action: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
  if (busy.value) return
  const token = generation,
    session = props.session,
    selectedId = selected.value
  const current = () =>
    !disposed &&
    generation === token &&
    props.modelValue &&
    props.session === session &&
    selected.value === selectedId &&
    artifact.value?.id === selectedId
  busy.value = true
  clearOperationFeedback()
  try {
    await action(request.signal, current)
  } catch (cause) {
    if (current())
      operationError.value =
        cause instanceof Error ? cause.message : '未能读取所选文件，请重新打开文件，或下载后查看。'
  } finally {
    if (current()) busy.value = false
  }
}

async function fileAction(file: Deliverable['files'][number], action: 'preview' | 'download') {
  const chosen = artifact.value
  if (!chosen || chosen.status !== 'ready') return
  await operation(async (signal, current) => {
    const ticket = await props.client.json<{ url: string; kind: string }>(
      `/chat/deliverables/${action}-ticket`,
      'POST',
      { id: chosen.id, file_id: file.file_id },
      signal
    )
    if (!current()) return
    const url = artifactUrl(ticket.url, props.client.base)
    if (action === 'download') {
      const link = document.createElement('a')
      link.href = url
      link.download = file.download_name
      link.rel = 'noreferrer noopener'
      link.target = '_blank'
      link.click()
      feedback.value = '已发起下载，请在浏览器下载列表中查看。'
    } else if (ticket.kind === 'html') {
      preview.value = {
        url,
        kind: 'html',
        name: file.download_name,
        artifactId: chosen.id,
        fileId: file.file_id,
        loadId: ++previewSequence
      }
    } else {
      if (file.size > 2_000_000) throw new Error('文件较大，请下载后查看。')
      const response = await fetch(url, { signal, credentials: 'omit', redirect: 'error' })
      if (!response.ok)
        throw new Error('未能读取文件预览，请再次点击“预览”获取链接，或下载后查看。')
      const text = await response.text()
      if (current())
        preview.value = {
          url,
          kind: ticket.kind,
          text,
          name: file.download_name,
          artifactId: chosen.id,
          fileId: file.file_id,
          loadId: ++previewSequence
        }
    }
  })
}

function selectArtifact(id: string) {
  cancelRead()
  selected.value = id
  preview.value = undefined
  const chosen = artifact.value
  const file =
    chosen?.files.find((item) => item.primary_preview && canPreview(item)) ??
    chosen?.files.find(canPreview)
  if (props.modelValue && chosen?.status === 'ready' && file) void fileAction(file, 'preview')
}
function chooseArtifact(id: string) {
  selectArtifact(id)
  emit('update:selectedId', id)
}

watch(
  () => props.session,
  () => {
    cancelRead()
    selected.value = ''
    preview.value = undefined
    differences.value = undefined
    baseFile.value = ''
    targetFile.value = ''
  }
)
watch(
  () => [props.modelValue, props.selectedId, props.artifacts] as const,
  ([open, id], previous) => {
    if (!open) return
    const requested = id && props.artifacts.some((item) => item.id === id) ? id : ''
    const next =
      requested ||
      (artifact.value
        ? selected.value
        : (props.artifacts.find((item) => item.current)?.id ?? props.artifacts[0]?.id ?? ''))
    if (next !== selected.value || (next && !previous?.[0])) selectArtifact(next)
  },
  { immediate: true }
)

async function recover() {
  const chosen = artifact.value
  if (!chosen || chosen.status !== 'ready') return
  await operation(async (signal, current) => {
    const value = await props.client.json<{ references: Reference[] }>(
      '/chat/deliverables/recovery-inputs',
      'POST',
      { revision_id: chosen.id },
      signal
    )
    if (!current()) return
    for (const reference of value.references) emit('reference', reference)
  })
}

function referenceFile(file: Deliverable['files'][number]) {
  const chosen = artifact.value
  if (!chosen || chosen.status !== 'ready') return
  clearOperationFeedback()
  emit('reference', artifactFileReference(chosen, file))
}

async function compare() {
  await operation(async (signal, current) => {
    const base = allFiles.value.find((file) => file.key === baseFile.value),
      target = allFiles.value.find((file) => file.key === targetFile.value)
    if (!base || !target || baseFile.value === targetFile.value)
      throw new Error('请选择两个不同的文件。')
    const result = await props.client.json<NonNullable<typeof differences.value>>(
      '/chat/artifacts/diff',
      'POST',
      {
        base: { artifact_id: base.artifact_id, path: base.path },
        target: { artifact_id: target.artifact_id, path: target.path }
      },
      signal
    )
    if (current()) differences.value = result
  })
}

onBeforeUnmount(() => {
  disposed = true
  exitPreviewFullscreen(false)
  clearLoadTimer()
  cancelRead()
  releaseFocus()
  media?.removeEventListener('change', mediaChanged)
})

const formTipId = useId()
</script>

<template>
  <div v-if="modelValue && mobile" class="artifact-backdrop" @click="requestClose" />
  <aside
    v-if="modelValue"
    ref="panel"
    class="artifact-panel"
    :role="mobile ? 'dialog' : 'complementary'"
    :aria-modal="mobile || undefined"
    aria-label="会话成果"
    tabindex="-1"
    @keydown="keydown"
  >
    <header class="artifact-panel-header">
      <div class="artifact-heading">
        <IconFileText aria-hidden="true" />
        <h2>{{ artifact?.name || '会话成果' }}</h2>
      </div>
      <div class="artifact-actions artifact-header-actions">
        <button
          type="button"
          class="artifact-icon"
          :disabled="loading"
          :aria-label="loading ? '正在更新成果' : '刷新成果'"
          :title="loading ? '正在更新成果' : '刷新成果'"
          @click="emit('refresh')"
        >
          <IconRefreshCw aria-hidden="true" />
        </button>
        <button
          type="button"
          class="artifact-icon"
          aria-label="关闭成果面板"
          title="关闭成果面板"
          @click="requestClose"
        >
          <IconX aria-hidden="true" />
        </button>
      </div>
    </header>
    <div class="artifact-panel-body">
      <ChatMessageNotice
        v-if="operationError || props.error"
        :title="operationError || props.error"
        type="error"
      />
      <ChatMessageNotice v-if="feedback" :title="feedback" type="success" />
      <div v-if="!artifacts.length" class="artifact-empty">
        <span class="artifact-empty-mark" aria-hidden="true">
          <IconFileText />
        </span>
        <h3>{{ loading ? '正在读取成果…' : error ? '成果尚未读取成功' : '成果会出现在这里' }}</h3>
        <template v-if="!loading && error">
          <p>请重新读取成果，确认当前会话的成果列表。</p>
          <button type="button" class="artifact-continue" @click="emit('refresh')">
            重新读取成果
          </button>
        </template>
        <p v-else-if="!loading">对话中生成的报告、网页和文件，可以在这里阅读和继续修改。</p>
      </div>
      <p v-if="artifacts.length && error" class="artifact-publication" role="status">
        成果刷新失败，当前展示上次读取的内容。
      </p>
      <template v-if="artifact">
        <div class="artifact-toolbar">
          <div class="artifact-select-label">
            <span>成果版本</span>
            <ChatSelect
              aria-label="成果版本"
              :aria-describedby="formTipId + '-group-0'"
              :model-value="selected"
              :disabled="busy"
              :options="
                artifacts.map((item) => ({
                  value: item.id,
                  label: `${item.name} · v${item.revision}${item.current ? '（当前）' : ''}`
                }))
              "
              @change="chooseArtifact"
            />

            <FieldTip :id="formTipId + '-group-0'"
              >选择成果版本查看对应文件；继续修改会引用当前选定版本。</FieldTip
            >
          </div>
          <button
            v-if="artifact.current"
            type="button"
            class="artifact-continue"
            title="引用此版本，继续修改"
            :disabled="busy || artifact.status !== 'ready'"
            @click="recover"
          >
            <IconQuote aria-hidden="true" />
            引用并继续修改
          </button>
        </div>
        <p v-if="artifact.status === 'ready'" class="artifact-publication" role="status">
          已发布 · 可下载
        </p>
        <section v-if="!preview" class="artifact-info">
          <span class="artifact-document-label">{{
            artifact.type === 'website' ? '网页成果' : '文件成果'
          }}</span>
          <h3>{{ artifact.name }}</h3>
          <p>
            v{{ artifact.revision }} · {{ artifact.current ? '当前版本' : '历史版本' }} ·
            {{ artifact.files.length }} 个文件
          </p>
          <p v-if="artifact.status !== 'ready'">此版本暂不可用，无法预览、下载或引用。</p>
        </section>
        <p v-if="busy" class="artifact-loading" role="status">正在准备…</p>
        <section
          v-if="preview"
          ref="previewElement"
          class="artifact-preview"
          :class="{ 'is-fullscreen': previewFullscreen }"
          :role="previewFullscreen ? 'dialog' : undefined"
          :aria-modal="previewFullscreen || undefined"
          :aria-label="preview.name"
        >
          <header class="artifact-paper-header">
            <span class="artifact-document-label">
              {{ artifact.type === 'website' ? '网页成果' : '文件成果' }} · v{{ artifact.revision }}
            </span>
            <h3 v-if="preview.kind !== 'markdown'">{{ artifact.name }}</h3>
            <div class="preview-caption">
              <span :title="preview.name">{{ preview.name }}</span>
              <div class="preview-actions">
                <button
                  v-if="preview.kind === 'html'"
                  type="button"
                  class="artifact-icon"
                  aria-label="重新加载网页"
                  title="重新加载网页"
                  :disabled="busy"
                  @click="retryPreview"
                >
                  <IconRefreshCw aria-hidden="true" />
                </button>
                <button
                  v-if="preview.kind === 'html'"
                  type="button"
                  class="artifact-icon preview-fullscreen-toggle"
                  :aria-label="previewFullscreen ? '退出网页全屏' : '网页全屏预览'"
                  :title="previewFullscreen ? '退出网页全屏' : '网页全屏预览'"
                  :aria-pressed="previewFullscreen"
                  @click="togglePreviewFullscreen"
                >
                  <IconMinimize v-if="previewFullscreen" aria-hidden="true" />
                  <IconMaximize v-else aria-hidden="true" />
                </button>
                <button type="button" @click="collapsePreview">收起预览</button>
              </div>
            </div>
          </header>
          <div
            v-if="
              preview.kind === 'html' &&
              (busy || ['loading', 'slow', 'error'].includes(previewLoad))
            "
            class="preview-web-status"
            role="status"
          >
            <IconLoader
              v-if="busy || previewLoad === 'loading'"
              class="preview-spinner"
              aria-hidden="true"
            />
            <span>{{
              busy
                ? '正在重新准备网页…'
                : previewLoad === 'loading'
                  ? '正在加载网页…'
                  : previewLoad === 'slow'
                    ? '网页加载时间较长，可以重新加载。'
                    : '网页暂未载入，请重新加载。'
            }}</span>
          </div>
          <iframe
            v-if="preview.kind === 'html'"
            :key="preview.loadId"
            :data-load-id="preview.loadId"
            :src="preview.url"
            :title="preview.name"
            sandbox="allow-scripts"
            referrerpolicy="no-referrer"
            :aria-busy="previewLoad === 'loading' || previewLoad === 'slow'"
            @load="previewLoaded"
            @error="previewFailed"
          /><Markdown v-else-if="preview.kind === 'markdown'" :text="preview.text ?? ''" />
          <pre v-else>{{ preview.text }}</pre>
        </section>
        <details class="artifact-file-section" :open="!preview">
          <summary>
            <span
              >文件 <small>{{ artifact.files.length }}</small></span
            >
            <IconChevronRight class="artifact-chevron" aria-hidden="true" />
          </summary>
          <ul class="artifact-files">
            <li v-for="file in artifact.files" :key="file.file_id">
              <span class="artifact-file-mark" aria-hidden="true">
                <IconFileText />
              </span>
              <div class="artifact-file-name">
                <strong>{{ file.download_name }}</strong
                ><small>{{ file.format.toUpperCase() }} · {{ fileSize(file.size) }}</small>
              </div>
              <div class="artifact-actions">
                <button
                  v-if="canPreview(file)"
                  type="button"
                  :disabled="busy || artifact.status !== 'ready'"
                  @click="fileAction(file, 'preview')"
                >
                  预览</button
                ><button
                  type="button"
                  :disabled="busy || artifact.status !== 'ready'"
                  @click="fileAction(file, 'download')"
                >
                  下载</button
                ><button
                  type="button"
                  :disabled="artifact.status !== 'ready'"
                  @click="referenceFile(file)"
                >
                  引用
                </button>
              </div>
            </li>
          </ul>
        </details>
      </template>
      <details v-if="allFiles.length > 1" class="artifact-compare">
        <summary>
          <span>比较文件版本</span>
          <IconChevronRight class="artifact-chevron" aria-hidden="true" />
        </summary>
        <form @submit.prevent="compare">
          <div class="artifact-compare-field">
            <label>基础文件</label
            ><ChatSelect
              aria-label="基础文件"
              :aria-describedby="formTipId + '-group-1'"
              v-model="baseFile"
              :disabled="busy"
              :options="allFiles.map((file) => ({ value: file.key, label: file.label }))"
            />
          </div>
          <div class="artifact-compare-field">
            <label>目标文件</label
            ><ChatSelect
              aria-label="目标文件"
              :aria-describedby="formTipId + '-group-1'"
              v-model="targetFile"
              :disabled="busy"
              :options="allFiles.map((file) => ({ value: file.key, label: file.label }))"
            />
          </div>
          <button
            type="submit"
            :disabled="busy || !baseFile || !targetFile || baseFile === targetFile"
          >
            比较差异
          </button>

          <FieldTip :id="formTipId + '-group-1'"
            >选择两个不同文件，比较结果只对应当前选定的版本。</FieldTip
          >
        </form>
        <p v-if="differences" role="status">{{ differenceStatus }}</p>
        <pre v-for="(change, index) in differences?.changes" :key="index" :class="change.type"
          >{{ change.type === 'added' ? '+' : change.type === 'removed' ? '-' : ' ' }} {{
            change.text
          }}</pre>
      </details>
    </div>
  </aside>
</template>

<style scoped>
.artifact-panel {
  width: 100%;
  max-width: none;
  min-width: 0;
  flex: none;
  align-self: stretch;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--surface);
  color: var(--text);
  margin: 0;
  border: 0;
  border-left: 1px solid var(--border);
  border-radius: 0;
  overflow: hidden;
  animation: panel-enter var(--duration) var(--ease);
}
.artifact-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 72px;
  padding: 16px 24px;
  border-bottom: 1px solid var(--border);
}
.artifact-heading {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 9px;
}
.artifact-heading > svg {
  color: var(--muted);
}
.artifact-panel-header h2 {
  min-width: 0;
  font-size: 14px;
  margin: 0;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.artifact-panel-body {
  padding: 18px 28px 28px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  font-size: 13px;
  scrollbar-width: thin;
  scrollbar-color: var(--border) transparent;
}
button,
select {
  color: var(--text);
  background: transparent;
  border: 0;
  border-radius: var(--radius);
  font: inherit;
  font-size: 12px;
}
button {
  cursor: pointer;
  padding: 7px 9px;
  transition:
    background var(--duration-fast) var(--ease),
    color var(--duration-fast) var(--ease);
}
button:hover:not(:disabled) {
  background: var(--soft);
}
button:disabled {
  cursor: default;
  opacity: 0.55;
}
button:focus-visible,
select:focus-visible,
summary:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
}
svg {
  width: 16px;
  height: 16px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.artifact-publication {
  color: var(--accent);
  font-size: 12px;
}
.artifact-icon {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  padding: 0;
  color: var(--muted);
}
select {
  width: 100%;
  min-width: 0;
  padding: 8px 6px;
  text-overflow: ellipsis;
  background: var(--surface);
}
.artifact-actions {
  display: flex;
  gap: 2px;
  flex-wrap: wrap;
  flex-shrink: 0;
}
.artifact-header-actions {
  flex-wrap: nowrap;
}
.artifact-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding-bottom: 22px;
}
.artifact-select-label {
  min-width: 0;
}
.artifact-select-label > span {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.artifact-select-label select {
  font-size: 11px;
  color: var(--muted);
  padding-left: 0;
}
.artifact-select-label select:hover {
  color: var(--text);
}
.artifact-compare label {
  display: grid;
  gap: 8px;
  color: var(--muted);
  font-size: 12px;
}
.artifact-compare-field {
  display: grid;
  gap: 8px;
}
.artifact-info {
  margin: 14px 3px 24px;
}
.artifact-info h3,
.artifact-paper-header h3 {
  font-size: 23px;
  line-height: 1.65;
  font-weight: 500;
  margin: 8px 0 10px;
  overflow-wrap: anywhere;
}
.artifact-document-label {
  font-size: 11px;
  color: var(--muted);
}
.artifact-info p,
.artifact-loading,
.artifact-empty {
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}
.artifact-empty {
  padding: 52px 12px;
  text-align: center;
}
.artifact-empty-mark {
  display: grid;
  place-items: center;
  width: 44px;
  height: 48px;
  margin: 0 auto 20px;
  background: var(--accent-soft);
  color: var(--accent);
  border-radius: var(--radius);
}
.artifact-empty-mark svg {
  width: 22px;
  height: 22px;
}
.artifact-empty h3 {
  margin: 0 0 9px;
  color: var(--text);
  font-size: 16px;
  font-weight: 500;
}
.artifact-empty p {
  margin: 0;
}
.artifact-files {
  display: grid;
  gap: 8px;
  padding: 0;
  margin: 12px 0 18px;
  list-style: none;
}
.artifact-files li {
  padding: 12px;
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 7px 11px;
  align-items: center;
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.artifact-file-mark {
  display: grid;
  place-items: center;
  width: 36px;
  height: 40px;
  background: var(--accent-soft);
  color: var(--accent);
  border-radius: var(--radius);
}
.artifact-file-name {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.artifact-files .artifact-actions {
  grid-column: 2;
  margin-left: -9px;
  color: var(--muted);
}
.artifact-files strong {
  font-size: 13px;
  font-weight: 500;
  overflow-wrap: anywhere;
}
.artifact-files small {
  font-size: 11px;
  color: var(--muted);
}
.artifact-preview {
  padding: 8px 3px 0;
  margin: 14px 0 24px;
}
.artifact-paper-header {
  border-bottom: 1px solid var(--border);
  padding-bottom: 14px;
  margin-bottom: 19px;
}
.preview-caption {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--muted);
  font-size: 11px;
  overflow-wrap: anywhere;
}
.preview-caption > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.preview-actions button {
  flex: none;
  font-size: 11px;
  padding: 4px 6px;
  color: var(--muted);
}
.preview-actions .artifact-icon {
  width: 36px;
  height: 36px;
  padding: 0;
}
.artifact-preview iframe {
  width: 100%;
  height: min(60dvh, 560px);
  min-height: 320px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  display: block;
}
.preview-web-status {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  min-height: 40px;
  margin-bottom: 8px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.preview-error {
  flex: none;
  margin: 0 0 8px;
}
.preview-spinner {
  animation: preview-spin 1s linear infinite;
}
.artifact-preview.is-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 3500;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100dvh;
  margin: 0;
  padding: 16px;
  background: var(--canvas);
  overflow: hidden;
}
.artifact-preview.is-fullscreen .artifact-paper-header {
  flex: none;
  padding-bottom: 8px;
  margin-bottom: 8px;
}
.artifact-preview.is-fullscreen .artifact-document-label,
.artifact-preview.is-fullscreen .artifact-paper-header > h3 {
  display: none;
}
.artifact-preview.is-fullscreen .preview-caption {
  font-size: 13px;
}
.artifact-preview.is-fullscreen iframe {
  flex: 1;
  height: 0;
  min-height: 0;
}
@media (pointer: coarse) {
  .preview-actions button {
    min-width: 44px;
    min-height: 44px;
  }
}
.artifact-preview :deep(.markdown),
.artifact-preview > pre {
  margin: 0;
  padding: 0;
  overflow: auto;
}
.artifact-preview :deep(.markdown) {
  font-size: 14px;
  line-height: 1.85;
}
.artifact-preview :deep(.markdown h1),
.artifact-preview :deep(.markdown h2) {
  font-size: 23px;
  line-height: 1.65;
  font-weight: 500;
}
.artifact-preview > pre,
.artifact-compare pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
}
.artifact-continue {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
  padding: 7px 8px;
  color: var(--accent);
}
.artifact-file-section,
.artifact-compare {
  border-top: 1px solid var(--border);
  margin-top: 12px;
  padding-top: 14px;
  font-size: 12px;
}
.artifact-file-section summary,
.artifact-compare summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  list-style: none;
  cursor: pointer;
  color: var(--muted);
}
summary::-webkit-details-marker {
  display: none;
}
.artifact-file-section summary small {
  margin-left: 5px;
  font-size: 11px;
}
.artifact-chevron {
  width: 13px;
  height: 13px;
  transition: transform var(--duration-fast) var(--ease);
}
details[open] > summary .artifact-chevron {
  transform: rotate(90deg);
}
.artifact-compare form {
  display: grid;
  gap: 12px;
  margin: 16px 0;
}
.artifact-compare select {
  border: 1px solid var(--border);
}
.artifact-compare button[type='submit'] {
  justify-self: start;
  background: var(--button);
  color: var(--on-button);
  padding: 8px 12px;
}
.artifact-compare .added {
  color: var(--accent);
}
.artifact-compare .removed,
.artifact-error {
  color: var(--error);
}
.artifact-error,
.artifact-feedback {
  font-size: 13px;
  padding: 12px;
  border-radius: var(--radius);
  overflow-wrap: anywhere;
}
.artifact-error {
  background: var(--error-bg);
}
.artifact-feedback {
  background: var(--accent-soft);
  color: var(--accent);
}
.artifact-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2099;
  background: color-mix(in srgb, var(--text) 20%, transparent);
}
@keyframes panel-enter {
  from {
    opacity: 0;
    transform: translateX(12px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
@keyframes preview-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 1100px) {
  .artifact-panel-body {
    padding: 16px 22px 24px;
  }
}
@media (max-width: 800px) {
  .artifact-panel {
    position: fixed;
    inset: 12px;
    z-index: 2100;
    width: auto;
    max-width: none;
    min-width: 0;
    margin: 0;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .artifact-panel-header {
    padding: 16px 18px 12px;
  }
  .artifact-preview iframe {
    height: 60dvh;
    min-height: 280px;
  }
}
@media (max-width: 380px) {
  .artifact-panel-body {
    padding: 12px 16px 20px;
  }
  .artifact-toolbar {
    gap: 6px;
  }
}
@media (pointer: coarse) {
  .artifact-icon {
    min-width: 44px;
    min-height: 44px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .artifact-panel {
    animation: none;
  }
  .preview-spinner {
    animation: none;
  }
  button,
  .artifact-chevron {
    transition: none;
  }
}
</style>
