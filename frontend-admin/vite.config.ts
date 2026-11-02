import { defineConfig, loadEnv } from 'vite'
import { frontendPlugins, runtimeDedupe } from '../tools/frontend/vite-shared'
import { artifactPreviewCsp, artifactPreviewOrigin } from './src/config/artifact-preview-policy.js'
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const previewOrigin = artifactPreviewOrigin(env.VITE_ARTIFACT_PREVIEW_ORIGIN)
  const backend = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:8000'
  return {
    plugins: [
      ...frontendPlugins(),
      {
        name: 'artifact-preview-frame-csp',
        transformIndexHtml: (html: string) =>
          html.replace('%ARTIFACT_PREVIEW_CSP%', artifactPreviewCsp(previewOrigin))
      }
    ],
    resolve: { dedupe: runtimeDedupe },
    build: { manifest: true },
    server: {
      port: 3000,
      proxy: {
        '/api': { target: backend, changeOrigin: true, ws: true },
        '/health': { target: backend, changeOrigin: true }
      }
    }
  }
})
