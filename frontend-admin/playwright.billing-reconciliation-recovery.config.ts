import { defineConfig, devices } from '@playwright/test'
import { resolve } from 'node:path'

const port = process.env.E2E_FRONTEND_PORT!
const evidence = process.env.E2E_ARTIFACT_DIR!
const quoted = (value: string) => `"${value.replaceAll('\\', '/')}"`

export default defineConfig({
  testDir: './e2e',
  testMatch: 'billing-reconciliation-recovery-real.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 25_000 },
  outputDir: evidence,
  reporter: [['list'], ['junit', { outputFile: `${evidence}/playwright-junit.xml` }]],
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    ...devices['Desktop Chrome'],
    trace: 'off',
    video: 'off',
    screenshot: 'only-on-failure',
    actionTimeout: 20_000,
    navigationTimeout: 45_000
  },
  webServer: {
    command: `${quoted(process.execPath)} ${quoted(resolve('node_modules/vite/bin/vite.js'))} --host 127.0.0.1 --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}/login`,
    reuseExistingServer: false,
    timeout: 90_000
  }
})
