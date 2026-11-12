import { defineConfig, devices } from '@playwright/test'
const baseURL = process.env.E2E_BASE_URL
const evidence = process.env.E2E_ARTIFACT_DIR
if (!baseURL || !evidence) throw Error('系统技能验收需要隔离服务与证据目录')
export default defineConfig({
  testDir: './e2e',
  testMatch: 'system-agent-skill-activation-real.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 150_000,
  expect: { timeout: 15_000 },
  outputDir: `${evidence}/playwright-output`,
  reporter: [['list'], ['junit', { outputFile: `${evidence}/playwright-junit.xml` }]],
  use: {
    baseURL,
    ...devices['Desktop Chrome'],
    channel: 'chrome',
    trace: 'off',
    video: 'off',
    screenshot: 'off',
    actionTimeout: 20_000
  }
})
