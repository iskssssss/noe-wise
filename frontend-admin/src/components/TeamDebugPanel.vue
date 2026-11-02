<template>
  <section>
    <FeedbackNotice
      v-if="error || loadError"
      :title="error || loadError"
      :closable="false"
      type="error"
    />
    <FeedbackNotice
      v-if="pendingControl && !busy"
      :title="`原控制操作尚待核对（标识：${pendingControl.body.input_id}），请先核对原请求，不要创建替代控制。`"
      type="warning"
    >
      <ElButton :disabled="busy" @click="reconcileControl">核对原请求</ElButton>
      <ElButton
        :disabled="busy || (readOnly && pendingControl.body.operation !== 'cancel')"
        @click="repeatControl"
        >重发原请求</ElButton
      >
    </FeedbackNotice>
    <div class="toolbar">
      <ElSelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="workId"
        :disabled="busy"
        clearable
        placeholder="当前任务计划"
        style="width: 320px"
        @change="reload"
        ><ElOption
          v-for="item in history"
          :key="item.work_id"
          :label="
            (item.title || item.coordinator.name) +
            ' / ' +
            teamWorkPhaseLabel(item) +
            ' / ' +
            item.work_id
          "
          :value="item.work_id" /></ElSelect
      ><ElButton :loading="loading" @click="reload">刷新计划</ElButton>

      <FieldTip :id="formTipId + '-group-0'"
        >选择要查看的任务计划记录；清除选择后查看当前任务计划。</FieldTip
      >
    </div>
    <template v-if="data"
      ><ElDescriptions :column="3" border
        ><ElDescriptionsItem label="工作状态">{{ data.status }}</ElDescriptionsItem
        ><ElDescriptionsItem label="主助手运行">{{ data.run_status }}</ElDescriptionsItem
        ><ElDescriptionsItem label="计划修订">{{
          data.plan_revision
        }}</ElDescriptionsItem></ElDescriptions
      >
      <h3>{{ data.title || '任务计划' }}</h3>
      <p>
        已完成 {{ data.tasks.filter((task) => task.status === 'completed').length }} /
        {{ data.tasks.length }} 项
      </p>
      <p v-if="data.notice">{{ data.notice }}</p>
      <div class="toolbar">
        <ElButton
          v-if="data.approval_required && (data.approved_revision ?? 0) < data.plan_revision"
          type="primary"
          :disabled="busy || readOnly || !!pendingControl"
          @click="control('approve_plan')"
          >批准当前计划</ElButton
        ><ElButton :disabled="busy || !!pendingControl" @click="control('cancel')"
          >取消计划</ElButton
        >
      </div>
      <ElTable table-layout="auto" :data="data.tasks" empty-text="暂无记录"
        ><ElTableColumn
          min-width="200"
          show-overflow-tooltip
          prop="title"
          label="任务"
        /><ElTableColumn min-width="200" show-overflow-tooltip label="负责人"
          ><template #default="{ row }">{{
            row.executor_type === 'PRIMARY' ? '主 Agent' : row.agent_name
          }}</template></ElTableColumn
        ><ElTableColumn label="依赖" min-width="180"
          ><template #default="{ row }">{{
            row.depends_on
              .map((id: string) => data?.tasks.find((task) => task.task_id === id)?.title || id)
              .join('、') || '无'
          }}</template></ElTableColumn
        ><ElTableColumn width="128" label="状态"
          ><template #default="{ row }">{{
            teamTaskStatusLabel(row.status)
          }}</template></ElTableColumn
        ><ElTableColumn
          min-width="280"
          show-overflow-tooltip
          prop="wait_reason"
          label="等待原因"
        /><ElTableColumn width="160" class-name="admin-table-actions" label="操作"
          ><template #default="{ row }"
            ><ElButton link :disabled="busy" @click="details(row as TeamTask)">详情</ElButton
            ><ElButton
              link
              :disabled="busy || readOnly"
              @click="
                () => {
                  selected = row as TeamTask
                  controlOpen = true
                }
              "
              >调整</ElButton
            ><ElButton
              link
              :disabled="busy || !!pendingControl"
              @click="control('cancel', row as TeamTask)"
              >取消</ElButton
            ></template
          ></ElTableColumn
        ></ElTable
      ><ElCard v-for="result in data.results" :key="result.result_id" style="margin-top: 12px"
        ><h4>{{ result.title }}</h4>
        <MarkdownContent :content="result.summary ?? ''" />
        <p v-for="limitation in result.limitations" :key="limitation">{{ limitation }}</p></ElCard
      ><ElAlert
        v-for="item in data.control_inputs ?? []"
        :key="item.input_id"
        :title="item.state + '：' + item.detail"
        :type="item.state === 'REJECTED' ? 'error' : 'info'" /></template
    ><ElEmpty v-else-if="!loading" description="当前会话尚无任务计划" />
    <ElDrawer
      v-model="controlOpen"
      :title="'调整任务：' + (selected?.title ?? '')"
      :before-close="requestClose"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      size="680px"
      ><FeedbackNotice
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        role="alert"
      /><ElForm
        label-position="top"
        @submit.prevent
        :disabled="busy || readOnly || !!pendingControl"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElFormItem label="操作"
          ><ElSelect
            v-described-by="formTipId + '-field-0'"
            :aria-describedby="formTipId + '-field-0'"
            v-model="operation"
            ><ElOption value="supplement" label="补充信息" /><ElOption
              value="adjust"
              label="调整方向"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-0'"
            >选择针对该步骤的调整操作，提交前核对目标任务。</FieldTip
          > </ElFormItem
        ><ElFormItem label="说明"
          ><ElInput
            v-described-by="formTipId + '-field-1'"
            :aria-describedby="formTipId + '-field-1'"
            v-model="text"
            type="textarea"
            :rows="6"
          />
          <FieldTip :id="formTipId + '-field-1'"
            >填写调整原因或补充要求，将随所选操作提交。</FieldTip
          >
        </ElFormItem></ElForm
      ><template #footer
        ><ElButton @click="requestClose" :disabled="busy">取消</ElButton
        ><ElButton
          type="primary"
          :loading="busy"
          :disabled="readOnly || !!pendingControl"
          @click="control(operation, selected)"
          >提交</ElButton
        ></template
      ></ElDrawer
    >
    <ElDrawer
      v-model="detailOpen"
      :before-close="closeDetails"
      title="步骤详情"
      size="760px"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      ><FeedbackNotice v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <p v-if="selected">{{ selected.goal }}</p>
      <p v-if="selected?.expected_output">预期输出：{{ selected.expected_output }}</p>
      <p v-if="selected?.wait_reason">{{ selected.wait_reason }}</p>
      <template v-if="member"
        ><p>{{ member.task.goal }}</p>
        <p v-if="member.task.expected_output">预期输出：{{ member.task.expected_output }}</p>
        <p v-for="constraint in member.task.constraints" :key="constraint">{{ constraint }}</p>
        <p v-if="member.task.wait_reason">{{ member.task.wait_reason }}</p>
        <ElTag>{{ member.run.status }}</ElTag
        ><ElTable table-layout="auto" :data="member.processes" empty-text="暂无记录"
          ><ElTableColumn
            min-width="200"
            show-overflow-tooltip
            prop="presentation.title"
            label="步骤" /><ElTableColumn
            width="128"
            show-overflow-tooltip
            prop="status"
            label="状态" /><ElTableColumn
            min-width="280"
            show-overflow-tooltip
            prop="presentation.summary"
            label="说明" /></ElTable
        ><ElButton v-if="member.has_more" :disabled="busy" @click="more">加载更多</ElButton
        ><ElCard v-if="member.pending"
          ><p>{{ member.pending.prompt }}</p>
          <ElInput
            v-described-by="formTipId + '-group-1'"
            :aria-describedby="formTipId + '-group-1'"
            v-model="response"
            :disabled="busy || readOnly || !!pendingControl"
            type="textarea"
          />
          <FieldTip :id="formTipId + '-group-1'"
            >填写该步骤等待的回复，核对待办内容后再提交。</FieldTip
          >

          <div class="toolbar">
            <ElButton
              v-if="member.pending.kind === 'operation'"
              :disabled="busy || readOnly || !!pendingControl"
              @click="reply('approve_operation')"
              >批准操作</ElButton
            ><ElButton
              v-if="member.pending.kind === 'operation'"
              :disabled="busy || readOnly || !!pendingControl"
              @click="reply('reject_operation')"
              >拒绝操作</ElButton
            ><ElButton
              v-else
              :disabled="busy || readOnly || !!pendingControl"
              @click="
                reply(member.pending.kind === 'workflow' ? 'workflow_response' : 'reply_input')
              "
              >提交回答</ElButton
            >
          </div></ElCard
        ></template
      ></ElDrawer
    >
  </section>
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { ref, shallowRef, watch, useId, onBeforeUnmount } from 'vue'
import { useTask, useResource, useDrawerClose, useUnsavedChanges } from '@skillforge/shared/admin'
import MarkdownContent from '@skillforge/shared/components/MarkdownContent.vue'
import { confirm, message } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { api } from '../api'
import {
  teamWorkPhaseLabel,
  teamTaskStatusLabel,
  mergeTeamWorkSnapshot
} from '@skillforge/shared/components/teamWork'
import type {
  TeamWorkSnapshot,
  TeamTask,
  TeamMemberDetails
} from '@skillforge/shared/components/teamWork'
const props = defineProps<{
    sessionId: string
    readOnly?: boolean
    update?: { workId: string; sequence: number }
  }>(),
  workId = ref(''),
  history = ref<TeamWorkSnapshot[]>([])
