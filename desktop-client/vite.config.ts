import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  base: './',
  plugins: [vue()],
  server: { host: '127.0.0.1', port: 0 },
  build: { target: 'es2022' }
})
