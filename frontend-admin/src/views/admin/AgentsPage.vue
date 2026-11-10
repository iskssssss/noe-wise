<template>
  <AdminPage :embedded="embedded" title="智能体" description="创建、配置和发布机构智能体。"
    ><template #extra
      ><ElButton
        @click="openTemplate"
        :disabled="
          busy ||
          !!createdAgent ||
          !!installationStorageError ||
          (!!pendingInstallationId && !pendingInstallation)
        "
        >从模板安装</ElButton
      ><ElButton
        type="primary"
        @click="openCreate"
        :disabled="busy || !!pendingCreation || !!creationStorageError || !!createdAgent"
        >新建智能体</ElButton
      ></template
    ><FeedbackNotice
      v-if="error || loadError"
      :title="error || loadError"
      :closable="false"
      type="error"
    />
    <section v-if="pendingCreation" class="admin-card" role="status">
      智能体“{{ pendingCreation.name }}”创建结果尚未确认，请求号：<code>{{
        pendingCreation.requestId
      }}</code
      >。未查到回执不代表创建失败。
      <ElButton
        v-permission="['foundation.agents.read']"
        :disabled="busy"
        @click="checkAgentCreation()"
        >核对创建结果</ElButton
      >
    </section>
    <p v-if="creationStorageError" role="status">{{ creationStorageError }}</p>
    <section v-if="createdAgent" class="admin-card" role="status">
      智能体已创建。<RouterLink :to="'/admin/agents/' + createdAgent">进入原智能体配置</RouterLink>
    </section>
    <section v-if="pendingInstallationId" class="admin-card" role="status">
      模板安装结果尚未确认，请求号：<code>{{ pendingInstallationId }}</code
      >。未查到回执不代表安装失败。
      <ElButton
        v-permission="['foundation.agents.read']"
        :disabled="busy"
        @click="checkInstallation()"
        >核对原安装结果</ElButton
      >
    </section>
    <p v-if="installationStorageError" role="status">{{ installationStorageError }}</p>
    <div class="admin-toolbar admin-filters">
      <ElInput
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="query"
        placeholder="搜索名称或标识"
        clearable
      /><ElSelect v-model="draftFilter" aria-label="发布状态" clearable placeholder="全部发布状态">
        <ElOption label="有待发布修改" value="pending" />
        <ElOption label="尚未发布" value="unpublished" />
        <ElOption label="已发布" value="published" /> </ElSelect
      ><ElButton ref="refreshButton" @click="reload" :loading="loading" :disabled="busy"
        >刷新</ElButton
      >

      <FieldTip :id="formTipId + '-group-0'"
        >名称、标识与发布状态即时筛选当前列表；进入配置后维护草稿并发布。</FieldTip
      >
    </div>
    <p v-if="data" class="admin-muted" role="status">显示 {{ filtered.length }} 个智能体</p>
    <p v-if="loadError && data" class="admin-muted" role="status">
      智能体列表刷新失败，以下保留上次读取的结果。
    </p>
    <ElTable
      table-layout="auto"
      :data="filtered"
      v-loading="loading"
      row-key="id"
      stripe
      :empty-text="
        !data && loading
          ? '正在读取智能体…'
          : !data && loadError
            ? '智能体列表尚未读取成功，请点击刷新重试'
            : query || draftFilter
              ? '没有匹配的智能体，请调整筛选条件'
              : '尚无智能体，点击“新建智能体”开始配置'
      "
      ><ElTableColumn min-width="200" show-overflow-tooltip prop="name" label="名称"
        ><template #default="{ row }"
          ><RouterLink :to="'/admin/agents/' + row.id">{{ row.name }}</RouterLink></template
        ></ElTableColumn
      ><ElTableColumn
        min-width="200"
        show-overflow-tooltip
        prop="agent_key"
        label="标识"
      ></ElTableColumn
      ><ElTableColumn width="128" show-overflow-tooltip label="状态"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn width="128" label="发布修订"
        ><template #default="{ row }">{{
          row.published_revision ?? '尚未发布'
        }}</template></ElTableColumn
      ><ElTableColumn width="128" label="工作草稿"
        ><template #default="{ row }"
          ><span v-if="row.definition_managed">由公共部署管理</span>
          <ElTag v-else-if="row.has_draft_changes" type="warning">待发布</ElTag
          ><span v-else>与发布一致</span></template
        ></ElTableColumn
      ><ElTableColumn min-width="164" class-name="admin-table-actions" prop="actions" label="操作"
        ><template #default="{ row }"
          ><ElButton link @click="router.push('/admin/agents/' + row.id)" :disabled="busy">{{
            row.definition_managed ? '查看' : '配置'
          }}</ElButton
          ><RouterLink
            v-if="
              row.definition_managed &&
              auth.user?.permissions.includes('tenant.agent_deployments.read')
            "
            to="/admin/agent-deployments"
            >管理公共部署</RouterLink
          ><ElButton
            v-if="
              !row.definition_managed &&
              row.status === 'published' &&
              auth.user?.permissions.includes('foundation.agents.update')
            "
            link
            type="warning"
            :disabled="busy"
            @click="disable(row as AgentItem)"
            >停用</ElButton
          ><ElButton
            v-if="!row.definition_managed && row.status === 'draft' && !row.published_revision_id"
            v-permission="['foundation.agents.delete']"
            link
            type="danger"
            @click="remove(row as AgentItem)"
            :disabled="busy"
            >删除</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    >
    <ElDrawer
      v-model="open"
      :before-close="closeDrawer0"
      title="新建智能体"
      size="680px"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      ><FeedbackNotice
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        role="alert"
      /><ElForm
        ref="createFormRef"
        :model="{ name }"
        @submit.prevent="create"
        label-position="top"
        :disabled="busy || !!pendingCreation || !!createdAgent || !!creationStorageError"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElFormItem
          label="名称"
          prop="name"
          :rules="[
            { required: true, whitespace: true, message: '请输入智能体名称', trigger: 'blur' }
          ]"
          required
          ><ElInput
            v-described-by="formTipId + '-field-0'"
            :aria-describedby="formTipId + '-field-0'"
            v-model="name"
            maxlength="100"
          />
          <FieldTip :id="formTipId + '-field-0'"
            >填写智能体的展示名称，最多 100 个字符。</FieldTip
          > </ElFormItem
        ><ElCollapse
          ><ElCollapseItem title="初始配置（可选）" name="initial"
            ><ElFormItem label="系统提示词"
              ><ElInput
                v-described-by="formTipId + '-field-2'"
                :aria-describedby="formTipId + '-field-2'"
                v-model="prompt"
                type="textarea"
                :rows="8"
              />
              <FieldTip :id="formTipId + '-field-2'">描述智能体职责、执行方式和限制。</FieldTip>
            </ElFormItem></ElCollapseItem
          ></ElCollapse
        ></ElForm
      ><template #footer
        ><ElButton @click="closeDrawer0" :disabled="busy">取消</ElButton
        ><ElButton
          v-permission="['foundation.agents.create']"
          type="primary"
          :loading="busy"
          :disabled="!!pendingCreation || !!createdAgent || !!creationStorageError"
          @click="create"
          >创建并配置</ElButton
        ></template
      ></ElDrawer
    >
    <ElDrawer
      v-model="templateOpen"
      :before-close="closeDrawer1"
      title="从模板安装"
      size="680px"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy && !templateSwitching"
      :show-close="!busy && !templateSwitching"
      class="admin-drawer"
      ><FeedbackNotice v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <ElAlert
        v-if="pendingInstallation"
        :title="`安装结果尚未确认。原请求 ID：${pendingInstallation.request_id}。可只读核对回执；继续原安装会再次提交相同请求，若原请求未受理可能产生首次安装。`"
        type="warning"
        :closable="false"
      />
      <ElForm
        label-position="top"
        @submit.prevent
        :disabled="busy || templateSwitching || !!pendingInstallation"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElFormItem label="模板"
          ><ElSelect
            v-described-by="formTipId + '-field-3'"
            :aria-describedby="formTipId + '-field-3'"
            v-model="templateId"
            filterable
            @change="selectTemplate"
            ><ElOption
              v-for="item in templates"
              :key="item.id"
              :label="item.name + ' ' + item.version"
              :value="item.id"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-3'"
            >选择要安装的已发布模板，加载模板所需的能力与智能体槽。</FieldTip
          > </ElFormItem
        ><ElFormItem label="智能体名称"
          ><ElInput
            v-described-by="formTipId + '-field-4'"
            :aria-describedby="formTipId + '-field-4'"
            v-model="templateName"
          />
          <FieldTip :id="formTipId + '-field-4'">填写安装后智能体的展示名称。</FieldTip>
        </ElFormItem>
        <ElAlert
          v-if="templateId && !templateDetail && !busy"
          title="尚未读到模板详情，请点击“重新读取模板”，加载完成后再配置安装资源。"
          type="warning"
          :closable="false"
        /><ElButton v-if="templateId && !templateDetail && !busy" @click="loadTemplate"
          >重新读取模板</ElButton
        >
        <ElEmpty
          v-if="!templates.length && !busy && !pendingInstallation"
          description="暂无可安装模板"
        >
          <ElButton
            v-permission="['foundation.agents.create']"
            type="primary"
            @click="createFromEmptyTemplates"
          >
            新建智能体
          </ElButton>
        </ElEmpty>
        <p>{{ templateDetail?.description }}</p>
        <ElFormItem v-for="slot in resourceSlots" :key="slot.key" :label="slot.name || slot.key"
          ><ElSelect
            v-described-by="formTipId + '-field-5' + '-' + encodeURIComponent(String(slot.key))"
            :aria-describedby="formTipId + '-field-5' + '-' + encodeURIComponent(String(slot.key))"
            v-model="slot.definitionId"
            @change="
              () => {
                slot.deploymentKey = undefined
                slot.workflowInstanceId = undefined
              }
            "
            filterable
            placeholder="选择能力（跟随当前发布）"
            ><ElOption
              v-for="definition in definitions.filter((v) => v.capabilityType === slot.type)"
              :key="definition.id"
              :label="definition.name"
              :value="definition.id" /></ElSelect
          ><ElSelect
            v-described-by="formTipId + '-field-5' + '-' + encodeURIComponent(String(slot.key))"
            :aria-describedby="formTipId + '-field-5' + '-' + encodeURIComponent(String(slot.key))"
            v-if="
              slot.type === 'TOOL' &&
              (matchingDeployments(slot.definitionId).length > 1 ||
                (!!slot.deploymentKey &&
                  !matchingDeployments(slot.definitionId).some(
                    (item) => item.deploymentKey === slot.deploymentKey
                  )))
            "
            v-model="slot.deploymentKey"
            placeholder="选择工具部署"
            ><ElOption
              v-for="d in matchingDeployments(slot.definitionId)"
              :key="d.deploymentKey"
              :value="d.deploymentKey"
              :label="d.deploymentKey" /></ElSelect
          ><ElSelect
            v-described-by="formTipId + '-field-5' + '-' + encodeURIComponent(String(slot.key))"
            :aria-describedby="formTipId + '-field-5' + '-' + encodeURIComponent(String(slot.key))"
            v-if="
              slot.type === 'WORKFLOW' &&
              (matchingInstances(slot.definitionId).length > 1 ||
                (!!slot.workflowInstanceId &&
                  !matchingInstances(slot.definitionId).some(
                    (item) => item.id === slot.workflowInstanceId
                  )))
            "
            v-model="slot.workflowInstanceId"
            placeholder="选择工作流安装实例"
            ><ElOption
              v-for="instance in matchingInstances(slot.definitionId)"
              :key="instance.id"
              :value="instance.id"
              :label="instance.workflowId + ' / ' + instance.instanceId"
          /></ElSelect>
          <p
            v-if="
              slot.definitionId &&
              slot.type === 'TOOL' &&
              matchingDeployments(slot.definitionId).length === 1 &&
              (!slot.deploymentKey ||
                matchingDeployments(slot.definitionId)[0]?.deploymentKey === slot.deploymentKey)
            "
            class="admin-muted"
          >
            将使用服务：{{ matchingDeployments(slot.definitionId)[0]?.deploymentKey }}
          </p>
          <p
            v-if="
              slot.definitionId &&
              slot.type === 'WORKFLOW' &&
              matchingInstances(slot.definitionId).length === 1 &&
              (!slot.workflowInstanceId ||
                matchingInstances(slot.definitionId)[0]?.id === slot.workflowInstanceId)
            "
            class="admin-muted"
          >
            将使用实例：{{ matchingInstances(slot.definitionId)[0]?.workflowId }} /
            {{ matchingInstances(slot.definitionId)[0]?.instanceId }}
          </p>
          <p
            v-if="
              slot.definitionId &&
              ((slot.type === 'TOOL' && !matchingDeployments(slot.definitionId).length) ||
                (slot.type === 'WORKFLOW' && !matchingInstances(slot.definitionId).length))
            "
            class="admin-muted"
          >
            尚无可用实例，请先完成接入。
          </p>
          <p
            v-if="
              (slot.deploymentKey &&
                !matchingDeployments(slot.definitionId).some(
                  (item) => item.deploymentKey === slot.deploymentKey
                )) ||
              (slot.workflowInstanceId &&
                !matchingInstances(slot.definitionId).some(
                  (item) => item.id === slot.workflowInstanceId
                ))
            "
            role="status"
            class="admin-muted"
          >
            原选择已不可用，请重新选择服务或实例。
          </p>
          <RouterLink
            v-if="slot.type === 'TOOL'"
            v-permission="['foundation.capabilities.read']"
            to="/admin/tools"
            target="_blank"
            rel="noopener noreferrer"
            >接入工具（新标签页）</RouterLink
          >
          <RouterLink
            v-if="slot.type === 'WORKFLOW'"
            v-permission="['foundation.workflows.read']"
            to="/admin/workflows"
            target="_blank"
            rel="noopener noreferrer"
            >接入工作流（新标签页）</RouterLink
          >
          <FieldTip :id="formTipId + '-field-5' + '-' + encodeURIComponent(String(slot.key))"
            >绑定该能力槽；只有一个工具服务或工作流实例时自动使用，多个实例时请选择。</FieldTip
          > </ElFormItem
        ><ElButton
          v-if="templateDetail && (resourceSlots.length || delegateSlots.length)"
          :disabled="busy || !!pendingInstallation"
          @click="refreshTemplateResources"
          >刷新可用资源</ElButton
        >
        <ElFormItem v-for="slot in delegateSlots" :key="slot.key" :label="slot.name || slot.key"
          ><ElSelect
            v-described-by="formTipId + '-field-6' + '-' + encodeURIComponent(String(slot.key))"
            :aria-describedby="formTipId + '-field-6' + '-' + encodeURIComponent(String(slot.key))"
            v-model="slot.agentId"
            ><ElOption
              v-for="agent in data?.items ?? []"
              :key="agent.id"
              :label="agent.name"
              :value="agent.id"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-6' + '-' + encodeURIComponent(String(slot.key))"
            >选择承担该槽职责的智能体，按模板要求完成绑定。</FieldTip
          >
        </ElFormItem></ElForm
      ><template #footer
        ><ElButton v-if="pendingInstallation" :loading="busy" @click="install">继续原安装</ElButton
        ><ElButton @click="closeDrawer1" :disabled="busy || templateSwitching">{{
          pendingInstallation ? '关闭核对' : '取消'
        }}</ElButton
        ><ElButton
          v-permission="['foundation.agents.create']"
          type="primary"
          :loading="busy"
          :disabled="!templateDetail || busy || templateSwitching || !!pendingInstallation"
          @click="install"
          >安装并配置</ElButton
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
import { statusLabel } from '@skillforge/shared/admin'
import type { FormInstance } from 'element-plus'
import { computed, nextTick, onMounted, onScopeDispose, ref, shallowRef, useId, watch } from 'vue'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
defineProps<{ tenantId?: string; embedded?: boolean }>()
import {
  AdminPage,
  useTask,
  useResource,
  useUnsavedChanges,
  useDrawerClose
} from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import {
  api,
  type AgentItem,
  type AgentTemplateInstallRequest,
  type AgentTemplate,
  type CapabilityVersion,
  type CapabilityDefinition,
  type CapabilityDeployment,
  type BuilderWorkflowInstance
} from '../../api'
const auth = useAuthStore()
const router = useRouter(),
  { data, loading, error: loadError, reload, invalidate } = useResource(() => api.agents.list())
