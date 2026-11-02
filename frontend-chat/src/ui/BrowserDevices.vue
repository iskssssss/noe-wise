<script setup lang="ts">
import { vDescribedBy } from './describedBy'

import FieldTip from './FieldTip.vue'

import {
  ChatMessageNotice,
  ChatButton,
  ChatDrawer,
  ChatForm,
  ChatFormItem,
  ChatInput,
  type FormInstance
} from './controls'
import { computed, onBeforeUnmount, reactive, ref, watch, useId } from 'vue'
import { query, type Client } from '../client'
import type { Devices } from '../protocol'
import { confirmAction, mayDiscard, validateForm } from './panel'
import { browserPairingAddress } from './browserPairing'
import MediaImage from './MediaImage.vue'
import BrowserDeviceOptions from './BrowserDeviceOptions.vue'
import { browserBridge, connectionBridgeMessage } from './browserConnection'

const props = defineProps<{ modelValue: boolean; client: Client; clientRef: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const busy = ref(false),
  error = ref(''),
  feedback = ref('')
const devices = ref<Devices>(),
  pairUrl = ref('')
const selectedDevice = ref('')
const adapterPending = ref(false)
const adapterKey = () =>
  `noewise:device-pair:${JSON.stringify([props.client.base, props.clientRef])}`
const adapterUnknown = ref(sessionStorage.getItem(adapterKey()) === 'sent')
const deviceNames = reactive<Record<string, string>>({})
const forms = new Map<string, FormInstance>()
const tasks = ref<
  {
    receipt: { invocation: string; status: string; state?: string; error?: string }
    content?: { session_id: string; media_id: string }[]
  }[]
>([])
const required = { required: true, whitespace: true, message: '请填写设备名称', trigger: 'blur' }
const dirty = computed(
  () => devices.value?.items.some((item) => deviceNames[item.device_id] !== item.label) ?? false
)
let abort = new AbortController(),
  epoch = 0,
  disposed = false

async function operation(action: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
  if (busy.value) return
  const token = epoch
  const current = () => !disposed && token === epoch
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    await action(abort.signal, current)
  } catch (cause) {
    if (current())
      error.value =
        cause instanceof Error
          ? cause.message
          : '未能确认操作结果，请先刷新设备与任务列表核对状态。'
  } finally {
    if (current()) busy.value = false
  }
}

async function read() {
  await operation(async (signal, current) => {
    const selector = query({ client_ref: props.clientRef })
    const [value, receipts] = await Promise.all([
      props.client.json<Devices>(`/chat/browser/devices?${selector}`, 'GET', undefined, signal),
      props.client.json<typeof tasks.value>(
        `/chat/browser/tasks?${selector}`,
        'GET',
        undefined,
        signal
      )
    ])
    if (!current()) return
    const previous = devices.value
    devices.value = value
    tasks.value = receipts
    for (const device of value.items) {
      const old = previous?.items.find((item) => item.device_id === device.device_id)
      if (!old || deviceNames[device.device_id] === old.label)
        deviceNames[device.device_id] = device.label
    }
  })
}

async function browser(action: 'pair' | 'rename' | 'select' | 'revoke', device?: string) {
  if (busy.value || (action === 'rename' && !(await validateForm(forms.get(device!))))) return
  if (
    action === 'revoke' &&
    !(await confirmAction(
      `撤销设备“${devices.value?.items.find((item) => item.device_id === device)?.label ?? '所选设备'}”的授权？后续使用需要重新配对。`,
      '撤销设备授权',
      '撤销授权'
    ))
  )
    return
  await operation(async (signal, current) => {
    const value = await props.client.json<Devices | { pairing_url: string }>(
      `/chat/browser/${action}`,
      'POST',
      {
        client_ref: props.clientRef,
        ...(device ? { device_id: device } : {}),
        ...(action === 'rename' ? { label: deviceNames[device!]?.trim() } : {})
      },
      signal
    )
    if (!current()) return
    if (action === 'pair')
      pairUrl.value = browserPairingAddress((value as { pairing_url: string }).pairing_url)
    else if (action !== 'revoke') {
      devices.value = value as Devices
      if (action === 'rename' && device)
        deviceNames[device] =
          devices.value.items.find((item) => item.device_id === device)?.label ??
          deviceNames[device]!
    } else if (devices.value) {
      const removed = devices.value.items.find((item) => item.device_id === device)
      devices.value.items = devices.value.items.filter((item) => item.device_id !== device)
      if (removed?.browser_id === devices.value.selected_browser_id)
        devices.value.selected_browser_id = undefined
    }
    feedback.value =
      action === 'pair'
        ? '配对地址已生成，请粘贴到官方扩展的 Remote connection。'
        : action === 'rename'
          ? '设备名称已保存。'
          : action === 'select'
            ? '默认设备已更新。'
            : '设备授权已撤销。'
  })
}

