<template>
  <AdminPage title="记忆管理" description="查询、审核、纠正或撤回机构内的记忆事实。"
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
    <div class="admin-toolbar admin-filters">
      <ElSelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="scope"
        :disabled="reviews"
        clearable
        placeholder="全部范围"
        style="width: 160px"
        ><ElOption label="会话" value="SESSION" /><ElOption
          label="用户与智能体"
          value="USER_AGENT" /><ElOption label="用户" value="USER" /></ElSelect
      ><EntitySelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="agentId"
        :disabled="reviews"
        :options="agentOptions"
        placeholder="全部智能体"
      /><EntitySelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="endUserId"
        :disabled="reviews"
        :options="userOptions"
        placeholder="全部终端用户"
      /><ElSwitch
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="reviews"
        active-text="待审核"
        @change="refresh"
      /><ElButton :loading="loading" @click="refresh">查询</ElButton>

      <FieldTip :id="formTipId + '-group-0'">{{
        reviews
          ? '待审核显示机构审核队列；关闭后可按范围、智能体和终端用户查询记忆。'
          : '按范围、智能体和终端用户组合查询，未选择的条件不限制结果。可加载更多结果。'
      }}</FieldTip>
    </div>
    <p v-if="refreshRequired" class="admin-field-help" role="status">
      {{
        refreshCause === 'write' ? '操作已完成，但当前列表尚未更新。' : '当前列表尚未更新。'
      }}请点击“查询”读取最新状态后继续修改。
    </p>
    <p v-else-if="loadError && data" class="admin-field-help" role="status">
      当前显示上次成功读取的结果，本次查询条件尚未确认生效。
    </p>
    <p v-if="data" class="admin-field-help" role="status">
      当前结果：{{
        data.reviews
          ? '机构待审核队列'
          : `范围 ${data.scope ? statusLabel(data.scope) : '全部'} · 智能体 ${data.agentId || '全部'} · 终端用户 ${data.endUserId || '全部'}`
      }}。
      <span v-if="pendingFilters">筛选条件已更改，请点击“查询”应用。</span>
    </p>
    <div class="admin-toolbar">
      <ElInput
        v-model="query"
        clearable
        aria-label="搜索记忆"
        placeholder="搜索主体、关系或内容，点击查询应用"
      /><span v-if="data" class="admin-muted" role="status"
        >当前显示 {{ data.items.length }} 条{{ data.reviews ? '待审核记忆' : '记忆'
        }}{{ data.nextCursor ? '，仍有更多结果' : '' }}</span
      ><span v-else class="admin-muted" role="status">{{
        loading ? '正在读取记忆…' : '记忆列表尚未读取'
      }}</span>
    </div>
    <ElTable
      table-layout="auto"
      :data="data?.items ?? []"
      v-loading="loading"
      :empty-text="
        !data
          ? loading
            ? '正在读取记忆…'
            : '记忆列表尚未读取，请查询后查看'
          : data.nextCursor
            ? '当前批次没有匹配记忆，可继续加载更多'
            : data.query
              ? '没有匹配记忆，请调整关键词'
              : data?.reviews
                ? '当前没有待审核记忆'
                : '没有找到记忆，可调整查询范围'
      "
      ><ElTableColumn min-width="140" show-overflow-tooltip label="范围"
        ><template #default="{ row }">{{ statusLabel(row.scope) }}</template></ElTableColumn
      ><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="subject"
        label="主体"
      /><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="predicate"
        label="关系"
      /><ElTableColumn min-width="280" show-overflow-tooltip label="内容"
        ><template #default="{ row }">{{
          displayContent(row as MemoryFact)
        }}</template></ElTableColumn
      ><ElTableColumn width="128" show-overflow-tooltip label="状态"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn width="96" prop="confidence" label="置信度" /><ElTableColumn
        width="220"
        class-name="admin-table-actions"
        label="操作"
        ><template #default="{ row }"
          ><ElButton
            link
            @click="edit(row as MemoryFact)"
            :disabled="busy || refreshRequired || row.status !== 'ACTIVE'"
            >纠正</ElButton
          ><ElButton
            v-permission="['foundation.memory.update']"
            v-if="row.status === 'PENDING_CONFIRM'"
            link
            @click="review(row as MemoryFact, true)"
            :disabled="busy || refreshRequired"
            >批准</ElButton
          ><ElButton
            v-permission="['foundation.memory.update']"
            v-if="row.status === 'PENDING_CONFIRM'"
            link
            type="danger"
            @click="review(row as MemoryFact, false)"
            :disabled="busy || refreshRequired"
            >拒绝</ElButton
          ><ElButton
            v-permission="['foundation.memory.update']"
            v-if="row.status !== 'REJECTED' && row.status !== 'ARCHIVED'"
            link
            :disabled="busy || refreshRequired"
            @click="changeState(row as MemoryFact)"
            >{{ ['FORGOTTEN', 'DECAYED'].includes(row.status) ? '恢复' : '遗忘' }}</ElButton
          ><ElText
            v-if="replacementNotice(row as MemoryFact)"
            class="memory-review-notice"
            type="warning"
            size="small"
            >{{ replacementNotice(row as MemoryFact) }}</ElText
          ><ElText
            v-if="requiresSharingConfirmation(row as MemoryFact)"
            class="memory-review-notice"
            type="warning"
            size="small"
            >请先审核跨助手共享</ElText
          ><ElText
            v-else-if="row.status !== 'ACTIVE'"
            class="memory-review-notice"
            type="warning"
            size="small"
            >{{
              row.status === 'FORGOTTEN' || row.status === 'DECAYED'
                ? '恢复后可纠正'
                : row.status === 'PENDING_CONFIRM'
                  ? '批准后可纠正'
                  : '当前状态不可纠正'
            }}</ElText
          ></template
        ></ElTableColumn
      ></ElTable
    >
    <div v-if="data?.nextCursor" class="admin-toolbar">
      <ElButton
        :loading="loadingMore"
        :disabled="loading || busy || pendingFilters || refreshRequired"
        @click="loadMore"
        >加载更多</ElButton
      >
    </div>
    <ElDrawer
      v-model="open"
      title="纠正记忆"
      :before-close="requestClose"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      size="680px"
      ><div class="admin-field-help">
        <ElButton :loading="latestMemory.loading.value" :disabled="busy" @click="checkLatest"
          >核对最新状态</ElButton
        >
        <p v-if="latestMemory.data.value" role="status">
          最新状态：{{ statusLabel(latestMemory.data.value.status) }}，版本
          {{ latestMemory.data.value.revision }}。
          <template v-if="latestMemory.data.value.revision !== selected?.revision"
            >事实已变化，当前草稿仍在；如需保留请先复制，关闭抽屉会丢弃草稿。请查看最新事实后再决定如何处理。</template
          >
        </p>
        <p v-if="latestMemory.data.value">
          最新关系：{{ latestMemory.data.value.predicate }}；最新内容：{{
            displayContent(latestMemory.data.value)
          }}
        </p>
        <p v-if="latestMemory.error.value" role="alert">{{ latestMemory.error.value }}</p>
      </div>
      <FeedbackNotice
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        role="alert"
      /><ElForm
        ref="formRef"
        :model="form"
        label-position="top"
        @submit.prevent="save"
        :disabled="busy"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElFormItem
          label="主体"
          prop="subject"
          :rules="[{ required: true, whitespace: true, message: '请填写主体', trigger: 'blur' }]"
          ><ElInput
            v-described-by="formTipId + '-field-0'"
            :aria-describedby="formTipId + '-field-0'"
            v-model="form.subject"
          />
          <FieldTip :id="formTipId + '-field-0'"
            >填写事实描述的主体，例如用户或业务对象。</FieldTip
          > </ElFormItem
        ><ElFormItem
          label="关系"
          prop="predicate"
          :rules="[{ required: true, whitespace: true, message: '请填写关系', trigger: 'blur' }]"
          ><ElInput
            v-described-by="formTipId + '-field-1'"
            :aria-describedby="formTipId + '-field-1'"
            v-model="form.predicate"
          />
          <FieldTip :id="formTipId + '-field-1'"
            >填写主体与事实内容之间的关系。</FieldTip
          > </ElFormItem
        ><ElFormItem v-if="simpleEditable" label="记忆内容"
          ><ElInput
            v-model="simpleContent"
            :disabled="simpleContent === undefined"
            type="textarea"
            :rows="4"
            aria-label="记忆内容"
            v-described-by="formTipId + '-simple'"
          />
          <FieldTip :id="formTipId + '-simple'"
            >直接修改文本；条件、时间和其他结构化字段保持不变。高级内容不是有效的文本记忆时，请先修正高级内容。</FieldTip
          >
        </ElFormItem>
        <ElCollapse v-if="simpleEditable" v-model="expandedContent">
          <ElCollapseItem title="高级：结构化事实内容" name="content">
            <ElFormItem label="结构化事实内容" :error="objectError">
              <CodeEditor
                :described-by="formTipId + '-field-2'"
                :readonly="busy"
                v-model="form.object_json"
                language="json"
                height="260px"
              />
              <FieldTip :id="formTipId + '-field-2'"
                >填写事实内容 JSON，保留结构和原有字段语义。</FieldTip
              >
            </ElFormItem>
          </ElCollapseItem>
        </ElCollapse>
        <ElFormItem v-else label="结构化事实内容" :error="objectError"
          ><CodeEditor
            :described-by="formTipId + '-field-2'"
            :readonly="busy"
            v-model="form.object_json"
            language="json"
            height="260px"
          />
          <FieldTip :id="formTipId + '-field-2'"
            >填写事实内容 JSON，保留结构和原有字段语义。</FieldTip
          > </ElFormItem
        ><ElFormItem label="置信度"
          ><ElInputNumber
            v-described-by="formTipId + '-field-3'"
            :aria-describedby="formTipId + '-field-3'"
            v-model="form.confidence"
            :min="0"
            :max="1"
            :step="0.1"
          />
          <FieldTip :id="formTipId + '-field-3'"
            >对该事实可靠性的判断，范围为 0–1。</FieldTip
          > </ElFormItem
        ><ElFormItem label="重要性"
          ><ElInputNumber
            v-described-by="formTipId + '-field-4'"
            :aria-describedby="formTipId + '-field-4'"
            v-model="form.importance"
            :min="0"
            :max="1"
            :step="0.1"
          />
          <FieldTip :id="formTipId + '-field-4'">该事实的使用重要程度，范围为 0–1。</FieldTip>
        </ElFormItem></ElForm
      ><template #footer
        ><ElButton @click="requestClose" :disabled="busy">取消</ElButton
        ><ElButton
          v-permission="['foundation.memory.update']"
          type="primary"
          :loading="busy"
          :disabled="refreshRequired || latestChanged || selected?.status !== 'ACTIVE'"
          @click="save"
          >保存纠正</ElButton
        ></template
      ></ElDrawer
    ></AdminPage
  >
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import type { FormInstance } from 'element-plus'
import { findNodeAtLocation, parseTree } from 'jsonc-parser'
import { computed, onMounted, onScopeDispose, reactive, ref, useId, watch } from 'vue'
import {
  AdminPage,
  EntitySelect,
  statusLabel,
  useResource,
  useTask,
  useDrawerClose
} from '@skillforge/shared/admin'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import {
  api,
  getSessionId,
  type MemoryFact,
  type MemoryListQuery,
  type MemoryPageQuery
} from '../../api'
import { useAuthStore } from '../../stores/auth'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { confirm, message } from '@skillforge/shared/feedback'
import { memoryContentText, parseMemoryContent } from '@skillforge/shared/memory/contracts'
const formRef = ref<FormInstance>()
const objectError = ref('')
const scope = ref<MemoryListQuery['scope']>(),
  agentId = ref(''),
  endUserId = ref(''),
  reviews = ref(false),
  query = ref('')
