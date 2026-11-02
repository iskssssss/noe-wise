<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { useActionPermissions } from '../../composables/actionPermissions'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import { systemSettingHelp } from './system-setting-help'
import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { message, confirm } from '@skillforge/shared/feedback'
import { computed, onMounted, ref, useId } from 'vue'

import {
  AdminPage,
  useTask,
  useUnsavedChanges,
  useResource,
  useDrawerClose
} from '@skillforge/shared/admin'
import {
  api,
  type SystemSettingField,
  type SystemSettingGroup,
  type SystemSettingVersion
} from '../../api'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
const { allowed, checked } = useActionPermissions()
// Element Plus 仅在挂载时写入此属性；表单忙碌状态变化后需同步实际输入状态。
const vNumberAria = {
  mounted: syncNumberAria,
  updated: syncNumberAria
}
function syncNumberAria(element: HTMLElement) {
  const input = element.querySelector('input')
  if (input) input.setAttribute('aria-disabled', String(input.disabled))
}
const load = useTask(),
  action = useTask(),
  groups = ref<SystemSettingGroup[]>([]),
  baseline = ref('[]'),
  selected = ref<SystemSettingGroup>(),
  historyOpen = ref(false)
const contextPolicy = useResource(() => api.admin.systemSettings.contextPolicy())
const contextPolicyVersion = computed(() => contextPolicy.data.value?.version ?? '')
const error = computed(() =>
  [
    load.error.value,
    contextPolicy.error.value && '上下文策略读取失败：' + contextPolicy.error.value,
    action.error.value
  ]
    .filter(Boolean)
    .join('；')
)
const dirty = computed(() => JSON.stringify(groups.value) !== baseline.value)
const search = ref('')
function groupDirty(group: SystemSettingGroup) {
  const base = JSON.parse(baseline.value) as SystemSettingGroup[]
  return JSON.stringify(base.find((value) => value.key === group.key)) !== JSON.stringify(group)
}
const selectedGroupDirty = computed(() => {
  const group = groups.value.find((item) => item.key === selected.value?.key)
  return !!group && groupDirty(group)
})
const visibleGroups = computed(() => {
  const text = search.value.trim().toLowerCase()
  return groups.value.filter(
    (group) =>
      groupDirty(group) ||
      `${group.label} ${group.key} ${group.fields.map((field) => field.label + ' ' + field.key).join(' ')}`
        .toLowerCase()
        .includes(text)
  )
})
useUnsavedChanges({ dirty: () => dirty.value, busy: () => action.busy.value })
function reload() {
  // 已有分组只能通过本组确认重读，初始失败重试不能覆盖页内设置草稿。
  if (groups.value.length) return
  void load.run(async () => {
    groups.value = await api.admin.systemSettings.list()
    baseline.value = JSON.stringify(groups.value)
  })
}
function acceptGroup(result: SystemSettingGroup) {
  const index = groups.value.findIndex((group) => group.key === result.key)
  if (index < 0) throw new Error('设置组已不可用，请刷新页面')
  groups.value[index] = result
  const base = JSON.parse(baseline.value) as SystemSettingGroup[]
  base[index] = result
  baseline.value = JSON.stringify(base)
}
const fieldErrors = ref<Record<string, Record<string, string>>>({})
function validateGroup(group: SystemSettingGroup) {
  const errors: Record<string, string> = {}
  for (const field of group.fields) {
    const value = group.values[field.key]
    if (field.type === 'number') {
      if (typeof value !== 'number' || !Number.isFinite(value)) errors[field.key] = '请填写有效数值'
      else if (Number.isInteger(value) && !Number.isSafeInteger(value))
        errors[field.key] = '此整数超出浏览器精确表示范围，请使用可精确表示的额度'
      else if (
        (field.minimum !== null && field.minimum !== undefined && value < field.minimum) ||
        (field.maximum !== null && field.maximum !== undefined && value > field.maximum)
      )
        errors[field.key] = '数值超出允许范围'
    } else if (field.type === 'select' && !field.choices?.includes(String(value)))
      errors[field.key] = '请选择当前可用选项'
  }
  fieldErrors.value[group.key] = errors
  if (Object.keys(errors).length)
    throw new Error(`${group.label}有 ${Object.keys(errors).length} 个字段需修正`)
}
function reloadGroup(group: SystemSettingGroup) {
  if (action.busy.value || load.busy.value) return
  void load.run(async () => {
    if (
      groupDirty(group) &&
      !(await confirm({
        title: '重新读取设置组',
        content: `重新读取“${group.label}”将放弃本组未保存修改，其他分组保持当前输入。`,
        confirmText: '放弃本组修改并读取',
        cancelText: '继续编辑'
      }))
    )
      return
    const value = (await api.admin.systemSettings.list()).find((item) => item.key === group.key)
    if (!value) throw new Error('此设置组已不可用，请重新进入页面')
    acceptGroup(value)
    fieldErrors.value[group.key] = {}
  })
}
function save(group: SystemSettingGroup) {
  if (!allowed('foundation.runtime_settings.update')) return
  if (load.busy.value) return
  void action.run(async () => {
    validateGroup(group)
    const result = await checked('foundation.runtime_settings.update', () =>
      api.admin.systemSettings.update(group.key, {
        expected_revision: group.revision,
        values: group.values
      })
    )
    acceptGroup(result)
    message.success(`“${group.label}”已保存，当前修订 ${result.revision}`)
  })
}
const historyLoad = useResource(async (signal) => {
  const group = selected.value!
  return { group, versions: await api.admin.systemSettings.history(group.key, { signal }) }
})
const history = computed(() => historyLoad.data.value?.versions ?? [])
function closeHistory() {
  historyOpen.value = false
  historyLoad.invalidate()
  historyLoad.data.value = undefined
}
const { requestClose: historyClose } = useDrawerClose({
  open: () => historyOpen.value,
  busy: () => action.busy.value,
  dirty: () => false,
  close: closeHistory
})
function openHistory(group: SystemSettingGroup) {
  if (action.busy.value || load.busy.value) return
  selected.value = { ...group }
  historyLoad.data.value = undefined
  historyOpen.value = true
  void historyLoad.reload()
}
function rollback(version: SystemSettingVersion) {
  if (!allowed('foundation.runtime_settings.update')) return
  if (version.contract_status !== 'SUPPORTED') return
  const snapshot = historyLoad.data.value
  if (
    !historyOpen.value ||
    !snapshot ||
    snapshot.group !== selected.value ||
    !snapshot.versions.includes(version)
  )
    return
  void action.run(async () => {
    const { key, revision } = snapshot.group
    if (
      !(await confirm({
        title: '恢复系统设置',
        content: `将“${snapshot.group.label}”当前配置替换为修订 ${version.revision} 的历史配置并保存？${selectedGroupDirty.value ? '本组未保存修改将被丢弃。' : ''}仅恢复本组，其他分组不受影响。`,
        confirmText: '恢复此修订',
        cancelText: '取消',
        danger: true
      }))
    )
      return
    const result = await checked('foundation.runtime_settings.update', () =>
      api.admin.systemSettings.rollback(key, {
        expected_revision: revision,
        target_revision: version.revision
      })
    )
    acceptGroup(result)
    fieldErrors.value[key] = {}
    closeHistory()
    message.success(`“${snapshot.group.label}”已恢复历史配置，当前修订 ${result.revision}`)
  })
}
// 按后端字段身份分组展示，不计算任何派生额度。
function sections(group: SystemSettingGroup) {
  if (group.key !== 'task_budget') return [{ label: '', fields: group.fields }]
  return [
    ['standard_root', '普通任务'],
    ['delegation_root', '协作任务'],
    ['team_root', '团队任务']
  ].map(([prefix, label]) => ({
    label,
    fields: group.fields.filter((field) => field.key.startsWith(prefix + '_'))
  }))
}
const scenarioLabels: Record<string, string> = {
  STANDARD: '普通',
  DELEGATION: '协作',
  TEAM: '团队'
}
function settingFieldHelp(group: string, field: SystemSettingField) {
  const help = systemSettingHelp(group, field)
  if (help.trim() || group !== 'workspace' || field.key !== 'enabled') return help
  return '允许会话使用文件工作区；修改后点击保存生效。'
}
onMounted(() => {
  reload()
  void contextPolicy.reload()
})

