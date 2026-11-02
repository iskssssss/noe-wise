<script setup lang="ts">
import FeedbackNotice from '../../feedback/FeedbackNotice.vue'
import { HttpClientError } from '../../utils/http'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import AdminPage from '../../admin/AdminPage.vue'
import FoundationOptionSelect from '../FoundationOptionSelect.vue'
import type { FoundationOption } from '../optionTypes'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useDrawerClose } from '../../admin/drawer'
import { confirmPageLeave, useUnsavedChanges } from '../../admin/leave'
import type {
  Branding,
  CatalogRequest,
  ControlledRoute,
  Dictionary,
  DictionaryValue,
  Feature,
  FeatureGrant,
  Limits,
  NavigationNode,
  PersonalPreference,
  Plan,
  PlanQuota,
  TenantEntitlements
} from './types'
import { useFoundationAppearanceState } from '../appearance'

/** 同一共享页面由双宿主注入客户端及实时 me 权限，不读取令牌或复制认证。 */
const props = defineProps<{
  request: CatalogRequest
  platform: boolean
  permissions: readonly string[]
}>()
const appearance = useFoundationAppearanceState()
const active = ref('dictionaries')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const dictionaries = ref<Dictionary[]>([])
const dictionaryCode = ref('')
const values = ref<DictionaryValue[]>([])
const nodes = ref<NavigationNode[]>([])
const routes = ref<ControlledRoute[]>([])
const features = ref<Feature[]>([])
const plans = ref<Plan[]>([])
const planId = ref('')
const planRevision = ref(0)
const planGrants = ref<FeatureGrant[]>([])
const tenantId = ref('')
const tenantName = ref('')
const tenantGrants = ref<FeatureGrant[]>([])
const tenant = ref<TenantEntitlements | null>(null)
const branding = reactive<Branding>({
  display_name: '',
  logo_url: null,
  primary_color: '#2563eb',
  contact_email: null,
  support_url: null,
  revision: 0
})
const personal = reactive<PersonalPreference>({
  theme: 'system',
  locale: 'zh-CN',
  page_size: 20,
  compact_navigation: false,
  revision: 0
})
const dictionaryForm = reactive({ code: '', name: '', enabled: true, expected_revision: 0 })
const valueForm = reactive({
  dictionary_code: '',
  code: '',
  label: '',
  sort_order: 0,
  enabled: true,
  expected_revision: 0
})
const nodeForm = reactive({
  code: '',
  parent_code: null as string | null,
  title: '',
  route_key: null as string | null,
  sort_order: 0,
  enabled: true,
  expected_revision: 0
})
const nodeKind = computed({
  get: () => (nodeForm.route_key === null ? 'group' : 'page'),
  set: (value: string) => {
    nodeForm.route_key = value === 'group' ? null : ''
  }
})
/** 父节点使用完整路径，禁止把自身或后代设为父级。 */
function nodePath(node: NavigationNode) {
  const titles = [node.title]
  const seen = new Set([node.code])
  let parent = node.parent_code
  while (parent && !seen.has(parent)) {
    seen.add(parent)
    const item = nodes.value.find((candidate) => candidate.code === parent)
    if (!item) break
    titles.unshift(item.title)
    parent = item.parent_code
  }
  return titles.join(' / ')
}
const parentNodes = computed(() => {
  const excluded = new Set([nodeForm.code])
  let changed = true
  while (changed) {
    changed = false
    for (const node of nodes.value) {
      if (node.parent_code && excluded.has(node.parent_code) && !excluded.has(node.code)) {
        excluded.add(node.code)
        changed = true
      }
    }
  }
  return nodes.value.filter((node) => !excluded.has(node.code))
})
const featureForm = reactive({
  code: '',
  name: '',
  description: '',
  enabled: true,
  expected_revision: 0
})
const editor = ref<'dictionary' | 'value' | 'navigation' | 'feature' | ''>('')
const editorBaseline = ref('')
const personalBaseline = ref('')
const brandingBaseline = ref('')
const planBaseline = ref('')
const tenantBaseline = ref('')
const emptyLimits = (): Limits => ({
  agents: null,
  workflows: null,
  knowledge_bases: null,
  members: null,
  channels: null,
  file_bytes: null,
  admin_tasks: null,
  sub_institutions: null
})
const planLimits = reactive<Limits>(emptyLimits())
const tenantLimits = reactive<Limits>(emptyLimits())
const countFields = [
  { key: 'agents', label: '智能体数' },
  { key: 'workflows', label: '工作流数' },
  { key: 'knowledge_bases', label: '知识库数' },
  { key: 'members', label: '成员数' },
  { key: 'channels', label: '渠道数' },
  { key: 'admin_tasks', label: '并行管理任务数' }
] as const
let requestVersion = 0
let dictionaryVersion = 0
let planVersion = 0
let tenantVersion = 0
const availableTabs = computed(() =>
  [
    { key: 'dictionaries', title: '字典', permission: 'foundation.dictionary.read' },
    { key: 'navigation', title: '菜单结构', permission: 'foundation.navigation.read' },
    ...(!props.platform
      ? [{ key: 'branding', title: '机构品牌', permission: 'foundation.settings.read' }]
      : []),
    { key: 'preferences', title: '个人偏好', permission: 'foundation.preferences.manage' },
    { key: 'entitlements', title: '功能与配额', permission: 'foundation.entitlements.read' }
  ].filter((tab) => can(tab.permission))
)

