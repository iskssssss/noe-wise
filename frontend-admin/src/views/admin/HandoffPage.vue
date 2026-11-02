<template>
  <AdminPage title="人工接管" description="领取工单并向会话发送可追踪的人工回复。"
    ><FeedbackNotice
      v-if="error || loadError"
      :title="error || loadError"
      :closable="false"
      type="error"
    />
    <div class="admin-toolbar admin-card">
      <ElSelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="status"
        clearable
        :disabled="loading || busy"
        style="width: 180px"
        placeholder="全部状态"
        @change="changeStatus"
        ><ElOption value="pending" label="待处理" /><ElOption
          value="assigned"
          label="已接管" /><ElOption value="resolved" label="已解决" /></ElSelect
      ><ElButton @click="reload" :loading="loading" :disabled="busy">刷新</ElButton>

      <FieldTip :id="formTipId + '-group-0'">选择工单处理状态；清除选择后显示全部状态。</FieldTip>
    </div>
    <ElTable
      table-layout="auto"
      :data="data?.items ?? []"
      v-loading="loading"
      :empty-text="
        !data && (loading || loadError)
          ? loading
            ? '正在读取工单…'
            : '工单列表尚未读取，请刷新重试'
          : status === 'pending'
            ? '暂无待处理工单'
            : '当前状态没有工单'
      "
      ><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="user"
        label="用户"
      /><ElTableColumn
        min-width="280"
        show-overflow-tooltip
        prop="reason"
        label="接管原因"
      /><ElTableColumn width="96" prop="priority" label="优先级" /><ElTableColumn
        width="128"
        show-overflow-tooltip
        label="状态"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="assigned_to"
        label="处理人"
      /><ElTableColumn width="210" class-name="admin-table-actions" label="操作"
        ><template #default="{ row }"
          ><ElButton
            v-if="row.status === 'pending' && can('foundation.handoff.update')"
            type="primary"
            link
            :disabled="busy || !!pendingReply"
            @click="claim(row as HandoffTicket)"
            >领取并处理</ElButton
          ><ElButton link @click="view(row as HandoffTicket)" :disabled="busy || !!pendingReply"
            >查看工单</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    ><ElDrawer
      v-model="open"
      :title="'人工接管 · ' + (detail?.ticket.user ?? '工单')"
      size="760px"
      :before-close="requestClose"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy && !pendingReply"
      :show-close="!busy && !pendingReply"
      class="admin-drawer"
      ><FeedbackNotice v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <ElAlert
        v-if="pendingReply"
        :title="`回复发送结果尚未确认，原命令 ${pendingReply.commandId}。请点击“刷新状态”只读核对；未查到不代表失败。${pendingReply.text ? '原回复仍在当前页面。' : '原正文未保存在浏览器，请勿重复发送。'}`"
        type="warning"
        :closable="false"
      />
      <ElAlert
        v-else-if="refreshRequired"
        title="当前工单信息需要更新。请点击“刷新状态”核对处理结果，避免重复提交。"
        type="warning"
        :closable="false"
      />
      <p v-if="replyStorageError" role="status">{{ replyStorageError }}</p>
      <div v-if="pendingReply && !pendingReply.text && can('foundation.handoff.update')">
        <p>请重新输入原回复内容；仅在摘要与原内容一致时才能继续原请求。</p>
        <ElInput v-model="recoveryText" type="textarea" aria-label="重新输入原回复" />
      </div>
      <ElButton
        v-if="pendingReply && can('foundation.handoff.update')"
        :disabled="busy || !!replyStorageError"
        @click="continueReply"
        >继续原回复</ElButton
      >
      <template v-if="detail"
        ><ElDescriptions :column="1" border
          ><ElDescriptionsItem label="用户">{{ detail.ticket.user }}</ElDescriptionsItem
          ><ElDescriptionsItem label="原因">{{ detail.ticket.reason }}</ElDescriptionsItem
          ><ElDescriptionsItem label="状态">{{
            statusLabel(detail.ticket.status)
          }}</ElDescriptionsItem></ElDescriptions
        >
        <div class="admin-toolbar">
          <ElButton
            :disabled="
              !can('foundation.handoff.update') ||
              busy ||
              !!pendingReply ||
              !!replyStorageError ||
              refreshRequired ||
              detail.ticket.status !== 'pending'
            "
            @click="accept"
            >领取工单</ElButton
          ><ElButton :disabled="busy" @click="refreshDetail">刷新状态</ElButton>
        </div>
        <ElButton v-if="detail.has_more" :disabled="busy" @click="older">加载较早消息</ElButton
        ><ElCard v-for="message in detail.messages" :key="message.message_id" style="margin: 12px 0"
          ><div class="admin-muted">
            {{ message.human_author || message.role }} · {{ formatDateTime(message.created_at) }}
          </div>
          <MarkdownContent :content="message.content" /></ElCard
        ><ElAlert
          v-for="reply in detail.replies.filter((r) => r.status !== 'committed')"
          :key="reply.command_id"
          :title="'人工回复：' + reply.status + (reply.error_code ? ' / ' + reply.error_code : '')"
          :type="reply.status === 'rejected' ? 'error' : 'info'"
        /><ElForm
          label-position="top"
          @submit.prevent="send"
          :disabled="busy"
          scroll-to-error
          @keydown.capture="guardFormEnter"
          ><ElFormItem label="人工回复"
            ><ElInput
              v-described-by="'handoff-reply-help'"
              aria-describedby="handoff-reply-help"
              v-model="replyText"
              type="textarea"
              maxlength="8192"
              :rows="4"
              :disabled="
                !can('foundation.handoff.update') ||
                busy ||
                !!pendingReply ||
                !!replyStorageError ||
                refreshRequired ||
                detail.ticket.status === 'resolved'
              "
            />
            <p id="handoff-reply-help" class="admin-field-help">
              最多 8192 个字符。Enter 用于换行，点击“发送回复”提交；提交期间不能关闭工单。
            </p></ElFormItem
          ><ElButton
            native-type="submit"
            type="primary"
            :loading="busy"
            :disabled="
              !can('foundation.handoff.update') ||
              !!pendingReply ||
              !!replyStorageError ||
              refreshRequired ||
              !replyText.trim() ||
              detail.ticket.status === 'resolved'
            "
            >发送回复</ElButton
          ><ElCollapse
            ><ElCollapseItem title="结束处理并标记已解决" name="resolve"
              ><ElFormItem label="解决说明" style="margin-top: 24px"
                ><ElInput
                  v-described-by="'handoff-resolution-help'"
                  aria-describedby="handoff-resolution-help"
                  v-model="resolution"
                  type="textarea"
                />
                <p id="handoff-resolution-help" class="admin-field-help">
                  说明问题如何解决，标记后该工单不再接受人工回复。
                </p></ElFormItem
              ><ElButton
                :disabled="
                  !can('foundation.handoff.update') ||
                  busy ||
                  !!pendingReply ||
                  !!replyStorageError ||
                  refreshRequired ||
                  detail.ticket.status === 'resolved'
                "
                @click="resolve"
                >标记已解决</ElButton
              ></ElCollapseItem
            ></ElCollapse
          ></ElForm
        ></template
      ><template #footer
        ><ElButton @click="requestClose" :disabled="busy || !!pendingReply"
          >关闭</ElButton
        ></template
      ></ElDrawer
    ></AdminPage
  >
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { confirm, message } from '@skillforge/shared/feedback'
import { guardFormEnter } from '@skillforge/shared/admin'
import { statusLabel } from '@skillforge/shared/admin'
import { onMounted, onScopeDispose, ref, useId } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import {
  AdminPage,
  useResource,
  useTask,
  useDrawerClose,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import { HttpClientError } from '@skillforge/shared/utils/http'
import MarkdownContent from '@skillforge/shared/components/MarkdownContent.vue'
import { api, type HandoffTicket, type HandoffDetail, type HandoffReply } from '../../api'
const route = useRoute()
const auth = useAuthStore()
const can = (permission: string) => auth.user?.permissions.includes(permission) === true
const subject = () => JSON.stringify([auth.user?.tenant_id, auth.user?.sub])
const replyKey = (id: string, owner = subject()) =>
  `skillforge.handoff-reply.${JSON.stringify([owner, id])}`
let disposed = false
onScopeDispose(() => {
  disposed = true
})
const status = ref('pending'),
  appliedStatus = ref('pending'),
  {
    data,
    loading,
    error: loadError,
    reload
  } = useResource(() => api.handoff.list(status.value || undefined))
async function changeStatus() {
  if (await reload()) appliedStatus.value = status.value
  else status.value = appliedStatus.value
}
onMounted(() => {
  reload()
  const ticket = typeof route.query?.ticket_id === 'string' ? route.query.ticket_id : ''
  const session = typeof route.query?.session_id === 'string' ? route.query.session_id : ''
  if (ticket && can('foundation.handoff.read')) void viewTicket(ticket, session)
})
const { busy, error, run } = useTask(),
  open = ref(false),
  detail = ref<HandoffDetail>(),
  replyText = ref(''),
  resolution = ref(''),
  refreshRequired = ref(false),
  pendingReply = ref<{
    id: string
    text?: string
    commandId: string
    revision: number
    digest: string
  }>(),
  recoveryText = ref(''),
  replyStorageError = ref('')
useUnsavedChanges({
  dirty: () => open.value && !!(replyText.value || resolution.value),
  busy: () => busy.value || !!pendingReply.value
})
const { requestClose } = useDrawerClose({
  open: () => open.value,
  busy: () => busy.value || !!pendingReply.value,
  dirty: () => open.value && !!(replyText.value || resolution.value),
  close: () => {
    open.value = false
    pendingReply.value = undefined
  }
})
async function view(row: HandoffTicket) {
  return viewTicket(row.id)
}
async function viewTicket(id: string, expectedSession = '') {
  if (!can('foundation.handoff.read') || pendingReply.value) return
  return await run(async () => {
    const value = await api.handoff.detail(id)
    if (value.ticket.id !== id || (expectedSession && value.ticket.session_id !== expectedSession))
      throw Error('工单与原会话不匹配')
    detail.value = value
    replyStorageError.value = ''
    try {
      const raw = sessionStorage.getItem(replyKey(id))
      if (raw) {
        const stored = JSON.parse(raw) as {
          id: string
          commandId: string
          revision: number
          digest: string
        }
        if (
          stored.id !== id ||
          typeof stored.commandId !== 'string' ||
          !Number.isSafeInteger(stored.revision) ||
          !/^[0-9a-f]{64}$/.test(stored.digest)
        )
          throw Error('invalid')
        pendingReply.value = stored
      } else pendingReply.value = undefined
    } catch {
      replyStorageError.value = '无法读取原回复记录，请恢复浏览器存储后核对。'
    }
    replyText.value = ''
    recoveryText.value = ''
    resolution.value = ''
    refreshRequired.value = false
    open.value = true
    return detail.value
  })
}
async function claim(row: HandoffTicket) {
  if (!can('foundation.handoff.update')) return
  const current = await view(row)
  if (current?.ticket.id === row.id && current.ticket.status === 'pending') await accept()
}
async function refreshDetail() {
  await run(async () => {
    await readDetail()
  })
}
async function readDetail() {
  if (!detail.value) return
  const next = await api.handoff.detail(detail.value.ticket.id)
  detail.value = next
  refreshRequired.value = false
  const pending = pendingReply.value
  if (!pending) return
  const owner = subject()
  try {
    await acceptReplyReceipt(
      await api.handoff.replyReceipt(pending.id, pending.commandId),
      pending,
      owner
    )
  } catch (cause) {
    if (cause instanceof HttpClientError && cause.status === 404) {
      cause.claimFeedback()
      message.warning('尚未查到原回复回执，结果仍未确认')
      return
    }
    throw cause
  }
}
async function older() {
  await run(async () => {
    const current = detail.value!
    const older = await api.handoff.detail(current.ticket.id, current.next_before_message_id)
    detail.value = { ...older, messages: [...older.messages, ...current.messages] }
  })
}
async function accept() {
  if (
    !can('foundation.handoff.update') ||
    pendingReply.value ||
    replyStorageError.value ||
    refreshRequired.value
  )
    return
  await run(async () => {
    const ticket = detail.value!.ticket
    await api.handoff.accept(ticket.id, ticket.revision)
    refreshRequired.value = true
    message.success('工单已领取')
    await readDetail()
    await reload()
  })
}
async function acceptReplyReceipt(
  receipt: HandoffReply,
  pending: NonNullable<typeof pendingReply.value>,
  owner: string
) {
  if (
    !receipt ||
    receipt.command_id !== pending.commandId ||
    receipt.ticket_id !== pending.id ||
    !['accepted', 'committed', 'rejected'].includes(receipt.status)
  ) {
    message.warning('原回复回执不匹配，结果仍未确认')
    return
  }
  if (disposed || subject() !== owner) return
  try {
    sessionStorage.removeItem(replyKey(pending.id, owner))
  } catch {
    replyStorageError.value = '无法清除原回复记录，请恢复浏览器存储后重新核对。'
    return
  }
  pendingReply.value = undefined
  replyStorageError.value = ''
  if (receipt.status === 'rejected')
    throw Error(receipt.error_code || '回复被拒绝，请检查内容后重新发送')
  replyText.value = ''
  recoveryText.value = ''
  message.success(
    receipt.status === 'committed' ? '人工回复已写入会话' : '人工回复已受理，尚待写入会话'
  )
}
async function replyDigest(text: string) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}
async function send() {
  if (
    !can('foundation.handoff.update') ||
    pendingReply.value ||
    replyStorageError.value ||
    refreshRequired.value
  )
    return
  await run(async () => {
    const ticket = detail.value!.ticket,
      owner = subject(),
      identity = auth.identityVersion,
      text = replyText.value.trim()
    if (!text) return
    if (text.length > 8192) throw Error('人工回复不能超过 8192 个字符')
    const digest = await replyDigest(text)
    if (
      disposed ||
      subject() !== owner ||
      auth.identityVersion !== identity ||
      !can('foundation.handoff.update')
    )
      return
    const pending = {
      id: ticket.id,
      text,
      digest,
      commandId: crypto.randomUUID(),
      revision: ticket.revision
    }
    try {
      sessionStorage.setItem(
        replyKey(ticket.id, owner),
        JSON.stringify({
          id: pending.id,
          commandId: pending.commandId,
          revision: pending.revision,
          digest: pending.digest
        })
      )
    } catch {
      replyStorageError.value = '无法保存原回复记录，未发送回复。'
      return
    }
    pendingReply.value = pending
    let result: HandoffReply
    try {
      result = await api.handoff.reply(ticket.id, ticket.revision, pending.commandId, text)
    } catch (cause) {
      if (
        !disposed &&
        subject() === owner &&
        pendingReply.value?.commandId === pending.commandId &&
        cause instanceof HttpClientError &&
        cause.code === 'HANDOFF_REPLY_NOT_ACCEPTED'
      ) {
        try {
          sessionStorage.removeItem(replyKey(ticket.id, owner))
          pendingReply.value = undefined
          refreshRequired.value = true
        } catch {
          replyStorageError.value = '无法清除原回复记录，请恢复浏览器存储后重新核对。'
        }
      }
      throw cause
    }
    await acceptReplyReceipt(result, pending, owner)
    if (disposed || subject() !== owner || pendingReply.value) return
    refreshRequired.value = true
    await readDetail()
  })
}
async function continueReply() {
  const pending = pendingReply.value,
    owner = subject(),
    identity = auth.identityVersion
  if (!pending || !can('foundation.handoff.update') || replyStorageError.value) return
  await run(async () => {
    const text = pending.text ?? recoveryText.value.trim()
    if (!text || (await replyDigest(text)) !== pending.digest)
      throw Error('输入的内容与原回复不一致，请核对后重试')
    if (
      !(await confirm({
        title: '继续原回复',
        confirmText: '继续原请求',
        content: '使用原命令、原修订和原正文继续；如果原回复尚未受理，此操作会提交该回复。'
      }))
    )
      return
    if (
      disposed ||
      subject() !== owner ||
      auth.identityVersion !== identity ||
      pendingReply.value?.commandId !== pending.commandId ||
      !can('foundation.handoff.update')
    )
      return
    let receipt: HandoffReply
    try {
      receipt = await api.handoff.reply(pending.id, pending.revision, pending.commandId, text)
    } catch (cause) {
      if (
        !disposed &&
        subject() === owner &&
        auth.identityVersion === identity &&
        pendingReply.value?.commandId === pending.commandId &&
        cause instanceof HttpClientError &&
        cause.code === 'HANDOFF_REPLY_NOT_ACCEPTED'
      ) {
        try {
          sessionStorage.removeItem(replyKey(pending.id, owner))
          pendingReply.value = undefined
          refreshRequired.value = true
        } catch {
          replyStorageError.value = '无法清除原回复记录，请恢复浏览器存储后重新核对。'
        }
      }
      throw cause
    }
    await acceptReplyReceipt(receipt, pending, owner)
    if (disposed || subject() !== owner || pendingReply.value) return
    refreshRequired.value = true
    await readDetail()
  })
}

async function resolve() {
  if (
    !can('foundation.handoff.update') ||
    pendingReply.value ||
    replyStorageError.value ||
    refreshRequired.value
  )
    return
  await run(async () => {
    if (!resolution.value.trim()) throw Error('请填写解决说明')
    const ticket = detail.value!.ticket
    await api.handoff.resolve(ticket.id, ticket.revision, resolution.value)
    resolution.value = ''
    refreshRequired.value = true
    message.success('工单已标记解决')
    await readDetail()
    await reload()
  })
}

const formTipId = useId()
</script>
