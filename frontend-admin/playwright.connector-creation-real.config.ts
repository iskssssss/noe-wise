import { defineConfig, devices } from '@playwright/test'
const baseURL = process.env.E2E_BASE_URL
const evidence = process.env.E2E_ARTIFACT_DIR
if (!baseURL || !evidence) throw Error('Connector recovery requires an isolated fixture')
export default defineConfig({
  testDir: './e2e',
  testMatch: 'connector-creation-real.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 90_000,
  outputDir: `${evidence}/playwright-output`,
  reporter: [['list'], ['junit', { outputFile: `${evidence}/playwright-junit.xml` }]],
  use: {
    baseURL,
    ...devices['Desktop Chrome'],
    channel: 'chrome',
    trace: 'off',
    video: 'off',
    actionTimeout: 20_000
  }
})
