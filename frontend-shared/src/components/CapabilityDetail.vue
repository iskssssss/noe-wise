<script setup lang="ts">
import FeedbackNotice from '../feedback/FeedbackNotice.vue'
import { computed, onMounted, reactive, ref, toRaw, useId } from 'vue'
import { statusLabel } from '../admin/presentation'
import { useRoute } from 'vue-router'
import { type FormInstance } from 'element-plus'
import { message, confirm } from '../feedback'
const formRef = ref<FormInstance>()
import { AdminPage, useTask, useUnsavedChanges, guardFormEnter } from '@skillforge/shared/admin'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import JsonSchemaFields from './JsonSchemaFields.vue'
import type {
  CapabilityCatalogApi,
  CapabilityDefinition,
  CapabilityVersion,
  CapabilityVersionSummary,
  CapabilityCatalogError
} from '../capabilities/catalog'
const props = defineProps<{
  api: CapabilityCatalogApi
  basePath: string
  skillBasePath: string
  permissions: readonly string[]
}>()
const hasPermission = (action: string) =>
  props.permissions.includes(`foundation.capabilities.${action}`)
const canCreate = computed(() => hasPermission('create'))
const canUpdate = computed(() => hasPermission('update'))
// 发布前当前客户端会写入验证结果，因此两个实际动作都必须获得授权。
const canPublish = computed(() => canUpdate.value && hasPermission('publish'))
const canSetDefault = computed(() => hasPermission('run'))
const canExport = computed(() => hasPermission('export'))
const id = String(useRoute().params.id ?? ''),
  load = useTask(),
  action = useTask(),
  definition = ref<CapabilityDefinition>(),
  versions = ref<CapabilityVersionSummary[]>([]),
  selected = ref<CapabilityVersion>(),
  editing = ref(false),
  showHistory = ref(false),
  advancedOpen = ref(false),
  baseline = ref(''),
  issues = ref<CapabilityCatalogError[]>([])
