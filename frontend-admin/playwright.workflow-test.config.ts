import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.WORKFLOW_TEST_E2E_BASE_URL ?? 'http://127.0.0.1:23446'
const port = Number(new URL(baseURL).port || 23446)
const artifactDir =
  process.env.WORKFLOW_TEST_E2E_ARTIFACT_DIR ?? 'test-results/workflow-test-session'

/** root 单独运行此 lane；独占 Vite，业务请求均由浏览器 context 的严格 mock 拦截。 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'workflow-test-session.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  outputDir: artifactDir,
  reporter: [['list'], ['junit', { outputFile: `${artifactDir}/junit.xml` }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 10_000
  },
  projects: [
    {
      name: 'desktop-light',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 1000 },
        colorScheme: 'light'
      }
    },
    {
      name: 'narrow-dark',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 390, height: 844 },
        colorScheme: 'dark'
      }
    }
  ],
  webServer: {
    command: `node ../node_modules/vite/bin/vite.js --host 127.0.0.1 --port ${port} --strictPort`,
    url: `${baseURL}/login`,
    reuseExistingServer: false,
    timeout: 60_000
  }
})
