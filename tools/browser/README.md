# 本地 BrowserSkill 高级场景验收

固定官方版本为 `3f10983c09fcd443c7420830ab984c9d409954bf`。服务、官方扩展和个人归属沿[BrowserSkill 设计](../../docs/02-架构与设计/18-BrowserSkill远程浏览器接入.md)，启动顺序沿[开发与部署指南](../../docs/04-指南与运维/01-开发与部署指南.md)。这些脚本不启动 Browser service，不安装扩展，不操作日常浏览器 profile。

## 授权持久化与协议回归

`verify-authorization-lifecycle.py` 使用固定官方 daemon 的真实配对、续期及撤销协议。先按启动指南准备独立 Browser 服务及专属持久卷；私有配置文件只保存 `endpoint` 与 `token`，其中地址必须为 `http://127.0.0.1:<端口>`。配置不入仓库。脚本生成新的测试 owner 并确认其设备集合为空，不接受已有个人设备作为测试对象。

```powershell
python -X utf8 -B tools/browser/verify-authorization-lifecycle.py --service-config <私有服务配置.json> --evidence <全新证据目录>
```

该入口检查双授权并发配对、并发续期、其他 owner 不可读、撤销其中一份而保留另一份、已撤销凭据被拒绝及剩余授权继续续期。`receipt.json` 保存控制 `request_id`、已完成检查及精确清理结果，不保存设备令牌或配对链接。失败或未知结果不自动重复控制调用；清理后读取权威设备集合，未清空则验收失败。保留 Browser 服务个人目录的 `authorization-audit`，用 `request_id` 关联机构审计与服务控制事实。

这组证据仅证明真实官方授权协议与服务审计，不涉及 Chrome/Edge 页面动作，也不能独自归因某次历史授权丢失。设备集合变化记录只表明两次观察之间发生变化；调查应结合相应控制意图、结果、操作者和原始时间线，不能把时间相邻的借用或帮助调用当作删除原因。

## 个人浏览器与网络场景

