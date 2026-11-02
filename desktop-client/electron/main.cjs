const { app, BrowserWindow, ipcMain, dialog, safeStorage, shell } = require('electron')
const path = require('node:path')
const fs = require('node:fs/promises')
const { pathToFileURL } = require('node:url')
const { HostBridge } = require('./host-bridge.cjs')
const { ModelSettings } = require('./model-settings.cjs')
const { externalUrl } = require('./external-links.cjs')
const { version: clientVersion } = require('../package.json')

const CLIENT_ROOT = path.resolve(__dirname, '..')
const HOST_MAIN_CLASS = 'com.skillforge.runtime.desktop.DesktopHostMain'
const WINDOW = { width: 1380, height: 900, minWidth: 900, minHeight: 650 }
const MAX_TASK_CHARACTERS = 64_000

function exactObject(value, fields) {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).some((key) => !fields.includes(key))
  )
    throw new Error('请求参数无效')
  return value
}
function identity(value, name) {
  if (typeof value !== 'string' || !value || value.length > 256 || /[\r\n\0]/.test(value))
    throw new Error(`${name}无效`)
  return value
}

function defaultHostLaunch(dataDirectory) {
  const library = app.isPackaged
    ? path.join(process.resourcesPath, 'host', 'lib')
    : path.resolve(
        CLIENT_ROOT,
        '../backend-java/agent-runtime-desktop-host/build/install/agent-runtime-desktop-host/lib'
      )
  const java = app.isPackaged
    ? path.join(
        process.resourcesPath,
        'jre',
        'bin',
        process.platform === 'win32' ? 'java.exe' : 'java'
      )
    : process.env.JAVA_HOME
      ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
      : 'java'
  // 不继承开发进程的全部环境，避免把其他服务的凭据暴露给 Agent 命令。
  const env = Object.fromEntries(
    Object.entries(process.env).filter(([key]) =>
      /^(PATH|PATHEXT|SYSTEMROOT|WINDIR|TEMP|TMP|TMPDIR|HOME|USERPROFILE|APPDATA|LOCALAPPDATA|LANG|LC_.*|JAVA_HOME)$/i.test(
        key
      )
    )
  )
  env.NOEWISE_NODE_EXECUTABLE = process.execPath
  return {
    command: java,
    args: [
      '-Dfile.encoding=UTF-8',
      '-cp',
      path.join(library, '*'),
      HOST_MAIN_CLASS,
      '--data-dir',
      dataDirectory
    ],
    cwd: dataDirectory,
    env
  }
}