const auth = useAuthStore()
let moreGeneration = 0
let firstReadGeneration = 0
let moreController: AbortController | undefined
const loadingMore = ref(false),
  moreError = ref('')
function cancelMore() {
  moreGeneration++
  moreController?.abort()
  moreController = undefined
  loadingMore.value = false
}
const {
  data,
  loading,
  error: loadError,
  reload,
  invalidate
} = useResource(async (signal) => {
  const identity = auth.identityVersion,
    session = getSessionId(),
    read = ++firstReadGeneration
  const requestedReviews = reviews.value
  const requestedScope = scope.value
  const requestedAgentId = agentId.value
  const requestedUserId = endUserId.value
  const requestedQuery = query.value.trim()
  const request: MemoryPageQuery = {
    status: requestedReviews ? 'PENDING_CONFIRM' : undefined,
    scope: requestedReviews ? undefined : requestedScope,
    agent_id: requestedReviews ? undefined : requestedAgentId || undefined,
    end_user_id: requestedReviews ? undefined : requestedUserId || undefined,
    q: requestedQuery || undefined,
    limit: 100
  }
  let result: Awaited<ReturnType<typeof api.memories.page>>
  try {
    result = await api.memories.page(request, signal)
  } catch (reason) {
    if (
      (identity !== auth.identityVersion || session !== getSessionId()) &&
      read === firstReadGeneration
    )
      invalidate()
    throw reason
  }
  if (identity !== auth.identityVersion || session !== getSessionId()) {
    if (read === firstReadGeneration) invalidate()
    throw Error('记忆查询已失效')
  }
  return {
    items: result.items,
    nextCursor: result.next_cursor,
    reviews: requestedReviews,
    scope: requestedScope,
    agentId: requestedAgentId,
    endUserId: requestedUserId,
    query: requestedQuery,
    request
  }
})
const pendingFilters = computed(
  () =>
    !!data.value &&
    (data.value.reviews !== reviews.value ||
      (!reviews.value &&
        (data.value.scope !== scope.value ||
          data.value.agentId !== agentId.value ||
          data.value.endUserId !== endUserId.value)) ||
      data.value.query !== query.value.trim())
)
const refreshRequired = ref(false)
const refreshCause = ref<'write' | 'stale'>('write')
async function refresh() {
  cancelMore()
  moreError.value = ''
  const value = await reload()
  if (value) {
    refreshRequired.value = false
    refreshCause.value = 'write'
  }
  return !!value
}
async function loadMore() {
  const previous = data.value
  if (
    !previous?.nextCursor ||
    loadingMore.value ||
    loading.value ||
    pendingFilters.value ||
    refreshRequired.value
  )
    return
  const generation = ++moreGeneration,
    identity = auth.identityVersion,
    session = getSessionId()
  const controller = new AbortController()
  moreController = controller
  loadingMore.value = true
  moreError.value = ''
  const current = () =>
    generation === moreGeneration &&
    data.value === previous &&
    auth.identityVersion === identity &&
    getSessionId() === session &&
    !pendingFilters.value
  try {
    const result = await api.memories.page(
      { ...previous.request, cursor: previous.nextCursor },
      controller.signal
    )
    if (!current()) return
    const seen = new Set(previous.items.map((item) => item.ref.fact_id))
    data.value = {
      ...previous,
      items: [...previous.items, ...result.items.filter((item) => !seen.has(item.ref.fact_id))],
      nextCursor: result.next_cursor
    }
  } catch (reason) {
    if (reason instanceof HttpClientError) reason.claimFeedback()
    if (current() && !controller.signal.aborted)
      moreError.value = reason instanceof Error ? reason.message : '无法读取更多记忆'
  } finally {
    if (generation === moreGeneration) {
      loadingMore.value = false
      moreController = undefined
    }
  }
}
watch(
  [scope, agentId, endUserId, reviews, query],
  () => {
    invalidate()
    cancelMore()
  },
  { flush: 'sync' }
)
watch(
  [() => auth.identityVersion, () => auth.sessionIdentity],
  () => {
    invalidate()
    cancelMore()
    data.value = undefined
    moreError.value = ''
  },
  { flush: 'sync' }
)
onScopeDispose(cancelMore)
function displayContent(row: MemoryFact) {
  const parsed = parseMemoryContent(row.object.canonical_json)
  return parsed ? memoryContentText(parsed) : row.object.canonical_json
}
const agentDirectory = useResource(async () => (await api.agents.list()).items)
const userDirectory = useResource(() => api.chat.endUsers())
const agentOptions = computed(() =>
  (agentDirectory.data.value ?? []).map((a) => ({ id: a.id, label: a.name }))
)
onMounted(async () => {
  await Promise.all([refresh(), agentDirectory.reload(), userDirectory.reload()])
})
const userOptions = computed(() =>
  (userDirectory.data.value ?? []).map((u) => ({ id: u.id, label: u.display_name || '未命名用户' }))
)
const pageError = computed(() =>
  [
    error.value || loadError.value || moreError.value,
    agentDirectory.error.value && `智能体选项读取失败：${agentDirectory.error.value}`,
    userDirectory.error.value && `终端用户选项读取失败：${userDirectory.error.value}`
  ]
    .filter(Boolean)
    .join('；')
)
const { busy, error, run } = useTask(),
  open = ref(false),
  selected = ref<MemoryFact>(),
  saved = ref(''),
  form = reactive({ subject: '', predicate: '', object_json: '', confidence: 1, importance: 0.5 })
