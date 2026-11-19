<script setup lang="ts">
import { vDescribedBy } from './describedBy'

import FieldTip from './FieldTip.vue'

import {
  ChatMessageNotice,
  ChatButton,
  ChatCheckbox,
  ChatCheckboxGroup,
  ChatDrawer,
  ChatForm,
  ChatFormItem,
  ChatInput,
  ChatOption,
  ChatSelect,
  type FormInstance,
  type FormRules
} from './controls'
import { computed, onBeforeUnmount, reactive, ref, watch, useId } from 'vue'
import { query, RequestError, type Client } from '../client'
import type { Agent, Team } from '../protocol'
import { mayDiscard, confirmAction, validateForm } from './panel'
import { agentChoiceName } from './agentChoice'
const props = defineProps<{
  modelValue: boolean
  client: Client
  clientRef: string
  agents: Agent[]
  teams: Team[]
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [team: Team]
  'agents-updated': [agents: Agent[]]
}>()
const form = reactive({
  id: '',
  revision: 0,
  status: '',
  name: '',
  description: '',
  primary_agent_id: '',
  members: [] as string[]
})
const formRef = ref<FormInstance>(),
  busy = ref(false),
  error = ref(''),
  feedback = ref(''),
  baseline = ref(JSON.stringify(form))
const creationUnknown = ref(false),
  checkedTeams = ref<Team[]>(),
  reconciliationId = ref(''),
  reconciliationNotice = ref('')
