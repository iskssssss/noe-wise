<script setup lang="ts">
import type { Devices } from '../protocol'
import { ChatSelect, ChatOption } from './controls'
defineProps<{ devices: Devices; modelValue: string; disabled?: boolean }>()
defineEmits<{ 'update:modelValue': [value: string] }>()
</script>
<template>
  <ChatSelect
    :model-value="modelValue"
    :disabled="disabled"
    aria-label="本人浏览器设备"
    @update:model-value="$emit('update:modelValue', String($event))"
  >
    <ChatOption
      v-for="device in devices.items"
      :key="device.device_id"
      :value="device.device_id"
      :disabled="!device.connected"
      :label="`${device.label || device.browser_id} · ${device.connected ? '在线' : '离线'}`"
    />
  </ChatSelect>
  <p v-if="modelValue">
    授权到期：{{ devices.items.find((item) => item.device_id === modelValue)?.expires_at }}
  </p>
</template>
