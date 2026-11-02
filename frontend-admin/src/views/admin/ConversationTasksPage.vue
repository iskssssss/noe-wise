<template>
  <AdminPage title="任务进展" description="按会话查看真实任务状态，并提交修订命令。"
    ><FeedbackNotice
      v-if="error || loadError"
      :title="error || loadError"
      :closable="false"
      type="error"
    />
    <section v-if="pending && !open" class="admin-card" aria-label="任务操作结果核对">
      <p role="status">
        任务操作结果尚未确认，请保留原请求 ID：<code>{{ pending.operationId }}</code
        >。未找到回执不代表操作失败。
      </p>
      <ElButton :loading="busy" @click="checkReceipt">核对原操作</ElButton>
    </section>
    <p v-if="storageError" class="admin-field-help" role="status">{{ storageError }}</p>
    <div class="admin-toolbar admin-card">
      <ElSelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="sessionId"
        :disabled="loading || busy || open || !!pending"
        filterable
        placeholder="选择会话"
        class="task-session-select"
        @change="search"
        ><ElOption
          v-if="sessionId && !sessions.some((session) => session.session_id === sessionId)"
          :value="sessionId"
          :label="sessionId" /><ElOption
          v-for="session in sessions"
          :key="session.session_id"
          :label="session.summary || session.session_id"
          :value="session.session_id" /></ElSelect
      ><ElButton :disabled="loading || busy || open" @click="loadSessions">刷新会话</ElButton
      ><ElInput
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        clearable
        v-model="query"
        placeholder="搜索任务"
        :disabled="!sessionId || loading || busy || open"
        @keyup.enter="search"
      /><ElCheckbox
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="includeClosed"
        :disabled="!sessionId || loading || busy || open"
        @change="search"
        >包含已结束任务</ElCheckbox
      ><ElButton :disabled="!sessionId || loading || busy || open" @click="search">查询</ElButton>

      <FieldTip :id="formTipId + '-group-0'"
        >先选择会话，再按任务关键词筛选；勾选包含已关闭可同时查看历史任务。</FieldTip
      >
    </div>
    <p v-if="refreshRequired" class="admin-field-help" role="status">
      任务操作已完成，但列表尚未更新。请刷新当前任务后继续操作。
      <ElButton :loading="loading" :disabled="busy || open" @click="refreshTasks"
        >刷新当前任务</ElButton
      >
    </p>
    <ElEmpty v-if="!sessionId && !loading" description="选择会话后查看任务进展" />
    <ElTable
      v-else
      table-layout="auto"
      :data="currentSession ? (data?.items ?? []) : []"
      v-loading="loading"
      :empty-text="
        !currentSession && loadError
          ? '任务尚未读取成功，请重新查询'
          : appliedQuery
            ? '当前条件没有匹配任务'
            : '此会话暂无任务'
      "
      ><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="title"
        label="任务"
      /><ElTableColumn
        min-width="280"
        show-overflow-tooltip
        prop="summary"
        label="进展"
      /><ElTableColumn width="128" show-overflow-tooltip label="状态"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn width="96" prop="revision" label="修订" /><ElTableColumn
        width="190"
        show-overflow-tooltip
        prop="updatedAt"
        label="更新时间"
        ><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></ElTableColumn
      ><ElTableColumn width="280" class-name="admin-table-actions" label="操作"
        ><template #default="{ row }"
          ><ElButton
            link
            @click="select(row as ConversationTask)"
            :disabled="busy || loading || refreshRequired || !!pending || !!storageError"
            >设为当前任务</ElButton
          ><ElButton
            link
            @click="correct(row as ConversationTask)"
            :disabled="busy || loading || refreshRequired || !!pending || !!storageError"
            >纠正</ElButton
          ><ElButton
            link
            v-if="['OPEN', 'WAITING_USER'].includes(row.status)"
            @click="update(row as ConversationTask, 'complete')"
            :disabled="busy || loading || refreshRequired || !!pending || !!storageError"
            >完成</ElButton
          ><ElButton
            link
            v-if="['OPEN', 'WAITING_USER'].includes(row.status)"
            @click="update(row as ConversationTask, 'cancel')"
            :disabled="busy || loading || refreshRequired || !!pending || !!storageError"
            >取消</ElButton
          ><ElButton
            link
            v-if="['COMPLETED', 'CANCELLED'].includes(row.status)"
            @click="update(row as ConversationTask, 'reopen')"
            :disabled="busy || loading || refreshRequired || !!pending || !!storageError"
            >重新打开</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    ><ElPagination
      v-if="sessionId"
      layout="prev,next"
      :current-page="offset / 30 + 1"
      :page-count="offset / 30 + 1 + (currentSession && (data?.items.length ?? 0) === 30 ? 1 : 0)"
      :disabled="loading || busy || open || refreshRequired || !sessionId || !currentSession"
      @current-change="changePage"
    /><ElDrawer
      v-model="open"
      :title="'纠正任务进展 · ' + (selected?.title ?? '')"
      :before-close="requestClose"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      size="680px"
      ><section v-if="pending" class="admin-card" aria-label="任务操作结果核对">
        <p role="status">
          任务操作结果尚未确认，原请求 ID：<code>{{ pending.operationId }}</code
          >。未找到回执不代表失败。
        </p>
        <ElButton :loading="busy" @click="checkReceipt">核对原操作</ElButton>
      </section>
      <ElForm
        label-position="top"
        @submit.prevent
        :disabled="busy || !!pending || !!storageError"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElFormItem label="更正说明"
          ><ElInput
            v-described-by="formTipId + '-field-0'"
            :aria-describedby="formTipId + '-field-0'"
            v-model="text"
            type="textarea"
            :rows="8"
          />
          <FieldTip :id="formTipId + '-field-0'"
            >记录对任务进展的事实更正和原因，避免把尚未完成的工作写成已完成。</FieldTip
          >
        </ElFormItem></ElForm
      ><template #footer
        ><ElButton @click="requestClose" :disabled="busy">取消</ElButton
        ><ElButton
          v-permission="['foundation.sessions.update']"
          type="primary"
          :loading="busy"
          :disabled="!!pending || !!storageError"
          @click="save"
          >提交纠正</ElButton
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
import { computed, onMounted, onScopeDispose, ref, useId, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  AdminPage,
  useResource,
  useTask,
  useDrawerClose,
  useUnsavedChanges
} from '@skillforge/shared/admin'
import { api } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { HttpClientError } from '@skillforge/shared/utils/http'
import type { ConversationTask, TaskOperation } from '../../api/conversationTasks'
const route = useRoute(),
  sessionId = ref(String(route.query.sessionId ?? '')),
  sessions = ref<Awaited<ReturnType<typeof api.chat.sessions>>>([]),
  query = ref(''),
  includeClosed = ref(false),
  offset = ref(0),
  appliedQuery = ref(''),
  appliedIncludeClosed = ref(false)