/** 客户端可见性只消费服务端当前权限；保存入口再次实时授权。 */
function can(permission: string) {
  return props.permissions.includes(permission)
}
/** 表单基线只驻留内存，关闭和路由离开共用未保存检查。 */
function editorSnapshot() {
  return JSON.stringify(
    editor.value === 'dictionary'
      ? dictionaryForm
      : editor.value === 'value'
        ? valueForm
        : editor.value === 'navigation'
          ? nodeForm
          : featureForm
  )
}
function beginEditor(kind: typeof editor.value) {
  editor.value = kind
  editorBaseline.value = editorSnapshot()
  error.value = ''
}
function discardEditor() {
  editor.value = ''
  editorBaseline.value = ''
}
const editorWritable = computed(() =>
  can(
    editor.value === 'navigation'
      ? 'foundation.navigation.manage'
      : editor.value === 'feature'
        ? 'foundation.entitlements.manage'
        : 'foundation.dictionary.manage'
  )
)
const editorClose = useDrawerClose({
  open: () => Boolean(editor.value),
  busy: () => saving.value,
  dirty: () => editorSnapshot() !== editorBaseline.value,
  close: discardEditor
})
useUnsavedChanges({
  dirty: () =>
    Boolean(
      (personalBaseline.value && JSON.stringify(personal) !== personalBaseline.value) ||
      (brandingBaseline.value && JSON.stringify(branding) !== brandingBaseline.value) ||
      (planBaseline.value && JSON.stringify(planLimits) !== planBaseline.value) ||
      (tenantBaseline.value && JSON.stringify(tenantLimits) !== tenantBaseline.value)
    ),
  busy: () => saving.value,
  discard: () => {
    personalBaseline.value = ''
    brandingBaseline.value = ''
    planBaseline.value = ''
    tenantBaseline.value = ''
  }
})
/** Element Plus的公共slot类型不保留数据泛型，用稳定编码回到本页已加载的明确类型。 */
function rowByCode<T extends { code: string }>(list: readonly T[], row: unknown): T {
  if (typeof row !== 'object' || row === null || !('code' in row) || typeof row.code !== 'string')
    throw new Error('目录行不存在')
  const value = list.find((item) => item.code === row.code)
  if (!value) throw new Error('目录行已更新，请刷新')
  return value
}
/** 字典slot只允许寻址当前字典目录。 */
function dictionaryRow(row: unknown) {
  return rowByCode(dictionaries.value, row)
}
/** 值slot只允许寻址当前选中字典。 */
function valueRow(row: unknown) {
  return rowByCode(values.value, row)
}
/** 菜单slot只允许寻址当前树。 */
function nodeRow(row: unknown) {
  return rowByCode(nodes.value, row)
}
/** 功能slot只允许寻址当前功能目录。 */
function featureRow(row: unknown) {
  return rowByCode(features.value, row)
}
/** 固定 catalog 根路径；主宿主注入的客户端负责 auth、错误信封和实际前缀。 */
function api<T>(method: 'GET' | 'PUT' | 'DELETE', path: string, data?: unknown) {
  return props.request<T>(method, `foundation/catalog/${path}`, data)
}
/** 不回显任意错误对象，只展示公开业务提示。 */
function message(cause: unknown) {
  return cause instanceof Error ? cause.message : '未能确认操作结果，请先核对当前数据'
}
/** 切换选项卡时只加载该权限范围；版本阻止迟到请求清除新请求的状态。 */
async function load() {
  const version = ++requestVersion
  const tab = active.value
  loading.value = true
  error.value = ''
  try {
    if (tab === 'dictionaries') {
      const result = await api<Dictionary[]>('GET', 'dictionaries')
      if (version === requestVersion) {
        dictionaries.value = result
        if (dictionaryCode.value && !result.some((row) => row.code === dictionaryCode.value)) {
          dictionaryCode.value = ''
          values.value = []
          ++dictionaryVersion
        }
      }
    }
    if (tab === 'navigation') {
      const result = await Promise.all([
        api<NavigationNode[]>('GET', 'navigation'),
        api<ControlledRoute[]>('GET', 'navigation/routes')
      ])
      if (version === requestVersion) [nodes.value, routes.value] = result
    }
    if (tab === 'branding') {
      const result = await api<Branding>('GET', 'branding')
      if (version === requestVersion) {
        Object.assign(branding, result)
        brandingBaseline.value = JSON.stringify(branding)
      }
    }
    if (tab === 'preferences') {
      const result = await api<PersonalPreference>('GET', 'preferences')
      if (version === requestVersion) {
        Object.assign(personal, result)
        personalBaseline.value = JSON.stringify(personal)
      }
    }
    if (tab === 'entitlements') {
      const result = await api<Feature[]>('GET', 'features')
      if (version !== requestVersion) return
      features.value = result
      if (props.platform) {
        const result = await api<Plan[]>('GET', 'plans')
        if (version === requestVersion) plans.value = result
      } else await inspectTenant()
    }
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (version === requestVersion) error.value = message(cause)
  } finally {
    if (version === requestVersion) loading.value = false
  }
}
/** 字典切换以独立版本限制迟到响应，列表只能显示当前选中字典。 */
async function loadValues() {
  const version = ++dictionaryVersion
  const code = dictionaryCode.value
  values.value = []
  if (!code) return
  try {
    const result = await api<DictionaryValue[]>(
      'GET',
      `dictionary-values?dictionary_code=${encodeURIComponent(code)}`
    )
    if (version === dictionaryVersion) values.value = result
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (version === dictionaryVersion) error.value = message(cause)
  }
}
/** 先绑定当前目录中的字典，再读取其值，避免模板承载多条语句。 */
function selectDictionary(row: Dictionary) {
  dictionaryCode.value = row.code
  void loadValues()
}
/** 套餐切换时同时读取同一个计划的限额与功能，不保留另一对象的表单。 */
async function loadPlan(preserveQuotaDraft = false) {
  const keepDraft =
    preserveQuotaDraft && !!planBaseline.value && JSON.stringify(planLimits) !== planBaseline.value
  const version = ++planVersion
  const id = planId.value
  planGrants.value = []
  if (!keepDraft) {
    planRevision.value = 0
    planBaseline.value = ''
    Object.assign(planLimits, emptyLimits())
  }
  if (!id) return
  try {
    const [snapshot, grants] = await Promise.all([
      api<PlanQuota>('GET', `plan-quota?plan_id=${encodeURIComponent(id)}`),
      api<FeatureGrant[]>('GET', `plan-features?plan_id=${encodeURIComponent(id)}`)
    ])
    if (version === planVersion) {
      // 功能授权回读不能覆盖限额草稿，原 CAS 基线保留以检测并发配额修改。
      if (!keepDraft) {
        Object.assign(planLimits, snapshot.limits)
        planBaseline.value = JSON.stringify(planLimits)
        planRevision.value = snapshot.revision
      }
      planGrants.value = grants
    }
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (version === planVersion) error.value = message(cause)
  }
}
/** 对象选择在丢弃确认通过后才改变，取消时保留原对象和限额草稿。 */
async function selectPlan(id: string) {
  if (id === planId.value || !(await confirmPageLeave())) return
  planId.value = id
  await loadPlan()
}
async function changeTenantId(id: string) {
  if (id === tenantId.value || !(await confirmPageLeave())) return
  tenantId.value = id
  invalidateTenant()
}
async function queryTenant() {
  if (await confirmPageLeave()) await inspectTenant()
}
async function chooseTenant(option: FoundationOption | undefined) {
  const id = option?.id || ''
  await changeTenantId(id)
  if (tenantId.value !== id) return
  tenantName.value = option?.name || ''
  if (id) await inspectTenant()
}
/** 机构目标变化使旧检查失效，不能继续显示另一机构的授权结果。 */
function invalidateTenant() {
  tenantVersion++
  tenant.value = null
  tenantBaseline.value = ''
}
/** 平台显式机构或当前机构检查，结果与目标绑定，失败不冒充默认配额。 */
async function inspectTenant(preserveQuotaDraft = false) {
  const quotaSnapshot = tenant.value
  const keepDraft =
    preserveQuotaDraft &&
    !!quotaSnapshot &&
    !!tenantBaseline.value &&
    JSON.stringify(tenantLimits) !== tenantBaseline.value
  const version = ++tenantVersion
  const id = tenantId.value
  if (!keepDraft) tenant.value = null
  tenantGrants.value = []
  if (props.platform && !/^[1-9][0-9]{0,18}$/.test(id)) {
    error.value = '请输入机构 ID'
    return
  }
  const query = props.platform ? `?tenant_id=${encodeURIComponent(id)}` : ''
  try {
    const [result, grants] = await Promise.all([
      api<TenantEntitlements>('GET', `tenant-entitlements${query}`),
      api<FeatureGrant[]>('GET', `tenant-features${query}`)
    ])
    if (version === tenantVersion) {
      tenant.value =
        keepDraft && quotaSnapshot
          ? { ...result, overrides: quotaSnapshot.overrides, revision: quotaSnapshot.revision }
          : result
      tenantGrants.value = grants
      if (!keepDraft) {
        Object.assign(tenantLimits, result.overrides)
        tenantBaseline.value = JSON.stringify(tenantLimits)
      }
    }
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (version === tenantVersion) error.value = message(cause)
  }
}
/** 写入与读回分别反馈，失败保留编辑输入；成功只刷新对应对象。 */
async function mutate(
  action: () => Promise<unknown>,
  after: () => Promise<void> = load,
  successText = '已保存'
) {
  if (saving.value) return
  saving.value = true
  error.value = ''
  try {
    await action()
    discardEditor()
    try {
      await after()
      if (error.value)
        error.value = `${successText}，但最新数据读取失败：${error.value}。可重新读取，无需再次提交。`
    } catch (cause) {
      if (cause instanceof HttpClientError) cause.claimFeedback()
      error.value = `${successText}，但最新数据读取失败：${message(cause)}。可重新读取，无需再次提交。`
    }
    ElMessage.success(successText)
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    error.value = message(cause)
  } finally {
    saving.value = false
  }
}
/** 打开完整编辑表单，编码在已有资源中禁改。 */
function editDictionary(row?: Dictionary) {
  Object.assign(
    dictionaryForm,
    row
      ? { code: row.code, name: row.name, enabled: row.enabled, expected_revision: row.revision }
      : { code: '', name: '', enabled: true, expected_revision: 0 }
  )
  beginEditor('dictionary')
}
/** 字典值编辑只改变标签、排序和启用。 */
function editValue(row?: DictionaryValue) {
  Object.assign(
    valueForm,
    row
      ? {
          dictionary_code: row.dictionary_code,
          code: row.code,
          label: row.label,
          sort_order: row.sort_order,
          enabled: row.enabled,
          expected_revision: row.revision
        }
      : {
          dictionary_code: dictionaryCode.value,
          code: '',
          label: '',
          sort_order: 0,
          enabled: true,
          expected_revision: 0
        }
  )
  beginEditor('value')
}
/** 菜单编辑只允许代码目录中的路由键。 */
function editNode(row?: NavigationNode) {
  Object.assign(
    nodeForm,
    row
      ? {
          code: row.code,
          parent_code: row.parent_code,
          title: row.title,
          route_key: row.route_key,
          sort_order: row.sort_order,
          enabled: row.enabled,
          expected_revision: row.revision
        }
      : {
          code: '',
          parent_code: null,
          title: '',
          route_key: null,
          sort_order: 0,
          enabled: true,
          expected_revision: 0
        }
  )
  beginEditor('navigation')
}
/** 功能目录编码稳定；停用保留已存在关系。 */
function editFeature(row?: Feature) {
  Object.assign(
    featureForm,
    row
      ? {
          code: row.code,
          name: row.name,
          description: row.description,
          enabled: row.enabled,
          expected_revision: row.revision
        }
      : { code: '', name: '', description: '', enabled: true, expected_revision: 0 }
  )
  beginEditor('feature')
}
/** 当前完整表单的保存动作。 */
async function saveEditor() {
  if (!editor.value || saving.value || !editorWritable.value) return
  if (editor.value === 'navigation' && nodeForm.route_key === '') {
    error.value = '请选择业务页面'
    return
  }
  if (editor.value === 'dictionary') await mutate(() => api('PUT', 'dictionaries', dictionaryForm))
  else if (editor.value === 'value')
    await mutate(() => api('PUT', 'dictionary-values', { ...valueForm }), loadValues)
  else if (editor.value === 'navigation') await mutate(() => api('PUT', 'navigation', nodeForm))
  else if (editor.value === 'feature') await mutate(() => api('PUT', 'features', featureForm))
}
/** 引用保护删除由服务端裁定；取消确认不显示为错误。 */
async function remove(
  kind: 'dictionary' | 'value' | 'navigation',
  row: Dictionary | DictionaryValue | NavigationNode
) {
  const command =
    kind === 'navigation'
      ? { code: row.code, expected_revision: row.revision }
      : {
          dictionary_code:
            kind === 'dictionary'
              ? row.code
              : 'dictionary_code' in row
                ? row.dictionary_code
                : dictionaryCode.value,
          code: kind === 'value' ? row.code : null,
          expected_revision: row.revision
        }
  try {
    const name = 'name' in row ? row.name : 'label' in row ? row.label : row.title
    await ElMessageBox.confirm(
      `删除“${name}”（${row.code}）？删除后无法恢复；仍被引用或有子节点时不会删除。`,
      '删除确认',
      {
        type: 'warning',
        autofocus: false
      }
    )
  } catch {
    return
  }
  await mutate(
    () => api('DELETE', kind === 'navigation' ? 'navigation' : 'dictionaries', command),
    kind === 'value' ? loadValues : load,
    '已删除'
  )
}
/** 当前授权项修订，缺失关系使用 0 新建。 */
function grantRevision(list: FeatureGrant[], code: string) {
  return list.find((g) => g.feature_code === code)?.revision ?? 0
}
/** 输入框清空明确转换为 NULL，不让 undefined 被JSON省略成部分更新。 */
function setCountLimit(
  key: (typeof countFields)[number]['key'],
  value: number | null | undefined,
  plan: boolean
) {
  ;(plan ? planLimits : tenantLimits)[key] = value ?? null
}
/** 计划功能授权不允许把无关系当作隐式启用。 */
async function savePlanGrant(code: string, enabled: boolean) {
  await mutate(
    () =>
      api('PUT', 'plan-features', {
        plan_id: planId.value,
        feature_code: code,
        enabled,
        expected_revision: grantRevision(planGrants.value, code)
      }),
    () => loadPlan(true)
  )
}
/** null 明确恢复租户套餐继承，false 明确拒绝。 */
async function saveTenantGrant(code: string, enabled: boolean | null) {
  await mutate(
    () =>
      api('PUT', 'tenant-features', {
        tenant_id: tenant.value?.tenant_id,
        feature_code: code,
        enabled,
        expected_revision: grantRevision(tenantGrants.value, code)
      }),
    () => inspectTenant(true)
  )
}
/** 配额保存携带真实 CAS；null 用于不限或继承，文件字节保持字符串。 */
async function saveQuota(plan: boolean) {
  const limits = plan ? planLimits : tenantLimits
  const normalized = { ...limits, file_bytes: limits.file_bytes === '' ? null : limits.file_bytes }
  const command = plan
    ? { plan_id: planId.value, limits: normalized, expected_revision: planRevision.value }
    : {
        tenant_id: tenant.value?.tenant_id,
        limits: normalized,
        expected_revision: tenant.value?.revision
      }
  await mutate(
    () => api('PUT', plan ? 'plan-quota' : 'tenant-quota', command),
    async () => {
      if (plan) {
        plans.value = await api<Plan[]>('GET', 'plans')
        await loadPlan()
      } else await inspectTenant()
    }
  )
}
/** 保存结果直接更新所属宿主壳；没有宿主上下文时仍通过注入客户端完成同一CAS契约。 */
async function savePersonal() {
  const command = {
    theme: personal.theme,
    locale: personal.locale,
    page_size: personal.page_size,
    compact_navigation: personal.compact_navigation,
    expected_revision: personal.revision
  }
  await mutate(
    async () => {
      const saved = appearance
        ? await appearance.savePersonal(command)
        : await api<PersonalPreference>('PUT', 'preferences', command)
      Object.assign(personal, saved)
      personalBaseline.value = JSON.stringify(personal)
    },
    async () => {}
  )
}
/** 品牌完整保存后当前壳立即采用实际返回的展示值。 */
async function saveBranding() {
  const command = {
    display_name: branding.display_name,
    logo_url: branding.logo_url || null,
    primary_color: branding.primary_color,
    contact_email: branding.contact_email || null,
    support_url: branding.support_url || null,
    expected_revision: branding.revision
  }
  await mutate(
    async () => {
      const saved = appearance
        ? await appearance.saveBranding(command)
        : await api<Branding>('PUT', 'branding', command)
      Object.assign(branding, saved)
      brandingBaseline.value = JSON.stringify(branding)
    },
    async () => {}
  )
}
/** 第一项可用能力作为默认；缺少权限不发无意义请求。 */
onMounted(async () => {
  if (availableTabs.value.length) {
    active.value = availableTabs.value[0]!.key
    await load()
  }
})
onBeforeUnmount(() => {
  ++requestVersion
  ++dictionaryVersion
  ++planVersion
  ++tenantVersion
})
</script>

