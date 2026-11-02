const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { once } = require('node:events')
const { HostBridge } = require('../electron/host-bridge.cjs')
const fixture = path.join(__dirname, 'fixtures', 'protocol-host.cjs')

function createBridge(mode) {
  return new HostBridge({
    command: process.execPath,
    args: [fixture, ...(mode ? [mode] : [])],
    requestTimeoutMs: 1000
  })
}

test('启动与协议请求保持 Unicode 和请求身份，关闭等待进程结束', async (t) => {
  const bridge = createBridge()
  t.after(() => bridge.stop())
  await bridge.start()
  assert.equal(bridge.status.status, 'ready')
  const replies = await Promise.all([
    bridge.request('echo', { text: '中文目录 🛠' }),
    bridge.request('echo', { index: 2 })
  ])
  assert.deepEqual(replies, [{ text: '中文目录 🛠' }, { index: 2 }])
  await bridge.stop()
  assert.equal(bridge.process, null)
})

test('Host 业务错误保留拒绝语义，不报告成功', async (t) => {
  const bridge = createBridge()
  t.after(() => bridge.stop())
  await bridge.start()
  await assert.rejects(bridge.request('fail'), /REJECTED: 明确拒绝/)
})

test('Host 异常退出拒绝全部待定请求，且不自动重发', async (t) => {
  const bridge = createBridge()
  t.after(() => bridge.stop())
  await bridge.start()
  const waiting = assert.rejects(bridge.request('wait'), /连接中断/)
  const crashing = assert.rejects(bridge.request('crash'), /连接中断/)
  await Promise.all([waiting, crashing])
  assert.equal(bridge.pending.size, 0)
  assert.equal(bridge.status.status, 'failed')
})

test('无效协议启动失败，并等待所属子进程结束', async () => {
  const bridge = createBridge('malformed')
  const closed = once(bridge, 'closed')
  await assert.rejects(bridge.start(), /无效协议/)
  await closed
  assert.equal(bridge.process, null)
})

test('不存在的启动程序返回明确失败且无遗留进程', async () => {
  const bridge = new HostBridge({ command: path.join(__dirname, 'missing-java'), args: [] })
  const closed = once(bridge, 'closed')
  await assert.rejects(bridge.start())
  await closed
  assert.equal(bridge.process, null)
})