const selectableTeams = computed(() => checkedTeams.value ?? props.teams)
const dirty = computed(() => JSON.stringify(form) !== baseline.value)
const primaryAgent = computed(() =>
  props.agents.find((agent) => agent.id === form.primary_agent_id)
)
const incompatibleMembers = computed(() =>
  form.members.filter(
    (id) =>
      id !== form.primary_agent_id &&
      primaryAgent.value &&
      (!primaryAgent.value.delegation_config?.enabled ||
        !primaryAgent.value.delegation_config.target_agent_ids.includes(id))
  )
)
const collaborationHint = computed(() => {
  const primary = primaryAgent.value
  if (!primary) return '选择成员和负责人后，可查看该负责人允许的协作范围。'
  const config = primary.delegation_config
  if (!config) return '尚未读取到负责人的协作范围，请刷新成员目录后再添加协作成员。'
  if (!config.enabled) return `“${primary.name}”尚未启用成员协作，目前只能保存单成员团队。`
  const available = props.agents.filter(
    (agent) => agent.id !== primary.id && config.target_agent_ids.includes(agent.id)
  )
  return available.length
    ? `“${primary.name}”可与以下成员协作：${available.map((agent) => agentChoiceName(agent, props.agents)).join('、')}。`
    : `“${primary.name}”已启用成员协作，但当前渠道没有它可委派的成员。`
})
function protectUnknownCreation(event: BeforeUnloadEvent) {
  if (!creationUnknown.value) return
  event.preventDefault()
  event.returnValue = ''
}
watch(creationUnknown, (unknown) => {
  if (unknown) window.addEventListener('beforeunload', protectUnknownCreation)
  else window.removeEventListener('beforeunload', protectUnknownCreation)
})
watch(
  () => [...form.members],
  () => {
    if (!form.members.includes(form.primary_agent_id))
      form.primary_agent_id = form.members.length === 1 ? form.members[0]! : ''
  }
)
const abort = new AbortController()
let disposed = false
const rules: FormRules = {
  name: [
    { required: true, message: '请填写团队名称', trigger: 'blur' },
    {
      validator: (_rule, value, callback) =>
        callback(String(value).trim() ? undefined : new Error('团队名称不能为空')),
      trigger: 'blur'
    }
  ],
  members: [
    { type: 'array', required: true, min: 1, message: '至少选择一个成员', trigger: 'change' }
  ],
  primary_agent_id: [
    {
      required: true,
      validator: (_rule, value, callback) =>
        callback(
          form.members.includes(String(value)) ? undefined : new Error('请从团队成员中选择负责人')
        ),
      trigger: 'change'
    }
  ]
}
function assign(team?: Team) {
  Object.assign(form, {
    id: team?.id ?? '',
    revision: team?.revision ?? 0,
    status: team?.status ?? '',
    name: team?.name ?? '',
    description: team?.description ?? '',
    primary_agent_id: team?.primary_agent_id ?? '',
    members: team?.members.map((item) => item.agent_id) ?? []
  })
  baseline.value = JSON.stringify(form)
  formRef.value?.clearValidate()
}
async function edit(team?: Team) {
  if (creationUnknown.value) return
  if (await mayDiscard(busy.value, dirty.value)) assign(team)
}
async function requestClose() {
  // 未知创建保留原草稿和冻结状态，关闭面板不等于撤销服务端操作。
  if (creationUnknown.value && !busy.value) {
    emit('update:modelValue', false)
    return true
  }
  if (!(await mayDiscard(busy.value, dirty.value))) return false
  assign()
  error.value = ''
  emit('update:modelValue', false)
  return true
}
async function beforeClose(done: () => void) {
  if (await requestClose()) done()
}
async function save(disable = false) {
  if (busy.value || creationUnknown.value || (!disable && incompatibleMembers.value.length)) return
  if (
    disable &&
    !(await confirmAction(
      `停用团队“${form.name}”后，不能新建会话或继续发送。已有会话和数据保留，可查看历史消息；在团队管理中重新启用后可继续对话。`,
      '停用团队',
      '停用团队'
    ))
  )
    return
  if (!disable && !(await validateForm(formRef.value))) return
  if (busy.value) return
  busy.value = true
  error.value = ''
  feedback.value = ''
  const body = {
    client_ref: props.clientRef,
    ...(form.id ? { id: form.id } : {}),
    revision: form.revision,
    ...(!disable
      ? {
          name: form.name.trim(),
          description: form.description,
          primary_agent_id: form.primary_agent_id,
          members: form.members.map((agent_id) => ({ agent_id }))
        }
      : {})
  }
  try {
    const team = await props.client.json<Team>(
      disable ? '/chat/teams/disable' : '/chat/teams',
      disable ? 'POST' : 'PUT',
      body,
      abort.signal
    )
    if (!disposed) {
      feedback.value = disable
        ? '团队已停用，已有会话和数据保留。'
        : '团队已保存并启用，可继续对话。'
      emit('saved', team)
      assign(team)
    }
  } catch (cause) {
    if (!disposed) {
      const rejected =
        cause instanceof RequestError &&
        cause.status >= 400 &&
        cause.status < 500 &&
        ['VALIDATION_ERROR', 'UNAUTHORIZED', 'FORBIDDEN', 'RATE_LIMITED'].includes(cause.code)
      if (!disable && !form.id && !rejected) {
        creationUnknown.value = true
        error.value = ''
      } else {
        error.value = cause instanceof Error ? cause.message : '保存团队失败，请稍后再次操作。'
      }
    }
  } finally {
    if (!disposed) busy.value = false
  }
}
async function refreshAgents() {
  if (busy.value || creationUnknown.value) return
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    const catalog = await props.client.json<{ items: Agent[] }>(
      '/chat/agents',
      'GET',
      undefined,
      abort.signal
    )
    if (!disposed) {
      emit('agents-updated', catalog.items)
      feedback.value = '成员目录与协作范围已刷新，当前团队填写内容保留。'
    }
  } catch (cause) {
    if (!disposed)
      error.value = cause instanceof Error ? cause.message : '未能刷新成员目录，请稍后重试。'
  } finally {
    if (!disposed) busy.value = false
  }
}
/** 当前接口没有创建操作查询键；只读核对列表，由用户明确关联已创建记录。 */
async function checkTeams() {
  if (busy.value || !creationUnknown.value) return
  busy.value = true
  error.value = ''
  reconciliationNotice.value = ''
  try {
    const items = await props.client.json<Team[]>(
      `/chat/teams?${query({ client_ref: props.clientRef })}`,
      'GET',
      undefined,
      abort.signal
    )
    if (disposed) return
    checkedTeams.value = items
    reconciliationId.value = ''
    reconciliationNotice.value = items.length
      ? '团队列表已刷新。请选择并核对本次已创建的团队，再关联此记录。'
      : '列表中暂未找到团队，创建结果仍未确认。请稍后再次核对或联系机构管理员。'
  } catch (cause) {
    if (!disposed)
      error.value = cause instanceof Error ? cause.message : '未能读取团队列表，请稍后再次核对。'
  } finally {
    if (!disposed) busy.value = false
  }
}
async function associateTeam() {
  const team = checkedTeams.value?.find((item) => item.id === reconciliationId.value)
  if (!team || busy.value || !creationUnknown.value) return
  if (
    !(await confirmAction(
      `请确认“${team.name}”是本次已创建的团队。关联后将编辑该记录，不再新建。`,
      '核对创建结果',
      '确认关联',
      '继续核对'
    ))
  )
    return
  creationUnknown.value = false
  error.value = ''
  reconciliationNotice.value = ''
  assign(team)
  emit('saved', team)
}
defineExpose({ requestClose })
onBeforeUnmount(() => {
  disposed = true
  abort.abort()
  window.removeEventListener('beforeunload', protectUnknownCreation)
})

