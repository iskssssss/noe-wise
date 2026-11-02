<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import {
  AdminPage,
  guardFormEnter,
  useDrawerClose,
  useResource,
  useTask
} from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { acceptUnhighlightedCreate } from '@skillforge/shared/admin/creatableOption'
import type {
  AgentTemplateDetail,
  AgentTemplateDefinition,
  AgentTemplateItem,
  AgentDefinitionImpact,
  AgentTemplateValidation
} from '@skillforge/shared/integration/agentDefinitions'
import { api } from '../../api'
import { useManagementIdentity } from '../../composables/managementIdentity'
import { useManagementCommand } from '../../composables/managementCommand'
const identity = useManagementIdentity(),
  page = ref(1),
  search = ref('')
defineProps<{ tenantId?: string; embedded?: boolean }>()
function changePage(value: number) {
  page.value = value
  void list.reload()
}
function query() {
  page.value = 1
  void list.reload()
}
const list = useResource(async (signal) => {
  const value = await api.agentDefinitions.list(
    { page: page.value, limit: 20, name: search.value.trim() },
    { signal, silent: true }
  )
  if (!identity.current()) throw Error('账号已变化，请重新进入页面')
  return value
})
const options = useResource(async () => {
  const [models, capabilities] = await Promise.all([
    api.settings.availableModels(),
    api.capabilities.listDefinitions({ status: 'PUBLISHED' })
  ])
  if (!identity.current()) throw Error('账号已变化，请重新进入页面')
  return {
    models: models.filter((m) => m.kind === 'LLM'),
    capabilities: capabilities.filter(
      (c) => c.defaultVersionId && (c.capabilityType === 'SKILL' || c.capabilityType === 'TOOL')
    )
  }
})
const write = useManagementCommand()
const action = useTask(),
  open = ref(false),
  item = ref<AgentTemplateDetail>(),
  history = ref<AgentTemplateDetail[]>([]),
  historyRevision = ref('')
const validation = ref<AgentTemplateValidation>(),
  impact = ref<AgentDefinitionImpact>()
const refreshRequired = ref(false)
function blank(): AgentTemplateDefinition {
  return {
    system_prompt: '',
    role_description: '',
    model_config_id: null,
    execution_config: {
      max_business_model_calls: null,
      max_tool_calls: null,
      max_knowledge_calls: null,
      max_total_tokens: null,
      max_wall_time_seconds: null
    },
    capabilities: [],
    resource_slots: [],
    delegation_slots: []
  }
}
const definition = ref<AgentTemplateDefinition>(blank()),
  form = reactive({ name: '', description: '', category: '', tags: [] as string[] })
