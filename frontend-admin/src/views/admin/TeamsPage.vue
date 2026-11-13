<template>
  <AdminPage title="团队" description="将已发布智能体组成协作团队，并指定主智能体。"
    ><template #extra
      ><ElButton type="primary" @click="edit()" :disabled="writeLocked"
        >新增团队</ElButton
      ></template
    ><FeedbackNotice v-if="pageError" :title="pageError" :closable="false" type="error" />
    <section v-if="draftPending && !open" class="admin-card" role="status">
      团队草稿保存结果尚未确认，请求 ID：<code>{{ draftPending.requestId }}</code
      >。未查到回执不代表保存失败。
      <ElButton v-if="can('foundation.teams.read')" :loading="busy" @click="checkDraft()"
        >核对草稿保存</ElButton
      >
      <p v-else>当前账号没有团队读取权限，请联系管理员授权后核对。</p>
    </section>
    <section v-if="publicationReceipt" class="admin-card" role="status">
      团队“{{ publicationReceipt.team?.name }}”已发布，修订
      {{ publicationReceipt.team?.revision }}。
      <ElButton
        v-if="canTrial && publicationReceipt.team?.id"
        link
        @click="trialTeam(publicationReceipt.team.id)"
        >试用当前发布版本</ElButton
      >
    </section>
    <section v-if="uncertain && !open" class="admin-card" aria-label="保存并发布结果核对">
      <p role="status">
        团队发布结果尚未确认，写入已锁定。请求 ID：<code>{{ uncertain.requestId }}</code>
      </p>
      <p>请记录请求 ID，核对原请求回执；尚未查到回执不代表操作失败。</p>
      <ElButton v-if="can('foundation.teams.read')" :loading="busy" @click="checkPublication"
        >核对原操作</ElButton
      >
    </section>
    <ElCollapse v-if="!uncertain && can('foundation.teams.read')" class="admin-card">
      <ElCollapseItem title="查询历史发布回执" name="receipt">
        <p class="admin-field-help">
          仅在系统提示结果未知且原页面已关闭时使用。正常保存与发布不需要请求 ID。
        </p>
        <div class="admin-actions">
          <ElInput v-model="receiptRequestId" placeholder="输入原请求 ID" aria-label="原请求 ID" />
          <ElButton :loading="busy" @click="checkPublication">查询发布回执</ElButton>
        </div>
      </ElCollapseItem>
    </ElCollapse>
    <div class="admin-toolbar admin-filters">
      <ElInput
        v-model="query"
        clearable
        aria-label="搜索团队"
        placeholder="搜索团队名称、职责或成员"
      />
      <ElButton :loading="loading" :disabled="locked" @click="refreshList">刷新</ElButton>
      <span v-if="data" class="admin-muted" role="status">{{ filtered.length }} 个团队</span>
    </div>
    <p v-if="refreshRequired" class="admin-field-help" role="status">
      团队操作已完成，但列表尚未更新；请刷新列表后继续修改。
    </p>
    <p v-if="loadError && data" class="admin-muted" role="status">
      团队列表刷新失败，以下保留上次读取的结果。
    </p>
    <ElTable
      table-layout="auto"
      :data="filtered"
      v-loading="loading"
      row-key="id"
      stripe
      :empty-text="
        !data && loading
          ? '正在读取团队…'
          : !data && loadError
            ? '团队列表尚未读取成功，请点击刷新重试'
            : query
              ? '没有匹配的团队，请调整关键词'
              : '尚无团队，选择已发布智能体组建协作团队'
      "
      ><ElTableColumn min-width="200" show-overflow-tooltip prop="name" label="团队"></ElTableColumn
      ><ElTableColumn
        min-width="280"
        show-overflow-tooltip
        prop="description"
        label="描述"
      ></ElTableColumn
      ><ElTableColumn min-width="180" label="成员" show-overflow-tooltip
        ><template #default="{ row }">{{
          row.members.map((member: Team['members'][number]) => member.name).join('、') || '暂无成员'
        }}</template></ElTableColumn
      ><ElTableColumn width="128" show-overflow-tooltip label="状态"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn width="210" class-name="admin-table-actions" prop="actions" label="操作"
        ><template #default="{ row }"
          ><ElButton link @click="edit(row as Team)" :disabled="writeLocked">编辑</ElButton
          ><ElButton
            v-permission="['foundation.teams.publish']"
            link
            :disabled="writeLocked || !can('foundation.teams.read') || row.status === 'disabled'"
            @click="publish(row as Team)"
            >{{ row.status === 'published' ? '发布新修订' : '发布' }}</ElButton
          ><ElButton
            v-if="canTrial && row.status === 'published'"
            link
            :disabled="busy"
            @click="trialTeam(row.id)"
            >试用</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    ><ElDrawer
      v-model="open"
      :before-close="closeDrawer0"
      :title="editing ? '编辑团队' : '新增团队'"
      size="680px"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!locked"
      :show-close="!locked"
      class="admin-drawer"
      ><section v-if="draftPending" class="admin-card" role="status">
        团队草稿保存结果尚未确认，请求 ID：<code>{{ draftPending.requestId }}</code
        >。
        <ElButton v-if="can('foundation.teams.read')" :loading="busy" @click="checkDraft()"
          >核对草稿保存</ElButton
        >
        <p v-else>当前账号没有团队读取权限，请联系管理员授权后核对。</p>
      </section>
      <section v-if="uncertain" class="admin-card" aria-label="保存并发布结果核对">
        <p role="status">
          团队发布结果尚未确认，写入已锁定。请求 ID：<code>{{ uncertain.requestId }}</code>
        </p>
        <p>请记录请求 ID，核对原请求回执；尚未查到回执不代表操作失败。</p>
        <ElButton v-if="can('foundation.teams.read')" :loading="busy" @click="checkPublication"
          >核对原操作</ElButton
        >
      </section>
      <ElForm
        ref="formRef"
        :model="form"
        label-position="top"
        @submit.prevent="save"
        :disabled="locked"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElFormItem
          label="名称"
          prop="name"
          :rules="[{ required: true, message: '请输入团队名称' }]"
          ><ElInput
            v-described-by="formTipId + '-field-0'"
            :aria-describedby="formTipId + '-field-0'"
            v-model="form.name"
          />
          <FieldTip :id="formTipId + '-field-0'"
            >填写团队展示名称，用于对话对象选择。</FieldTip
          > </ElFormItem
        ><ElFormItem label="描述"
          ><ElInput
            v-described-by="formTipId + '-field-1'"
            :aria-describedby="formTipId + '-field-1'"
            v-model="form.description"
            type="textarea"
          />
          <FieldTip :id="formTipId + '-field-1'">描述团队职责和适用场景。</FieldTip> </ElFormItem
        ><ElDivider content-position="left">协作分工</ElDivider
        ><ElAlert
          title="主智能体负责理解任务、协调成员并汇总答复。成员需要先发布才能加入团队。"
          type="info"
          :closable="false"
        /><ElButton
          v-if="agentLoadError"
          :loading="agentsLoading"
          :disabled="locked"
          @click="loadAgents"
          >重新读取成员选项</ElButton
        >
        <p v-if="!agentsLoading && !agentLoadError && !agents.length" class="admin-field-help">
          暂无可选的已发布智能体，请先发布团队需要的智能体。
          <RouterLink
            v-if="can('foundation.agents.read')"
            to="/admin/agents"
            target="_blank"
            rel="noopener noreferrer"
            >打开智能体管理（新标签页）</RouterLink
          >
          <ElButton link :disabled="locked" @click="loadAgents">重新读取成员选项</ElButton>
        </p>
        <p v-if="!agentLoadError && agents.length" class="admin-field-help">
          <RouterLink
            v-if="can('foundation.agents.read')"
            to="/admin/agents"
            target="_blank"
            rel="noopener noreferrer"
            >打开智能体管理（新标签页）</RouterLink
          >
          <ElButton link :loading="agentsLoading" :disabled="locked" @click="loadAgents"
            >刷新成员选项</ElButton
          >
        </p>
        <ElFormItem label="团队成员"
          ><ElSelect
            v-described-by="formTipId + '-field-2'"
            :aria-describedby="formTipId + '-field-2'"
            v-model="members"
            @change="markMemberSelection"
            :loading="agentsLoading"
            multiple
            filterable
            :filter-method="(query: string) => (memberSearch = query)"
            popper-class="team-member-options"
            ><ElOption
              v-for="agent in filteredAgents"
              :key="agent.id"
              :value="agent.id"
              :label="agent.name"
              ><div>{{ agent.name }}</div>
              <small class="admin-field-help"
                >{{ agent.role || '未填写职责' }} · 标识：{{ agent.id }}</small
              ></ElOption
            ></ElSelect
          >
          <p
            v-if="
              autoMember &&
              !agentsLoading &&
              !agentLoadError &&
              agentDirectory?.length === 1 &&
              agentDirectory[0]?.id === autoMember &&
              members.includes(autoMember)
            "
            class="admin-field-help"
            role="status"
          >
            已预选唯一可用成员并设为主智能体，可按需修改。
          </p>
          <FieldTip :id="formTipId + '-field-2'"
            >按名称、职责或标识查找；主智能体必须属于已选成员。</FieldTip
          > </ElFormItem
        ><ElFormItem
          label="主智能体"
          prop="primary_agent_id"
          :rules="[{ required: true, message: '请选择主智能体' }]"
          ><ElSelect
            v-described-by="formTipId + '-field-3'"
            :aria-describedby="formTipId + '-field-3'"
            v-model="form.primary_agent_id"
            :disabled="!members.length"
            placeholder="先选择团队成员"
            ><ElOption
              v-for="id in members"
              :key="id"
              :value="id"
              :label="(agents.find((a) => a.id === id)?.name || '智能体') + ' · ' + id"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-3'"
            >从已选成员中选择协调任务的主智能体。</FieldTip
          > </ElFormItem
        ><ElFormItem label="状态"
          ><ElSelect
            v-described-by="formTipId + '-field-4'"
            :aria-describedby="formTipId + '-field-4'"
            v-model="form.status"
            ><ElOption value="draft" label="草稿" /><ElOption value="disabled" label="停用"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-4'"
            >保存为草稿后发布，团队才能用于新会话；停用会停止提供新会话入口。</FieldTip
          >
        </ElFormItem></ElForm
      ><template #footer
        ><ElButton @click="closeDrawer0" :disabled="locked">取消</ElButton
        ><span v-if="!members.length" class="admin-muted">选择团队成员后即可保存。</span
        ><ElButton
          v-permission="[editing ? 'foundation.teams.update' : 'foundation.teams.create']"
          type="primary"
          :loading="busy"
          :disabled="locked || !members.length || !form.primary_agent_id"
          @click="save"
          >保存</ElButton
        ><span
          v-if="
            can(editing ? 'foundation.teams.update' : 'foundation.teams.create') &&
            can('foundation.teams.publish') &&
            !can('foundation.teams.read')
          "
          class="admin-muted"
          >保存并发布需要团队读取权限，以便核对原请求回执。</span
        ><ElButton
          v-if="canSaveAndPublish"
          type="primary"
          :loading="busy"
          :disabled="
            locked || !members.length || !form.primary_agent_id || form.status === 'disabled'
          "
          @click="saveAndPublish"
          >保存并发布</ElButton
        ></template
      ></ElDrawer
    ></AdminPage
  >
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { confirm, message } from '@skillforge/shared/feedback'
import { guardFormEnter } from '@skillforge/shared/admin'
import { statusLabel } from '@skillforge/shared/admin'
import { onMounted, onScopeDispose, ref, shallowRef, reactive, computed, watch, useId } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { HttpClientError } from '@skillforge/shared/utils/http'
import type { FormInstance } from 'element-plus'
import {
  AdminPage,
  useTask,
  useResource,
  useUnsavedChanges,
  useDrawerClose
} from '@skillforge/shared/admin'
import {
  api,
  getSessionId,
  type Team,
  type TeamDraft,
  type TeamSaveAndPublishReceipt
} from '../../api'
const {
  data,
  loading,
  error: loadError,
  reload,
  invalidate: invalidateTeams
} = useResource(() => api.teams.list())
const auth = useAuthStore()
const router = useRouter()
const can = (permission: string) => auth.user?.permissions.includes(permission) === true
const owner = computed(() =>
  auth.user?.tenant_id && auth.user?.sub ? JSON.stringify([auth.user.tenant_id, auth.user.sub]) : ''
)
type DraftOperation = { requestId: string; teamId: string | null; expectedRevision: number }
const draftPending = shallowRef<DraftOperation>()
const draftStorageError = ref('')
const draftStorageKey = (subject: string) => `skillforge.team-draft.${subject}`
const storageError = ref('')
const refreshRequired = ref(false)
const storageKey = (subject: string) => `skillforge.team-publication.${subject}`
const operations = reactive<
  Record<
    string,
    | { requestId: string; teamId: string | null; kind?: 'saved'; expectedRevision?: number }
    | undefined
  >
