/** 宿主注入已认证 HTTP 客户端；路径相对 foundation/catalog，响应已解包 ApiResponse。 */
export type CatalogRequest = <T>(
  method: 'GET' | 'PUT' | 'DELETE',
  path: string,
  data?: unknown
) => Promise<T>
/** 稳定字典定义。 */
export interface Dictionary {
  code: string
  name: string
  enabled: boolean
  revision: number
}
/** 稳定编码的字典值，停用保留历史引用。 */
export interface DictionaryValue {
  dictionary_code: string
  code: string
  label: string
  sort_order: number
  enabled: boolean
  revision: number
}
/** 可配置菜单节点；仅引用受控 route_key。 */
export interface NavigationNode {
  code: string
  parent_code: string | null
  title: string
  route_key: string | null
  sort_order: number
  enabled: boolean
  revision: number
}
/** 代码拥有的可选路由；路径和权限无法在此页面自由编辑。 */
export interface ControlledRoute {
  key: string
  title: string
  path: string
  permission: string
  platform: boolean
}
/** UI 品牌配置，实时生效且不进入执行冻结配置。 */
export interface Branding {
  display_name: string
  logo_url: string | null
  primary_color: string
  contact_email: string | null
  support_url: string | null
  revision: number
}
/** 本人展示偏好，账号不由请求提供。 */
export interface PersonalPreference {
  theme: string
  locale: string
  page_size: number
  compact_navigation: boolean
  revision: number
}
/** 稳定功能目录。 */
export interface Feature {
  code: string
  name: string
  description: string
  enabled: boolean
  revision: number
}
/** 现有套餐查询投影。 */
export interface Plan {
  id: string
  code: string
  name: string
  enabled: boolean
  revision: number
}
/** 明确功能关系，无关系时继承/拒绝由服务端解释。 */
export interface FeatureGrant {
  feature_code: string
  enabled: boolean
  revision: number
}
/** typed 数量配额，null 表示不限或在覆盖场景继承；字节使用精确字符串。 */
export interface Limits {
  agents: number | null
  workflows: number | null
  knowledge_bases: number | null
  members: number | null
  channels: number | null
  file_bytes: string | null
  admin_tasks: number | null
  /** 当前直属子机构数，套餐null不限、机构覆盖null继承；0禁止新增。 */
  sub_institutions: number | null
}
/** 限额与对应CAS修订一起读取，不能用套餐目录的另一时刻修订保存。 */
export interface PlanQuota {
  plan_id: string
  limits: Limits
  revision: number
}
/** 实时有效授权与真实用量。 */
export interface TenantEntitlements {
  tenant_id: string
  limits: Limits
  overrides: Limits
  revision: number
  usage: { kind: string; limit: string | null; used: string }[]
  features: string[]
}
