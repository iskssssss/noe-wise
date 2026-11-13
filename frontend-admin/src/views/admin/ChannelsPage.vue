<template>
  <AdminPage title="渠道" description="配置接入渠道、智能体路由与访问凭据。"
    ><template #extra
      ><ElButton
        type="primary"
        @click="edit()"
        :disabled="busy || !!pendingCreate || !!storageError"
        >新增渠道</ElButton
      ></template
    ><FeedbackNotice
      v-if="error || loadError || (bindingOpen && agentLoadError)"
      :title="error || loadError || agentLoadError"
      :closable="false"
      type="error"
    />
    <div v-if="pendingCreate || storageError" role="status" class="admin-card">
      <p v-if="pendingCreate">
        渠道创建结果待核对，请求 ID：{{ pendingCreate }}。未查到回执不表示创建失败。
      </p>
      <p v-if="storageError">{{ storageError }}</p>
      <ElButton v-if="pendingCreate && canRead" :loading="busy" @click="checkCreate()"
        >核对创建结果</ElButton
      >
      <p v-else-if="pendingCreate">当前账号没有渠道读取权限，请联系管理员恢复权限后核对原请求。</p>
    </div>
    <div class="admin-toolbar admin-card">
      <ElInput
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="query"
        placeholder="搜索渠道名称"
        clearable
      /><ElSelect v-model="type" clearable placeholder="全部类型" aria-label="渠道类型筛选">
        <ElOption
          v-for="item in channelTypes"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </ElSelect>
      <span class="admin-muted" role="status">{{ filtered.length }} 个渠道</span
      ><ElButton @click="reload" :loading="loading" :disabled="busy">刷新</ElButton>

      <FieldTip :id="formTipId + '-group-0'"
        >按渠道名称和类型组合筛选；清空后可查询全部渠道。</FieldTip
      >
    </div>
    <ElTable
      table-layout="auto"
      :data="filtered"
      v-loading="loading"
      row-key="id"
      stripe
      :empty-text="
        query || type
          ? '没有匹配渠道，清空名称或类型后查看全部'
          : '尚未创建渠道，新增渠道后绑定智能体即可接入'
      "
      ><ElTableColumn min-width="200" show-overflow-tooltip prop="name" label="名称"></ElTableColumn
      ><ElTableColumn min-width="140" show-overflow-tooltip label="类型"
        ><template #default="{ row }">{{ channelTypeLabel(row.type) }}</template></ElTableColumn
      ><ElTableColumn width="128" show-overflow-tooltip label="状态"
        ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
      ><ElTableColumn width="300" class-name="admin-table-actions" prop="actions" label="操作"
        ><template #default="{ row }"
          ><ElButton link @click="edit(row as ChannelItem)" :disabled="busy">编辑</ElButton
          ><ElButton link @click="bind(row as ChannelItem)" :disabled="busy">绑定智能体</ElButton
          ><ElButton link @click="credentials(row as ChannelItem)" :disabled="busy"
            >访问凭据</ElButton
          ><ElButton
            v-permission="['foundation.channels.delete']"
            link
            type="danger"
            @click="remove(row as ChannelItem)"
            :disabled="busy"
            >删除</ElButton
          ></template
        ></ElTableColumn
      ></ElTable
    >
    <ElDrawer
      v-model="open"
      :before-close="closeDrawer0"
      :title="editing ? '编辑渠道' : '新增渠道'"
      size="680px"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      ><FeedbackNotice v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <div v-if="pendingCreate" role="status">
        <p>渠道创建结果待核对，请求 ID：{{ pendingCreate }}。</p>
        <ElButton v-if="canRead" :loading="busy" @click="checkCreate()">核对创建结果</ElButton>
        <p v-else>当前账号没有渠道读取权限，请联系管理员恢复权限后核对原请求。</p>
      </div>
      <ElForm
        ref="formRef"
        :model="form"
        label-position="top"
        @submit.prevent="save"
        :disabled="busy"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElFormItem
          label="渠道名称"
          prop="name"
          :rules="[{ required: true, message: '请输入渠道名称' }]"
          ><ElInput
            v-described-by="formTipId + '-field-0'"
            :aria-describedby="formTipId + '-field-0'"
            v-model="form.name"
          />
          <FieldTip :id="formTipId + '-field-0'"
            >为接入渠道填写易识别的名称。</FieldTip
          > </ElFormItem
        ><ElFormItem label="接入类型"
          ><ElSelect
            v-described-by="formTipId + '-field-1'"
            :aria-describedby="formTipId + '-field-1'"
            v-model="form.channel_type"
            :disabled="!!editing"
            ><ElOption label="网页组件" value="web_widget" /><ElOption
              label="开放 API"
              value="openapi"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-1'"
            >选择渠道接入方式；已创建渠道的类型不可在此修改。</FieldTip
          > </ElFormItem
        ><ElFormItem v-if="editing" label="状态"
          ><ElSelect
            v-described-by="formTipId + '-field-2'"
            :aria-describedby="formTipId + '-field-2'"
            v-model="form.status"
            ><ElOption label="启用" value="active" /><ElOption label="停用" value="disabled"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-2'"
            >选择启用或停用，控制该渠道是否可供接入。</FieldTip
          > </ElFormItem
        ><ElFormItem v-if="form.channel_type === 'web_widget'" label="欢迎语"
          ><ElInput
            v-described-by="formTipId + '-field-3'"
            :aria-describedby="formTipId + '-field-3'"
            v-model="form.welcome"
            type="textarea"
          />
          <FieldTip :id="formTipId + '-field-3'"
            >填写用户进入渠道时看到的欢迎说明。</FieldTip
          > </ElFormItem
        ><ElFormItem v-if="form.channel_type === 'web_widget'" label="主题色"
          ><ElColorPicker
            v-described-by="formTipId + '-field-4'"
            :aria-describedby="formTipId + '-field-4'"
            v-model="form.color"
          />
          <FieldTip :id="formTipId + '-field-4'">选择渠道界面的主题颜色。</FieldTip>
        </ElFormItem>
        <ElFormItem v-if="!editing && canSelectAgents" label="接入智能体">
          <ElSelect
            v-model="selectedAgents"
            multiple
            filterable
            :loading="agentsLoading"
            :aria-describedby="formTipId + '-create-agents'"
            placeholder="选择已发布智能体（可稍后绑定）"
          >
            <ElOption
              v-for="agent in agents"
              :key="agent.id"
              :label="agent.name"
              :value="agent.id"
            />
          </ElSelect>
          <FieldTip :id="formTipId + '-create-agents'"
            >创建时一并绑定；自动选择默认智能体，多选时可自行调整。</FieldTip
          >
          <ElButton v-if="agentLoadError" :loading="agentsLoading" @click="loadAgents"
            >重新读取可选智能体</ElButton
          >
        </ElFormItem>
        <ElFormItem
          v-if="!editing && canSelectAgents && selectedAgents.length > 1"
          label="默认智能体"
        >
          <ElSelect v-model="defaultAgent">
            <ElOption
              v-for="id in selectedAgents"
              :key="id"
              :value="id"
              :label="agents.find((agent) => agent.id === id)?.name || id"
            />
          </ElSelect> </ElFormItem></ElForm
      ><template #footer
        ><ElButton @click="closeDrawer0" :disabled="busy">取消</ElButton
        ><ElButton
          v-permission="[editing ? 'foundation.channels.update' : 'foundation.channels.create']"
          type="primary"
          :loading="busy"
          :disabled="!editing && (!!pendingCreate || !!storageError)"
          @click="save"
          >保存</ElButton
        ></template
      ></ElDrawer
    >
    <ElDrawer
      v-model="bindingOpen"
      :before-close="closeDrawer1"
      :title="'绑定智能体 · ' + (selected?.name ?? '')"
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
      /><ElAlert
        title="每个渠道最多选择一个默认智能体；保存将替换完整绑定集合。"
        type="info"
        :closable="false"
      /><ElSelect
        v-described-by="formTipId + '-group-1'"
        :aria-describedby="formTipId + '-group-1'"
        v-model="selectedAgents"
        multiple
        filterable
        :filter-method="(value: string) => (agentSearch = value)"
        placeholder="选择已发布智能体"
        :disabled="busy"
        :loading="agentsLoading"
        ><ElOption
          v-for="agent in filteredAgents"
          :key="agent.id"
          :label="agent.name"
          :value="agent.id"
          class="channel-agent-option"
          ><div>{{ agent.name }}</div>
          <div v-if="agent.description" class="admin-field-help">{{ agent.description }}</div>
          <div v-if="agent.key" class="admin-field-help">{{ agent.key }}</div></ElOption
        ></ElSelect
      >
      <FieldTip :id="formTipId + '-group-1'"
        >按名称、角色说明或标识查找已发布智能体；首次选择一个时自动设为默认，可按需修改或清除。</FieldTip
      >
      <ElButton v-if="agentLoadError" :disabled="busy" :loading="agentsLoading" @click="loadAgents">
        重新读取可选智能体
      </ElButton>
      <ElForm
        label-position="top"
        @submit.prevent
        :disabled="busy"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElFormItem label="默认智能体"
          ><ElSelect
            v-described-by="'channel-default-help'"
            aria-describedby="channel-default-help"
            v-model="defaultAgent"
            clearable
            ><ElOption
              v-for="id in selectedAgents"
              :key="id"
              :label="agents.find((a) => a.id === id)?.name || id"
              :value="id"
          /></ElSelect>
          <p id="channel-default-help" class="admin-field-help">
            默认智能体必须属于上方已绑定智能体；移除对应绑定后需要重新选择。
          </p></ElFormItem
        ></ElForm
      ><template #footer
        ><ElButton @click="closeDrawer1" :disabled="busy">取消</ElButton
        ><ElButton
          v-permission="['foundation.channels.update']"
          type="primary"
          :loading="busy"
          @click="saveBindings"
          >保存</ElButton
        ></template
      ></ElDrawer
    >
    <ElDrawer
      v-model="secretOpen"
      :before-close="closeDrawer2"
      :title="'渠道访问凭据 · ' + (selected?.name ?? '')"
      size="680px"
      append-to-body
      direction="rtl"
      class="admin-drawer"
      ><FeedbackNotice
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        role="alert"
      /><ElAlert
        title="凭据只在本次签发或轮换时展示，请妥善保存。"
        type="warning"
        :closable="false"
      />
      <p v-if="createdCredentials" class="admin-field-help">
        渠道已创建。请先保存本次令牌，关闭后无法再次查看。{{
          selectedAgents.length ? '所选智能体已绑定。' : '接入前还需绑定已发布智能体。'
        }}
      </p>
      <p v-if="recoveredCredentials && !pendingToken" class="admin-field-help" role="status">
        已确认渠道创建成功，但首次令牌无法恢复。请在此轮换令牌；没有轮换权限时请联系有权限的管理员。
      </p>
      <div class="admin-actions">
        <ElButton
          v-if="!pendingToken"
          v-permission="['foundation.channels.update']"
          :loading="busy"
          :disabled="!!tokenStorageError"
          @click="rotateToken"
          >轮换渠道令牌</ElButton
        ><ElButton v-if="pendingToken && canRead" :loading="busy" @click="checkTokenReceipt()"
          >核对原轮换</ElButton
        ><ElButton v-if="pendingKey && canRead" :loading="busy" @click="checkKeyReceipt()"
          >核对原签发</ElButton
        ><ElButton
          v-if="selected?.type === 'openapi' && !pendingKey"
          v-permission="['foundation.channels.update']"
          :loading="busy"
          :disabled="!!keyStorageError"
          @click="issueKey()"
          >{{ selected.api_key_issued ? '轮换 API Key' : '签发 API Key' }}</ElButton
        ><ElButton
          v-permission="['foundation.channels.delete']"
          v-if="selected?.api_key_issued"
          type="danger"
          @click="revokeKey"
          :disabled="busy || !!pendingKey || !!keyStorageError"
          >吊销 API Key</ElButton
        >
      </div>
      <p v-if="pendingToken" class="admin-field-help" role="status">
        渠道令牌轮换结果尚未确认，原请求 ID：{{ pendingToken }}。未查到回执不代表失败。
        <span v-if="!canRead">当前账号没有渠道读取权限，请联系管理员授权后核对。</span>
      </p>
      <p v-if="tokenReceiptRecovered" class="admin-field-help" role="status">
        原令牌轮换已确认，但明文无法恢复。如需新令牌，请重新轮换。
      </p>
      <p v-if="tokenStorageError" class="admin-field-help" role="status">
        {{ tokenStorageError }}
        <ElButton v-if="!pendingToken" :disabled="busy" @click="loadPendingTokens(owner)">
          重新读取令牌操作记录
        </ElButton>
      </p>
      <p v-if="pendingKey" class="admin-field-help" role="status">
        API 凭据签发结果尚未确认，原请求 ID：{{ pendingKey }}。未查到回执不代表失败。
        <span v-if="!canRead">当前账号没有渠道读取权限，请联系管理员授权后核对。</span>
      </p>
      <p v-if="keyReceiptRecovered" class="admin-field-help" role="status">
        原签发已确认，但明文无法恢复。如需新凭据，请重新签发。
      </p>
      <p v-if="keyStorageError" class="admin-field-help" role="status">
        {{ keyStorageError }}
        <ElButton v-if="!pendingKey" :disabled="busy" @click="loadPendingKeys(owner)"
          >重新读取操作记录</ElButton
        >
      </p>
      <ElDescriptions v-if="secrets" class="channel-secrets" :column="1" :label-width="128" border
        ><template v-for="(value, key) in secrets" :key="key"
          ><ElDescriptionsItem :label="String(key)"
            ><div class="channel-secret">
              <span class="channel-secret-value">{{ value }}</span>
              <ElButton link @click="copySecret(value)">复制 {{ key }}</ElButton>
            </div></ElDescriptionsItem
          ></template
        ></ElDescriptions
      ><template #footer
        ><span v-permission.all="['foundation.channels.read', 'foundation.channels.update']">
          <ElButton v-if="createdCredentials" :disabled="busy" @click="continueBinding">{{
            selectedAgents.length ? '已保存令牌，查看绑定' : '已保存令牌，绑定智能体'
          }}</ElButton> </span
        ><ElButton :disabled="busy" @click="closeDrawer2">关闭</ElButton></template
      ></ElDrawer
    >
  </AdminPage>
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { statusLabel } from '@skillforge/shared/admin'
import { computed, onMounted, onScopeDispose, reactive, ref, watch, useId } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  AdminPage,
  useTask,
  useResource,
  useUnsavedChanges,
  useDrawerClose
} from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { api, type ChannelItem } from '../../api'
import { useAuthStore } from '../../stores/auth'
const auth = useAuthStore()
const canRead = computed(() => auth.user?.permissions.includes('foundation.channels.read') === true)
const owner = computed(() =>
  auth.user?.tenant_id && auth.user?.sub ? JSON.stringify([auth.user.tenant_id, auth.user.sub]) : ''
)
const canSelectAgents = computed(() =>
  ['foundation.channels.update', 'foundation.agents.read'].every((p) =>
    auth.user?.permissions.includes(p)
  )
)
const pendingCreate = ref('')
const storageError = ref('')
const storageKey = (subject: string) => 'skillforge.channel-create:' + subject
let disposed = false
onScopeDispose(() => {
  disposed = true
})
watch(
  owner,
  (subject) => {
    pendingCreate.value = ''
    storageError.value = ''
    if (!subject) return
    try {
      const value = sessionStorage.getItem(storageKey(subject))
      if (value && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value))
        throw Error('invalid')
      pendingCreate.value = value ?? ''
    } catch {
      storageError.value = '无法读取渠道创建记录，请恢复浏览器存储后重新进入。'
    }
  },
  { immediate: true, flush: 'sync' }
)
const { data, loading, error: loadError, reload } = useResource(() => api.channels.list())
onMounted(() => {
  void reload()
  if (pendingCreate.value && canRead.value) void checkCreate(true)
})
const { busy, error, run } = useTask(),
  query = ref(''),
  type = ref(''),
  open = ref(false),
  editing = ref<ChannelItem>(),
  saved = ref(''),
  formRef = ref<FormInstance>()
