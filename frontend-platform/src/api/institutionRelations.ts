import type { RequestOptions } from '@skillforge/shared/utils/http'

/** 当前管理范围可见的机构关系；机构ID不经JavaScript数值转换。 */
export interface InstitutionRelation {
  tenant_id: string
  parent_tenant_id: string | null
  name: string
  status: string
  revision: number
  tree_revision: number
}

/** 关系页同时携带树修订，跨页变化时不能拼接成同一棵树。 */
export interface InstitutionRelationPage {
  items: InstitutionRelation[]
  total: number
  page: number
  page_size: number
  tree_revision: number
}

/** 迁移保留机构身份，只改变精确所见修订上的父关系。 */
export interface InstitutionRelationChange {
  child_tenant_id: string
  parent_tenant_id: string | null
  expected_revision: number
}
export type RelationOperation = 'attach' | 'move' | 'detach'

/** 平台人员JWT入口；明确意图键由消费者保留，未知结果只沿原键核对。 */
export function createInstitutionRelationsApi(http: {
  get: <T>(path: string, options?: RequestOptions) => Promise<T>
  post: <T>(path: string, input: unknown, options?: RequestOptions) => Promise<T>
}) {
  return {
    list: (page: number, options?: RequestOptions) =>
      http.get<InstitutionRelationPage>(
        `/platform/institution-relations?page=${page}&page_size=200`,
        options
      ),
    change: (operation: RelationOperation, input: InstitutionRelationChange, key: string) =>
      http.post<InstitutionRelation>(`/platform/institution-relations/${operation}`, input, {
        headers: { 'Idempotency-Key': key },
        silent: true
      })
  }
}
