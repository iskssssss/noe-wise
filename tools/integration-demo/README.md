# 通用业务联调 Demo

Demo 展示真实公开用量、客户应收、独立钱包余额、结算指令、共享群管理、模拟消息和媒体回执。灵知 的机构接入、执行授权、Session、Run、Connector、群治理与用量导出仍走真实 API；钱包、外部发送与媒体供应商属于模拟业务。客户销售价不使用供应商成本。

计价和可靠待发记录由 [SettlementAdapter](../integration-scenario/settlement.py) 维护，钱包由[独立服务](../integration-wallet/README.md)维护自己的 SQLite。Demo 只通过 HTTP 查询余额与结算，不访问钱包库。MCP、WebSocket 与模拟业务继续复用 [business_service.py](../integration-scenario/business_service.py)。GI-11 新增群管理消费者，复用正式群 API 及已有独立发送器，不另建群控制、记忆或执行引擎；群身份、消息与准确等待的准备流程见[场景说明](../integration-scenario/README.md)。GI-12 已完成必要本地组合及真实浏览器等待回复，实际证据见阶段记录；本地模拟不证明真实外部送达。

## 准备与启动

先通过[接入指南](../../docs/02-架构与设计/04-接口与事件契约.md)准备真实机构。复制[钱包配置](../integration-wallet/config.example.json)为本地配置，替换真实 Tenant ID、明确初始余额/币种/透支限额和服务账户范围；示例 7–10 只是占位。钱包和 Demo 进程环境均提供 `SKILLFORGE_WALLET_SERVICE_TOKEN`，值由操作者安全设置，不写配置文件或命令行。

```powershell
python -X utf8 -B tools/integration-wallet/wallet_service.py --config tools/integration-wallet/config.local.json --state tools/integration-wallet/.state/wallet.sqlite3 --port 8099
```

把 [billing.example.json](billing.example.json) 复制为 Demo 本地配置。它包含钱包地址、凭据环境变量名称、输入/输出 Token 和工具调用三项销售价、价格版本及账户映射。账户可按真实机构 ID 或场景别名配置；Demo 经当前钱包账户接口核验真实 Tenant，不允许由请求正文创建或改绑账户。再在另一终端启动：

```powershell
python -X utf8 -B tools/integration-demo/demo.py --port 8098 --billing-config tools/integration-demo/billing.local.json --state tools/integration-demo/.state/demo.sqlite3
```

打开 `http://127.0.0.1:8098/`。钱包、结算、消息与媒体区域每五秒刷新，分别展示独立钱包余额、累计应收、已确认净扣款、未付差额及原指令状态；群管理区域由操作者显式读取最新状态。价格在每个结算对象首次创建时冻结，新版本只影响新对象；金额使用精确分字符串，币种不混加。重启不充值，也不以新初始余额覆盖现余额。旧版同库扣款状态不自动迁移，使用新状态路径并保留旧证据。

### 启用群管理

