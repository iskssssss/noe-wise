function browserPairingAddress(value: string) {
  const url = new URL(value)
  if (
    !['wss:', 'ws:'].includes(url.protocol) ||
    (url.protocol === 'ws:' && !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) ||
    url.username ||
    url.password
  )
    throw new Error('服务返回的浏览器配对地址无效。')
  return url.href
}

export interface BridgeResponse {
  ok: boolean
  code?: string
  version_info?: { adapter_version?: string; upstream_commit?: string }
}

/** 只接受当前页面的同源、同请求公开响应；配对凭据只发送给适配扩展。 */
export function browserBridge(
  operation: 'probe' | 'pair',
  payload: { pairing_url?: string; connection_request_id?: string; identity_scope?: string } = {},
  signal?: AbortSignal
): Promise<BridgeResponse> {
  if (payload.pairing_url)
    payload = { ...payload, pairing_url: browserPairingAddress(payload.pairing_url) }
  const request_id = crypto.randomUUID()
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new DOMException('已停止观察', 'AbortError'))
    const finish = (value?: BridgeResponse, error?: Error) => {
      clearTimeout(timer)
      window.removeEventListener('message', receive)
      signal?.removeEventListener('abort', abort)
      if (error) reject(error)
      else resolve(value!)
    }
    const abort = () => finish(undefined, new DOMException('已停止观察', 'AbortError'))
    const receive = (event: MessageEvent) => {
      const data = event.data
      if (
        event.source !== window ||
        event.origin !== location.origin ||
        !data ||
        data.source !== 'noewise-extension' ||
        data.version !== 1 ||
        data.request_id !== request_id ||
        typeof data.ok !== 'boolean'
      )
        return
      // 不传播扩展意外附带的地址、token 或其他私有字段。
      finish({
        ok: data.ok,
        code: typeof data.code === 'string' ? data.code : undefined,
        version_info: data.version_info && {
          adapter_version:
            typeof data.version_info.adapter_version === 'string'
              ? data.version_info.adapter_version
              : undefined,
          upstream_commit:
            typeof data.version_info.upstream_commit === 'string'
              ? data.version_info.upstream_commit
              : undefined
        }
      })
    }
    const timer = setTimeout(
      () =>
        finish(
          undefined,
          new Error(
            operation === 'probe'
              ? '未检测到适配扩展，请安装扩展并打开插件信任当前站点。'
              : '配对结果尚未确认，请查看插件并读取设备或连接状态，不要重复配对。'
          )
        ),
      operation === 'probe' ? 3000 : 125000
    )
    window.addEventListener('message', receive)
    signal?.addEventListener('abort', abort, { once: true })
    window.postMessage(
      { source: 'noewise-page', version: 1, request_id, operation, payload },
      location.origin
    )
  })
}

export function connectionBridgeMessage(
  code?: string,
  context: 'connection' | 'device' = 'connection'
) {
  if (code === 'site_not_trusted') return '请打开插件，信任当前站点后确认配对。'
  if (code === 'user_rejected')
    return context === 'device'
      ? '你已在插件中拒绝配对，设备授权未改变。'
      : '你已在插件中拒绝配对。可取消本次等待。'
  if (context === 'device') return '配对结果尚未确认，请查看插件并刷新设备列表，不要重复配对。'
  return '配对结果尚未确认，请查看插件。页面将继续读取连接状态，不会重复配对。'
}

/** 对结果未知的写请求保留身份，重挂载也不自动或重复发出。 */
export function connectionAttemptKey(identity: string, request: string, action: string) {
  return `noewise:browser-connection:${JSON.stringify([identity, request, action])}`
}

export type ConnectionSelector =
  { client_ref: string; session_id?: never } | { session_id: string; client_ref?: never }
