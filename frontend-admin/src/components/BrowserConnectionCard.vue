<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  createBrowserConnection,
  connectionStatusLabel,
  browserConnectionReason,
  browserConnectionError,
  browserConnectionReconnectHint,
  type ConnectionState
} from '@skillforge/shared/utils/browserConnection'
import { api } from '../api'
const props = defineProps<{ session: string; request: string; title?: string; reason?: string }>()
const state = ref<ConnectionState>({
  error: '',
  feedback: '',
  busy: false,
  makeDefault: false,
  attempted: {}
})
const device = ref(''),
  detail = computed(() => state.value.detail)
let connection: ReturnType<typeof createBrowserConnection> | undefined
function action(kind: 'pair' | 'select' | 'cancel') {
  return connection?.action(kind, device.value)
}
watch(
  () => [props.session, props.request] as const,
  () => {
    connection?.close()
    device.value = ''
    state.value = { error: '', feedback: '', busy: false, makeDefault: false, attempted: {} }
    connection = createBrowserConnection({
      request: props.request,
      selector: { session_id: props.session },
      changed: (value) => {
        state.value = value
      },
      transport: {
        detail: api.chat.browserConnectionDetail,
        pair: api.chat.browserConnectionPair,
        select: api.chat.browserConnectionSelect,
        cancel: api.chat.browserConnectionCancel
      }
    })
    if (props.request && props.session) connection.start()
  },
  { immediate: true }
)
onBeforeUnmount(() => connection?.close())
</script>
<template>
  <ElCard class="browser-connection-card">
    <h3>{{ title || '连接浏览器以继续任务' }}</h3>
    <p>{{ browserConnectionReason(detail?.reason || reason) }}</p>
    <p role="status">{{ detail ? connectionStatusLabel[detail.status] : '正在读取连接状态…' }}</p>
    <FeedbackNotice
      v-if="state.error || detail?.error"
      :title="browserConnectionError(state.error || detail?.error)"
      type="error"
      :closable="false"
    />
    <FeedbackNotice v-if="state.feedback" :title="state.feedback" type="info" />
    <p v-if="browserConnectionReconnectHint(detail)" class="reconnect-hint">
      {{ browserConnectionReconnectHint(detail) }}
    </p>
    <template v-if="detail?.status === 'WAITING'">
      <p v-if="state.attempted.pair || state.attempted.select">
        连接操作已发出；结果未确认时只读取状态，不重复发送。你仍可取消本次连接等待。
      </p>
      <ElSelect
        v-if="detail.devices.items.length"
        v-model="device"
        aria-label="本人浏览器设备"
        :disabled="state.busy || state.attempted.select"
      >
        <ElOption
          v-for="item in detail.devices.items"
          :key="item.device_id"
          :value="item.device_id"
          :disabled="!item.connected"
          :label="`${item.label || item.browser_id} · ${item.connected ? '在线' : '离线'}`"
        />
      </ElSelect>
      <p v-if="device">
        授权到期：{{ detail.devices.items.find((item) => item.device_id === device)?.expires_at }}
      </p>
      <ElCheckbox
        :model-value="state.makeDefault"
        :disabled="state.busy || state.attempted.pair || state.attempted.select"
        @update:model-value="connection?.setDefault(Boolean($event))"
        >设为默认浏览器</ElCheckbox
      >
      <div class="connection-actions">
        <ElButton
          v-if="detail.devices.items.length"
          :disabled="state.busy || !device || state.attempted.select || state.attempted.pair"
          @click="action('select')"
          >使用所选设备继续</ElButton
        >
        <ElButton
          :disabled="state.busy || state.attempted.pair || state.attempted.select"
          @click="action('pair')"
          >配对当前浏览器</ElButton
        >
        <ElButton :disabled="state.busy || state.attempted.cancel" @click="action('cancel')"
          >取消连接等待</ElButton
        >
      </div>
      <p>请安装适配扩展，打开插件并信任当前站点；配对后在插件中确认。连接成功后任务会自动继续。</p>
      <a
        v-if="detail.devices.installation_url?.startsWith('https://')"
        :href="detail.devices.installation_url"
        target="_blank"
        rel="noopener noreferrer"
        >安装浏览器扩展</a
      >
    </template>
  </ElCard>
</template>
<style scoped>
.connection-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin: 16px 0;
}
.browser-connection-card p {
  line-height: 1.7;
  overflow-wrap: anywhere;
}
</style>
