import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  browserBridge,
  browserConnectionReason,
  browserConnectionError,
  browserConnectionReconnectHint,
  createBrowserConnection,
  type BrowserConnectionDetail,
  type ConnectionState
} from './browserConnection'

const detail = (
  status: BrowserConnectionDetail['status'] = 'WAITING',
  selected?: string
): BrowserConnectionDetail => ({
  connection_request_id: 'request',
  status,
  reason: '需要浏览器',
  make_default: false,
  devices: {
    items: [
      {
        device_id: 'device',
        browser_id: 'browser',
        label: '本人设备',
        connected: true,
        expires_at: '2030-01-01'
      }
    ],
    selected_browser_id: selected,
    installation_url: 'https://example.com/install'
  }
})
const flush = async () => {
  for (let i = 0; i < 12; i++) await Promise.resolve()
}
beforeEach(() => {
  vi.useFakeTimers()
  sessionStorage.clear()
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})
describe('浏览器连接共享控制', () => {
  it('已知原因与错误显示中文，未知HTTP说明保留，离线原设备才提供重连指引', () => {
    expect(browserConnectionReason('NO_SELECTION')).toContain('尚未选择')
    expect(browserConnectionReason('AUTHORIZATION_REQUIRED')).toContain('授权')
    expect(browserConnectionError('browser_connection_changed')).toContain('状态已改变')
    expect(browserConnectionError('HTTP 503：暂时不可用')).toBe('HTTP 503：暂时不可用')
    expect(browserConnectionReconnectHint({ ...detail(), reason: 'OFFLINE' })).toBe('')
    expect(
      browserConnectionReconnectHint({ ...detail(), reason: 'OFFLINE', device_id: 'device' })
    ).toContain('原设备“本人设备”')
  })
  function fixture(selected?: string) {
    let state: ConnectionState
    const transport = {
      detail: vi.fn().mockResolvedValue(detail('WAITING', selected)),
      pair: vi.fn().mockResolvedValue({ pairing_url: 'wss://example.com/pair#secret' }),
      select: vi.fn().mockResolvedValue({}),
      cancel: vi.fn().mockResolvedValue({})
    }
    const bridge = vi.fn().mockResolvedValue({ ok: true })
    const connection = createBrowserConnection({
      request: 'request',
      selector: { client_ref: 'owner' },
      transport,
      bridge,
      changed: (value) => {
        state = value
      }
    })
    return { connection, transport, bridge, state: () => state! }
  }
  it('首次默认选中，已有默认不覆盖；只读轮询自动恢复且不提交恢复输入', async () => {
    const f = fixture()
    f.connection.start()
    await flush()
    expect(f.state().makeDefault).toBe(true)
    f.transport.detail.mockResolvedValue(detail('RESUMED'))
    await vi.advanceTimersByTimeAsync(2000)
    expect(f.state().detail?.status).toBe('RESUMED')
    await vi.advanceTimersByTimeAsync(6000)
    expect(f.transport.detail).toHaveBeenCalledTimes(2)
    expect(f.transport.pair).not.toHaveBeenCalled()
    expect(f.transport.select).not.toHaveBeenCalled()
    f.connection.close()
    const old = fixture('old-default')
    old.connection.start()
    await flush()
    expect(old.state().makeDefault).toBe(false)
    old.connection.close()
  })
  it('探测失败不创建配对；配对只发一次并交给扩展确认，不暴露地址', async () => {
    const f = fixture()
    f.connection.start()
    await flush()
    f.bridge.mockResolvedValueOnce({ ok: false, code: 'site_not_trusted' })
    await f.connection.action('pair')
    expect(f.transport.pair).not.toHaveBeenCalled()
    expect(f.state().error).toContain('信任当前站点')
    await f.connection.action('pair')
    await f.connection.action('pair')
    expect(f.transport.pair).toHaveBeenCalledTimes(1)
    expect(f.bridge).toHaveBeenLastCalledWith(
      'pair',
      expect.objectContaining({
        connection_request_id: 'request',
        pairing_url: 'wss://example.com/pair#secret'
      }),
      expect.any(AbortSignal)
    )
    expect(JSON.stringify(f.state())).not.toContain('#secret')
    f.connection.close()
  })
  it('未知选择不会重发，重挂载仍锁定；取消等待使用专用接口', async () => {
    const f = fixture()
    f.connection.start()
    await flush()
    f.transport.select.mockRejectedValue(new Error('结果未知'))
    await f.connection.action('select', 'device')
    await f.connection.action('select', 'device')
    expect(f.transport.select).toHaveBeenCalledTimes(1)
    f.connection.close()
    const next = fixture()
    next.connection.start()
    await flush()
    await next.connection.action('select', 'device')
    expect(next.transport.select).not.toHaveBeenCalled()
    await next.connection.action('cancel')
    expect(next.transport.cancel).toHaveBeenCalledWith(
      { client_ref: 'owner', connection_request_id: 'request' },
      expect.any(AbortSignal)
    )
    next.transport.detail.mockResolvedValue(detail('CANCELLING'))
    await vi.advanceTimersByTimeAsync(2000)
    expect(next.state().detail?.status).toBe('CANCELLING')
    next.connection.close()
  })
  it('关闭中止观察并忽略迟到响应，不再轮询', async () => {
    const f = fixture()
    let resolve!: (value: BrowserConnectionDetail) => void
    f.transport.detail.mockImplementation(
      () =>
        new Promise<BrowserConnectionDetail>((done) => {
          resolve = done
        })
    )
    f.connection.start()
    f.connection.close()
    expect(f.transport.detail.mock.calls[0]![2].aborted).toBe(true)
    resolve(detail())
    await flush()
    await vi.advanceTimersByTimeAsync(10000)
    expect(f.transport.detail).toHaveBeenCalledTimes(1)
    expect(f.state()).toBeUndefined()
  })
  it('机构选择器不会被改成client_ref，离线设备不派发选择', async () => {
    const f = fixture()
    f.connection.close()
    f.transport.detail.mockResolvedValue({
      ...detail(),
      devices: { ...detail().devices, items: [{ ...detail().devices.items[0]!, connected: false }] }
    })
    const connection = createBrowserConnection({
      request: 'request',
      selector: { session_id: 'session' },
      transport: f.transport,
      changed: () => {}
    })
    connection.start()
    await flush()
    await connection.action('select', 'device')
    expect(f.transport.select).not.toHaveBeenCalled()
    await connection.action('cancel')
    expect(f.transport.cancel).toHaveBeenCalledWith(
      { session_id: 'session', connection_request_id: 'request' },
      expect.any(AbortSignal)
    )
    connection.close()
  })
})
describe('网页扩展桥接信封', () => {
  it('拒绝错误来源、origin与request，只公开允许字段', async () => {
    const post = vi.spyOn(window, 'postMessage').mockImplementation(() => {})
    const pending = browserBridge('probe')
    const envelope = post.mock.calls[0]![0]
    const reply = {
      source: 'noewise-extension',
      version: 1,
      request_id: envelope.request_id,
      ok: true,
      token: 'never',
      version_info: { adapter_version: '1', upstream_commit: 'commit' }
    }
    let settled = false
    void pending.then(() => {
      settled = true
    })
    window.dispatchEvent(
      new MessageEvent('message', { data: reply, source: null, origin: location.origin })
    )
    await flush()
    expect(settled).toBe(false)
    window.dispatchEvent(
      new MessageEvent('message', { data: reply, source: window, origin: 'https://other.example' })
    )
    await flush()
    expect(settled).toBe(false)
    window.dispatchEvent(
      new MessageEvent('message', {
        data: { ...reply, request_id: 'wrong' },
        source: window,
        origin: location.origin
      })
    )
    await flush()
    expect(settled).toBe(false)
    window.dispatchEvent(
      new MessageEvent('message', { data: reply, source: window, origin: location.origin })
    )
    expect(await pending).toEqual({
      ok: true,
      code: undefined,
      version_info: { adapter_version: '1', upstream_commit: 'commit' }
    })
  })
  it('探测超时和关闭均清理监听，不自动发送第二次操作', async () => {
    const post = vi.spyOn(window, 'postMessage').mockImplementation(() => {})
    const pending = browserBridge('probe')
    const rejected = expect(pending).rejects.toThrow('信任当前站点')
    await vi.advanceTimersByTimeAsync(3000)
    await rejected
    expect(post).toHaveBeenCalledTimes(1)
    const abort = new AbortController()
    const cancelled = browserBridge('pair', {}, abort.signal)
    const check = expect(cancelled).rejects.toThrow('已停止观察')
    abort.abort()
    await check
  })
})