>({})
const uncertain = computed(() => operations[owner.value])
const publicationReceipt = ref<TeamSaveAndPublishReceipt>()
const receiptRequestId = ref('')
const locked = computed(
  () =>
    busy.value ||
    !!uncertain.value ||
    !!storageError.value ||
    !!draftPending.value ||
    !!draftStorageError.value
)
const writeLocked = computed(() => locked.value || refreshRequired.value)
const canSaveAndPublish = computed(
  () =>
    can(editing.value ? 'foundation.teams.update' : 'foundation.teams.create') &&
    can('foundation.teams.publish') &&
    can('foundation.teams.read')
)
const canTrial = computed(() => can('foundation.teams.read') && can('foundation.sessions.read'))
let disposed = false
onScopeDispose(() => {
  disposed = true
})
watch(
  owner,
  () => {
    publicationReceipt.value = undefined
    refreshRequired.value = false
    receiptRequestId.value = ''
    storageError.value = ''
    draftPending.value = undefined
    draftStorageError.value = ''
    if (!owner.value) {
      storageError.value = '无法确认当前机构和成员，暂不能保存并发布。'
      return
    }
    try {
      const raw = sessionStorage.getItem(draftStorageKey(owner.value))
      if (raw) {
        const value = JSON.parse(raw) as DraftOperation
        if (
          !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
            value.requestId
          ) ||
          !(value.teamId === null || typeof value.teamId === 'string') ||
          !Number.isSafeInteger(value.expectedRevision) ||
          value.expectedRevision < 0
        )
          throw Error('invalid')
        draftPending.value = value
      }
    } catch {
      draftStorageError.value = '无法读取原草稿保存记录，请恢复浏览器存储后重新进入。'
    }
    try {
      const raw = sessionStorage.getItem(storageKey(owner.value))
      if (!raw) return
      const pending: unknown = JSON.parse(raw)
      if (
        !pending ||
        typeof pending !== 'object' ||
        typeof (pending as { requestId?: unknown }).requestId !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
          (pending as { requestId: string }).requestId
        ) ||
        !(
          (pending as { teamId?: unknown }).teamId === null ||
          typeof (pending as { teamId?: unknown }).teamId === 'string'
        ) ||
        ((pending as { kind?: unknown }).kind === 'saved' &&
          (!Number.isSafeInteger((pending as { expectedRevision?: unknown }).expectedRevision) ||
            Number((pending as { expectedRevision: number }).expectedRevision) < 1))
      )
        throw Error('invalid pending operation')
      operations[owner.value] = pending as {
        requestId: string
        teamId: string | null
        kind?: 'saved'
        expectedRevision?: number
      }
    } catch {
      storageError.value = '无法读取原发布请求记录，请恢复浏览器存储后重新进入页面。'
    }
  },
  { flush: 'sync', immediate: true }
)
const query = ref('')
const filtered = computed(() =>
  (data.value ?? []).filter((item) => {
    const keyword = query.value.trim().toLowerCase()
    return [item.name, item.description ?? '', ...item.members.map((member) => member.name)].some(
      (value) => value.toLowerCase().includes(keyword)
    )
  })
)
async function refreshList() {
  if (await reload()) refreshRequired.value = false
}
async function refreshAfterWrite() {
  refreshRequired.value = true
  await refreshList()
}
onMounted(() => {
  void refreshList()
  if (draftPending.value && can('foundation.teams.read')) void checkDraft(true)
})
const { busy, error, run } = useTask()
const open = ref(false),
  editing = ref<Team>(),
  members = ref<string[]>([]),
  saved = ref(''),
  formRef = ref<FormInstance>()
