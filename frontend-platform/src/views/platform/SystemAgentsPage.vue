<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { useActionPermissions } from '../../composables/actionPermissions'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { message } from '@skillforge/shared/feedback'
import { guardFormEnter } from '@skillforge/shared/admin'
import { computed, onMounted, onScopeDispose, reactive, ref, useId, watch } from 'vue'
import { type FormInstance } from 'element-plus'
import { useRouter } from 'vue-router'
import {
  AdminPage,
  statusLabel,
  useDrawerClose,
  useTask,
  useResource
} from '@skillforge/shared/admin'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { useAuthStore } from '../../stores/auth'
import { api, type SystemAgentView, type SystemAgentExecutionMode } from '../../api'
const { allowed, checked } = useActionPermissions()
const auth = useAuthStore()
const actor = computed(() => auth.user?.sub ?? '')
const pending = ref<{ requestId: string; agentKey: string; name: string }>()
const storageError = ref('')
const storageKey = (subject: string) => `skillforge.system-agent-create:${subject}`
let disposed = false
onScopeDispose(() => {
  disposed = true
})
watch(
  () => auth.identityVersion,
  () => {
    pending.value = undefined
    storageError.value = ''
    if (!actor.value) return
    try {
      const raw = sessionStorage.getItem(storageKey(actor.value))
      if (!raw) return
      const item = JSON.parse(raw) as { requestId: string; agentKey: string; name: string }
      if (
        !item ||
        typeof item.name !== 'string' ||
        typeof item.agentKey !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(item.requestId)
      )
        throw Error('invalid')
      pending.value = item
    } catch {
      storageError.value = '无法读取原创建记录，请恢复浏览器存储后重新进入。'
    }
  },
  { immediate: true, flush: 'sync' }
)
const modelOptions = useResource((signal) => api.admin.models.list({ signal, silent: true }))
const formRef = ref<FormInstance>()
const query = reactive({ text: '', status: '', mode: '' })
const router = useRouter(),
  load = useTask(),
  action = useTask(),
  rows = ref<SystemAgentView[]>([]),
  open = ref(false)
const form = reactive({
  agent_key: '',
  name: '',
  description: '',
  execution_mode: 'AGENT_RUN' as SystemAgentExecutionMode,
  system_prompt: '',
  model_config_id: ''
})
const createBaseline = ref('')
watch(
  () => auth.identityVersion,
  () => {
    open.value = false
    Object.assign(form, {
      agent_key: '',
      name: '',
      description: '',
      execution_mode: 'AGENT_RUN',
      system_prompt: '',
      model_config_id: ''
    })
    modelOptions.invalidate()
    modelOptions.data.value = undefined
    modelOptions.error.value = ''
  },
  { flush: 'sync' }
)
function startCreate() {
  if (
    action.busy.value ||
    open.value ||
    pending.value ||
    storageError.value ||
    !allowed('foundation.system_agents.create')
  )
    return
  action.error.value = ''
  Object.assign(form, {
    agent_key: 'agent-' + crypto.randomUUID(),
    name: '',
    description: '',
    execution_mode: 'AGENT_RUN',
    system_prompt: '',
    model_config_id: ''
  })
  createBaseline.value = JSON.stringify(form)
  open.value = true
  if (allowed('foundation.system_agents.update') && allowed('foundation.models.read'))
    void modelOptions.reload()
}
const filtered = computed(() =>
  rows.value.filter(
    (row) =>
      (!query.text ||
        `${row.agent_key} ${row.draft.name}`.toLowerCase().includes(query.text.toLowerCase())) &&
      (!query.status || row.status === query.status) &&
      (!query.mode || row.execution_mode === query.mode)
  )
)
function reload() {
  void load.run(async () => {
    rows.value = await api.systemAgents.list()
  })
}
function save() {
  if (!allowed('foundation.system_agents.create') || pending.value || storageError.value) return
  void action.run(async () => {
    await formRef.value?.validate()
    if (!form.agent_key.trim() || !form.name.trim()) throw new Error('请填写标识和名称')
    const subject = actor.value,
      identity = auth.identityVersion
    if (!subject) throw Error('当前身份不可用，请重新登录')
    if ((form.system_prompt || form.model_config_id) && !allowed('foundation.system_agents.update'))
      throw Error('当前账号没有更新系统智能体权限')
    const operation = { requestId: crypto.randomUUID(), agentKey: form.agent_key, name: form.name }
    try {
      sessionStorage.setItem(storageKey(subject), JSON.stringify(operation))
    } catch {
      storageError.value = '无法保存创建记录，请恢复浏览器存储后重试。'
      return
    }
    const payload = {
      agent_key: form.agent_key,
      name: form.name,
      description: form.description,
      execution_mode: form.execution_mode,
      ...(form.system_prompt ? { system_prompt: form.system_prompt } : {}),
      ...(form.model_config_id ? { model_config_id: form.model_config_id } : {})
    }
    let result
    try {
      result = await checked('foundation.system_agents.create', () =>
        api.systemAgents.createWithReceipt(operation.requestId, payload)
      )
      if (disposed || actor.value !== subject || auth.identityVersion !== identity) return
      if (
        !result ||
        result.request_id !== operation.requestId ||
        typeof result.agent_id !== 'string' ||
        !result.agent_id
      )
        throw Error('创建回执不完整，请核对原请求')
      sessionStorage.removeItem(storageKey(subject))
    } catch (reason) {
      if (!disposed && actor.value === subject && auth.identityVersion === identity) {
        if (reason instanceof HttpClientError && reason.code === 'SYSTEM_AGENT_CREATE_REJECTED') {
          try {
            sessionStorage.removeItem(storageKey(subject))
          } catch {
            storageError.value = '创建已被拒绝，但无法清理本地记录；请恢复浏览器存储。'
          }
        } else pending.value = operation
      }
      throw reason
    }
    open.value = false
    message.success('系统智能体已创建，请配置草稿并发布后用于正式执行')
    reload()
    try {
      const failure = await router.push('/platform/system-agents/' + result.agent_id)
      if (failure) message.warning('智能体已创建，详情导航失败，请从列表进入。')
    } catch {
      message.warning('智能体已创建，详情导航失败，请从列表进入。')
    }
  })
}
function checkCreation() {
  if (!pending.value || !allowed('foundation.system_agents.read')) return
  void action.run(async () => {
    const operation = pending.value!,
      subject = actor.value,
      identity = auth.identityVersion
    const result = await api.systemAgents.createReceipt(operation.requestId)
    if (
      disposed ||
      actor.value !== subject ||
      auth.identityVersion !== identity ||
      pending.value !== operation
    )
      return
    if (
      !result ||
      result.request_id !== operation.requestId ||
      typeof result.agent_id !== 'string' ||
      !result.agent_id
    ) {
      message.warning('尚未查到原创建回执，结果仍待核对')
      return
    }
    sessionStorage.removeItem(storageKey(subject))
    pending.value = undefined
    open.value = false
    message.success('系统智能体创建已确认')
    reload()
    try {
      const failure = await router.push('/platform/system-agents/' + result.agent_id)
      if (failure) message.warning('智能体已创建，详情导航失败，请从列表进入。')
    } catch {
      message.warning('智能体已创建，详情导航失败，请从列表进入。')
    }
  })
}
onMounted(reload)

