import { defineConfig, loadEnv } from 'vite'
import { fileURLToPath } from 'node:url'
import { relative } from 'node:path'

const root = fileURLToPath(new URL('.', import.meta.url))
const pages = ['', 'product', 'scenarios', 'governance', 'developers', 'about']
const navigation = ['首页', '产品能力', '应用场景', '机构治理', '开发者', '关于灵知']
const escapeAttribute = (value: string) =>
  value
    .replaceAll('&', '&amp;')
    .replaceAll('"', '&quot;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, root, 'SITE_')
  let origin = ''
  if (env.SITE_ORIGIN) {
    const url = new URL(env.SITE_ORIGIN)
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.pathname !== '/' ||
      url.search ||
      url.hash
    ) {
      throw new Error('SITE_ORIGIN 必须是无路径、凭据、查询参数的 HTTP(S) 站点地址')
    }
    origin = url.origin
  }
  return {
    appType: 'mpa',
    plugins: [
      {
        name: 'lingzhi-static-shell',
        transformIndexHtml: {
          order: 'pre',
          handler(html, context) {
            const sourcePath = relative(root, context.filename).replaceAll('\\', '/')
            const page = pages.find((name) => sourcePath === `${name ? `${name}/` : ''}index.html`)
            const links = pages
              .map(
                (name, index) =>
                  `<a href="/${name ? `${name}/` : ''}"${page === name ? ' aria-current="page"' : ''}>${navigation[index]}</a>`
              )
              .join('')
            const header = `<a class="skip-link" href="#main">跳至主要内容</a><header class="site-header"><div class="container header-inner"><a class="brand" href="/" aria-label="灵知首页"><img src="/brand.svg" alt="" width="32" height="32"><span>灵知</span></a><button class="menu-toggle" type="button" aria-expanded="false" aria-controls="navigation" hidden>打开导航</button><nav id="navigation" aria-label="主导航">${links}</nav></div></header>`
            const footer = `<footer class="site-footer"><div class="container"><div class="footer-top"><a class="brand" href="/"><img src="/brand.svg" alt="" width="28" height="28">灵知</a><a href="/about/#stage">预发布阶段 ↗</a></div><nav class="footer-links" aria-label="页脚导航">${links}</nav><div class="footer-bottom"><span>© ${new Date().getFullYear()} 灵知</span><span>面向机构的智能体平台</span></div></div></footer>`
            const description =
              html.match(/<meta\s+name="description"\s+content="([^"]+)"/)?.[1] ?? ''
            const title = html.match(/<title>(.*?)<\/title>/)?.[1] ?? '灵知'
            const path = page === undefined ? '/404.html' : `/${page ? `${page}/` : ''}`
            const metadata = `<meta property="og:type" content="website"><meta property="og:locale" content="zh_CN"><meta property="og:site_name" content="灵知"><meta property="og:title" content="${title}"><meta property="og:description" content="${description}">${origin && page !== undefined ? `<link rel="canonical" href="${escapeAttribute(origin + path)}"><meta property="og:url" content="${escapeAttribute(origin + path)}"><meta property="og:image" content="${escapeAttribute(origin)}/share.svg">` : ''}`
            return html
              .replace('<!--site-header-->', header)
              .replace('<!--site-footer-->', footer)
              .replace('</head>', `${metadata}</head>`)
          }
        },
        generateBundle() {
          if (!origin) return
          this.emitFile({
            type: 'asset',
            fileName: 'sitemap.xml',
            source: `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${pages.map((page) => `<url><loc>${escapeAttribute(origin)}/${page ? `${page}/` : ''}</loc></url>`).join('')}</urlset>`
          })
          this.emitFile({
            type: 'asset',
            fileName: 'robots.txt',
            source: `User-agent: *\nAllow: /\nSitemap: ${origin}/sitemap.xml\n`
          })
        }
      }
    ],
    build: {
      rollupOptions: {
        input: [
          ...pages.map((page) => `${root}${page ? `${page}/` : ''}index.html`),
          `${root}404.html`
        ]
      }
    }
  }
})