function onTagEnter(event: KeyboardEvent) {
  acceptUnhighlightedCreate(
    event,
    canEdit.value && !action.busy.value && !write.pending.value,
    [],
    (value) => {
      if (!form.tags.includes(value)) form.tags.push(value)
    }
  )
}
const baseline = ref('')
const snapshot = () => JSON.stringify({ form, definition: definition.value })
const dirty = computed(() => snapshot() !== baseline.value)
const previewSnapshot = ref('')
const previewCurrent = computed(
  () => !!previewSnapshot.value && previewSnapshot.value === snapshot()
)
const canEdit = computed(() =>
  identity.allowed(item.value ? 'tenant.agent_definitions.edit' : 'tenant.agent_definitions.create')
)
const close = useDrawerClose({
  open: () => open.value,
  busy: () => action.busy.value || !!write.pending.value,
  dirty: () => dirty.value,
  close: () => {
    open.value = false
  }
})
const budgets = [
  ['max_business_model_calls', '业务模型调用次数'],
  ['max_tool_calls', '最大工具调用'],
  ['max_knowledge_calls', '最大知识检索'],
  ['max_total_tokens', '累计总 Tokens'],
  ['max_wall_time_seconds', '活跃执行时长（秒）']
] as const
function accept(value: AgentTemplateDetail) {
  item.value = value
  Object.assign(form, {
    name: value.name,
    description: value.description ?? '',
    category: value.category ?? '',
    tags: [...(value.tags ?? [])]
  })
  definition.value = JSON.parse(JSON.stringify(value.definition))
  baseline.value = snapshot()
  validation.value = undefined
  impact.value = undefined
  previewSnapshot.value = ''
  refreshRequired.value = false
}
function begin(row?: AgentTemplateItem) {
  if (action.busy.value) return
  void action.run(async () => {
    action.error.value = ''
    history.value = []
    historyRevision.value = ''
    if (row) {
      const value = await api.agentDefinitions.detail(row.definition_id, { silent: true })
      identity.requirePermission('tenant.agent_definitions.read')
      accept(value)
    } else {
      identity.requirePermission('tenant.agent_definitions.create')
      item.value = undefined
      definition.value = blank()
      Object.assign(form, { name: '', description: '', category: '', tags: [] })
      baseline.value = snapshot()
      validation.value = undefined
      impact.value = undefined
      previewSnapshot.value = ''
      refreshRequired.value = false
    }
    open.value = true
    if (canEdit.value) void options.reload()
  })
}
function selectCapability(index: number) {
  const mount = definition.value.capabilities[index]!
  const selected = options.data.value?.capabilities.find(
    (c) => c.id === mount.capability_definition_id
  )
  if (!selected) return
  mount.capability_type = selected.capabilityType as 'SKILL' | 'TOOL'
  mount.capability_version_id = selected.defaultVersionId ?? null
  mount.platform_tool_name = null
  mount.skill_config =
    mount.capability_type === 'SKILL' ? { activation_mode: 'ON_DEMAND', token_budget: null } : null
}
function save(validateAfterSave = false) {
  if (refreshRequired.value) return
  void action.run(async () => {
    const permission = item.value
      ? 'tenant.agent_definitions.edit'
      : 'tenant.agent_definitions.create'
    if (!form.name.trim() || !definition.value.system_prompt.trim())
      throw Error('请填写定义名称与系统提示词')
    const payload = {
      ...form,
      name: form.name.trim(),
      definition: JSON.parse(JSON.stringify(definition.value)) as AgentTemplateDefinition,
      distribution_policy: 'AUTHORIZED_DEPLOY' as const
    }
    identity.requirePermission(permission)
    const target = item.value
    await write.execute(
      (key) =>
        target
          ? api.agentDefinitions.update(target.definition_id, target.draft_revision, payload, key)
          : api.agentDefinitions.create(payload, key),
      async (result) => {
        accept({ ...result, definition: payload.definition })
        refreshRequired.value = true
        if (!validateAfterSave) message.success('共享定义草稿已保存')
        void list.reload()
        if (!identity.allowed('tenant.agent_definitions.read')) {
          message.warning('草稿已保存，请有查看权限的管理员重新读取后继续维护')
          return
        }
        try {
          const saved = await api.agentDefinitions.detail(result.definition_id, { silent: true })
          identity.requirePermission('tenant.agent_definitions.read')
          if (
            saved.definition_id !== result.definition_id ||
            saved.draft_revision !== result.draft_revision
          )
            throw Error('草稿已被其他操作更新')
          accept(saved)
        } catch {
          if (identity.current())
            message.warning('草稿已保存，但未能读取保存结果；请重新读取，不要重复保存')
          return
        }
        if (validateAfterSave) {
          if (
            !identity.allowed('tenant.agent_definitions.validate') ||
            !identity.allowed('tenant.agent_definitions.read')
          ) {
            if (identity.current()) message.warning('草稿已保存，请由有权限的管理员校验。')
            return
          }
          try {
            await performPreview()
            if (previewCurrent.value && validation.value?.valid && impact.value)
              message.success('草稿已保存并校验，发布影响已更新')
            else message.warning('草稿已保存，但校验未通过或影响不可用，请检查结果。')
          } catch (reason) {
            if (reason instanceof HttpClientError) reason.claimFeedback()
            if (identity.current()) message.warning('草稿已保存，但校验未完成，请重新校验。')
          }
        }
      },
      () => identity.requirePermission(permission)
    )
  })
}
function reload() {
  void action.run(async () => {
    if (!item.value) return
    const id = item.value.definition_id
    if (
      dirty.value &&
      !(await confirm({
        title: '重新读取草稿',
        content: '将丢弃未保存的字段内容并读取最新修订。',
        confirmText: '丢弃并重新读取',
        cancelText: '继续编辑'
      }))
    )
      return
    identity.requirePermission('tenant.agent_definitions.read')
    const value = await api.agentDefinitions.detail(id, { silent: true })
    identity.requirePermission('tenant.agent_definitions.read')
    accept(value)
  })
}
function preview() {
  if (refreshRequired.value) return
  void action.run(performPreview)
}
async function performPreview() {
  if (!item.value) return
  const target = { ...item.value },
    frozen = snapshot(),
    candidate = JSON.parse(JSON.stringify(definition.value)) as AgentTemplateDefinition
  identity.requirePermission('tenant.agent_definitions.validate')
  const result = dirty.value
    ? await api.agentDefinitions.previewContent(
        target.definition_id,
        target.draft_revision,
        candidate,
        { silent: true }
      )
    : await api.agentDefinitions.previewSaved(target.definition_id, target.draft_revision, {
        silent: true
      })
  identity.requirePermission('tenant.agent_definitions.validate')
  if (result.impact) identity.requirePermission('tenant.agent_definitions.read')
  if (
    snapshot() !== frozen ||
    item.value?.definition_id !== target.definition_id ||
    item.value.draft_revision !== target.draft_revision
  )
    return
  validation.value = result.validation
  impact.value = result.impact ?? undefined
  previewSnapshot.value = frozen
}
function publish() {
  void action.run(async () => {
    if (
      !item.value ||
      refreshRequired.value ||
      dirty.value ||
      !previewCurrent.value ||
      !validation.value?.valid ||
      impact.value?.draft_revision !== item.value.draft_revision
    )
      throw Error('请先保存并校验当前草稿，读取其影响预览')
    const target = { ...item.value },
      frozen = snapshot(),
      previewed = impact.value
    if (
      !(await confirm({
        title: '发布机构共享定义',
        content: `发布草稿修订 ${target.draft_revision}，当前可见 ${previewed.visible_deployment_count} 个部署将跟随此发布。存在缺项的部署会转为待完善；旧任务保留冻结版本。`,
        confirmText: '发布此修订',
        cancelText: '继续编辑'
      }))
    )
      return
    identity.requirePermission('tenant.agent_definitions.publish')
    if (snapshot() !== frozen) throw Error('草稿内容已变化，请重新校验预览')
    await write.execute(
      (key) => api.agentDefinitions.publish(target.definition_id, target.draft_revision, key),
      (result) => {
        item.value = { ...result, definition: definition.value }
        baseline.value = snapshot()
        impact.value = undefined
        validation.value = undefined
        previewSnapshot.value = ''
        message.success('机构共享定义已发布')
        void list.reload()
      },
      () => identity.requirePermission('tenant.agent_definitions.publish')
    )
  })
}
function loadHistory() {
  void action.run(async () => {
    if (!item.value) return
    const values = await api.agentDefinitions.history(item.value.definition_id, { silent: true })
    identity.requirePermission('tenant.agent_definitions.read')
    history.value = values
  })
}
function restore() {
  if (refreshRequired.value) return
  void action.run(async () => {
    if (!item.value || !historyRevision.value) throw Error('请选择发布历史')
    const target = { ...item.value },
      revision = historyRevision.value
    if (
      !(await confirm({
        title: '从发布历史恢复草稿',
        content: '所选历史内容将成为新的工作草稿，当前未保存修改会丢弃。恢复不会直接发布。',
        confirmText: '恢复为草稿',
        cancelText: '取消'
      }))
    )
      return
    identity.requirePermission('tenant.agent_definitions.restore')
    await write.execute(
      (key) =>
        api.agentDefinitions.restore(target.definition_id, target.draft_revision, revision, key),
      async (result) => {
        const restored = history.value.find((v) => v.current_published_revision === revision)
        if (restored) accept({ ...result, definition: restored.definition })
        else {
          item.value = { ...result, definition: definition.value }
          baseline.value = snapshot()
          impact.value = undefined
          validation.value = undefined
        }
        message.success('已恢复为工作草稿')
        void list.reload()
        try {
          const detail = await api.agentDefinitions.detail(target.definition_id, { silent: true })
          identity.requirePermission('tenant.agent_definitions.read')
          accept(detail)
        } catch {
          message.warning('恢复已成功，草稿刷新失败，请主动重新读取后继续编辑')
        }
      },
      () => identity.requirePermission('tenant.agent_definitions.restore')
    )
  })
}
function deprecate() {
  if (refreshRequired.value) return
  void action.run(async () => {
    if (!item.value) return
    const target = { ...item.value }
    if (
      !(await confirm({
        title: '停用共享定义',
        content: '停用后按服务端当前可用性阻止新的部署与执行。确认停用？',
        confirmText: '停用定义',
        cancelText: '取消'
      }))
    )
      return
    identity.requirePermission('tenant.agent_definitions.deprecate')
    await write.execute(
      (key) => api.agentDefinitions.deprecate(target.definition_id, target.draft_revision, key),
      (result) => {
        item.value = { ...result, definition: definition.value }
        message.success('共享定义已停用')
        void list.reload()
      },
      () => identity.requirePermission('tenant.agent_definitions.deprecate')
    )
  })
}
onMounted(() => {
  void list.reload()
})
</script>
<template>
  <AdminPage
    :embedded="embedded"
    title="机构共享定义"
    description="维护本机构拥有的共享源。保存工作草稿、查看发布影响后发布；授权及下级部署分别管理。"
  >
    <template #extra
      ><ElButton
        v-if="identity.allowed('tenant.agent_definitions.create')"
        type="primary"
        :disabled="action.busy.value || !!write.pending.value"
        @click="begin()"
        >新建共享定义</ElButton
      ></template
    >
    <FeedbackNotice
      v-if="list.error.value || (!open && action.error.value)"
      :title="action.error.value || '刷新失败，保留上次内容：' + list.error.value"
      type="error"
      :closable="false"
    />
    <div class="admin-card admin-toolbar">
      <div>
        <strong>编写定义 · 校验影响 · 发布共享</strong>
        <p class="admin-muted">可预览当前编辑内容对现有机构实例的影响，保存并确认后发布。</p>
      </div>
      <ElTag>{{ list.data.value?.total ?? 0 }} 个自有定义</ElTag>
    </div>
    <ElForm class="admin-filters" inline @submit.prevent="query"
      ><ElFormItem label="名称筛选"><ElInput v-model="search" clearable /></ElFormItem
      ><ElButton native-type="submit" :loading="list.loading.value">查询</ElButton></ElForm
    >
    <ElTable
      :data="list.data.value?.items ?? []"
      table-layout="auto"
      row-key="definition_id"
      v-loading="list.loading.value"
      empty-text="暂无本机构自有共享定义"
    >
      <ElTableColumn prop="name" label="名称" /><ElTableColumn
        prop="definition_id"
        label="定义标识"
      /><ElTableColumn prop="draft_revision" label="草稿修订" /><ElTableColumn
        prop="current_published_revision"
        label="当前发布"
      /><ElTableColumn prop="status" label="状态" /><ElTableColumn label="草稿"
        ><template #default="{ row }">{{
          row.has_unpublished_changes ? '有未发布变更' : '与发布一致'
        }}</template></ElTableColumn
      ><ElTableColumn label="操作"
        ><template #default="{ row }"
          ><ElButton
            link
            :disabled="action.busy.value || !!write.pending.value"
            @click="begin(row as AgentTemplateItem)"
            >查看与维护</ElButton
          ></template
        ></ElTableColumn
      >
    </ElTable>
    <ElPagination
      :current-page="page"
      :page-size="20"
      :total="list.data.value?.total ?? 0"
      layout="prev, pager, next, total"
      @current-change="changePage"
    />
    <ElDrawer
      v-model="open"
      :title="item ? '维护机构共享定义' : '新建机构共享定义'"
      size="820px"
      append-to-body
      :before-close="close.requestClose"
      :close-on-click-modal="false"
      :show-close="!action.busy.value && !write.pending.value"
      :close-on-press-escape="!action.busy.value && !write.pending.value"
    >
      <FeedbackNotice
        v-if="action.error.value"
        :title="action.error.value"
        type="error"
        :closable="false"
      />
      <p v-if="item">
        定义 {{ item.definition_id }} · 草稿修订 {{ item.draft_revision }} · 当前发布
        {{ item.current_published_revision ?? '尚未发布' }}
      </p>
      <p v-if="refreshRequired" role="status">草稿已保存，需重新读取保存结果后继续维护。</p>
      <ElButton
        v-if="item && identity.allowed('tenant.agent_definitions.read')"
        :disabled="action.busy.value || !!write.pending.value"
        @click="reload"
        >重新读取草稿</ElButton
      >
      <FeedbackNotice
        v-if="options.error.value"
        :title="'模型与能力目录读取失败，现有字段保留：' + options.error.value"
        type="warning"
        :closable="false"
      /><ElButton v-if="options.error.value" @click="options.reload">重试目录</ElButton>
      <ElAlert
        v-if="write.pending.value"
        title="本次定义操作的结果尚未确认，原内容已保留，后续写入已暂停。请点击“核对原请求”确认结果，避免重复操作。"
        type="warning"
        :closable="false"
      /><ElButton
        v-if="write.pending.value"
        :loading="action.busy.value"
        @click="action.run(write.reconcile)"
        >核对原请求</ElButton
      ><ElForm
        :model="form"
        label-position="top"
        :disabled="action.busy.value || !!write.pending.value || refreshRequired || !canEdit"
        @submit.prevent="save()"
        @keydown.capture="guardFormEnter"
      >
        <h3>基本信息</h3>
        <ElFormItem label="定义名称" required
          ><ElInput v-model="form.name" maxlength="200" /></ElFormItem
        ><ElFormItem label="用途说明"
          ><ElInput v-model="form.description" type="textarea" :rows="2" /></ElFormItem
        ><ElFormItem label="分类"><ElInput v-model="form.category" /></ElFormItem
        ><ElFormItem label="标签"
          ><ElSelect
            v-model="form.tags"
            @keydown.enter.capture="onTagEnter"
            multiple
            filterable
            allow-create
            default-first-option
        /></ElFormItem>
        <ElFormItem label="角色描述"
          ><ElInput v-model="definition.role_description" type="textarea" :rows="2" /></ElFormItem
        ><ElFormItem label="系统提示词" required
          ><ElInput v-model="definition.system_prompt" type="textarea" :rows="6"
        /></ElFormItem>
        <ElFormItem label="执行模型"
          ><ElSelect
            v-model="definition.model_config_id"
            clearable
            filterable
            placeholder="继承机构默认模型"
            :loading="options.loading.value"
            ><ElOption
              v-for="model in options.data.value?.models ?? []"
              :key="model.id"
              :value="model.id"
              :label="model.name" /></ElSelect
        ></ElFormItem>
        <ElCollapse
          ><ElCollapseItem title="执行预算（可选）" name="budget">
            <p class="admin-field-help">留空继承平台默认预算。</p>
            <ElFormItem v-for="[key, label] in budgets" :key="key" :label="label"
              ><ElInputNumber v-model="definition.execution_config[key]" :min="1" :precision="0"
            /></ElFormItem> </ElCollapseItem
        ></ElCollapse>
        <h3>基础能力</h3>
        <p class="admin-field-help">选择能力后，保存时自动采用当前发布配置。</p>
        <div v-for="(mount, index) in definition.capabilities" :key="index" class="field-block">
          <ElFormItem label="已发布能力"
            ><ElSelect
              v-model="mount.capability_definition_id"
              filterable
              @change="selectCapability(index)"
              ><ElOption
                v-for="cap in options.data.value?.capabilities ?? []"
                :key="cap.id"
                :value="cap.id"
                :label="cap.name" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="优先级"
            ><ElInputNumber v-model="mount.priority" :precision="0" /></ElFormItem
          ><ElFormItem v-if="mount.skill_config" label="技能 Token 预算"
            ><ElInputNumber
              v-model="mount.skill_config.token_budget"
              :min="1"
              :precision="0" /></ElFormItem
          ><ElButton type="danger" link @click="definition.capabilities.splice(index, 1)"
            >删除基础能力</ElButton
          >
        </div>
        <ElButton
          @click="
            definition.capabilities.push({
              capability_type: 'SKILL',
              capability_definition_id: '',
              capability_version_id: null,
              skill_config: { activation_mode: 'ON_DEMAND', token_budget: null },
              platform_tool_name: null,
              priority: 0
            })
          "
          >新增基础能力</ElButton
        >
        <ElCollapse
          ><ElCollapseItem
            :title="'机构资源槽位（' + definition.resource_slots.length + '）'"
            name="resources"
          >
            <div
              v-for="(slot, index) in definition.resource_slots"
              :key="index"
              class="field-block admin-form-grid"
            >
              <ElFormItem label="资源槽键"><ElInput v-model="slot.key" maxlength="64" /></ElFormItem
              ><ElFormItem label="资源槽名称"><ElInput v-model="slot.label" /></ElFormItem
              ><ElFormItem label="资源槽说明"><ElInput v-model="slot.description" /></ElFormItem
              ><ElFormItem label="资源类型"
                ><ElSelect v-model="slot.capability_type"
                  ><ElOption value="TOOL" label="工具" /><ElOption
                    value="WORKFLOW"
                    label="工作流" /><ElOption
                    value="KNOWLEDGE_SOURCE"
                    label="知识源" /></ElSelect></ElFormItem
              ><ElButton type="danger" link @click="definition.resource_slots.splice(index, 1)"
                >删除资源槽</ElButton
              >
            </div>
            <ElButton
              @click="
                definition.resource_slots.push({
                  key: '',
                  label: '',
                  description: '',
                  capability_type: 'TOOL'
                })
              "
              >新增资源槽</ElButton
            > </ElCollapseItem
          ><ElCollapseItem
            :title="'协作槽位（' + definition.delegation_slots.length + '）'"
            name="delegates"
          >
            <div
              v-for="(slot, index) in definition.delegation_slots"
              :key="index"
              class="field-block admin-form-grid"
            >
              <ElFormItem label="协作槽键"><ElInput v-model="slot.key" maxlength="64" /></ElFormItem
              ><ElFormItem label="协作槽名称"><ElInput v-model="slot.label" /></ElFormItem
              ><ElFormItem label="协作槽说明"><ElInput v-model="slot.description" /></ElFormItem
              ><ElButton type="danger" link @click="definition.delegation_slots.splice(index, 1)"
                >删除协作槽</ElButton
              >
            </div>
            <ElButton
              @click="definition.delegation_slots.push({ key: '', label: '', description: '' })"
              >新增协作槽</ElButton
            >
          </ElCollapseItem></ElCollapse
        >
      </ElForm>
      <section v-if="item">
        <h3>校验与发布影响</h3>
        <ElButton
          v-if="identity.allowed('tenant.agent_definitions.validate')"
          :disabled="action.busy.value || !!write.pending.value || refreshRequired"
          @click="preview"
          >校验并预览影响</ElButton
        ><ElAlert
          v-if="validation && previewCurrent"
          :title="validation.valid ? '定义配置校验通过' : '定义配置校验未通过'"
          :type="validation.valid ? 'success' : 'warning'"
          :closable="false"
        />
        <ul v-if="previewCurrent && validation?.issues.length">
          <li v-for="issue in validation.issues" :key="issue.path + issue.code">
            {{ issue.path }}：{{ issue.message }}
          </li>
        </ul>
        <div v-if="impact && previewCurrent">
          <p v-if="dirty">当前为未保存内容预览；保存后需重新校验确认才能发布。</p>
          <p>
            新增参数 {{ impact.changes.added_parameter_keys?.join('、') || '无' }}；移除参数
            {{ impact.changes.removed_parameter_keys?.join('、') || '无' }}
          </p>
          <p>
            预览草稿修订 {{ impact.draft_revision }} · 可见部署
            {{ impact.visible_deployment_count }} 个
          </p>
          <p>
            新增资源槽
            {{
              impact.changes.added_resource_slots.map((s) => s.key).join('、') || '无'
            }}；变更资源槽
            {{
              impact.changes.changed_resource_slots.map((s) => s.key).join('、') || '无'
            }}；移除资源槽 {{ impact.changes.removed_resource_slot_keys.join('、') || '无' }}
          </p>
          <p>
            新增协作槽
            {{
              impact.changes.added_delegation_slots.map((s) => s.key).join('、') || '无'
            }}；移除协作槽 {{ impact.changes.removed_delegation_slot_keys.join('、') || '无' }}
          </p>
          <ElTable :data="impact.deployments" table-layout="auto"
            ><ElTableColumn prop="tenant_id" label="机构" /><ElTableColumn
              prop="deployment_id"
              label="部署"
            /><ElTableColumn label="发布后声明"
              ><template #default="{ row }"
                >{{ row.draft_declarations_satisfied ? '满足' : '待完善' }}
                <ul>
                  <li v-for="issue in row.draft_issues" :key="issue.path + issue.code">
                    {{ issue.message }}
                  </li>
                </ul></template
              ></ElTableColumn
            ></ElTable
          >
        </div>
        <ElButton
          v-if="identity.allowed('tenant.agent_definitions.publish')"
          type="primary"
          :disabled="
            action.busy.value ||
            !!write.pending.value ||
            refreshRequired ||
            dirty ||
            !previewCurrent ||
            !validation?.valid ||
            !impact
          "
          @click="publish"
          >发布此修订</ElButton
        >
        <ElCollapse
          ><ElCollapseItem title="发布历史与生命周期" name="history">
            <ElButton :disabled="action.busy.value || !!write.pending.value" @click="loadHistory"
              >读取发布历史</ElButton
            ><ElSelect v-model="historyRevision" placeholder="选择历史发布"
              ><ElOption
                v-for="version in history"
                :key="version.current_published_revision ?? version.version"
                :value="version.current_published_revision ?? version.version"
                :label="
                  version.version + ' · ' + (version.current_published_revision ?? '')
                " /></ElSelect
            ><ElButton
              v-if="identity.allowed('tenant.agent_definitions.restore')"
              :disabled="
                action.busy.value || !!write.pending.value || refreshRequired || !historyRevision
              "
              @click="restore"
              >恢复为草稿</ElButton
            ><ElButton
              v-if="identity.allowed('tenant.agent_definitions.deprecate')"
              type="danger"
              :disabled="action.busy.value || !!write.pending.value || refreshRequired"
              @click="deprecate"
              >停用定义</ElButton
            >
          </ElCollapseItem></ElCollapse
        >
      </section>
      <template #footer
        ><ElButton
          :disabled="action.busy.value || !!write.pending.value"
          @click="close.requestClose"
          >关闭</ElButton
        ><ElButton
          v-if="canEdit"
          :type="
            dirty &&
            identity.allowed('tenant.agent_definitions.read') &&
            identity.allowed('tenant.agent_definitions.validate')
              ? undefined
              : 'primary'
          "
          :loading="action.busy.value"
          :disabled="!!write.pending.value || refreshRequired"
          @click="save()"
          >保存草稿</ElButton
        ><ElButton
          v-if="
            canEdit &&
            dirty &&
            identity.allowed('tenant.agent_definitions.read') &&
            identity.allowed('tenant.agent_definitions.validate')
          "
          type="primary"
          :loading="action.busy.value"
          :disabled="!!write.pending.value || refreshRequired"
          @click="save(true)"
          >保存并校验</ElButton
        ></template
      >
    </ElDrawer>
  </AdminPage>
</template>
<style scoped>
.field-block {
  margin-block: 12px;
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
}
</style>
