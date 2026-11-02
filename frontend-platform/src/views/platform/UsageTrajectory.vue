<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useResource } from '@skillforge/shared/admin'
import { message } from '@skillforge/shared/feedback'
import type { UsageEvent } from '@skillforge/shared/integration/usage'
import { api } from '../../api'

const props = defineProps<{ row: UsageEvent }>()
const open = ref(false)
const target = computed(() => ({
  tenant_id: props.row.tenant_id,
  session_id: props.row.session_id ?? '',
  root_input_id: props.row.root_input_id ?? ''
}))
const eligible = computed(
  () =>
    ['native', 'external'].includes(props.row.origin_kind ?? '') &&
    !!target.value.tenant_id &&
    !!target.value.session_id &&
    !!target.value.root_input_id &&
    !!props.row.run_id &&
    !!props.row.attempt_id
)
const trajectory = useResource((signal) => api.trajectories.detail(target.value, { signal }))
const attempt = computed(() => {
  const result = trajectory.data.value
  if (
    result?.summary.tenant_id !== target.value.tenant_id ||
    result.summary.session_id !== target.value.session_id ||
    result.summary.root_input_id !== target.value.root_input_id
  )
    return undefined
  return result.usage?.attempts.find(
    (item) =>
      item.attempt_id === props.row.attempt_id &&
      item.run_id === props.row.run_id &&
      item.source_session_id === target.value.session_id &&
      item.source_root_input_id === target.value.root_input_id &&
      (!props.row.invocation_id || item.invocation_id === props.row.invocation_id)
  )
})
function show() {
  open.value = true
  void reload()
}
async function reload() {
  trajectory.data.value = undefined
  await trajectory.reload()
}
watch(trajectory.error, (error) => {
  if (error) message.error(`运行记录读取失败：${error}`)
})
watch(
  () => [
    props.row.event_id,
    props.row.tenant_id,
    props.row.session_id,
    props.row.root_input_id,
    props.row.run_id,
    props.row.attempt_id,
    props.row.invocation_id
  ],
  () => {
    trajectory.invalidate()
    trajectory.data.value = undefined
    open.value = false
  }
)
</script>

<template>
  <template v-if="eligible">
    <ElButton link type="primary" @click="show">查看关联运行</ElButton>
    <ElDrawer v-model="open" title="关联运行" size="min(620px, 100%)">
      <p v-if="trajectory.loading.value" role="status">正在核对运行记录…</p>
      <template v-else-if="trajectory.data.value && attempt">
        <p>已找到该用量对应的模型调用。</p>
        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="会话">{{ target.session_id }}</ElDescriptionsItem>
          <ElDescriptionsItem label="运行状态">{{
            trajectory.data.value.summary.status
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="模型">{{ attempt.model ?? '未记录' }}</ElDescriptionsItem>
        </ElDescriptions>
        <RouterLink
          class="el-button"
          target="_blank"
          :to="{
            path: '/platform/trajectories/detail',
            query: { ...target, tab: 'timeline', node_id: attempt.trace_node_id || undefined }
          }"
          >打开完整轨迹</RouterLink
        >
      </template>
      <p v-else-if="trajectory.data.value">未找到与此记录匹配的模型调用，请核对原始用量来源。</p>
      <ElButton v-else @click="reload">重新核对</ElButton>
    </ElDrawer>
  </template>
</template>