onMounted(() => {
  void reload()
  void (async () => {
    if (!auth.user?.permissions.includes('foundation.agents.read')) return
    if (pendingCreation.value) await checkAgentCreation(true)
    if (active && !createdAgent.value && pendingInstallationId.value) await checkInstallation(true)
  })()
})
let active = true
onScopeDispose(() => {
  active = false
})
const { busy, error, run } = useTask()
const refreshButton = ref<{ $el: HTMLButtonElement }>()
const createFormRef = ref<FormInstance>()
const query = ref(''),
  draftFilter = ref(''),
  open = ref(false),
  name = ref(''),
  prompt = ref('')
useUnsavedChanges({
  dirty: () =>
    !!pendingInstallationId.value ||
    !!pendingCreation.value ||
    (open.value && !!(name.value || prompt.value)) ||
    templateDraftDirty(),
  busy: () => busy.value || templateSwitching.value,
  leaveMessage: installationLeaveMessage
})
const filtered = computed(() =>
  (data.value?.items ?? []).filter((a) => {
    const match = (a.name + a.agent_key).toLowerCase().includes(query.value.trim().toLowerCase())
    return (
      match &&
      (!draftFilter.value ||
        (draftFilter.value === 'pending' && a.has_draft_changes) ||
        (draftFilter.value === 'unpublished' && !a.published_revision) ||
        (draftFilter.value === 'published' && !!a.published_revision))
    )
  })
)
function openCreate() {
  if (pendingCreation.value || creationStorageError.value || createdAgent.value) return
  error.value = ''
  name.value = ''
  prompt.value = ''
  createFormRef.value?.clearValidate()
  open.value = true
}
async function create() {
  if (pendingCreation.value || creationStorageError.value || createdAgent.value) return
  const identity = auth.identityVersion
  const created = await run(async () => {
    if (!(await createFormRef.value?.validate().catch(() => false))) return
    if (!name.value.trim()) throw Error('请输入智能体名称')
    if (!active || identity !== auth.identityVersion) return
    const operation = { requestId: crypto.randomUUID(), name: name.value.trim() }
    const storageKey = creationKey()
    if (!storageKey) throw Error('当前身份不可用，尚未发送创建请求')
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(operation))
    } catch {
      creationStorageError.value = '无法保存创建记录，尚未发送创建请求。'
      throw Error(creationStorageError.value)
    }
    pendingCreation.value = operation
    let receipt
    try {
      receipt = await api.agents.createWithReceipt(operation.requestId, {
        name: name.value.trim(),
        system_prompt: prompt.value
      })
    } catch (reason) {
      if (!active || identity !== auth.identityVersion) throw 'cancel'
      if (reason instanceof HttpClientError && reason.code === 'AGENT_CREATE_REJECTED')
        clearCreation()
      throw reason
    }
    if (
      !active ||
      identity !== auth.identityVersion ||
      pendingCreation.value?.requestId !== operation.requestId
    )
      return
    if (!acceptCreation(receipt, operation.requestId)) throw Error('创建回执不完整，请核对原请求')
    open.value = false
    name.value = ''
    prompt.value = ''
    message.success('智能体已创建')
    return receipt.agent_id
  })
  if (created) await navigateCreatedAgent(created)
}
const pendingCreation = ref<{ requestId: string; name: string }>()
const creationStorageError = ref('')
const createdAgent = ref('')
const creationKey = () =>
  auth.user?.tenant_id && auth.user?.sub
    ? `skillforge.agent-create:${encodeURIComponent(JSON.stringify([auth.user.tenant_id, auth.user.sub]))}`
    : ''
