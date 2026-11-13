<script setup lang="ts">
import FeedbackNotice from '../../feedback/FeedbackNotice.vue'
import { HttpClientError } from '../../utils/http'
import { computed, onBeforeUnmount, onMounted, reactive, ref, useId, watch } from 'vue'
import AdminPage from '../../admin/AdminPage.vue'
import FoundationOptionSelect from '../FoundationOptionSelect.vue'
import type { FoundationOption } from '../optionTypes'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUnsavedChanges } from '../../admin/leave'
import { useFoundationPageSize } from '../appearance'
import FieldTip from '../../components/FieldTip.vue'
import { vDescribedBy } from '../../directives/describedBy'
import type {
  AccessRequest,
  Department,
  Grant,
  Member,
  MemberPage,
  Permission,
  Position,
  ResourceAuthorization,
  Role
} from './types'

/** 页面只消费宿主注入的认证客户端与实时权限，不读取令牌。 */
const props = defineProps<{
  request: AccessRequest
  platform: boolean
  permissions: readonly string[]
}>()
const tab = ref('members')
const formTipId = useId()
const loading = ref(false)
const loaded = ref(false)
const saving = ref(false)
const error = ref('')
const editor = ref<'department' | 'position' | 'member' | 'member_roles' | 'role' | ''>('')
const departments = ref<Department[]>([])
const positions = ref<Position[]>([])
const roles = ref<Role[]>([])
const permissionCatalog = ref<Permission[]>([])
const members = ref<Member[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = useFoundationPageSize()
const keyword = ref('')
const appliedKeyword = ref('')
const departmentForm = reactive({
  id: '',
  code: '',
  name: '',
  parent_id: null as string | null,
  enabled: true,
  lock_version: 0
})
const positionForm = reactive({ id: '', code: '', name: '', enabled: true, lock_version: 0 })
const memberForm = reactive({
  id: '',
  username: '',
  display_name: '',
  email: '',
  password: '',
  credential: 'invitation',
  department_id: null as string | null,
  position_id: null as string | null,
  status: 'active',
  role_ids: [] as string[],
  lock_version: 0
})
const roleForm = reactive({
  id: '',
  code: '',
  name: '',
  data_scope: 'self' as Role['data_scope'],
  permissions: [] as string[],
  department_ids: [] as string[],
  enabled: true,
  lock_version: 0
})
const resourceKind = ref<ResourceAuthorization['kind']>('agent')
const resourceId = ref('')
const resourceName = ref('')
const switchingResource = ref(false)
const resource = ref<ResourceAuthorization | null>(null)
const resourceBaseline = ref('')
const resourceError = ref('')
const permissionFilter = ref('')
const selectedPermissionsOnly = ref(false)
const memberEmailError = ref('')
const editorBaseline = ref('')
let requestVersion = 0
let resourceVersion = 0
const scopes = [
  { value: 'self', label: '本人' },
  { value: 'department', label: '本部门' },
  { value: 'descendants', label: '本部门及下级' },
  { value: 'specified', label: '指定部门' },
  { value: 'all', label: '全机构' }
]
const memberStates: Record<string, string> = {
  active: '启用',
  disabled: '停用',
  pending: '待发送邀请',
  invited: '待接受邀请'
}
const manageableRoles = computed(() => roles.value.filter((role) => role.enabled))
const permissionGroups = computed(() => {
  const groups = new Map<string, Permission[]>()
  const term = permissionFilter.value.trim().toLowerCase()
  for (const permission of permissionCatalog.value) {
    if (selectedPermissionsOnly.value && !roleForm.permissions.includes(permission.code)) continue
    if (!`${permission.name} ${permission.code}`.toLowerCase().includes(term)) continue
    const group = permission.code.split('.').slice(0, 2).join('.')
    const items = groups.get(group) ?? []
    items.push(permission)
    groups.set(group, items)
  }
  return [...groups].map(([code, items]) => ({ code, items }))
})
const permissionGroupNames: Record<string, string> = {
  'foundation.access': '组织与权限',
  'foundation.agents': '智能体',
  'foundation.audit': '审计',
  'foundation.billing': '账务',
  'foundation.capabilities': '能力目录',
  'foundation.channels': '渠道',
  'foundation.connectors': '连接器',
  'foundation.dictionary': '字典',
  'foundation.entitlements': '功能与配额',
  'foundation.files': '公共文件',
  'foundation.handoff': '人工接管',
  'foundation.jobs': '导入导出',
  'foundation.knowledge': '知识库',
  'foundation.memory': '记忆',
  'foundation.messaging': '消息通知',
  'foundation.marketplace': '市场审核',
  'foundation.models': '模型',
  'foundation.navigation': '菜单',
  'foundation.operations': '审计与运维',
  'foundation.preferences': '个人偏好',
  'foundation.resource': '资源共享',
  'foundation.runtime_settings': '运行配置',
  'foundation.security': '账号安全',
  'foundation.service_principals': '服务凭据',
  'foundation.sessions': '会话与任务',
  'foundation.settings': '机构设置',
  'foundation.skills': '技能',
  'foundation.system_agent_tools': '系统工具',
  'foundation.system_agents': '系统智能体',
  'foundation.teams': '智能体团队',
  'foundation.tenants': '机构',
  'foundation.usage': '用量',
  'foundation.webhooks': '事件订阅',
  'foundation.workflows': '工作流',
  'platform.agent_definitions': '平台共享定义',
  'platform.agent_grants': '平台授权',
  'platform.agent_deployments': '平台部署',
  'platform.institution_credentials': '机构执行凭证',
  'platform.institution_management_grants': '总部部署管理许可',
  'platform.institution_relations': '机构关系',
  'platform.institutions': '机构接入',
  'platform.management_principals': '管理机器身份',
  'platform.usage': '平台用量',
  'tenant.agent_definitions': '机构共享定义',
  'tenant.agent_grants': '机构授权',
  'tenant.agent_deployments': '机构部署',
  'tenant.sub_institutions': '直属机构'
}
const parentDepartments = computed(() => {
  const excluded = new Set([departmentForm.id])
  let changed = true
  while (changed) {
    changed = false
    for (const item of departments.value) {
      if (item.parent_id && excluded.has(item.parent_id) && !excluded.has(item.id)) {
        excluded.add(item.id)
        changed = true
      }
    }
  }
  return departments.value.filter((item) => item.enabled && !excluded.has(item.id))
})
const resourceDirty = computed(
  () => resource.value !== null && JSON.stringify(resource.value) !== resourceBaseline.value
)
const editorWritable = computed(() => {
  if (editor.value === 'role' || editor.value === 'member_roles')
    return can('foundation.access.role.manage')
  if (!editor.value) return false
  return (
    can('foundation.access.organization.manage') &&
    (editor.value !== 'member' || !!memberForm.id || can('foundation.access.role.manage'))
  )
})
const editorDirty = computed(() => !!editor.value && editorSnapshot() !== editorBaseline.value)
const dialogTitle = computed(
  () =>
    ({
      department: `${departmentForm.id ? '编辑' : '新增'}部门`,
      position: `${positionForm.id ? '编辑' : '新增'}岗位`,
      member: `${memberForm.id ? '编辑' : '新增'}成员`,
      member_roles: `设置 ${memberForm.display_name} 的角色`,
      role: `${roleForm.id ? '编辑' : '新增'}角色`,
      '': ''
    })[editor.value]
)

/** 仅控制客户端展示，服务端在写入前再次实时判权。 */
function can(code: string) {
  return props.permissions.includes(code)
}
/** 离开确认只比较内存草稿；账号密码不写入浏览器存储。 */
function editorSnapshot() {
  return JSON.stringify(
    editor.value === 'department'
      ? departmentForm
      : editor.value === 'position'
        ? positionForm
        : editor.value === 'role'
          ? roleForm
          : memberForm
  )
}
function beginEditor(kind: typeof editor.value) {
  editor.value = kind
  editorBaseline.value = editorSnapshot()
}
function discardEditor() {
  editor.value = ''
  memberForm.password = ''
  editorBaseline.value = ''
}
useUnsavedChanges({
  dirty: () => editorDirty.value || resourceDirty.value,
  busy: () => saving.value,
  discard: () => {
    discardEditor()
    resource.value = null
  }
})
/** 请求前缀由宿主账号体系统一补全。 */
function api<T>(method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string, data?: unknown) {
  return props.request<T>(method, `foundation/access/${path}`, data)
}
/** 公共业务错误提示。 */
function message(cause: unknown) {
  return cause instanceof Error ? cause.message : '未能确认操作结果，请先核对当前数据'
}
/** 页内请求以世代绑定，迟到响应不能覆盖新查询或卸载后的状态。 */
async function load() {
  const version = ++requestVersion
  loading.value = true
  try {
    const [departmentRows, positionRows, roleRows, permissionRows, memberRows] = await Promise.all([
      api<Department[]>('GET', 'departments'),
      api<Position[]>('GET', 'positions'),
      api<Role[]>('GET', 'roles'),
      api<Permission[]>('GET', 'permissions'),
      api<MemberPage>(
        'GET',
        `members?page=${page.value}&page_size=${pageSize.value}&keyword=${encodeURIComponent(appliedKeyword.value)}`
      )
    ])
    if (version !== requestVersion) return
    departments.value = departmentRows
    positions.value = positionRows
    roles.value = roleRows
    permissionCatalog.value = permissionRows
    members.value = memberRows.items
    total.value = memberRows.total
    loaded.value = true
    error.value = ''
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (version === requestVersion) error.value = message(cause)
  } finally {
    if (version === requestVersion) loading.value = false
  }
}
/** 显式查询一次；翻页沿用已提交关键词。 */
function search() {
  appliedKeyword.value = keyword.value.trim()
  page.value = 1
  void load()
}
/** 每页数量变化后回到第一页，沿用已提交的查询条件读取成员。 */
function changePageSize() {
  page.value = 1
  void load()
}
/** 表单在确认后打开，复制对象并保持本次编辑基线版本。 */
function openDepartment(row?: Department) {
  if (!can('foundation.access.organization.manage')) return
  Object.assign(
    departmentForm,
    row ?? { id: '', code: '', name: '', parent_id: null, enabled: true, lock_version: 0 }
  )
  beginEditor('department')
}
/** 岗位编码创建后不可更改。 */
function openPosition(row?: Position) {
  if (!can('foundation.access.organization.manage')) return
  Object.assign(positionForm, row ?? { id: '', code: '', name: '', enabled: true, lock_version: 0 })
  beginEditor('position')
}
/** 邀请是默认创建方式，密码不会从成员详情回填。 */
function openMember(row?: Member, roleOnly = false) {
  if (
    roleOnly
      ? !can('foundation.access.role.manage')
      : !can('foundation.access.organization.manage') ||
        (!row && !can('foundation.access.role.manage'))
  )
    return
  Object.assign(
    memberForm,
    {
      id: '',
      username: '',
      display_name: '',
      email: '',
      password: '',
      credential: 'invitation',
      department_id: null,
      position_id: null,
      status: 'active',
      role_ids: [],
      lock_version: 0
    },
    row ? { ...row, role_ids: [...row.role_ids], email: row.email ?? '' } : {}
  )
  memberEmailError.value = ''
  beginEditor(roleOnly ? 'member_roles' : 'member')
}
/** 内置角色只读，角色定义操作复制集合，不直接修改列表事实。 */
function openRole(row?: Role) {
  if (!can('foundation.access.role.manage') || row?.built_in) return
  Object.assign(
    roleForm,
    row
      ? { ...row, permissions: [...row.permissions], department_ids: [...row.department_ids] }
      : {
          id: '',
          code: '',
          name: '',
          data_scope: 'self',
          permissions: [],
          department_ids: [],
          enabled: true,
          lock_version: 0
        }
  )
  permissionFilter.value = ''
  selectedPermissionsOnly.value = false
  beginEditor('role')
}
/** 写入成功与后续刷新分开，刷新失败不会再次提交创建请求。 */
async function submit() {
  if (saving.value || !editorWritable.value) return
  error.value = ''
  if (editor.value === 'member') {
    const email = memberForm.email.trim()
    memberEmailError.value =
      !email && !memberForm.id && memberForm.credential === 'invitation'
        ? '邀请激活需要填写邮箱，也可改为“设置初始密码”。'
        : email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
          ? '邮箱格式无效'
          : ''
    if (memberEmailError.value) return
  }
  saving.value = true
  try {
    if (editor.value === 'department')
      await api(
        '' === departmentForm.id ? 'POST' : 'PUT',
        'department',
        departmentForm.id
          ? {
              id: departmentForm.id,
              name: departmentForm.name,
              parent_id: departmentForm.parent_id,
              enabled: departmentForm.enabled,
              lock_version: departmentForm.lock_version
            }
          : {
              code: departmentForm.code,
              name: departmentForm.name,
              parent_id: departmentForm.parent_id
            }
      )
    if (editor.value === 'position')
      await api(
        positionForm.id ? 'PUT' : 'POST',
        'position',
        positionForm.id
          ? {
              id: positionForm.id,
              name: positionForm.name,
              enabled: positionForm.enabled,
              lock_version: positionForm.lock_version
            }
          : { code: positionForm.code, name: positionForm.name }
      )
    if (editor.value === 'member_roles')
      await api('POST', 'member-roles', {
        member_id: memberForm.id,
        role_ids: [...memberForm.role_ids],
        lock_version: memberForm.lock_version
      })
    if (editor.value === 'member') {
      if (memberForm.id)
        await api('PUT', 'member', {
          id: memberForm.id,
          display_name: memberForm.display_name,
          email: memberForm.email || null,
          department_id: memberForm.department_id,
          position_id: memberForm.position_id,
          status: memberForm.status,
          lock_version: memberForm.lock_version
        })
      else
        await api('POST', 'member', {
          username: memberForm.username,
          display_name: memberForm.display_name,
          email: memberForm.email || null,
          password: memberForm.credential === 'password' ? memberForm.password : null,
          department_id: memberForm.department_id,
          position_id: memberForm.position_id,
          role_ids: [...memberForm.role_ids]
        })
    }
    if (editor.value === 'role') {
      const definition = {
        name: roleForm.name,
        data_scope: roleForm.data_scope,
        permissions: [...roleForm.permissions],
        department_ids: roleForm.data_scope === 'specified' ? [...roleForm.department_ids] : []
      }
      await api(
        roleForm.id ? 'PUT' : 'POST',
        'role',
        roleForm.id
          ? {
              ...definition,
              id: roleForm.id,
              enabled: roleForm.enabled,
              lock_version: roleForm.lock_version
            }
          : { ...definition, code: roleForm.code }
      )
    }
    discardEditor()
    ElMessage.success('已保存')
    await load()
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    error.value = message(cause)
  } finally {
    saving.value = false
  }
}
/** 删除前展示明确对象，取消不发写请求。 */
async function remove(kind: 'department' | 'position' | 'role', row: Department | Position | Role) {
  const permission =
    kind === 'role' ? 'foundation.access.role.manage' : 'foundation.access.organization.manage'
  if (saving.value || !can(permission) || ('built_in' in row && row.built_in)) return
  try {
    await ElMessageBox.confirm(`删除“${row.name}”？仍被引用的对象将保留。`, '确认删除', {
      type: 'warning',
      autofocus: false
    })
  } catch {
    return
  }
  if (saving.value || !can(permission)) return
  saving.value = true
  try {
    await api('DELETE', kind, { id: row.id, lock_version: row.lock_version })
    ElMessage.success('已删除')
    await load()
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    error.value = message(cause)
  } finally {
    saving.value = false
  }
}
/** 待邀请成员通过真实安全域发信，投递结果在通知管理查看。 */
async function invite(member: Member) {
  if (saving.value || !can('foundation.security.invitation.manage')) return
  saving.value = true
  try {
    await props.request('POST', 'foundation/security/invitations', { member_id: member.id })
    ElMessage.success('激活邀请已受理，尚不代表邮件已送达')
    await load()
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    error.value = message(cause)
  } finally {
    saving.value = false
  }
}
/** 资源读取绑定类型与业务ID；拒绝切换时遗留上个对象的授权表单。 */
async function confirmResourceSwitch() {
  if (resourceDirty.value) {
    try {
      await ElMessageBox.confirm('放弃当前未保存的资源授权修改？', '切换资源')
    } catch {
      return false
    }
  }
  return !saving.value && can('foundation.access.resource.manage')
}
async function changeResourceKind(kind: ResourceAuthorization['kind']) {
  if (switchingResource.value || saving.value || kind === resourceKind.value) return
  switchingResource.value = true
  try {
    if (!(await confirmResourceSwitch())) return
    ++resourceVersion
    resourceKind.value = kind
    resourceId.value = ''
    resourceName.value = ''
    resource.value = null
    resourceError.value = ''
  } finally {
    switchingResource.value = false
  }
}
async function inspectResource(option: FoundationOption | undefined) {
  if (
    !option ||
    switchingResource.value ||
    saving.value ||
    !can('foundation.access.resource.manage')
  )
    return
  const kind = resourceKind.value
  const id = option.id
  const version = ++resourceVersion
  switchingResource.value = true
  try {
    if (!(await confirmResourceSwitch())) return
    resourceId.value = id
    resource.value = null
    resourceError.value = ''
    loading.value = true
    const result = await api<ResourceAuthorization>(
      'GET',
      `resource?kind=${kind}&resource_id=${encodeURIComponent(id)}`
    )
    if (version === resourceVersion) {
      resource.value = result
      resourceName.value = option.name
      resourceBaseline.value = JSON.stringify(result)
    }
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (version === resourceVersion) resourceError.value = message(cause)
  } finally {
    if (version === resourceVersion) {
      loading.value = false
      switchingResource.value = false
    }
  }
}
/** 明确添加一条共享目标，不给予任何动作默认授权。 */
function addGrant() {
  if (!saving.value && can('foundation.access.resource.manage'))
    resource.value?.grants.push({ grantee_type: 'member', grantee_id: '', action: 'read' })
}
/** 不同目标类型使用同一已授权资源上下文，成员搜索和已选解析不得扩张对象访问。 */
function resourceMemberPath(purpose: 'resource-owner' | 'resource-grantee') {
  return `foundation/options/members?purpose=${purpose}&kind=${resource.value?.kind}&resource_id=${encodeURIComponent(resource.value?.resource_id || '')}`
}
function grantOptions(grant: Grant) {
  return grant.grantee_type === 'role'
    ? roles.value.map((role) => ({ id: role.id, name: role.name }))
    : departments.value.map((department) => ({ id: department.id, name: department.name }))
}
/** 保存资源授权时使用已有对象身份和版本，不使用正在编辑的查询栏身份。 */
async function saveResource() {
  if (!resource.value || saving.value || !can('foundation.access.resource.manage')) return
  saving.value = true
  resourceError.value = ''
  try {
    const current = resource.value
    const saved = await api<ResourceAuthorization>('PUT', 'resource-authorization', {
      kind: current.kind,
      resource_id: current.resource_id,
      owner_member_id: current.owner_member_id,
      department_id: current.department_id,
      grants: current.grants.map(({ grantee_type, grantee_id, action }) => ({
        grantee_type,
        grantee_id,
        action
      })),
      lock_version: current.lock_version
    })
    resource.value = saved
    resourceBaseline.value = JSON.stringify(saved)
    ElMessage.success('资源授权已保存')
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    resourceError.value = message(cause)
  } finally {
    saving.value = false
  }
}
/** 关闭编辑时保留提交中状态；普通取消经明确确认后释放草稿。 */
async function close(done: () => void) {
  if (saving.value) return
  try {
    if (editorDirty.value) await ElMessageBox.confirm('关闭表单并放弃尚未保存的修改？', '关闭编辑')
    if (saving.value) return
    discardEditor()
    done()
  } catch {
    /* 保留当前草稿 */
  }
}
watch(
  () => props.platform,
  () => {
    ++resourceVersion
    resource.value = null
    discardEditor()
    void load()
  }
)
watch(
  () => [memberForm.email, memberForm.credential] as const,
  () => {
    memberEmailError.value = ''
  }
)
onMounted(() => {
  void load()
})
onBeforeUnmount(() => {
  ++requestVersion
  ++resourceVersion
  memberForm.password = ''
})
</script>

<template>
  <AdminPage
    class="access-page"
    :title="platform ? '平台组织与权限' : '机构组织与权限'"
    :aria-busy="loading"
  >
    <template #extra
      ><el-button :loading="loading" :disabled="saving" @click="load">刷新</el-button></template
    >
    <FeedbackNotice v-if="error" :title="error" type="error" :closable="false" show-icon />
    <p v-if="!loaded" class="access-read-status" role="status" aria-live="polite">
      {{ loading ? '正在读取组织与权限…' : '组织与权限尚未加载，请点击刷新重试。' }}
    </p>
    <p v-else-if="loading" class="access-read-status" role="status" aria-live="polite">
      正在刷新，仍显示上次成功读取的数据。
    </p>
    <el-tabs v-model="tab">
      <el-tab-pane label="成员" name="members">
        <div class="toolbar">
          <el-input
            v-model="keyword"
            v-described-by="formTipId + '-keyword'"
            :aria-describedby="formTipId + '-keyword'"
            placeholder="用户名或显示名称"
            clearable
            @keyup.enter="search"
          /><el-button @click="search">查询</el-button
          ><el-button
            v-if="
              can('foundation.access.organization.manage') && can('foundation.access.role.manage')
            "
            :disabled="saving"
            type="primary"
            @click="openMember()"
            >新增成员</el-button
          >
        </div>
        <FieldTip :id="formTipId + '-keyword'"> 按姓名或账号查询；翻页沿用已查询条件。 </FieldTip>
        <el-table v-if="loaded" :data="members" v-loading="loading" row-key="id"
          ><el-table-column label="成员" min-width="210"
            ><template #default="{ row }"
              ><div>{{ row.display_name }}</div>
              <div class="admin-field-help">{{ row.username }}</div>
              <div class="admin-field-help">{{ row.email || '未登记邮箱' }}</div></template
            ></el-table-column
          ><el-table-column label="组织归属" min-width="210"
            ><template #default="{ row }"
              ><div>
                部门：{{
                  departments.find((item) => item.id === row.department_id)?.name ?? '未分配'
                }}
              </div>
              <div class="admin-field-help">
                角色：{{
                  row.roles
                    .map((code: string) => roles.find((role) => role.code === code)?.name || code)
                    .join('、') || '无角色'
                }}
              </div>
              <div class="admin-field-help">
                状态：{{ memberStates[row.status] ?? row.status }}
              </div></template
            ></el-table-column
          ><el-table-column
            label="操作"
            width="260"
            fixed="right"
            class-name="access-member-actions"
            ><template #default="{ row }"
              ><el-button
                v-if="can('foundation.access.organization.manage')"
                link
                :disabled="saving"
                @click="openMember(row as Member)"
                >编辑资料</el-button
              ><el-button
                v-if="can('foundation.access.role.manage')"
                link
                :disabled="saving"
                @click="openMember(row as Member, true)"
                >设置角色</el-button
              ><el-button
                v-if="
                  ['pending', 'invited'].includes(row.status) &&
                  can('foundation.security.invitation.manage')
                "
                link
                :disabled="saving"
                @click="invite(row as Member)"
                >发送邀请</el-button
              ></template
            ></el-table-column
          ></el-table
        >
        <el-pagination
          v-if="loaded"
          v-described-by="formTipId + '-pagination'"
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="sizes, prev, pager, next, total"
          @current-change="load"
          @size-change="changePageSize"
        />
        <FieldTip v-if="loaded" :id="formTipId + '-pagination'"> 条数仅对当前列表生效。 </FieldTip>
      </el-tab-pane>
      <el-tab-pane label="部门" name="departments"
        ><el-button
          v-if="can('foundation.access.organization.manage')"
          :disabled="saving"
          @click="openDepartment()"
          >新增部门</el-button
        ><el-table v-if="loaded" :data="departments" row-key="id"
          ><el-table-column prop="code" label="编码" /><el-table-column
            prop="name"
            label="名称"
          /><el-table-column label="上级部门"
            ><template #default="{ row }">{{
              departments.find((item) => item.id === row.parent_id)?.name ?? '根部门'
            }}</template></el-table-column
          ><el-table-column label="状态"
            ><template #default="{ row }">{{
              row.enabled ? '启用' : '停用'
            }}</template></el-table-column
          ><el-table-column v-if="can('foundation.access.organization.manage')" label="操作"
            ><template #default="{ row }"
              ><el-button link :disabled="saving" @click="openDepartment(row as Department)"
                >编辑</el-button
              ><el-button
                link
                type="danger"
                :disabled="saving"
                @click="remove('department', row as Department)"
                >删除</el-button
              ></template
            ></el-table-column
          ></el-table
        ></el-tab-pane
      >
      <el-tab-pane label="岗位" name="positions"
        ><el-button
          v-if="can('foundation.access.organization.manage')"
          :disabled="saving"
          @click="openPosition()"
          >新增岗位</el-button
        ><el-table v-if="loaded" :data="positions" row-key="id"
          ><el-table-column prop="code" label="编码" /><el-table-column
            prop="name"
            label="名称"
          /><el-table-column label="状态"
            ><template #default="{ row }">{{
              row.enabled ? '启用' : '停用'
            }}</template></el-table-column
          ><el-table-column v-if="can('foundation.access.organization.manage')" label="操作"
            ><template #default="{ row }"
              ><el-button link :disabled="saving" @click="openPosition(row as Position)"
                >编辑</el-button
              ><el-button
                link
                type="danger"
                :disabled="saving"
                @click="remove('position', row as Position)"
                >删除</el-button
              ></template
            ></el-table-column
          ></el-table
        ></el-tab-pane
      >
      <el-tab-pane label="角色与数据范围" name="roles"
        ><el-button
          v-if="can('foundation.access.role.manage')"
          :disabled="saving"
          @click="openRole()"
          >新增角色</el-button
        ><el-table v-if="loaded" :data="roles" row-key="id"
          ><el-table-column prop="code" label="编码" /><el-table-column
            prop="name"
            label="名称"
          /><el-table-column label="数据范围"
            ><template #default="{ row }">{{
              scopes.find((scope) => scope.value === row.data_scope)?.label
            }}</template></el-table-column
          ><el-table-column label="类型"
            ><template #default="{ row }">{{
              row.built_in ? '内置（只读）' : '自定义'
            }}</template></el-table-column
          ><el-table-column label="权限数"
            ><template #default="{ row }">{{ row.permissions.length }}</template></el-table-column
          ><el-table-column label="操作"
            ><template #default="{ row }"
              ><el-button
                link
                :disabled="saving || !can('foundation.access.role.manage') || row.built_in"
                @click="openRole(row as Role)"
                >编辑</el-button
              ><el-button
                link
                type="danger"
                :disabled="saving || !can('foundation.access.role.manage') || row.built_in"
                @click="remove('role', row as Role)"
                >删除</el-button
              ></template
            ></el-table-column
          ></el-table
        ></el-tab-pane
      >
      <el-tab-pane
        v-if="!platform && can('foundation.access.resource.manage')"
        label="资源授权"
        name="resources"
      >
        <div class="toolbar">
          <el-select
            :model-value="resourceKind"
            :disabled="saving || switchingResource"
            aria-label="资源类型"
            @update:model-value="changeResourceKind"
            ><el-option label="智能体" value="agent" /><el-option
              label="工作流容器"
              value="workflow" /><el-option label="知识库" value="knowledge" /></el-select
          ><FoundationOptionSelect
            :model-value="resourceId"
            :request="request"
            :path="`foundation/options/resources?kind=${resourceKind}`"
            label="选择资源"
            :disabled="saving || switchingResource"
            :clearable="false"
            @selected="inspectResource"
          />
        </div>
        <FeedbackNotice
          v-if="resourceError"
          :title="resourceError"
          type="error"
          :closable="false"
        />
        <el-form
          v-if="resource"
          label-width="120px"
          :disabled="saving || !can('foundation.access.resource.manage')"
          ><el-form-item label="正在编辑"
            >{{ resourceName || resource.resource_id }} · 授权版本
            {{ resource.lock_version }}</el-form-item
          ><el-form-item label="所有者"
            ><FoundationOptionSelect
              :model-value="resource.owner_member_id || ''"
              :request="request"
              :path="resourceMemberPath('resource-owner')"
              label="资源所有者"
              @update:model-value="resource.owner_member_id = $event || null" /></el-form-item
          ><el-form-item label="归属部门"
            ><el-select
              v-model="resource.department_id"
              clearable
              @clear="resource.department_id = null"
              ><el-option
                v-for="department in departments.filter((item) => item.enabled)"
                :key="department.id"
                :label="department.name"
                :value="department.id" /></el-select></el-form-item
          ><el-form-item label="共享目标"
            ><div class="grants">
              <div v-for="(grant, index) in resource.grants" :key="index" class="grant-row">
                <el-select
                  v-model="grant.grantee_type"
                  aria-label="共享目标类型"
                  @change="grant.grantee_id = ''"
                  ><el-option label="成员" value="member" /><el-option
                    label="角色"
                    value="role" /><el-option label="部门" value="department" /></el-select
                ><FoundationOptionSelect
                  v-if="grant.grantee_type === 'member'"
                  v-model="grant.grantee_id"
                  :request="request"
                  :path="resourceMemberPath('resource-grantee')"
                  label="共享成员"
                />
                <el-select v-else v-model="grant.grantee_id" filterable aria-label="共享目标"
                  ><el-option
                    v-for="option in grantOptions(grant)"
                    :key="option.id"
                    :label="option.name"
                    :value="option.id" /></el-select
                ><el-select v-model="grant.action" aria-label="共享动作"
                  ><el-option label="查看" value="read" /><el-option
                    label="执行"
                    value="execute" /><el-option label="管理" value="manage" /></el-select
                ><el-button @click="resource.grants.splice(index, 1)">移除</el-button>
              </div>
              <el-button @click="addGrant">增加共享目标</el-button>
            </div></el-form-item
          ><el-alert
            title="共享仍要求目标主体持有相应动作权限。会话历史按自身所有权授权，不参与资源共享。"
            type="info"
            :closable="false"
          /><el-button
            type="primary"
            :loading="saving"
            :disabled="!resource.owner_member_id || !resourceDirty"
            @click="saveResource"
            >保存授权</el-button
          ></el-form
        >
      </el-tab-pane>
    </el-tabs>
    <el-dialog
      :model-value="editor !== ''"
      :title="dialogTitle"
      width="min(680px, calc(100vw - 32px))"
      class="access-editor"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :before-close="close"
      @update:model-value="
        (value) => {
          if (!value) editor = ''
        }
      "
    >
      <FeedbackNotice v-if="error" :title="error" type="error" :closable="false" />
      <el-form label-width="110px" :disabled="saving || !editorWritable" @submit.prevent="submit">
        <template v-if="editor === 'department'"
          ><el-form-item label="部门编码" required
            ><el-input
              v-model="departmentForm.code"
              :disabled="!!departmentForm.id"
              maxlength="64"
            />
            <p v-if="departmentForm.id" class="admin-field-help">
              部门编码创建后不可修改。
            </p></el-form-item
          ><el-form-item label="部门名称" required
            ><el-input v-model="departmentForm.name" maxlength="128" /></el-form-item
          ><el-form-item label="上级部门"
            ><el-select
              v-model="departmentForm.parent_id"
              placeholder="无上级部门（根部门）"
              clearable
              @clear="departmentForm.parent_id = null"
              ><el-option
                v-for="department in parentDepartments"
                :key="department.id"
                :label="department.name"
                :value="department.id"
            /></el-select>
            <p class="admin-field-help">不选择时作为根部门。</p></el-form-item
          ><el-form-item v-if="departmentForm.id" label="启用"
            ><el-switch v-model="departmentForm.enabled" /></el-form-item
        ></template>
        <template v-if="editor === 'position'"
          ><el-form-item label="岗位编码" required
            ><el-input v-model="positionForm.code" :disabled="!!positionForm.id" maxlength="64" />
            <p v-if="positionForm.id" class="admin-field-help">
              岗位编码创建后不可修改。
            </p></el-form-item
          ><el-form-item label="岗位名称" required
            ><el-input v-model="positionForm.name" maxlength="128" /></el-form-item
          ><el-form-item v-if="positionForm.id" label="启用"
            ><el-switch v-model="positionForm.enabled" /></el-form-item
        ></template>
        <template v-if="editor === 'member'"
          ><el-form-item label="用户名" required
            ><el-input
              v-model="memberForm.username"
              :disabled="!!memberForm.id"
              maxlength="128" /></el-form-item
          ><el-form-item label="显示名称" required
            ><el-input v-model="memberForm.display_name" maxlength="128" /></el-form-item
          ><el-form-item
            label="邮箱"
            :required="!memberForm.id && memberForm.credential === 'invitation'"
            :error="memberEmailError"
            ><el-input v-model="memberForm.email" type="email" maxlength="255" /></el-form-item
          ><el-form-item v-if="!memberForm.id" label="激活方式"
            ><el-radio-group v-model="memberForm.credential"
              ><el-radio value="invitation">邀请激活</el-radio
              ><el-radio value="password">设置初始密码</el-radio></el-radio-group
            ></el-form-item
          >
          <p
            v-if="!memberForm.id && memberForm.credential === 'invitation'"
            class="admin-field-help"
          >
            保存后在成员列表发送邀请，邮件发往登记邮箱。
          </p>
          <el-form-item
            v-if="!memberForm.id && memberForm.credential === 'password'"
            label="初始密码"
            required
            ><el-input
              v-model="memberForm.password"
              type="password"
              show-password
              autocomplete="new-password"
            /><span>至少 12 个字符，包含字母和数字，最多 72 个 UTF-8 字节</span></el-form-item
          ><el-form-item label="部门"
            ><el-select
              v-model="memberForm.department_id"
              placeholder="不分配部门"
              clearable
              @clear="memberForm.department_id = null"
              ><el-option
                v-for="department in departments.filter((item) => item.enabled)"
                :key="department.id"
                :label="department.name"
                :value="department.id" /></el-select></el-form-item
          ><el-form-item label="岗位"
            ><el-select
              v-model="memberForm.position_id"
              placeholder="不分配岗位"
              clearable
              @clear="memberForm.position_id = null"
              ><el-option
                v-for="position in positions.filter((item) => item.enabled)"
                :key="position.id"
                :label="position.name"
                :value="position.id" /></el-select></el-form-item
          ><el-form-item v-if="memberForm.id" label="状态"
            ><el-select v-model="memberForm.status"
              ><el-option
                label="启用"
                value="active"
                :disabled="
                  ['pending', 'invited'].includes(
                    members.find((item) => item.id === memberForm.id)?.status ?? ''
                  )
                " /><el-option label="停用" value="disabled" /><el-option
                v-if="memberForm.status === 'pending'"
                label="待发送邀请"
                value="pending" /><el-option
                v-if="memberForm.status === 'invited'"
                label="待接受邀请"
                value="invited" /></el-select></el-form-item
        ></template>
        <el-form-item
          v-if="editor === 'member_roles' || (editor === 'member' && !memberForm.id)"
          label="角色"
          required
          ><el-select v-model="memberForm.role_ids" multiple filterable
            ><el-option
              v-for="role in manageableRoles"
              :key="role.id"
              :label="role.name"
              :value="role.id" /></el-select
          ><span>多个角色取权限和部门范围的联合，最后管理员受到保护。</span></el-form-item
        >
        <template v-if="editor === 'role'"
          ><el-form-item label="角色编码" required
            ><el-input v-model="roleForm.code" :disabled="!!roleForm.id" maxlength="64" />
            <p v-if="roleForm.id" class="admin-field-help">
              角色编码创建后不可修改。
            </p></el-form-item
          ><el-form-item label="角色名称" required
            ><el-input v-model="roleForm.name" maxlength="128" /></el-form-item
          ><el-form-item label="数据范围" required
            ><el-select v-model="roleForm.data_scope"
              ><el-option
                v-for="scope in scopes"
                :key="scope.value"
                :label="scope.label"
                :value="scope.value" /></el-select></el-form-item
          ><el-form-item v-if="roleForm.data_scope === 'specified'" label="指定部门" required
            ><el-select v-model="roleForm.department_ids" multiple
              ><el-option
                v-for="department in departments.filter((item) => item.enabled)"
                :key="department.id"
                :label="department.name"
                :value="department.id" /></el-select></el-form-item
          ><el-form-item label="权限动作"
            ><div class="permission-picker">
              <p class="admin-field-help" role="status">
                已选 {{ roleForm.permissions.length }} 项权限；只能授予本人持有的权限。
              </p>
              <el-input v-model="permissionFilter" placeholder="搜索权限动作" clearable />
              <el-checkbox v-model="selectedPermissionsOnly">只看已选</el-checkbox>
              <p v-if="!permissionGroups.length" class="admin-field-help">没有符合条件的权限</p>
              <el-checkbox-group v-model="roleForm.permissions">
                <fieldset
                  v-for="group in permissionGroups"
                  :key="group.code"
                  class="permission-group"
                >
                  <legend :title="group.code">
                    {{ permissionGroupNames[group.code] || '其他权限' }}（{{ group.items.length }}）
                  </legend>
                  <el-checkbox
                    v-for="permission in group.items"
                    :key="permission.code"
                    :value="permission.code"
                    :disabled="!can(permission.code)"
                    :title="permission.code"
                  >
                    {{ permission.name }}
                  </el-checkbox>
                </fieldset>
              </el-checkbox-group>
            </div></el-form-item
          ><el-form-item v-if="roleForm.id" label="启用"
            ><el-switch v-model="roleForm.enabled" /></el-form-item
        ></template>
      </el-form>
      <template #footer
        ><el-button :disabled="saving" @click="close(() => (editor = ''))">取消</el-button
        ><el-button type="primary" :loading="saving" :disabled="!editorWritable" @click="submit"
          >保存</el-button
        ></template
      >
    </el-dialog>
  </AdminPage>
