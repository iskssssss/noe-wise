import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL
const evidence = process.env.E2E_ARTIFACT_DIR
if (!baseURL || !evidence)
  throw new Error(
    'Real trajectory browser requires an isolated frontend URL and evidence directory'
  )

/** 后端与 Vite 由隔离夹具唯一持有；浏览器不启动服务、不重发业务输入。 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'trajectory-real.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 20_000 },
  outputDir: `${evidence}/playwright-output`,
  reporter: [['list'], ['junit', { outputFile: `${evidence}/playwright-junit.xml` }]],
  use: {
    baseURL,
    ...devices['Desktop Chrome'],
    actionTimeout: 15_000,
    screenshot: 'only-on-failure',
    trace: 'off',
    video: 'off'
  }
})
