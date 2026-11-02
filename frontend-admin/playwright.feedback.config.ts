import { defineConfig, devices } from '@playwright/test'

const baseURL = 'http://127.0.0.1:23440'
const artifactDir = process.env.FEEDBACK_E2E_ARTIFACT_DIR ?? 'test-results/feedback'

/** 独占 Vite 与浏览器上下文；仅测试公开反馈和真实页面的错误归属。 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'feedback.spec.ts',
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
    command: 'node ../node_modules/vite/bin/vite.js --host 127.0.0.1 --port 23440 --strictPort',
    url: `${baseURL}/login`,
    reuseExistingServer: false,
    timeout: 60_000
  }
})
