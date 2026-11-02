<script setup lang="ts">
import { HttpClientError } from '@skillforge/shared/utils/http'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { onBeforeUnmount, ref, watch } from 'vue'
import type { ImageInstance } from 'element-plus'
import { api } from '../api'

const props = withDefaults(
  defineProps<{ session: string; media: string; label?: string; alt?: string }>(),
  { label: '图片', alt: '会话图片' }
)
const image = ref<ImageInstance>()
const source = ref(''),
  error = ref(''),
  feedbackVisible = ref(false),
  retry = ref(0)
let controller = new AbortController()

/** 图片只从已确认历史或回执的会话和媒体身份读取；切换后拒绝迟到响应并回收旧Blob地址。 */
watch(
  () => [props.session, props.media, retry.value],
  async () => {
    controller.abort()
    controller = new AbortController()
    const signal = controller.signal
    if (source.value) URL.revokeObjectURL(source.value)
    source.value = ''
    error.value = ''
    feedbackVisible.value = false
    try {
      const blob = await api.chat.mediaContent(props.session, props.media, signal)
      if (!signal.aborted) source.value = URL.createObjectURL(blob)
    } catch (cause) {
      if (cause instanceof HttpClientError) cause.claimFeedback()
      if (!signal.aborted) {
        error.value = '图片暂时无法读取'
        feedbackVisible.value = true
      }
    }
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  controller.abort()
  if (source.value) URL.revokeObjectURL(source.value)
})
</script>

<template>
  <ElImage
    v-if="source"
    ref="image"
    :src="source"
    :preview-src-list="[source]"
    preview-teleported
    :alt="alt"
    fit="contain"
    class="session-media-image"
    ><template #error
      ><span>{{ label }}暂时无法显示</span></template
    ></ElImage
  >
  <ElButton v-if="source" link :aria-label="'查看' + alt + '大图'" @click="image?.showPreview()"
    >查看大图</ElButton
  >
  <template v-else-if="error">
    <span class="session-media-placeholder">{{ label }}未加载</span>
    <ElButton link @click="retry++">重新加载图片</ElButton>
    <ElButton v-if="!feedbackVisible" link @click="feedbackVisible = true">查看恢复操作</ElButton>
    <FeedbackNotice
      v-if="feedbackVisible"
      :title="error"
      type="warning"
      :closable="false"
      @close="feedbackVisible = false"
    >
      <ElButton @click="retry++">重新加载图片</ElButton>
    </FeedbackNotice>
  </template>
  <span v-else role="status">正在加载{{ label }}…</span>
</template>

<style scoped>
.session-media-image {
  display: block;
  max-width: 100%;
  width: min(640px, 100%);
}
.session-media-image :deep(img) {
  max-height: 360px;
  object-fit: contain;
}
</style>