watch(
  () => [auth.identityVersion, auth.user?.tenant_id, auth.user?.sub],
  () => {
    pendingCreation.value = undefined
    creationStorageError.value = ''
    createdAgent.value = ''
    const storageKey = creationKey()
    if (!storageKey) return
    try {
      const raw = sessionStorage.getItem(storageKey)
      if (!raw) return
      const value = JSON.parse(raw) as { requestId: string; name: string }
      if (
        !value.name ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.requestId)
      )
        throw Error('invalid')
      pendingCreation.value = { requestId: value.requestId, name: value.name }
    } catch {
      creationStorageError.value = '无法读取原创建记录，请恢复浏览器存储后重新进入。'
    }
  },
  { immediate: true, flush: 'sync' }
)
function clearCreation() {
  try {
    sessionStorage.removeItem(creationKey())
    pendingCreation.value = undefined
    creationStorageError.value = ''
    return true
  } catch {
    creationStorageError.value = '无法清除原创建记录，请恢复浏览器存储后重新核对。'
    return false
  }
}
function acceptCreation(
  value: { request_id: string; agent_id: string } | undefined,
  requestId: string
) {
  if (
    !value ||
    value.request_id !== requestId ||
    !/^[1-9][0-9]*$/.test(value.agent_id) ||
    !clearCreation()
  )
    return false
  createdAgent.value = value.agent_id
  return true
}
async function navigateCreatedAgent(id: string) {
  try {
    const failure = await router.push('/admin/agents/' + id)
    if (failure) message.warning('智能体已创建，请使用原智能体入口继续配置。')
  } catch {
    message.warning('智能体已创建，请使用原智能体入口继续配置。')
  }
}
async function checkAgentCreation(automatic = false) {
  const operation = pendingCreation.value,
    identity = auth.identityVersion
  if (!operation) return
  const receipt = await run(async () => {
    const value = await api.agents.creationResult(operation.requestId)
    if (
      !active ||
      identity !== auth.identityVersion ||
      pendingCreation.value?.requestId !== operation.requestId
    )
      return
    if (!value) {
      if (!automatic) message.warning('尚未查到原创建回执，结果仍待核对')
      return
    }
    if (!acceptCreation(value, operation.requestId)) throw Error('创建回执不完整，请核对原请求')
    open.value = false
    message.success('智能体创建已确认')
    return value
  })
  if (receipt) await navigateCreatedAgent(receipt.agent_id)
}
async function disable(row: AgentItem) {
  if (
    row.definition_managed ||
    row.status !== 'published' ||
    !auth.user?.permissions.includes('foundation.agents.update')
  )
    return
  const identity = auth.identityVersion
  await run(async () => {
    if (
      !(await confirm({
        title: '停用智能体',
        confirmText: '停用',
        danger: true,
        content: `停用“${row.name}”后将拒绝新的执行，发布历史与会话记录保留。可在配置页重新发布后恢复使用。`
      }))
    )
      return
    if (
      !active ||
      identity !== auth.identityVersion ||
      !auth.user?.permissions.includes('foundation.agents.update')
    )
      return
    let updated: AgentItem
    try {
      updated = await api.agents.update(row.id, { status: 'disabled' })
    } catch (reason) {
      if (!active || identity !== auth.identityVersion) {
        if (reason instanceof HttpClientError) reason.claimFeedback()
        throw 'cancel'
      }
      throw reason
    }
    if (!active || identity !== auth.identityVersion) return
    invalidate()
    if (data.value)
      data.value = {
        ...data.value,
        items: data.value.items.map((item) => (item.id === row.id ? updated : item))
      }
    message.success('智能体已停用，可在配置页重新发布后恢复使用')
  })
}
async function remove(row: AgentItem) {
  if (row.definition_managed || row.status !== 'draft' || row.published_revision_id) return
  if (
    await confirm({
      title: '删除智能体',
      confirmText: '删除智能体',
      danger: true,
      content: '确定删除“' + row.name + '”？'
    })
  ) {
    const deleted = await run(async () => {
      await api.agents.remove(row.id)
      if (!active) return
      invalidate()
      if (data.value?.items.some((item) => item.id === row.id)) {
        data.value = {
          ...data.value,
          total: data.value.total - 1,
          items: data.value.items.filter((item) => item.id !== row.id)
        }
      }
      const refreshed = await reload()
      if (!active) return
      if (refreshed) message.success('智能体已删除')
      else {
        loadError.value = ''
        message.warning('智能体已删除，但列表刷新失败。请点击“刷新”重试。')
      }
      return true
    })
    if (deleted && active) {
      await nextTick()
      if (active && document.activeElement === document.body) refreshButton.value?.$el.focus()
    }
  }
}
const pendingInstallation = shallowRef<AgentTemplateInstallRequest>()
const pendingInstallationId = ref('')
const installationStorageError = ref('')
const installationStorageKey = () =>
  auth.user?.tenant_id && auth.user?.sub
    ? `skillforge.agent-template-install:${encodeURIComponent(JSON.stringify([auth.user.tenant_id, auth.user.sub]))}`
    : ''
