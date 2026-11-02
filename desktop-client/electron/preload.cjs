const { contextBridge, ipcRenderer } = require('electron')
let clickedExternalLink = null

// 只允许本次真实点击的正文链接；页面脚本调用与合成 click 不获得打开资格。
window.addEventListener(
  'click',
  (event) => {
    clickedExternalLink = null
    if (!event.isTrusted || !(event.target instanceof Element)) return
    const anchor = event.target.closest('.markdown-message a')
    if (anchor instanceof HTMLAnchorElement) clickedExternalLink = anchor.href
  },
  { capture: true }
)

// 每个方法固定映射 IPC，不向页面暴露 ipcRenderer 或通用通道。
contextBridge.exposeInMainWorld(
  'desktop',
  Object.freeze({
    getStatus: () => ipcRenderer.invoke('desktop:status'),
    getModel: () => ipcRenderer.invoke('desktop:model:get'),
    saveModel: (settings) => ipcRenderer.invoke('desktop:model:save', settings),
    testModel: () => ipcRenderer.invoke('desktop:model:test'),
    openExternal: (url) => {
      if (clickedExternalLink !== url)
        return Promise.resolve({ ok: false, error: { message: '请点击正文中的外部链接打开' } })
      clickedExternalLink = null
      return ipcRenderer.invoke('desktop:external-link', { url })
    },
    chooseWorkspace: () => ipcRenderer.invoke('desktop:workspace'),
    listSessions: () => ipcRenderer.invoke('desktop:sessions'),
    createSession: (input) => ipcRenderer.invoke('desktop:session:create', input),
    getSession: (sessionId) => ipcRenderer.invoke('desktop:session:get', { sessionId }),
    submitTask: (input) => ipcRenderer.invoke('desktop:task:submit', input),
    cancelTask: (input) => ipcRenderer.invoke('desktop:task:cancel', input),
    confirmTool: (input) => ipcRenderer.invoke('desktop:tool:confirm', input),
    onEvent: (listener) => {
      const handler = (_event, value) => listener(value)
      ipcRenderer.on('desktop:event', handler)
      return () => ipcRenderer.removeListener('desktop:event', handler)
    }
  })
)