</template>

<style scoped>
.access-read-status {
  color: var(--el-text-color-secondary);
  margin: var(--space-4, 16px) 0;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3, 12px);
  margin-bottom: var(--space-4, 16px);
}
.toolbar .el-input {
  max-width: 320px;
}
.el-pagination {
  margin-top: var(--space-4, 16px);
  flex-wrap: wrap;
  row-gap: 8px;
}
:deep(.access-member-actions .cell) {
  white-space: nowrap;
}
.grants,
.permission-picker {
  width: 100%;
}
.grant-row {
  display: flex;
  gap: var(--space-2, 8px);
  margin-bottom: var(--space-2, 8px);
}
.permission-picker .el-checkbox-group {
  display: flex;
  flex-direction: column;
  max-height: 320px;
  overflow: auto;
  margin-top: var(--space-2, 8px);
}
.permission-group {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 8px 12px;
  margin: 4px 0;
  min-width: 0;
}
.permission-group legend {
  font-weight: 600;
  padding: 0 4px;
}
.permission-group :deep(.el-checkbox) {
  height: auto;
  min-height: 32px;
  white-space: normal;
}
.permission-group :deep(.el-checkbox__label) {
  white-space: normal;
  overflow-wrap: anywhere;
}
small {
  margin-left: var(--space-2, 8px);
  color: var(--el-text-color-secondary);
}
.el-alert {
  margin-bottom: var(--space-4, 16px);
}
</style>

<style>
.access-editor {
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 32px);
  margin-top: 16px;
  margin-bottom: 16px;
}
.access-editor .el-dialog__body {
  min-height: 0;
  overflow: auto;
}
.access-editor .el-dialog__footer {
  flex-shrink: 0;
}
@media (max-width: 760px) {
  .access-page .grant-row {
    flex-wrap: wrap;
  }
}
</style>