watch(
  () => [auth.identityVersion, auth.user?.tenant_id, auth.user?.sub],
  () => {
    pendingInstallation.value = undefined
    pendingInstallationId.value = ''
    installationStorageError.value = ''
    const key = installationStorageKey()
    if (!key) return
    try {
      const requestId = sessionStorage.getItem(key)
      if (requestId && !/^[a-zA-Z0-9_-]{1,128}$/.test(requestId)) throw Error('invalid')
      pendingInstallationId.value = requestId ?? ''
    } catch {
      installationStorageError.value = '无法读取原安装请求，请恢复浏览器存储后重新进入。'
    }
  },
  { immediate: true, flush: 'sync' }
)
function clearInstallation() {
  try {
    sessionStorage.removeItem(installationStorageKey())
    pendingInstallation.value = undefined
    pendingInstallationId.value = ''
    installationStorageError.value = ''
    return true
  } catch {
    installationStorageError.value = '无法清除原安装记录，请恢复浏览器存储后重新核对。'
    return false
  }
}
async function checkInstallation(automatic = false) {
  const requestId = pendingInstallationId.value,
    identity = auth.identityVersion
  if (!requestId) return
  const receipt = await run(async () => {
    const value = await api.agents.installationResult(requestId)
    if (!active || identity !== auth.identityVersion || pendingInstallationId.value !== requestId)
      return
    if (!value) {
      if (!automatic) message.warning('尚未查到原安装回执，结果仍待核对')
      return
    }
    if (value.request_id !== requestId || !/^[1-9][0-9]*$/.test(value.agent_id))
      throw Error('原安装回执不完整，请稍后核对')
    if (!clearInstallation()) return
    templateOpen.value = false
    createdAgent.value = value.agent_id
    message.success('已确认模板安装')
    return value
  })
  if (receipt) await navigateCreatedAgent(receipt.agent_id)
}
const templateSwitching = ref(false)
const templateOpen = ref(false),
  templates = ref<AgentTemplate[]>([]),
  templateId = ref(''),
  templateName = ref(''),
  automaticTemplateId = ref(''),
  automaticTemplateName = ref(''),
  loadedTemplateId = ref(''),
  attemptedTemplateId = ref(''),
  templateDetail = ref<AgentTemplate>(),
  versions = ref<Array<Pick<CapabilityVersion, 'id' | 'definitionId' | 'capabilityType'>>>([]),
  definitions = ref<CapabilityDefinition[]>([])