const activeVersions = computed(() =>
  versions.value.filter(
    (version) => version.status === 'DRAFT' || version.id === definition.value?.defaultVersionId
  )
)
const historicalVersions = computed(() =>
  versions.value.filter((version) => !activeVersions.value.includes(version))
)
const visibleVersions = computed(() => (showHistory.value ? versions.value : activeVersions.value))
const form = reactive({
  versionKey: '',
  name: '',
  description: '',
  model_invocable: true,
  user_invocable: false,
  tool_name: '',
  title: '',
  effect: 'READ_ONLY',
  idempotent: true,
  confirmation_policy: 'POLICY',
  timeout_ms: 30000,
  parallel_safe: false,
  sensitive_fields: [] as string[],
  provider_requirements: [] as string[],
  supports_hitl: false,
  export_fields: [] as string[],
  concurrency_safe: false,
  source_id: '',
  source_versions: [] as string[],
  allowed_operators: [] as string[],
  freshness_policy: '',
  citation_required: true,
  input_schema: '{\n  "type": "object",\n  "properties": {}\n}',
  output_schema: '{}',
  annotations: '{}',
  icons: '[]'
})
const dirty = computed(() => editing.value && JSON.stringify(form) !== baseline.value)
const canSave = computed(
  () =>
    (!selected.value || selected.value.status === 'DRAFT') &&
    (selected.value?.versionKey === form.versionKey ? canUpdate.value : canCreate.value)
)
const canEdit = computed(() =>
  !selected.value
    ? canCreate.value
    : selected.value.status === 'DRAFT' && (canUpdate.value || canCreate.value)
)
const canEditWorkingDraft = computed(() =>
  versions.value.some((v) => v.status === 'DRAFT')
    ? canUpdate.value || canCreate.value
    : canCreate.value
)
useUnsavedChanges({ dirty: () => dirty.value, busy: () => action.busy.value })
async function loadData() {
  const [nextDefinition, nextVersions] = await Promise.all([
    props.api.getDefinition(id),
    props.api.listVersionSummaries(id)
  ])
  definition.value = nextDefinition
  versions.value = nextVersions
}
function reload() {
  void load.run(loadData)
}
async function editWorkingDraft() {
  if (!canEditWorkingDraft.value) return
  const current =
    versions.value.find((v) => v.status === 'DRAFT') ??
    versions.value.find((v) => v.id === definition.value?.defaultVersionId)
  const success = await edit(current)
  if (success && current?.status !== 'DRAFT') {
    selected.value = undefined
    form.versionKey = ''
    baseline.value = JSON.stringify(form)
  }
}
async function restorePublication(value: CapabilityVersionSummary) {
  if (!canCreate.value) return
  await edit(value, true)
}
async function edit(summary?: CapabilityVersionSummary, restore = false) {
  if (
    action.busy.value ||
    load.busy.value ||
    !definition.value ||
    definition.value.capabilityType === 'SKILL'
  )
    return
  return action.run(async () => {
    if (restore) {
      if (dirty.value) throw new Error('请先保存当前修改')
      if (
        !(await confirm({
          title: '恢复能力草稿',
          confirmText: '恢复为草稿',
          danger: true,
          content: `将版本 ${summary!.versionKey} 的内容载入编辑区？请核对后保存为工作草稿，再验证和发布。`
        }))
      )
        return false
      if (!canCreate.value) return false
    }
    if (
      dirty.value &&
      !(await confirm({
        title: '切换版本',
        content: '切换后将丢弃未保存的版本修改。',
        confirmText: '丢弃并切换',
        cancelText: '继续编辑'
      }))
    )
      return false
    const value = summary ? await props.api.getVersion(summary.id) : undefined
    if (
      value &&
      (value.id !== summary!.id ||
        value.definitionId !== id ||
        value.capabilityType !== definition.value!.capabilityType)
    )
      throw new Error('版本详情与当前能力不匹配，请重新读取')
    selected.value = value
    Object.assign(form, {
      versionKey: value?.versionKey ?? '',
      name: value?.name ?? definition.value!.name,
      description: value?.description ?? definition.value!.description,
      model_invocable: true,
      user_invocable: false,
      tool_name: '',
      title: '',
      effect: 'READ_ONLY',
      idempotent: true,
      confirmation_policy: 'POLICY',
      timeout_ms: 30000,
      parallel_safe: false,
      sensitive_fields: [],
      provider_requirements: [],
      supports_hitl: false,
      export_fields: [],
      concurrency_safe: false,
      source_id: '',
      source_versions: [],
      allowed_operators: [],
      freshness_policy: '',
      citation_required: true
    })
    const d = value?.descriptor ?? {}
    for (const key of Object.keys(form)) {
      if (
        key in d &&
        key !== 'input_schema' &&
        key !== 'output_schema' &&
        key !== 'annotations' &&
        key !== 'icons'
      )
        (form as Record<string, unknown>)[key] = structuredClone(toRaw(d[key]))
    }
    for (const key of ['input_schema', 'output_schema', 'annotations', 'icons'] as const)
      form[key] = JSON.stringify(d[key] ?? (key === 'icons' ? [] : {}), null, 2)
    if (restore) {
      selected.value = undefined
      form.versionKey = ''
    }
    baseline.value = restore ? '' : JSON.stringify(form)
    editing.value = true
    advancedOpen.value = false
    issues.value = []
    return true
  })
}

function jsonRule(kind: 'object' | 'array') {
  return {
    validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
      try {
        const parsed = JSON.parse(value)
        const valid =
          kind === 'array'
            ? Array.isArray(parsed)
            : parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
        callback(
          valid ? undefined : new Error(kind === 'array' ? '请填写 JSON 数组' : '请填写 JSON 对象')
        )
      } catch {
        callback(new Error('JSON 格式不正确，请检查引号、逗号和括号'))
      }
    }
  }
}

