import { defineConfig } from 'vitest/config'
import { frontendPlugins, runtimeDedupe } from '../tools/frontend/vite-shared'
export default defineConfig({
  plugins: frontendPlugins(false),
  resolve: { dedupe: runtimeDedupe },
  test: {
    server: { deps: { inline: ['element-plus'] } },
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
    setupFiles: ['./src/test/setup.ts']
  }
})