const {
  data,
  loading,
  error: loadError,
  reload,
  invalidate
} = useResource(async () => {
  const requestedSession = sessionId.value
  const items = requestedSession
    ? await api.conversationTasks.list(
        requestedSession,
        appliedQuery.value,
        appliedIncludeClosed.value,
        offset.value
      )
    : []
  return { sessionId: requestedSession, items }
})
const currentSession = computed(() => data.value?.sessionId === sessionId.value)
const refreshRequired = ref(false)
function requireCurrentSession() {
  if (!currentSession.value) throw Error('请先读取当前会话的任务，再执行操作')
  if (refreshRequired.value) throw Error('请先刷新当前任务，再执行操作')
}
onMounted(async () => {
  await loadSessions()
  if (sessionId.value) await reload()
  if (active && pending.value) checkReceipt()
})
const { busy, error, run } = useTask()
const auth = useAuthStore()
const owner = computed(() =>
  auth.user?.tenant_id && auth.user?.sub ? JSON.stringify([auth.user.tenant_id, auth.user.sub]) : ''
)
type PendingTaskOperation = { sessionId: string; taskId: string; operationId: string }
const pending = ref<PendingTaskOperation>()
const storageError = ref('')
const storageKey = (subject: string) => `skillforge.task-operation.${subject}`
let active = true
onScopeDispose(() => {
  active = false
})
watch(
  owner,
  (subject) => {
    pending.value = undefined
    storageError.value = ''
    if (!subject) {
      storageError.value = '无法确认当前机构和成员，暂不能操作任务。'
      return
    }
    try {
      const raw = sessionStorage.getItem(storageKey(subject))
      if (!raw) return
      const value = JSON.parse(raw) as PendingTaskOperation
      if (
        !value ||
        typeof value.sessionId !== 'string' ||
        !value.sessionId ||
        typeof value.taskId !== 'string' ||
        !value.taskId ||
        typeof value.operationId !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.operationId)
      )
        throw Error('invalid task operation')
      pending.value = value
      sessionId.value = value.sessionId
    } catch {
      storageError.value = '无法读取原任务操作记录，请恢复浏览器存储后重新进入页面。'
    }
  },
  { immediate: true, flush: 'sync' }
)
useUnsavedChanges({
  dirty: () => !!pending.value,
  busy: () => busy.value,
  leaveMessage: () =>
    pending.value
      ? `任务操作结果未知，请记录原请求 ID ${pending.value.operationId} 并核对原回执。`
      : undefined
})
function beginOperation(task: ConversationTask): PendingTaskOperation {
  if (!owner.value || pending.value || storageError.value) throw Error('请先核对原任务操作结果')
  const value = {
    sessionId: sessionId.value,
    taskId: task.taskId,
    operationId: crypto.randomUUID()
  }
  try {
    sessionStorage.setItem(storageKey(owner.value), JSON.stringify(value))
  } catch {
    storageError.value = '无法保存原请求标识，未发送任务操作。请恢复浏览器存储。'
    throw Error(storageError.value)
  }
  pending.value = value
  return value
}
function finishOperation(subject: string, operation: PendingTaskOperation): boolean {
  if (!active || owner.value !== subject || pending.value?.operationId !== operation.operationId)
    return false
  try {
    sessionStorage.removeItem(storageKey(subject))
  } catch {
    storageError.value = '无法清除原请求记录，请恢复浏览器存储后重新核对。'
    return false
  }
  pending.value = undefined
  storageError.value = ''
  return true
}
async function performOperation(
  task: ConversationTask,
  write: (operation: PendingTaskOperation) => Promise<unknown>
) {
  const subject = owner.value,
    operation = beginOperation(task)
  try {
    await write(operation)
  } catch (cause) {
    if (
      cause instanceof HttpClientError &&
      cause.code === 'TASK_OPERATION_REJECTED_BEFORE_MUTATION'
    )
      finishOperation(subject, operation)
    throw cause
  }
  return finishOperation(subject, operation)
}
function checkReceipt() {
  const operation = pending.value,
    subject = owner.value
  if (!operation || !subject) return
  void run(async () => {
    const receipt = await api.conversationTasks.receipt(
      operation.sessionId,
      operation.taskId,
      operation.operationId
    )
    if (!active || owner.value !== subject || pending.value?.operationId !== operation.operationId)
      return
    if (receipt.status !== 'SUCCEEDED' || receipt.task?.taskId !== operation.taskId) {
      message.warning('尚未找到原操作的成功回执，结果仍未确认。')
      return
    }
    if (!finishOperation(subject, operation)) return
    open.value = false
    sessionId.value = operation.sessionId
    await refreshAfterWrite('原任务操作已确认')
  })
}
async function loadSessions() {
  const subject = owner.value
  await run(async () => {
    try {
      const value = await api.chat.sessions()
      if (active && owner.value === subject) sessions.value = value
    } catch (cause) {
      if (active && owner.value === subject) throw cause
    }
  })
}
const open = ref(false),
  selected = ref<ConversationTask>(),
  text = ref(''),
  { requestClose } = useDrawerClose({
    open: () => open.value,
    busy: () => busy.value,
    dirty: () => open.value && text.value !== (selected.value?.summary ?? ''),
    close: () => {
      open.value = false
    }
  })