const {
  data,
  loading,
  error: loadError,
  reload,
  invalidate
} = useResource<TeamWorkSnapshot | null>(async (signal): Promise<TeamWorkSnapshot | null> => {
  if (!props.sessionId) return null
  const value = await api.chat.teamWork(props.sessionId, signal, workId.value || undefined)
  return value ? mergeTeamWorkSnapshot(data.value, value) : value
})
const { busy, error, run } = useTask()
let sessionAbort = new AbortController(),
  sessionEpoch = 0
onBeforeUnmount(() => sessionAbort.abort())
watch(
  () => props.sessionId,
  async () => {
    sessionAbort.abort()
    sessionAbort = new AbortController()
    const epoch = ++sessionEpoch
    invalidate()
    data.value = null
    workId.value = ''
    history.value = []
    if (props.sessionId) {
      try {
        const items = (await api.chat.teamWorkHistory(props.sessionId, sessionAbort.signal)).items
        if (epoch !== sessionEpoch) return
        history.value = items
        await reload()
      } catch (cause) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        if (epoch === sessionEpoch && !sessionAbort.signal.aborted)
          loadError.value = cause instanceof Error ? cause.message : '任务计划历史暂时无法读取。'
      }
    }
  },
  { immediate: true }
)
let refreshing = false,
  dirty = false