/** 按当前版本生成冻结 descriptor，未填写的可选标题不进入 wire 或内容摘要。 */
function descriptor() {
  const base = { model_invocable: form.model_invocable, user_invocable: form.user_invocable }
  if (definition.value!.capabilityType === 'TOOL')
    return {
      ...base,
      tool_name: form.tool_name,
      // 可选标题未填写时省略字段；非空输入仍交由原 Tool 定义契约校验。
      ...(form.title === '' ? {} : { title: form.title }),
      input_schema: JSON.parse(form.input_schema),
      output_schema: JSON.parse(form.output_schema),
      icons: JSON.parse(form.icons),
      annotations: JSON.parse(form.annotations),
      effect: form.effect,
      idempotent: form.idempotent,
      confirmation_policy: form.confirmation_policy,
      timeout_ms: form.timeout_ms,
      sensitive_fields: form.sensitive_fields,
      provider_requirements: form.provider_requirements,
      parallel_safe: form.parallel_safe
    }
  if (definition.value!.capabilityType === 'WORKFLOW')
    return {
      ...base,
      input_schema: JSON.parse(form.input_schema),
      output_schema: JSON.parse(form.output_schema),
      supports_hitl: form.supports_hitl,
      export_fields: form.export_fields,
      timeout_ms: form.timeout_ms,
      concurrency_safe: form.concurrency_safe
    }
  return {
    ...base,
    source_id: form.source_id,
    source_versions: form.source_versions,
    allowed_operators: form.allowed_operators,
    freshness_policy: form.freshness_policy,
    citation_required: form.citation_required
  }
}
function save() {
  if (!canSave.value) return
  void action.run(async () => {
    if (!(await formRef.value?.validate().catch(() => false))) {
      advancedOpen.value = true
      return
    }
    const d = descriptor(),
      bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(d))),
      contentHash = Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, '0')).join(
        ''
      )
    const input = {
      versionKey: form.versionKey,
      name: form.name,
      description: form.description,
      descriptor: d,
      contentHash
    }
    if (!canSave.value) return
    const result =
      selected.value && selected.value.versionKey === form.versionKey
        ? await props.api.updateDraftVersion(
            id,
            selected.value.id,
            definition.value!.revision,
            input
          )
        : await props.api.createDraftVersion(id, input)
    selected.value = result
    baseline.value = JSON.stringify(form)
    message.success('版本草稿已保存')
    await load.run(loadData)
  })
}
function validate(value: CapabilityVersionSummary) {
  if (!canUpdate.value || dirty.value) return
  void action.run(async () => {
    if (dirty.value) throw new Error('请先保存版本修改')
    const r = await props.api.validateVersion(id, value.id)
    issues.value = r.errors
    if (r.valid) message.success('版本验证通过')
  })
}
function publish(value: CapabilityVersionSummary) {
  if (!canPublish.value || dirty.value) return
  void action.run(async () => {
    if (dirty.value) throw new Error('请先保存版本修改')
    if (
      !(await confirm({
        title: '发布能力',
        content: `发布版本 ${value.versionKey}？发布后该版本不能再编辑；需要修改时请另建草稿。`,
        confirmText: '发布',
        cancelText: '取消'
      }))
    )
      return
    if (!canPublish.value) return
    const v = await props.api.validateVersion(id, value.id)
    issues.value = v.errors
    if (!v.valid) throw new Error('版本校验未通过，请修正页面标出的问题后重新发布')
    if (!canPublish.value) return
    await props.api.publishVersion(id, value.id, v.definitionRevision)
    editing.value = false
    value.status = 'PUBLISHED'
    message.success('能力版本已发布')
    await load.run(loadData)
  })
}
function setDefault(value: CapabilityVersionSummary) {
  if (!canSetDefault.value) return
  void action.run(async () => {
    if (
      !(await confirm({
        title: '切换当前发布版本',
        content: `将 ${value.versionKey} 设为当前发布版本？后续新执行将采用此版本。`,
        confirmText: '设为当前发布',
        cancelText: '取消',
        danger: false
      }))
    )
      return
    if (!canSetDefault.value) return
    definition.value = await props.api.setSkillDefault(id, value.id, definition.value!.revision)
    message.success('已切换当前发布版本')
  })
}
function download(value: CapabilityVersionSummary) {
  if (!canExport.value) return
  void action.run(async () => {
    const blob = await props.api.downloadSkill(value.id),
      url = URL.createObjectURL(blob),
      a = document.createElement('a')
    a.href = url
    a.download = `${definition.value!.capabilityKey}-${value.versionKey}.zip`
    a.click()
    message.success('已发起 ZIP 下载')
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  })
}
onMounted(reload)