async function select(row: ConversationTask) {
  if (refreshRequired.value || pending.value || storageError.value) return
  await run(async () => {
    requireCurrentSession()
    if (
      !(await performOperation(row, (operation) =>
        api.conversationTasks.select(operation.sessionId, row, operation.operationId)
      ))
    )
      return
    await refreshAfterWrite('已设为当前任务')
  })
}
async function update(row: ConversationTask, operation: TaskOperation) {
  if (refreshRequired.value || pending.value || storageError.value) return
  if (
    operation === 'cancel' &&
    !(await confirm({
      title: '取消任务',
      content: `取消任务“${row.title}”？取消后不再作为进行中的任务。`,
      confirmText: '取消任务',
      danger: true
    }))
  )
    return
  await run(async () => {
    requireCurrentSession()
    if (
      !(await performOperation(row, (command) =>
        api.conversationTasks.update(command.sessionId, row, operation, command.operationId)
      ))
    )
      return
    await refreshAfterWrite('任务状态已更新')
  })
}
function correct(row: ConversationTask) {
  if (
    !currentSession.value ||
    busy.value ||
    refreshRequired.value ||
    pending.value ||
    storageError.value
  )
    return
  selected.value = row
  text.value = row.summary
  open.value = true
}
async function save() {
  if (refreshRequired.value || pending.value || storageError.value) return
  await run(async () => {
    requireCurrentSession()
    if (!text.value.trim()) throw Error('请输入纠正说明')
    const task = selected.value!
    if (
      !(await performOperation(task, (operation) =>
        api.conversationTasks.update(
          operation.sessionId,
          task,
          'correct',
          operation.operationId,
          text.value
        )
      ))
    )
      return
    open.value = false
    await refreshAfterWrite('任务纠正已提交')
  })
}
async function refreshTasks() {
  if (await reload()) refreshRequired.value = false
}
async function refreshAfterWrite(success: string) {
  refreshRequired.value = true
  if (await reload()) {
    refreshRequired.value = false
    message.success(success)
  }
}
async function search() {
  if (loading.value || busy.value || open.value) return
  const requestedSession = sessionId.value,
    previousQuery = appliedQuery.value,
    previousIncludeClosed = appliedIncludeClosed.value,
    previousOffset = currentSession.value ? offset.value : 0
  appliedQuery.value = query.value
  appliedIncludeClosed.value = includeClosed.value
  offset.value = 0
  if ((await reload()) === undefined && sessionId.value === requestedSession) {
    appliedQuery.value = previousQuery
    appliedIncludeClosed.value = previousIncludeClosed
    offset.value = previousOffset
  } else if (currentSession.value) refreshRequired.value = false
}
async function changePage(page: number) {
  if (
    loading.value ||
    busy.value ||
    open.value ||
    refreshRequired.value ||
    !sessionId.value ||
    !currentSession.value
  )
    return
  const previous = offset.value,
    next = (page - 1) * 30
  if (
    next < 0 ||
    next === previous ||
    Math.abs(next - previous) !== 30 ||
    (next > previous && (data.value?.items.length ?? 0) < 30)
  )
    return
  offset.value = next
  if ((await reload()) === undefined) offset.value = previous
}
watch(
  owner,
  () => {
    invalidate()
    data.value = undefined
    sessions.value = []
    sessionId.value = pending.value?.sessionId ?? ''
    selected.value = undefined
    open.value = false
    text.value = ''
    query.value = ''
    appliedQuery.value = ''
    includeClosed.value = false
    appliedIncludeClosed.value = false
    offset.value = 0
    refreshRequired.value = false
    loadError.value = ''
    error.value = ''
  },
  { flush: 'sync' }
)

const formTipId = useId()
</script>

<style scoped>
.task-session-select {
  width: min(360px, 100%);
}
</style>
