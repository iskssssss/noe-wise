const { EventEmitter } = require('node:events')
const { spawn } = require('node:child_process')
const { StringDecoder } = require('node:string_decoder')
const { randomUUID } = require('node:crypto')

const MAX_PROTOCOL_LINE_BYTES = 8 * 1024 * 1024
const DEFAULT_REQUEST_TIMEOUT_MS = 150_000
const DEFAULT_READY_TIMEOUT_MS = 30_000
const SHUTDOWN_TIMEOUT_MS = 30_000

/** 私有 stdio 通道只由主进程持有；断连拒绝待定请求，不重放写请求。 */
class HostBridge extends EventEmitter {
  constructor(options) {
    super()
    this.options = options
    this.pending = new Map()
    this.process = null
    this.status = { status: 'starting', message: '正在启动本地 Agent…' }
    this.stopping = false
  }

  async start() {
    if (this.process) throw new Error('本地 Agent 已启动')
    const child = spawn(this.options.command, this.options.args, {
      cwd: this.options.cwd,
      env: this.options.env,
      windowsHide: true,
      detached: process.platform !== 'win32',
      stdio: ['pipe', 'pipe', 'pipe']
    })
    this.process = child
    const decoder = new StringDecoder('utf8')
    let buffer = ''
    let startupComplete = false
    return new Promise((resolve, reject) => {
      const readyTimer = setTimeout(() => {
        const error = new Error('本地 Agent 启动超时，请检查 Java 与 Host 构建产物')
        fail(error)
        child.kill()
      }, this.options.readyTimeoutMs ?? DEFAULT_READY_TIMEOUT_MS)
      const fail = (error) => {
        clearTimeout(readyTimer)
        this.setStatus('failed', this.stopping ? '本地 Agent 已停止' : error.message)
        for (const request of this.pending.values()) {
          clearTimeout(request.timer)
          request.reject(new Error('本地 Agent 连接中断；任务结果需要重新核对'))
        }
        this.pending.clear()
        if (!startupComplete) {
          startupComplete = true
          reject(error)
        }
      }
      child.on('error', fail)
      child.stdin.on('error', fail)
      // 不把原始 stderr 转发给界面，避免供应商响应或凭据泄露。
      child.stderr.on('data', () => {})
      child.on('close', (code, signal) => {
        this.process = null
        fail(
          new Error(`本地 Agent 已退出（${signal ?? code ?? '未知'}）。请重启应用后核对任务状态。`)
        )
        this.emit('closed')
      })
      child.stdout.on('data', (chunk) => {
        buffer += decoder.write(chunk)
        if (Buffer.byteLength(buffer) > MAX_PROTOCOL_LINE_BYTES) {
          fail(new Error('本地 Agent 协议输出超出容量限制'))
          child.kill()
          return
        }
        let newline
        while ((newline = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, newline)
          buffer = buffer.slice(newline + 1)
          if (!line.trim()) continue
          let message
          try {
            message = JSON.parse(line)
          } catch {
            fail(new Error('本地 Agent 返回了无效协议消息'))
            child.kill()
            return
          }
          if (!message || typeof message !== 'object' || Array.isArray(message)) {
            fail(new Error('本地 Agent 协议消息结构无效'))
            child.kill()
            return
          }
          if (message.event === 'ready') {
            if ((message.data?.protocolVersion ?? message.protocolVersion) !== 1) {
              fail(new Error('本地 Agent 协议版本不匹配'))
              child.kill()
              return
            }
            clearTimeout(readyTimer)
            this.setStatus('ready', '本地 Agent 已就绪')
            if (!startupComplete) {
              startupComplete = true
              resolve()
            }
          } else if (typeof message.event === 'string') {
            this.emit('event', message)
          } else if (typeof message.id === 'string') {
            const pending = this.pending.get(message.id)
            if (!pending) continue
            clearTimeout(pending.timer)
            this.pending.delete(message.id)
            if (message.error) {
              pending.reject(
                new Error(
                  `${message.error.code ?? 'HOST_ERROR'}: ${message.error.message ?? '请求失败'}`
                )
              )
            } else pending.resolve(message.result)
          }
        }
      })
    })
  }

  setStatus(status, message) {
    this.status = { status, message }
    this.emit('status', this.status)
  }

  request(method, params = {}) {
    if (
      !this.process ||
      this.status.status !== 'ready' ||
      (this.stopping && method !== 'shutdown')
    ) {
      return Promise.reject(new Error('本地 Agent 未就绪，请重启应用后重试'))
    }
    const id = randomUUID()
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error('请求等待超时；已保留输入，请核对任务记录，勿重复提交'))
      }, this.options.requestTimeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS)
      this.pending.set(id, { resolve, reject, timer })
      this.process.stdin.write(`${JSON.stringify({ id, method, params })}\n`, (error) => {
        if (!error) return
        const pending = this.pending.get(id)
        if (pending) {
          clearTimeout(pending.timer)
          this.pending.delete(id)
          reject(new Error('本地 Agent 连接中断'))
        }
      })
    })
  }

  async stop() {
    const child = this.process
    if (!child) return
    this.stopping = true
    const closed = new Promise((resolve) => child.once('close', resolve))
    const force = setTimeout(() => {
      if (this.process !== child) return
      if (process.platform === 'win32') {
        const killer = spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], {
          windowsHide: true,
          stdio: 'ignore'
        })
        killer.on('error', () => child.kill())
      } else {
        try {
          process.kill(-child.pid, 'SIGKILL')
        } catch {
          child.kill('SIGKILL')
        }
      }
    }, SHUTDOWN_TIMEOUT_MS)
    try {
      if (this.status.status === 'ready') await this.request('shutdown').catch(() => {})
      child.stdin.end()
      await closed
    } finally {
      clearTimeout(force)
    }
  }
}

module.exports = { HostBridge }
