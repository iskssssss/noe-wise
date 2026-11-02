import { defineConfig, devices } from '@playwright/test'

const frontendPort = process.env.E2E_FRONTEND_PORT ?? '3000'
const baseURL = process.env.E2E_BASE_URL ?? `http://127.0.0.1:${frontendPort}`
const artifactDir = process.env.E2E_ARTIFACT_DIR ?? 'test-results/runtime-e2e'

export default defineConfig({
  testDir: './e2e',
  testIgnore: ['**/trajectory-real.spec.ts', '**/trajectory-persistent-real.spec.ts'],
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  outputDir: artifactDir,
  reporter: [['list'], ['junit', { outputFile: `${artifactDir}/playwright-junit.xml` }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    ...devices['Desktop Chrome']
  },
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: `node ./node_modules/vite/bin/vite.js --host 127.0.0.1 --port ${frontendPort} --strictPort`,
        url: `http://127.0.0.1:${frontendPort}`,
        reuseExistingServer: false,
        timeout: 120_000
      }
})
