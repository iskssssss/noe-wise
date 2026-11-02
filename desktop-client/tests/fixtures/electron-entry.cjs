const path = require('node:path')
const { startApplication } = require('../../electron/main.cjs')
globalThis.__openedExternalUrls = []

// 受控 Host 只通过真实主进程装配入口注入，不进入安装包或生产环境开关。
void startApplication({
  dataDirectory: process.env.NOEWISE_TEST_DATA,
  hostLaunch: {
    command: process.env.NOEWISE_TEST_NODE,
    args: [path.join(__dirname, 'protocol-host.cjs')],
    cwd: process.env.NOEWISE_TEST_DATA
  },
  chooseWorkspace: async () => ({
    canceled: false,
    filePaths: [process.env.NOEWISE_TEST_WORKSPACE]
  }),
  openExternal: async (url) => {
    globalThis.__openedExternalUrls.push(url)
  }
})