async function copyPairAddress() {
  try {
    await navigator.clipboard.writeText(pairUrl.value)
    feedback.value = '配对地址已复制，请粘贴到官方扩展的 Remote connection。'
  } catch {
    error.value = '无法自动复制，请选中并复制下方配对地址。'
  }
}

/** 独立设备配对不创建连接等待；地址直接交给适配扩展逐次确认。 */
async function pairWithAdapter() {
  if (busy.value || adapterPending.value || adapterUnknown.value) return
  const version = epoch,
    signal = abort.signal
  const current = () => !disposed && version === epoch
  let ownsBusy = true
  adapterPending.value = true
  busy.value = true
  error.value = ''
  feedback.value = ''
  pairUrl.value = ''
  try {
    const probe = await browserBridge('probe', {}, signal)
    if (!probe.ok) throw new Error(connectionBridgeMessage(probe.code, 'device'))
    if (!current()) return
    adapterUnknown.value = true
    sessionStorage.setItem(adapterKey(), 'sent')
    const result = await props.client.json<{ pairing_url: string }>(
      '/chat/browser/pair',
      'POST',
      { client_ref: props.clientRef },
      signal
    )
    if (!current()) return
    busy.value = false
    ownsBusy = false
    feedback.value = '请打开插件确认配对。配对只建立设备连接，不会恢复任何任务。'
    const response = await browserBridge(
      'pair',
      { pairing_url: result.pairing_url, identity_scope: props.clientRef },
      signal
    )
    if (!current()) return
    if (response.ok || response.code === 'user_rejected' || response.code === 'site_not_trusted') {
      adapterUnknown.value = false
      sessionStorage.removeItem(adapterKey())
    }
    if (response.ok) {
      await read()
      if (current()) feedback.value = '插件已确认配对。请在设备列表查看连接状态，可按需设为默认。'
    } else feedback.value = connectionBridgeMessage(response.code, 'device')
  } catch (cause) {
    if (current())
      error.value =
        cause instanceof Error ? cause.message : '配对结果尚未确认，请查看插件并刷新设备列表。'
  } finally {
    if (current()) {
      if (ownsBusy) busy.value = false
      adapterPending.value = false
    }
  }
}

async function cancel(invocation: string) {
  await operation(async (signal, current) => {
    await props.client.json(
      '/chat/browser/cancel',
      'POST',
      { client_ref: props.clientRef, invocation },
      signal
    )
    if (current())
      feedback.value = '已收到取消请求回执。请刷新任务列表核对是否已停止，清理结果以任务回执为准。'
  })
}
async function requestClose() {
  if (!(await mayDiscard(busy.value, dirty.value))) return false
  for (const device of devices.value?.items ?? []) deviceNames[device.device_id] = device.label
  emit('update:modelValue', false)
  return true
}
async function beforeClose(done: () => void) {
  if (await requestClose()) done()
}
defineExpose({ requestClose })

