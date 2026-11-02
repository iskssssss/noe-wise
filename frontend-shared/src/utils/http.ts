/**
 * 请求层共享核心 —— createHttpClient 配置注入工厂。
 *
 * <p>两个前端项目（admin 机构端 / platform 平台端）的 fetch 封装此前逐字节重复，
 * 差异仅在：BASE、token 键名、鉴权头、刷新端点、401 刷新跳过前缀。
 * 本模块把差异全部收敛为 {@link HttpClientConfig} 注入，拦截逻辑只写一份。
 *
 * <p>统一行为（规范 §3）：
 * <ul>
 *   <li>401 且非鉴权路径 → 单飞刷新重试一次；刷新失败 clearTokens + onUnauthorized（各端跳登录）；</li>
 *   <li>非 2xx 保留类型化错误；页面已承接反馈时不重复弹出全局提示，其余由 onError 兜底；</li>
 *   <li>AbortSignal 透传（请求竞态取消），AbortError 静默上抛不触发 onError；</li>
 *   <li>服务端错误不会直接作为用户主提示；安全主文案与可展开技术详情分离；204 → undefined；FormData 直传。</li>
 * </ul>
 */
import { commonZhCN } from '../i18n/common.zh-CN'

/** Token 载荷（各端 AuthTokens 的公共子集；不设 index signature，保持字段边界封闭）。 */
export interface TokenPayload {
  access_token: string
  refresh_token?: string
}

/** 普通业务 JSON 的唯一响应信封。二进制、204 与 OpenAPI 原始文档不使用此模型。 */
export interface ApiResponse<T> {
  code: 'OK' | string
  message: string
  data: T | null
  trace_id: string
  retryable?: boolean
  field_errors?: Record<string, string>
}

/**
 * 非成功 HTTP 响应的安全错误模型。
 *
 * `message` 始终等于可直接展示的 `userMessage`，调用方读取标准 Error 文案时
 * 不会泄露服务端实现细节。诊断内容只能放入反馈的 details。
 */
export class HttpClientError extends Error {
  private feedbackClaimed = false
  /** 页面已有错误区或专用恢复面板时接管反馈，不改变异常与请求结果。 */
  claimFeedback() {
    this.feedbackClaimed = true
  }
  get hasFeedbackOwner() {
    return this.feedbackClaimed
  }
  readonly status: number
  readonly code?: string
  readonly retryable: boolean
  readonly userMessage: string
  readonly technicalDetails?: string
  readonly traceId?: string
  readonly fieldErrors: Record<string, string>

  constructor({
    status,
    code,
    retryable = false,
    userMessage,
    technicalDetails,
    traceId,
    fieldErrors = {}
  }: {
    status: number
    code?: string
    retryable?: boolean
    userMessage: string
    technicalDetails?: string
    traceId?: string
    fieldErrors?: Record<string, string>
  }) {
    super(userMessage)
    this.name = 'HttpClientError'
    this.status = status
    this.code = code
    this.retryable = retryable
    this.userMessage = userMessage
    this.technicalDetails = technicalDetails
    this.traceId = traceId
    this.fieldErrors = fieldErrors
  }
}

/** 客户端配置（各端差异点全部注入，禁止在共享层硬编码任何项目值）。 */
export interface HttpClientConfig {
  /** API 前缀，两项目均为 "/api/v1"。 */
  base: string
  /** access_token 的 localStorage 键。 */
  accessTokenKey: string
  /** refresh_token 的 localStorage 键。 */
  refreshTokenKey: string
  /** 鉴权头构造（admin: Authorization / platform: X-Platform-Token）。 */
  authHeader: (token: string) => Record<string, string>
  /** token 刷新端点（admin: /auth/refresh / platform: /platform/auth/refresh）。 */
  refreshPath: string
  /** 401 跳过刷新重试的路径判定（各端鉴权接口自身前缀）。 */
  isAuthPath: (path: string) => boolean
  /** 刷新失败回调：clearTokens + 跳登录（由各端注入，避免共享层依赖路由）。 */
  onUnauthorized?: () => void
  /**
   * 页面未接管的失败反馈兜底；下一任务核对归属与身份后调用，取消不提示。
   * 第四个参数携带技术详情和 traceId，供反馈组件展示。
   */
  onError?: (status: number, userMessage: string, path: string, error: HttpClientError) => void
}

