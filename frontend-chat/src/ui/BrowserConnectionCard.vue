<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { query, type Client } from '../client'
import type { Pending } from '../protocol'
import {
  createBrowserConnection,
  connectionStatusLabel,
  browserConnectionReason,
  browserConnectionError,
  browserConnectionReconnectHint,
  type ConnectionState
} from './browserConnection'
import BrowserDeviceOptions from './BrowserDeviceOptions.vue'
import { ChatAlert, ChatButton, ChatMessageNotice } from './controls'
const props = defineProps<{
  client: Client
  clientRef: string
  pending: Pending
  disabled?: boolean
}>()
const state = ref<ConnectionState>({
  error: '',
  feedback: '',
  busy: false,
  makeDefault: false,
  attempted: {}
})
const device = ref('')
const detail = computed(() => state.value.detail),
  waiting = computed(() => detail.value?.status === 'WAITING')
let connection: ReturnType<typeof createBrowserConnection> | undefined
function action(kind: 'pair' | 'select' | 'cancel') {
  if (props.disabled) return
  return connection?.action(kind, device.value)
}
watch(
  () => [props.client, props.clientRef, props.pending.card?.connection_request_id] as const,
  () => {
    connection?.close()
    device.value = ''
    state.value = { error: '', feedback: '', busy: false, makeDefault: false, attempted: {} }
    const request = String(props.pending.card?.connection_request_id ?? '')
    connection = createBrowserConnection({
      request,
      selector: { client_ref: props.clientRef },
      changed: (value) => {
        state.value = value
      },
      transport: {
        detail: (request, selector, signal) =>
          props.client.json(
            `/chat/browser/connection-requests/detail?${query({ request_id: request, ...selector })}`,
            'GET',
            undefined,
            signal
          ),
        pair: (body, signal) => props.client.json('/chat/browser/pair', 'POST', body, signal),
        select: (body, signal) => props.client.json('/chat/browser/select', 'POST', body, signal),
        cancel: (body, signal) =>
          props.client.json('/chat/browser/connection-requests/cancel', 'POST', body, signal)
      }
    })
    if (request) connection.start()
  },
  { immediate: true }
)
onBeforeUnmount(() => connection?.close())
</script>
<template>
  <section class="browser-connection-card" aria-label="连接浏览器">
    <h3>{{ pending.card?.title || '连接浏览器以继续任务' }}</h3>
    <p>{{ browserConnectionReason(detail?.reason || String(pending.card?.reason ?? '')) }}</p>
    <p role="status">{{ detail ? connectionStatusLabel[detail.status] : '正在读取连接状态…' }}</p>
    <ChatAlert
      v-if="detail?.error"
      :title="browserConnectionError(detail.error)"
      type="error"
      :closable="false"
    />
    <ChatMessageNotice
      v-if="state.error"
      :title="browserConnectionError(state.error)"
      type="error"
    />
    <ChatMessageNotice v-if="state.feedback" :title="state.feedback" type="info" />
    <p v-if="browserConnectionReconnectHint(detail)" class="reconnect-hint">
      {{ browserConnectionReconnectHint(detail) }}
    </p>
    <template v-if="waiting">
      <p v-if="state.attempted.pair || state.attempted.select">
        连接操作已发出；结果未确认时只读取状态，不重复发送。你仍可取消本次连接等待。
      </p>
      <BrowserDeviceOptions
        v-if="detail?.devices.items.length"
        v-model="device"
        :devices="detail.devices"
        :disabled="disabled || state.busy || state.attempted.select"
      />
      <label
        ><input
          :checked="state.makeDefault"
          @change="connection?.setDefault(($event.target as HTMLInputElement).checked)"
          type="checkbox"
          :disabled="disabled || state.busy || state.attempted.pair || state.attempted.select"
        />设为默认浏览器</label
      >
      <div class="connection-actions">
        <ChatButton
          v-if="detail?.devices.items.length"
          :disabled="
            disabled || state.busy || !device || state.attempted.select || state.attempted.pair
          "
          @click="action('select')"
          >使用所选设备继续</ChatButton
        >
        <ChatButton
          :disabled="disabled || state.busy || state.attempted.pair || state.attempted.select"
          @click="action('pair')"
          >配对当前浏览器</ChatButton
        >
        <ChatButton
          :disabled="disabled || state.busy || state.attempted.cancel"
          @click="action('cancel')"
          >取消连接等待</ChatButton
        >
      </div>
      <p>请安装适配扩展，打开插件并信任当前站点；配对后在插件中确认。连接成功后任务会自动继续。</p>
      <a
        v-if="detail?.devices.installation_url?.startsWith('https://')"
        :href="detail.devices.installation_url"
        target="_blank"
        rel="noopener noreferrer"
        >安装浏览器扩展</a
      >
    </template>
  </section>
</template>
<style scoped>
.browser-connection-card {
  border: 1px solid var(--border);
  padding: 20px;
  border-radius: var(--radius);
  margin: 16px 0;
}
.browser-connection-card p {
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.connection-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 16px 0;
}
</style>