// 全局设备仅跟随访问者身份；会话切换既不重新授权，也不清空名称草稿。
watch(
  () => [props.client, props.clientRef] as const,
  () => {
    epoch++
    abort.abort()
    abort = new AbortController()
    busy.value = false
    devices.value = undefined
    selectedDevice.value = ''
    adapterPending.value = false
    adapterUnknown.value = sessionStorage.getItem(adapterKey()) === 'sent'
    tasks.value = []
    pairUrl.value = ''
    feedback.value = ''
    error.value = ''
    for (const key of Object.keys(deviceNames)) delete deviceNames[key]
    if (props.modelValue) void read()
  }
)
watch(
  () => props.modelValue,
  (open) => {
    if (open) void read()
    else {
      epoch++
      abort.abort()
      abort = new AbortController()
      busy.value = false
      adapterPending.value = false
    }
  },
  { immediate: true }
)
onBeforeUnmount(() => {
  disposed = true
  epoch++
  abort.abort()
})

const formTipId = useId()
</script>

<template>
  <ChatDrawer
    :model-value="modelValue"
    title="浏览器设备"
    size="min(480px, 100vw)"
    class="browser-devices"
    :before-close="beforeClose"
    :close-on-click-modal="false"
    :show-close="!busy"
    :close-on-press-escape="!busy"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="device-intro">设备属于你的当前身份，可在不同对话中共用。</p>
    <ChatMessageNotice v-if="error" :title="error" type="error" :closable="false" show-icon />
    <ChatMessageNotice
      v-if="feedback"
      :title="feedback"
      type="success"
      :closable="false"
      show-icon
    />
    <div class="device-actions">
      <ChatButton
        native-type="button"
        type="primary"
        :disabled="busy || adapterPending || adapterUnknown"
        @click="pairWithAdapter"
        >配对当前浏览器</ChatButton
      >
      <details class="manual-pair">
        <summary>其他配对方式</summary>
        <ChatButton native-type="button" :disabled="busy" @click="browser('pair')"
          >手动官方扩展配对</ChatButton
        >
      </details>
      <ChatButton native-type="button" :loading="busy" :disabled="busy" @click="read"
        >刷新</ChatButton
      >
    </div>
    <p v-if="adapterUnknown && !adapterPending" role="status">
      配对操作已发出，结果尚未确认。请查看插件并刷新设备列表，不要重复配对。
    </p>
    <p class="device-intro">
      适配扩展可直接接收配对；请先打开插件并信任当前站点。也可通过手动官方扩展配对入口复制地址。
    </p>
    <template v-if="devices?.items.length">
      <BrowserDeviceOptions v-model="selectedDevice" :devices="devices" :disabled="busy" />
      <ChatButton :disabled="busy || !selectedDevice" @click="browser('select', selectedDevice)"
        >设为默认设备</ChatButton
      >
    </template>
    <ChatForm v-if="pairUrl" label-position="top">
      <ChatFormItem label="浏览器配对地址">
        <ChatInput
          v-described-by="formTipId + '-field-0'"
          :aria-describedby="formTipId + '-field-0'"
          :model-value="pairUrl"
          type="textarea"
          readonly
          :rows="4"
        />

        <FieldTip :id="formTipId + '-field-0'"
          >复制此地址到需要授权的浏览器设备中完成配对；配对仅建立设备连接。</FieldTip
        >
      </ChatFormItem>
      <p>将此地址粘贴到官方扩展的 Remote connection，然后在扩展中完成授权。</p>
      <ChatButton native-type="button" :disabled="busy" @click="copyPairAddress"
        >复制配对地址</ChatButton
      >
    </ChatForm>
    <p v-if="!devices?.items.length && !busy && !error" class="device-empty">
      还没有配对设备。配对后，助手可以使用你授权的浏览器完成任务。
    </p>
    <article v-for="device in devices?.items" :key="device.device_id" class="device-card">
      <div class="device-state">
        <span :class="{ online: device.connected }">{{ device.connected ? '在线' : '离线' }}</span
        ><span v-if="devices?.selected_browser_id === device.browser_id">默认设备</span>
      </div>
      <details class="device-management">
        <summary>{{ device.label || device.browser_id }} · 管理设备</summary>
        <ChatForm
          :ref="
            (form) => {
              if (form) forms.set(device.device_id, form as unknown as FormInstance)
              else forms.delete(device.device_id)
            }
          "
          :model="deviceNames"
          :disabled="busy"
          label-position="top"
          @submit.prevent="browser('rename', device.device_id)"
          ><ChatFormItem label="设备名称" :prop="[device.device_id]" :rules="[required]"
            ><ChatInput
              v-described-by="
                formTipId + '-field-1' + '-' + encodeURIComponent(String(device.device_id))
              "
              :aria-describedby="
                formTipId + '-field-1' + '-' + encodeURIComponent(String(device.device_id))
              "
              v-model="deviceNames[device.device_id]"
              maxlength="100"
            />
            <FieldTip
              :id="formTipId + '-field-1' + '-' + encodeURIComponent(String(device.device_id))"
              >填写便于识别的设备名称，最多 100 个字符；保存后更新设备列表。</FieldTip
            >
          </ChatFormItem>
          <div class="device-actions">
            <ChatButton native-type="submit">保存名称</ChatButton
            ><ChatButton
              native-type="button"
              :disabled="busy || devices?.selected_browser_id === device.browser_id"
              @click="browser('select', device.device_id)"
              >设为默认</ChatButton
            ><ChatButton
              native-type="button"
              :disabled="busy"
              @click="browser('revoke', device.device_id)"
              >撤销授权</ChatButton
            >
          </div></ChatForm
        >
      </details>
    </article>
    <section class="device-tasks">
      <h3>浏览器任务</h3>
      <p v-if="!tasks.length && !busy && !error" class="device-empty">
        当前没有浏览器任务，发起需要浏览器的对话后可在这里查看。
      </p>
      <article v-for="(item, index) in tasks" :key="item.receipt.invocation" class="device-card">
        <h4>任务 {{ index + 1 }}</h4>
        <p>{{ item.receipt.state || item.receipt.status }}</p>
        <p v-if="item.receipt.error">{{ item.receipt.error }}</p>
        <MediaImage
          v-for="image in item.content"
          :key="image.media_id"
          :client="client"
          :session="image.session_id"
          :media="image.media_id"
        /><ChatButton native-type="button" :disabled="busy" @click="cancel(item.receipt.invocation)"
          >请求取消</ChatButton
        >
      </article>
    </section>
    <p class="device-intro">关闭面板会保留设备授权；取消任务以服务返回的清理回执为准。</p>
    <template #footer
      ><ChatButton :disabled="busy" @click="requestClose">完成</ChatButton></template
    >
  </ChatDrawer>
