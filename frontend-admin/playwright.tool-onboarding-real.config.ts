import { defineConfig, devices } from '@playwright/test'
const baseURL = process.env.E2E_BASE_URL
const evidence = process.env.E2E_ARTIFACT_DIR
if (!baseURL || !evidence) throw Error('工具接入验收需要隔离夹具')
export default defineConfig({
  testDir: './e2e',
  testMatch: 'tool-onboarding-real.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 120_000,
  outputDir: `${evidence}/playwright-output`,
  reporter: 'list',
  use: { baseURL, ...devices['Desktop Chrome'], channel: 'chrome', actionTimeout: 20_000 }
})
