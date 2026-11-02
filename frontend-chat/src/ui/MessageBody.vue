<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { formatDateTime } from './dateTime'

const props = defineProps<{ createdAt?: string }>()
const timeId = `message-time-${useId()}`
const visible = ref(false)
/** 只展示已有创建时间；缺失时不新增提示，无效值使用统一占位符。 */
const timestamp = computed(() => (props.createdAt ? formatDateTime(props.createdAt) : undefined))
let timer: ReturnType<typeof setTimeout> | undefined
let pointerActivation = false

/** 隐藏时同时撤销延时和视口监听，快速划过或滚动不会留下迟到提示。 */
function hideTime() {
  if (timer !== undefined) clearTimeout(timer)
  timer = undefined
  visible.value = false
  window.removeEventListener('scroll', hideTime, true)
  window.removeEventListener('resize', hideTime)
}
function observeViewport() {
  window.addEventListener('scroll', hideTime, true)
  window.addEventListener('resize', hideTime)
}
/** 触屏不模拟 hover；时间固定在正文下方，仅在持续停留后显示。 */
function scheduleTime(event: PointerEvent) {
  hideTime()
  pointerActivation = false
  if (!timestamp.value || !['mouse', 'pen'].includes(event.pointerType)) return
  observeViewport()
  timer = setTimeout(() => {
    timer = undefined
    visible.value = true
  }, 700)
}
/** 点击选字不触发聚焦提示；键盘聚焦可直接读取并用 Escape 关闭。 */
function focusTime() {
  if (pointerActivation || !timestamp.value) return
  hideTime()
  visible.value = true
  observeViewport()
}
function pointerDown() {
  pointerActivation = true
  hideTime()
}
function blurTime() {
  pointerActivation = false
  hideTime()
}
watch(timestamp, hideTime)
onBeforeUnmount(hideTime)
</script>

<template>
  <div
    class="message-body"
    :tabindex="timestamp ? 0 : undefined"
    :aria-describedby="visible ? timeId : undefined"
    @pointerenter="scheduleTime"
    @pointerleave="hideTime"
    @pointerdown="pointerDown"
    @focus="focusTime"
    @blur="blurTime"
    @keydown.esc="hideTime"
  >
    <slot />
    <time
      v-if="timestamp"
      :id="timeId"
      :datetime="timestamp === '—' ? undefined : createdAt"
      :class="['message-time', { 'is-visible': visible }]"
      :aria-hidden="!visible"
      >{{ timestamp }}</time
    >
  </div>
</template>

<style scoped>
.message-body {
  position: relative;
  min-width: 0;
}
.message-time {
  /* 弱提示不参与气泡尺寸；短消息也能在正文下方显示完整日期。 */
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  display: block;
  width: max-content;
  max-width: calc(100vw - 48px);
  color: var(--muted);
  font-size: 11px;
  font-weight: 400;
  line-height: 16px;
  text-align: right;
  overflow-wrap: anywhere;
  pointer-events: none;
  user-select: none;
  visibility: hidden;
  opacity: 0;
  transition: opacity var(--duration-fast) var(--ease);
}
.message-time.is-visible {
  visibility: visible;
  opacity: 1;
}
@media (prefers-reduced-motion: reduce) {
  .message-time {
    transition: none;
  }
}
</style>
