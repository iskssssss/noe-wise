<template>
  <AdminPage title="会话记录" description="按智能体和用户查找会话，查看问答与执行记录。"
    ><FeedbackNotice v-if="pageError" :title="pageError" :closable="false" type="error" />
    <div v-if="agentDirectory.error.value || userDirectory.error.value" class="admin-toolbar">
      <ElButton
        v-if="agentDirectory.error.value"
        :loading="agentDirectory.loading.value"
        :disabled="busy || agentDirectory.loading.value"
        @click="agentDirectory.reload"
      >
        重新读取智能体选项
      </ElButton>
      <ElButton
        v-if="userDirectory.error.value"
        :loading="userDirectory.loading.value"
        :disabled="busy || userDirectory.loading.value"
        @click="userDirectory.reload"
      >
        重新读取终端用户选项
      </ElButton>
    </div>
    <ElForm class="toolbar" @submit.prevent="reload" @keydown.capture="guardFormEnter">
      <EntitySelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="agentId"
        :options="
          (agentDirectory.data.value ?? []).map((agent) => ({
            id: agent.id,
            label: agent.name,
            description: agent.id
          }))
        "
        placeholder="全部智能体"
      /><EntitySelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="endUserId"
        :options="userOptions"
        placeholder="全部终端用户"
      /><ElButton type="primary" native-type="submit" :loading="loading">查询</ElButton>

      <FieldTip :id="formTipId + '-group-0'"
        >按智能体和终端用户组合筛选会话；清除选项后不限制对应条件。</FieldTip
      >
    </ElForm>
    <ElButton v-if="loadError" :loading="loading" @click="reload">重新读取会话</ElButton>
    <p v-if="data" class="session-count" role="status">
      {{ data.items.length }} 个会话 · 智能体：{{ appliedAgentName }} · 终端用户：{{ appliedUserName }}
      <span v-if="pendingFilters">；筛选条件已更改，请查询后应用。</span>
      <span v-if="loadError">；本次读取失败，仍显示上次结果。</span>
    </p>
    <ElTable
      table-layout="auto"
      :data="data?.items ?? []"
      v-loading="loading"
      :empty-text="!data ? (loading ? '正在读取会话…' : '会话列表尚未读取，请重新读取') : '没有符合查询条件的会话，可清除智能体或用户筛选后查询'"
      ><ElTableColumn min-width="320" show-overflow-tooltip label="会话 / 摘要">
        <template #default="{ row }"
          ><ElButton
            class="session-summary"
            link
            type="primary"
            :disabled="busy"
            :loading="busy && pendingSession === row.session_id"
            @click="view(row.session_id)"
            >{{ row.summary || '未命名会话' }}</ElButton
          ><small class="session-id">{{ row.session_id }}</small></template
        > </ElTableColumn
      ><ElTableColumn width="96" prop="message_count" label="消息数" /><ElTableColumn
        width="190"
        show-overflow-tooltip
        prop="updated_at"
        label="更新时间"
        ><template #default="{ row }">{{ formatDateTime(row.updated_at) }}</template></ElTableColumn
      ><ElTableColumn width="120" class-name="admin-table-actions" label="操作"
        ><template #default="{ row }"
          ><RouterLink :to="'/admin/chat/sessions/' + row.session_id + '/trace'"
            >追踪</RouterLink
          ></template
        ></ElTableColumn
      ></ElTable
    ><ElDrawer
      v-model="open"
      title="会话详情"
      size="min(960px, 100vw)"
      append-to-body
      direction="rtl"
      class="admin-drawer"
      ><template v-if="detail"
        ><RouterLink
          class="el-button session-trace-link"
          :to="'/admin/chat/sessions/' + encodeURIComponent(detail.session.session_id) + '/trace'"
          >查看执行轨迹</RouterLink
        ><ElDescriptions :column="1" border
          ><ElDescriptionsItem label="会话">{{ detail.session.session_id }}</ElDescriptionsItem
          ><ElDescriptionsItem label="智能体">{{ detail.agent?.name || '—' }}</ElDescriptionsItem
          ><ElDescriptionsItem label="更新时间">{{
            formatDateTime(detail.session.updated_at)
          }}</ElDescriptionsItem></ElDescriptions
        ><ElTabs
          ><ElTabPane label="问答消息"
            ><ElButton v-if="!history" :loading="busy" @click="loadHistory">{{
              busy ? '正在读取消息' : '重新读取消息'
            }}</ElButton>
            <ElEmpty v-else-if="!history.messages.length" description="本会话暂无可见消息" /><ElButton
              v-if="history?.has_more"
              :disabled="busy"
              @click="older"
              >加载较早消息</ElButton
            ><ElCard
              v-for="message in history?.messages ?? []"
              :key="message.message_id"
              style="margin-top: 12px"
              ><div class="muted">
                {{ statusLabel(message.role) }} · {{ formatDateTime(message.created_at) }}
              </div>
              <MarkdownContent :content="message.content" /></ElCard></ElTabPane
          ><ElTabPane label="工作流执行"
            ><ElTable
              table-layout="auto"
              :data="detail.executions"
              empty-text="本会话暂无工作流执行记录"
              ><ElTableColumn
                min-width="200"
                show-overflow-tooltip
                prop="workflow_name"
                label="工作流"
              /><ElTableColumn
                min-width="200"
                show-overflow-tooltip
                prop="execution_id"
                label="执行 ID"
              /><ElTableColumn width="128" show-overflow-tooltip label="状态"
                ><template #default="{ row }">{{ statusLabel(row.state) }}</template></ElTableColumn
              ><ElTableColumn width="190" show-overflow-tooltip prop="updated_at" label="更新"
                ><template #default="{ row }">{{
                  formatDateTime(row.updated_at)
                }}</template></ElTableColumn
              ></ElTable
            ></ElTabPane
          ><ElTabPane label="任务"
            ><ElTable table-layout="auto" :data="detail.tasks" empty-text="本会话暂无任务记录"
              ><ElTableColumn
                min-width="140"
                show-overflow-tooltip
                prop="task_type"
                label="类型"
              /><ElTableColumn
                min-width="200"
                show-overflow-tooltip
                prop="node_id"
                label="节点"
              /><ElTableColumn width="128" show-overflow-tooltip label="状态"
                ><template #default="{ row }">{{
                  statusLabel(row.status)
                }}</template></ElTableColumn
              ><ElTableColumn
                width="190"
                show-overflow-tooltip
                prop="hitl_expires_at"
                label="等待截止"
                ><template #default="{ row }">{{
                  formatDateTime(row.hitl_expires_at)
                }}</template></ElTableColumn
              ></ElTable
            ></ElTabPane
          ><ElTabPane label="回调"
            ><ElTable table-layout="auto" :data="detail.callbacks" empty-text="本会话暂无回调记录"
              ><ElTableColumn
                min-width="200"
                show-overflow-tooltip
                prop="callback_id"
                label="回调 ID"
              /><ElTableColumn
                min-width="160"
                show-overflow-tooltip
                prop="action"
                label="操作"
              /><ElTableColumn width="128" show-overflow-tooltip label="状态"
                ><template #default="{ row }">{{
                  statusLabel(row.status)
                }}</template></ElTableColumn
              ></ElTable
            ></ElTabPane
          ></ElTabs
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