const formTipId = useId()
</script>
<template>
  <AdminPage :title="definition?.name ?? '能力版本'" :description="definition?.capabilityKey"
    ><template #extra
      ><RouterLink :to="basePath"><ElButton>返回目录</ElButton></RouterLink
      ><RouterLink v-if="definition?.capabilityType === 'SKILL'" :to="skillBasePath + '/' + id"
        ><ElButton type="primary">打开 Skill 工作台</ElButton></RouterLink
      ><ElButton
        v-else-if="canEditWorkingDraft"
        :disabled="action.busy.value || load.busy.value || !definition"
        type="primary"
        @click="editWorkingDraft"
        >编辑工作草稿</ElButton
      ></template
    ><FeedbackNotice
      v-if="load.error.value"
      :title="load.error.value"
      type="error"
      :closable="false"
    /><FeedbackNotice
      v-if="action.error.value"
      :title="action.error.value"
      type="error"
      :closable="false"
    /><ElButton v-if="load.error.value" :loading="load.busy.value" @click="reload"
      >重新加载版本</ElButton
    ><ElAlert
      v-for="issue in issues"
      :key="issue.code"
      :title="issue.message"
      type="warning"
    /><ElSkeleton v-if="load.busy.value && !definition" :rows="6" /><template v-else
      ><section v-if="definition" class="capability-overview" aria-label="能力概况">
        <ElTag effect="plain">{{ statusLabel(definition.capabilityType) }}</ElTag
        ><span>{{ definition.description || '尚未填写能力说明' }}</span
        ><span class="admin-field-help">{{ versions.length }} 个版本</span>
      </section>
      <div class="version-section-heading">
        <h2>{{ showHistory ? '全部版本' : '当前工作版本' }}</h2>
        <ElButton v-if="historicalVersions.length" text @click="showHistory = !showHistory">{{
          showHistory ? '收起历史' : `历史与维护（${historicalVersions.length}）`
        }}</ElButton>
      </div>
      <div class="capability-versions">
        <ElCard
          v-for="version in visibleVersions"
          :key="version.id"
          shadow="never"
          class="capability-version"
          ><div class="admin-actions">
            <h3>{{ version.versionKey }} · {{ version.name }}</h3>
            <ElTag>{{ statusLabel(version.status) }}</ElTag
            ><ElTag v-if="definition?.defaultVersionId === version.id" type="success"
              >当前发布</ElTag
            >
          </div>
          <p>{{ version.description }}</p>
          <div class="admin-actions">
            <template v-if="definition?.capabilityType === 'SKILL'"
              ><RouterLink :to="skillBasePath + '/' + id + '?versionId=' + version.id"
                ><ElButton>查看文件</ElButton></RouterLink
              ><ElButton v-if="canExport" :disabled="action.busy.value" @click="download(version)"
                >下载 ZIP</ElButton
              ><ElButton
                v-if="
                  canSetDefault &&
                  version.status === 'PUBLISHED' &&
                  definition.defaultVersionId !== version.id
                "
                :disabled="action.busy.value"
                @click="setDefault(version)"
                >设为当前发布</ElButton
              ></template
            ><ElButton v-else :disabled="action.busy.value" @click="edit(version)">{{
              version.status === 'DRAFT'
                ? canCreate || canUpdate
                  ? '编辑草稿'
                  : '查看草稿'
                : '查看历史发布'
            }}</ElButton
            ><ElButton
              v-if="
                canCreate &&
                version.status === 'PUBLISHED' &&
                definition?.capabilityType !== 'SKILL'
              "
              :disabled="action.busy.value || dirty"
              @click="restorePublication(version)"
              >恢复为草稿</ElButton
            ><ElButton
              v-if="canUpdate"
              :disabled="action.busy.value || dirty"
              @click="validate(version)"
              >验证</ElButton
            ><ElButton
              v-if="canPublish && version.status === 'DRAFT'"
              type="primary"
              :disabled="action.busy.value || dirty"
              @click="publish(version)"
              >发布</ElButton
            >
          </div></ElCard
        >
      </div>
      <ElEmpty
        v-if="!visibleVersions.length && !load.error.value"
        :description="
          historicalVersions.length
            ? '尚无工作草稿或当前发布版本。可展开“历史与维护”，选择版本恢复为草稿。'
            : '尚无版本，请先编辑工作草稿并保存。'
        "
        :image-size="64"
      /><ElCard v-if="editing" shadow="never" class="capability-editor"
        ><h2>
          {{ selected?.status === 'PUBLISHED' ? '历史发布 ' + selected.versionKey : '工作草稿' }}
        </h2>
        <p v-if="selected && selected.status !== 'DRAFT'" class="admin-field-help">
          此版本已发布，内容仅供查看。需要修改时，选择“恢复为草稿”并保存新草稿。
        </p>
        <p v-else-if="!canEdit" class="admin-field-help">当前账号没有该草稿的编辑权限。</p>
        <p v-if="dirty" class="admin-field-help" role="status">
          当前修改尚未保存，请先保存版本草稿，再验证或发布。
        </p>
        <ElForm
          ref="formRef"
          :model="form"
          scroll-to-error
          label-position="top"
          :disabled="!canEdit || action.busy.value"
          @submit.prevent="save"
          @keydown.capture="guardFormEnter"
          ><div class="capability-form-heading">
            <h3>基本信息</h3>
            <span class="admin-field-help">先填写能力内容，再按需配置运行与维护选项</span>
          </div>
          <div class="admin-form-grid">
            <ElFormItem
              label="版本标识"
              prop="versionKey"
              :rules="[
                { required: true, whitespace: true, message: '请填写版本标识', trigger: 'blur' }
              ]"
            >
              <ElInput v-model="form.versionKey" :aria-describedby="formTipId + '-version'" />
              <p :id="formTipId + '-version'" class="admin-field-help">
                同一草稿可反复保存；填写新的版本标识会另建草稿。发布后该版本不能再编辑。
              </p>
            </ElFormItem>
            <ElFormItem
              label="名称"
              prop="name"
              :rules="[
                { required: true, whitespace: true, message: '请填写名称', trigger: 'blur' }
              ]"
              ><ElInput v-model="form.name"
            /></ElFormItem>
            <ElFormItem class="full-width" label="说明"
              ><ElInput v-model="form.description" type="textarea" :rows="3"
            /></ElFormItem>
            <template v-if="definition?.capabilityType === 'TOOL'">
              <ElFormItem
                label="工具名称"
                prop="tool_name"
                :rules="[
                  { required: true, whitespace: true, message: '请填写工具名称', trigger: 'blur' }
                ]"
                ><ElInput v-model="form.tool_name" :aria-describedby="formTipId + '-tool'" />
                <p :id="formTipId + '-tool'" class="admin-field-help">
                  填写实际工具提供的名称，用于选择部署中的对应工具。
                </p></ElFormItem
              >
              <ElFormItem label="工具标题"
                ><ElInput v-model="form.title" placeholder="可选，用于界面展示"
              /></ElFormItem>
            </template>
            <ElFormItem
              v-if="definition?.capabilityType === 'KNOWLEDGE_SOURCE'"
              label="知识来源标识"
              prop="source_id"
              :rules="[
                { required: true, whitespace: true, message: '请填写知识来源标识', trigger: 'blur' }
              ]"
              ><ElInput v-model="form.source_id" />
              <p class="admin-field-help">知识来源的稳定身份，用于绑定检索范围。</p></ElFormItem
            >
            <template v-if="definition?.capabilityType !== 'KNOWLEDGE_SOURCE'">
              <ElFormItem
                class="full-width"
                label="输入 JSON Schema"
                prop="input_schema"
                :rules="[jsonRule('object')]"
                ><JsonSchemaFields
                  :key="(selected?.id ?? 'new') + '-input'"
                  v-model="form.input_schema"
                  label="输入 JSON Schema"
                  :described-by="formTipId + '-input'"
                  :readonly="!canEdit || action.busy.value"
                />
                <p :id="formTipId + '-input'" class="admin-field-help">
                  定义输入字段、类型与必填约束，内容必须为 JSON 对象。
                </p></ElFormItem
              >
              <ElFormItem
                class="full-width"
                label="输出 JSON Schema"
                prop="output_schema"
                :rules="[jsonRule('object')]"
                ><JsonSchemaFields
                  :key="(selected?.id ?? 'new') + '-output'"
                  v-model="form.output_schema"
                  label="输出 JSON Schema"
                  :readonly="!canEdit || action.busy.value"
              /></ElFormItem>
            </template>
          </div>
          <details
            class="capability-maintenance"
            :open="advancedOpen"
            @toggle="advancedOpen = ($event.target as HTMLDetailsElement).open"
          >
            <summary>
              运行与维护配置<span class="admin-field-help">{{
                definition?.capabilityType === 'TOOL'
                  ? `副作用：${statusLabel(form.effect)} · 确认：${statusLabel(form.confirmation_policy)}`
                  : '调用入口、安全策略与高级描述'
              }}</span>
            </summary>
            <div class="admin-form-grid">
              <ElFormItem label="允许模型调用"
                ><ElSwitch v-model="form.model_invocable" />
                <p class="admin-field-help">允许智能体通过模型决策调用此能力。</p></ElFormItem
              >
              <ElFormItem label="允许用户调用"
                ><ElSwitch v-model="form.user_invocable" />
                <p class="admin-field-help">允许用户主动调用此能力。</p></ElFormItem
              >
              <template v-if="definition?.capabilityType === 'TOOL'">
                <ElFormItem label="副作用等级"
                  ><ElSelect v-model="form.effect"
                    ><ElOption
                      v-for="value in [
                        'READ_ONLY',
                        'IDEMPOTENT_WRITE',
                        'NON_IDEMPOTENT_WRITE',
                        'DESTRUCTIVE'
                      ]"
                      :key="value"
                      :value="value"
                      :label="statusLabel(value)"
                  /></ElSelect>
                  <p class="admin-field-help">
                    按工具的实际操作选择：只读、写入或破坏性操作。此声明用于决定调用时的确认与执行限制。
                  </p></ElFormItem
                >
                <ElFormItem label="确认策略"
                  ><ElSelect v-model="form.confirmation_policy"
                    ><ElOption
                      v-for="value in ['NEVER', 'POLICY', 'ALWAYS']"
                      :key="value"
                      :value="value"
                      :label="statusLabel(value)" /></ElSelect
                ></ElFormItem>
                <ElFormItem label="幂等"
                  ><ElSwitch v-model="form.idempotent" />
                  <p class="admin-field-help">
                    相同请求重复执行不会产生额外副作用时启用。
                  </p></ElFormItem
                >
                <ElFormItem label="允许并行"
                  ><ElSwitch v-model="form.parallel_safe" />
                  <p class="admin-field-help">多个调用可安全并行执行时启用。</p></ElFormItem
                >
                <ElFormItem label="敏感字段"
                  ><ElSelect
                    v-model="form.sensitive_fields"
                    multiple
                    filterable
                    allow-create
                    default-first-option
                  />
                  <p class="admin-field-help">
                    填写需隐藏的顶层字段名，按 Enter 添加。输入展示会隐藏同名字段，结构化输出
                    structuredContent 也会移除同名字段。
                  </p></ElFormItem
                >
                <ElFormItem label="提供方要求"
                  ><ElSelect
                    v-model="form.provider_requirements"
                    multiple
                    filterable
                    allow-create
                    default-first-option
                  />
                  <p class="admin-field-help">
                    填写运行此工具所需的提供方条件，按 Enter 添加。
                  </p></ElFormItem
                >
              </template>
              <template v-if="definition?.capabilityType === 'WORKFLOW'">
                <ElFormItem label="支持人工介入"
                  ><ElSwitch v-model="form.supports_hitl"
                /></ElFormItem>
                <ElFormItem label="并发安全"
                  ><ElSwitch v-model="form.concurrency_safe" />
                  <p class="admin-field-help">同一工作流可安全并发执行时启用。</p></ElFormItem
                >
                <ElFormItem label="导出字段"
                  ><ElSelect
                    v-model="form.export_fields"
                    multiple
                    filterable
                    allow-create
                    default-first-option
                /></ElFormItem>
              </template>
              <ElFormItem
                v-if="definition?.capabilityType !== 'KNOWLEDGE_SOURCE'"
                label="超时（毫秒）"
                prop="timeout_ms"
                :rules="[
                  {
                    required: true,
                    type: 'number',
                    min: 1,
                    message: '超时必须为正整数',
                    trigger: 'change'
                  }
                ]"
                ><ElInputNumber v-model="form.timeout_ms" :min="1" :precision="0" />
                <p class="admin-field-help">
                  设置单次调用允许的最长时间，单位为毫秒，必须大于 0。
                </p></ElFormItem
              >
              <template v-else>
                <ElFormItem label="来源版本"
                  ><ElSelect
                    v-model="form.source_versions"
                    multiple
                    filterable
                    allow-create
                    default-first-option
                  />
                  <p class="admin-field-help">
                    填写允许检索的来源版本标识，按 Enter 添加。
                  </p></ElFormItem
                >
                <ElFormItem label="允许操作符"
                  ><ElSelect
                    v-model="form.allowed_operators"
                    multiple
                    filterable
                    allow-create
                    default-first-option
                /></ElFormItem>
                <ElFormItem label="新鲜度策略"
                  ><ElInput v-model="form.freshness_policy"
                /></ElFormItem>
                <ElFormItem label="必须包含引用"
                  ><ElSwitch v-model="form.citation_required"
                /></ElFormItem>
              </template>
              <template v-if="definition?.capabilityType === 'TOOL'">
                <ElFormItem
                  class="full-width"
                  label="工具注解（JSON 对象）"
                  prop="annotations"
                  :rules="[jsonRule('object')]"
                  ><CodeEditor
                    v-model="form.annotations"
                    label="工具注解"
                    language="json"
                    :readonly="!canEdit || action.busy.value"
                    height="180px"
                /></ElFormItem>
                <ElFormItem
                  class="full-width"
                  label="工具图标（JSON 数组）"
                  prop="icons"
                  :rules="[jsonRule('array')]"
                  ><CodeEditor
                    v-model="form.icons"
                    label="工具图标"
                    language="json"
                    :readonly="!canEdit || action.busy.value"
                    height="140px"
                /></ElFormItem>
              </template>
            </div>
          </details>
          <ElButton v-if="canSave" type="primary" native-type="submit" :loading="action.busy.value"
            >保存版本草稿</ElButton
          ></ElForm
        ></ElCard
      ></template
    ></AdminPage
  >