let directoryRequest = 0
const {
  data: agentDirectory,
  loading: agentsLoading,
  error: agentLoadError,
  reload: reloadAgentDirectory,
  invalidate: invalidateAgents
} = useResource(async () => {
  const identity = pageIdentity.value,
    session = getSessionId(),
    epoch = editorEpoch,
    request = ++directoryRequest
  const current = () =>
    !disposed &&
    identity === pageIdentity.value &&
    session === getSessionId() &&
    epoch === editorEpoch &&
    open.value
  try {
    const result = (await api.agents.list()).items.filter((agent) => agent.status === 'published')
    if (!current()) throw Error('成员选项读取已失效')
    return result
  } catch (reason) {
    if (!current() && request === directoryRequest) invalidateAgents()
    throw reason
  }
})
const agents = computed(() => {
  const options = new Map<string, { id: string; name: string; role: string }>(
    (agentDirectory.value ?? []).map((agent) => [
      agent.id,
      { id: agent.id, name: agent.name, role: agent.role_description ?? '' }
    ])
  )
  for (const member of editing.value?.members ?? []) {
    if (members.value.includes(member.agent_id))
      options.set(member.agent_id, {
        id: member.agent_id,
        name: member.name,
        role: options.get(member.agent_id)?.role ?? ''
      })
  }
  return [...options.values()]
})
const memberSearch = ref('')
const memberSelectionTouched = ref(false)
const autoMember = ref('')
function markMemberSelection() {
  memberSelectionTouched.value = true
  autoMember.value = ''
}
let editorEpoch = 0
let directorySession: string | null = null
let lastForegroundRead = 0
function refreshForegroundAgents() {
  const session = getSessionId()
  if (session !== directorySession) {
    invalidateAgents()
    return
  }
  if (
    disposed ||
    !open.value ||
    document.visibilityState !== 'visible' ||
    locked.value ||
    !can('foundation.agents.read') ||
    !session ||
    agentsLoading.value ||
    Date.now() - lastForegroundRead < 5000
  )
    return
  lastForegroundRead = Date.now()
  void reloadAgentDirectory()
}
function onDirectoryVisibility() {
  if (document.visibilityState !== 'visible') invalidateAgents()
  else refreshForegroundAgents()
}
window.addEventListener('focus', refreshForegroundAgents)
document.addEventListener('visibilitychange', onDirectoryVisibility)
watch(
  [open, locked, () => auth.sessionIdentity],
  () => {
    if (!open.value || locked.value || getSessionId() !== directorySession) invalidateAgents()
  },
  { flush: 'sync' }
)
watch(
  () => can('foundation.agents.read'),
  (allowed, previous) => {
    if (previous && !allowed) invalidateAgents()
  },
  { flush: 'sync' }
)
onScopeDispose(() => {
  invalidateAgents()
  window.removeEventListener('focus', refreshForegroundAgents)
  document.removeEventListener('visibilitychange', onDirectoryVisibility)
})
const filteredAgents = computed(() => {
  const keyword = memberSearch.value.trim().toLocaleLowerCase()
  return agents.value.filter((agent) =>
    [agent.name, agent.role, agent.id].some((value) => value.toLocaleLowerCase().includes(keyword))
  )
})
const pageError = computed(() =>
  [
    draftStorageError.value,
    storageError.value || error.value || loadError.value,
    open.value && agentLoadError.value && `成员选项读取失败：${agentLoadError.value}`
  ]
    .filter(Boolean)
    .join('；')
)
const form = reactive<TeamDraft>({
  revision: 0,
  name: '',
  description: '',
  status: 'draft',
  members: [],
  primary_agent_id: ''
})
const pageIdentity = computed(() => JSON.stringify([owner.value, auth.identityVersion]))
watch(
  pageIdentity,
  () => {
    invalidateTeams()
    invalidateAgents()
    data.value = undefined
    agentDirectory.value = undefined
    loadError.value = ''
    agentLoadError.value = ''
    error.value = ''
    query.value = ''
    memberSearch.value = ''
    open.value = false
    editing.value = undefined
    editorEpoch++
    memberSelectionTouched.value = false
    autoMember.value = ''
    members.value = []
    Object.assign(form, {
      revision: 0,
      name: '',
      description: '',
      status: 'draft',
      members: [],
      primary_agent_id: ''
    })
    saved.value = ''
    const identity = pageIdentity.value
    void Promise.resolve().then(() => {
      if (!disposed && owner.value && pageIdentity.value === identity) void refreshList()
    })
  },
  { flush: 'sync' }
)
const snapshot = () => JSON.stringify([form, members.value])
const dirty = computed(() => open.value && snapshot() !== saved.value)
useUnsavedChanges({
  dirty: () => dirty.value || !!uncertain.value || !!draftPending.value,
  busy: () => busy.value,
  leaveMessage: () =>
    uncertain.value
      ? `团队发布结果未知，请记录请求 ID 并查询原回执：${uncertain.value.requestId}`
      : undefined
})
watch(members, (list) => {
  if (!list.includes(form.primary_agent_id))
    form.primary_agent_id = list.length === 1 ? list[0]! : ''
})
async function edit(row?: Team) {
  if (writeLocked.value) return
  await run(async () => {
    memberSearch.value = ''
    editorEpoch++
    directorySession = getSessionId()
    lastForegroundRead = 0
    memberSelectionTouched.value = false
    autoMember.value = ''
    editing.value = row
    Object.assign(form, {
      revision: row?.revision ?? 0,
      name: row?.name ?? '',
      description: row?.description ?? '',
      status: row?.status === 'disabled' ? 'disabled' : 'draft',
      members: [],
      primary_agent_id: row?.primary_agent_id ?? ''
    })
    members.value = row?.members.map((a) => a.agent_id) ?? []
    saved.value = snapshot()
    open.value = true
    void loadAgents()
  })
}
async function loadAgents() {
  const epoch = editorEpoch,
    identity = pageIdentity.value,
    session = getSessionId()
  const directory = await reloadAgentDirectory()
  if (
    !disposed &&
    pageIdentity.value === identity &&
    editorEpoch === epoch &&
    open.value &&
    session === getSessionId()
  )
    directorySession = session
  if (
    disposed ||
    pageIdentity.value !== identity ||
    editorEpoch !== epoch ||
    !open.value ||
    editing.value ||
    memberSelectionTouched.value ||
    members.value.length ||
    directory?.length !== 1
  )
    return
  const only = directory[0]!.id
  const [baseline] = JSON.parse(saved.value) as [TeamDraft, string[]]
  baseline.primary_agent_id = only
  saved.value = JSON.stringify([baseline, [only]])
  members.value = [only]
  form.primary_agent_id = only
  autoMember.value = only
}
async function save() {
  if (writeLocked.value) return
  const identity = pageIdentity.value,
    subject = owner.value
  await run(async () => {
    if (!(await formRef.value?.validate().catch(() => false))) return
    if (identity !== pageIdentity.value) return
    if (!members.value.includes(form.primary_agent_id)) throw Error('主智能体必须属于团队')
    if (!subject) throw Error('当前身份不可用，请重新登录')
    const payload = { ...form, members: members.value.map((agent_id) => ({ agent_id })) }
    const operation: DraftOperation = {
      requestId: crypto.randomUUID(),
      teamId: editing.value?.id ?? null,
      expectedRevision: form.revision
    }
    try {
      sessionStorage.setItem(draftStorageKey(subject), JSON.stringify(operation))
    } catch {
      draftStorageError.value = '无法保存原草稿请求记录，未发送保存请求。'
      return
    }
    draftPending.value = operation
    try {
      const receipt = await api.teams.saveWithReceipt(
        operation.requestId,
        operation.teamId,
        payload
      )
      if (disposed || pageIdentity.value !== identity || owner.value !== subject) return
      await acceptDraft(receipt, operation, subject, identity)
    } catch (cause) {
      if (disposed || pageIdentity.value !== identity || owner.value !== subject) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        return
      }
      if (cause instanceof HttpClientError && cause.code === 'TEAM_DRAFT_SAVE_REJECTED') {
        try {
          sessionStorage.removeItem(draftStorageKey(subject))
          draftPending.value = undefined
        } catch {
          draftStorageError.value = '无法清除原草稿保存记录，请恢复浏览器存储后重新核对。'
        }
      }
      throw cause
    }
  })
}
async function acceptDraft(
  receipt: { request_id: string; team_id: string; revision: number } | undefined,
  operation: DraftOperation,
  subject: string,
  identity: string,
  automatic = false
) {
  if (
    disposed ||
    pageIdentity.value !== identity ||
    owner.value !== subject ||
    draftPending.value !== operation
  )
    return
  if (
    !receipt ||
    receipt.request_id !== operation.requestId ||
    !receipt.team_id ||
    (operation.teamId && receipt.team_id !== operation.teamId) ||
    !Number.isSafeInteger(receipt.revision) ||
    receipt.revision !== operation.expectedRevision + 1
  ) {
    if (!automatic) message.warning('尚未查到完整匹配的原草稿回执，结果仍未确认')
    return
  }
  try {
    sessionStorage.removeItem(draftStorageKey(subject))
  } catch {
    draftStorageError.value = '草稿已保存，但无法清除原请求记录，请恢复浏览器存储。'
    return
  }
  draftPending.value = undefined
  saved.value = snapshot()
  open.value = false
  message.success('团队草稿已保存')
  await refreshAfterWrite()
}
async function checkDraft(automatic = false) {
  const operation = draftPending.value,
    subject = owner.value,
    identity = pageIdentity.value
  if (!operation || !subject || !can('foundation.teams.read')) return
  await run(async () => {
    const receipt = await api.teams.saveReceipt(operation.requestId)
    await acceptDraft(receipt, operation, subject, identity, automatic)
  })
}
async function publish(row: Team) {
  if (
    writeLocked.value ||
    !can('foundation.teams.publish') ||
    !can('foundation.teams.read') ||
    row.status === 'disabled'
  )
    return
  const identity = pageIdentity.value,
    operationOwner = owner.value,
    teamId = row.id,
    revision = row.revision
  await run(async () => {
    if (
      !(await confirm({
        title: row.status === 'published' ? '发布团队新修订' : '发布团队',
        confirmText: row.status === 'published' ? '发布新修订' : '发布团队',
        content: `按当前已保存配置为“${row.name}”生成新的发布修订？新受理的执行使用新发布，已经受理的执行保持原冻结配置。`
      }))
    )
      return
    if (
      disposed ||
      identity !== pageIdentity.value ||
      operationOwner !== owner.value ||
      !can('foundation.teams.publish') ||
      !can('foundation.teams.read') ||
      uncertain.value ||
      storageError.value ||
      draftPending.value ||
      draftStorageError.value ||
      refreshRequired.value
    )
      return
    if (!Number.isSafeInteger(revision) || revision < 1) throw Error('团队修订无效，请刷新列表')
    const operation = {
      requestId: crypto.randomUUID(),
      teamId,
      kind: 'saved' as const,
      expectedRevision: revision
    }
    try {
      sessionStorage.setItem(storageKey(operationOwner), JSON.stringify(operation))
    } catch {
      storageError.value =
        '无法保存原发布请求记录，未发送发布请求；请恢复浏览器存储后重新进入页面。'
      return
    }
    operations[operationOwner] = operation
    publicationReceipt.value = undefined
    try {
      const value = await api.teams.publishWithReceipt(operation.requestId, teamId, revision)
      await acceptReceipt(value, operation.requestId, operationOwner, identity)
    } catch (cause) {
      if (disposed || pageIdentity.value !== identity || owner.value !== operationOwner) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        throw 'cancel'
      }
      if (cause instanceof HttpClientError && cause.code === 'TEAM_PUBLISH_PRE_DISPATCH_REJECTED') {
        try {
          sessionStorage.removeItem(storageKey(operationOwner))
          delete operations[operationOwner]
        } catch {
          storageError.value = '无法清除原发布请求记录，请恢复浏览器存储后重新核对。'
        }
      }
      throw cause
    }
  })
}