const form = reactive({
  name: '',
  channel_type: 'web_widget',
  status: 'active',
  welcome: '',
  color: '#409eff'
})
const dirty = computed(
  () =>
    open.value &&
    (JSON.stringify(form) !== saved.value || (!editing.value && selectedAgents.value.length > 0))
)
useUnsavedChanges({
  dirty: () =>
    dirty.value ||
    (bindingOpen.value && bindingSnapshot() !== savedBindings.value) ||
    Object.keys(unknownKeys.value).length > 0 ||
    Object.keys(unknownTokens.value).length > 0,
  busy: () => busy.value,
  leaveMessage: () =>
    Object.keys(unknownKeys.value).length || Object.keys(unknownTokens.value).length
      ? '凭据操作结果尚未确认。关闭标签页可能丢失原请求记录，请先核对。'
      : undefined
})
const channelTypes = [
  { value: 'web_widget', label: '网页组件' },
  { value: 'openapi', label: '开放 API' }
]
const channelTypeLabel = (value: string) =>
  channelTypes.find((item) => item.value === value)?.label ?? value
const filtered = computed(() =>
  (data.value?.items ?? []).filter(
    (row) =>
      (!query.value || row.name.includes(query.value)) && (!type.value || row.type === type.value)
  )
)
async function edit(row?: ChannelItem) {
  if (busy.value || (!row && (pendingCreate.value || storageError.value))) return
  editing.value = row
  if (!row) {
    selected.value = undefined
    selectedAgents.value = []
    defaultAgent.value = ''
    if (canSelectAgents.value) void loadAgents()
  }
  Object.assign(form, {
    name: row?.name ?? '',
    channel_type: row?.type ?? 'web_widget',
    status: row?.status ?? 'active',
    welcome: String(row?.ui_config?.welcome_message ?? ''),
    color: String(row?.ui_config?.primary_color ?? '#409eff')
  })
  saved.value = JSON.stringify(form)
  open.value = true
}
async function save() {
  await run(async () => {
    if (!(await formRef.value?.validate().catch(() => false))) return
    const body = {
      name: form.name.trim(),
      channel_type: form.channel_type,
      status: form.status,
      ui_config: {
        ...editing.value?.ui_config,
        welcome_message: form.welcome,
        primary_color: form.color
      }
    }
    if (editing.value?.id) {
      await api.channels.update(editing.value.id, body)
    } else {
      if (pendingCreate.value || storageError.value || !owner.value)
        throw Error('请先核对原渠道创建结果')
      const subject = owner.value
      const requestId = crypto.randomUUID()
      try {
        sessionStorage.setItem(storageKey(subject), requestId)
      } catch {
        storageError.value = '无法保存创建记录，请恢复浏览器存储后重试'
        return
      }
      pendingCreate.value = requestId
      let result
      try {
        result = await api.channels.onboard(requestId, {
          ...body,
          agent_bindings: selectedAgents.value.map((id, index) => ({
            agent_id: id,
            priority: index,
            is_default: id === defaultAgent.value
          }))
        })
      } catch (reason) {
        if (
          reason instanceof HttpClientError &&
          reason.code === 'CHANNEL_ONBOARDING_REJECTED' &&
          !disposed &&
          owner.value === subject
        ) {
          sessionStorage.removeItem(storageKey(subject))
          pendingCreate.value = ''
        }
        throw reason
      }
      if (disposed || owner.value !== subject) return
      if (
        !result ||
        result.request_id !== requestId ||
        !result.channel_id ||
        (result.channel &&
          (result.channel.id !== result.channel_id ||
            typeof result.channel.channel_token !== 'string' ||
            !result.channel.channel_token.trim()))
      )
        throw Error('创建结果不完整，请核对原请求')
      if (result.channel) {
        sessionStorage.removeItem(storageKey(subject))
        pendingCreate.value = ''
        const { channel_token, ...channel } = result.channel
        selected.value = { ...channel, api_key_issued: false }
        secrets.value = { channel_token }
        createdCredentials.value = true
        secretOpen.value = true
      } else {
        await openCreatedChannel(result.channel_id, subject, requestId)
        return
      }
    }
    saved.value = JSON.stringify(form)
    open.value = false
    if (editing.value) message.success('渠道已保存')
    else if (secretOpen.value) message.success('渠道已创建，请保存首次令牌')
    await reload()
  })
}
async function checkCreate(automatic = false) {
  await run(async () => {
    const subject = owner.value,
      requestId = pendingCreate.value
    if (!subject || !requestId) return
    const result = await api.channels.onboardingResult(requestId)
    if (disposed || owner.value !== subject || pendingCreate.value !== requestId) return
    if (!result || result.request_id !== requestId || !result.channel_id) {
      if (!automatic) message.warning('尚未查到原请求回执，结果仍待核对')
      return
    }
    await openCreatedChannel(result.channel_id, subject, requestId)
  })
}
async function openCreatedChannel(channelId: string, subject: string, requestId: string) {
  const channel = await api.channels.get(channelId)
  if (disposed || owner.value !== subject || pendingCreate.value !== requestId) return
  if (channel.id !== channelId) throw Error('渠道详情不匹配，请重新核对原请求')
  sessionStorage.removeItem(storageKey(subject))
  pendingCreate.value = ''
  selected.value = channel
  secrets.value = undefined
  createdCredentials.value = false
  recoveredCredentials.value = true
  secretOpen.value = true
  saved.value = JSON.stringify(form)
  open.value = false
  message.warning('渠道已创建，首次令牌无法恢复；请在访问凭据中明确轮换令牌。')
  await reload()
}
async function remove(row: ChannelItem) {
  if (
    await confirm({
      title: '删除渠道',
      confirmText: '删除渠道',
      danger: true,
      content: '确定删除渠道“' + row.name + '”？现有接入将失效。'
    })
  )
    await run(async () => {
      await api.channels.remove(row.id!)
      message.success('渠道已删除')
      await reload()
    })
}
const bindingOpen = ref(false),
  selected = ref<ChannelItem>(),
  selectedAgents = ref<string[]>([]),
  defaultAgent = ref(''),
  savedBindings = ref('')
