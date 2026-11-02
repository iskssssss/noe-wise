<script setup lang="ts">
import { ChatAlert, ChatButton, ChatImage } from './controls'
import { onBeforeUnmount, ref, watch } from 'vue'
import { query, type Client } from '../client'
const props = defineProps<{ client: Client; session: string; media: string }>()
const source = ref(''),
  error = ref(''),
  retry = ref(0)
let controller = new AbortController()
watch(
  () => [props.session, props.media, props.client, retry.value],
  async () => {
    controller.abort()
    controller = new AbortController()
    const signal = controller.signal
    if (source.value) URL.revokeObjectURL(source.value)
    source.value = ''
    error.value = ''
    try {
      const blob = await props.client.blob(
        `/chat/sessions/media/content?${query({ session_id: props.session, media_id: props.media })}`,
        signal
      )
      if (!signal.aborted) source.value = URL.createObjectURL(blob)
    } catch {
      if (!signal.aborted) error.value = '图片暂时无法读取'
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
  <ChatImage
    v-if="source"
    :src="source"
    :preview-src-list="[source]"
    preview-teleported
    alt="会话图片"
    fit="contain"
    class="message-image"
    ><template #error
      ><ChatAlert title="图片暂时无法显示" type="warning" :closable="false"
        ><ChatButton @click="retry++">重新加载图片</ChatButton></ChatAlert
      ></template
    ></ChatImage
  ><ChatAlert v-else-if="error" :title="error" type="warning" :closable="false"
    ><ChatButton @click="retry++">重新加载图片</ChatButton></ChatAlert
  ><span v-else role="status">正在加载图片…</span>
</template>
