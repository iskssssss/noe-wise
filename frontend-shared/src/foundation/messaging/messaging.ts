import type { RequestOptions } from '../../utils/http'

/** 宿主注入已认证的现有客户端，消息页不读取或保存令牌。 */
export type MessagingRequest = <T>(
  method: 'GET' | 'POST' | 'PUT',
  path: string,
  body?: unknown,
  options?: RequestOptions
) => Promise<T>
/** 服务端真实游标分页；ID保持字符串。 */
export interface MessagingPageResult<T> {
  items: T[]
  has_more: boolean
  next_cursor: string | null
}
/** 本人站内消息；邮件-only安全正文不进入此结构。 */
export interface InboxItem {
  id: string
  title: string
  body: string
  read_at: string | null
  created_at: string
}
/** 不可变公告正文和冻结收件人数。 */
export interface AnnouncementItem {
  id: string
  title: string
  body: string
  audience: string
  recipients: number
  status: 'published' | 'withdrawn'
  created_at: string
}
/** 投递状态明确区分SMTP接受和最终送达。 */
export interface DeliveryItem {
  id: string
  notification_id: string
  status: string
  error_code: string | null
  attempts: number
  updated_at: string
}
/** 邮箱安全投影，无密码或密文字段。 */
export interface MailboxView {
  configured: boolean
  enabled: boolean
  host: string | null
  port: number | null
  username: string | null
  from_address: string | null
  tls_mode: 'starttls' | 'tls' | null
  credential_configured: boolean
}
/** 当前不可变模板版本。 */
export interface TemplateView {
  id: string
  key: string
  subject: string
  body: string
  variable_names: string[]
  created_at: string
}
/** 真实服务端状态映射，unknown没有自动重试入口。 */
export const deliveryLabels: Record<string, string> = {
  pending: '待处理',
  leased: '已领取',
  dispatching: '正在派发',
  accepted: 'SMTP 已接受',
  rejected: '明确未接受',
  unknown: '结果未知，待核验',
  blocked: '暂不可投递',
  cancelled: '已取消'
}
