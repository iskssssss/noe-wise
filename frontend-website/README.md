# 灵知官网

独立的中文多页静态官网。正文、导航和页脚在构建时写入 HTML，关闭 JavaScript 后仍可阅读；脚本增强移动导航、能力目录收展与阅读位置提示。本站无需模型、数据库或业务后端。

## 本地开发

从仓库根目录执行，使用根目录声明的 Node 与 pnpm 版本：

```powershell
corepack pnpm install --frozen-lockfile
corepack pnpm --filter frontend-website dev
```

默认访问 `http://127.0.0.1:3300/`。端口被占用时启动失败，不会自动切换端口或终止其他服务；可通过 Vite 参数指定其他端口。

## 验证与构建

```powershell
corepack pnpm --filter frontend-website typecheck
corepack pnpm --filter frontend-website build
corepack pnpm --filter frontend-website check
corepack pnpm --filter frontend-website preview
```

构建输出在本目录 `dist/`。`check` 核验七个 HTML 页面及内部资源、链接和锚点；不能替代真实浏览器的交互与视觉检查。开发和预览共用 3300 端口，只启动其中一个。

## 内容与结构

页面为首页、产品能力、应用场景、机构治理、开发者、关于灵知，以及静态 404。各页 HTML 是正文来源；`vite.config.ts` 在开发响应及生产构建中注入静态导航、页脚和元数据。`src/main.ts` 维护移动导航与能力目录交互，`src/style.css` 维护独立样式。

首页保留定位、会话端布局示意、产品组成关系与阅读目录。界面示意使用静态 HTML，不是可操作产品或真实执行记录。产品页完整列出对话与任务、知识与记忆、能力与成果、协作与人工参与、接入与运行、建设与运营，以及后续方向；机构和群场景不作为独立宣传卖点。原首页 `/#demo` 落到阅读目录，`/scenarios/#demo` 落到场景说明，已移除模拟执行与步骤切换。

产品定位以[产品概述](../docs/01-产品与需求/02-产品概述.md)为准，能力覆盖对照[PRD](../docs/01-产品与需求/01-PRD.md)、[后续阶段需求](../harness-design/04-后续阶段需求扩展-P1-P3.md)及[未实现主题](../docs/02-架构与设计/11-未实现功能清单.md)。桌面范围见[桌面原型](../desktop-client/README.md)。产品页先展示分类总览，再提供完整目录；桌面目录标记当前阅读位置，手机目录可展开、收起并跳转。关闭脚本后保留原生目录与全部正文。产品目录是能力说明，不是全量功能已通过验收的声明；规划项和原型就近标注。官网只显示中文品牌“灵知”，现有标识复制至 `public/brand.svg`，辅助文字使用中文。

## 静态部署

将 `dist/` 的内容部署到站点根路径，启用目录 `index.html`，未知路径使用 `404.html` 并返回 HTTP 404。不要把所有路径回退到首页；无需 SPA 路由回退。官网只支持根路径部署，子目录部署不在当前范围。

在构建前配置实际站点根地址（不含子路径、查询参数或凭据）：

```powershell
$env:SITE_ORIGIN = 'https://你的实际域名'
corepack pnpm --filter frontend-website build
corepack pnpm --filter frontend-website check
Remove-Item Env:SITE_ORIGIN
```

配置后生成 canonical、分享地址、sitemap.xml 和 robots.txt。未配置时不生成虚构绝对地址或 sitemap。本地分享图为 SVG；具体社交平台是否接受 SVG 由其抓取规则决定，正式分享推广前可按渠道要求提供 PNG 图片。

首次发布时需核实正式域名、页面阶段说明及托管环境的 404 配置。当前没有公开试用链接、销售联系方式、注册、付费、分析埋点或咨询提交功能；主按钮引导至站内产品目录与开发者说明。