/** 请求附加选项。 */
export interface RequestOptions {
  /** 登录等公开入口不携带上一个身份的令牌，也不触发刷新。 */
  anonymous?: boolean
  /** 竞态取消信号（切换分页/搜索时 abort 上一次请求）。 */
  signal?: AbortSignal
  /** 额外请求头；认证头由客户端统一追加，调用方不得覆盖认证逻辑。 */
  headers?: HeadersInit
  /** 调用方自行承接或探测性请求不展示全局提示，异常仍照常返回。 */
  silent?: boolean
  /** 设为 false 时保留原始 401 响应，不刷新令牌或自动重发该请求。 */
  retryOnUnauthorized?: boolean
  /** 下载选择 blob，长连接选择 stream 返回原 Response；缺省按 JSON 信封解析。 */
  responseType?: 'json' | 'blob' | 'stream'
}

/** 请求方法集合。 */
type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

/** 是否为中止错误（用户主动取消，非业务失败）。 */
function isAbortError(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError'
}

function userMessageForStatus(status: number, method?: HttpMethod): string {
  if (status === 400) return commonZhCN.http.badRequest
  if (status === 401) return commonZhCN.http.sessionExpired
  if (status === 403) return commonZhCN.http.forbidden
  if (status === 404) return commonZhCN.http.notFound
  if (status === 409) return commonZhCN.http.conflict
  if (status === 422) return commonZhCN.http.validationFailed
  if (status === 429) return commonZhCN.http.rateLimited
  return method === 'GET'
    ? commonZhCN.http.serviceUnavailableRead
    : commonZhCN.http.serviceUnavailable
}

function readableField(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const normalized = value.trim()
  return normalized ? normalized.slice(0, 2000) : undefined
}

function isJsonObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function safeFieldErrors(value: unknown): Record<string, string> {
  if (!isJsonObject(value)) return {}
  return Object.fromEntries(
    Object.entries(value)
      .filter(
        (entry): entry is [string, string] =>
          /^[a-z][a-z0-9_]*$/.test(entry[0]) && typeof entry[1] === 'string'
      )
      .map(([key, item]) => [key, item.slice(0, 500)])
  )
}

/** 将普通 JSON 错误信封转为安全的客户端错误，供 SSE 握手和常规请求共用。 */
export async function readApiResponseError(
  res: Response,
  method?: HttpMethod
): Promise<HttpClientError> {
  const decoded: unknown = await res.json().catch(() => undefined)
  const body = isJsonObject(decoded) ? decoded : {}
  const technicalDetails = readableField(body.message) ?? readableField(res.statusText)
  const traceId =
    readableField(body.trace_id) ??
    readableField(res.headers.get('X-Trace-Id')) ??
    readableField(res.headers.get('X-Request-Id'))
  return new HttpClientError({
    status: res.status,
    code: readableField(body.code),
    retryable: body.retryable === true,
    userMessage: userMessageForStatus(res.status, method),
    technicalDetails,
    traceId,
    fieldErrors: safeFieldErrors(body.field_errors)
  })
}

function toNetworkError(reason: unknown, method: HttpMethod): HttpClientError {
  return new HttpClientError({
    status: 0,
    userMessage: method === 'GET' ? commonZhCN.http.networkRead : commonZhCN.http.network,
    technicalDetails: readableField(reason instanceof Error ? reason.message : String(reason))
  })
}

async function unwrapApiResponse<T>(res: Response, method?: HttpMethod): Promise<T> {
  const decoded: unknown = await res.json().catch(() => undefined)
  if (
    !isJsonObject(decoded) ||
    typeof decoded.code !== 'string' ||
    typeof decoded.message !== 'string' ||
    !('data' in decoded) ||
    !readableField(decoded.trace_id)
  ) {
    throw new HttpClientError({
      status: res.status,
      userMessage: commonZhCN.http.invalidResponse,
      technicalDetails: '响应未符合 ApiResponse 契约。'
    })
  }
  const envelope = decoded as unknown as ApiResponse<T>
  if (envelope.code !== 'OK') {
    throw new HttpClientError({
      status: res.status,
      code: envelope.code,
      retryable: envelope.retryable === true,
      userMessage: userMessageForStatus(res.status, method),
      technicalDetails: readableField(envelope.message),
      traceId: readableField(envelope.trace_id),
      fieldErrors: safeFieldErrors(envelope.field_errors)
    })
  }
  return envelope.data as T
}

