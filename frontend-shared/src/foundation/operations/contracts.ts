import type { RequestOptions } from '../../utils/http'

/** 双宿主注入认证客户端；页面不读取令牌，API信封由宿主解包。 */
export type OperationsRequest = <T>(
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
  retry?: boolean,
  options?: RequestOptions
) => Promise<T>
/** 安全审计投影，机构与ID按字符串表示，秘密元数据已脱敏。 */
export interface AuditEvent {
  id: string
  tenant_id: string | null
  actor_id: string | null
  action: string
  category: string
  outcome: string
  resource_type: string | null
  resource_id: string | null
  message: string | null
  trace_id: string | null
  payload: Record<string, unknown>
  created_at: string
}
/** 安全审计目录只读九字段摘要；原 AuditEvent 保留完整审计读取契约。 */
export interface AuditSummary {
  id: string
  actor_id: string | null
  action: string
  category: string
  outcome: string
  resource_type: string | null
  message: string | null
  trace_id: string | null
  created_at: string
}
export interface AuditSummaryPage {
  items: AuditSummary[]
  total: number
  page: number
  page_size: number
}
/** 从1开始的审计分页。 */
export interface AuditPage {
  items: AuditEvent[]
  total: number
  page: number
  page_size: number
}
/** 当前生效保留策略，version用于完整替换CAS。 */
export interface RetentionPolicy {
  resource: string
  retention_days: number
  enabled: boolean
  version: number
}
/** 冻结清理预览与已提交执行回执，失败不能显示成成功。 */
export interface CleanupRun {
  id: string
  resource: string
  cutoff: string
  candidates: number
  protected_count: number
  deleted_count: number
  status: 'ready' | 'succeeded' | 'failed' | 'expired'
  expires_at: string
  created_at: string
  completed_at: string | null
  failure_code: string | null
}
/** 调查引用保护与显式解除时间。 */
export interface AuditHold {
  id: string
  audit_id: string
  reason: string
  expires_at: string | null
  released_at: string | null
}
/** 实际依赖探测；未配置与故障分别为unavailable/down。 */
export interface Probe {
  name: string
  status: string
  duration_millis: number
  observed_at: string
  detail: string
}
/** 当前宿主JVM与依赖采样，不推定业务验收通过。 */
export interface RuntimeStatus {
  observed_at: string
  uptime_millis: number
  heap_used_bytes: number
  heap_max_bytes: number
  threads: number
  database_pool_active: number | null
  database_pool_idle: number | null
  probes: Probe[]
}

/** 获取受控附件后保存文件，并释放当前页面持有的对象URL。 */
export function saveAuditCsv(blob: Blob) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'security-audit.csv'
  anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
