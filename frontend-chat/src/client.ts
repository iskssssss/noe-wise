import type { Credentials, StreamEvent } from './protocol'

export class RequestError extends Error {
  constructor(
    message: string,
    public status = 0,
    public code = '',
    public traceId = ''
  ) {
    super(message)
  }
}
class StreamError extends Error {
  constructor(
    message: string,
    public code: string,
    public retryable: boolean
  ) {
    super(message)
  }
}
export function serviceBase(value: string) {
  const url = new URL(value.trim() || location.origin)
  if (
    !['https:', 'http:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash
  )
    throw new Error('服务地址必须是 HTTP 或 HTTPS 地址，不能包含账号或查询参数。')
  return url.href.replace(/\/$/, '').replace(/\/api\/v1$/, '')
}
const hex = (data: ArrayBuffer) =>
  Array.from(new Uint8Array(data), (byte) => byte.toString(16).padStart(2, '0')).join('')
export async function sign(
  credentials: Credentials,
  method: string,
  url: URL,
  wire: string,
  now = Date.now()
) {
  const headers = new Headers()
  if (credentials.mode === 'token') headers.set('X-Channel-Token', credentials.token)
  else {
    if (!crypto.subtle) throw new Error('签名认证需要 HTTPS 或 localhost 安全环境。')
    const encoder = new TextEncoder(),
      timestamp = String(Math.floor(now / 1000))
    const digest = hex(await crypto.subtle.digest('SHA-256', encoder.encode(wire)))
    const key = await crypto.subtle.importKey(
      'raw',
      encoder.encode(credentials.secret),
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign']
    )
    headers.set('X-Api-Key', credentials.apiKey)
    headers.set('X-Timestamp', timestamp)
    headers.set(
      'X-Signature',
      hex(
        await crypto.subtle.sign(
          'HMAC',
          key,
          encoder.encode(`${timestamp}\n${method}\n${url.pathname}${url.search}\n${digest}`)
        )
      )
    )
  }
  return headers
}
export function createClient(
  base: string,
  credentials: Credentials,
  invalid: (code?: string) => void = () => {}
) {
  let identityToken = ''
  async function request(
    path: string,
    method = 'GET',
    body?: unknown,
    signal?: AbortSignal,
    extra?: Record<string, string>
  ) {
    const url = new URL(`${base}/api/v1${path}`),
      multipart = body instanceof FormData
    const wire = body === undefined || multipart ? '' : JSON.stringify(body)
    const headers = await sign(credentials, method, url, wire)
    if (identityToken) headers.set('X-Chat-User-Token', identityToken)
    if (body !== undefined && !multipart) headers.set('Content-Type', 'application/json')
    for (const [key, value] of Object.entries(extra ?? {})) headers.set(key, value)
    const response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : multipart ? body : wire,
      signal,
      credentials: 'omit',
      redirect: 'error',
      cache: 'no-store'
    })
    if (response.status === 401) {
      const failure = await response
        .clone()
        .json()
        .catch(() => null)
      invalid(typeof failure?.code === 'string' ? failure.code : undefined)
    }
    return response
  }
  async function decode<T>(response: Response): Promise<T> {
    const value = await response.json().catch(() => null)
    if (
      !value ||
      typeof value.code !== 'string' ||
      typeof value.message !== 'string' ||
      typeof value.trace_id !== 'string' ||
      !value.trace_id ||
      !('data' in value)
    )
      throw new RequestError(`服务响应格式无效（${response.status}）`, response.status)
    if (!response.ok || value.code !== 'OK')
      throw new RequestError(
        value.message || `请求失败（${response.status}）`,
        response.status,
        value.code,
        value.trace_id
      )
    return value.data as T
  }
  return {
    base,
    setIdentityToken: (value: string) => {
      identityToken = value
    },
    json: async <T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal) =>
      decode<T>(await request(path, method, body, signal)),
    blob: async (path: string, signal?: AbortSignal) => {
      const response = await request(path, 'GET', undefined, signal)
      if (!response.ok) await decode(response)
      return response.blob()
    },
    events: (session: string, cursor: string, signal: AbortSignal) =>
      request(
        `/chat/events?${new URLSearchParams({ session_id: session })}`,
        'GET',
        undefined,
        signal,
        { Accept: 'text/event-stream', ...(cursor ? { 'Last-Event-ID': cursor } : {}) }
      ),
    decode
  }
}
export type Client = ReturnType<typeof createClient>
export const query = (values: Record<string, string | number | undefined>) =>
  new URLSearchParams(
    Object.entries(values)
      .filter((entry): entry is [string, string | number] => entry[1] !== undefined)
      .map(([key, value]) => [key, String(value)])
  ).toString()

