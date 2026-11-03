# 灵知 桌面原型

Electron + Vue 桌面窗口管理本地 Java Agent Host，通过用户配置的云端 LLM 完成文件与命令任务。应用不依赖灵知服务端、PostgreSQL、Redis 或 Docker。

## 开发启动

需要仓库规定的 Node.js、Corepack，以及 Java 21 JDK。先在仓库根目录安装依赖，再构建本地宿主：

```powershell
corepack pnpm install --frozen-lockfile
Set-Location backend-java
.\gradlew.bat :agent-runtime-desktop-host:installDist
Set-Location ..
corepack pnpm --dir desktop-client dev
```

开发时从 `JAVA_HOME` 查找 Java，未设置时使用 `PATH` 中的 Java。安装包内置 Java 21，无需用户单独安装。

1. 打开“模型设置”，选择 DeepSeek 或自定义 OpenAI Chat Completions 兼容服务，确认或填写 API 根地址与模型名，输入 API Key，保存配置。可选填写“单次最大输出 token”；留空由宿主采用默认值，客户端不另补默认。
2. 可显式点击“测试当前配置”；该操作会调用模型，可能产生费用。
3. 通过系统目录选择器选择项目目录，创建会话并输入任务。
4. Agent 的文件修改直接写入所选目录；命令执行前显示完整命令与目录，由用户允许或拒绝。
5. 在“执行记录”查看工具结果、stdout、stderr 与退出码，在“文件变更”查看 diff。

助手回复支持常规 Markdown 标题、列表、表格、行内代码、代码块与链接。原始 HTML 按文本显示，不执行脚本、不加载远程图片；不包含图表或 Mermaid 渲染。外部 HTTP(S) 链接仅在用户真实点击后经主进程校验，交给默认浏览器打开，应用窗口不导航到外站。

“单次最大输出 token”必须是正整数，是单次生成上限而不是整任务预算；供应商也可能限制更低。配置应用后用于新受理任务，已经受理任务的冻结配置不变。

配置在 Host 确认后即用于本次运行；若随后写入磁盘失败，设置页明确提示“已应用、未保存”，重新打开仍显示本次运行的实际配置，可排除目录权限等问题后再次保存。重启时使用磁盘中的配置，未持久化的密钥需要重新输入。

模型输出达到长度上限时，会保留失败状态和后续操作提示：被截断的这次内容不用于文件修改或命令执行，不会自动重试。该任务此前的工具调用或文件修改仍然保留；核对执行记录后，可按文件或功能拆成小任务，或调整模型设置中的单次上限，再明确提交下一步。

API Key 通过 Electron `safeStorage` 使用操作系统加密；系统加密不可用时仅保留在当前进程内存中，退出后需重新输入。会话和本地 Host 数据保存在 Electron 的应用用户数据目录。任务上下文会发送到所配置的模型供应商。

可在启动可执行程序时传入 `--data-dir <绝对路径>`，选择独立的本地应用数据目录。验收或试用时可用该入口隔离已有配置；目录由桌面主进程管理，不能通过页面参数切换。

## 界面与交互

桌面客户端沿用独立会话端的中性色、青绿强调及 8pt 间距，在本模块维护样式和控件，不导入后台组件。正文为 14px，辅助说明至少 12px；右上角可切换深浅主题，偏好在本机恢复。

宽窗口展示会话导航、对话及执行详情；可用宽度小于 1180 CSS px 时，执行记录与文件变更通过原生模态面板打开；小于 760 CSS px 时，会话导航也按需打开。面板支持 Esc、焦点约束与返回触发入口，详情标签支持方向键及 Home/End。缩放与窗口调整保留草稿和已展开内容，工作目录可点击展开完整路径。

浏览历史时，新回复不会将阅读位置拉到末尾；可使用“回到最新消息”，切换会话后恢复各自阅读位置。读取失败在所属区域保留说明及重新读取入口，任务拒绝或断连保留输入；发送禁用时在输入区说明缺少的条件。设置表单保留未保存关闭保护与字段说明。

任务取消与本地资源退出分别展示：已取消但执行槽仍在退出时保留“正在清理本地任务”，不重复发送取消；Host 释放活动槽后移除清理提示。

## 构建安装包

首先完成宿主 `installDist`。在 Windows x64 环境中，将 `JAVA_HOME` 指向 Windows x64 Java 21 JDK：

```powershell
corepack pnpm --dir desktop-client package:win
```

未签名的 Windows NSIS 安装包输出到 `release/`。`package:dir` 生成可直接运行的目录产物。打包脚本只读当前宿主安装产物，并使用 `jlink` 创建随包运行时；每次构建替换本模块拥有的 `staging/` 生成目录，拒绝符号链接或重解析点指向模块外的目录。

已有版本正在运行时，修复包使用独立输出目录，保留旧安装包和旧 `win-unpacked/`。例如当前 `0.1.5` 版本完成对应 Host 的 `installDist` 后：

```powershell
corepack pnpm --dir desktop-client build
corepack pnpm --dir desktop-client stage x64
corepack pnpm --dir desktop-client exec electron-builder --win nsis --x64 --config.directories.output=release/0.1.5
```

该命令将新安装包和可运行目录放在 `release/0.1.5/`，不覆盖原 `release/win-unpacked/`。切换版本前先由用户结束旧客户端；不要同时使用同一数据目录启动两个版本。

macOS 分别在对应平台与架构的机器上使用匹配的 Java 21 JDK 构建：

```sh
corepack pnpm --dir desktop-client package:mac:arm64
# Intel Mac 使用：
corepack pnpm --dir desktop-client package:mac:x64
```

macOS 构建入口不代表已完成真机验证。当前配置不进行代码签名、公证或自动更新；公开分发需补齐这些交付步骤。

## 验证

```powershell
corepack pnpm --dir desktop-client test
corepack pnpm --dir desktop-client build
corepack pnpm --dir desktop-client test:electron
corepack pnpm --dir desktop-client test:rendering
corepack pnpm --dir desktop-client test:ui
corepack pnpm --dir desktop-client test:model-settings
# 完成 Java Host installDist 后，验证真实组合链：
corepack pnpm --dir desktop-client test:combination
```

Node 测试验证 stdio 生命周期、业务拒绝、断连待定请求和模型密钥边界。Electron 测试实际启动桌面应用与测试专用协议 Host，覆盖配置、目录选择、任务、命令确认、结果、差异、窗口布局和断连草稿保留。`test:combination` 使用生产默认 Java 启动链、真实 SQLite 和本机工具，接入端口由操作系统分配的受控 HTTP 模型，核对文件实际修改与本机测试退出码。上述测试均不调用付费模型，不证明真实云端供应商已验收。截图输出到本模块忽略的 `test-results/`。

## 实现边界

- 主进程管理 Host 与凭据，通过私有 stdio JSONL 通道通信；标准输出仅承载协议。
- 页面启用 `contextIsolation` 与沙箱，关闭 Node 集成；preload 只暴露固定用例，不暴露任意 IPC、文件系统或 Shell。
- 会话状态由 Host 查询投影提供；传输失败保留任务输入与提交身份，不自动重发写操作。
- 工具与恢复语义归本地 Runtime，桌面界面不另建 Agent 循环或审批状态机。
- 首版不含交互式终端、浏览器自动化、插件管理、多 Agent、云端任务托管或跨设备同步。

Runtime 边界见 [Harness 架构](../harness-design/02-微内核插件架构实现思路.md)，宿主与本地工具见 [桌面 Host](../backend-java/agent-runtime-desktop-host/README.md)。
