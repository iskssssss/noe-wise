import { readFile, access } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import assert from 'node:assert/strict'

const directory = resolve('dist')
const pages = ['', 'product', 'scenarios', 'governance', 'developers', 'about']
const inputs = [...pages.map((page) => `${page ? `${page}/` : ''}index.html`), '404.html']
let checkedLinks = 0
for (const file of inputs) {
  const html = await readFile(join(directory, file), 'utf8')
  assert.match(html, /<html lang="zh-CN">/, `${file} 缺少中文语言标识`)
  assert.match(html, /<h1[\s>]/, `${file} 缺少主标题`)
  assert.match(html, /<nav[^>]+aria-label="主导航"/, `${file} 缺少静态导航`)
  assert.match(html, /property="og:description" content="[^"]+"/, `${file} 缺少分享描述`)
  assert.doesNotMatch(
    html,
    /NoeWise|<!--(?:site-header|site-footer|demo)-->|data-scenario|data-step|data-demo|查看场景演示|把下一步，交给灵知/i,
    `${file} 品牌、模板或旧演示未清理`
  )
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1])
  assert.equal(new Set(ids).size, ids.length, `${file} 存在重复 ID`)
  for (const [, reference] of html.matchAll(/(?:href|src)="([^"]+)"/g)) {
    if (/^https?:\/\//.test(reference)) continue
    assert.ok(reference !== '#', `${file} 存在空链接`)
    const url = new URL(reference, `https://local.invalid/${file}`)
    let target = decodeURIComponent(url.pathname).slice(1)
    if (target.endsWith('/') || !target) target += 'index.html'
    await access(join(directory, target))
    if (url.hash) {
      const targetHtml = await readFile(join(directory, target), 'utf8')
      assert.ok(
        targetHtml.includes(`id="${decodeURIComponent(url.hash.slice(1))}"`),
        `${file} 的锚点 ${reference} 不存在`
      )
    }
    checkedLinks++
  }
  if (file !== '404.html' && process.env.SITE_ORIGIN) {
    const origin = new URL(process.env.SITE_ORIGIN).origin
    const path = file.replace('index.html', '')
    assert.ok(html.includes(`rel="canonical" href="${origin}/${path}"`))
  }
}
const home = await readFile(join(directory, 'index.html'), 'utf8')
assert.match(home, /<h1>灵知<\/h1>/, '首页应以中文品牌作为主标题')
const homeMain = home.match(/<main[\s\S]*?<\/main>/)?.[0] ?? ''
const homeCharacters = (homeMain.replace(/<[^>]*>/g, '').match(/[\u4e00-\u9fff]/g) ?? []).length
assert.ok(homeCharacters <= 300, `首页正文过长：${homeCharacters} 个汉字`)
for (const file of ['index.html', 'scenarios/index.html']) {
  assert.ok(
    (await readFile(join(directory, file), 'utf8')).includes('id="demo"'),
    `${file} 缺少旧锚点的阅读目标`
  )
}
if (process.env.SITE_ORIGIN) {
  const sitemap = await readFile(join(directory, 'sitemap.xml'), 'utf8')
  assert.equal([...sitemap.matchAll(/<loc>/g)].length, 6)
  assert.ok(!sitemap.includes('404.html'))
  await access(join(directory, 'robots.txt'))
}
console.log(
  `通过：7 个静态页面，${checkedLinks} 个内部资源与锚点；首页 ${homeCharacters} 个汉字，中文品牌、静态正文、导航及分享元数据完整，旧演示已移除。`
)