const deployments = ref<CapabilityDeployment[]>([]),
  instances = ref<BuilderWorkflowInstance[]>([])
const resourceSlots = ref<
    Array<{
      key: string
      name: string
      type: string
      definitionId?: string
      deploymentKey?: string
      workflowInstanceId?: string
    }>
  >([]),
  delegateSlots = ref<Array<{ key: string; name: string; agentId?: string }>>([])
function templateDraftDirty() {
  if (!templateOpen.value) return false
  if (!automaticTemplateId.value || templateId.value !== automaticTemplateId.value)
    return !!(templateId.value || templateName.value)
  return (
    templateName.value !== automaticTemplateName.value ||
    resourceSlots.value.some(
      (slot) => slot.definitionId || slot.deploymentKey || slot.workflowInstanceId
    ) ||
    delegateSlots.value.some((slot) => slot.agentId)
  )
}
watch(
  () => [auth.identityVersion, auth.user?.tenant_id, auth.user?.sub],
  () => {
    templateOpen.value = false
    open.value = false
    templateId.value = ''
    templateName.value = ''
    automaticTemplateId.value = ''
    automaticTemplateName.value = ''
    loadedTemplateId.value = ''
    attemptedTemplateId.value = ''
    templateDetail.value = undefined
    resourceSlots.value = []
    delegateSlots.value = []
    name.value = ''
    prompt.value = ''
  },
  { flush: 'sync' }
)
async function openTemplate() {
  if (createdAgent.value || (pendingInstallationId.value && !pendingInstallation.value)) return
  if (pendingInstallation.value) {
    templateOpen.value = true
    return
  }
  const identity = auth.identityVersion
  const available = await run(async () => {
    templateId.value = ''
    templateName.value = ''
    automaticTemplateId.value = ''
    automaticTemplateName.value = ''
    loadedTemplateId.value = ''
    attemptedTemplateId.value = ''
    templateDetail.value = undefined
    resourceSlots.value = []
    delegateSlots.value = []
    const items = (await api.agents.templates()).items
    if (!active || auth.identityVersion !== identity) return
    templates.value = items
    templateOpen.value = true
    return items
  })
  if (
    available?.length === 1 &&
    active &&
    auth.identityVersion === identity &&
    templateOpen.value
  ) {
    automaticTemplateId.value = available[0]!.id
    templateId.value = automaticTemplateId.value
    await loadTemplate()
  }
}
function createFromEmptyTemplates() {
  if (busy.value || pendingInstallation.value || templates.value.length) return
  templateOpen.value = false
  openCreate()
}
async function selectTemplate() {
  const selected = templateId.value
  const identity = auth.identityVersion
  if (templateDetail.value && selected === loadedTemplateId.value) return
  const changed = templateDetail.value
    ? templateName.value !== templateDetail.value.name ||
      resourceSlots.value.some(
        (slot) => slot.definitionId || slot.deploymentKey || slot.workflowInstanceId
      ) ||
      delegateSlots.value.some((slot) => slot.agentId)
    : !!templateName.value
  if (changed) {
    templateSwitching.value = true
    let accepted: boolean
    try {
      accepted = await confirm({
        title: '切换模板',
        content: '切换后将清除当前名称和资源绑定，是否继续？',
        confirmText: '切换模板',
        cancelText: '继续编辑'
      })
    } finally {
      templateSwitching.value = false
    }
    if (!active || auth.identityVersion !== identity || !templateOpen.value) return
    if (!accepted) {
      templateId.value = attemptedTemplateId.value
      return
    }
  }
  if (selected !== attemptedTemplateId.value) templateName.value = ''
  if (selected !== automaticTemplateId.value) {
    automaticTemplateId.value = ''
    automaticTemplateName.value = ''
  }
  await loadTemplate()
}
async function loadTemplate() {
  const identity = auth.identityVersion
  const selected = templateId.value
  attemptedTemplateId.value = selected
  templateDetail.value = undefined
  resourceSlots.value = []
  delegateSlots.value = []
  await run(async () => {
    const detail = await api.agents.templateDetail(selected)
    if (
      !active ||
      auth.identityVersion !== identity ||
      !templateOpen.value ||
      templateId.value !== selected
    )
      return
    const definition = detail.definition as {
      resource_slots?: Array<{ key: string; label?: string; capability_type: string }>
      delegation_slots?: Array<{ key: string; label?: string }>
    }
    const slots = (definition.resource_slots ?? []).map((s) => ({
      key: s.key,
      name: s.label ?? s.key,
      type: s.capability_type
    }))
    const delegates = (definition.delegation_slots ?? []).map((s) => ({
      key: s.key,
      name: s.label ?? s.key
    }))
    const resources = await readTemplateResources(slots.map((slot) => slot.type))
    if (
      !active ||
      auth.identityVersion !== identity ||
      !templateOpen.value ||
      templateId.value !== selected
    )
      return
    const keepEditedName =
      selected === automaticTemplateId.value
        ? templateName.value !== automaticTemplateName.value
        : !!templateName.value && selected !== loadedTemplateId.value
    if (!keepEditedName) templateName.value = detail.name
    if (selected === automaticTemplateId.value) automaticTemplateName.value = detail.name
    resourceSlots.value = slots
    delegateSlots.value = delegates
    definitions.value = resources.definitions
    versions.value = resources.versions
    deployments.value = resources.deployments
    instances.value = resources.instances
    templateDetail.value = detail
    loadedTemplateId.value = selected
  })
}
async function readTemplateResources(types: string[]) {
  if (!types.length) return { definitions: [], versions: [], deployments: [], instances: [] }
  const definitions = (await api.capabilities.listDefinitions({ status: 'PUBLISHED' })).filter(
    (definition) => definition.defaultVersionId && types.includes(definition.capabilityType)
  )
  const [versions, currentOptions, deployments, workflows] = await Promise.all([
    Promise.all(
      definitions
        .filter((definition) => definition.capabilityType !== 'KNOWLEDGE_SOURCE')
        .map((definition) => api.capabilities.versions(definition.id, 'PUBLISHED'))
    ),
    definitions.some((definition) => definition.capabilityType === 'KNOWLEDGE_SOURCE')
      ? api.capabilities.listRequirementOptions('KNOWLEDGE_SOURCE')
      : Promise.resolve([]),
    types.includes('TOOL') ? api.capabilities.deployments('ACTIVE') : Promise.resolve([]),
    types.includes('WORKFLOW') ? api.workflowDrafts.instances() : Promise.resolve({ items: [] })
  ])
  const current = currentOptions
    .filter((option) =>
      definitions.some(
        (definition) =>
          definition.id === option.id &&
          definition.capabilityType === 'KNOWLEDGE_SOURCE' &&
          definition.defaultVersionId === option.versionId
      )
    )
    .map((option) => ({
      id: option.versionId,
      definitionId: option.id,
      capabilityType: 'KNOWLEDGE_SOURCE' as const
    }))
  return {
    definitions,
    versions: [...versions.flat(), ...current],
    deployments,
    instances: workflows.items
  }
}
async function refreshTemplateResources() {
  if (busy.value || pendingInstallation.value || !templateDetail.value) return
  const owner = auth.identityVersion
  const template = templateId.value
  await run(async () => {
    const [resources, agents] = await Promise.all([
      readTemplateResources(resourceSlots.value.map((slot) => slot.type)),
      delegateSlots.value.length ? api.agents.list() : undefined
    ])
    if (
      !active ||
      auth.identityVersion !== owner ||
      !templateOpen.value ||
      templateId.value !== template ||
      pendingInstallation.value
    )
      return
    definitions.value = resources.definitions
    versions.value = resources.versions
    deployments.value = resources.deployments
    instances.value = resources.instances
    if (agents) data.value = agents
    message.success('可用资源已刷新，请核对槽位选择')
  })
}
function templateMount(
  slot: { type: string; deploymentKey?: string; workflowInstanceId?: string },
  version: Pick<CapabilityVersion, 'definitionId' | 'capabilityType'>
): Record<string, unknown> {
  const base = {
    capability_type: version.capabilityType,
    capability_definition_id: version.definitionId
  }
  if (slot.type === 'SKILL')
    return { ...base, skill_config: { activation_mode: 'ON_DEMAND', token_budget: 2000 } }
  if (slot.type === 'KNOWLEDGE_SOURCE')
    return { ...base, knowledge_config: { mode: 'FOLLOW_ACTIVE', pinned_revision_id: null } }
  if (slot.type === 'TOOL') {
    const matches = matchingDeployments(version.definitionId)
    if (slot.deploymentKey && !matches.some((item) => item.deploymentKey === slot.deploymentKey))
      throw Error('原工具服务已不可用，请重新选择')
    const d =
      matches.length === 1
        ? matches[0]
        : matches.find((item) => item.deploymentKey === slot.deploymentKey)
    if (!d) throw Error('请选择匹配的工具部署')
    return {
      ...base,
      tool_config: { tool_deployment_id: d.deploymentKey }
    }
  }
  const matches = matchingInstances(version.definitionId)
  if (slot.workflowInstanceId && !matches.some((item) => item.id === slot.workflowInstanceId))
    throw Error('原工作流实例已不可用，请重新选择')
  const instance =
    matches.length === 1 ? matches[0] : matches.find((item) => item.id === slot.workflowInstanceId)
  if (!instance?.installationRevision) throw Error('请选择匹配的工作流实例')
  return {
    ...base,
    workflow_config: {
      workflow_instance_id: instance.id
    }
  }
}
function versionDefinition(versionId: string | null) {
  return versions.value.find((v) => v.id === versionId)?.definitionId
}
function matchingDeployments(definitionId?: string) {
  return definitionId
    ? deployments.value.filter((d) => versionDefinition(d.toolVersionId) === definitionId)
    : []
}
function matchingInstances(definitionId?: string) {
  return definitionId
    ? instances.value.filter(
        (i) => i.installationRevision && versionDefinition(i.capabilityVersionId) === definitionId
      )
    : []
}
async function install() {
  if (
    templateSwitching.value ||
    installationStorageError.value ||
    (pendingInstallationId.value && !pendingInstallation.value)
  )
    return
  const identity = auth.identityVersion
  const installed = await run(async () => {
    if (!templateId.value) throw Error('请选择模板')
    if (!templateDetail.value) throw Error('请先成功读取模板及可用资源')
    if (!templateName.value.trim()) throw Error('请输入智能体名称')
    const command = pendingInstallation.value ?? {
      template_id: templateId.value,
      name: templateName.value,
      request_id: crypto.randomUUID(),
      resource_bindings: resourceSlots.value
        .filter((s) => s.definitionId)
        .map((s) => {
          const definition = definitions.value.find((d) => d.id === s.definitionId)
          const version = versions.value.find((v) => v.id === definition?.defaultVersionId)
          if (!version) throw Error('请选择已发布能力')
          return { slot_key: s.key, mount: templateMount(s, version) }
        }),
      delegation_bindings: delegateSlots.value
        .filter((s) => s.agentId)
        .map((s) => ({ slot_key: s.key, target_agent_id: s.agentId! }))
    }
    let result
    const retry = !!pendingInstallation.value
    if (!retry) {
      const key = installationStorageKey()
      if (!key) throw Error('当前身份不可用，尚未发送安装请求')
      try {
        sessionStorage.setItem(key, command.request_id)
      } catch {
        installationStorageError.value = '无法保存原安装请求，尚未发送安装请求。'
        throw Error(installationStorageError.value)
      }
      pendingInstallationId.value = command.request_id
    }
    try {
      // 同一请求 ID 和完整命令按服务端幂等契约返回原安装结果。
      result = await api.agents.installTemplate(command)
      if (!active || identity !== auth.identityVersion) return
    } catch (reason) {
      if (!active || identity !== auth.identityVersion) throw 'cancel'
      const rejected = reason instanceof HttpClientError && reason.code === 'AGENT_INSTALL_REJECTED'
      // 已有未知结果只能由成功回执解除，后续拒绝不改写原请求事实。
      if (retry || !rejected) pendingInstallation.value = command
      else clearInstallation()
      throw reason
    }
    if (!clearInstallation()) return
    templateOpen.value = false
    createdAgent.value = result.agent.id
    message.success('模板已安装')
    return result
  })
  if (installed) await navigateCreatedAgent(installed.agent.id)
}

const { requestClose: closeDrawer0 } = useDrawerClose({
  open: () => open.value,
  busy: () => busy.value,
  dirty: () => open.value && !!(name.value || prompt.value),
  close: () => {
    open.value = false
  }
})
function installationLeaveMessage() {
  const requestId = pendingInstallationId.value
  return requestId
    ? `安装请求 ${requestId} 的结果尚未确认。离开不会撤销已提交的安装，也不能将结果当作失败再次安装。请先保留原请求 ID，用于核对原安装结果。`
    : undefined
}
async function closeDrawer1() {
  if (busy.value || templateSwitching.value) return
  const content = installationLeaveMessage()
  if (!content) return closeTemplateDraft()
  const accepted = await confirm({
    title: '关闭安装核对',
    content,
    confirmText: '保留原请求并关闭',
    cancelText: '继续核对'
  })
  if (accepted && !busy.value) templateOpen.value = false
}
const { requestClose: closeTemplateDraft } = useDrawerClose({
  open: () => templateOpen.value,
  busy: () => busy.value,
  dirty: () => !pendingInstallation.value && templateDraftDirty(),
  close: () => {
    templateOpen.value = false
  }
})

const formTipId = useId()
</script>