先按[群身份场景](../integration-scenario/README.md#群身份流程gi-02)准备已存在的群绑定、当前成员与服务 Agent，再将 [group.example.json](group.example.json) 复制为同目录本地配置 `group.local.json`。每个 context 固定机构宿主 origin、group_binding_id、operator_end_user_id、记忆查询/提取所用 agent_id，以及 HMAC 凭证的环境变量名称；所有 ID 使用正式 API 返回的精确字符串。页面修改服务 Agent 时另行明确目标 agent_id，不将该目标混同为配置中的记忆调用 Agent。

通过进程环境提供示例引用的 `SKILLFORGE_GROUP_API_KEY` 和 `SKILLFORGE_GROUP_SIGNING_SECRET`，再启动：

```powershell
python -X utf8 -B tools/integration-demo/demo.py --port 8098 --billing-config tools/integration-demo/billing.local.json --group-config tools/integration-demo/group.local.json --state tools/integration-demo/.state/demo.sqlite3
```

浏览器只获得 context 的名称、固定群/操作者/记忆 Agent 标识和发送器是否配置；宿主地址、秘密值和凭证环境变量名称由服务端持有。页面写入不接受另传 Tenant、principal、群或操作者身份，服务端按所选 context 注入并经正式接口校验当前权限。未配置群时页面明确显示配置空态。

需要发送器本地意图与投递对账时，复用既有 [SenderApplication](../integration-scenario/group_sender_http.py) 和[发送器配置](../integration-scenario/group_sender.example.json)，由 [business_service.py](../integration-scenario/business_service.py) 的 `--sender-config` 启动，使用独立状态文件：

```powershell
python -X utf8 -B tools/integration-scenario/business_service.py --port 8097 --sender-config tools/integration-scenario/group_sender.local.json --state tools/integration-scenario/.state/group-sender.sqlite3
```

发送器配置中的 skillforge_origin/group_binding_id 对应同一正式群，sender_credential 与 operators 的远端凭证分别来自服务端环境；sink_origin 指向实现既有 `/send` 与 `/receipts` 协议的投递接收服务。Demo 的 sender_origin 指向该发送器，sender_token_env 的值对应一个已配置本地操作者 token；不能用它替代远端 HMAC 身份。端点注册、应用控制和退休仍沿既有发送器协议，不由 Demo 创建另一套参与方状态。未配置 sender 时，仅发送器区域明确不可用，不影响其他群管理接口。

### 群管理操作与结果核对

页面读取及写入经 [group_console.py](group_console.py) 转发至[正式 OpenAPI](../../backend-java/skillforge-institution-service/src/main/resources/openapi/open-platform-v1.yaml)。成员和服务 Agent 目录分别保留自己的 `after_id`，公开历史使用独立 cursor；“第一页 / 下一页”显示本页条数，不把默认最多 100 条当作全群总数。成员/Agent 修改使用对象版本与来源事件、来源版本，不借目录重读补造来源事件。

记忆查询按状态和被描述成员筛选；操作者与被描述对象分别表达，群记忆和成员记忆的治理权限沿[群记忆与成员治理指南](../../docs/02-架构与设计/07-记忆管理.md)。审核、更正、遗忘、恢复与自动提取策略均使用正式服务，不在浏览器重新实现提取、召回或防回填。更正使用文字、条件和时效的固定表单构造正式 MemoryContent；查询最多返回 100 条符合筛选的事实，不表示全量记忆。

填写 Run ID 后可查看原 Run 的公开阶段、等待卡，沿正式接口取消、纠正或回答当前等待。业务确认绑定原 execution_id、字符串 run_revision、action_id 和 generation；Run 命令和回复的 input_id 与本次 Idempotency-Key 一致，不重新发送群触发或创建替代 Run。

控制回执中 `ACCEPTED` 表示命令已受理，`PARTIAL` 表示部分必需参与方已应用，`APPLIED` 才表示该回执所要求参与方已应用。页面分别展示 required_participants、持久 ACK、在途投递、阻断边界和退休证据；端点 ACTIVE 是注册/门禁状态，连接仍以 UNKNOWN 和最后持久确认表达，不能推断为在线。

远端控制状态与发送器本地意图独立核对：远端恢复 APPLIED 不等于本地阻断已解除。页面可对明确 intent_id 与 expected_revision 查询、核对后重新提交、证明终止后放弃，或凭明确 resume_command_id 请求本地放行；原拒绝、待核对和阻断边界仍由发送器正式回执决定。“核对原请求”只读原控制命令回执，不承担本地意图解除。

网络超时、断连或成功回包损坏时，页面保留原操作、原正文和原幂等键为 UNKNOWN，显式核对沿同 key/body 查询原结果，桥接不会再次派发原外部写入。控制命令读取原命令回执；发送器命令读取其原本地命令回执；没有证明时继续 UNKNOWN，不以新键代替旧意图。409 保留输入，成功读取冲突后的对象后，操作者显式核对最新版本才能提交新意图。普通读取故障保留旧数据并标明；当前撤权清除受保护展示并收窄写入口。

恢复群运行与释放既有积压分开执行。“精确释放消息”填写原 Run、message_id、message_version、delivery_id，并显式选择当前阻断边界；不会通配未来消息或一键清空全部阻断。恢复后的实际投递仍由既有发送器资格校验和原投递回执证明，UNKNOWN 投递只核对原结果。

## 消费与恢复

真实场景配置的 billing 与 Demo 保持相同价格和账户映射，business_origin 指向 Demo。只同步已经发生的用量：

```powershell
python -X utf8 -B tools/integration-demo/sync.py --config tools/integration-scenario/config.local.json --scenario-state tools/integration-scenario/.state/scenario.sqlite3 --demo-origin http://127.0.0.1:8098
```

同步器只读场景 SQLite，用当前平台 JWT 或独立管理 HMAC 查询机构与用量 API，不提交新模型输入，不把平台凭据交给 Demo。有 Demo 时，它是唯一计价者，Scenario 仅观察原事实和进度；无 Demo 时，Scenario 使用自身唯一适配器和独立钱包 HTTP，不同时计价。

事件去重、逐 Attempt 计量、冻结价格、累计目标、连续指令与游标同本地事务提交，之后才派发 HTTP。UNKNOWN 不补零，部分报告只计已知维度；旧报告不回退，合法更高修订或同修订最终快照可以更正累计目标。供应商 confirmation/adjustment 只留审计，无明确销售价的计量项保留缺口。

未知结果查询或同参重试原 ID，后笔不越过前笔。仅恢复既有 outbox：

```powershell
python -X utf8 -B tools/integration-demo/demo.py --billing-config tools/integration-demo/billing.local.json --state tools/integration-demo/.state/demo.sqlite3 --dispatch-only
```

确定拒绝保留原回执及未付差额。操作者明确核验后，可一起提供 `--reconcile-object`、`--reconcile-rejection`、`--authorization-ref`，复核当前账户范围后生成关联原拒绝的连续后继，不重新计量或重放模型。

## HTTP 接口与准入

这些入口属于示例服务，不是灵知产品 API。普通 Demo POST 只接受同源 JSON；独立钱包使用当前服务 Bearer 及固定账户范围。钱包采用 `bounded_overdraft` 后付费：准入观察余额是否高于负透支限额，不预占未来未计量费用；实际结算事务保证余额不低于负限额，超限保留确定拒绝。并发准入不表示费用已预留，结束准入不退款。

| Demo 路径 | 行为 |
|---|---|
| `GET /api/snapshot` | 余额、应收、差额、指令和原回执安全投影，不返回凭据或通用 kv |
| `GET /api/groups/contexts` | 群管理的安全固定上下文目录 |
| `GET /api/groups/{context}/snapshot` | 正式群、成员/Agent 分页、历史、记忆、Run、控制授权与发送器的分区结果 |
| `GET /api/groups/{context}/commands/{command_id}` | 原正式控制命令回执，只核对不重提 |
| `POST /api/groups/{context}/{operation}` | 固定表单意图、原 Idempotency-Key 和持久请求回执；范围由 context 注入 |
| `GET /api/cursor?stream_key=...` | 原样读取本地不透明消费游标 |
| `POST /api/wallet/register` | tenant_id/name 核对预建账户，不开户、不充值 |
| `POST /api/wallet/admit` | tenant_id/request_id 形成原准入身份，经独立钱包决定 |
| `POST /api/usage/consume` | stream_key/expected_cursor/page 原子消费，提交后派发 |
| `POST /api/settlements/dispatch` | 有界恢复原 outbox，不生成新计价事实 |
| `POST /api/v1/settlement-reconciliations` | 独立管理 Bearer、Idempotency-Key，以及 object_id/expected_revision/rejected_instruction_id/reason |

管理重结算仅在 billing.reconciliation_token_env 明确配置后可用，值为独立管理凭据，钱包执行凭据不能替代。每请求核对当前凭据和原账户范围；旧 CAS 或同键异参返回 409，合法重放返回原指令。快照只返回管理能力是否启用，不嵌入秘密或环境变量名。操作者在页面选择最新明确拒绝且有差额的对象，填写原因与独立管理凭据，提交后凭据立即清空，不进入浏览器存储、账本或报告。

页面分别显示管理请求受理和该原指令的钱包结果。网络结果未知时保留原请求内容与幂等键，显式“核对原请求”才能继续；其他对象等待或未知不会改变当前管理请求的受理事实。409 后保留原因，读取最新对象并显式采用其修订后才可创建新管理意图。快照刷新失败时保留既有事实、输入及受理状态，不把余额或差额改成零，也不重新开放已受理的旧拒绝入口。

具体经济身份、累计目标和因果顺序归[项目用量设计](../../docs/02-架构与设计/14-用量与账单设计.md#钱包结算交付契约目标)。

## 消息、媒体与验证

`/mcp`、`/ws`、`/delivery/pause`、`/delivery/wecom` 保持原职责。只投递已完成 Run 的 final，暂停不占成功身份，重复 delivery_id 复用原回执；模拟媒体关联原 request_id 和工具参数，不证明真实供应商质量或企微送达。

```powershell
python -X utf8 -B -m unittest discover -s tools/integration-demo -p test_demo.py -v
python -X utf8 -B -m unittest discover -s tools/integration-demo -p test_group_console.py -v
node --test tools/integration-demo/group-console.test.mjs tools/integration-demo/settlement-ui.test.mjs
python -X utf8 -B -m unittest discover -s tools/integration-wallet -p test_wallet_service.py -v
python -X utf8 -B -m unittest discover -s tools/integration-scenario -p test_wallet_http_integration.py -v
```

直接测试覆盖真实 loopback HTTP 与独立 SQLite；进程组合实际退出并分别重启钱包/适配器，验证事务提交边界、丢回执、持久租约和原身份恢复。真实 Runtime 导出组合须以环境变量 `GI10_ACTUAL_RUNTIME_USAGE_REPORT` 提供本轮业务 HTTP 报告；无事实源时该子项明确跳过，不用手工事件替代真实 Runtime 证据。

群桥接测试验证固定上下文、正式请求字段、原回执核对与服务端凭证边界；DOM 测试验证表单消费、分页、原 key/body、CAS 与上下文切换，不能代替真实浏览器、双宿主组合或外部消息送达证据。管理消费者、双宿主组合和外部交付应分别运行对应场景并保存当前候选的报告；局部 DOM 测试不证明这些范围通过。