const formTipId = useId()
</script>

<template>
  <AdminPage
    title="系统参数"
    description="按业务分组调整参数，每组独立保存；有未保存修改的分组始终保留显示"
  >
    <div class="admin-toolbar">
      <div class="admin-filters">
        <ElInput
          v-model="search"
          aria-label="查找系统参数"
          placeholder="查找参数名称或分组"
          clearable
        />
      </div>
      <span v-if="dirty" class="admin-muted" role="status">有未保存的参数，请在对应分组保存</span>
    </div>
    <FeedbackNotice v-if="error" :title="error" :closable="false" type="error" />
    <ElButton v-if="load.error.value && !groups.length" :loading="load.busy.value" @click="reload"
      >重新读取系统参数</ElButton
    >
    <ElButton
      v-if="contextPolicy.error.value"
      :loading="contextPolicy.loading.value"
      @click="contextPolicy.reload"
      >重新读取上下文策略</ElButton
    >
    <ElSkeleton v-if="load.busy.value && !groups.length" :rows="8" />
    <ElEmpty
      v-if="!load.busy.value && !load.error.value && !groups.length"
      description="暂无系统参数"
    />
    <ElAlert
      v-if="contextPolicyVersion"
      :title="`上下文策略 ${contextPolicyVersion} · 由代码管理`"
      type="info"
      :closable="false"
    />
    <ElEmpty v-if="groups.length && !visibleGroups.length" description="未找到匹配的参数分组"
      ><ElButton @click="search = ''">清除搜索</ElButton></ElEmpty
    >
    <ElCard class="admin-card" v-for="group in visibleGroups" :key="group.key">
      <template #header>
        <div class="admin-actions">
          <strong>{{ group.label }}</strong>
          <ElTag>修订 {{ group.revision }}</ElTag>
          <ElTag v-if="groupDirty(group)" type="warning">未保存</ElTag>
          <ElButton
            link
            :disabled="action.busy.value || load.busy.value"
            @click="reloadGroup(group)"
            >重新读取本组</ElButton
          >
          <ElButton
            link
            :disabled="action.busy.value || load.busy.value"
            @click="openHistory(group)"
            >历史版本</ElButton
          >
        </div>
      </template>
      <ElForm
        @keydown.capture="guardFormEnter"
        label-position="top"
        :disabled="
          !allowed('foundation.runtime_settings.update') || action.busy.value || load.busy.value
        "
        @submit.prevent="save(group)"
      >
        <section v-for="section in sections(group)" :key="section.label">
          <h3 v-if="section.label">{{ section.label }}</h3>
          <div class="admin-form-grid">
            <ElFormItem
              v-for="field in section.fields"
              :key="field.key"
              :label="field.label + (field.unit ? '（' + field.unit + '）' : '')"
              :error="fieldErrors[group.key]?.[field.key]"
            >
              <ElSwitch
                v-described-by="
                  formTipId +
                  '-field-0' +
                  '-' +
                  encodeURIComponent(String(group.key)) +
                  '-' +
                  encodeURIComponent(String(section.label)) +
                  '-' +
                  encodeURIComponent(String(field.key))
                "
                :aria-describedby="
                  formTipId +
                  '-field-0' +
                  '-' +
                  encodeURIComponent(String(group.key)) +
                  '-' +
                  encodeURIComponent(String(section.label)) +
                  '-' +
                  encodeURIComponent(String(field.key))
                "
                v-if="field.type === 'boolean'"
                v-model="group.values[field.key] as boolean"
              />
              <ElInputNumber
                v-number-aria
                v-described-by="
                  formTipId +
                  '-field-0' +
                  '-' +
                  encodeURIComponent(String(group.key)) +
                  '-' +
                  encodeURIComponent(String(section.label)) +
                  '-' +
                  encodeURIComponent(String(field.key))
                "
                :aria-describedby="
                  formTipId +
                  '-field-0' +
                  '-' +
                  encodeURIComponent(String(group.key)) +
                  '-' +
                  encodeURIComponent(String(section.label)) +
                  '-' +
                  encodeURIComponent(String(field.key))
                "
                v-else-if="field.type === 'number'"
                v-model="group.values[field.key] as number"
                :min="field.minimum ?? undefined"
                :max="field.maximum ?? undefined"
              />
              <ElSelect
                v-described-by="
                  formTipId +
                  '-field-0' +
                  '-' +
                  encodeURIComponent(String(group.key)) +
                  '-' +
                  encodeURIComponent(String(section.label)) +
                  '-' +
                  encodeURIComponent(String(field.key))
                "
                :aria-describedby="
                  formTipId +
                  '-field-0' +
                  '-' +
                  encodeURIComponent(String(group.key)) +
                  '-' +
                  encodeURIComponent(String(section.label)) +
                  '-' +
                  encodeURIComponent(String(field.key))
                "
                v-else-if="field.type === 'select'"
                v-model="group.values[field.key] as string"
              >
                <ElOption
                  v-if="
                    group.values[field.key] &&
                    !field.choices?.includes(String(group.values[field.key]))
                  "
                  :value="String(group.values[field.key])"
                  :label="String(group.values[field.key]) + '（已不可用）'"
                  disabled
                />
                <ElOption v-for="choice in field.choices" :key="choice" :value="choice" />
              </ElSelect>
              <ElInput
                v-described-by="
                  formTipId +
                  '-field-0' +
                  '-' +
                  encodeURIComponent(String(group.key)) +
                  '-' +
                  encodeURIComponent(String(section.label)) +
                  '-' +
                  encodeURIComponent(String(field.key))
                "
                :aria-describedby="
                  formTipId +
                  '-field-0' +
                  '-' +
                  encodeURIComponent(String(group.key)) +
                  '-' +
                  encodeURIComponent(String(section.label)) +
                  '-' +
                  encodeURIComponent(String(field.key))
                "
                v-else
                v-model="group.values[field.key] as string"
                :type="field.secret ? 'password' : field.type === 'text' ? 'textarea' : 'text'"
                :rows="4"
                :show-password="field.secret"
              />

              <FieldTip
                :id="
                  formTipId +
                  '-field-0' +
                  '-' +
                  encodeURIComponent(String(group.key)) +
                  '-' +
                  encodeURIComponent(String(section.label)) +
                  '-' +
                  encodeURIComponent(String(field.key))
                "
                >{{ settingFieldHelp(group.key, field) }}</FieldTip
              >
            </ElFormItem>
          </div>
        </section>
        <ElButton
          v-permission="['foundation.runtime_settings.update']"
          type="primary"
          native-type="submit"
          :loading="action.busy.value"
          >保存 {{ group.label }}</ElButton
        >
      </ElForm>
      <template v-if="group.key === 'task_budget'">
        <p class="admin-field-help">
          额度作用于整个任务；暂停期间不计活跃时长。累计 Tokens
          为资源上限。保存后下列默认角色额度更新，Agent 显式覆盖受任务根额度约束。
        </p>
        <ElTable :data="group.derived_budgets">
          <ElTableColumn label="场景"
            ><template #default="{ row }">{{
              scenarioLabels[row.scenario]
            }}</template></ElTableColumn
          >
          <ElTableColumn label="角色"
            ><template #default="{ row }">{{
              row.role === 'PRIMARY' ? '主 Agent' : '成员'
            }}</template></ElTableColumn
          >
          <ElTableColumn prop="business_models" label="业务模型" />
          <ElTableColumn prop="auxiliary_models" label="辅助模型" />
          <ElTableColumn prop="tools" label="工具" />
          <ElTableColumn prop="knowledge" label="知识" />
          <ElTableColumn prop="total_tokens" label="累计总 Tokens" />
          <ElTableColumn prop="active_seconds" label="活跃秒数" />
        </ElTable>
      </template>
    </ElCard>
    <ElDrawer
      class="admin-drawer"
      direction="rtl"
      append-to-body
      v-model="historyOpen"
      :before-close="historyClose"
      :title="selected?.label + ' · 历史'"
      size="680px"
      :close-on-click-modal="false"
    >
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        :closable="false"
        type="error"
      />
      <p v-if="selectedGroupDirty" class="admin-field-help">恢复历史版本将丢弃本组未保存修改。</p>
      <FeedbackNotice
        v-if="historyLoad.error.value"
        :title="historyLoad.error.value"
        :closable="false"
        type="error"
      />
      <ElSkeleton v-if="historyLoad.loading.value" :rows="4" />
      <ElEmpty
        v-if="!historyLoad.loading.value && !history.length && !historyLoad.error.value"
        description="暂无历史版本"
      />
      <ElCard class="admin-card" v-for="version in history" :key="version.revision">
        <h3>修订 {{ version.revision }}</h3>
        <ElTag v-if="version.contract_status === 'RETIRED'" type="info">旧契约</ElTag>
        <p>{{ formatDateTime(version.created_at) }}</p>
        <p>操作人：{{ version.actor_id ?? '未知' }}</p>
        <p v-if="version.contract_status === 'RETIRED'" class="admin-field-help">
          该修订使用已停用的配置格式，详情不可用，无法回滚；版本记录仍保留。
        </p>
        <ElDescriptions v-if="version.contract_status === 'SUPPORTED'" :column="1" border>
          <ElDescriptionsItem
            v-for="(value, key) in version.values"
            :key="key"
            :label="selected?.fields.find((field) => field.key === key)?.label ?? String(key)"
            >{{
              selected?.fields.find((f) => f.key === key)?.secret ? '••••••' : value
            }}</ElDescriptionsItem
          >
        </ElDescriptions>
        <ElButton
          v-permission="['foundation.runtime_settings.update']"
          :disabled="
            version.contract_status !== 'SUPPORTED' ||
            action.busy.value ||
            version.revision === selected?.revision
          "
          @click="rollback(version)"
          >恢复此版本</ElButton
        >
      </ElCard>
    </ElDrawer>
  </AdminPage>
</template>
