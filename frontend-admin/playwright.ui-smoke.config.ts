import { defineConfig, devices } from '@playwright/test'
const artifactDir = process.env.UI_SMOKE_ARTIFACT_DIR ?? 'test-results/ui-smoke'
const baseURL = 'http://127.0.0.1:23440'
const sizes = [
  { width: 1440, height: 900 },
  { width: 1280, height: 800 },
  { width: 1024, height: 768 }
]
export default defineConfig({
  testDir: './e2e',
  testMatch: ['ui-smoke.spec.ts', 'ui-navigation.spec.ts'],
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
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
    ...sizes.flatMap((size) =>
      (['light', 'dark'] as const).map((colorScheme) => ({
        name: `${size.width}-${colorScheme}`,
        use: { ...devices['Desktop Chrome'], viewport: size, colorScheme },
        testMatch:
          size.width === 1440 && colorScheme === 'light'
            ? ['mock-ui.spec.ts', 'ui-smoke.spec.ts', 'ui-navigation.spec.ts']
            : ['ui-smoke.spec.ts', 'ui-navigation.spec.ts']
      }))
    ),
    ...(['light', 'dark'] as const).map((colorScheme) => ({
      name: `200percent-equivalent-${colorScheme}`,
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 640, height: 400 },
        deviceScaleFactor: 2,
        colorScheme
      }
    }))
  ],
  webServer: {
    command: 'node ../node_modules/vite/bin/vite.js --host 127.0.0.1 --port 23440 --strictPort',
    url: `${baseURL}/login`,
    reuseExistingServer: true,
    timeout: 60_000
  }
})
