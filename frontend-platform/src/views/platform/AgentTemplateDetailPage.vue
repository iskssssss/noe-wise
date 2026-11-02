<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { useActionPermissions } from '../../composables/actionPermissions'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import { budgetOverrideHelp } from '@skillforge/shared/utils/formHelp'
import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { acceptUnhighlightedCreate } from '@skillforge/shared/admin/creatableOption'
import { message, confirm } from '@skillforge/shared/feedback'
import { computed, onMounted, reactive, ref, useId, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { type FormInstance } from 'element-plus'
import {
  AdminPage,
  EntitySelect,
  useTask,
  useResource,
  useUnsavedChanges,
  AgentConfigFields
} from '@skillforge/shared/admin'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { useAuthStore } from '../../stores/auth'
import AgentDefinitionImpactPanel from './AgentDefinitionImpactPanel.vue'
import InstitutionAgentSetup from './InstitutionAgentSetup.vue'
import { createOperationOptions } from '../../api/entityOptions'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
import {
  api,
  type AgentTemplateDetail,
  type AgentTemplateItem,
  type AgentTemplateDefinition,
  type AgentDefinitionImpact,
  type ModelView,
  type CapabilityDefinition
} from '../../api'
const { allowed, checked } = useActionPermissions()
const deploymentTenants = createOperationOptions('tenants', 'platform.agent_deployments.create')
const setupOpen = ref(false)
const setupOpened = ref(false)
const setupTenant = ref('')
const setupDefinition = ref('')
const selectedSetupTenant = ref('')
const setupStarted = ref(false)
const setupCompleted = ref(false)
const setupAction = useTask()
const recoverableTenants = computed(() => {
  if (
    isNew ||
    setupCompleted.value ||
    !auth.user?.sub ||
    (!allowed('platform.agent_grants.read') && !allowed('platform.agent_deployments.read'))
  )
    return [] as string[]
  const prefix = 'skillforge.agent-setup:'
  const tenants: string[] = []
  try {
    for (let index = 0; index < sessionStorage.length; index++) {
      const key = sessionStorage.key(index)
      if (!key?.startsWith(prefix)) continue
      const [actorTenant, subject, tenant, definition] = JSON.parse(
        decodeURIComponent(key.slice(prefix.length))
      )
      if (
        actorTenant === (auth.user.tenant_id ?? null) &&
        subject === auth.user.sub &&
        definition === String(route.params.id)
      )
        tenants.push(String(tenant))
    }
  } catch {
    return tenants
  }
  return tenants
})
function resumeSetup(tenant: string) {
  if (isNew || !recoverableTenants.value.includes(tenant)) return
  setupTenant.value = tenant
  setupDefinition.value = String(route.params.id)
  selectedSetupTenant.value = setupTenant.value
  setupStarted.value = true
  setupCompleted.value = false
}
function toggleSetup() {
  setupOpened.value = true
  setupOpen.value = !setupOpen.value
}
function startSetup() {
  if (!selectedSetupTenant.value || setupStarted.value) return
  void setupAction.run(async () => {
    const tenantId = selectedSetupTenant.value
    const definitionId = item.value?.definition_id
    if (!definitionId) throw Error('模板详情不可用，请重新读取')
    if (!allowed('platform.agent_deployments.create')) throw Error('当前账号没有部署创建权限')
    if (!(await deploymentTenants.resolve(tenantId)))
      throw Error('所选机构不在当前部署范围内，请重新选择')
    if (!allowed('platform.agent_deployments.create')) throw Error('当前账号没有部署创建权限')
    if (selectedSetupTenant.value !== tenantId || item.value?.definition_id !== definitionId)
      throw Error('机构或模板已变化，请重新选择')
    setupTenant.value = tenantId
    setupDefinition.value = definitionId
    setupStarted.value = true
    setupCompleted.value = false
  })
}
watch(selectedSetupTenant, (tenant) => {
  if (tenant && setupOpen.value && !setupStarted.value) startSetup()
})
function startAnotherSetup() {
  setupStarted.value = false
  setupCompleted.value = false
  selectedSetupTenant.value = ''
  setupTenant.value = ''
  setupDefinition.value = ''
}
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const route = useRoute(),
  router = useRouter(),
  isNew = route.path.endsWith('/new'),
  action = useTask(),
  item = ref<AgentTemplateDetail>(),
  models = ref<ModelView[]>([]),
  definitions = ref<CapabilityDefinition[]>([]),
  baseline = ref(''),
  ready = ref(false),
  issues = ref<Array<{ path: string; code: string; message: string }>>([])
const listContext = computed(() => ({
  list_name: route.query.list_name,
  list_status: route.query.list_status,
  list_page: route.query.list_page
}))
const current = ref<AgentTemplateDetail>()
const conflict = ref(false)
const refreshRequired = ref(false)
const previewStale = ref(true)
const candidatePreview = ref<AgentDefinitionImpact>()
const candidateSnapshot = ref('')
const validatedRevision = ref<number>()
const catalogError = ref('')
const pending = ref<{
  permission: string
  label: string
  run: () => Promise<AgentTemplateItem>
  definition: AgentTemplateDefinition
  validateAfterSave?: boolean
}>()
const catalogs = useResource(async (signal) => {
  const results = await Promise.allSettled([
    api.admin.models.list({ signal, silent: true }),
    api.capabilities.listDefinitions({ status: 'PUBLISHED' }, { signal, silent: true })
  ] as const)
  const failures = results.flatMap((result) => {
    if (result.status === 'fulfilled') return []
    if (result.reason instanceof HttpClientError) result.reason.claimFeedback()
    return [result.reason instanceof Error ? result.reason.message : '编辑目录读取失败']
  })
  return {
    modelList: results[0].status === 'fulfilled' ? results[0].value : undefined,
    defs: results[1].status === 'fulfilled' ? results[1].value : undefined,
    error: [...new Set(failures)].join('；')
  }
})
function acceptCatalogs(value: NonNullable<typeof catalogs.data.value>) {
  if (value.modelList) models.value = value.modelList
  if (value.defs) definitions.value = value.defs
  catalogError.value = value.error
}
async function refreshCatalogs() {
  if (!allowed(isNew ? 'platform.agent_definitions.create' : 'platform.agent_definitions.edit'))
    return
  const result = await catalogs.reload()
  if (result) acceptCatalogs(result)
}
const load = useResource(async (signal) => {
  const editable = allowed(
    isNew ? 'platform.agent_definitions.create' : 'platform.agent_definitions.edit'
  )
  const [catalog, detail] = await Promise.all([
    editable ? catalogs.reload() : Promise.resolve(undefined),
    isNew
      ? Promise.resolve(undefined)
      : api.agentTemplates.detail(String(route.params.id), { signal, silent: true })
  ])
  return { catalog, detail }
})
const history = useResource((signal) =>
  api.agentTemplates.history(item.value!.definition_id, { signal, silent: true })
)
const preview = useResource(async (signal) => {
  const target = item.value!
  try {
    return await api.agentTemplates.impact(target.definition_id, target.draft_revision, {
      signal,
      silent: true
    })
  } catch (reason) {
    if (reason instanceof HttpClientError && reason.status === 409 && !signal.aborted) {
      conflict.value = true
      previewStale.value = true
      await reload()
    }
    throw reason
  }
})
const fields = [
  ['max_business_model_calls', '业务模型调用次数'],
  ['max_tool_calls', '最大工具调用'],
  ['max_knowledge_calls', '最大知识检索'],
  ['max_total_tokens', '累计总 Tokens'],
  ['max_wall_time_seconds', '活跃执行时长（秒）']
] as const
const form = ref({
  name: '',
  description: '',
  system_prompt: '',
  model_config_id: null as string | null
})
const meta = reactive({
  category: '',
  tags: [] as string[],
  icon_url: '',
  price_type: 'free',
  price_cents: 0,
  distribution_policy: null as 'OPEN_INSTALL' | 'AUTHORIZED_DEPLOY' | null
})
function onTagEnter(event: KeyboardEvent) {
  acceptUnhighlightedCreate(event, true, [], (value) => {
    if (!meta.tags.includes(value)) meta.tags.push(value)
  })
}
const definition = reactive<AgentTemplateDefinition>({
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
})
function snapshot() {
  return JSON.stringify({ form: form.value, meta, definition })
}
const committedNavigation = ref(false)
const dirty = computed(() => ready.value && snapshot() !== baseline.value)
const previewCurrent = computed(
  () =>
    !previewStale.value &&
    !dirty.value &&
    !conflict.value &&
    preview.data.value?.definition_id === item.value?.definition_id &&
    preview.data.value?.draft_revision === item.value?.draft_revision
)
useUnsavedChanges({
  dirty: () => dirty.value || !!pending.value,
  busy: () => action.busy.value && !committedNavigation.value
})
function accept(value: AgentTemplateDetail) {
  if (
    item.value?.draft_revision !== value.draft_revision ||
    item.value?.current_published_revision !== value.current_published_revision
  ) {
    previewStale.value = true
    preview.invalidate()
    validatedRevision.value = undefined
  }
  item.value = value
  form.value = {
    name: value.name,
    description: value.description ?? '',
    system_prompt: value.definition.system_prompt,
    model_config_id: value.definition.model_config_id
  }
  Object.assign(meta, {
    category: value.category ?? '',
    tags: [...(value.tags ?? [])],
    icon_url: value.icon_url ?? '',
    price_type: value.price_type ?? 'free',
    price_cents: value.price_cents ?? 0,
    distribution_policy: value.distribution_policy
  })
  Object.assign(definition, JSON.parse(JSON.stringify(value.definition)))
  baseline.value = snapshot()
  ready.value = true
  current.value = value
  conflict.value = false
}
const entryKeys = new WeakMap<object, string>()
let entrySequence = 0
function entryKey(entry: object) {
  if (!entryKeys.has(entry)) entryKeys.set(entry, 'entry-' + ++entrySequence)
  return entryKeys.get(entry)!
}
const capabilityReads = ref<Record<string, { busy: boolean; error: string }>>({})
function entryError(path: string) {
  return issues.value
    .filter((issue) => issue.path === path || issue.path.startsWith(path + '.'))
    .map((issue) => issue.message)
    .join('；')
}
function selectCapability(
  mount: AgentTemplateDefinition['capabilities'][number],
  readDescriptor = false
) {
  const selected = definitions.value.find(
    (value) =>
      value.id === mount.capability_definition_id && value.capabilityType === mount.capability_type
  )
  mount.capability_version_id = selected?.defaultVersionId ?? null
  if (!readDescriptor) return
  const key = entryKey(mount),
    version = mount.capability_version_id
  capabilityReads.value[key] = { busy: !!version, error: '' }
  const state = capabilityReads.value[key]!
  mount.platform_tool_name = null
  if (!version) return
  void api.capabilities
    .getVersion(version)
    .then((value) => {
      if (
        !definition.capabilities.includes(mount) ||
        mount.capability_version_id !== version ||
        capabilityReads.value[key] !== state
      )
        return
      if (mount.capability_type === 'TOOL') {
        if (typeof value.descriptor.tool_name !== 'string' || !value.descriptor.tool_name)
          throw new Error('所选发布版本缺少平台工具名称')
        mount.platform_tool_name = value.descriptor.tool_name
      } else if (mount.skill_config) {
        const mode = value.descriptor.activation_mode
        if (mode !== 'ALWAYS' && mode !== 'ON_DEMAND')
          throw new Error('所选发布版本缺少有效激活方式')
        mount.skill_config.activation_mode = mode
      }
    })
    .catch((reason) => {
      if (reason instanceof HttpClientError) reason.claimFeedback()
      if (definition.capabilities.includes(mount) && capabilityReads.value[key] === state) {
        state.error = reason instanceof Error ? reason.message : '读取能力发布配置失败'
      }
    })
    .finally(() => {
      if (capabilityReads.value[key] === state) state.busy = false
    })
}
function capabilityVersionChanged(mount: AgentTemplateDefinition['capabilities'][number]) {
  const selected = definitions.value.find(
    (value) =>
      value.id === mount.capability_definition_id && value.capabilityType === mount.capability_type
  )
  return Boolean(
    selected?.defaultVersionId &&
    mount.capability_version_id &&
    selected.defaultVersionId !== mount.capability_version_id
  )
}
function nextSlotKey(slots: Array<{ key: string }>, prefix: string) {
  let number = 1
  while (slots.some((slot) => slot.key === prefix + number)) number++
  return prefix + number
}
function addResourceSlot() {
  definition.resource_slots.push({
    key: nextSlotKey(definition.resource_slots, 'resource_'),
    label: '',
    description: '',
    capability_type: 'TOOL'
  })
  if (!optionalSections.value.includes('resources')) optionalSections.value.push('resources')
}
function addDelegationSlot() {
  definition.delegation_slots.push({
    key: nextSlotKey(definition.delegation_slots, 'delegate_'),
    label: '',
    description: ''
  })
  if (!optionalSections.value.includes('delegation')) optionalSections.value.push('delegation')
}
function validateEntries() {
  const found: typeof issues.value = []
  for (const field of ['resource_slots', 'delegation_slots'] as const) {
    const used = new Set<string>()
    definition[field].forEach((slot, index) => {
      if (!/^[a-z][a-z0-9_]{0,63}$/.test(slot.key) || used.has(slot.key))
        found.push({
          path: `${field}[${index}].key`,
          code: 'slot_key_invalid',
          message: '标识须为唯一的小写字母、数字或下划线，且以字母开头'
        })
      used.add(slot.key)
      if (!slot.label.trim())
        found.push({
          path: `${field}[${index}].label`,
          code: 'required',
          message: '请填写显示名称'
        })
    })
  }
  const used = new Set<string>()
  definition.capabilities.forEach((mount, index) => {
    const identity = mount.capability_type + ':' + mount.capability_definition_id
    if (used.has(identity))
      found.push({
        path: `capabilities[${index}]`,
        code: 'duplicate_capability',
        message: '同一能力只能挂载一次'
      })
    used.add(identity)
    if (
      mount.skill_config &&
      (!mount.skill_config.token_budget ||
        !Number.isSafeInteger(mount.skill_config.token_budget) ||
        mount.skill_config.token_budget < 1)
    )
      found.push({
        path: `capabilities[${index}].skill_config`,
        code: 'skill_budget_required',
        message: '请为技能填写正整数 Token 预算'
      })
  })
  if (found.length) {
    issues.value = found
    throw new Error('请修正标记的能力与槽位配置')
  }
}
async function reload(expectedRevision?: number) {
  const requested = snapshot()
  const result = await load.reload()
  if (!result) return
  refreshRequired.value = false
  if (result.catalog) acceptCatalogs(result.catalog)
  current.value = result.detail
  if (expectedRevision !== undefined && result.detail?.draft_revision !== expectedRevision) {
    conflict.value = true
    return
  }
  if (!ready.value || (!dirty.value && !conflict.value && requested === snapshot())) {
    if (result.detail) accept(result.detail)
    else {
      baseline.value = snapshot()
      ready.value = true
    }
  }
}
/** 冲突重读只展示最新基数，明确选择覆盖后才替换本地草稿。 */
async function replaceWithCurrent() {
  if (action.busy.value || pending.value || !current.value) return
  if (
    !(await confirm({
      title: '采用最新草稿',
      content: '将以已读取的服务端草稿覆盖当前未保存输入，原影响预览和校验均失效。',
      confirmText: '覆盖当前输入',
      cancelText: '保留输入'
    }))
  )
    return
  if (!action.busy.value && !pending.value && current.value) accept(current.value)
}
/** 一个写意图只持有一个键，网络未知时保留原快照；CAS失败保留输入与失效预览。 */
async function commit(command: NonNullable<typeof pending.value>) {
  let result: AgentTemplateItem
  try {
    result = await checked(command.permission, command.run)
  } catch (reason) {
    if (reason instanceof HttpClientError && reason.status === 409) {
      pending.value = undefined
      conflict.value = true
      previewStale.value = true
      await reload()
    } else if (
      !(reason instanceof HttpClientError) ||
      (reason.status >= 400 && reason.status < 500 && reason.status !== 408)
    )
      pending.value = undefined
    throw reason
  }
  pending.value = undefined
  accept({ ...result, definition: JSON.parse(JSON.stringify(command.definition)) })
  refreshRequired.value = !isNew
  if (!command.validateAfterSave) message.success(command.label)
  if (isNew) {
    committedNavigation.value = true
    try {
      await router.replace({
        path: '/platform/agent-templates/' + result.definition_id,
        query: listContext.value
      })
    } catch {
      message.warning('模板已保存，详情导航失败，可从模板列表重新进入。')
    } finally {
      committedNavigation.value = false
    }
  } else {
    await reload(result.draft_revision)
    if (
      command.validateAfterSave &&
      !refreshRequired.value &&
      !conflict.value &&
      !dirty.value &&
      item.value?.draft_revision === result.draft_revision &&
      allowed('platform.agent_definitions.validate') &&
      allowed('platform.agent_definitions.read')
    ) {
      try {
        await validateCurrent(true)
      } catch (reason) {
        if (reason instanceof HttpClientError) reason.claimFeedback()
        if (allowed(command.permission))
          action.error.value = `模板已保存，但验证未完成：${reason instanceof Error ? reason.message : '请重新验证'}。`
      }
    } else if (command.validateAfterSave && allowed(command.permission)) {
      message.warning('模板已保存，请核对当前草稿后验证。')
    }
  }
}
function retryPending() {
  if (!pending.value || action.busy.value) return
  const command = pending.value
  void action.run(() => commit(command))
}
function save(validateAfterSave = false) {
  if (
    !allowed(
      item.value ? 'platform.agent_definitions.edit' : 'platform.agent_definitions.create'
    ) ||
    conflict.value ||
    refreshRequired.value ||
    pending.value
  )
    return
  void action.run(async () => {
    await formRef.value?.validate()
    if (!form.value.name.trim()) throw new Error('模板名称不能为空')
    if (!meta.distribution_policy) throw new Error('请选择定义分发方式')
    validateEntries()
    if (definition.capabilities.some((mount) => !mount.capability_definition_id))
      throw Error('请选择每个基础能力')
    const payload = JSON.parse(
      JSON.stringify({
        ...meta,
        name: form.value.name,
        description: form.value.description,
        definition: {
          ...definition,
          system_prompt: form.value.system_prompt,
          model_config_id: form.value.model_config_id
        }
      })
    )
    const target = item.value
    const key = crypto.randomUUID()
    const command = {
      permission: target ? 'platform.agent_definitions.edit' : 'platform.agent_definitions.create',
      label: '模板已保存',
      validateAfterSave: !!target && validateAfterSave,
      definition: payload.definition,
      run: () =>
        target
          ? api.agentTemplates.update(target.definition_id, target.draft_revision, payload, key)
          : api.agentTemplates.create(payload, key)
    }
    pending.value = command
    await commit(command)
  })
}
/** 发布和下架绑定当前草稿修订，确认后再次检查当前人员动作。 */
function transition(name: 'publish' | 'deprecate') {
  const permission = `platform.agent_definitions.${name}`
  if (!allowed(permission) || conflict.value || refreshRequired.value || pending.value) return
  void action.run(async () => {
    if (dirty.value) throw Error('请先保存修改')
    const target = item.value!
    const expected = snapshot()
    if (
      name === 'publish' &&
      (!previewCurrent.value || validatedRevision.value !== target.draft_revision)
    )
      throw Error('请验证并预览当前精确草稿修订后再发布')
    if (
      !(await confirm({
        title: '定义状态',
        content:
          name === 'publish'
            ? `确认发布 ${target.name} 的草稿修订 ${target.draft_revision}？当前可见影响 ${preview.data.value!.visible_deployment_count} 个部署，运行中任务保留原冻结版本。`
            : `确认下架 ${target.name}（当前发布 ${target.current_published_revision ?? target.version}）？新调用将受当前下架状态限制；历史和已发生费用保留。`,
        confirmText: name === 'publish' ? '发布定义' : '下架定义',
        cancelText: '取消',
        danger: name === 'deprecate'
      }))
    )
      return
    if (
      expected !== snapshot() ||
      item.value?.draft_revision !== target.draft_revision ||
      conflict.value ||
      (name === 'publish' && !previewCurrent.value)
    )
      throw Error('确认期间草稿已变化，请重新校验与预览')
    const key = crypto.randomUUID()
    const command = {
      permission,
      label: name === 'publish' ? '定义已发布' : '定义已下架',
      definition: structuredClone(JSON.parse(JSON.stringify(definition))),
      run: () => api.agentTemplates[name](target.definition_id, target.draft_revision, key)
    }
    pending.value = command
    await commit(command)
  })
}
const historyOpen = ref(false)
function loadHistory() {
  historyOpen.value = true
  void history.reload()
}
/** 历史恢复只创建新草稿，不移动当前发布指针。 */
function restore(publication: AgentTemplateDetail) {
  if (
    !allowed('platform.agent_definitions.restore') ||
    pending.value ||
    conflict.value ||
    refreshRequired.value
  )
    return
  void action.run(async () => {
    if (dirty.value) throw Error('请先保存或明确放弃当前输入后再恢复历史')
    const target = item.value!,
      expected = snapshot()
    if (
      !(await confirm({
        title: '历史恢复为草稿',
        content: `将发布修订 ${publication.version} 的配置恢复为新草稿，覆盖当前草稿；当前发布指针保持不变，后续仍需验证、预览和发布。`,
        confirmText: '恢复为草稿',
        cancelText: '保留当前草稿'
      }))
    )
      return
    if (expected !== snapshot() || target.draft_revision !== item.value?.draft_revision)
      throw Error('草稿已变化，请重新确认')
    const key = crypto.randomUUID(),
      command = {
        permission: 'platform.agent_definitions.restore',
        label: '历史配置已恢复为新草稿，尚未发布',
        definition: JSON.parse(JSON.stringify(publication.definition)),
        run: () =>
          api.agentTemplates.restore(
            target.definition_id,
            target.draft_revision,
            publication.version,
            key
          )
      }
    pending.value = command
    await commit(command)
    historyOpen.value = false
  })
}
async function loadPreview() {
  if (dirty.value || conflict.value || action.busy.value || pending.value || refreshRequired.value)
    return
  const expected = snapshot()
  const result = await preview.reload()
  if (result && expected === snapshot()) previewStale.value = false
  if (!result && preview.error.value) action.error.value = preview.error.value
}
function validate() {
  if (
    !allowed('platform.agent_definitions.validate') ||
    conflict.value ||
    pending.value ||
    refreshRequired.value
  )
    return
  void action.run(() => validateCurrent())
}
async function validateCurrent(afterSave = false) {
  const expected = snapshot(),
    target = item.value!
  if (dirty.value) {
    const candidate = JSON.parse(
      JSON.stringify({
        ...definition,
        system_prompt: form.value.system_prompt,
        model_config_id: form.value.model_config_id
      })
    ) as AgentTemplateDefinition
    let result
    try {
      result = await checked('platform.agent_definitions.validate', () =>
        api.agentTemplates.previewContent(target.definition_id, target.draft_revision, candidate, {
          silent: true
        })
      )
    } catch (reason) {
      if (reason instanceof HttpClientError && reason.status === 409) {
        conflict.value = true
        previewStale.value = true
        await reload()
      }
      throw reason
    }
    if (expected !== snapshot() || target.draft_revision !== item.value?.draft_revision) return
    issues.value = result.validation.issues
    candidatePreview.value = result.impact ?? undefined
    candidateSnapshot.value = expected
    validatedRevision.value = undefined
    if (result.validation.valid) message.success('当前编辑内容验证通过，影响预览已更新')
    return
  }
  preview.invalidate()
  preview.error.value = ''
  let result
  try {
    result = await checked('platform.agent_definitions.validate', () =>
      api.agentTemplates.previewSaved(target.definition_id, target.draft_revision, {
        silent: true
      })
    )
  } catch (reason) {
    if (reason instanceof HttpClientError && reason.status === 409) {
      conflict.value = true
      previewStale.value = true
      await reload()
    }
    throw reason
  }
  if (expected !== snapshot() || target.draft_revision !== item.value?.draft_revision) return
  if (result.impact && !allowed('platform.agent_definitions.read'))
    throw Error('当前账号没有查看影响的权限')
  issues.value = result.validation.issues
  preview.data.value = result.impact ?? undefined
  validatedRevision.value =
    result.validation.valid && result.impact ? target.draft_revision : undefined
  previewStale.value = !result.impact
  if (result.validation.valid && result.impact)
    message.success(afterSave ? '模板已保存并验证，发布影响已更新' : '模板验证通过，发布影响已更新')
  else if (afterSave) message.warning('模板已保存，但验证未通过或影响不可用，请检查结果。')
}
watch(snapshot, () => {
  previewStale.value = true
  preview.invalidate()
  validatedRevision.value = undefined
  issues.value = []
  candidatePreview.value = undefined
  candidateSnapshot.value = ''
})
watch(
  () => [auth.identityVersion, auth.user?.permissions.join('|')],
  () => {
    load.invalidate()
    catalogs.invalidate()
    catalogs.data.value = undefined
    models.value = []
    definitions.value = []
    catalogError.value = ''
    history.invalidate()
    preview.invalidate()
    history.data.value = undefined
    preview.data.value = undefined
    candidatePreview.value = undefined
    candidateSnapshot.value = ''
    issues.value = []
    current.value = undefined
    historyOpen.value = false
    ready.value = false
    pending.value = undefined
    item.value = undefined
  },
  { flush: 'sync' }
)
onMounted(reload)

const optionalSections = ref<string[]>([])
watch(issues, (values) => {
  for (const [path, section] of [
    ['execution_config', 'budget'],
    ['resource_slots', 'resources'],
    ['delegation_slots', 'delegation']
  ] as const) {
    if (
      values.some((issue) => issue.path.includes(path)) &&
      !optionalSections.value.includes(section)
    )
      optionalSections.value.push(section)
  }
})
const formTipId = useId()
</script>

<template>
  <AdminPage
    :title="item?.name ?? '新建智能体模板'"
    description="模板定义包含行为、预算、能力和机构安装时需要绑定的资源槽位"
  >
    <template #extra>
      <RouterLink :to="{ path: '/platform/agent-templates', query: listContext }">
        <ElButton>返回列表</ElButton>
      </RouterLink>
      <ElTag v-if="dirty" type="warning">未保存</ElTag>
      <ElTag v-if="item?.has_unpublished_changes" type="warning">草稿待发布</ElTag>
      <ElTag v-if="item"
        >草稿修订 {{ item.draft_revision }} · 当前发布
        {{ item.current_published_revision ?? '无' }}</ElTag
      >
      <ElButton
        v-if="item"
        :loading="load.loading.value"
        :disabled="action.busy.value"
        @click="reload()"
        >重读服务端草稿</ElButton
      >
      <ElButton v-if="item" :disabled="action.busy.value" @click="loadHistory">发布历史</ElButton>
      <ElButton
        v-if="
          setupOpened ||
          recoverableTenants.length ||
          (item?.status === 'published' &&
            item.current_published_revision &&
            item.distribution_policy === 'AUTHORIZED_DEPLOY' &&
            allowed('platform.agent_deployments.create'))
        "
        @click="toggleSetup"
        >{{
          setupOpen
            ? '收起机构部署'
            : recoverableTenants.length
              ? '恢复或开始机构部署'
              : '向机构授权并部署'
        }}</ElButton
      >
      <ElButton
        v-if="item"
        :loading="preview.loading.value"
        :disabled="dirty || conflict || action.busy.value || !!pending || refreshRequired"
        @click="loadPreview"
        >预览发布影响</ElButton
      >
      <template v-if="item">
        <ElButton
          v-permission="['platform.agent_definitions.validate']"
          :disabled="conflict || action.busy.value || !!pending || refreshRequired"
          @click="validate"
          >{{ dirty ? '验证并预览' : '验证' }}</ElButton
        >
        <ElButton
          v-permission="['platform.agent_definitions.publish']"
          v-if="
            item.status === 'draft' || item.status === 'deprecated' || item.has_unpublished_changes
          "
          type="primary"
          :disabled="
            dirty ||
            conflict ||
            action.busy.value ||
            !!pending ||
            refreshRequired ||
            !previewCurrent ||
            validatedRevision !== item.draft_revision
          "
          @click="transition('publish')"
          >发布</ElButton
        >
        <ElButton
          v-permission="['platform.agent_definitions.deprecate']"
          v-if="item.status === 'published'"
          :disabled="
            !allowed('platform.agent_definitions.deprecate') ||
            action.busy.value ||
            !!pending ||
            refreshRequired ||
            conflict
          "
          @click="transition('deprecate')"
          >下架</ElButton
        >
      </template>
    </template>
    <section v-if="setupOpened" v-show="setupOpen" class="admin-card" aria-label="向机构授权并部署">
      <p class="admin-field-help">选择目标机构后继续；模板草稿保留在当前页面。</p>
      <ElButton
        v-for="tenant in setupStarted ? [] : recoverableTenants"
        :key="tenant"
        @click="resumeSetup(tenant)"
        >恢复机构 {{ tenant }} 的原流程</ElButton
      >
      <ElFormItem
        v-if="
          allowed('platform.agent_deployments.create') &&
          item?.status === 'published' &&
          item.distribution_policy === 'AUTHORIZED_DEPLOY'
        "
        label="目标机构"
        required
      >
        <EntitySelect
          v-model="selectedSetupTenant"
          :load="deploymentTenants.load"
          :resolve="deploymentTenants.resolve"
          :disabled="setupStarted || setupAction.busy.value"
          placeholder="搜索目标机构"
        />
      </ElFormItem>
      <ElButton
        v-if="
          !setupStarted &&
          !!setupAction.error.value &&
          allowed('platform.agent_deployments.create') &&
          item?.status === 'published' &&
          item.distribution_policy === 'AUTHORIZED_DEPLOY'
        "
        :loading="setupAction.busy.value"
        :disabled="!selectedSetupTenant"
        @click="startSetup"
        >重新核对并继续</ElButton
      >
      <InstitutionAgentSetup
        v-if="setupStarted"
        :tenant-id="setupTenant"
        :definition-id="setupDefinition"
        @completed="setupCompleted = true"
      />
      <ElButton
        v-if="setupCompleted && allowed('platform.agent_deployments.create')"
        @click="startAnotherSetup"
        >为其他机构部署</ElButton
      >
    </section>
    <FeedbackNotice
      v-if="load.error.value || action.error.value || setupAction.error.value"
      :title="load.error.value || action.error.value || setupAction.error.value"
      :closable="false"
      type="error"
    />
    <FeedbackNotice
      v-if="catalogError"
      :title="'编辑目录读取失败，当前草稿已保留，可刷新编辑选项重试：' + catalogError"
      type="warning"
      :closable="false"
    />
    <ElButton v-if="load.error.value && !ready" :loading="load.loading.value" @click="reload()"
      >重新读取模板</ElButton
    >
    <ElAlert
      v-if="pending"
      title="操作结果待核对，输入与原请求身份已保留；只能核对原操作，不能创建新的写入意图。"
      type="warning"
      :closable="false"
    />
    <ElButton v-if="pending" :loading="action.busy.value" @click="retryPending"
      >核对原操作回执</ElButton
    >
    <ElAlert
      v-if="refreshRequired"
      title="操作回执已确认，但当前服务端草稿尚未刷新。请重读服务端草稿后继续；不要重复提交已确认操作。"
      type="warning"
      :closable="false"
    />
    <ElAlert
      v-if="conflict"
      title="草稿修订已变化，当前输入和旧预览已保留且失效。请重读并对照最新草稿，再明确选择是否覆盖当前输入。"
      type="warning"
      :closable="false"
    />
    <ElCollapse v-if="current && ready && (dirty || conflict)"
      ><ElCollapseItem title="对照已读取的服务端草稿（不会自动覆盖输入）"
        ><p>服务端草稿修订 {{ current.draft_revision }}</p>
        <pre class="readable">{{ JSON.stringify(current.definition, null, 2) }}</pre>
        <ElButton :disabled="action.busy.value || !!pending" @click="replaceWithCurrent"
          >采用最新草稿（覆盖当前输入）</ElButton
        ></ElCollapseItem
      ></ElCollapse
    >
    <p v-if="item" class="admin-field-help">
      可验证当前编辑内容并预览影响；使用“保存并验证”可直接验证已保存修订，发布仍需单独确认。
    </p>
    <FeedbackNotice
      v-if="preview.error.value"
      :title="preview.error.value"
      type="error"
      :closable="false"
    />
    <p v-if="candidatePreview && candidateSnapshot === snapshot()" class="admin-field-help">
      当前为未保存内容预览；保存后需重新验证才能发布。
    </p>
    <AgentDefinitionImpactPanel
      v-if="candidatePreview && candidateSnapshot === snapshot()"
      :impact="candidatePreview"
      :stale="false"
      candidate
    />
    <AgentDefinitionImpactPanel
      v-else-if="preview.data.value"
      :impact="preview.data.value"
      :stale="!previewCurrent"
    />
    <ElAlert
      v-for="issue in issues"
      :key="issue.path + issue.code"
      :title="issue.path + ': ' + issue.message"
      type="warning"
    />
    <ElSkeleton v-if="load.loading.value && !ready" :rows="8" />
    <ElForm
      @keydown.capture="guardFormEnter"
      v-else-if="ready"
      ref="formRef"
      :model="form"
      label-position="top"
      :disabled="
        !allowed(item ? 'platform.agent_definitions.edit' : 'platform.agent_definitions.create') ||
        action.busy.value ||
        !!pending ||
        refreshRequired ||
        item?.status === 'archived'
      "
      @submit.prevent="save()"
    >
      <ElCard class="admin-card">
        <template #header><strong>基本信息与分发</strong></template>
        <AgentConfigFields
          v-model="form"
          :models="models.filter((m) => m.kind === 'LLM' && m.enabled)"
          :disabled="
            !allowed(
              item ? 'platform.agent_definitions.edit' : 'platform.agent_definitions.create'
            ) || action.busy.value
          "
        />
        <p class="admin-field-help">
          <RouterLink
            v-if="allowed('foundation.models.read')"
            to="/platform/models"
            target="_blank"
            rel="noopener noreferrer"
            >管理模型（新标签页）</RouterLink
          >
          <RouterLink
            v-if="allowed('foundation.capabilities.read')"
            to="/platform/capabilities"
            target="_blank"
            rel="noopener noreferrer"
            >管理能力（新标签页）</RouterLink
          >
          <ElButton link :loading="catalogs.loading.value" @click="refreshCatalogs"
            >刷新编辑选项</ElButton
          >
        </p>
        <div class="admin-form-grid">
          <ElFormItem label="分发方式" required>
            <ElSelect
              v-model="meta.distribution_policy"
              :disabled="Boolean(item?.published_at)"
              placeholder="请选择分发方式"
              v-described-by="formTipId + '-distribution'"
              :aria-describedby="formTipId + '-distribution'"
            >
              <ElOption label="独立安装" value="OPEN_INSTALL" />
              <ElOption label="授权部署" value="AUTHORIZED_DEPLOY" />
            </ElSelect>
            <FieldTip :id="formTipId + '-distribution'"
              >独立安装后由机构自行维护；授权部署持续使用获准源定义。首次发布后不能切换。</FieldTip
            >
          </ElFormItem>
          <ElFormItem label="定价">
            <ElSelect
              v-described-by="formTipId + '-field-3'"
              :aria-describedby="formTipId + '-field-3'"
              v-model="meta.price_type"
            >
              <ElOption label="免费" value="free" />
              <ElOption label="付费" value="paid" />
            </ElSelect>

            <FieldTip :id="formTipId + '-field-3'">选择免费或付费，付费模板需要填写价格。</FieldTip>
          </ElFormItem>
          <ElFormItem v-if="meta.price_type === 'paid'" label="价格（分）">
            <ElInputNumber
              v-described-by="formTipId + '-field-4'"
              :aria-describedby="formTipId + '-field-4'"
              v-model="meta.price_cents"
              :min="0"
              :precision="0"
            />

            <FieldTip :id="formTipId + '-field-4'"
              >以分为单位填写非负整数，例如 100 表示 1 元。</FieldTip
            >
          </ElFormItem>
          <ElFormItem class="wide" label="角色说明">
            <ElInput
              v-described-by="formTipId + '-field-5'"
              :aria-describedby="formTipId + '-field-5'"
              v-model="definition.role_description"
              type="textarea"
              :rows="3"
            />

            <FieldTip :id="formTipId + '-field-5'">说明安装后智能体在协作中的职责。</FieldTip>
          </ElFormItem>
        </div>
        <ElCollapse>
          <ElCollapseItem
            name="catalog"
            :title="`目录展示 · ${meta.category || '未分类'} · ${meta.tags.length} 个标签`"
          >
            <div class="admin-form-grid">
              <ElFormItem label="分类">
                <ElInput
                  v-described-by="formTipId + '-field-0'"
                  :aria-describedby="formTipId + '-field-0'"
                  v-model="meta.category"
                />

                <FieldTip :id="formTipId + '-field-0'"
                  >填写模板所属分类，便于机构在模板目录中查找。</FieldTip
                >
              </ElFormItem>
              <ElFormItem label="标签">
                <ElSelect
                  v-described-by="formTipId + '-field-1'"
                  :aria-describedby="formTipId + '-field-1'"
                  v-model="meta.tags"
                  @keydown.enter.capture="onTagEnter"
                  multiple
                  filterable
                  allow-create
                />

                <FieldTip :id="formTipId + '-field-1'">选择或输入模板标签，支持多个标签。</FieldTip>
              </ElFormItem>
              <ElFormItem label="图标地址">
                <ElInput
                  v-described-by="formTipId + '-field-2'"
                  :aria-describedby="formTipId + '-field-2'"
                  v-model="meta.icon_url"
                />

                <FieldTip :id="formTipId + '-field-2'">填写用于模板展示的图标 URL。</FieldTip>
              </ElFormItem>
            </div>
          </ElCollapseItem>
        </ElCollapse>
      </ElCard>

      <ElCard class="admin-card">
        <h3>模板能力 · {{ definition.capabilities.length }} 项</h3>
        <p class="admin-muted">按需添加固定能力；没有挂载时仍可保存模板。</p>
        <ElCard
          class="admin-card"
          v-for="(mount, index) in definition.capabilities"
          :key="entryKey(mount)"
        >
          <div class="admin-form-grid">
            <ElFormItem label="类型">
              <ElSelect
                v-described-by="formTipId + '-field-7' + '-' + entryKey(mount)"
                :aria-describedby="formTipId + '-field-7' + '-' + entryKey(mount)"
                v-model="mount.capability_type"
                @change="
                  () => {
                    mount.capability_definition_id = ''
                    mount.capability_version_id = null
                    mount.platform_tool_name = null
                    mount.skill_config =
                      mount.capability_type === 'SKILL'
                        ? { activation_mode: 'ON_DEMAND', token_budget: null }
                        : null
                  }
                "
              >
                <ElOption value="SKILL" />
                <ElOption value="TOOL" />
              </ElSelect>

              <FieldTip :id="formTipId + '-field-7' + '-' + entryKey(mount)"
                >选择固定挂载的能力类别。</FieldTip
              >
            </ElFormItem>
            <ElFormItem label="能力" :error="entryError(`capabilities[${index}]`)">
              <ElSelect
                v-described-by="formTipId + '-field-8' + '-' + entryKey(mount)"
                :aria-describedby="formTipId + '-field-8' + '-' + entryKey(mount)"
                v-model="mount.capability_definition_id"
                filterable
                @change="
                  () => {
                    selectCapability(mount, true)
                  }
                "
              >
                <ElOption
                  v-for="d in definitions.filter(
                    (d) => d.capabilityType === mount.capability_type && d.defaultVersionId
                  )"
                  :key="d.id"
                  :label="d.name"
                  :value="d.id"
                />
              </ElSelect>

              <FieldTip :id="formTipId + '-field-8' + '-' + entryKey(mount)"
                >选择已发布能力；新执行跟随其当前发布版本。</FieldTip
              >
            </ElFormItem>
            <p class="admin-field-help">绑定能力身份，后续新执行跟随当前发布版本。</p>
            <p v-if="capabilityVersionChanged(mount)" class="admin-field-help" role="status">
              能力已发布新版本；保存时将使用当前发布配置。
            </p>
            <ElFormItem label="优先级">
              <ElInputNumber
                v-described-by="formTipId + '-field-9' + '-' + entryKey(mount)"
                :aria-describedby="formTipId + '-field-9' + '-' + entryKey(mount)"
                v-model="mount.priority"
                :precision="0"
              />

              <FieldTip :id="formTipId + '-field-9' + '-' + entryKey(mount)"
                >设置该能力挂载的装配优先级。</FieldTip
              >
            </ElFormItem>
            <template v-if="mount.skill_config">
              <ElFormItem label="激活方式（跟随所选发布）">
                <ElSelect
                  v-described-by="formTipId + '-field-10' + '-' + entryKey(mount)"
                  :aria-describedby="formTipId + '-field-10' + '-' + entryKey(mount)"
                  v-model="mount.skill_config.activation_mode"
                  disabled
                >
                  <ElOption value="ALWAYS" />
                  <ElOption value="ON_DEMAND" />
                </ElSelect>

                <FieldTip :id="formTipId + '-field-10' + '-' + entryKey(mount)"
                  >跟随所选能力发布版本的激活方式。</FieldTip
                >
              </ElFormItem>
              <ElFormItem label="Token 预算">
                <ElInputNumber
                  v-described-by="formTipId + '-field-11' + '-' + entryKey(mount)"
                  :aria-describedby="formTipId + '-field-11' + '-' + entryKey(mount)"
                  v-model="mount.skill_config.token_budget"
                  :min="1"
                  :precision="0"
                />

                <FieldTip :id="formTipId + '-field-11' + '-' + entryKey(mount)"
                  >限制该技能挂载使用的 Token 额度；这不是整个任务的累计额度。</FieldTip
                >
              </ElFormItem>
            </template>
            <ElFormItem v-else label="平台工具名（自动读取）">
              <ElInput
                v-described-by="formTipId + '-field-12' + '-' + entryKey(mount)"
                :aria-describedby="formTipId + '-field-12' + '-' + entryKey(mount)"
                v-model="mount.platform_tool_name"
                readonly
              />

              <FieldTip :id="formTipId + '-field-12' + '-' + entryKey(mount)"
                >来自所选能力发布配置，保存时由服务端确定。</FieldTip
              >
            </ElFormItem>
          </div>
          <p v-if="capabilityReads[entryKey(mount)]?.busy" class="admin-muted" role="status">
            正在读取能力发布配置…
          </p>
          <FeedbackNotice
            v-if="capabilityReads[entryKey(mount)]?.error"
            :title="capabilityReads[entryKey(mount)]!.error"
            type="error"
            :closable="false"
          />
          <ElButton
            v-if="capabilityReads[entryKey(mount)]?.error"
            @click="selectCapability(mount, true)"
            >重试读取能力配置</ElButton
          >
          <ElButton link type="danger" @click="definition.capabilities.splice(index, 1)"
            >移除</ElButton
          >
        </ElCard>
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
          >添加能力</ElButton
        >
      </ElCard>

      <ElCollapse v-model="optionalSections"
        ><ElCollapseItem
          name="budget"
          :title="`执行预算 · ${Object.values(definition.execution_config).filter((value) => value != null).length} 项覆盖；留空继承场景与角色默认额度`"
          ><ElCard class="admin-card">
            <div class="admin-form-grid">
              <ElFormItem v-for="[key, label] in fields" :key="key" :label="label">
                <ElInputNumber
                  v-described-by="formTipId + '-field-6' + '-' + encodeURIComponent(String(key))"
                  :aria-describedby="formTipId + '-field-6' + '-' + encodeURIComponent(String(key))"
                  v-model="definition.execution_config[key]"
                  :min="1"
                  :precision="0"
                />

                <FieldTip :id="formTipId + '-field-6' + '-' + encodeURIComponent(String(key))">{{
                  budgetOverrideHelp(key, 'template')
                }}</FieldTip>
              </ElFormItem>
            </div>
          </ElCard></ElCollapseItem
        ><ElCollapseItem
          name="resources"
          :title="`机构资源槽位 · ${definition.resource_slots.length} 项`"
          ><ElCard class="admin-card">
            <ElCard
              class="admin-card"
              v-for="(slot, index) in definition.resource_slots"
              :key="entryKey(slot)"
            >
              <div class="admin-form-grid">
                <ElFormItem label="槽位标识" :error="entryError(`resource_slots[${index}].key`)">
                  <ElInput
                    v-described-by="formTipId + '-field-13' + '-' + entryKey(slot)"
                    :aria-describedby="formTipId + '-field-13' + '-' + entryKey(slot)"
                    v-model="slot.key"
                  />

                  <FieldTip :id="formTipId + '-field-13' + '-' + entryKey(slot)"
                    >填写能力槽的稳定标识，供模板绑定引用。</FieldTip
                  >
                </ElFormItem>
                <ElFormItem label="显示名称" :error="entryError(`resource_slots[${index}].label`)">
                  <ElInput
                    v-described-by="formTipId + '-field-14' + '-' + entryKey(slot)"
                    :aria-describedby="formTipId + '-field-14' + '-' + entryKey(slot)"
                    v-model="slot.label"
                  />

                  <FieldTip :id="formTipId + '-field-14' + '-' + entryKey(slot)"
                    >填写机构安装模板时看到的槽位名称。</FieldTip
                  >
                </ElFormItem>
                <ElFormItem label="类型">
                  <ElSelect
                    v-described-by="formTipId + '-field-15' + '-' + entryKey(slot)"
                    :aria-describedby="formTipId + '-field-15' + '-' + entryKey(slot)"
                    v-model="slot.capability_type"
                  >
                    <ElOption
                      v-for="t in ['TOOL', 'WORKFLOW', 'KNOWLEDGE_SOURCE']"
                      :key="t"
                      :value="t"
                    />
                  </ElSelect>

                  <FieldTip :id="formTipId + '-field-15' + '-' + entryKey(slot)"
                    >选择该槽要求绑定的能力类别。</FieldTip
                  >
                </ElFormItem>
                <ElFormItem label="说明">
                  <ElInput
                    v-described-by="formTipId + '-field-16' + '-' + entryKey(slot)"
                    :aria-describedby="formTipId + '-field-16' + '-' + entryKey(slot)"
                    v-model="slot.description"
                  />

                  <FieldTip :id="formTipId + '-field-16' + '-' + entryKey(slot)"
                    >说明该能力槽的用途和绑定要求。</FieldTip
                  >
                </ElFormItem>
              </div>
              <ElButton link type="danger" @click="definition.resource_slots.splice(index, 1)"
                >移除</ElButton
              >
            </ElCard>
            <ElButton @click="addResourceSlot">添加资源槽位</ElButton>
          </ElCard></ElCollapseItem
        ><ElCollapseItem
          name="delegation"
          :title="`委派槽位 · ${definition.delegation_slots.length} 项`"
          ><ElCard class="admin-card">
            <ElCard
              class="admin-card"
              v-for="(slot, index) in definition.delegation_slots"
              :key="entryKey(slot)"
            >
              <div class="admin-form-grid">
                <ElFormItem label="槽位标识" :error="entryError(`delegation_slots[${index}].key`)">
                  <ElInput
                    v-described-by="formTipId + '-field-17' + '-' + entryKey(slot)"
                    :aria-describedby="formTipId + '-field-17' + '-' + entryKey(slot)"
                    v-model="slot.key"
                  />

                  <FieldTip :id="formTipId + '-field-17' + '-' + entryKey(slot)"
                    >填写委派智能体槽的稳定标识。</FieldTip
                  >
                </ElFormItem>
                <ElFormItem
                  label="显示名称"
                  :error="entryError(`delegation_slots[${index}].label`)"
                >
                  <ElInput
                    v-described-by="formTipId + '-field-18' + '-' + entryKey(slot)"
                    :aria-describedby="formTipId + '-field-18' + '-' + entryKey(slot)"
                    v-model="slot.label"
                  />

                  <FieldTip :id="formTipId + '-field-18' + '-' + entryKey(slot)"
                    >填写安装时用于选择委派对象的槽位名称。</FieldTip
                  >
                </ElFormItem>
                <ElFormItem label="说明">
                  <ElInput
                    v-described-by="formTipId + '-field-19' + '-' + entryKey(slot)"
                    :aria-describedby="formTipId + '-field-19' + '-' + entryKey(slot)"
                    v-model="slot.description"
                  />

                  <FieldTip :id="formTipId + '-field-19' + '-' + entryKey(slot)"
                    >说明该槽智能体需要承担的职责。</FieldTip
                  >
                </ElFormItem>
              </div>
              <ElButton link type="danger" @click="definition.delegation_slots.splice(index, 1)"
                >移除</ElButton
              >
            </ElCard>
            <ElButton @click="addDelegationSlot">添加委派槽位</ElButton>
          </ElCard></ElCollapseItem
        ></ElCollapse
      >
      <div class="admin-actions">
        <ElButton
          v-permission="[
            item ? 'platform.agent_definitions.edit' : 'platform.agent_definitions.create'
          ]"
          :type="
            item &&
            dirty &&
            allowed('platform.agent_definitions.validate') &&
            allowed('platform.agent_definitions.read')
              ? undefined
              : 'primary'
          "
          native-type="submit"
          :loading="action.busy.value"
          :disabled="conflict || !!pending || refreshRequired"
          >保存模板</ElButton
        >
        <ElButton
          v-if="
            item &&
            dirty &&
            allowed('platform.agent_definitions.edit') &&
            allowed('platform.agent_definitions.validate') &&
            allowed('platform.agent_definitions.read')
          "
          type="primary"
          :loading="action.busy.value"
          :disabled="conflict || !!pending || refreshRequired"
          @click="save(true)"
          >保存并验证</ElButton
        >
      </div>
    </ElForm>
    <ElDrawer v-model="historyOpen" title="模板发布历史" size="760px" append-to-body>
      <FeedbackNotice
        v-if="history.error.value || action.error.value"
        :title="history.error.value || action.error.value"
        type="error"
        :closable="false"
      />
      <ElButton :loading="history.loading.value" @click="history.reload">刷新发布历史</ElButton>
      <ElEmpty v-if="history.data.value && !history.data.value.length" description="尚无发布记录" />
      <ElCard
        class="admin-card"
        v-for="publication in history.data.value ?? []"
        :key="publication.version"
      >
        <h3>发布修订 {{ publication.version }} · {{ publication.name }}</h3>
        <p>{{ formatDateTime(publication.published_at) }}</p>
        <ElButton
          v-if="allowed('platform.agent_definitions.restore')"
          :disabled="dirty || conflict || action.busy.value || !!pending || refreshRequired"
          @click="restore(publication)"
          >恢复此历史为草稿</ElButton
        >
        <ElCollapse
          ><ElCollapseItem title="查看完整配置">
            <pre class="readable">{{ JSON.stringify(publication, null, 2) }}</pre>
          </ElCollapseItem></ElCollapse
        >
      </ElCard>
    </ElDrawer>
  </AdminPage>
</template>
