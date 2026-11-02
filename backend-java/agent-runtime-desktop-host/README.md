# 桌面本地宿主

本模块是独立桌面原型的 Java 21 装配根，复用正式 `ExecutionCoordinator`、`AgentExecutionProgram`、Core 与模型 Provider。会话、执行状态和预算通过 [SQLite 适配器](../agent-runtime-adapter-sqlite/README.md)持久化；不需要 Spring Boot、机构服务、PostgreSQL、Redis 或 Docker。桌面界面见 [desktop-client](../../desktop-client/README.md)。

## 提示词归属与冻结

本模块自有的桌面行为正文归自身 UTF-8 classpath 资源；不依赖平台 `skillforge-prompts`。共享动作、声明与执行语义仍由 Runtime 提供，`SessionPrompt.Type` 分类不决定资源存放模块。归属依据见 [Harness 提示词模块](../../harness-design/03-上下文视图与执行优化.md#11-具名提示词模块)。

固定资源来源与摘要、实际运行策略、最终正文及其 SHA-256 随新 Run 冻结；旧 Run 保留原冻结配置，新 Run 承接业务历史时剔除旧 SYSTEM。完整设计与证据边界见[独立桌面宿主设计](../../docs/02-架构与设计/01-技术架构.md#11-独立桌面宿主原型)，固定正文见 [desktop-system.md](src/main/resources/com/skillforge/runtime/desktop/prompts/desktop-system.md)，读取与来源校验由 [DesktopPrompts](src/main/java/com/skillforge/runtime/desktop/DesktopPrompts.java)拥有，配置装配由 [DesktopConfiguration](src/main/java/com/skillforge/runtime/desktop/DesktopConfiguration.java)维护。本说明不代替资源打包、原配置恢复及多轮真实请求验证。

## 启动与验证

在 `backend-java` 目录使用仓库 Wrapper：

```powershell
.\gradlew.bat :agent-runtime-desktop-host:installDist
.\agent-runtime-desktop-host\build\install\agent-runtime-desktop-host\bin\agent-runtime-desktop-host.bat --data-dir .\data\desktop
.\gradlew.bat :agent-runtime-desktop-host:test
```

应用入口是 `com.skillforge.runtime.desktop.DesktopHostMain`。桌面进程通过私有标准输入输出与该进程连接；手动启动时，它会等待输入协议，不提供 HTTP 端口。

测试使用临时 SQLite、本地受控 HTTP Provider 与本机工具，不需要配置真实 API Key，也不代表付费云端模型或 macOS 真机已验收。

## 桌面内部协议

标准输入和输出都是 UTF-8 JSONL，每行一个对象。标准输出只承载协议；凭据与用户正文不进入宿主错误日志。

请求为 `{ "id": "请求身份", "method": "方法", "params": {} }`；成功返回 `{ "id": "请求身份", "result": ... }`，失败返回 `{ "id": "请求身份", "error": { "code": "稳定分类", "message": "可公开说明" } }`。方法及参数入口由 [DesktopProtocol](src/main/java/com/skillforge/runtime/desktop/DesktopProtocol.java)维护。

| 方法 | 行为 |
|---|---|
| `configureModel` | 配置 `providerId`、`baseUrl`、`model`、`apiKey`；供应商为 `deepseek` 或 `custom`，后者使用正式自定义 OpenAI 兼容 Provider；可选正整数 `outputTokens` 指定单次输出上限，省略时采用 Host 默认，仅影响新受理 Run |
| `testModel` | 用户显式触发一次真实模型连接测试，会消耗供应商调用额度，不自动重试 |
| `listSessions`、`createSession`、`getSession` | 返回会话数组、创建的会话对象或完整会话投影 |
| `submitTask` | 以 `sessionId`、`text`、`inputId` 受理任务，返回 `runId` 和是否新建的 `created` |
| `cancelTask` | 以 `runId`、`inputId` 取消原任务，保留已发生副作用和原调用结果 |
| `confirmTool` | 用 `runId`、`waitingId`、`approved`、`inputId` 回复精确命令等待；拒绝不会执行命令 |
| `shutdown` | 停止受理，取消在途工作并关闭所属资源 |

启动完成发出 `{ "event": "ready", "data": { "protocolVersion": 1 } }`。状态变化发出 `{ "event": "state", "sessionId": "..." }` 变更通知；前端通过 `getSession` 读取 SQLite 权威投影。观察器只检查活动 Run 修订，不为已完成会话定时构建历史投影。模型配置冻结目录中已验证的流式能力（configuration.v5；旧格式只读展示），已验证支持流式的服务协议使用同一次 `ModelPort.stream` 派发，正文增量发出 `{ "event": "modelPreview", "sessionId": "...", "runId": "...", "attemptId": "...", "delta": "..." }`；推理及工具参数不进入公开预览。预览明确未完成，最终消息与取消、失败、UNKNOWN 仍只采用保存后的状态；重连读取不会重发模型。模型连接测试为独立单在途请求，保持原请求 id，不阻塞控制读取；关闭会取消测试并停止迟到输出。`hostError` 和 `fatal` 事件包含可公开的错误分类及说明；事件不代表请求响应，前端仍需按请求 `id` 配对。

`getSession` 包含 `session`、`messages`、`runs`、`events`、`pendingConfirmations`。`messages` 展示已受理输入与已提交最终回答；`runs` 包含 `id/status/phase/active/createdAt/updatedAt`；`events` 展示已保存 Runtime 过程事实及工具调用投影。工具投影含 `type=tool`、`runId`、`toolName`、`arguments` 和存在时的结构化 `result`，文件结果可含 `diff`，命令结果可含 `stdout/stderr/exitCode`。命令确认包含 `runId/waitingId/command/workspace`；`waitingId` 是宿主生成的不透明身份。

展示投影限制在 1 MiB UTF-8，保留最近消息、过程和工具结果，单字段只展示有界摘录。`projectionTruncated` 与各条目的 `fieldsTruncated` 明确标记省略，原 SQLite 材料和回执文件保留完整内容。该裁剪只作用于桌面展示，不改变正式 Runtime 结果或模型输入。已正常完成但退出码非零的命令以真实观察回注模型，包含 `status=FAILED`、`isError=true`、退出码和输出；界面仍将该命令显示为失败，不能用工具外层返回观察推定测试通过。

展示窗口与完整数量由执行数据库的同一只读快照提供，只读取窗口所需的输入、精确终态事件及工具材料；`omittedMessages/omittedEvents/omittedRuns` 同时包含窗口省略和最终字节裁剪。旧命令等待确认单独读取，仍可查看与处理，不受最近 Run 窗口限制。模型历史与恢复继续读取完整持久上下文。

## 执行与数据边界

同一数据目录只允许一个 Host 持有操作系统文件锁。不同会话受有限活动槽约束，同一会话的顶层任务有序执行；已保存等待和未知结果会阻止该会话的后续任务越过原任务。容量、步数、超时等默认值由 [DesktopPolicy](src/main/java/com/skillforge/runtime/desktop/DesktopPolicy.java)定义，受理时冻结到任务配置。

API Key 只保留在 Host 内存中，由桌面主进程从系统保护的凭据存储恢复后重新配置；不会写入 SQLite、任务配置、协议响应或命令环境。冻结的历史任务使用其原模型与端点，重新配置其他模型不会重写已受理任务。

数据目录保存 `runtime.db`、`sessions.db` 与不可变 `tool-receipts/` 材料。SQLite 拥有执行状态；工具回执文件保留原输出，特别是失败及 UNKNOWN 的命令输出，不作为新的派发或成功依据。修改的工作文件仍保留在用户选择的目录中。

命令初次调用返回正式 `ToolOutcome.InputRequired`，由 Runtime 保存等待并释放活动槽；接受精确回复后，在同一逻辑 Action 的新 Attempt 中继续原调用。中断无结果或 UNKNOWN 不自动重发。关闭桌面期间已保存的待确认命令保留等待，重启后仍需用户明确确认。

文件工具限制到工作目录并检查读取版本。本机命令不是操作系统沙箱，确认界面必须展示完整命令和工作目录。首版没有浏览器、MCP/Skills 管理、多 Agent、交互式终端或跨设备同步能力。

## 本地工具与进程归属

工具定义与参数由 [DesktopTools](src/main/java/com/skillforge/runtime/desktop/DesktopTools.java)提供，模型通过同一入口调用实际文件系统和本机进程。文件操作使用会话选定的目录；命令需要用户精确确认，仍然拥有当前系统用户的本机权限。

| 工具 | 用途与限制 |
|---|---|
| `list_files` | 列举目录直接子项，最多返回 200 项并标记截断 |
| `read_file` | 读取不超过 256 KiB 的 UTF-8 文本并记录观察版本 |
| `search_files` | 在文件或目录中搜索字面文本；默认返回行内容及包含首个命中的完整字符起点 `readColumn`，可结合行号作为 `read_file` 的 `offset`、`column` 回读；列从 1 开始按 UTF-16 代码单元计数。`output=matches` 仅返回路径、行号和本页匹配行数。按游标继续并报告跳过和深度限制，计数不是全部结果 |
| `write_file` | 创建文件或替换已读取的文件，父目录须存在，结果不超过 256 KiB |
| `edit_file` | 在已读取的文件中替换唯一匹配片段，返回实际修改前后内容及有界 diff 预览 |
| `read_context_reference` | 按当前 Run 中已保存的上下文材料事件分页读取原文；不重新执行原工具 |
| `run_command` | 在选定目录使用本机 Shell 执行，每次独立进程、无交互 stdin、无跨调用终端状态 |

新 Run 的模型目录包含上下文原文读取工具。超过模型上下文预算时，已有 Session 机制可以将较早的完整消息组替换为引用；模型按事件身份分页读取当前 Run 保存的原始材料，不重放原来的文件或命令操作。旧 Run 的工具目录仍按受理时的配置冻结，不自动增加该工具。

搜索游标属于当前桌面会话并只能使用一次，五分钟空闲、宿主重启或关闭后失效；失效须从头开始新搜索。分页保持单次文件读取的内容，目录遍历不构成快照，遍历期间的新增、删除或改名可能导致遗漏或重复。`hasMore` 表示仍有待遍历内容；即使已经没有下一页，累计跳过文件或深度限制仍表示结果不完整。已冻结的旧运行不新增工具参数，持久回执与 UNKNOWN 不因搜索续读而重发。

文件工具校验规范化后的真实路径，包括符号链接及 Windows junction 目标；拒绝目录越界、Windows 备用数据流和驱动器限定路径。已有文件须先读取；同一进程不同会话通过共享锁原子检查观察版本并替换，发生版本冲突时须重新读取。该控制不等于对其他编辑器或已批准 Shell 的文件系统沙箱。

命令默认超时 120 秒，可按工具参数在 1–600 秒内指定。stdout、stderr 分别并发采集，最多保存各自 64 KiB，超出时继续排空管道并标记截断。工具按照 UTF-8 解码，结果包含 `outputEncoding`、`stdoutDecodingErrors` 和 `stderrDecodingErrors`；原生程序应自行配置 UTF-8 输出。Windows 的本机程序可能使用独立于 Shell 的旧代码页，例如 JDK 21 的 `stdout.encoding` 不一定跟随 `file.encoding`；不能通过猜测代码页或替换乱码宣称原始输出已正确还原。

Windows 使用固定 PowerShell 监督脚本和独立命令数据。业务 Shell 先以挂起状态创建，加入启用 `KILL_ON_JOB_CLOSE` 的 Windows Job Object 后才恢复执行；监督进程在正常退出、取消或宿主进程退出时清理 Job 成员，并确认活动进程归零后生成绑定本次操作的回执。PowerShell cmdlet 错误通过 `ErrorActionPreference=Stop` 与异常边界表达，原生程序退出码单独保留。缺失或不匹配的清理回执返回 UNKNOWN。

macOS/POSIX 使用随 JAR 分发的 [Node 监督脚本](src/main/resources/desktop/command-supervisor.cjs)，先建立独立进程组再执行 Shell；正常退出或取消后向所属组发送终止信号，必要时强制终止，并确认进程组消失后返回回执。Electron 通过可信启动环境 `NOEWISE_NODE_EXECUTABLE` 提供随包可执行文件，监督进程采用 `ELECTRON_RUN_AS_NODE=1`；单独运行 Java Host 时可以提供该路径或使用 `PATH` 中的 Node。业务命令重新使用基础环境白名单，不继承 Node 模式标志或模型凭据。进程组管理不构成阻止恶意程序自行创建新 session 的操作系统沙箱；无法确认清理时仍保留 UNKNOWN。

Windows 已通过实际本机文件与进程测试，包括正常退出和取消后的后台后代清理、junction 越界、双流输出上限与 PowerShell 错误。macOS/POSIX 实现及测试入口已提供，当前没有 macOS 真机运行证据；Windows 上的 Node 语法检查和平台测试跳过不替代该验证。Windows 符号链接创建权限不足时对应测试会明确跳过，junction 用例单独验证同类真实路径边界。
