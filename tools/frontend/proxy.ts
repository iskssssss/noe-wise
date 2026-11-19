import type { ProxyOptions } from 'vite'

/** 上游响应未完整结束时关闭下游连接，让客户端感知中断并沿原游标恢复。 */
export const closeInterruptedProxyResponses: NonNullable<ProxyOptions['configure']> = (proxy) => {
  proxy.on('proxyRes', (upstream, _request, downstream) => {
    upstream.on('close', () => {
      if (!upstream.complete) downstream.destroy()
    })
  })
}