</template>

<style scoped>
.manual-pair summary,
.device-management summary {
  cursor: pointer;
  min-height: 28px;
  line-height: 28px;
  overflow-wrap: anywhere;
}
.manual-pair {
  color: var(--muted);
}
.device-management form {
  margin-top: 12px;
}

.device-intro,
.device-empty {
  font-size: 13px;
  color: var(--muted);
  line-height: 1.8;
}
.device-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin: 14px 0;
}
.device-actions :deep(.chat-button + .chat-button) {
  margin-left: 0;
}
.pair-link {
  display: inline-block;
  margin: 4px 0 12px;
  color: var(--accent);
}
.device-card {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 18px;
  margin: 16px 0;
}
.device-state {
  display: flex;
  justify-content: space-between;
  margin-bottom: 16px;
  font-size: 11px;
  color: var(--muted);
}
.device-state .online {
  color: var(--accent);
}
.device-tasks {
  margin-top: 28px;
  border-top: 1px solid var(--border);
}
.device-tasks h3 {
  font-size: 15px;
  font-weight: 600;
  margin-top: 24px;
}
.device-tasks h4 {
  margin: 0;
  font-size: 14px;
}
.device-tasks p {
  font-size: 12px;
  overflow-wrap: anywhere;
}
.device-tasks :deep(img) {
  max-width: 100%;
  border-radius: var(--radius);
}
</style>