export interface ConnectionDevices {
  items: {
    device_id: string
    browser_id: string
    label: string
    connected: boolean
    expires_at: string
  }[]
  selected_browser_id?: string
  installation_url: string
}
export interface BrowserConnectionDetail {
  connection_request_id: string
  status: 'WAITING' | 'READY' | 'RESUMING' | 'RESUMED' | 'CANCELLING' | 'CANCELLED' | 'FAILED'
  reason: string
  device_id?: string
  browser_id?: string
  make_default: boolean
  devices: ConnectionDevices
  error?: string
}
export interface ConnectionTransport {
  detail(
    request: string,
    selector: ConnectionSelector,
    signal: AbortSignal
  ): Promise<BrowserConnectionDetail>
  pair(
    body: ConnectionSelector & { connection_request_id: string; make_default: boolean },
    signal: AbortSignal
  ): Promise<{ pairing_url: string }>
  select(
    body: ConnectionSelector & {
      connection_request_id: string
      device_id: string
      make_default: boolean
    },
    signal: AbortSignal
  ): Promise<unknown>
  cancel(
    body: ConnectionSelector & { connection_request_id: string },
    signal: AbortSignal
  ): Promise<unknown>
}
export interface ConnectionState {
  detail?: BrowserConnectionDetail
  error: string
  feedback: string
  busy: boolean
  makeDefault: boolean
  attempted: Partial<Record<'pair' | 'select' | 'cancel', boolean>>
}
export const connectionStatusLabel: Record<BrowserConnectionDetail['status'], string> = {
  WAITING: '等待浏览器连接',
  READY: '连接已就绪，正在自动继续任务',
  RESUMING: '正在自动继续任务',
  RESUMED: '任务已继续',
  CANCELLING: '正在取消连接等待',
  CANCELLED: '已取消连接等待',
  FAILED: '连接未能完成'
}
/** 已知业务原因使用产品文案；未知原因保留服务端已有说明。 */
export function browserConnectionReason(reason?: string) {
  return (
    (
      {
        NO_SELECTION: '尚未选择用于本次任务的浏览器。请选择已有设备，或配对当前浏览器。',
        OFFLINE: '所选浏览器当前离线，需要恢复连接后才能继续。',
        AUTHORIZATION_REQUIRED: '浏览器尚未授权或授权已失效，请重新授权，或选择其他已授权设备。'
      } as Record<string, string>
    )[reason ?? ''] ??
    reason ??
    ''
  )
}

/** 只转换已知错误，不用通用提示吞掉未知 HTTP 错误的既有 message。 */
export function browserConnectionError(message?: string) {
  return (
    (
      {
        browser_connection_response_invalid: '浏览器连接确认无效，请读取最新连接状态。',
        browser_connection_cancelled: '已取消本次浏览器连接等待。',
        browser_connection_access_denied: '当前身份无法继续此连接请求，请检查设备授权。',
        browser_connection_observation_unavailable: '暂时无法读取浏览器连接，页面会继续查询状态。',
        browser_connection_run_ended: '任务已结束，无需继续连接浏览器。',
        browser_connection_wait_changed: '任务等待状态已改变，请查看最新会话进度。',
        browser_connection_not_found: '此浏览器连接请求不存在，请查看最新会话进度。',
        browser_connection_not_owned: '此连接请求不属于当前身份，请切换回发起请求的身份。',
        browser_connection_source_unavailable: '原任务已不可用，请查看会话中的任务状态。',
        browser_connection_source_mismatch: '此连接请求与当前任务不一致，请查看最新会话进度。',
        browser_connection_changed: '连接状态已改变，页面会读取最新状态，请勿重复操作。',
        browser_connection_runtime_source_rejected: '任务当前无法接受连接结果，请查看最新会话进度。'
      } as Record<string, string>
    )[message ?? ''] ??
    message ??
    ''
  )
}

