import assert from 'node:assert/strict'
import { once } from 'node:events'
import { createServer as createHttpServer } from 'node:http'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { createServer, resolveConfig } from 'vite'

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

for (const app of ['frontend-admin', 'frontend-platform', 'frontend-chat']) {
  test(
    `${app} 将上游中断传递给 SSE 客户端，重连沿用游标且普通错误仍返回 502`,
    { timeout: 20000 },
    async () => {
      let streamingResponse
      const requests = []
      const upstream = createHttpServer((request, response) => {
        requests.push({
          method: request.method,
          path: request.url,
          cursor: request.headers['last-event-id']
        })
        if (request.url === '/api/events') {
          response.writeHead(200, { 'Content-Type': 'text/event-stream' })
          if (request.headers['last-event-id']) {
            response.end('id: cursor-two\ndata: {"complete":true}\n\n')
          } else {
            streamingResponse = response
            response.write('id: cursor-one\ndata: {"ready":true}\n\n')
          }
        } else if (request.url === '/api/unavailable') {
          request.socket.destroy()
        } else {
          response.writeHead(200, { 'Content-Type': 'application/json' })
          response.end('{"ok":true}')
        }
      })
      let proxy
      let reader
      let timer
      const controller = new AbortController()
      try {
        upstream.listen(0, '127.0.0.1')
        await once(upstream, 'listening')
        const config = await resolveConfig(
          {
            root: resolve(repo, app),
            configFile: resolve(repo, app, 'vite.config.ts'),
            logLevel: 'silent'
          },
          'serve'
        )
        // 使用三个实际入口的代理配置，仅替换本用例独占的上游地址和监听端口。
        proxy = await createServer({
          configFile: false,
          root: resolve(repo, app),
          logLevel: 'silent',
          publicDir: false,
          optimizeDeps: { noDiscovery: true, include: [], entries: [] },
          server: {
            host: '127.0.0.1',
            port: 0,
            hmr: false,
            watch: null,
            proxy: {
              '/api': {
                ...config.server.proxy['/api'],
                target: `http://127.0.0.1:${upstream.address().port}`
              }
            }
          }
        })
        await proxy.listen()
        const origin = `http://127.0.0.1:${proxy.httpServer.address().port}`
        const response = await fetch(`${origin}/api/events`, { signal: controller.signal })
        assert.equal(response.status, 200)
        reader = response.body.getReader()
        assert.match(new TextDecoder().decode((await reader.read()).value), /cursor-one/)
        timer = setTimeout(() => controller.abort(), 3000)
        const interrupted = reader.read().then(
          (chunk) => ({
            done: chunk.done,
            bytes: chunk.value?.length,
            aborted: controller.signal.aborted
          }),
          (error) => ({ error: error.name, aborted: controller.signal.aborted })
        )
        streamingResponse.destroy()
        const closed = await interrupted
        assert.ok(
          !closed.aborted && (closed.done || closed.error),
          `上游中断必须结束下游读取：${JSON.stringify(closed)}`
        )
        clearTimeout(timer)
        const resumed = await fetch(`${origin}/api/events`, {
          headers: { 'Last-Event-ID': 'cursor-one' }
        })
        assert.match(await resumed.text(), /cursor-two/)
        assert.equal(requests[1].cursor, 'cursor-one')
        assert.deepEqual(await (await fetch(`${origin}/api/normal`)).json(), { ok: true })
        const unavailable = await fetch(`${origin}/api/unavailable`)
        assert.equal(unavailable.status, 502)
        await unavailable.text()
        assert.equal(requests.length, 4)
        assert.ok(requests.every((request) => request.method === 'GET'))
      } finally {
        clearTimeout(timer)
        controller.abort()
        if (reader) {
          await reader.cancel().catch(() => {})
          reader.releaseLock()
        }
        const upstreamClosed = new Promise((resolveClosed) => upstream.close(resolveClosed))
        upstream.closeAllConnections()
        await upstreamClosed
        if (proxy) await proxy.close()
      }
    }
  )
}
