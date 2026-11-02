import type { RequestOptions } from '../../utils/http'

/** 宿主注入既有客户端；认证、刷新和错误信封解析由宿主统一负责。 */
export type FoundationRequest = <T>(
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
  retry?: boolean,
  options?: RequestOptions
) => Promise<T>
/** 受控文件投影，ID 与摘要保持字符串。 */
export interface FoundationFile {
  id: string
  name: string
  mime_type: string
  size_bytes: number
  sha256: string
  reference_count: number
  created_at: string
}
/** 普通管理列表，页号从一开始。 */
export interface FoundationPage<T> {
  items: T[]
  total: number
  page: number
  page_size: number
}
/** 注册处理器的可执行动作由服务器实时授权计算。 */
export interface TransferHandler {
  code: string
  title: string
  columns: string[]
  option_fields: TransferOptionField[]
  can_import: boolean
  can_export: boolean
}
/** 所属领域声明的任务表单字段，不能由客户端新增执行参数。 */
export interface TransferOptionField {
  code: string
  label: string
  required: boolean
  hint: string
}
/** 任务状态反映受理与执行结果，取消请求并不代表已撤销提交行。 */
export interface FoundationJob {
  id: string
  handler: string
  operation: 'import' | 'export'
  state: string
  total_rows: number
  completed_rows: number
  error_rows: number
  error_message: string | null
  result_file_id: string | null
  created_at: string
  expires_at: string | null
}
/** CSV 错误不包含原始单元格和秘密。 */
export interface TransferRowError {
  row_number: number
  field: string
  message: string
}
/** 无副作用的完整导入预检结果。 */
export interface TransferPreflight {
  row_count: number
  valid: boolean
  errors: TransferRowError[]
}
/** 创建固定处理器的任务，不接受租户、账号或执行代码。 */
export interface TransferSubmission {
  handler: string
  operation: 'import' | 'export'
  file_id?: string
  options: Record<string, string>
  idempotency_key: string
}

/** 受控 blob 下载，浏览器对象 URL 由本次下载释放。 */
export function saveFoundationBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