/** 桌面装配入口；依赖由主进程注入，页面只能调用受限用例。 */
async function startApplication(options = {}) {
  if (options.dataDirectory) app.setPath('userData', options.dataDirectory)
  if (!app.requestSingleInstanceLock()) {
    app.quit()
    return
  }
  await app.whenReady()
  const dataDirectory = app.getPath('userData')
  await fs.mkdir(dataDirectory, { recursive: true })
  const settings = new ModelSettings(dataDirectory, safeStorage)
  let setupError = ''
  try {
    await settings.load()
  } catch (error) {
    setupError = error.message
  }
  const bridge = new HostBridge(options.hostLaunch ?? defaultHostLaunch(dataDirectory))
  const selectedDirectories = new Set()
  let window
  let quitting = false
  let modelWritePending = false
  const viewUrl = !app.isPackaged ? process.env.NOEWISE_DEV_URL : undefined
  const productionPage = path.join(CLIENT_ROOT, 'dist', 'index.html')
  const send = (value) => {
    if (window && !window.isDestroyed()) window.webContents.send('desktop:event', value)
  }
  bridge.on('status', (value) => send({ event: 'host-status', data: value }))
  bridge.on('event', send)

  function verifySender(event) {
    if (
      !window ||
      event.sender !== window.webContents ||
      event.senderFrame !== window.webContents.mainFrame
    )
      throw new Error('不可信的页面来源')
    const url = event.senderFrame.url
    if (
      viewUrl
        ? new URL(url).origin !== new URL(viewUrl).origin
        : url !== pathToFileURL(productionPage).href
    )
      throw new Error('不可信的页面地址')
  }
  function handle(channel, operation) {
    ipcMain.handle(channel, async (event, value) => {
      verifySender(event)
      try {
        return { ok: true, value: await operation(value) }
      } catch (error) {
        return {
          ok: false,
          error: { message: error instanceof Error ? error.message : '操作失败，请重试' }
        }
      }
    })
  }
  handle('desktop:status', () => ({ ...bridge.status, setupError, clientVersion }))
  handle('desktop:model:get', () => settings.publicSettings())
  handle('desktop:model:save', async (value) => {
    exactObject(value, ['providerId', 'baseUrl', 'model', 'apiKey', 'outputTokens'])
    if (modelWritePending) throw new Error('模型设置正在保存')
    modelWritePending = true
    try {
      const prepared = settings.prepare(value)
      await bridge.request('configureModel', prepared)
      settings.apply(prepared)
      setupError = ''
      try {
        return await settings.save(prepared)
      } catch {
        // Host 的确定应用回执不能被磁盘失败撤销；公开当前进程的真实配置。
        return settings.publicSettings()
      }
    } finally {
      modelWritePending = false
    }
  })
  handle('desktop:model:test', () => bridge.request('testModel'))
  handle('desktop:external-link', async (value) => {
    const input = exactObject(value, ['url'])
    const url = externalUrl(input.url)
    await (options.openExternal ?? shell.openExternal)(url)
  })
  handle('desktop:workspace', async () => {
    const result = options.chooseWorkspace
      ? await options.chooseWorkspace()
      : await dialog.showOpenDialog(window, {
          title: '选择 Agent 工作目录',
          properties: ['openDirectory', 'createDirectory']
        })
    if (result.canceled || !result.filePaths[0]) return null
    const directory = await fs.realpath(result.filePaths[0])
    selectedDirectories.add(directory)
    return directory
  })
  handle('desktop:sessions', () => bridge.request('listSessions'))
  handle('desktop:session:create', (value) => {
    const input = exactObject(value, ['id', 'title', 'workspace'])
    identity(input.id, '会话标识')
    if (typeof input.title !== 'string' || !input.title.trim() || input.title.length > 160)
      throw new Error('会话名称无效')
    if (!selectedDirectories.has(input.workspace)) throw new Error('请通过目录选择器选择工作目录')
    return bridge.request('createSession', input)
  })
  handle('desktop:session:get', (value) => {
    const input = exactObject(value, ['sessionId'])
    identity(input.sessionId, '会话标识')
    return bridge.request('getSession', input)
  })
  handle('desktop:task:submit', (value) => {
    const input = exactObject(value, ['sessionId', 'text', 'inputId'])
    identity(input.sessionId, '会话标识')
    identity(input.inputId, '输入标识')
    if (
      typeof input.text !== 'string' ||
      !input.text.trim() ||
      input.text.length > MAX_TASK_CHARACTERS
    )
      throw new Error('任务内容为空或过长')
    return bridge.request('submitTask', input)
  })
  handle('desktop:task:cancel', (value) => {
    const input = exactObject(value, ['runId', 'inputId'])
    identity(input.runId, '任务标识')
    identity(input.inputId, '输入标识')
    return bridge.request('cancelTask', input)
  })
  handle('desktop:tool:confirm', (value) => {
    const input = exactObject(value, ['runId', 'waitingId', 'approved', 'inputId'])
    identity(input.runId, '任务标识')
    identity(input.waitingId, '确认标识')
    identity(input.inputId, '输入标识')
    if (typeof input.approved !== 'boolean') throw new Error('确认结果无效')
    return bridge.request('confirmTool', input)
  })

  function createWindow() {
    window = new BrowserWindow({
      ...WINDOW,
      title: '灵知',
      backgroundColor: '#111214',
      show: false,
      autoHideMenuBar: true,
      webPreferences: {
        preload: path.join(__dirname, 'preload.cjs'),
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true,
        webSecurity: true,
        spellcheck: false
      }
    })
    window.once('ready-to-show', () => window.show())
    window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
    window.webContents.on('will-navigate', (event) => event.preventDefault())
    window.webContents.on('will-attach-webview', (event) => event.preventDefault())
    window.webContents.session.setPermissionRequestHandler((_contents, _permission, callback) =>
      callback(false)
    )
    window.on('close', (event) => {
      if (quitting) return
      event.preventDefault()
      window.webContents.send('desktop:event', { event: 'close-requested' })
      void dialog
        .showMessageBox(window, {
          type: 'question',
          buttons: ['继续工作', '退出并停止本地任务'],
          defaultId: 0,
          cancelId: 0,
          title: '退出灵知',
          message: '退出将停止本地任务。未发送的输入不会保存。'
        })
        .then((result) => {
          if (result.response === 1) app.quit()
        })
    })
    if (viewUrl) void window.loadURL(viewUrl)
    else void window.loadFile(productionPage)
  }
  app.on('second-instance', () => {
    if (window) {
      if (window.isMinimized()) window.restore()
      window.focus()
    }
  })
  app.on('before-quit', (event) => {
    if (quitting) return
    event.preventDefault()
    quitting = true
    void bridge.stop().finally(() => app.quit())
  })
  app.on('window-all-closed', () => app.quit())
  createWindow()
  void bridge
    .start()
    .then(async () => {
      const configuration = settings.configuration()
      if (configuration) {
        try {
          await bridge.request('configureModel', configuration)
        } catch (error) {
          setupError = error.message
          send({ event: 'configuration-error', data: { message: setupError } })
        }
      }
    })
    .catch(() => {})
  return { bridge, getWindow: () => window }
}

module.exports = { startApplication, defaultHostLaunch }
if (require.main === module) {
  const dataOption = process.argv.indexOf('--data-dir')
  const dataDirectory = dataOption >= 0 ? process.argv[dataOption + 1] : undefined
  if (dataOption >= 0 && (!dataDirectory || !path.isAbsolute(dataDirectory))) {
    dialog.showErrorBox('灵知 启动失败', '--data-dir 必须指定独立的绝对数据目录。')
    app.exit(1)
  } else
    startApplication({ dataDirectory }).catch(() => {
      dialog.showErrorBox(
        '灵知 启动失败',
        '无法初始化桌面应用，请检查应用数据目录权限后重试。'
      )
      app.exit(1)
    })
}
