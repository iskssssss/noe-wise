import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { runtimeDedupe } from '../tools/frontend/vite-shared'
export default defineConfig({
  plugins: [vue()],
  resolve: { dedupe: runtimeDedupe },
  test: { server: { deps: { inline: ['element-plus'] } }, environment: 'jsdom', restoreMocks: true }
})
