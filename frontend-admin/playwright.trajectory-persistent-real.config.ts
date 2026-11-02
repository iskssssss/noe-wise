import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL
const evidence = process.env.E2E_ARTIFACT_DIR
if (!baseURL || !evidence)
  throw new Error(
    'Persistent trajectory browser requires an explicit frontend URL and evidence directory'
  )

/** 只读取已生成的真实轨迹，不启动服务或发送聊天；登录凭据不写入录制。 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'trajectory-persistent-real.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 20_000 },
  outputDir: `${evidence}/playwright-output`,
  reporter: [['list'], ['junit', { outputFile: `${evidence}/playwright-junit.xml` }]],
  use: {
    baseURL,
    ...devices['Desktop Chrome'],
    viewport: { width: 1440, height: 1000 },
    actionTimeout: 15_000,
    screenshot: 'off',
    trace: 'off',
    video: 'off'
  }
})