const {
  data: agentDirectory,
  loading: agentsLoading,
  error: agentLoadError,
  reload: loadAgents
} = useResource(() => api.agents.list())
const agents = computed(() => {
  const options = new Map<string, { id: string; name: string; description: string; key: string }>(
    (agentDirectory.value?.items ?? [])
      .filter((agent) => agent.status === 'published')
      .map((agent) => [
        agent.id,
        {
          id: agent.id,
          name: agent.name,
          description: agent.role_description ?? '',
          key: agent.agent_key ?? ''
        }
      ])
  )
  for (const binding of selected.value?.agent_bindings ?? []) {
    if (selectedAgents.value.includes(binding.id))
      options.set(binding.id, {
        id: binding.id,
        name: binding.name,
        description: options.get(binding.id)?.description ?? '',
        key: options.get(binding.id)?.key ?? ''
      })
  }
  return [...options.values()]
})
const agentSearch = ref('')
const filteredAgents = computed(() => {
  const keyword = agentSearch.value.trim().toLocaleLowerCase()
  return agents.value.filter((agent) =>
    [agent.name, agent.description, agent.key].some((value) =>
      value.toLocaleLowerCase().includes(keyword)
    )
  )
})
const bindingSnapshot = () => JSON.stringify([selectedAgents.value, defaultAgent.value])
watch(selectedAgents, (ids, previous) => {
  if (open.value && !editing.value && !ids.includes(defaultAgent.value))
    defaultAgent.value = ids[0] ?? ''
  else if (
    bindingOpen.value &&
    !selected.value?.agent_bindings?.length &&
    previous.length === 0 &&
    ids.length === 1
  )
    defaultAgent.value = ids[0]!
  else if (!ids.includes(defaultAgent.value)) defaultAgent.value = ''
})
async function bind(row: ChannelItem) {
  await run(async () => {
    agentSearch.value = ''
    const detail = await api.channels.get(row.id!)
    selected.value = detail
    selectedAgents.value = (detail.agent_bindings ?? []).map((a) => a.id)
    defaultAgent.value = detail.agent_bindings?.find((a) => a.is_default)?.id ?? ''
    savedBindings.value = bindingSnapshot()
    bindingOpen.value = true
    void loadAgents()
  })
}
async function saveBindings() {
  await run(async () => {
    await api.channels.bindAgents(
      selected.value!.id!,
      selectedAgents.value.map((id, index) => {
        const binding = selected.value!.agent_bindings?.find((item) => item.id === id)
        return {
          agent_id: id,
          priority: binding?.priority ?? index,
          traffic_percentage: binding?.traffic_percentage,
          is_default: id === defaultAgent.value
        }
      })
    )
    bindingOpen.value = false
    await reload()
    message.success('智能体绑定已保存')
  })
}
const unknownTokens = ref<Record<string, string>>({})
const tokenStorageError = ref('')
const tokenReceiptRecovered = ref(false)
const tokenStorageKey = (subject: string) => 'skillforge.channel-token:' + subject
function loadPendingTokens(subject: string) {
  if (!subject) {
    tokenStorageError.value = '无法确认当前机构和成员，请重新登录后操作令牌。'
    return
  }
  try {
    const raw = sessionStorage.getItem(tokenStorageKey(subject))
    const value: Record<string, unknown> = raw ? JSON.parse(raw) : {}
    if (
      !value ||
      Array.isArray(value) ||
      typeof value !== 'object' ||
      Object.entries(value).some(
        ([channel, request]) =>
          !channel ||
          typeof request !== 'string' ||
          !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(request)
      )
    )
      throw Error('invalid')
    unknownTokens.value = value as Record<string, string>
    tokenStorageError.value = ''
  } catch {
    tokenStorageError.value = '无法读取令牌操作记录，请恢复浏览器存储后重试。'
  }
}
function persistPendingTokens(subject: string, value: Record<string, string>) {
  try {
    sessionStorage.setItem(tokenStorageKey(subject), JSON.stringify(value))
    unknownTokens.value = value
    tokenStorageError.value = ''
    return true
  } catch {
    tokenStorageError.value = '无法保存令牌操作记录，请恢复浏览器存储后重试。'
    return false
  }
}
function clearPendingToken(subject: string, channelId: string, requestId: string) {
  if (disposed || owner.value !== subject || unknownTokens.value[channelId] !== requestId)
    return false
  const next = { ...unknownTokens.value }
  delete next[channelId]
  return persistPendingTokens(subject, next)
}
const pendingToken = computed(() =>
  selected.value?.id ? unknownTokens.value[selected.value.id] : undefined
)
const unknownKeys = ref<Record<string, string>>({})
const keyStorageError = ref('')
const keyReceiptRecovered = ref(false)
const keyStorageKey = (subject: string) => 'skillforge.channel-key:' + subject
function loadPendingKeys(subject: string) {
  if (!subject) {
    keyStorageError.value = '无法确认当前机构和成员，请重新登录后操作凭据。'
    return
  }
  try {
    const raw = sessionStorage.getItem(keyStorageKey(subject))
    const value: Record<string, unknown> = raw ? JSON.parse(raw) : {}
    if (
      !value ||
      Array.isArray(value) ||
      typeof value !== 'object' ||
      Object.entries(value).some(
        ([channel, request]) =>
          !channel ||
          typeof request !== 'string' ||
          !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(request)
      )
    )
      throw Error('invalid')
    unknownKeys.value = value as Record<string, string>
    keyStorageError.value = ''
  } catch {
    keyStorageError.value = '无法读取凭据操作记录，请恢复浏览器存储后重试。'
  }
}
function persistPendingKeys(subject: string, value: Record<string, string>) {
  try {
    sessionStorage.setItem(keyStorageKey(subject), JSON.stringify(value))
    unknownKeys.value = value
    keyStorageError.value = ''
    return true
  } catch {
    keyStorageError.value = '无法保存凭据操作记录，请恢复浏览器存储后重试。'
    return false
  }
}
function clearPendingKey(subject: string, channelId: string, requestId: string) {
  if (disposed || owner.value !== subject || unknownKeys.value[channelId] !== requestId)
    return false
  const next = { ...unknownKeys.value }
  delete next[channelId]
  return persistPendingKeys(subject, next)
}
const pendingKey = computed(() =>
  selected.value?.id ? unknownKeys.value[selected.value.id] : undefined
)
const secretOpen = ref(false),
  createdCredentials = ref(false),
  recoveredCredentials = ref(false),
  secrets = ref<Record<string, string>>()
