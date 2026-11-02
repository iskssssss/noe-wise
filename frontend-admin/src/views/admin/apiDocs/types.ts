/** 接口说明展示模型；接口事实由对应 Controller、DTO 与认证拦截器核对。 */
export interface DocField {
  name: string
  type: string
  required?: boolean
  description: string
}

export interface DocParameter extends DocField {
  location: 'query' | 'header'
}

export interface ApiDocEndpoint {
  id: string
  title: string
  group: string
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  path: string
  auth: 'channel' | 'service' | 'openapi' | 'callback' | 'management'
  summary: string
  parameters?: DocParameter[]
  body?: DocField[]
  requestExample?: Record<string, unknown>
  requestFile?: string
  /** 固定请求头，例如 SSE 协商所需的 Accept。 */
  requestHeaders?: Record<string, string>
  queryExample?: Record<string, string | number>
  responseType: string
  /** JSON 默认显示 ApiResponse 信封；SSE 与二进制正文保持其原始协议形状。 */
  responseKind?: 'json' | 'sse' | 'binary'
  responseExample?: unknown
  notes?: string[]
  scopes?: string[]
}
