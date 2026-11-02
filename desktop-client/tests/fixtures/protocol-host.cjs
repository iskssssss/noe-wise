const readline = require('node:readline')
const path = require('node:path')
const mode = process.argv[2]
const send = (message) => process.stdout.write(`${JSON.stringify(message)}\n`)
const sessions = []
let configured = false
let modelKey = ''
let modelOutputTokens
let counter = 0
const states = new Map()

if (mode === 'malformed') process.stdout.write('this is not JSON\n')
else send({ event: 'ready', data: { protocolVersion: 1 } })

readline.createInterface({ input: process.stdin }).on('line', (line) => {
  const { id, method, params } = JSON.parse(line)
  const result = (value) => send({ id, result: value })
  const error = (code, message) => send({ id, error: { code, message } })
  if (method === 'shutdown') {
    result({ stopped: true })
    process.exitCode = 0
    process.stdin.destroy()
    return
  }
  if (method === 'echo') {
    result(params)
    return
  }
  if (method === 'fail') {
    error('REJECTED', '明确拒绝')
    return
  }
  if (method === 'crash') {
    process.exit(2)
  }
  if (method === 'wait') return
  if (method === 'configureModel') {
    configured = true
    modelKey = params.apiKey
    modelOutputTokens = params.outputTokens
    result({ configured })
    return
  }
  if (method === 'testModel') {
    if (modelKey === 'bad-key') {
      result({ connected: false, code: 'AUTH_REJECTED', message: '模型鉴权失败' })
      return
    }
    if (modelKey === 'unknown-key') {
      result({ connected: false, code: 'UNKNOWN', message: '模型结果未知' })
      return
    }
    configured ? result({ connected: true }) : error('MODEL_NOT_CONFIGURED', '请先配置模型')
    return
  }
  if (method === 'listSessions') {
    result(sessions)
    return
  }
  if (method === 'createSession') {
    sessions.unshift(params)
    const state = { session: params, messages: [], runs: [], events: [], pendingConfirmations: [] }
    states.set(params.id, state)
    result(params)
    return
  }
  if (method === 'getSession') {
    result(states.get(params.sessionId))
    return
  }
  if (method === 'submitTask') {
    if (params.text === '受控拒绝') {
      error('TASK_REJECTED', '受控请求拒绝，输入应保留')
      return
    }
    if (!configured) {
      error('MODEL_NOT_CONFIGURED', '请先配置模型')
      return
    }
    const state = states.get(params.sessionId)
    if (state.runs.at(-1)?.recoveryBlocked) {
      error('DESKTOP_RUN_FORMAT_UNSUPPORTED', '此会话包含旧版执行协议，请新建会话开始任务。')
      return
    }
    const runId = `run-${++counter}`
    state.messages.push({ role: 'user', text: params.text })
    if (params.text.startsWith('UI阅读演示')) {
      state.runs.push({ id: runId, status: 'COMPLETED', phase: 'FINISHED' })
      const first = !state.messages.some((message) => message.text.startsWith('阅读记录 1：'))
      const amount = first ? 44 : 2
      for (let index = 1; index <= amount; index++) {
        state.messages.push({
          role: 'assistant',
          text:
            `${first ? '阅读记录' : '新增阅读记录'} ${index}：项目进展\n\n` +
            '这里展示已有文件、执行事实与下一步说明。'.repeat(9) +
            '\n\n`src/example.ts` 的修改需要保留真实结果，阅读历史时不应被新回复打断。'
        })
      }
      state.events.push({
        sequence: state.events.length + 1,
        type: 'tool',
        runId,
        toolName: 'edit_file',
        arguments: { path: 'src/example.ts' },
        result: {
          status: 'SUCCEEDED',
          diff: '--- src/example.ts\n+++ src/example.ts\n- old\n+ new'
        }
      })
      result({ runId, created: true })
      send({ event: 'state', sessionId: params.sessionId, data: state })
      return
    }
    if (params.text === '旧版历史演示') {
      state.runs.push({
        id: runId,
        status: 'RUNNING',
        phase: 'READY',
        recoveryBlocked: true,
        recoveryReason: 'DESKTOP_RUN_FORMAT_UNSUPPORTED'
      })
      state.messages.push({ role: 'assistant', text: '原有回答和执行记录仍保留。' })
      result({ runId, created: true })
      send({ event: 'state', sessionId: params.sessionId, data: state })
      return
    }
    if (params.text.startsWith('输出上限检查')) {
      state.runs.push({ id: runId, status: 'COMPLETED', phase: 'FINISHED' })
      state.messages.push({
        role: 'assistant',
        text: `本次单次上限：${modelOutputTokens ?? '宿主默认'}`
      })
      result({ runId, created: true })
      send({ event: 'state', sessionId: params.sessionId, data: state })
      return
    }
    if (params.text.startsWith('Markdown展示')) {
      state.runs.push({ id: runId, status: 'COMPLETED', phase: 'FINISHED' })
      state.messages.push({
        role: 'assistant',
        text:
          '# 项目说明\n\n## 验证步骤\n\n1. **读取项目**\n2. 运行测试\n\n- 文件保存在本地\n- 使用 `node --test`\n\n```javascript\nconst html = "<script>window.__markdownXss=true</script>";\n' +
          'const longLine = "' +
          'x'.repeat(400) +
          '";\n```\n\n[外部文档](https://example.com/docs?q=1#intro)\n[脚本链接](javascript:alert%281%29)\n[本地文件](file:///C:/secret.txt)\n[数据链接](data:text/html,test)\n[带凭据链接](https://user:pass@example.com)\n\n![远程图片](https://attacker.invalid/pixel.png)\n\n<script>window.__markdownXss=true</script>\n<img src="https://attacker.invalid/raw.png" onerror="window.__markdownXss=true">\n<style>body { display: none }</style>\n<iframe src="https://attacker.invalid/frame"></iframe>'
      })
      result({ runId, created: true })
      send({ event: 'state', sessionId: params.sessionId, data: state })
      return
    }
    if (params.text.startsWith('输出截断')) {
      state.runs.push({ id: runId, status: 'FAILED', phase: 'FINISHED' })
      if (params.text === '输出截断有工具')
        state.events.push({
          sequence: 1,
          type: 'tool',
          runId,
          toolName: 'write_file',
          result: { status: 'SUCCEEDED', diff: '--- a/prior.txt\n+++ b/prior.txt\n+此前写入已完成' }
        })
      state.events.push({
        sequence: 2,
        type: 'execution.completed',
        runId,
        data: { reason: 'model_output_length', status: 'FAILED' }
      })
      result({ runId, created: true })
      send({ event: 'state', sessionId: params.sessionId, data: state })
      return
    }
    state.runs.push({ id: runId, status: 'SUSPENDED', phase: 'WAITING' })
    state.pendingConfirmations.push({
      runId,
      waitingId: `wait-${counter}`,
      command: 'node --test',
      workspace: state.session.workspace
    })
    result({ runId, created: true })
    send({ event: 'state', sessionId: params.sessionId, data: state })
    return
  }
  const state = [...states.values()].find((item) =>
    item.runs.some((run) => run.id === params.runId)
  )
  if (method === 'confirmTool') {
    const failedCommand = state.messages.at(-1)?.text === '命令失败演示'
    state.pendingConfirmations = []
    state.runs.find((run) => run.id === params.runId).status = 'COMPLETED'
    state.runs.find((run) => run.id === params.runId).phase = 'FINISHED'
    state.messages.push({
      role: 'assistant',
      text: failedCommand
        ? '命令退出码为 7，已保留失败输出。'
        : params.approved
          ? '已修复边界条件，测试通过。'
          : '已拒绝执行命令。'
    })
    state.events.push({
      sequence: 1,
      type: 'tool',
      runId: params.runId,
      toolName: 'file_edit',
      arguments: { path: 'src/index.ts' },
      result: { diff: '--- src/index.ts\n+++ src/index.ts\n- return value\n+ return value ?? 0' }
    })
    if (params.approved)
      state.events.push({
        sequence: 2,
        type: 'tool',
        runId: params.runId,
        toolName: 'command_execute',
        text: 'SUCCEEDED',
        fieldsTruncated: failedCommand ? ['result.stdout'] : [],
        arguments: { command: 'node --test' },
        result: failedCommand
          ? {
              status: 'FAILED',
              isError: true,
              stdout: '展示的输出前缀',
              stdoutTruncated: true,
              stderr: '受控失败',
              exitCode: 7
            }
          : { stdout: 'PASS 1/1\n' + 'output line\n'.repeat(3000), stderr: '', exitCode: 0 }
      })
    if (failedCommand) state.projectionTruncated = true
    result({ confirmed: true })
    send({ event: 'state', sessionId: state.session.id, data: state })
    return
  }
  if (method === 'cancelTask') {
    state.pendingConfirmations = []
    state.runs.find((run) => run.id === params.runId).status = 'CANCELLED'
    state.runs.find((run) => run.id === params.runId).phase = 'FINISHED'
    result({ cancelled: true })
    send({ event: 'state', sessionId: state.session.id, data: state })
    return
  }
  error('UNKNOWN_METHOD', `未知方法 ${path.basename(method)}`)
})