const { requestClose: openClose } = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value,
  dirty: () => JSON.stringify(form) !== createBaseline.value,
  close: () => {
    open.value = false
  }
})

const formTipId = useId()
</script>

<template>
  <AdminPage title="系统智能体" description="维护平台内部使用的智能体及发布版本">
    <template #extra>
      <ElButton
        v-permission="['foundation.system_agents.create']"
        type="primary"
        :disabled="action.busy.value || !!pending || !!storageError"
        @click="startCreate"
        >新增智能体</ElButton
      >
      <ElButton :loading="load.busy.value" @click="reload">刷新</ElButton>
    </template>
    <FeedbackNotice
      v-if="load.error.value"
      :title="load.error.value"
      :closable="false"
      type="error"
    />
    <section v-if="pending || storageError" class="admin-card" role="status">
      <p v-if="pending">
        系统智能体“{{ pending.name }}”创建结果待核对，请求号：{{
          pending.requestId
        }}。未查到回执不表示创建失败。
      </p>
      <p v-if="storageError">{{ storageError }}</p>
      <ElButton
        v-if="pending"
        :disabled="!allowed('foundation.system_agents.read')"
        :loading="action.busy.value"
        @click="checkCreation"
        >核对创建结果</ElButton
      >
    </section>
    <ElForm
      @keydown.capture="guardFormEnter"
      class="admin-filters filters"
      @submit.prevent="reload"
    >
      <ElInput
        v-model="query.text"
        aria-label="搜索系统智能体"
        clearable
        placeholder="搜索智能体名称或标识"
      />
      <ElSelect v-model="query.status" aria-label="智能体状态" clearable placeholder="全部状态"
        ><ElOption
          v-for="value in ['draft', 'published', 'disabled', 'archived']"
          :key="value"
          :value="value"
          :label="statusLabel(value)"
      /></ElSelect>
      <ElSelect v-model="query.mode" aria-label="执行模式" clearable placeholder="全部执行模式"
        ><ElOption value="AGENT_RUN" label="智能体执行" /><ElOption
          value="MODEL_TASK"
          label="模型任务"
      /></ElSelect>
      <ElButton @click="Object.assign(query, { text: '', status: '', mode: '' })">重置</ElButton>
    </ElForm>
    <ElTable
      table-layout="auto"
      :empty-text="
        load.busy.value
          ? '正在读取系统智能体…'
          : load.error.value
            ? '系统智能体列表尚未读取，请查看上方错误'
            : rows.length
              ? '没有匹配的系统智能体，可调整名称、状态或执行模式'
              : '当前可见范围内没有系统智能体'
      "
      :data="filtered"
      v-loading="load.busy.value"
      style="width: 100%"
    >
      <ElTableColumn prop="name" label="名称" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <RouterLink :to="'/platform/system-agents/' + row.id">{{ row.draft.name }}</RouterLink>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="agent_key" label="标识" min-width="220" show-overflow-tooltip>
      </ElTableColumn>
      <ElTableColumn prop="execution_mode" label="执行模式" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{
          row.execution_mode === 'MODEL_TASK' ? '模型任务' : '智能体执行'
        }}</template>
      </ElTableColumn>
      <ElTableColumn prop="status" label="状态" width="110"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      >
      <ElTableColumn label="工作草稿" width="120"
        ><template #default="{ row }">{{
          JSON.stringify(row.draft) === JSON.stringify(row.published) ? '与发布一致' : '待发布'
        }}</template></ElTableColumn
      >
      <ElTableColumn prop="published_version" label="发布版本" width="120"> </ElTableColumn>
    </ElTable>
    <ElDrawer
      class="admin-drawer"
      direction="rtl"
      append-to-body
      v-model="open"
      title="新增系统智能体"
      :before-close="openClose"
      size="680px"
      :close-on-click-modal="false"
    >
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        :closable="false"
        type="error"
      />
      <div v-if="pending" role="status">
        <p>创建结果待核对，请求号：{{ pending.requestId }}。</p>
        <ElButton
          :disabled="!allowed('foundation.system_agents.read')"
          :loading="action.busy.value"
          @click="checkCreation"
          >核对创建结果</ElButton
        >
      </div>
      <ElForm
        @keydown.capture="guardFormEnter"
        id="agent-create-form"
        ref="formRef"
        :model="form"
        label-position="top"
        :disabled="
          !allowed('foundation.system_agents.create') ||
          action.busy.value ||
          !!pending ||
          !!storageError
        "
        @submit.prevent="save"
      >
        <p class="admin-muted">可在创建时填写模型和提示词；能力可随后配置，创建不会发布。</p>
        <ElFormItem
          label="唯一标识"
          prop="agent_key"
          :rules="[{ required: true, whitespace: true, message: '请输入唯一标识' }]"
        >
          <ElInput
            v-described-by="'agent-key-help'"
            v-model="form.agent_key"
            aria-describedby="agent-key-help"
          />
          <p id="agent-key-help" class="admin-field-help">
            已生成唯一标识，可按集成约定修改；创建后不可修改。
          </p>
        </ElFormItem>
        <ElFormItem
          label="名称"
          prop="name"
          :rules="[{ required: true, whitespace: true, message: '请输入名称' }]"
        >
          <ElInput v-model="form.name" />
        </ElFormItem>
        <ElFormItem label="说明">
          <ElInput
            v-described-by="formTipId + '-field-2'"
            :aria-describedby="formTipId + '-field-2'"
            v-model="form.description"
            type="textarea"
          />

          <FieldTip :id="formTipId + '-field-2'">描述系统智能体的职责和适用范围。</FieldTip>
        </ElFormItem>
        <ElFormItem v-if="allowed('foundation.system_agents.update')" label="系统提示词（可选）">
          <ElInput v-model="form.system_prompt" type="textarea" />
        </ElFormItem>
        <ElFormItem
          v-if="allowed('foundation.system_agents.update') && allowed('foundation.models.read')"
          label="模型（可选）"
        >
          <ElSelect
            v-model="form.model_config_id"
            clearable
            filterable
            :loading="modelOptions.loading.value"
            placeholder="选择可用模型"
          >
            <ElOption
              v-for="model in (modelOptions.data.value ?? []).filter(
                (value) => value.kind === 'LLM' && value.enabled
              )"
              :key="model.id"
              :value="model.id"
              :label="model.name"
            />
          </ElSelect>
          <ElButton
            v-if="modelOptions.error.value"
            :loading="modelOptions.loading.value"
            @click="modelOptions.reload()"
            >重新读取模型</ElButton
          >
        </ElFormItem>
        <ElFormItem label="执行模式">
          <ElSelect
            v-described-by="formTipId + '-field-3'"
            :aria-describedby="formTipId + '-field-3'"
            v-model="form.execution_mode"
          >
            <ElOption value="AGENT_RUN" label="智能体执行" />
            <ElOption value="MODEL_TASK" label="模型任务" />
          </ElSelect>

          <FieldTip :id="formTipId + '-field-3'"
            >智能体执行可多次调用模型与工具；模型任务只调用一次业务模型。</FieldTip
          >
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton :disabled="action.busy.value" @click="openClose()">取消</ElButton>
        <ElButton
          v-permission="['foundation.system_agents.create']"
          type="primary"
          :loading="action.busy.value"
          :disabled="!!pending || !!storageError"
          native-type="submit"
          form="agent-create-form"
          >创建并配置</ElButton
        >
      </template>
    </ElDrawer>
  </AdminPage>
</template>
