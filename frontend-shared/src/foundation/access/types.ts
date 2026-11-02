/** 双宿主注入已统一认证、错误信封处理的请求函数，路径由宿主补全机构/平台前缀。 */
export type AccessRequest = <T>(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  data?: unknown
) => Promise<T>
/** 部门节点，父节点为空表示根。 */
export interface Department {
  id: string
  code: string
  name: string
  parent_id: string | null
  enabled: boolean
  lock_version: number
}
/** 同账号体系岗位。 */
export interface Position {
  id: string
  code: string
  name: string
  enabled: boolean
  lock_version: number
}
/** 当前目录中真实注册的动作。 */
export interface Permission {
  code: string
  name: string
  audience: 'PLATFORM' | 'TENANT' | 'BOTH'
}
/** 多角色权限与部门范围定义，内置角色不可变。 */
export interface Role {
  id: string
  code: string
  name: string
  data_scope: 'self' | 'department' | 'descendants' | 'specified' | 'all'
  built_in: boolean
  administrator: boolean
  enabled: boolean
  permissions: string[]
  department_ids: string[]
  lock_version: number
}
/** 成员资料，不包含密码或安全材料。 */
export interface Member {
  id: string
  username: string
  display_name: string
  email: string | null
  status: string
  department_id: string | null
  position_id: string | null
  role_ids: string[]
  roles: string[]
  lock_version: number
  authorization_version: number
  created_at: string
}
/** 范围过滤后的真实成员页。 */
export interface MemberPage {
  items: Member[]
  total: number
  page: number
  page_size: number
}
/** 私有资源明确共享动作，不扩张主体动作权限。 */
export interface Grant {
  id?: string
  grantee_type: 'member' | 'role' | 'department'
  grantee_id: string
  action: 'read' | 'execute' | 'manage'
}
/** 私有资源授权版本，未登记时所有者为空且版本0。 */
export interface ResourceAuthorization {
  kind: 'agent' | 'workflow' | 'knowledge'
  resource_id: string
  owner_member_id: string | null
  department_id: string | null
  grants: Grant[]
  lock_version: number
}
