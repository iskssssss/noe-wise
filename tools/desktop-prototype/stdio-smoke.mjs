import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { EventEmitter, once } from 'node:events'
import { createServer } from 'node:http'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'

// 真实Java进程、SQLite、正式Provider与本机工具；HTTP服务仅模拟供应商，不使用任何真实凭据。
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const option = name => {
  const index = process.argv.indexOf(name)
  return index < 0 ? undefined : process.argv[index + 1]
}
const library = option('--host-lib') ?? path.join(repository, 'backend-java/agent-runtime-desktop-host/build/install/agent-runtime-desktop-host/lib')
const java = option('--java') ?? (process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java')
const fixtureKey = 'controlled-desktop-smoke-key'
const root = await mkdtemp(path.join(tmpdir(), 'noewise-stdio-smoke-'))
const workspace = path.join(root, 'project')
await mkdir(workspace)
await writeFile(path.join(workspace, 'sample.txt'), 'broken\n', 'utf8')

class Host {
  constructor() {
    this.events = new EventEmitter()
    this.pending = new Map()
    this.states = new Map()
    this.stderr = ''
    this.child = spawn(java, ['-Dfile.encoding=UTF-8', '-cp', path.join(library, '*'),
      'com.skillforge.runtime.desktop.DesktopHostMain', '--data-dir', path.join(root, 'data')], {
      windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'],
    })
    this.exited = once(this.child, 'exit')
    this.child.stderr.setEncoding('utf8')
    this.child.stderr.on('data', value => { this.stderr = (this.stderr + value).slice(-8000) })
    this.lines = createInterface({ input: this.child.stdout })
    this.lines.on('line', line => {
      try {
        const message = JSON.parse(line)
        if (message.id) {
          const pending = this.pending.get(message.id)
          if (!pending) return
          this.pending.delete(message.id)
          clearTimeout(pending.timer)
          message.error ? pending.reject(new Error(JSON.stringify(message.error))) : pending.resolve(message.result)
        } else {
          if (message.event === 'state') this.states.set(message.sessionId, message.data)
          if (message.event === 'ready') this.ready = message
          if (message.event === 'fatal' || message.event === 'hostError') this.problem = message
          this.events.emit('change')
        }
      } catch (error) { this.problem = error; this.events.emit('change') }
    })
    this.child.on('exit', () => {
      this.dead = true
      for (const pending of this.pending.values()) {
        clearTimeout(pending.timer)
        pending.reject(new Error('Host exited before request completed: ' + this.stderr))
      }
      this.pending.clear()
      this.events.emit('change')
    })
  }
  request(method, params = {}) {
    const id = randomUUID()
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error('Request deadline: ' + method))
      }, 30_000)
      this.pending.set(id, { resolve, reject, timer })
      this.child.stdin.write(JSON.stringify({ id, method, params }) + '\n')
    })
  }
  async until(predicate) {
    const deadline = Date.now() + 45_000
    while (!predicate()) {
      if (this.problem) throw new Error('Host protocol failure: ' + JSON.stringify(this.problem))
      if (this.dead) throw new Error('Host stopped: ' + this.stderr)
      const remaining = deadline - Date.now()
      if (remaining <= 0) throw new Error('State deadline: ' + JSON.stringify([...this.states.values()]))
      await once(this.events, 'change', { signal: AbortSignal.timeout(remaining) })
    }
  }
  async stop() {
    if (this.dead) return
    await this.request('shutdown')
    this.child.stdin.end()
    const [code] = await this.exited
    assert.equal(code, 0)
    this.lines.close()
  }
}

