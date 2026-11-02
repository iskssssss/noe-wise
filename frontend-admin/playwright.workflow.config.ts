import { defineConfig, devices } from '@playwright/test'

const artifactDir = process.env.WORKFLOW_E2E_ARTIFACT_DIR ?? 'test-results/workflow-dataflow'
const baseURL = 'http://127.0.0.1:23440'

/** 本 lane 独占 Vite；API 全由 context 夹具拦截，不连接共享后端。 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'workflow-dataflow.spec.ts',
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
    ...(['light', 'dark'] as const).map((colorScheme) => ({
      name: `narrow-${colorScheme}`,
      use: { ...devices['Desktop Chrome'], viewport: { width: 390, height: 844 }, colorScheme }
    }))
  ],
  webServer: {
    command: 'node ../node_modules/vite/bin/vite.js --host 127.0.0.1 --port 23440 --strictPort',
    url: `${baseURL}/login`,
    reuseExistingServer: false,
    timeout: 60_000
  }
})
