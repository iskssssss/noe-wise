<template>
  <ElDrawer :model-value="true" title="知识依据" size="min(560px, 100%)" @close="close">
    <div v-loading="loading">
      <template v-if="content">
        <p>{{ content.label }}</p>
        <p class="admin-muted">来源版本：{{ content.source_version }}</p>
        <p class="citation-text">{{ content.text }}</p>
      </template>
      <ElButton v-if="retry" @click="load">重试读取</ElButton>
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { message } from '@skillforge/shared/feedback'
import { get } from '../api'
import { useAuthStore } from '../stores/auth'

const props = defineProps<{ selection: { session: string; message: string; citation: string } }>()
const emit = defineEmits<{ close: [] }>()
const auth = useAuthStore()
const content = ref<{ label: string; source_version: string; text: string }>()
const loading = ref(false)
const retry = ref(false)
let epoch = 0
let controller: AbortController | undefined

function clear() {
  epoch++
  controller?.abort()
  controller = undefined
  content.value = undefined
  loading.value = false
  retry.value = false
}
function close() {
  clear()
  emit('close')
}
async function load() {
  clear()
  const version = epoch
  const selection = { ...props.selection }
  const identity = auth.identityVersion
  const abort = new AbortController()
  controller = abort
  const current = () =>
    !abort.signal.aborted && version === epoch && identity === auth.identityVersion
  loading.value = true
  try {
    const query = new URLSearchParams({
      session_id: selection.session,
      message_id: selection.message,
      citation_id: selection.citation
    })
    const value = await get<{ label: string; source_version: string; text: string }>(
      `/chat/citations/content?${query}`,
      { signal: abort.signal, silent: true }
    )
    if (current()) content.value = value
  } catch (cause) {
    if (!current()) return
    const unavailable =
      cause instanceof HttpClientError && (cause.status === 403 || cause.status === 404)
    retry.value = !unavailable
    message.error(unavailable ? '来源不可用或无权查看' : '读取知识依据失败，请重试')
    if (unavailable) close()
  } finally {
    if (current()) loading.value = false
  }
}
watch(
  () => props.selection,
  () => void load(),
  { immediate: true, flush: 'sync' }
)
watch(() => auth.identityVersion, close, { flush: 'sync' })
onScopeDispose(clear)
</script>

<style scoped>
.citation-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
