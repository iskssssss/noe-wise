import type { InstitutionSection } from '@skillforge/shared/institution/workspace'

export const platformInstitutionSections: InstitutionSection[] = [
  { key: 'overview', label: '概览' },
  { key: 'settings', label: '机构设置' },
  { key: 'access', label: '接入与凭证' },
  { key: 'agents', label: '智能体' },
  { key: 'relations', label: '下级机构' },
  { key: 'usage', label: '用量' }
]

/** 缺省定位概览；明确未知分区交由页面提示，不能悄然回退到其他维护内容。 */
export function institutionSection(value: unknown): string {
  return value === undefined ? 'overview' : typeof value === 'string' ? value : 'invalid'
}