import { computed, onMounted, ref, useId } from 'vue'
import {
  guardFormEnter,
  AdminPage,
  EntitySelect,
  statusLabel,
  useTask,
  useResource
} from '@skillforge/shared/admin'
import MarkdownContent from '@skillforge/shared/components/MarkdownContent.vue'
import { api, type SessionDetail, type ChatHistoryResponse } from '../../api'
const agentId = ref(''),
  endUserId = ref(''),
  {
    data,
    loading,
    error: loadError,
    reload
  } = useResource(async () => {
    const selectedAgent = agentId.value,
      selectedUser = endUserId.value
    const items = await api.chat.sessions({
      agentId: selectedAgent || undefined,
      endUserId: selectedUser || undefined
    })
    return { items, agentId: selectedAgent, endUserId: selectedUser }
  })
const pendingFilters = computed(() =>
  !!data.value && (data.value.agentId !== agentId.value || data.value.endUserId !== endUserId.value)
)
const agentDirectory = useResource(async () => (await api.agents.list()).items)
const userDirectory = useResource(() => api.chat.endUsers())
onMounted(async () => {
  await Promise.all([reload(), agentDirectory.reload(), userDirectory.reload()])
})
const userOptions = computed(() =>
  (userDirectory.data.value ?? []).map((u) => ({ id: u.id, label: u.display_name || '未命名用户' }))
)
const appliedAgentName = computed(() =>
  data.value?.agentId
    ? agentDirectory.data.value?.find((agent) => agent.id === data.value?.agentId)?.name || data.value.agentId
    : '全部'
)
const appliedUserName = computed(() =>
  data.value?.endUserId
    ? userOptions.value.find((user) => user.id === data.value?.endUserId)?.label || data.value.endUserId
    : '全部'
)
const pageError = computed(() =>
  [
    error.value || loadError.value,
    agentDirectory.error.value && `智能体选项读取失败：${agentDirectory.error.value}`,
    userDirectory.error.value && `终端用户选项读取失败：${userDirectory.error.value}`
  ]
    .filter(Boolean)
    .join('；')
)
const { busy, error, run } = useTask(),
  open = ref(false),
  pendingSession = ref(''),
  detail = ref<SessionDetail>(),
  history = ref<ChatHistoryResponse>()
async function view(id: string) {
  if (busy.value) return
  pendingSession.value = id
  await run(async () => {
    const historyRead = api.chat.history(id).then(
      (value) => ({ value } as const),
      (reason: unknown) => ({ reason } as const)
    )
    detail.value = await api.chat.sessionDetail(id)
    history.value = undefined
    open.value = true
    const result = await historyRead
    if ('reason' in result) throw result.reason
    history.value = result.value
  })
  pendingSession.value = ''
}
async function loadHistory() {
  if (!detail.value) return
  const id = detail.value.session.session_id
  await run(async () => {
    history.value = await api.chat.history(id)
  })
}
async function older() {
  await run(async () => {
    const current = history.value!
    const previous = await api.chat.history(current.session_id, current.next_before_message_id)
    history.value = { ...previous, messages: [...previous.messages, ...current.messages] }
  })
}

const formTipId = useId()
</script>

<style scoped>
.session-trace-link {
  margin-bottom: 16px;
}
.session-summary {
  max-width: 100%;
  height: auto;
  min-height: 28px;
  white-space: normal;
  text-align: left;
}
.session-summary :deep(span) {
  overflow-wrap: anywhere;
}

.session-count,
.session-id {
  color: var(--admin-muted);
  font-size: 13px;
}
.session-id {
  display: block;
  margin-top: 4px;
  overflow-wrap: anywhere;
}
.toolbar :deep(.el-select) {
  width: 240px;
  max-width: 100%;
}
:deep(.el-card) {
  box-shadow: none;
  overflow-wrap: anywhere;
}
</style>