const latestMemory = useResource(async () => {
  const row = selected.value!
  const identity = auth.identityVersion,
    session = getSessionId()
  const result = await api.memories.detail(row.ref.fact_id)
  if (
    !open.value ||
    selected.value !== row ||
    identity !== auth.identityVersion ||
    session !== getSessionId()
  )
    throw Error('记忆查询已失效')
  return result
})
const latestChanged = computed(
  () => !!latestMemory.data.value && latestMemory.data.value.revision !== selected.value?.revision
)
async function checkLatest() {
  const result = await latestMemory.reload()
  if (result && result.revision !== selected.value?.revision) {
    refreshCause.value = 'stale'
    refreshRequired.value = true
    await refresh()
  }
}
const simpleEditable = ref(false)
const expandedContent = ref<string[]>([])
// 按 JSON 字符范围替换正文，其他字段不经过 JS 数字转换。
const simpleNode = computed(() => {
  if (!parseMemoryContent(form.object_json)) return undefined
  const tree = parseTree(form.object_json)
  const values = tree?.children?.filter((property) => property.children?.[0]?.value === 'value')
  if (values?.length !== 1) return undefined
  const value = findNodeAtLocation(tree!, ['value'])
  if (value?.type === 'string') return value
  if (
    value?.type !== 'object' ||
    value.children?.filter((property) => property.children?.[0]?.value === 'text').length !== 1
  )
    return undefined
  const text = findNodeAtLocation(value, ['text'])
  return text?.type === 'string' ? text : undefined
})
const simpleContent = computed<string | undefined>({
  get: () => simpleNode.value?.value as string | undefined,
  set(value) {
    const node = simpleNode.value
    if (value === undefined || !node) return
    form.object_json =
      form.object_json.slice(0, node.offset) +
      JSON.stringify(value) +
      form.object_json.slice(node.offset + node.length)
  }
})
const { requestClose } = useDrawerClose({
  open: () => open.value,
  busy: () => busy.value,
  dirty: () => open.value && JSON.stringify(form) !== saved.value,
  close: () => {
    open.value = false
  }
})
function edit(row: MemoryFact) {
  if (refreshRequired.value || row.status !== 'ACTIVE') return
  latestMemory.invalidate()
  latestMemory.data.value = undefined
  latestMemory.error.value = ''
  selected.value = row
  Object.assign(form, {
    subject: row.subject,
    predicate: row.predicate,
    object_json: row.object.canonical_json,
    confidence: row.confidence,
    importance: row.importance
  })
  simpleEditable.value = simpleContent.value !== undefined
  expandedContent.value = []
  saved.value = JSON.stringify(form)
  open.value = true
}
async function save() {
  if (refreshRequired.value || latestChanged.value || selected.value?.status !== 'ACTIVE') return
  await run(async () => {
    if (!(await formRef.value?.validate().catch(() => false))) return
    objectError.value = ''
    try {
      JSON.parse(form.object_json)
    } catch {
      objectError.value = '请输入有效的 JSON 事实内容'
      expandedContent.value = ['content']
      return
    }
    const row = selected.value!
    await api.memories.correct(row.ref.fact_id, { expected_revision: row.revision, ...form })
    open.value = false
    refreshCause.value = 'write'
    refreshRequired.value = true
    if (await refresh()) message.success('记忆纠正已保存')
  })
}
function requiresSharingConfirmation(row: MemoryFact) {
  return row.scope === 'USER' && row.status === 'PENDING_CONFIRM'
}
function replacementNotice(row: MemoryFact): string | undefined {
  const proposal = parseMemoryContent(row.object.canonical_json)?.revisionProposal
  if (proposal) return `批准后替换事实 ${proposal.factId} 的第 ${proposal.expectedRevision} 版`
}
function confirmationMemoryContent(row: MemoryFact): string {
  const original = row.object.canonical_json
  const content = parseMemoryContent(original)
  return content?.revisionProposal ? memoryContentText(content) : original
}
async function review(row: MemoryFact, approved: boolean) {
  if (refreshRequired.value) return
  const factId = row.ref.fact_id
  const expectedRevision = row.revision
  const confirmSharing = approved && requiresSharingConfirmation(row)
  const replacement = approved ? replacementNotice(row) : undefined
  const confirmationContent = [
    ...(replacement ? [replacement] : []),
    ...(confirmSharing
      ? ['批准后，这条记忆可供同一终端用户的其他助手使用。请核对来源和内容后确认共享范围。']
      : []),
    `范围：${statusLabel(row.scope)}（${row.scope}${confirmSharing ? '，跨助手共享' : ''}）`,
    `来源事件：${row.source_event_ids.map((source) => source.value).join('、') || '无来源事件'}`,
    `主体：${row.subject}`,
    `关系：${row.predicate}`,
    `内容：${confirmationMemoryContent(row)}`
  ].join('\n')
  await run(async () => {
    if (confirmSharing || replacement) {
      if (
        !(await confirm({
          title: replacement ? '批准记忆修订' : '确认跨助手共享',
          content: confirmationContent,
          confirmText: replacement ? '确认替换' : '确认跨助手共享'
        }))
      )
        return
      await api.memories.review(
        factId,
        expectedRevision,
        approved,
        confirmSharing ? 'USER' : undefined
      )
    } else {
      await api.memories.review(factId, expectedRevision, approved)
    }
    refreshRequired.value = true
    if (await refresh()) message.success(approved ? '记忆已批准' : '记忆已拒绝')
  })
}
async function changeState(row: MemoryFact) {
  if (refreshRequired.value) return
  const restoring = row.status === 'FORGOTTEN' || row.status === 'DECAYED'
  if (
    !(await confirm({
      title: restoring ? '恢复记忆' : '遗忘记忆',
      confirmText: restoring ? '恢复记忆' : '遗忘记忆',
      content: restoring
        ? '恢复这条记忆，允许后续任务按原共享范围使用？'
        : '这条记忆将不再供后续任务使用；已经开始的任务保留原快照。'
    }))
  )
    return
  await run(async () => {
    await (restoring ? api.memories.restore : api.memories.forget)(row.ref.fact_id, row.revision)
    refreshRequired.value = true
    if (await refresh())
      message.success(
        restoring ? '记忆已恢复，后续任务可按原范围使用' : '记忆已遗忘，后续任务不再使用'
      )
  })
}

const formTipId = useId()
</script>

<style scoped>
.memory-review-notice {
  display: block;
  white-space: normal;
  overflow-wrap: anywhere;
}
</style>