watch(
  owner,
  (subject) => {
    unknownKeys.value = {}
    unknownTokens.value = {}
    tokenStorageError.value = ''
    tokenReceiptRecovered.value = false
    keyStorageError.value = ''
    keyReceiptRecovered.value = false
    secrets.value = undefined
    secretOpen.value = false
    loadPendingKeys(subject)
    loadPendingTokens(subject)
  },
  { immediate: true, flush: 'sync' }
)
watch(secretOpen, (value) => {
  if (!value) {
    secrets.value = undefined
    createdCredentials.value = false
    recoveredCredentials.value = false
    keyReceiptRecovered.value = false
    tokenReceiptRecovered.value = false
  }
})
function credentials(row: ChannelItem) {
  createdCredentials.value = false
  recoveredCredentials.value = false
  selected.value = row
  secrets.value = undefined
  keyReceiptRecovered.value = false
  tokenReceiptRecovered.value = false
  secretOpen.value = true
  if (canRead.value) void checkPendingCredentials()
}
async function checkPendingCredentials() {
  if (pendingKey.value) await checkKeyReceipt(true)
  if (pendingToken.value) await checkTokenReceipt(true)
}
async function copySecret(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    message.success('凭据已复制')
  } catch {
    message.error('复制失败，请手动选择并保存凭据')
  }
}
async function continueBinding() {
  const channel = selected.value!
  await bind(channel)
  if (bindingOpen.value) closeDrawer2()
}
async function checkTokenReceipt(automatic = false) {
  const channel = selected.value,
    subject = owner.value,
    requestId = pendingToken.value
  if (!channel?.id || !subject || !requestId || !canRead.value) return
  await run(async () => {
    const result = await api.channels.tokenRotationResult(requestId)
    if (disposed || owner.value !== subject || unknownTokens.value[channel.id!] !== requestId)
      return
    if (!result || result.request_id !== requestId || result.channel_id !== channel.id) {
      if (!automatic) message.warning('尚未查到匹配的原轮换回执，结果仍未确认。')
      return
    }
    if (!clearPendingToken(subject, channel.id!, requestId)) return
    tokenReceiptRecovered.value = !secrets.value?.channel_token
    recoveredCredentials.value = false
    message.success('原令牌轮换已确认')
  })
}
async function rotateToken() {
  const channel = selected.value,
    subject = owner.value
  if (!channel?.id || !subject || pendingToken.value || tokenStorageError.value) return
  if (
    !(await confirm({
      title: '轮换渠道令牌',
      confirmText: '轮换渠道令牌',
      content: `渠道“${channel.name}”的旧令牌将立即失效，请同步更新接入方。`
    }))
  )
    return
  if (disposed || owner.value !== subject || selected.value?.id !== channel.id) return
  await run(async () => {
    const requestId = crypto.randomUUID()
    if (!persistPendingTokens(subject, { ...unknownTokens.value, [channel.id!]: requestId })) return
    let result
    try {
      result = await api.channels.rotateTokenWithReceipt(requestId, channel.id!)
      if (disposed || owner.value !== subject || unknownTokens.value[channel.id!] !== requestId)
        return
      if (
        !result ||
        result.request_id !== requestId ||
        result.channel_id !== channel.id ||
        (result.channel_token !== undefined &&
          (typeof result.channel_token !== 'string' || !result.channel_token.trim()))
      )
        throw Error('渠道令牌响应不完整，请核对原请求')
    } catch (reason) {
      if (disposed || owner.value !== subject || unknownTokens.value[channel.id!] !== requestId)
        return
      if (reason instanceof HttpClientError && reason.code === 'CHANNEL_TOKEN_REJECTED') {
        clearPendingToken(subject, channel.id!, requestId)
      } else if (secrets.value?.channel_token) {
        const remaining = { ...secrets.value }
        delete remaining.channel_token
        secrets.value = Object.keys(remaining).length ? remaining : undefined
      }
      throw reason
    }
    const cleared = clearPendingToken(subject, channel.id!, requestId)
    recoveredCredentials.value = false
    if (result.channel_token) {
      secrets.value = { channel_token: result.channel_token }
      tokenReceiptRecovered.value = false
      message.success('渠道令牌已轮换，请保存新凭据')
    } else {
      if (secrets.value?.channel_token) {
        const remaining = { ...secrets.value }
        delete remaining.channel_token
        secrets.value = Object.keys(remaining).length ? remaining : undefined
      }
      tokenReceiptRecovered.value = true
      message.warning('原令牌轮换已确认，但明文无法恢复，请重新轮换。')
    }
    if (!cleared) message.warning('原轮换已确认，但本地记录尚未清除，请恢复浏览器存储后核对。')
  })
}
async function checkKeyReceipt(automatic = false) {
  const channel = selected.value,
    subject = owner.value,
    requestId = pendingKey.value
  if (!channel?.id || !subject || !requestId || !canRead.value) return
  await run(async () => {
    const result = await api.channels.apiKeyIssueResult(requestId)
    if (disposed || owner.value !== subject || unknownKeys.value[channel.id!] !== requestId) return
    if (!result || result.request_id !== requestId || result.channel_id !== channel.id) {
      if (!automatic) message.warning('尚未查到匹配的原签发回执，结果仍未确认。')
      return
    }
    const current = await api.channels.get(channel.id!)
    if (disposed || owner.value !== subject || unknownKeys.value[channel.id!] !== requestId) return
    if (current.id !== channel.id) throw Error('渠道详情不匹配，请重新核对原请求')
    if (!clearPendingKey(subject, channel.id!, requestId)) return
    channel.api_key_issued = current.api_key_issued
    keyReceiptRecovered.value = !secrets.value?.api_key
    message.success('原凭据签发已确认')
  })
}
async function issueKey() {
  const channel = selected.value!,
    subject = owner.value
  if (!channel.id || !subject || pendingKey.value || keyStorageError.value) return
  if (
    channel.api_key_issued &&
    !(await confirm({
      title: '轮换 API Key',
      confirmText: '轮换 API Key',
      content: `渠道“${channel.name}”的旧 API Key 将立即失效，请同步更新接入方。`
    }))
  )
    return
  if (disposed || owner.value !== subject || selected.value?.id !== channel.id) return
  await run(async () => {
    const requestId = crypto.randomUUID()
    if (!persistPendingKeys(subject, { ...unknownKeys.value, [channel.id!]: requestId })) return
    let result
    try {
      result = await api.channels.issueApiKeyWithReceipt(requestId, channel.id!)
      if (disposed || owner.value !== subject || unknownKeys.value[channel.id!] !== requestId)
        return
      if (
        !result ||
        result.request_id !== requestId ||
        result.channel_id !== channel.id ||
        ((result.api_key !== undefined || result.api_secret !== undefined) &&
          (typeof result.api_key !== 'string' ||
            !result.api_key.trim() ||
            typeof result.api_secret !== 'string' ||
            !result.api_secret.trim()))
      )
        throw Error('API 凭据响应不完整，请核对原请求')
    } catch (reason) {
      if (disposed || owner.value !== subject || unknownKeys.value[channel.id!] !== requestId)
        return
      if (reason instanceof HttpClientError && reason.code === 'CHANNEL_KEY_REJECTED') {
        clearPendingKey(subject, channel.id!, requestId)
      } else if (secrets.value?.api_key || secrets.value?.api_secret) {
        const remaining = { ...secrets.value }
        delete remaining.api_key
        delete remaining.api_secret
        secrets.value = Object.keys(remaining).length ? remaining : undefined
      }
      throw reason
    }
    let issued = true
    if (!result.api_key && !result.api_secret) {
      const current = await api.channels.get(channel.id!)
      if (disposed || owner.value !== subject || unknownKeys.value[channel.id!] !== requestId)
        return
      if (current.id !== channel.id) throw Error('渠道详情不匹配，请重新核对原请求')
      issued = !!current.api_key_issued
    }
    const cleared = clearPendingKey(subject, channel.id!, requestId)
    channel.api_key_issued = issued
    if (result.api_key && result.api_secret) {
      secrets.value = { api_key: result.api_key, api_secret: result.api_secret }
      keyReceiptRecovered.value = false
      message.success('API 凭据已签发，请保存新凭据')
    } else {
      secrets.value = undefined
      keyReceiptRecovered.value = true
      message.warning('原签发已确认，但明文无法恢复，请重新签发。')
    }
    if (!cleared) message.warning('原签发已确认，但本地记录尚未清除，请恢复浏览器存储后核对。')
    await reload()
  })
}
async function revokeKey() {
  if (pendingKey.value || keyStorageError.value) return
  if (
    await confirm({
      title: '吊销 API Key',
      confirmText: '吊销 API Key',
      danger: true,
      content: `渠道“${selected.value?.name}”的 API Key 将失效，使用该凭据的接入将中断。`
    })
  )
    await run(async () => {
      await api.channels.deleteApiKey(selected.value!.id!)
      message.success('API Key 已吊销')
      selected.value!.api_key_issued = false
      secrets.value = undefined
      await reload()
    })
}

const { requestClose: closeDrawer0 } = useDrawerClose({
  open: () => open.value,
  busy: () => busy.value,
  dirty: () => open.value && dirty.value,
  close: () => {
    open.value = false
  }
})
const { requestClose: closeDrawer1 } = useDrawerClose({
  open: () => bindingOpen.value,
  busy: () => busy.value,
  dirty: () => bindingOpen.value && bindingSnapshot() !== savedBindings.value,
  close: () => {
    bindingOpen.value = false
  }
})
const { requestClose: closeDrawer2 } = useDrawerClose({
  open: () => secretOpen.value,
  busy: () => busy.value,
  dirty: () => secretOpen.value && false,
  close: () => {
    secretOpen.value = false
  }
})

const formTipId = useId()
</script>

<style scoped>
.channel-agent-option {
  height: auto;
  line-height: 1.5;
  padding-block: 8px;
  white-space: normal;
}
.channel-agent-option .admin-field-help {
  margin: 0;
}
.channel-secrets :deep(.el-descriptions__table) {
  table-layout: fixed;
}
.channel-secret {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}
.channel-secret-value {
  max-width: 100%;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
