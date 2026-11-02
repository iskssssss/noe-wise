import { defineConfig, devices } from '@playwright/test'

const artifactDir = process.env.MOCK_E2E_ARTIFACT_DIR ?? 'test-results/mock-e2e'
const baseURL = process.env.MOCK_E2E_BASE_URL ?? 'http://127.0.0.1:3441'
const port = Number(new URL(baseURL).port || 3441)
const preview = process.env.MOCK_E2E_PREVIEW === '1'

export default defineConfig({
  testDir: './e2e',
  testMatch: ['login-feedback.spec.ts', 'mock-ui.spec.ts', 'trajectory-ui.spec.ts'],
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  outputDir: artifactDir,
  reporter: [['list'], ['junit', { outputFile: `${artifactDir}/junit.xml` }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 20_000
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `corepack pnpm@10.33.4 --filter frontend-platform ${preview ? 'preview' : 'dev'} --host 127.0.0.1 --port ${port} --strictPort`,
    url: `${baseURL}/login`,
    reuseExistingServer: false,
    timeout: 120_000
  }
})