export function browserConnectionReconnectHint(detail?: BrowserConnectionDetail) {
  if (detail?.reason !== 'OFFLINE' || !detail.device_id) return ''
  const label = detail.devices.items.find((item) => item.device_id === detail.device_id)?.label
  return `请打开${label ? `原设备“${label}”` : '原设备'}的浏览器扩展，恢复 Remote 连接；服务端会自动继续任务，无需再次配对。也可明确选择另一台在线设备继续。`
}
/** 服务端拥有授权与恢复；页面只观察状态和发送显式的一次操作。 */
export function createBrowserConnection(options: {
  request: string
  selector: ConnectionSelector
  transport: ConnectionTransport
  changed(state: ConnectionState): void
  bridge?: typeof browserBridge
}) {
  const { request, selector, transport, changed } = options
  const bridge = options.bridge ?? browserBridge
  const identity = JSON.stringify(selector)
  const state: ConnectionState = {
    error: '',
    feedback: '',
    busy: false,
    makeDefault: false,
    attempted: {}
  }
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined,
    closed = false,
    started = false,
    actionSequence = 0
  const notify = () => {
    if (!closed) changed({ ...state, attempted: { ...state.attempted } })
  }
  for (const kind of ['pair', 'select', 'cancel'] as const)
    state.attempted[kind] =
      sessionStorage.getItem(connectionAttemptKey(identity, request, kind)) === 'sent'
  async function read() {
    try {
      const value = await transport.detail(request, selector, controller.signal)
      if (closed) return
      if (value.connection_request_id !== request) throw new Error('连接请求身份不一致。')
      if (!state.detail) state.makeDefault = !value.devices.selected_browser_id
      state.detail = value
      if (value.status !== 'WAITING') state.error = ''
    } catch (cause) {
      if (!closed) state.error = cause instanceof Error ? cause.message : '暂时无法读取连接状态。'
    } finally {
      notify()
      if (!closed && !['RESUMED', 'CANCELLED', 'FAILED'].includes(state.detail?.status ?? ''))
        timer = setTimeout(() => void read(), 2000)
    }
  }
  async function action(kind: 'pair' | 'select' | 'cancel', device = '') {
    if (closed || state.busy || state.attempted[kind] || state.detail?.status !== 'WAITING') return
    if (kind !== 'cancel' && (state.attempted.pair || state.attempted.select)) return
    if (
      kind === 'select' &&
      !state.detail.devices.items.some((item) => item.device_id === device && item.connected)
    )
      return
    const sequence = ++actionSequence
    state.busy = true
    state.error = ''
    state.feedback = ''
    notify()
    try {
      if (kind === 'pair') {
        const probe = await bridge('probe', {}, controller.signal)
        if (!probe.ok) throw new Error(connectionBridgeMessage(probe.code))
      }
      if (closed || state.detail?.status !== 'WAITING') return
      state.attempted[kind] = true
      sessionStorage.setItem(connectionAttemptKey(identity, request, kind), 'sent')
      const body = { ...selector, connection_request_id: request }
      if (kind === 'pair') {
        const result = await transport.pair(
          { ...body, make_default: state.makeDefault },
          controller.signal
        )
        if (closed) return
        state.feedback = '请打开插件确认配对。连接成功后服务端将自动继续任务。'
        state.busy = false
        notify()
        const response = await bridge(
          'pair',
          {
            pairing_url: result.pairing_url,
            connection_request_id: request,
            identity_scope: identity
          },
          controller.signal
        )
        if (closed || state.detail?.status !== 'WAITING') return
        state.feedback = response.ok
          ? '插件已确认配对，等待服务端自动继续任务。'
          : connectionBridgeMessage(response.code)
      } else if (kind === 'select') {
        await transport.select(
          { ...body, device_id: device, make_default: state.makeDefault },
          controller.signal
        )
        if (!closed) state.feedback = '已选择设备，服务端将自动继续任务。'
      } else {
        await transport.cancel(body, controller.signal)
        if (!closed) state.feedback = '已请求取消连接等待，服务端正在处理。'
      }
    } catch (cause) {
      if (!closed)
        state.error = cause instanceof Error ? cause.message : '操作结果尚未确认，请查看连接状态。'
    } finally {
      if (sequence === actionSequence) state.busy = false
      notify()
    }
  }
  return {
    start() {
      if (!closed && !started) {
        started = true
        void read()
      }
    },
    action,
    setDefault(value: boolean) {
      state.makeDefault = value
      notify()
    },
    close() {
      closed = true
      clearTimeout(timer)
      controller.abort()
    }
  }
}