const formTipId = useId()
</script>
<template>
  <ChatDrawer
    :model-value="modelValue"
    title="团队管理"
    size="min(460px, 100vw)"
    :before-close="beforeClose"
    :close-on-click-modal="false"
    :show-close="!busy"
    :close-on-press-escape="!busy"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <ChatMessageNotice v-if="error" :title="error" type="error" :closable="false" show-icon />
    <p v-if="busy" role="status">正在处理团队，请稍候；完成后可关闭面板。</p>
    <ChatMessageNotice
      v-if="feedback"
      :title="feedback"
      type="success"
      :closable="false"
      show-icon
    />
    <ChatMessageNotice
      v-if="creationUnknown"
      title="团队创建结果尚未确认，原草稿已保留。请核对团队列表，勿重复新建。"
      type="warning"
    />
    <ChatMessageNotice v-if="reconciliationNotice" :title="reconciliationNotice" type="info" />
    <section v-if="creationUnknown" class="team-reconciliation" aria-label="核对团队创建结果">
      <ChatButton :disabled="busy" :loading="busy" @click="checkTeams">核对团队列表</ChatButton>
      <template v-if="checkedTeams?.length">
        <ChatSelect
          v-model="reconciliationId"
          aria-label="本次已创建的团队"
          :disabled="busy"
          :options="
            checkedTeams.map((team) => ({ value: team.id, label: `${team.name}（${team.id}）` }))
          "
        />
        <ChatButton :disabled="busy || !reconciliationId" @click="associateTeam"
          >已核对，关联此团队</ChatButton
        >
      </template>
    </section>
    <p v-if="!selectableTeams.length" class="muted">暂无团队，可在下方新建。</p>
    <div class="actions team-actions">
      <ChatButton :disabled="busy || creationUnknown" @click="edit()">新建团队</ChatButton>
      <ChatSelect
        v-if="selectableTeams.length"
        :model-value="form.id"
        aria-label="选择团队编辑"
        placeholder="选择已有团队"
        filterable
        :disabled="busy || creationUnknown"
        @change="edit(selectableTeams.find((team) => team.id === $event))"
      >
        <ChatOption
          v-for="team in selectableTeams"
          :key="team.id"
          :value="team.id"
          :label="team.name + (team.status === 'disabled' ? '（已停用）' : '')"
        />
      </ChatSelect>
    </div>
    <p v-if="form.status === 'disabled'" class="muted">
      团队已停用，已有会话仅可查看历史。点击“保存并启用团队”后，当前配置立即生效，可继续对话。
    </p>
    <ChatForm
      ref="formRef"
      :model="form"
      :rules="rules"
      :disabled="busy || creationUnknown"
      label-position="top"
      @submit.prevent="save()"
    >
      <ChatFormItem label="名称" prop="name"
        ><ChatInput
          v-described-by="formTipId + '-field-0'"
          :aria-describedby="formTipId + '-field-0'"
          v-model="form.name"
          maxlength="100"
        />
        <FieldTip :id="formTipId + '-field-0'"
          >用于对话对象列表的团队名称，最多 100 个字符。</FieldTip
        >
      </ChatFormItem>
      <ChatFormItem label="说明" prop="description"
        ><ChatInput
          v-described-by="formTipId + '-field-1'"
          :aria-describedby="formTipId + '-field-1'"
          v-model="form.description"
          type="textarea"
          :rows="3"
        />
        <FieldTip :id="formTipId + '-field-1'">说明团队职责和适用场景，便于选择协作对象。</FieldTip>
      </ChatFormItem>
      <ChatFormItem label="成员" prop="members">
        <p v-if="!agents.length" role="status">
          当前没有可选智能体，请联系机构管理员配置渠道可用成员。
        </p>
        <ChatCheckboxGroup
          v-described-by="'team-members-help'"
          v-model="form.members"
          aria-describedby="team-members-help"
          ><ChatCheckbox
            v-described-by="'team-members-help'"
            aria-describedby="team-members-help"
            v-for="agent in agents"
            :key="agent.id"
            :value="agent.id"
            :label="agentChoiceName(agent, agents)"
            class="agent-choice-checkbox"
            ><span class="agent-choice-content"
              ><span>{{ agentChoiceName(agent, agents) }}</span
              ><small v-if="agent.role_description">{{ agent.role_description }}</small></span
            ></ChatCheckbox
          ></ChatCheckboxGroup
        >
      </ChatFormItem>
      <p id="team-members-help" class="muted">
        成员按角色说明选择；负责人必须属于已选成员，其他成员需在负责人已发布的协作范围内。
      </p>
      <ChatFormItem label="负责人" prop="primary_agent_id">
        <ChatSelect
          v-described-by="'team-members-help'"
          v-model="form.primary_agent_id"
          placeholder="请从已选成员中选择"
          :disabled="!form.members.length"
          filterable
          aria-describedby="team-members-help"
          ><ChatOption
            v-for="agent in agents.filter((item) => form.members.includes(item.id))"
            :key="agent.id"
            :value="agent.id"
            :label="agentChoiceName(agent, agents)"
            :search-text="agent.role_description"
            class="agent-choice-option"
            ><span class="agent-choice-content"
              ><span>{{ agentChoiceName(agent, agents) }}</span
              ><small v-if="agent.role_description">{{ agent.role_description }}</small></span
            ></ChatOption
          ></ChatSelect
        >
      </ChatFormItem>
      <div class="panel-card" aria-label="团队协作范围">
        <p role="status">{{ collaborationHint }}</p>
        <p v-if="incompatibleMembers.length" class="chat-field__error" role="status">
          以下已选成员暂不能参与协作：{{
            incompatibleMembers
              .map((id) => agents.find((agent) => agent.id === id)?.name ?? '不可用成员')
              .join('、')
          }}。请移除这些成员或选择允许该分工的负责人后保存。
        </p>
        <p class="muted">
          需要增加协作成员时，请联系机构管理员在负责人的“运行与协作”中配置任务委派并发布，同时将成员绑定到当前渠道。配置完成后可刷新目录，已填写内容会保留。
        </p>
        <ChatButton :disabled="busy || creationUnknown" @click="refreshAgents"
          >刷新成员目录</ChatButton
        >
      </div>
      <div class="actions">
        <ChatButton
          type="primary"
          native-type="submit"
          :loading="busy"
          :disabled="creationUnknown || Boolean(incompatibleMembers.length)"
          >{{ form.status === 'disabled' ? '保存并启用团队' : '保存团队' }}</ChatButton
        ><ChatButton v-if="form.id && form.status !== 'disabled'" @click="save(true)"
          >停用团队</ChatButton
        >
      </div>
    </ChatForm>
    <template #footer
      ><ChatButton :disabled="busy" @click="requestClose">关闭</ChatButton></template
    >
  </ChatDrawer>
</template>