</template>

<style scoped>
.capability-overview {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 12px;
  padding: 16px 20px;
  border: 1px solid var(--admin-border);
  border-radius: 10px;
  background: var(--admin-surface);
  overflow-wrap: anywhere;
}
.capability-overview .admin-field-help {
  margin-left: auto;
}
.version-section-heading,
.capability-form-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 16px;
}
.version-section-heading {
  justify-content: space-between;
  margin-block: 24px 12px;
}
.version-section-heading h2,
.capability-form-heading h3 {
  margin: 0;
  font-size: 16px;
}
.capability-versions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 360px), 1fr));
  gap: 16px;
}
.capability-version h3 {
  margin: 0;
  font-size: 15px;
  overflow-wrap: anywhere;
}
.capability-version p {
  color: var(--admin-muted);
  font-size: 13px;
  overflow-wrap: anywhere;
}
.capability-version .admin-actions {
  flex-wrap: wrap;
}
.capability-editor {
  margin-top: 24px;
}
.capability-form-heading {
  margin-block: 20px 16px;
}
.capability-maintenance {
  border-top: 1px solid var(--admin-border);
  margin-bottom: 20px;
}
.capability-maintenance summary {
  cursor: pointer;
  padding: 16px 0;
  font-weight: 600;
}
.capability-maintenance summary .admin-field-help {
  margin-left: 12px;
  font-weight: 400;
}
.capability-maintenance summary:focus-visible {
  outline: 2px solid var(--admin-accent);
  outline-offset: 3px;
}
.capability-maintenance .admin-form-grid {
  padding-top: 8px;
}
</style>