/**
 * 创建请求客户端。
 *
 * @param config 各端差异配置
 * @returns 请求函数与 token 管理工具
 */
export function createHttpClient(config: HttpClientConfig) {
  const { base, accessTokenKey, refreshTokenKey, authHeader } = config
  let identityVersion = 0
  function notifyError(
    error: HttpClientError,
    path: string,
    options: RequestOptions,
    version: number,
    sessionId: string | null
  ) {
    if (options.silent || !config.onError) return
    // 等待当前 Promise 链交给页面错误区；不让兜底提示抢在实际恢复方案前出现。
    setTimeout(() => {
      if (
        !error.hasFeedbackOwner &&
        !options.signal?.aborted &&
        version === identityVersion &&
        sessionId === getSessionId()
      )
        config.onError?.(error.status, error.userMessage, path, error)
    }, 0)
  }
  // 登录会话跨标签共享，普通令牌刷新不会改变身份或销毁编辑页面。
  const authSessionKey = `${accessTokenKey}.session`
  function getSessionId(): string | null {
    return localStorage.getItem(authSessionKey)
  }
  const refreshedTokens = new Set<string>()
  let refreshedCurrentToken: string | null = null

  function getAccessToken(): string | null {
    return localStorage.getItem(accessTokenKey)
  }

  function saveTokens(tokens: TokenPayload): void {
    identityVersion++
    refreshedTokens.clear()
    refreshedCurrentToken = null
    refreshPromise = null
    localStorage.setItem(authSessionKey, crypto.randomUUID())
    localStorage.removeItem(refreshTokenKey)
    writeTokens(tokens)
  }

  function writeTokens(tokens: TokenPayload): void {
    localStorage.setItem(accessTokenKey, tokens.access_token)
    if (tokens.refresh_token) localStorage.setItem(refreshTokenKey, tokens.refresh_token)
  }

  function clearTokens(): void {
    identityVersion++
    refreshedTokens.clear()
    refreshedCurrentToken = null
    refreshPromise = null
    localStorage.removeItem(accessTokenKey)
    localStorage.removeItem(refreshTokenKey)
    localStorage.removeItem(authSessionKey)
  }

  function authHeaders(extra: HeadersInit = {}): Headers {
    const headers = new Headers(extra)
    const token = getAccessToken()
    if (token) Object.entries(authHeader(token)).forEach(([k, v]) => headers.set(k, v))
    return headers
  }

  let refreshPromise: Promise<string | null> | null = null

  async function refreshAccessToken(): Promise<string | null> {
    const version = identityVersion
    const sessionId = getSessionId()
    const refreshToken = localStorage.getItem(refreshTokenKey)
    const accessToken = getAccessToken()
    if (!refreshToken) return null
    if (!refreshPromise) {
      const pending = fetch(`${base}${config.refreshPath}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refresh_token: refreshToken })
      })
        .then(async (res) => {
          if (!res.ok) throw new Error(commonZhCN.http.sessionExpired)
          const data = await unwrapApiResponse<TokenPayload>(res)
          // 刷新期间退出或换号后，迟到响应不得重新写入旧身份。
          if (
            version !== identityVersion ||
            sessionId !== getSessionId() ||
            refreshToken !== localStorage.getItem(refreshTokenKey) ||
            accessToken !== getAccessToken()
          )
            return null
          // 只承认本登录会话内成功刷新的前驱，旧账号请求不能改用新账号凭据。
          if (accessToken) refreshedTokens.add(accessToken)
          writeTokens(data)
          refreshedCurrentToken = data.access_token
          return data.access_token
        })
        .catch(() => {
          if (
            version === identityVersion &&
            sessionId === getSessionId() &&
            refreshToken === localStorage.getItem(refreshTokenKey) &&
            accessToken === getAccessToken()
          )
            clearTokens()
          return null
        })
        .finally(() => {
          if (refreshPromise === pending) refreshPromise = null
        })
      refreshPromise = pending
    }
    return refreshPromise
  }

  async function request<T>(
    method: HttpMethod,
    path: string,
    body?: unknown,
    retried = false,
    opts: RequestOptions = {}
  ): Promise<T> {
    const version = identityVersion
    const sessionId = getSessionId()
    const requestToken = getAccessToken()
    const headers = opts.anonymous ? new Headers(opts.headers) : authHeaders(opts.headers)
    let requestBody: BodyInit | undefined
    if (body instanceof FormData) requestBody = body
    else if (body !== undefined && method !== 'GET') {
      headers.set('Content-Type', 'application/json')
      requestBody = JSON.stringify(body)
    }

    let res: Response
    try {
      res = await fetch(`${base}${path}`, {
        method,
        headers,
        body: requestBody,
        signal: opts.signal
      })
    } catch (e) {
      if (isAbortError(e)) throw e // 竞态取消：静默上抛，由调用方（usePagedList 等）吞掉
      const error = toNetworkError(e, method)
      notifyError(error, path, opts, version, sessionId)
      throw error
    }

    if (
      res.status === 401 &&
      opts.retryOnUnauthorized !== false &&
      !opts.anonymous &&
      !retried &&
      !config.isAuthPath(path)
    ) {
      if (version !== identityVersion || sessionId !== getSessionId())
        throw new HttpClientError({ status: 401, userMessage: userMessageForStatus(401) })
      if (requestToken !== getAccessToken()) {
        if (
          requestToken &&
          refreshedTokens.has(requestToken) &&
          refreshedCurrentToken === getAccessToken()
        )
          return request<T>(method, path, body, true, opts)
        throw new HttpClientError({ status: 401, userMessage: userMessageForStatus(401) })
      }
      const token = await refreshAccessToken()
      if (
        token &&
        version === identityVersion &&
        sessionId === getSessionId() &&
        token === getAccessToken()
      )
        return request<T>(method, path, body, true, opts)
      if (getAccessToken())
        throw new HttpClientError({ status: 401, userMessage: userMessageForStatus(401) })
      config.onUnauthorized?.() // 刷新失败：各端清 token 并跳登录，不重复 toast
      throw new HttpClientError({ status: 401, userMessage: userMessageForStatus(401) })
    }

    if (!res.ok) {
      const error = await readApiResponseError(res, method)
      notifyError(error, path, opts, version, sessionId)
      throw error
    }
    if (opts.responseType === 'stream') return res as T
    if (res.status === 204) return undefined as T
    if (opts.responseType === 'blob') return res.blob() as Promise<T>
    try {
      return await unwrapApiResponse<T>(res, method)
    } catch (error) {
      if (error instanceof HttpClientError) notifyError(error, path, opts, version, sessionId)
      throw error
    }
  }

  function get<T>(path: string, opts?: RequestOptions): Promise<T> {
    return request<T>('GET', path, undefined, false, opts)
  }
  function post<T>(path: string, body?: unknown, opts?: RequestOptions): Promise<T> {
    return request<T>('POST', path, body, false, opts)
  }
  function put<T>(path: string, body?: unknown, opts?: RequestOptions): Promise<T> {
    return request<T>('PUT', path, body, false, opts)
  }
  function patch<T>(path: string, body?: unknown, opts?: RequestOptions): Promise<T> {
    return request<T>('PATCH', path, body, false, opts)
  }
  function del<T>(path: string, body?: unknown, opts?: RequestOptions): Promise<T> {
    return request<T>('DELETE', path, body, false, opts)
  }

  return {
    base,
    get,
    post,
    put,
    patch,
    del,
    request,
    getAccessToken,
    getSessionId,
    authSessionKey,
    saveTokens,
    clearTokens,
    authHeaders,
    refreshAccessToken
  }
}

/** 客户端类型（createHttpClient 返回值的结构化类型）。 */
export type HttpClient = ReturnType<typeof createHttpClient>
