<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { useTask, useUnsavedChanges } from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { useAuthStore } from '../stores/auth'
import { api, type HandoffCreateReceipt } from '../api'
const props = defineProps<{ sessionId: string; disabled: boolean }>()
const auth = useAuthStore()
const can = (permission: string) => auth.user?.permissions.includes(permission) === true
const owner = computed(() =>
  auth.user?.tenant_id && auth.user?.sub && props.sessionId
    ? JSON.stringify([auth.user.tenant_id, auth.user.sub, props.sessionId])
    : ''
)
const key = (value: string) => `skillforge.handoff-create.${value}`
type Operation = { commandId: string; sessionId: string; receipt?: HandoffCreateReceipt }
const operation = ref<Operation>()
const storageError = ref('')
const { busy, error, run } = useTask()
let disposed = false
onScopeDispose(() => {
  disposed = true
})
watch(
  owner,
  (subject) => {
    operation.value = undefined
    storageError.value = ''
    if (!subject) return
    try {
      const raw = sessionStorage.getItem(key(subject))
      if (!raw) return
      const value = JSON.parse(raw) as Operation
      if (
        !value ||
        value.sessionId !== props.sessionId ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.commandId) ||
        (value.receipt &&
          (value.receipt.command_id !== value.commandId ||
            value.receipt.session_id !== value.sessionId ||
            !value.receipt.id))
      )
        throw Error('invalid')
      operation.value = value
    } catch {
      storageError.value = '无法读取原工单创建记录，请恢复浏览器存储后重新进入会话。'
    }
  },
  { immediate: true, flush: 'sync' }
)
useUnsavedChanges({
  dirty: () => !!operation.value && !operation.value.receipt,
  busy: () => busy.value,
  leaveMessage: () =>
    operation.value && !operation.value.receipt
      ? `工单创建结果未知，请记录原请求 ID：${operation.value.commandId}。`
      : undefined
})
function accept(value: HandoffCreateReceipt, current: Operation, subject: string) {
  if (
    !value ||
    value.command_id !== current.commandId ||
    value.session_id !== current.sessionId ||
    typeof value.id !== 'string' ||
    !value.id ||
    value.status !== 'pending'
  ) {
    if (!disposed && owner.value === subject)
      message.warning('未收到匹配的原创建回执，结果仍未确认')
    return
  }
  if (disposed || owner.value !== subject) return
  const next = { ...current, receipt: value }
  try {
    sessionStorage.setItem(key(subject), JSON.stringify(next))
  } catch {
    storageError.value = '已收到工单回执，但无法保存记录；请恢复浏览器存储后核对原请求。'
    return
  }
  operation.value = next
  storageError.value = ''
  message.success('人工接管工单已创建')
}
async function create() {
  if (
    !owner.value ||
    operation.value ||
    storageError.value ||
    props.disabled ||
    !can('foundation.handoff.create') ||
    !can('foundation.handoff.read')
  )
    return
  const subject = owner.value,
    identity = auth.identityVersion,
    session = props.sessionId
  await run(async () => {
    if (
      !(await confirm({
        title: '创建人工接管工单',
        confirmText: '创建工单',
        content: '为当前会话创建人工接管工单？创建后需坐席手动领取和处理。'
      }))
    )
      return
    if (
      disposed ||
      subject !== owner.value ||
      identity !== auth.identityVersion ||
      props.disabled ||
      !can('foundation.handoff.create') ||
      !can('foundation.handoff.read')
    )
      return
    const current = { commandId: crypto.randomUUID(), sessionId: session }
    try {
      sessionStorage.setItem(key(subject), JSON.stringify(current))
    } catch {
      storageError.value = '无法保存原请求记录，未发送工单创建请求。'
      return
    }
    operation.value = current
    const value = await api.handoff.create(session, '机构调试请求人工接管', current.commandId)
    accept(value, current, subject)
  })
}
async function continueOriginal() {
  const current = operation.value,
    subject = owner.value,
    identity = auth.identityVersion
  if (
    !current ||
    current.receipt ||
    props.disabled ||
    storageError.value ||
    !can('foundation.handoff.create') ||
    !can('foundation.handoff.read')
  )
    return
  await run(async () => {
    if (
      !(await confirm({
        title: '继续原创建',
        confirmText: '继续原请求',
        content: '使用原请求 ID、原会话和原原因继续；如果原请求尚未创建工单，此操作会创建工单。'
      }))
    )
      return
    if (
      disposed ||
      owner.value !== subject ||
      auth.identityVersion !== identity ||
      operation.value?.commandId !== current.commandId ||
      props.disabled ||
      !can('foundation.handoff.create') ||
      !can('foundation.handoff.read')
    )
      return
    accept(
      await api.handoff.create(current.sessionId, '机构调试请求人工接管', current.commandId),
      current,
      subject
    )
  })
}
async function createAnother() {
  if (!operation.value?.receipt || busy.value || storageError.value) return
  const subject = owner.value,
    commandId = operation.value.commandId
  if (
    !(await confirm({
      title: '创建另一工单',
      confirmText: '继续',
      content: '当前工单已创建。继续后可为此会话创建另一张工单。'
    }))
  )
    return
  if (
    disposed ||
    owner.value !== subject ||
    operation.value?.commandId !== commandId ||
    !operation.value.receipt
  )
    return
  try {
    sessionStorage.removeItem(key(subject))
    operation.value = undefined
  } catch {
    storageError.value = '无法清除已确认的本地记录，请恢复浏览器存储后重试。'
  }
}
async function check() {
  if (!can('foundation.handoff.read') || !operation.value || operation.value.receipt) return
  const current = operation.value,
    subject = owner.value
  await run(async () => {
    try {
      accept(await api.handoff.createReceipt(current.commandId), current, subject)
    } catch (cause) {
      if (cause instanceof HttpClientError && cause.status === 404) {
        cause.claimFeedback()
        message.warning('尚未查到原创建回执，结果仍未确认')
        return
      }
      throw cause
    }
  })
}
</script>
<template>
  <section aria-label="人工接管工单">
    <FeedbackNotice v-if="error" :title="error" :closable="false" type="error" />
    <ElButton
      v-if="can('foundation.handoff.create')"
      :disabled="
        disabled || busy || !!operation || !!storageError || !can('foundation.handoff.read')
      "
      @click="create"
      >创建人工接管工单</ElButton
    >
    <p
      v-if="can('foundation.handoff.create') && !can('foundation.handoff.read')"
      class="admin-field-help"
    >
      创建并核对工单需要人工接管读取权限。
    </p>
    <p v-if="storageError" role="status">{{ storageError }}</p>
    <div v-if="operation && !operation.receipt" role="status">
      工单创建结果未知，原请求 ID：<code>{{ operation.commandId }}</code
      >；未查到回执不代表失败。
      <ElButton v-if="can('foundation.handoff.read')" :loading="busy" @click="check"
        >核对原工单</ElButton
      >
      <ElButton
        v-if="can('foundation.handoff.create') && can('foundation.handoff.read')"
        :loading="busy"
        :disabled="disabled"
        @click="continueOriginal"
        >继续原创建</ElButton
      >
    </div>
    <div v-if="operation?.receipt" role="status">
      工单 {{ operation.receipt.id }} 已创建，请打开查看当前状态。
      <RouterLink
        v-if="can('foundation.handoff.read')"
        :to="{
          path: '/admin/handoff',
          query: { ticket_id: operation.receipt.id, session_id: operation.sessionId }
        }"
        >打开工单</RouterLink
      >
      <ElButton v-if="can('foundation.handoff.create')" link :disabled="busy" @click="createAnother"
        >创建另一工单</ElButton
      >
    </div>
  </section>
</template>
