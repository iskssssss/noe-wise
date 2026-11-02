# 灵知 BrowserSkill 适配扩展

本目录以固定 [BrowserSkill 3f10983](https://github.com/Tencent/BrowserSkill/tree/3f10983c09fcd443c7420830ab984c9d409954bf) 为构建输入，只叠加网页到扩展的可信站点配对入口。目标契约见[远程浏览器接入设计 §9](../../../docs/02-架构与设计/18-BrowserSkill远程浏览器接入.md#9-可信站点与扩展交接)。官方执行、授权交换、设备凭据存储、续期与撤销源码保持原样。扩展名称为“灵知 · BrowserSkill”，适配包 `manifest.version` 为 `1.0.1`，`version_name` 为 `1.0.1 (BrowserSkill 0.3.2, 3f10983)`；上游扩展协议版本 `__BSK_EXT_VERSION__` 仍为 `0.3.2`，不混用这两个版本。

## 构建与产物

需要 Python 3、Node.js 22.12 或以上及 npm/npx。脚本通过 `npx pnpm@10.17.0` 使用上游声明的固定包管理器；首次构建需要访问 GitHub codeload 和 npm registry。依赖严格使用上游 `pnpm-lock.yaml`，不更新锁文件。

在仓库根目录执行：

```powershell
python -X utf8 -B tools/browser/extension-adapter/build.py
```

也可先下载固定归档，再显式提供路径：

```powershell
python -X utf8 -B tools/browser/extension-adapter/build.py --archive C:/Downloads/BrowserSkill-3f10983.zip
```

归档 SHA-256 必须为 `f453ec7e9fbf6182f7897b2b55977936124d43b38c46bfc1d1044e7cc01bd59f`，不匹配即停止。归档解压到本目录 `.build/`，补丁锚点必须唯一匹配；构建脚本依次生成 WXT 配置、执行 TypeScript 检查、桥接与官方授权代表测试，再实际构建两个浏览器的 MV3 包。`--prepare-only` 只校验、解压和叠加源码，不安装依赖或执行构建。仅在 overlay 未变且已有对应测试证据、当前差量只涉及清单或打包时，可使用 `--skip-tests` 复用测试；TypeScript 与两个浏览器构建仍执行。

产物位于忽略目录 `dist/`：

- `noewise-browser-chrome-1.0.1.zip`
- `noewise-browser-edge-1.0.1.zip`
- `build-metadata.json`：上游提交、归档/锁文件/overlay 摘要、Node/pnpm 版本、ZIP 大小与摘要。

ZIP 根目录包含 `manifest.json`、`LICENSE.BrowserSkill` 和 `NOEWISE-BUILD.json`，不包含源码工作区、测试 profile 或配对材料。ZIP 使用固定文件顺序和时间戳；相同依赖、工具链与 overlay 输入可重建并核对摘要。Chrome 和 Edge 当前均使用相同 Chromium MV3 能力，产物内容可以相同。

## 开发者模式加载

1. 将对应 ZIP 解压到一个固定目录；选择含 `manifest.json` 的那一层，不能直接选择 ZIP。
2. Chrome 打开 `chrome://extensions`，或 Edge 打开 `edge://extensions`，启用开发者模式，选择“加载已解压的扩展”。
3. 确认名称“灵知 · BrowserSkill”，将扩展固定到工具栏。开发验证使用独立 profile；不要把测试授权导入日常 profile。
4. 打开灵知会话页面，再点击扩展图标。在“灵知 会话站点”区域核对完整 origin，点击“信任当前站点”。信任范围精确到 scheme、host、port；只允许 HTTPS 或 loopback HTTP。
5. 回到会话页面发起连接。扩展图标出现 `!` 后打开 popup，核对“请求站点”“目标服务”和身份摘要（单一 `session_id` 显示为“会话标识”），点击“确认站点与服务并配对”，或拒绝。目标服务不显示 credential fragment。
6. 网页收到 `paired` 仅表示官方配对调用完成；服务端仍负责核对准确配对关联、设备在线与原等待任务续跑。关闭或刷新网页不会替代服务端恢复判断。

每次配对都重新确认，不因之前信任过站点而自动替换远程连接。身份 scope 或目标服务变化也需要新的明确确认。取消站点信任只停止该站点的后续桥接交接；已有设备授权的撤销仍使用官方设备管理入口。官方 popup 的 Remote 设置仍可手动粘贴配对串。本目录不发布商店、不打包或读取用户日常 profile。

### 更新已加载的扩展

`1.0.1` 将会话站点区域接入上游 Button、Badge 和主题样式，分开展示信任状态与配对确认，修复窄窗口按钮挤在一起及长地址布局。桥接公开版本与 manifest 同为 `1.0.1`，授权流程保持原样。

已有开发者模式扩展时，先结束正在执行的浏览器任务，再把新 ZIP 解压覆盖到原加载目录，在扩展管理页对原扩展点击“重新加载”；确认版本为 `1.0.1` 后刷新会话页。保留原加载目录，避免另装一份扩展造成重复连接。

## 网页桥接

仅顶层页面、同 window 且同 origin 的 `window.postMessage` 可用。请求 source 为 `noewise-page`、version 为 `1`，request_id 为 1–128 位字母/数字/下划线/连字符。只接受 `probe` 与 `pair`；其他操作、未知字段和 iframe 请求被拒绝。

```javascript
window.postMessage({
  source: 'noewise-page', version: 1, request_id: 'probe_1',
  operation: 'probe', payload: {}
}, location.origin);
```

`pair` payload 必须包含 `pairing_url`（至多 4096 字符）、非空 `identity_scope`（至多 256 字符）。连接等待场景另外提供 `connection_request_id`（同 request_id 格式），独立设备配置场景省略该字段，以真实 `request_id` 去重，不创建虚假连接请求。identity_scope 是不透明、非凭据身份摘要，仅用于显示与逐次确认，不能获得授权。配对串沿官方规则只接受 WSS，或 loopback WS，凭据位于 URL fragment。

响应始终为安全投影：

```json
{"source":"noewise-extension","version":1,"request_id":"probe_1","ok":true,"version_info":{"adapter_version":"1.0.1","upstream_commit":"3f10983c09fcd443c7420830ab984c9d409954bf"}}
```

配对响应包含安全 `code`，不返回配对串、设备 ID 或 token。未信任站点的 probe 返回 `ok:false`、`code:site_not_trusted` 并携带公开 `version_info`，便于识别已安装扩展并提示信任；信任后 probe 才成功，pair 还必须在 popup 中逐次确认。配对材料只临时保存在后台内存，popup 只读取去凭据摘要；设备密钥由官方 `updateRemoteConnection` 写入官方凭据存储。

常见 code：`site_not_trusted`、`confirmation_busy`、`confirmation_expired`、`page_changed`、`user_rejected`、`paired`、`pairing_outcome_unknown`、`request_already_submitted`。确认窗口为 120 秒；网页可等待 125 秒，或超时后继续只读查询服务端连接请求。`pairing_outcome_unknown`、页面关闭、service worker 退出或网页超时均不能当作确定失败自动重发。每个 tab 同时只保留一个待确认请求，确认前再次核对 documentId 和 origin。

## 验证边界

构建内测试覆盖可信 origin、封闭信封、未信任/iframe/假 popup 拒绝、确认前不调用官方授权、不同身份逐次确认、页面导航失效、请求去重与并发确认，以及上游授权交换/续期/URL校验回归。构建、TypeScript 和 ZIP 检查不等于真实个人浏览器、WSS、产品连接等待与自动续跑全部通过；真实组合证据归所属实施记录。开发者模式包未经过 Chrome Web Store 或 Edge Add-ons 审核。
