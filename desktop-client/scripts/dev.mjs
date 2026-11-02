import { createServer } from 'vite'
import { spawn } from 'node:child_process'
import electron from 'electron'

const server = await createServer()
await server.listen()
const address = server.httpServer.address()
if (!address || typeof address === 'string') throw new Error('开发服务器没有返回 TCP 地址')
const child = spawn(electron, ['.'], {
  stdio: 'inherit',
  env: { ...process.env, NOEWISE_DEV_URL: `http://127.0.0.1:${address.port}` },
  windowsHide: true
})
let closing = false
async function close() {
  if (closing) return
  closing = true
  child.kill()
  await server.close()
}
process.once('SIGINT', close)
process.once('SIGTERM', close)
child.once('error', async (error) => {
  console.error(error.message)
  await close()
  process.exitCode = 1
})
child.once('exit', async (code) => {
  await close()
  process.exitCode = code ?? 1
})