let calls = 0
let providerFailure
const sequence = [
  ['read_file', { path: 'sample.txt' }],
  ['edit_file', { path: 'sample.txt', oldText: 'broken', newText: 'fixed' }],
  ['run_command', { command: process.platform === 'win32'
    ? "if ((Get-Content -Raw sample.txt).Trim() -ne 'fixed') { throw 'Unexpected file' }; Write-Output 'desktop smoke passed'"
    : "test \"$(cat sample.txt)\" = fixed && printf 'desktop smoke passed\\n'" }],
  [null, { text: '已读取并修复 sample.txt，命令检查通过。' }],
]
const server = createServer(async (request, response) => {
  try {
    assert.equal(request.method, 'POST')
    assert.ok(request.url.endsWith('/chat/completions'))
    assert.equal(request.headers.authorization, 'Bearer ' + fixtureKey)
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    const [description, arguments_] = sequence[calls++] ?? []
    assert.ok(arguments_, 'Unexpected model retry or extra invocation')
    assert.equal(body.tool_choice, 'auto')
    assert.ok(body.tools.every(value => value.function.name !== 'final_answer'))
    if (description === null) {
      response.writeHead(200, { 'content-type': 'application/json' })
      response.end(JSON.stringify({
        id: 'controlled-response-' + calls, model: 'controlled-model',
        choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content: arguments_.text } }],
        usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 },
      }))
      return
    }
    const matches = body.tools.filter(value => value.function.name === description || value.function.description
      .split('\n').some(line => line.startsWith('title=') && JSON.parse(line.slice('title='.length)) === description))
    assert.equal(matches.length, 1, 'Expected exactly one declared tool: ' + description)
    const tool = matches[0]
    assert.ok(tool, 'Expected tool was absent from actual model request: ' + description)
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify({
      id: 'controlled-response-' + calls, model: 'controlled-model',
      choices: [{ index: 0, finish_reason: 'tool_calls', message: {
        role: 'assistant', content: null, tool_calls: [{
          id: 'controlled-call-' + calls, type: 'function',
          function: { name: tool.function.name, arguments: JSON.stringify(arguments_) },
        }],
      } }],
      usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 },
    }))
  } catch (error) {
    providerFailure = error
    response.writeHead(500)
    response.end()
  }
})
server.listen(0, '127.0.0.1')
await once(server, 'listening')
let host
try {
  host = new Host()
  await host.until(() => host.ready)
  assert.equal(host.ready.data.protocolVersion, 1)
  await host.request('configureModel', {
    providerId: 'custom', baseUrl: 'http://127.0.0.1:' + server.address().port,
    model: 'controlled-model', apiKey: fixtureKey,
  })
  const sessionId = randomUUID()
  await host.request('createSession', { id: sessionId, title: 'stdio smoke', workspace })
  const submitted = await host.request('submitTask', { sessionId, text: '修复文件并运行检查', inputId: randomUUID() })
  await host.until(() => host.states.get(sessionId)?.pendingConfirmations?.length > 0)
  const waiting = host.states.get(sessionId).pendingConfirmations[0]
  assert.equal(waiting.runId, submitted.runId)
  assert.match(waiting.command, /desktop smoke passed/)
  await host.request('confirmTool', {
    runId: waiting.runId, waitingId: waiting.waitingId, approved: true, inputId: randomUUID(),
  })
  await host.until(() => host.states.get(sessionId)?.runs.some(run => run.id === submitted.runId && run.phase === 'FINISHED'))
  let state = await host.request('getSession', { sessionId })
  assert.equal(state.runs.find(run => run.id === submitted.runId).status, 'COMPLETED')
  assert.equal(await readFile(path.join(workspace, 'sample.txt'), 'utf8'), 'fixed\n')
  assert.ok(state.messages.some(message => message.role === 'assistant' && message.text.includes('命令检查通过')))
  assert.equal(calls, 4)
  assert.equal(providerFailure, undefined)
  await host.stop()
  host = new Host()
  await host.until(() => host.ready)
  state = await host.request('getSession', { sessionId })
  assert.equal(state.runs.find(run => run.id === submitted.runId).status, 'COMPLETED')
  assert.ok(state.events.some(event => event.type === 'tool' && event.result?.stdout?.includes('desktop smoke passed')))
  assert.equal(calls, 4, 'Reopening history must not replay the provider call')
  await host.stop()
  console.log(JSON.stringify({ status: 'PASS', model: 'controlled HTTP provider',
    transport: 'real Java stdio', database: 'real SQLite',
    checks: ['read', 'edit', 'command approval', 'native command', 'native assistant answer', 'restart without replay'] }))
} finally {
  if (host && !host.dead) {
    try { await host.stop() } catch { host.child.kill(); await host.exited }
  }
  server.closeAllConnections()
  await new Promise(resolve => server.close(resolve))
  const expectedParent = path.resolve(tmpdir()) + path.sep
  assert.ok(path.resolve(root).startsWith(expectedParent + 'noewise-stdio-smoke-'))
  await rm(root, { recursive: true, force: true })
}
