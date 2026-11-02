import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// 会话端自行持有构建配置，不读取后台的插件、别名或共享主题。
export default defineConfig({
  plugins: [vue()],
  optimizeDeps: { include: ['vue', 'lucide-vue-next', 'marked', 'dompurify'] },
  server: {
    port: 3200,
    strictPort: true,
    proxy: { '/api': { target: 'http://localhost:8000', changeOrigin: true } }
  },
  test: { environment: 'jsdom', restoreMocks: true }
})
