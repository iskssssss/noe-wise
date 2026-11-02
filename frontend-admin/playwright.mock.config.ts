import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.MOCK_E2E_BASE_URL ?? 'http://127.0.0.1:3440'
const port = Number(new URL(baseURL).port || 3440)
const artifactDir = process.env.MOCK_E2E_ARTIFACT_DIR ?? 'test-results/mock-e2e'

/** 仅拦截当前浏览器 context 的 API；不连接共享后端或接管已有 Vite。 */
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
    command: `corepack pnpm@10.33.4 --filter frontend-admin ${process.env.MOCK_E2E_PREVIEW === '1' ? 'preview' : 'dev'} --host 127.0.0.1 --port ${port} --strictPort`,
    url: `${baseURL}/login`,
    reuseExistingServer: false,
    timeout: 120_000
  }
})
