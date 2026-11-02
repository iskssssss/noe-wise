import { defineConfig, devices } from '@playwright/test'

/** Java 夹具已实际监听独立 Vite；本入口不扫描或复用共享端口。 */
const baseURL = process.env.E2E_BASE_URL
const evidence = process.env.E2E_ARTIFACT_DIR
if (!baseURL || !evidence)
  throw new Error('Knowledge browser requires an isolated base URL and evidence directory')
export default defineConfig({
  testDir: './e2e',
  testMatch: 'knowledge-rebuild.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 25_000 },
  outputDir: `${evidence}/playwright-output`,
  reporter: [['list'], ['junit', { outputFile: `${evidence}/playwright-junit.xml` }]],
  use: {
    baseURL,
    ...devices['Desktop Chrome'],
    actionTimeout: 15_000,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'off'
  }
})
