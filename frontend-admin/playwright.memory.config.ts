import { defineConfig, devices } from '@playwright/test'
import { resolve } from 'node:path'

const baseURL = process.env.E2E_MEMORY_BASE_URL
const evidence = process.env.E2E_ARTIFACT_DIR
if (!baseURL || !evidence) throw new Error('记忆浏览器验收必须由隔离后端测试提供地址和证据目录')

// Vite 由 Java 夹具实际绑定 port 0 并拥有其清理；Playwright 只连接本次监听地址。
export default defineConfig({
  testDir: './e2e',
  testMatch: 'memory-governance.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 20_000 },
  outputDir: resolve(evidence, 'playwright-output'),
  reporter: [
    ['list'],
    ['junit', { outputFile: resolve(evidence, 'playwright-junit.xml') }],
    ['json', { outputFile: resolve(evidence, 'playwright-results.json') }]
  ],
  use: {
    baseURL,
    ...devices['Desktop Chrome'],
    trace: 'retain-on-failure',
    video: 'off',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 45_000
  }
})