<template>
  <AdminPage class="catalog-page" title="基础目录与设置">
    <FeedbackNotice
      v-if="error && !editor"
      :title="error"
      type="error"
      show-icon
      :closable="true"
      @close="error = ''"
    />
    <el-empty v-if="!availableTabs.length" description="当前账号没有目录管理权限" />
    <el-tabs v-else v-model="active" :before-leave="confirmPageLeave" @tab-change="load">
      <el-tab-pane v-for="tab in availableTabs" :key="tab.key" :label="tab.title" :name="tab.key" />
    </el-tabs>
    <div v-loading="loading" class="catalog-body">
      <template v-if="active === 'dictionaries' && availableTabs.length">
        <div class="toolbar">
          <el-button
            v-if="can('foundation.dictionary.manage')"
            type="primary"
            @click="editDictionary()"
            >新建字典</el-button
          ><el-button @click="load">刷新</el-button>
        </div>
        <el-table :data="dictionaries" row-key="code" border>
          <el-table-column
            prop="code"
            label="稳定编码"
            min-width="220"
            show-overflow-tooltip
          /><el-table-column
            prop="name"
            label="名称"
            min-width="200"
            show-overflow-tooltip
          /><el-table-column label="状态" min-width="90"
            ><template #default="s">{{
              s.row.enabled ? '启用' : '停用'
            }}</template></el-table-column
          >
          <el-table-column label="操作" width="170" fixed="right"
            ><template #default="s"
              ><el-button link @click="selectDictionary(dictionaryRow(s.row))">查看值</el-button
              ><el-button
                v-if="can('foundation.dictionary.manage')"
                link
                @click="editDictionary(dictionaryRow(s.row))"
                >编辑</el-button
              ><el-button
                v-if="can('foundation.dictionary.manage')"
                link
                type="danger"
                @click="remove('dictionary', dictionaryRow(s.row))"
                >删除</el-button
              ></template
            ></el-table-column
          >
        </el-table>
        <template v-if="dictionaryCode"
          ><div class="toolbar">
            <h2>
              {{
                dictionaries.find((item) => item.code === dictionaryCode)?.name || dictionaryCode
              }}
              · 字典值
            </h2>
            <el-button v-if="can('foundation.dictionary.manage')" @click="editValue()"
              >添加值</el-button
            >
          </div>
          <el-table :data="values" row-key="code" border
            ><el-table-column
              prop="code"
              label="稳定编码"
              min-width="180"
              show-overflow-tooltip
            /><el-table-column
              prop="label"
              label="显示文本"
              min-width="200"
              show-overflow-tooltip
            /><el-table-column prop="sort_order" label="排序" min-width="80" /><el-table-column
              label="状态"
              min-width="80"
              ><template #default="s">{{
                s.row.enabled ? '启用' : '停用'
              }}</template></el-table-column
            ><el-table-column
              v-if="can('foundation.dictionary.manage')"
              label="操作"
              width="140"
              fixed="right"
              ><template #default="s"
                ><el-button link @click="editValue(valueRow(s.row))">编辑</el-button
                ><el-button link type="danger" @click="remove('value', valueRow(s.row))"
                  >删除</el-button
                ></template
              ></el-table-column
            ></el-table
          ></template
        >
      </template>
      <template v-if="active === 'navigation'"
        ><el-alert
          title="路由和对应权限来自代码目录。菜单可见性不替代接口授权。"
          type="info"
          :closable="false"
        />
        <div class="toolbar">
          <el-button v-if="can('foundation.navigation.manage')" type="primary" @click="editNode()"
            >新增节点</el-button
          ><el-button @click="load">刷新</el-button>
        </div>
        <el-table :data="nodes" row-key="code" border
          ><el-table-column prop="code" label="节点编码" /><el-table-column
            label="位置"
            min-width="200"
            ><template #default="{ row }">{{ nodePath(nodeRow(row)) }}</template></el-table-column
          >
          <el-table-column label="类型 / 页面" min-width="180"
            ><template #default="{ row }">{{
              row.route_key
                ? routes.find((route) => route.key === row.route_key)?.title || row.route_key
                : '菜单分组'
            }}</template></el-table-column
          ><el-table-column prop="sort_order" label="排序" /><el-table-column label="状态"
            ><template #default="s">{{
              s.row.enabled ? '启用' : '隐藏'
            }}</template></el-table-column
          ><el-table-column v-if="can('foundation.navigation.manage')" label="操作"
            ><template #default="s"
              ><el-button link @click="editNode(nodeRow(s.row))">编辑</el-button
              ><el-button link type="danger" @click="remove('navigation', nodeRow(s.row))"
                >删除</el-button
              ></template
            ></el-table-column
          ></el-table
        ></template
      >
      <el-form
        v-if="active === 'branding'"
        label-position="top"
        class="settings-form"
        :disabled="saving || !can('foundation.settings.manage')"
        ><el-form-item label="品牌名称"
          ><el-input v-model="branding.display_name" maxlength="128" /></el-form-item
        ><el-form-item label="公开 Logo HTTPS 链接"
          ><el-input v-model="branding.logo_url" clearable /></el-form-item
        ><el-form-item label="品牌色"
          ><el-color-picker v-model="branding.primary_color" /></el-form-item
        ><el-form-item label="联系邮箱"><el-input v-model="branding.contact_email" /></el-form-item
        ><el-form-item label="支持页面 HTTPS 链接"
          ><el-input v-model="branding.support_url" clearable /></el-form-item
        ><el-button
          v-if="can('foundation.settings.manage')"
          :loading="saving"
          :disabled="!brandingBaseline || JSON.stringify(branding) === brandingBaseline"
          type="primary"
          @click="saveBranding"
          >保存品牌</el-button
        ></el-form
      >
      <el-form
        v-if="active === 'preferences'"
        label-position="top"
        class="settings-form"
        :disabled="saving"
        ><el-form-item label="主题"
          ><el-select v-model="personal.theme"
            ><el-option label="跟随系统" value="system" /><el-option
              label="浅色"
              value="light" /><el-option label="深色" value="dark" /></el-select></el-form-item
        ><el-form-item label="列表默认每页条数"
          ><el-select v-model="personal.page_size"
            ><el-option
              v-for="size in [10, 20, 50, 100]"
              :key="size"
              :label="String(size)"
              :value="size" /></el-select></el-form-item
        ><el-form-item label="紧凑导航"
          ><el-switch v-model="personal.compact_navigation" /></el-form-item
        ><el-button
          :loading="saving"
          :disabled="!personalBaseline || JSON.stringify(personal) === personalBaseline"
          type="primary"
          @click="savePersonal"
          >保存个人偏好</el-button
        ></el-form
      >
      <template v-if="active === 'entitlements'">
        <template v-if="platform"
          ><details class="admin-disclosure">
            <summary>功能目录与全局开关</summary>
            <div class="toolbar">
              <el-button v-if="can('foundation.entitlements.manage')" @click="editFeature()"
                >新增功能</el-button
              >
            </div>
            <el-table :data="features" row-key="code" border
              ><el-table-column prop="code" label="功能编码" /><el-table-column
                prop="name"
                label="名称"
              /><el-table-column prop="description" label="说明" /><el-table-column label="全局状态"
                ><template #default="s">{{
                  s.row.enabled ? '启用' : '停用'
                }}</template></el-table-column
              ><el-table-column v-if="can('foundation.entitlements.manage')" label="操作"
                ><template #default="s"
                  ><el-button link @click="editFeature(featureRow(s.row))"
                    >编辑</el-button
                  ></template
                ></el-table-column
              ></el-table
            >
          </details>
          <h2>套餐授权</h2>
          <el-select
            :model-value="planId"
            filterable
            placeholder="选择现有套餐"
            @change="selectPlan"
            ><el-option
              v-for="plan in plans"
              :key="plan.id"
              :label="`${plan.name} (${plan.code})`"
              :value="plan.id"
          /></el-select>
          <template v-if="planId"
            ><el-table :data="features" row-key="code"
              ><el-table-column prop="name" label="功能" /><el-table-column label="套餐授权"
                ><template #default="s"
                  ><el-switch
                    :model-value="
                      planGrants.find((g) => g.feature_code === s.row.code)?.enabled ?? false
                    "
                    :disabled="saving || !can('foundation.entitlements.manage')"
                    @change="
                      (value) => savePlanGrant(s.row.code, Boolean(value))
                    " /></template></el-table-column
            ></el-table>
            <details class="admin-disclosure" :open="JSON.stringify(planLimits) !== planBaseline">
              <summary>套餐资源限额（留空表示不限）</summary>
              <el-form
                class="quota-grid"
                label-position="top"
                :disabled="saving || !can('foundation.entitlements.manage')"
                ><el-form-item v-for="field in countFields" :key="field.key" :label="field.label"
                  ><el-input-number
                    :model-value="planLimits[field.key] ?? undefined"
                    @change="(value) => setCountLimit(field.key, value, true)"
                    :min="0"
                    :max="2147483647"
                    :precision="0" /></el-form-item
                ><el-form-item label="公共文件字节"
                  ><el-input
                    v-model="planLimits.file_bytes"
                    placeholder="留空不限"
                    clearable
                    @clear="planLimits.file_bytes = null" /></el-form-item></el-form
              ><el-button
                v-if="can('foundation.entitlements.manage')"
                :loading="saving"
                :disabled="!planBaseline || JSON.stringify(planLimits) === planBaseline"
                @click="saveQuota(true)"
                >保存套餐限额</el-button
              >
            </details></template
          >
          <h2>机构授权与覆盖</h2>
          <div class="toolbar">
            <FoundationOptionSelect
              :model-value="tenantId"
              :request="request"
              path="foundation/options/tenants"
              label="选择授权机构"
              class="id-input"
              @selected="chooseTenant"
            /><el-button :disabled="!tenantId || loading" @click="queryTenant">刷新授权</el-button>
          </div>
        </template>
        <template v-if="tenant"
          ><el-alert
            :title="`${tenantName || tenant.tenant_id} · 功能与资源用量`"
            type="info"
            :closable="false"
          /><el-table :data="tenant.usage" border
            ><el-table-column prop="kind" label="资源" /><el-table-column
              prop="used"
              label="已用"
            /><el-table-column label="有效上限"
              ><template #default="s">{{ s.row.limit ?? '不限' }}</template></el-table-column
            ></el-table
          >
          <p>
            当前有效功能：{{
              tenant.features
                .map((code) => features.find((feature) => feature.code === code)?.name || code)
                .join('、') || '无'
            }}
          </p>
          <template v-if="platform && can('foundation.entitlements.manage')"
            ><el-table :data="features" row-key="code"
              ><el-table-column prop="name" label="功能" /><el-table-column label="机构覆盖"
                ><template #default="s"
                  ><el-select
                    :model-value="
                      tenantGrants.find((g) => g.feature_code === s.row.code)?.enabled ?? 'inherit'
                    "
                    :disabled="saving"
                    @change="
                      (value) =>
                        saveTenantGrant(s.row.code, value === 'inherit' ? null : Boolean(value))
                    "
                    ><el-option label="继承套餐" value="inherit" /><el-option
                      label="明确启用"
                      :value="true" /><el-option
                      label="明确停用"
                      :value="false" /></el-select></template></el-table-column
            ></el-table>
            <details
              class="admin-disclosure"
              :open="JSON.stringify(tenantLimits) !== tenantBaseline"
            >
              <summary>机构配额覆盖（留空恢复继承）</summary>
              <el-form class="quota-grid" label-position="top" :disabled="saving"
                ><el-form-item v-for="field in countFields" :key="field.key" :label="field.label"
                  ><el-input-number
                    :model-value="tenantLimits[field.key] ?? undefined"
                    @change="(value) => setCountLimit(field.key, value, false)"
                    :min="0"
                    :max="2147483647"
                    :precision="0" /></el-form-item
                ><el-form-item label="公共文件字节"
                  ><el-input
                    v-model="tenantLimits.file_bytes"
                    placeholder="留空继承"
                    clearable
                    @clear="tenantLimits.file_bytes = null" /></el-form-item></el-form
              ><el-button
                :loading="saving"
                :disabled="!tenantBaseline || JSON.stringify(tenantLimits) === tenantBaseline"
                type="primary"
                @click="saveQuota(false)"
                >保存机构覆盖</el-button
              >
            </details></template
          >
        </template>
      </template>
    </div>
    <el-dialog
      :model-value="Boolean(editor)"
      :title="
        editor === 'dictionary'
          ? '字典'
          : editor === 'value'
            ? '字典值'
            : editor === 'navigation'
              ? '菜单节点'
              : '功能目录'
      "
      class="catalog-editor"
      width="min(560px, calc(100vw - 32px))"
      align-center
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      :before-close="() => editorClose.requestClose()"
    >
      <FeedbackNotice v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-form label-position="top" :disabled="saving || !editorWritable">
        <template v-if="editor === 'dictionary'"
          ><el-form-item label="稳定编码"
            ><el-input
              v-model="dictionaryForm.code"
              :disabled="dictionaryForm.expected_revision > 0"
              maxlength="64" /></el-form-item
          ><el-form-item label="名称"
            ><el-input v-model="dictionaryForm.name" maxlength="128" /></el-form-item
          ><el-form-item label="启用"><el-switch v-model="dictionaryForm.enabled" /></el-form-item
        ></template>
        <template v-if="editor === 'value'"
          ><el-form-item label="稳定编码"
            ><el-input
              v-model="valueForm.code"
              :disabled="valueForm.expected_revision > 0"
              maxlength="64" /></el-form-item
          ><el-form-item label="显示文本"
            ><el-input v-model="valueForm.label" maxlength="128" /></el-form-item
          ><el-form-item label="排序"
            ><el-input-number
              v-model="valueForm.sort_order"
              :min="0"
              :precision="0" /></el-form-item
          ><el-form-item label="启用"><el-switch v-model="valueForm.enabled" /></el-form-item
        ></template>
        <template v-if="editor === 'navigation'"
          ><el-form-item label="节点编码"
            ><el-input
              v-model="nodeForm.code"
              :disabled="nodeForm.expected_revision > 0"
              maxlength="64" /></el-form-item
          ><el-form-item label="标题"
            ><el-input v-model="nodeForm.title" maxlength="128" /></el-form-item
          ><el-form-item label="父节点"
            ><el-select
              v-model="nodeForm.parent_code"
              clearable
              @clear="nodeForm.parent_code = null"
              ><el-option
                v-for="node in parentNodes"
                :key="node.code"
                :label="nodePath(node)"
                :value="node.code" /></el-select></el-form-item
          ><el-form-item label="节点类型"
            ><el-radio-group v-model="nodeKind"
              ><el-radio-button value="group">菜单分组</el-radio-button
              ><el-radio-button value="page">业务页面</el-radio-button></el-radio-group
            ></el-form-item
          ><el-form-item v-if="nodeKind === 'page'" label="业务页面" required
            ><el-select v-model="nodeForm.route_key" filterable placeholder="选择受控业务页面"
              ><el-option
                v-for="route in routes"
                :key="route.key"
                :label="`${route.title} · ${route.path}`"
                :value="route.key" /></el-select></el-form-item
          ><el-form-item label="排序"
            ><el-input-number v-model="nodeForm.sort_order" :min="0" :precision="0" /></el-form-item
          ><el-form-item label="显示"><el-switch v-model="nodeForm.enabled" /></el-form-item
        ></template>
        <template v-if="editor === 'feature'"
          ><el-form-item label="功能编码"
            ><el-input
              v-model="featureForm.code"
              :disabled="featureForm.expected_revision > 0"
              maxlength="64" /></el-form-item
          ><el-form-item label="名称"
            ><el-input v-model="featureForm.name" maxlength="128" /></el-form-item
          ><el-form-item label="说明"
            ><el-input
              v-model="featureForm.description"
              type="textarea"
              maxlength="512" /></el-form-item
          ><el-form-item label="全局启用"><el-switch v-model="featureForm.enabled" /></el-form-item
        ></template>
      </el-form>
      <template #footer
        ><el-button :disabled="saving" @click="editorClose.requestClose">取消</el-button
        ><el-button :loading="saving" :disabled="!editorWritable" type="primary" @click="saveEditor"
          >保存</el-button
        ></template
      >
    </el-dialog>
  </AdminPage>
</template>

<style scoped>
.catalog-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
  gap: 16px;
}
.catalog-page > * {
  min-width: 0;
}
.catalog-body {
  min-height: 160px;
  min-width: 0;
}
.catalog-page :deep(.catalog-editor) {
  max-height: calc(100dvh - 32px);
  overflow: auto;
}
.catalog-page :deep(.catalog-editor .el-alert) {
  margin-bottom: 16px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 16px 0;
  flex-wrap: wrap;
}
.toolbar h2 {
  margin: 0;
  overflow-wrap: anywhere;
}
.settings-form {
  max-width: 640px;
}
.settings-form .el-alert {
  margin-bottom: 20px;
}
.quota-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
  margin-top: 16px;
}
.id-input {
  max-width: 320px;
}
</style>
