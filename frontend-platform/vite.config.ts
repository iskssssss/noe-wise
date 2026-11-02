import { defineConfig } from 'vite'
import { frontendPlugins, runtimeDedupe } from '../tools/frontend/vite-shared'
export default defineConfig({
  plugins: frontendPlugins(),
  resolve: { dedupe: runtimeDedupe },
  build: { manifest: true },
  server: {
    port: 3100,
    proxy: {
      '/api': {
        target: process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
        ws: true
      },
      '/health': {
        target: process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})