`local-network-fixture.mjs` 在两个 loopback 端口提供自有页面、GET/POST 请求回显及只读 `/__facts`。每次启动分配独立 fixture run，保留服务端实际请求日志。`local-network-plan.json` 包含 mock/耗尽、modify 正文与测试头、block/disable/enable/remove、同源 replay 去重及跨源拒绝。网络规则字段按固定官方 [debugging.md](https://github.com/Tencent/BrowserSkill/blob/3f10983c09fcd443c7420830ab984c9d409954bf/crates/bsk-cli/skill/references/debugging.md) 核对。

执行前准备专用测试 owner、独立持久卷和官方扩展测试 profile；用户在产品界面完成配对并明确选择设备。owner 文件只包含选择器：`tenant`、`endUser`、`browser`、`session`、`run`，均为字符串，不保存服务 token。服务 token 由执行进程的 `BROWSER_ACCEPTANCE_SERVICE_TOKEN` 读取，脚本不读取根 `.env`，不打印 token。不要把普通业务 Session 或用户日常 profile 当作测试对象。

在 Windows 上先启动独立 profile。以下二选一，`<绝对测试profile目录>` 必须是本次验收专用的新目录；浏览器程序安装位置以本机实际安装为准。不要添加忽略 TLS 证书错误的启动参数。

```powershell
& "$env:ProgramFiles\Google\Chrome\Application\chrome.exe" --user-data-dir="<绝对测试profile目录>" --no-first-run
& "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe" --user-data-dir="<绝对测试profile目录>" --no-first-run
```

固定官方[安装说明](https://github.com/Tencent/BrowserSkill/blob/3f10983c09fcd443c7420830ab984c9d409954bf/AGENT_INSTALL.md#4-connect-the-browser-extension)给出的安装入口是 [Chrome Web Store](https://chromewebstore.google.com/detail/hhcmgoofomhgciiibhipgmgkgnoenaoi) 和 [Edge Add-ons](https://microsoftedge.microsoft.com/addons/detail/browserskill/emacgiaaaiojkkpkddmmdfhmokgmnikg)。商店安装入口本身不保证扩展构建来自上述 commit；验收前记录实际扩展版本，并取得与固定 CLI 配套的官方扩展构建证据。没有该证据时只能报告版本匹配未验证，不能把商店当前版本称为固定 commit 的扩展。

按启动指南确认服务就绪后，在机构端 `http://localhost:3000` 进入测试会话，或在 `http://localhost:3200` 进入已配置且授权的独立渠道会话。输入框下展开“个人浏览器”，点击“配对新设备”；产品动态返回一次性配对链接，没有可预填的固定配对 URL。在专用 profile 的扩展弹窗选择 **Remote connection**，粘贴该链接并保存，再回产品点击“刷新设备与回执”，确认目标设备“已连接”后点击“选择此浏览器”。配对链接含一次性授权信息，不写入 owner 文件、执行证据或文档。

固定上游的扩展本地实例 ID 与 Browser 服务返回的 remote `browser_id` 是两个不同身份，不能用扩展弹窗中的本地 ID 直接执行远程调用。配对后核对产品新增的 device grant、`device_id`、remote `browser_id` 和授权到期时间；再由用户在该专用 profile 显式断开扩展连接，确认服务端对应设备离线，恢复连接后确认同一设备上线。将这组映射记入本次证据，owner 文件的 `browser` 和所有远程调用都使用已核实的 remote ID。切换 Chrome/Edge 后重新核对当前选择与设备映射；不能因本地 ID 不匹配或设备暂时离线而盲切其他 profile。此核验属于配对、选择和断连恢复流程，本地实例名或相同扩展版本不能代替归属证据。

机构端的配对归属来自有权访问的真实 `session_id`；独立渠道端来自当前渠道已注册的 `client_ref`。产品接口是 `/api/v1/chat/browser/devices`、`/pair` 和 `/select`，归属由服务端解析，不允许用任意 endUser 替代产品授权。上述页面端口来自启动指南，实际运行仍须检查就绪状态。浏览器所在主机必须信任 Browser 服务的 WSS TLS 证书，并能访问配对链接公布的 WSS 地址；服务配置、证书信任和设备连接尚未完成时，不运行高级场景计划。

```powershell
node tools/browser/local-network-fixture.mjs <明确的夹具证据目录> 18581 18582
node tools/browser/run-local-network-acceptance.mjs --plan tools/browser/local-network-plan.json --owner <隔离owner.json> --evidence <全新的执行证据目录> --service http://127.0.0.1:<隔离Browser服务端口>/ --isolated-owner confirmed
```

若端口变更，同时修改计划的两个 origin。每次执行使用新启动夹具和新 journal；已有 journal 会拒绝重新提交。调用超时只读取原 invocation 回执，unknown 不重放；脚本只按准确 task 停止自己的任务，清理失败另记失败。规则 ID 按明确名称唯一捕获，replay 使用实际 request ID。服务端请求次数、请求正文/测试头和浏览器结果共同形成断言。`execution.json` 明确声明 `productSessionVerified:false` 与 `humanInteractionVerified:false`，内部服务场景不能当作完整产品 Session 或人工交互验收。

脚本和计划存在不代表真实动作已经通过。集中验证夹具的命令为：

```powershell
node --test tools/browser/local-network-fixture.test.mjs
node --check tools/browser/run-local-network-acceptance.mjs
```

人工场景仍需用户在专用 Chrome/Edge profile 操作：打开自有页面，从真实 `tab-list` 获取普通窗口的 tab ID，确认 `tab-borrow` 后再归还；分别验证拒绝与确认超时；开启 `record-start` 时明确指定自有页面，实际点击和填写非秘密值，然后准确停止录制。trace 必须包含非零用户动作，单纯开始/停止且零步骤不能代表用户动作录制通过。人工帮助必须按固定官方流程进行，不能由脚本代替用户确认。

`tab-borrow` 的人工确认弹窗需要用户普通窗口当前激活页是可注入的 HTTP(S) 页面。发起借用前，让用户切回专用 profile 中的自有测试页面并保持该页激活；目标 tab ID 与当前窗口均以实际 `tab-list` 和用户确认核对。若当前激活页为 `chrome://`、`edge://`、扩展调试页或其他不可注入页面，上游会返回 `unsupported`，这不是用户拒绝借用。先将该失败按页面前置不满足保留，用户切回自有 HTTP(S) 页后再执行确认流程；不要把 `unsupported` 归入拒绝或超时验收证据。

需要隔离的真实产品授权入口时，可使用 [BrowserHumanAcceptanceHarnessTest](../../backend-java/skillforge-institution-service/src/test/java/com/skillforge/institution/integration/BrowserHumanAcceptanceHarnessTest.java) 的 `serveIsolatedApplicationUntilExplicitHumanFinishReceipt` 方法。它沿用完整迁移 PostgreSQL/Redis 夹具，在随机后端端口创建测试机构、渠道、用户与 Session；关闭 Runtime workers，不调用模型，也不启动用户浏览器。先启动并核对本批隔离 Browser 服务，然后在执行进程提供以下环境变量：

- `SKILLFORGE_BROWSER_HUMAN_ACCEPTANCE=1`：显式启用人工驻留测试。
- `BROWSER_HUMAN_SERVICE_URL`：隔离 Browser 服务地址。
- `BROWSER_HUMAN_SERVICE_TOKEN`：该隔离服务的 token，仅从进程环境注入。
- `BROWSER_HUMAN_EVIDENCE_DIR`：本次独立证据目录；已有 `ready.private.json` 或 `finish.json` 时拒绝复用。

在 `backend-java/` 运行：

```powershell
.\gradlew.bat '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=GBK' --max-workers=1 :skillforge-institution-service:test --tests com.skillforge.institution.integration.BrowserHumanAcceptanceHarnessTest.serveIsolatedApplicationUntilExplicitHumanFinishReceipt
```

服务就绪后，`ready.private.json` 提供本批随机后端地址与测试登录/渠道配置；该文件含凭据，仅供本地验收进程使用，不进入公开报告。用户按上述方法配对、选择设备及执行人工场景后，再写入有效 JSON 的 `finish.json` 结束驻留。Harness 最长等待 60 分钟，并在 `harness-result.json` 区分收到回执、等待未完成和中断；它只检查回执存在且可解析，产品判定固定为 `NOT_ASSERTED_BY_HARNESS`。因此 Gradle 通过或收到 finish receipt 均不能证明所有人工场景通过，仍须逐项核对设备归属、授权、动作与服务回执；到期后的 `HUMAN_NOT_COMPLETED` 也不能计为人工验收成功。

本地可信 WSS 可完成这些本地场景；公网 DNS/TLS、生产代理、跨设备网络和生产发布继续需要目标环境的独立验收。