/** 分块只改变传输边界，不能改变事件边界或丢弃中文的 UTF-8 字节。 */
export async function readEvents(
  response: Response,
  receive: (event: StreamEvent) => void,
  heartbeat: () => void = () => {}
) {
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream'))
    throw new RequestError('事件订阅未返回有效事件流。', response.status)
  const reader = response.body.getReader(),
    decoder = new TextDecoder()
  let buffer = '',
    id = '',
    event = '',
    data: string[] = []
  const line = (value: string) => {
    if (!value) {
      if (data.length) {
        const parsed: unknown = JSON.parse(data.join('\n'))
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
          throw new Error('事件数据无效')
        receive({ id, event: event || 'message', data: parsed as Record<string, unknown> })
      }
      id = ''
      event = ''
      data = []
      return
    }
    if (value.startsWith(':')) {
      heartbeat()
      return
    }
    const colon = value.indexOf(':'),
      key = colon < 0 ? value : value.slice(0, colon),
      raw = colon < 0 ? '' : value.slice(colon + 1),
      text = raw.startsWith(' ') ? raw.slice(1) : raw
    if (key === 'id' && !text.includes('\0')) id = text
    else if (key === 'event') event = text
    else if (key === 'data') data.push(text)
  }
  try {
    while (true) {
      const chunk = await reader.read()
      buffer += decoder.decode(chunk.value, { stream: !chunk.done })
      let index: number
      while ((index = buffer.indexOf('\n')) >= 0) {
        line(buffer.slice(0, index).replace(/\r$/, ''))
        buffer = buffer.slice(index + 1)
      }
      if (buffer.length > 2_000_000) throw new Error('事件内容超过安全读取上限。')
      if (chunk.done) break
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
/** 可取消的只读恢复等待；取消后立即清理计时器与监听。 */
export const delay = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    if (signal.aborted) {
      reject(signal.reason)
      return
    }
    const done = () => {
      signal.removeEventListener('abort', abort)
      resolve()
    }
    const timer = setTimeout(done, ms)
    const abort = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', abort)
      reject(signal.reason)
    }
    signal.addEventListener('abort', abort, { once: true })
  })
/** 只有只读订阅会自动重连；提交失败绝不自动重发业务输入。 */
export async function observe(
  client: Client,
  session: string,
  startCursor: string,
  signal: AbortSignal,
  receive: (event: StreamEvent) => void,
  status: (value: string) => void,
  recoverCursor: () => Promise<string>
) {
  let cursor = startCursor,
    failures = 0,
    needsSnapshot = false
  while (!signal.aborted) {
    let retryAfter = 0
    try {
      status(failures ? '重新连接中' : '连接中')
      if (needsSnapshot) {
        cursor = await recoverCursor()
        if (signal.aborted) return
        needsSnapshot = false
        failures = 0
      }
      const response = await client.events(session, cursor, signal)
      if (signal.aborted) return
      const retry = response.headers.get('retry-after')
      if (retry)
        retryAfter = /^\d+$/.test(retry)
          ? Number(retry) * 1000
          : Math.max(0, Date.parse(retry) - Date.now())
      if (!response.ok) await client.decode(response)
      status('已连接')
      await readEvents(
        response,
        (event) => {
          if (signal.aborted) return
          if (event.event === 'error')
            throw new StreamError(
              typeof event.data.message === 'string' ? event.data.message : '实时事件暂时不可用。',
              typeof event.data.error_code === 'string' ? event.data.error_code : '',
              event.data.retryable !== false
            )
          receive(event)
          if (event.id) cursor = event.id
          failures = 0
        },
        () => {
          failures = 0
        }
      )
      if (signal.aborted) return
    } catch (error) {
      if (signal.aborted) return
      if (error instanceof StreamError && !error.retryable) throw error
      if (
        !needsSnapshot &&
        error instanceof RequestError &&
        error.status === 409 &&
        error.code === 'EVENT_CURSOR_EXPIRED'
      ) {
        needsSnapshot = true
        failures = 0
        continue
      }
      if (
        needsSnapshot &&
        !(error instanceof RequestError && error.status >= 500 && error.status < 600)
      )
        throw error
      if (error instanceof RequestError && [400, 401, 403, 404, 409, 410].includes(error.status))
        throw error
    }
    failures++
    if (failures > 8) throw new Error('实时连接暂时不可用，请点击重新连接恢复观察。')
    status('连接中断，正在恢复')
    await delay(
      Math.min(30_000, Math.max(retryAfter, 500 * 2 ** Math.min(failures, 5))) *
        (0.9 + Math.random() * 0.2),
      signal
    )
  }
}
