const { startApplication } = require('../../electron/main.cjs')

// 使用生产默认 Java 启动链，仅隔离测试数据和系统目录选择。
void startApplication({
  dataDirectory: process.env.NOEWISE_TEST_DATA,
  chooseWorkspace: async () => ({
    canceled: false,
    filePaths: [process.env.NOEWISE_TEST_WORKSPACE]
  })
})