watch(
  () => props.update?.sequence,
  async () => {
    const update = props.update
    if (!update) return
    dirty = true
    if (refreshing) return
    refreshing = true
    const epoch = sessionEpoch
    try {
      while (dirty && epoch === sessionEpoch) {
        dirty = false
        const items = (await api.chat.teamWorkHistory(props.sessionId, sessionAbort.signal)).items
        if (epoch !== sessionEpoch) return
        history.value = items
        if (!workId.value || props.update?.workId === workId.value) await reload()
      }
    } catch (cause) {
      if (cause instanceof HttpClientError) cause.claimFeedback()
      if (epoch === sessionEpoch && !sessionAbort.signal.aborted)
        loadError.value = cause instanceof Error ? cause.message : '任务计划历史暂时无法读取。'
    } finally {
      refreshing = false
    }
  }
)
const controlOpen = ref(false),
  detailOpen = ref(false),
  selected = ref<TeamTask>(),
  member = ref<TeamMemberDetails>(),
  operation = ref<'supplement' | 'adjust'>('supplement'),
  text = ref(''),
  response = ref('')
watch(
  () => props.sessionId,
  () => {
    selected.value = undefined
    member.value = undefined
    detailOpen.value = false
    controlOpen.value = false
    text.value = ''
    response.value = ''
  }
)
watch(workId, () => {
  selected.value = undefined
  member.value = undefined
  detailOpen.value = false
})
type ControlCommand = {
  sessionId: string
  body: Parameters<typeof api.chat.controlTeamWork>[1]
}
// 单个在途/未知命令拥有原身份与正文，编辑草稿或计划刷新不能生成替代标识。
const pendingControl = shallowRef<ControlCommand>()
useUnsavedChanges({
  dirty: () => !!pendingControl.value,
  busy: () => busy.value,
  leaveMessage: () =>
    pendingControl.value
      ? `原控制操作尚待核对，请记录标识 ${pendingControl.value.body.input_id}。离开后内存中的原命令将丢失，请在原会话核对结果。`
      : undefined
})
async function sendControl(command: ControlCommand) {
  const signal = sessionAbort.signal
  pendingControl.value = command
  const result = await api.chat.controlTeamWork(command.sessionId, command.body, signal)
  if (signal.aborted || pendingControl.value !== command) return false
  if (result.session_id !== command.sessionId || result.work_id !== command.body.work_id)
    throw Error('原控制回执与请求不匹配，请核对原请求')
  pendingControl.value = undefined
  return props.sessionId === command.sessionId
}
async function reconcileControl() {
  const command = pendingControl.value
  if (!command) return
  await run(async () => {
    const signal = sessionAbort.signal
    const result = await api.chat.teamWork(command.sessionId, signal, command.body.work_id)
    if (signal.aborted || pendingControl.value !== command) return
    const receipt =
      result?.session_id === command.sessionId && result.work_id === command.body.work_id
        ? result.control_inputs?.find((item) => item.input_id === command.body.input_id)
        : undefined
    if (!receipt || !['ACCEPTED', 'APPLIED', 'REJECTED'].includes(receipt.state)) {
      message.warning('尚未查到匹配的原控制回执，结果仍待核对；未找到不表示原请求未执行。')
      return
    }
    pendingControl.value = undefined
    if (props.sessionId === command.sessionId && data.value?.work_id === command.body.work_id) {
      invalidate()
      data.value = mergeTeamWorkSnapshot(data.value, result!)
    }
    if (receipt.state === 'REJECTED') message.warning(`原控制已拒绝：${receipt.detail}`)
    else message.success(receipt.state === 'APPLIED' ? '原控制已执行' : '原控制已受理，尚待执行')
  })
}
async function repeatControl() {
  const command = pendingControl.value
  if (!command || busy.value || (props.readOnly && command.body.operation !== 'cancel')) return
  if (
    !(await confirm({
      title: '重发原控制请求',
      content:
        '仅重发原标识与原内容，不采用当前编辑内容；服务端按原标识核对已受理的操作。确认继续？',
      confirmText: '重发原请求',
      cancelText: '继续核对'
    }))
  )
    return
  await run(async () => {
    if (pendingControl.value !== command || (props.readOnly && command.body.operation !== 'cancel'))
      return
    if (await sendControl(command)) {
      message.success('原控制请求已确认')
      await reload()
    }
  })
}
const { requestClose } = useDrawerClose({
  open: () => controlOpen.value,
  busy: () => busy.value,
  dirty: () => !!text.value,
  close: () => {
    controlOpen.value = false
    text.value = ''
  }
})
const { requestClose: closeDetails } = useDrawerClose({
  open: () => detailOpen.value,
  busy: () => busy.value,
  dirty: () => !!response.value,
  close: () => {
    detailOpen.value = false
    response.value = ''
  }
})
async function control(op: 'supplement' | 'adjust' | 'cancel' | 'approve_plan', task?: TeamTask) {
  if ((props.readOnly && op !== 'cancel') || pendingControl.value) return
  if (
    op === 'cancel' &&
    !(await confirm({
      title: '取消计划任务',
      content: `取消${task?.title ? '“' + task.title + '”' : '当前任务计划'}？任务将停止继续执行。`,
      confirmText: '取消任务',
      danger: true
    }))
  )
    return
  await run(async () => {
    if (!data.value || pendingControl.value) return
    if ((op === 'supplement' || op === 'adjust') && !text.value.trim())
      throw Error('请输入调整说明')
    if (
      !(await sendControl({
        sessionId: props.sessionId,
        body: {
          input_id: crypto.randomUUID(),
          work_id: data.value.work_id,
          task_id: task?.task_id,
          operation: op,
          content: text.value || undefined,
          plan_revision: op === 'approve_plan' ? data.value.plan_revision : undefined
        }
      }))
    )
      return
    message.success('任务调整已提交')
    text.value = ''
    controlOpen.value = false
    await reload()
  })
}
async function details(task: TeamTask) {
  await run(async () => {
    const epoch = sessionEpoch
    selected.value = task
    member.value = undefined
    detailOpen.value = true
    if (task.details_access !== 'available') return
    const value = await api.chat.memberTaskDetails(
      {
        session_id: props.sessionId,
        work_id: data.value!.work_id,
        task_kind: task.task_kind,
        task_id: task.task_id
      },
      undefined,
      sessionAbort.signal
    )
    if (epoch !== sessionEpoch) return
    member.value = value
    response.value = ''
    detailOpen.value = true
  })
}
async function more() {
  await run(async () => {
    const epoch = sessionEpoch
    const task = selected.value!,
      current = member.value!
    const next = await api.chat.memberTaskDetails(
      {
        session_id: props.sessionId,
        work_id: data.value!.work_id,
        task_kind: task.task_kind,
        task_id: task.task_id
      },
      current.next_cursor ?? undefined,
      sessionAbort.signal
    )
    if (epoch !== sessionEpoch) return
    member.value = { ...next, processes: [...current.processes, ...next.processes] }
  })
}
async function reply(
  op: 'approve_operation' | 'reject_operation' | 'reply_input' | 'workflow_response'
) {
  if (props.readOnly || pendingControl.value) return
  await run(async () => {
    const pending = member.value?.pending
    if (!pending || !selected.value || !data.value || pendingControl.value) return
    if (
      !(await sendControl({
        sessionId: props.sessionId,
        body: {
          input_id: crypto.randomUUID(),
          work_id: data.value.work_id,
          task_id: selected.value.task_id,
          operation: op,
          pending_id: pending.pending_id,
          parameters_version: pending.parameters_version,
          content: response.value
        }
      }))
    )
      return
    message.success('回答已提交')
    response.value = ''
    member.value = await api.chat.memberTaskDetails(
      {
        session_id: props.sessionId,
        work_id: data.value.work_id,
        task_kind: selected.value.task_kind,
        task_id: selected.value.task_id
      },
      undefined,
      sessionAbort.signal
    )
    await reload()
  })
}

const formTipId = useId()
</script>
