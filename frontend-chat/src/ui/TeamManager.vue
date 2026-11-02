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
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [team: Team] }>()
const form = reactive({
  id: '',
  revision: 0,
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
  if (busy.value || creationUnknown.value) return
  if (
    disable &&
    !(await confirmAction(
      `停用团队“${form.name}”后，将不能再选择它发起新会话。`,
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
      feedback.value = disable ? '团队已停用。' : '团队已保存，可在团队列表中查看。'
      emit('saved', team)
      assign()
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
      <p id="team-members-help" class="muted">成员按公开角色说明选择；负责人必须属于已选成员。</p>
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
      <div class="actions">
        <ChatButton type="primary" native-type="submit" :loading="busy" :disabled="creationUnknown"
          >保存团队</ChatButton
        ><ChatButton v-if="form.id" @click="save(true)">停用团队</ChatButton>
      </div>
    </ChatForm>
    <template #footer
      ><ChatButton :disabled="busy" @click="requestClose">关闭</ChatButton></template
    >
  </ChatDrawer>
</template>
