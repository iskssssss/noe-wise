import { defineConfig, devices } from '@playwright/test'
import { resolve } from 'node:path'

const adminPort = process.env.E2E_FRONTEND_PORT!
const quotedPath = (value: string) => `"${value.replaceAll('\\', '/')}"`
const node = quotedPath(process.execPath)
const adminVite = quotedPath(resolve('node_modules/vite/bin/vite.js'))
const evidence = process.env.E2E_ARTIFACT_DIR!

// 只使用已安装 Node/Vite；不得通过 pnpm/npx 安装依赖或连接共享前端进程。
export default defineConfig({
  testDir: './e2e',
  testMatch: 'conversation-task-recovery-real.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 25_000 },
  outputDir: evidence,
  reporter: [['list'], ['junit', { outputFile: `${evidence}/playwright-junit.xml` }]],
  use: {
    baseURL: `http://127.0.0.1:${adminPort}`,
    ...devices['Desktop Chrome'],
    trace: 'off',
    video: 'off',
    screenshot: 'only-on-failure',
    actionTimeout: 20_000,
    navigationTimeout: 45_000
  },
  webServer: [
    {
      command: `${node} ${adminVite} --host 127.0.0.1 --port ${adminPort} --strictPort`,
      url: `http://127.0.0.1:${adminPort}/login`,
      reuseExistingServer: false,
      timeout: 90_000
    }
  ]
})