async function acceptReceipt(
  value: TeamSaveAndPublishReceipt | undefined,
  requestId: string,
  operationOwner: string,
  identity: string
) {
  if (disposed || pageIdentity.value !== identity) return
  const operation = operations[operationOwner]
  if (
    !value ||
    value.request_id !== requestId ||
    (value.status !== 'PROCESSING' &&
      value.status !== 'REJECTED' &&
      value.status !== 'SUCCEEDED') ||
    (value.status === 'REJECTED' && !value.error_message) ||
    (value.status === 'SUCCEEDED' &&
      (!value.team?.id ||
        value.team.status !== 'published' ||
        !Number.isSafeInteger(value.team.revision) ||
        value.team.revision < 1 ||
        (operation?.teamId && operation.teamId !== value.team.id) ||
        (operation?.kind === 'saved' && value.team.revision !== operation.expectedRevision! + 1)))
  ) {
    if (!disposed && owner.value === operationOwner)
      message.warning('尚未查到完整匹配的原请求回执，结果仍未确认')
    return
  }
  if (value.status === 'PROCESSING') {
    if (!disposed && owner.value === operationOwner) message.warning('原请求仍在处理，结果尚未确认')
    return
  }
  if (disposed || owner.value !== operationOwner) return
  if (operation?.requestId === requestId) {
    try {
      sessionStorage.removeItem(storageKey(operationOwner))
    } catch {
      storageError.value = '无法清除原发布请求记录，请恢复浏览器存储后重新核对。'
      return
    }
    delete operations[operationOwner]
    storageError.value = ''
  }
  receiptRequestId.value = ''
  if (value.status === 'REJECTED') {
    message.error(`团队发布未完成：${value.error_message}`)
    return
  }
  publicationReceipt.value = value
  if (operation?.requestId === requestId && operation.kind !== 'saved') {
    saved.value = snapshot()
    open.value = false
  }
  message.success(
    operation?.kind === 'saved' ? '团队已发布' : `团队已保存并发布，修订 ${value.team!.revision}`
  )
  await refreshAfterWrite()
}
async function saveAndPublish() {
  if (writeLocked.value || !canSaveAndPublish.value || form.status === 'disabled') return
  const operationOwner = owner.value
  const identity = pageIdentity.value
  await run(async () => {
    if (!(await formRef.value?.validate().catch(() => false))) return
    if (!members.value.includes(form.primary_agent_id)) throw Error('主智能体必须属于团队')
    if (
      !(await confirm({
        title: '保存并发布团队',
        confirmText: '保存并发布',
        content: `保存并发布“${form.name}”的当前配置？新受理的执行使用新发布，已经受理的执行保持原冻结配置。`
      }))
    )
      return
    if (
      disposed ||
      pageIdentity.value !== identity ||
      owner.value !== operationOwner ||
      !canSaveAndPublish.value ||
      uncertain.value
    )
      return
    const requestId = crypto.randomUUID()
    const teamId = editing.value?.id ?? null
    const payload = { ...form, members: members.value.map((agent_id) => ({ agent_id })) }
    try {
      sessionStorage.setItem(storageKey(operationOwner), JSON.stringify({ requestId, teamId }))
    } catch {
      storageError.value =
        '无法保存原发布请求记录，未发送发布请求；请恢复浏览器存储后重新进入页面。'
      return
    }
    operations[operationOwner] = { requestId, teamId }
    publicationReceipt.value = undefined
    try {
      const value = await api.teams.saveAndPublish(requestId, teamId, payload)
      await acceptReceipt(value, requestId, operationOwner, identity)
    } catch (cause) {
      if (disposed || pageIdentity.value !== identity || owner.value !== operationOwner) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        throw 'cancel'
      }
      if (cause instanceof HttpClientError && cause.code === 'TEAM_PUBLISH_PRE_DISPATCH_REJECTED') {
        try {
          sessionStorage.removeItem(storageKey(operationOwner))
          delete operations[operationOwner]
        } catch {
          storageError.value = '无法清除原发布请求记录，请恢复浏览器存储后重新核对。'
        }
      }
      throw cause
    }
  })
}
async function checkPublication() {
  const operationOwner = owner.value
  const identity = pageIdentity.value
  const requestId = uncertain.value?.requestId ?? receiptRequestId.value.trim()
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)) {
    message.warning('请输入原请求的 UUID')
    return
  }
  await run(async () => {
    try {
      const value = await api.teams.saveAndPublishResult(requestId)
      await acceptReceipt(value, requestId, operationOwner, identity)
    } catch (cause) {
      if (identity !== pageIdentity.value) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        return
      }
      throw cause
    }
  })
}
function trialTeam(id: string) {
  if (canTrial.value) void router.push({ path: '/admin/chat', query: { team_id: id } })
}

const { requestClose: closeDrawer0 } = useDrawerClose({
  open: () => open.value,
  busy: () => locked.value,
  dirty: () => open.value && dirty.value,
  close: () => {
    open.value = false
  }
})

const formTipId = useId()
</script>

<style scoped>
:global(.team-member-options .el-select-dropdown__item) {
  height: auto;
  line-height: 1.5;
  padding-block: 6px;
  white-space: normal;
}
</style>
