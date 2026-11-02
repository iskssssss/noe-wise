# 聊天会话回归脚本

Python 3.11+ 标准库工具，通过真实 HTTP API 创建测试数据、提交 JSON 聊天回执、GET 订阅 SSE 并读取持久 trace。模型和 Runtime 使用真实服务；独立 MCP 夹具提供已知查询结果、幂等写入和可控错误。不直接访问数据库，不修改已有智能体或全局模型设置。

`plan` 和 `report` 完全离线；`prepare` 创建和发布资源；只有 `run` 发起聊天模型调用。脚本自身的单元测试不代表真实接口已通过。

行为评测使用固定的 18 类场景 × 3 次重复（54 个样本），包含配置/部署 binding、60 秒结算、阻断原因并存、Judge 校准和发布检查。请先阅读[行为评测指南](BEHAVIOR.md)；运行 CLI 为 `behavior_main.py plan|prepare|run|evaluate|report|judge|collect-late|release-check`，其中 `release-check` 调用 `behavior_release.py` 的 GitHub 来源核验。真实运行只允许连接按[权威启动指南](../../docs/04-指南与运维/01-开发与部署指南.md#53-本地启动)准备好的受控 Windows 环境；该 workflow 不部署业务服务。

## 生成计划

在仓库根目录执行，批次名自行换为唯一值：

```powershell
python -X utf8 tools/chat_regression/main.py plan --root tools/chat_regression/runs/batch-001 --batch batch-001
```

生成 10 个智能体配置、50 个终端用户索引和 1000 个独立会话用例，每一用户与每一智能体有两个会话。用例包含不同提示词、单/多 Skill、超过 20 轮的间隔、跨会话续接、用户和助手隔离、工具错误、重复消息、断流和代码产物。随机种子固定，完整输入和断言写入 `plan.json`，不由被测模型生成预期答案。

前 100 会话覆盖全部场景。会话按用户分组串行执行，用户组之间并行；第一批 20 会话属于同一用户，实际串行运行用于验证链路，不用于声称并发容量。

## 启动环境与夹具

业务服务依照[权威启动指南](../../docs/04-指南与运维/01-开发与部署指南.md#53-本地启动)启动。本脚本不自动重启已有业务服务。

另开终端运行夹具并保持其存活，batch 必须与计划相同：

```powershell
python -X utf8 tools/chat_regression/mcp_fixture.py --batch batch-001 --port 18765
```

仅绑定 `127.0.0.1`。`query_record` 读取预置值；`write_record` 以 `request_key` 去重；`fault_probe` 返回已知错误或至多 10 秒延迟。夹具状态保存在内存，重启会丢失调用审计，不能将重启后的夹具用于旧批次续跑，应创建新批次。可设置 `MCP_FIXTURE_TOKEN`，准备和运行脚本使用相同环境变量，不把令牌写入报告。

## 准备接口资源

凭据仅从环境读取，不写入脚本、命令参数或报告。PowerShell 7 示例：

```powershell
$env:SF_TENANT = 'demo'
$env:SF_ADMIN_USER = 'admin'
$env:SF_ADMIN_PASSWORD = Read-Host '机构账号密码' -MaskInput
$env:SF_PLATFORM_USER = 'platform-admin'
$env:SF_PLATFORM_PASSWORD = Read-Host '平台账号密码' -MaskInput
python -X utf8 tools/chat_regression/main.py prepare --root tools/chat_regression/runs/batch-001 --model-id <已配置的模型ID>
```

默认机构入口为 `http://localhost:3000/api/v1`，平台入口为 `http://localhost:8100/api/v1`。可以显式指定 `--api`、`--platform`、`--fixture`，同一批次后续命令须保持一致。模型 ID 必须显式指定，脚本不自行选择收费模型或修改模型参数。

准备步骤按项目 Controller/DTO 构造请求，经接口创建能力定义、发布版本、部署、挂载、终端用户和智能体。内置代码工具复用平台目录的精确协议，不复制或降低沙箱授权规则。资源 ID、配置和版本保存在 `resources.json`。写请求结果不明确时停止；不能删除检查点后盲目重跑，应先通过平台接口确认该步结果。

多 Skill 协作用例不设置互斥组，输出前缀按用户当前请求选择。互斥组表示拒绝同时激活，不表示自动停用旧 Skill 并切换。资源定义或提示词修改后必须使用新批次经 API 准备新版本，不能把旧 `resources.json` 当作已应用新配置。

## 分批执行

```powershell
python -X utf8 tools/chat_regression/main.py run --root tools/chat_regression/runs/batch-001 --limit 20 --concurrency 2 --max-turns 200 --max-tokens 2000000
```

`--limit` 是累计会话数，后续按 100、300、1000 扩大。完成的用例保留首次结果，不重复执行；失败不会因重跑变成通过。修复后用新批次记录复测结果。

主要限制：

| 参数 | 默认 | 作用 |
|---|---:|---|
| `--concurrency` | 2 | 并发用户组，最多 10 |
| `--turn-timeout` | 540 | 单轮观察截止秒数 |
| `--max-seconds` | 7200 | 本次执行预算秒数 |
| `--max-turns` | 200 | 本次允许发送/恢复的轮数 |
| `--max-tokens` | 2000000 | 本批次已观察的会话 token 上限 |
| `--failure-limit` | 5 | 本次断言失败达到阈值后停止启动新轮次 |

全量执行需要根据第一批的实测成本显式提高预算；示例默认值不会自动消耗到 1000 个会话。限制在轮次边界生效，在途请求及尚未入账用量可能使 token/时间略超上限，不能当作供应商硬限额。费用按接口返回币种和分单位汇总，没有账单的调用记为未知；当前没有按实时金额强制中止的能力。

HTTP 402/429 或明确额度不足信号触发全局停止，写入 `stop.json` 后不自动恢复。供应商错误正文可能被宿主隐藏，因此模型拒绝或任务维护调用失败也先暂停核实；不会将这类通用失败直接声称为已确认额度耗尽。在途请求仍保留结果，停止后不启动新的聊天。

机构拦截器返回精确的 `{"detail":"请求过于频繁，请稍后重试"}` 时，因请求尚未进入 Controller，仅允许按 `Retry-After` 等待并重试一次；其他 429 仍停止。`SF_TEST_REQUEST_INTERVAL` 控制探针请求间隔，默认 0.55 秒；平台已将机构限流提高到 1200 次/分钟时，可使用 0.06 秒减少 trace 采集开销。

发送前记录 `client_message_id`；未知结果恢复时只查询 `/chat/events` 原请求终态，不重新 POST。专门的幂等用例才故意重复 POST 同一消息，并核对 MCP 调用计数和写入事件是否变化。断流用例主动关闭已连接的 SSE 后读取原请求事件。

每批次使用互斥文件防止同时运行。进程异常退出留下 `execution.lock` 时，先确认原进程已结束，再移除该锁文件。未知建会话/资源创建结果需要人工核对，脚本不会猜测创建是否成功。

定向复验可重复传入 `--case-id u10-operator-0 --case-id u10-artifact-0`，此时覆盖 `--limit` 的前缀选择，冻结计划和报告分母保持完整。改变夹具契约后使用新批次创建资源，不修改旧批次证据。写入夹具的 `request_key` 仅提供业务去重，MCP 部署声明为 `NON_IDEMPOTENT_WRITE`、`idempotent=false`，不授予 Runtime 崩溃后自动重放能力。

## 报告及证据

```powershell
python -X utf8 tools/chat_regression/main.py report --root tools/chat_regression/runs/batch-001
```

- `report.html`：场景统计、失败列表、延迟分位数、token 和金额。
- `summary.json`、`results.jsonl`：机器可读汇总，明确区分通过、失败、中断、前置未通过及未运行。
- `cases/<case>/state.json`：会话 ID、消息 ID、精确请求，可用于定位复现。
- `turn-XX.sse.jsonl`：逐事件接收时间及正文。
- `turn-XX.trace.json`：分页读取的持久 trace 及内容引用。
- `turn-XX.json`：该轮断言、最终回复、工具/技能次数、夹具审计和产物校验。

普通回复要求终态为 completed 且有持久 `run.completed`，并通过场景事实断言。资料维护类用例显式允许 `waiting_hitl`，此时必须存在 `run.suspended`、匹配当前 Run 的追问与恢复引用，追问文本仍接受事实断言；下一轮通过 `hitl_response` 回答并恢复原 Run。续答只评估上一轮 trace 游标之后的新事件，恢复查询不接受旧输入的终态。工具错误场景要求观察到错误结果并告知用户；不会仅凭 HTTP 200 判成功。产物下载使用票据，不向独立下载端口转发机构 Bearer，当前仅接受本地 loopback 下载地址。

延迟只统计实际完整观察的请求，恢复等待不计入分位数；没有正文片段则首输出时间为空。各阶段细节保留在 trace，不把无法归因的时间推测为模型或数据库耗时。当前断言检查客观事实、格式、隔离标记与副作用，不自动评判所有开放式回答的质量；不覆盖浏览器 UI 或跨租户权限。

默认输出目录已忽略，避免把聊天正文、运行数据和下载票据提交到 Git。测试资源保留用于复核，不自动删除用户或能力；结束夹具只停止本批次拥有的进程。

## 脚本自身验证

```powershell
python -X utf8 -m unittest discover -s tools/chat_regression -p 'test_*.py' -v
```

这些测试覆盖固定矩阵、SSE 解析、错误终态、trace 分页、断点不重发、预算、批次互斥、报告分母和本地 MCP 协议。它们不调用模型，也不创建平台或机构资源。


## HTTP 契约定向探针

`contract_probe.py` 复用上述环境凭据，默认只验证机构/平台登录、成功/错误信封、认证、SSE 握手失败、无效文件票据和 204。响应仍是旧契约时立即失败，不继续创建业务资源。

```powershell
python -X utf8 -B tools/chat_regression/contract_probe.py --report .runtime-logs/contract-governance/http.json
```

显式添加 `--agent-id <目标Agent> --end-user-id <目标用户>` 后，才创建一份独立测试 Session 并发起两次模型请求，验证同键回执/异体冲突、GET 断线恢复及取消竞争。使用有权限的测试身份和只读 Agent；脚本不修改模型配置、不删除数据、不重发未知请求。报告记录新建 Session ID，清理由既有保留规范处理。

添加 `--artifact-session-id <已有会话>` 可读取其 ready 产物和最终成果，验证元数据鉴权、票据签发、二进制下载摘要和篡改票据拒绝。文件正文与票据不写入报告，也不修改或删除已有产物。通过 `--api`、`--platform` 指向网关可以复用同一批检查。

探针覆盖取消意图受理及已结束 Run 胜出分支；没有观察到取消完成时不得把 cancelling 写为 cancelled。工具未知结果、存储部分发布失败、跨租户/渠道隔离和进程崩溃恢复需要相应受控夹具或身份，报告会明确列为未验证，不能用接口可达替代这些场景。
