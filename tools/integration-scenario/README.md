# 多机构平台直管接入示例

示例使用真实 灵知 HTTP API，业务卡片、媒体 Connector 和客户钱包由本地业务服务模拟；示例业务数据库独立于 灵知，所有平台操作都经过公开接口。

准备和运行步骤见[多机构应用接入指南](../../docs/02-架构与设计/04-接口与事件契约.md)。`scenario.py` 提供 `setup/run/usage/status/media-demo`，`business_service.py` 提供本地 MCP 及业务投递服务；配置从 `config.example.json` 复制到被忽略的 `config.local.json`，凭据与回执仅落在 `.state/`。

## 群身份流程（GI-02）

[group_identity.py](group_identity.py) 独立调用群 ensure/detail、成员与服务 Agent 更新，并可按明确计划关闭群。操作定义见[接口 §2.7](../../docs/02-架构与设计/04-接口与事件契约.md#27-多机构接入公开协议基线目标)。前置条件是机构宿主已启动，当前 Tenant/integration 已登记有效业务范围、真实 EndUser 与可用 Agent，执行凭据拥有 `groups.read/write`、`groups.members.write`、`groups.agents.write`。原生 Agent 或 integration 为 null 的受控实例仍需显式群服务授权；固定非空 integration 的部署必须同域。

复制 [group-identity.example.json](group-identity.example.json) 到被忽略的 `.runtime-logs/group-identity.plan.json`，将 business_scope_id、end_user_id、agent_id 替换为真实公开 API 返回的精确字符串，选择明确的外部群生命周期和稳定命令键。示例数字只是占位，不是已存在的机构数据。通过进程环境提供 `SKILLFORGE_GROUP_API_KEY` 和 `SKILLFORGE_GROUP_SIGNING_SECRET`，不把凭据放进计划或命令行，再执行：

```powershell
python -X utf8 -B tools/integration-scenario/group_identity.py --origin http://127.0.0.1:8081 --plan .runtime-logs/group-identity.plan.json --evidence .runtime-logs/group-identity.http.json
```

流程仅修改群身份，不发送消息、不创建 Run、不调用模型或启动服务。成员/Agent 的 expected_revision=0 表示首次投影；已有对象使用自身 revision 和递增 source_revision。每个写请求仅发送一次；失败立即停止，网络超时保留 `UNKNOWN`、原请求和命令键，不自动重发或换键。人工核验后以相同计划重投仍由服务端按原命令/来源回执幂等处理。JSON 证据保存公开请求和实际回执，不包含 HMAC 头或凭据；客户端单测将 evidence_kind 标为 client_fixture，不构成真实宿主验收。

需要关闭时，在计划中显式增加 `"close": {"expected_revision": 1, "idempotency_key": "group-lifecycle-1:close"}`，revision 必须来自该绑定当前 metadata；成员/Agent 对象版本不替代绑定版本。关闭保留原群和 Session，旧生命周期不会重新启用，新生命周期需要新的明确计划与命令键。个人 `scenario.py run` 仍使用真实 user subject，与此身份流程独立。

```powershell
python -X utf8 -B -m unittest discover -s tools/integration-scenario -p test_group_identity.py -v
```

## 群来源、触发与等待（GI-03）

[group_messages.py](group_messages.py) 使用已建群身份，分别调用消息记录、显式触发、触发当前状态、原 Run 的准确等待响应及定向取消/steer。执行凭据按实际使用分支授予 `groups.messages.write`、`groups.triggers.write/read`、`groups.runs.read/respond/control`；沿用上节的进程环境凭据和 loopback 限制。服务端继续核对当前成员、群服务 Agent 和原来源授权，客户端不从消息里的 `@名字` 选择或授权 Agent。

复制 [group-messages.example.json](group-messages.example.json) 到 `.runtime-logs/group-messages.plan.json`，替换已建立的 group_binding_id、真实成员和服务 Agent。计划第一条仅记录，第二条显式引用记录回执的 source_message_id 触发；普通消息只返回六项来源元数据，不产生 Input/Run。消息内容沿现有 ChatContent，支持有序 text/已登记 image 选择器，数量 1–100。消息事件不可变，同事件换 source_revision 不表示编辑，纠正需明确的新 source_event_id。

```powershell
python -X utf8 -B tools/integration-scenario/group_messages.py --origin http://127.0.0.1:8081 --plan .runtime-logs/group-messages.plan.json --evidence .runtime-logs/group-messages.http.json
```

消息记录成功返回 200，触发登记返回 202。POST 的原触发回执保持不变，GET `/open-platform/groups/triggers/detail` 读取当前 queued/accepted/rejected 投影；排队时 input_id/run_id 为 null，accepted 后才有 Run 映射。示例只读一次当前投影，不等待模型完成；JSON 中 run_completion 仍为 NOT_RUN。一个来源在本群只对应一个逻辑触发，换命令键不能重复执行，换 requester/Agent 为冲突。当前触发只允许原 EndUser 作者作为 requester，不提供客户端代办授权框架。

四种 author 分支严格互斥：end_user 使用 end_user_id；agent/tool 使用 agent_id、run_id、source_ref；system 使用 source_ref。当前 Agent 的 source_ref 必须是同群已 completed 的正式 assistant message_id，且完整文本内容与原事实一致；tool/system 暂无可核验的正式公开来源，返回 403。自报类型、运行 ID 或来源字符串不形成授权。外部事件重投保持原来源身份；接入方将消息接收和触发决定分别持久处理，先同步后触发引用同一来源。

群 Run 挂起后，GET `/open-platform/groups/runs/detail?group_binding_id=...&run_id=...` 提供公开状态和 pending_interactions。复制 [group-wait-response.example.json](group-wait-response.example.json) 到 `.runtime-logs/group-wait-response.plan.json`，用**原公开等待卡**的 execution_id、字符串 run_revision、action_id、generation 和实际回答替换所有占位值；示例中的 `1` 不能代替真实等待版本。human_input/tool_input/workflow_input 的结构直接沿用现有 [Chat 准确响应类型](../../frontend-shared/src/types/chatCollaboration.ts)，不得从当前状态补造旧等待身份。

```powershell
python -X utf8 -B tools/integration-scenario/group_messages.py --origin http://127.0.0.1:8081 --plan .runtime-logs/group-wait-response.plan.json --evidence .runtime-logs/group-wait-response.http.json
```

等待计划只向原 Run respond，前后各读一次同 Run 的安全状态，不创建新消息或触发。真实 responder 必须是当前成员，且为原 requester 或当前 manager；服务端保留原 requester、Agent 和 Run 身份。input_id 是这次回答的稳定输入身份，客户端据此设置相同的 Idempotency-Key，不另设可漂移的命令键。版本和 wait generation 保持十进制字符串，普通聊天文字不进入准确恢复协议。过时等待、当前权限失效或冲突会停止流程；网络/5xx 保留 UNKNOWN 和原请求，不重发、不换身份。

原任务的补充信息和取消使用 POST `/open-platform/groups/runs/commands`，不进入普通 trigger 队列。复制 [group-run-commands.example.json](group-run-commands.example.json) 为 `.runtime-logs/group-run-commands.plan.json`，只保留本次明确要执行的命令，替换原 run_id、当前 operator_end_user_id 和稳定 input_id，再用同一脚本执行：

```powershell
python -X utf8 -B tools/integration-scenario/group_messages.py --origin http://127.0.0.1:8081 --plan .runtime-logs/group-run-commands.plan.json --evidence .runtime-logs/group-run-commands.http.json
```

action=steer 时必须提供非空白 text；action=cancel_execution 时 text 只能缺失或 null。operator 必须是当前真实成员且为原 requester 或 manager，header 仍自动等于 input_id。示例按计划执行，前后读同 Run，保持原 requester/Agent/Run 归属；取消返回 cancelling 等受理状态时不能报告为已取消终态，也不重放未知外调。此入口只复用原 Runtime 的定向补充/取消，不承担 GI-04 的跨服务 pause、发送控制或公开历史。

```powershell
python -X utf8 -B -m unittest discover -s tools/integration-scenario -p test_group_messages.py -v
```

这些测试明确使用 client_fixture，只证明客户端字段、稳定身份、首错停止和原响应转发；真实消息持久化、单前台队列、waiting 或 UNKNOWN 重启由真实宿主验收负责。示例支持原 Run 的定向响应、补充和取消，完整群公开历史、跨服务暂停/发送控制及可靠外发由 GI-04 接续；受理或读取状态不表示 Run 完成。

## 个人会话与业务示例

平台管理身份采用真实人员 JWT（`SKILLFORGE_SCENARIO_PLATFORM_TOKEN`），或明确授权的独立管理主体 HMAC（`SKILLFORGE_SCENARIO_MANAGEMENT_KEY` 与 `SKILLFORGE_SCENARIO_MANAGEMENT_SECRET` 必须同时提供）。管理主体使用 `X-Management-Key`，机构执行凭据使用 `X-Api-Key`，两者不可互换。机器本金必须预先获授实际机构、定义及 namespace 的精确目标与动作；脚本不会自授目标或复制人员权限。新机构/定义目标无法预先指定时由平台人员运行 `setup`。

`setup` 使用 `/platform/institutions/provision` 明确套餐并开通机构，`/platform/institution-credentials/issue` 签发固定 Tenant/integration 凭据；机构业务范围走 `/open-platform/business-scopes/ensure`。定义分发显式选择 `AUTHORIZED_DEPLOY`，通过 `/platform/agent-grants` 直授机构，部署复用机构宿主的 `/platform/agent-deployments`。`deployment_origin` 缺省为 `institution_origin`；经网关调用时可显式指向拥有该精确路由的网关。业务模型和公开会话仍经真实 Runtime 执行。

消费状态保存稳定事件、不透明游标及唯一适配器的冻结销售价、逐 Attempt 累计应收和可靠结算指令。钱包是[独立 HTTP 服务](../integration-wallet/README.md)，持有自己的余额、净扣款与原回执数据库；没有同库直接扣款路径。旧版 SQLite 不自动迁移，使用新 `--state` 路径并保留旧证据。平台读取 `/platform/usage/events`，授权或筛选变化产生 409 时，先核验原范围再显式重建游标，不能自动重放模型请求。

先运行 `setup` 获得真实机构 ID，再按 billing.accounts 的场景别名为钱包配置对应的固定 Tenant/账户/币种。输入、输出 Token 与 tool.call 使用独立单价，已有对象保留原价格版本。配置 `business_origin=http://127.0.0.1:8098` 时，先启动[通用业务 Demo](../integration-demo/README.md)，由 Demo 独占计价与 outbox，Scenario 仅观察原用量和进度；无 Demo 时，Scenario 装配自身唯一适配器，仍只通过独立钱包 HTTP 结算。消息和媒体服务不拥有另一份钱包余额。

`media.image`、`media.video`、`media.transcription`、`media.speech` 参与同一可靠消费和跨视图去重，但本示例尚未配置媒体销售单位与售价，因此保留 `unpriced`、应收目标为空，不签发钱包结算指令，也不套用 `tool.call` 单价。`quantity=1 / measurement_unit=call` 只表示技术调用次数；图片数、视频数、字符数、小数秒和冻结模型引用完整保存在原不可变事件中，缺失仍为 `null`，调用次数已知不代表所有媒体计量维度齐全。后继报告、终态与重启回读继续归原 Attempt，不新建媒体收费对象。

发送前审计后又明确取消的媒体终态采用 `measured / quantity=0 / measurement_unit=call / amount_status=not_applicable / usage_source=UNKNOWN`，媒体分项与 Token 仍为空。该事实将原对象关闭为 `not_applicable / not_sent`，保留起始未知报告，不作为免费价或待定价对象，也不因重读、重启或旧报告再次进入钱包结算。

`media-demo` 由平台关联 `demo_tenant_id` 指定的已有真实机构，签发固定集成域执行凭据，直接向机构授权公共定义。环境提供该机构 `admin/developer` 的 `SKILLFORGE_SCENARIO_INSTITUTION_TOKEN`，平台管理身份按下文配置；命令先读 `/auth/me` 核对机构及角色，再经正式 HTTP 注册/发现 MCP、发布三个机构 TOOL 能力与部署、发布授权公共模板，并验证三资源槽由未就绪变为 CAS 绑定后就绪。三个工具分别运行独立 Session/Run，每个 Run 至多调用一个工具。命令仅在真实公开工具过程成功、Run 与 final 完成、模拟服务回执参数精确一致时成功。证据位于指定 `--state` 同目录的 `media-evidence.json`，包括注册发现、能力与绑定、Run/status/history/process 以及明确模拟的媒体回执，不含认证秘密。

```powershell
python -X utf8 -B tools/integration-scenario/scenario.py media-demo --config tools/integration-scenario/config.local.json --state tools/integration-scenario/.state/scenario.sqlite3
```

此命令会调用配置的真实模型；直接 MCP 测试和模型回答文字都不会被用作 Runtime 成功证据。失败保留原输入、公开事件和回执，不自动创建新幂等键重新执行；已完成的步骤及消息在相同 checkpoint 下重用。

Demo MCP 支持正式 `2026-07-28` 与显式握手的 `2025-11-25`。现代工具结果声明 `resultType=complete`；显式 2025 结果遵守该版本的响应形态。HTTP 版本头和请求元数据冲突、未知版本会在工具业务执行前拒绝。Runtime 已派发但响应协议不合法时，保留原未知结果；修正 Demo 协议不能成为重放原请求的理由。

消费者按正式 `completed/terminated/failed/cancelled` 识别终态；失败终态立即停止等待，保留 status、公开事件、history 和已有模拟回执，不生成成功执行证明。`suspended` 和待交互保持原 Run，不继续创建输入。

Run 的事件与状态轮询至少间隔两秒，最终业务投递仍严格要求同一 Run 的 `phase=final`、`state=completed`。这些读取及完成后的历史读取若收到 429，只在服务端提供合法 `Retry-After` 时按该政策重读；等待和单次 HTTP 超时共同受原 `run_timeout_seconds` 的 monotonic deadline 约束。等待超过剩余预算时保留原 Run 与 checkpoint 并停止，不延长预算。写请求收到限流或网络未知结果都不会自动重放，也不通过新幂等键重新执行。

媒体定义设置 `max_iterations=8`、`max_tool_calls=1`，活跃时间预算继承正式平台根预算。模型准备会保护最低收尾时间，按冻结模型超时和收尾调用次数测量；不要以消费者等待超时替代 Agent 活跃时间预算，也不要将活跃时间设置为低于一次模型超时加最低收尾处理的值。已经受理的 Run 保留原冻结预算，发布新定义不修改它。

`media_attempt_ref` 是可选的显式新尝试输入，限 1–32 个 ASCII 字母数字、点、下划线、短横线，首字符必须是字母或数字。缺省保持首次 request_id、消息及 Session checkpoint、外部会话引用；失败后原命令重跑仍读取原 Run。只有明确配置例如 `"media_attempt_ref": "budget-v2"`，才为各工具建立独立 request_id、消息/Session/status checkpoint 和外部会话身份；相同 ref 重跑保持幂等。每个 Run 证据保存 `attempt_ref`，缺省为 `null`，新旧尝试在同一证据集合中分别保存。

预算不足的旧 Run 应通过正式 `cancel_execution` 命令请求取消并读回终态，保留原 Session/input/Run 证据；核对没有派发模型/工具或模拟回执后，发布同公共模板的新定义，再明确设置新的 `media_attempt_ref` 执行。脚本不会自动取消旧 Run、改写原冻结预算、删除旧输入或选择新 ref。

消费者验证：

```powershell
python -X utf8 -B -m unittest discover -s tools/integration-scenario -p "test_*.py" -v
```

该测试覆盖业务端去重、累计用量、重启、事务回滚、供应商成本调整、累计消息版本、人工暂停、可选外部 Demo 的真实 WS/HTTP 投递、机构 JWT/CAS 请求和媒体证据的拒绝门槛。真实 灵知 链路需使用指南指定服务和模型运行 `scenario.py run` 与 `media-demo`，单测成功不表示服务端或生产集成已验收。

隔离双宿主集成测试为 `ScenarioFixtureHttpIntegrationTest`，使用真实平台登录和 HMAC HTTP 调用本脚本，并通过独立 Python 进程重启验证持久消费；所需 bootJar 构建命令和证据范围见指南第 5 节。

## 非群媒体材料与正式成果

[media_deliverables.py](media_deliverables.py) 是独立的正式材料消费者，可被真实宿主组合测试或已完成的业务场景调用。它复用 `Client` 的机构执行身份、HMAC 与禁止重定向策略；附件和图片分别上传到 `/open-platform/sessions/attachments`、`/media`，正文为 `application/octet-stream` 原始文件字节；附件 query 为 `sessionId/uploadId/filename`，图片为 `sessionId/uploadId`，元数据和文件原字节一起签名。进程环境提供当前机构及 integration 的 `SKILLFORGE_MEDIA_API_KEY`、`SKILLFORGE_MEDIA_SIGNING_SECRET`，上传需 `sessions.write`，成果读取需 `sessions.read` 和 `artifacts.read`。使用已存在的明确 Session 和稳定 UUID：

```powershell
python -X utf8 -B tools/integration-scenario/media_deliverables.py --origin http://127.0.0.1:8081 --session-id <真实Session> --evidence .runtime-logs/gi08-upload.json upload --upload-id <稳定UUID> --file .runtime-logs/gi08-input.json
```

图片上传增加 `--image`。服务器核验当前会话、实际文件类型与上传幂等身份，客户端不声明新的音视频 MediaRef；STT/TTS/视频继续由模拟 Connector 提供业务结果。上传不会创建模型输入或正式成果，所得附件/图片选择器须经已有消息入口明确引用。

正式成果由真实 Runtime 工具执行产生：`code_execute` 或 `workspace_exec` 经 Sandbox 收集候选 Artifact，`finalize_task_outputs` 冻结正式清单，原 Run 完成后由成果服务发布。模拟工具的 `fixture:tts:` 引用、Workspace 中间文件及回答文字均不能代替此链路。素材与模拟供应商结果可由该链路生成文本、JSON 或实际文件，模拟声明须随成果保留。

原 Run 完成并发布后，按精确 Session/Run 读取成果：

```powershell
python -X utf8 -B tools/integration-scenario/media_deliverables.py --origin http://127.0.0.1:8081 --session-id <真实Session> --evidence .runtime-logs/gi08-deliverables.json collect --run-id <原Run> --directory .runtime-logs/gi08-files
```

消费者先核对原 Run `completed`，再读正式清单，逐个使用服务器返回的 `revisionId/fileId` 下载；每次下载由服务端重新授权。下载字节的长度和 SHA-256 必须与清单一致，校验通过才原子保存本地文件，远端文件名不会成为本地路径。失败立即停止，已成功文件及原身份保留；网络未知不自动重做上传、生成新输入或调用工具。该命令的 PASS 只证明本次正式 HTTP 消费，完整 Runtime 生成、跨范围拒绝和撤权组合仍须以真实宿主验证。

```powershell
python -X utf8 -B -m unittest discover -s tools/integration-scenario -p test_media_deliverables.py -v
```

此组使用独立本地 HTTP 替身检验真实原始二进制/HMAC、精确文件下载、内容完整性、重定向拒绝、未知不重试及失败无成功文件；不冒充产品服务端授权通过。

## 门店预约与随访确认

[business_workflow.py](business_workflow.py) 提供门店外部业务示例，门店 Connector 的 Bearer 凭据在业务服务端映射固定机构、门店、服务操作者和允许访问的患者。固定服务操作者不代表当前聊天用户；模型参数只选择获授权的业务对象，不能指定机构、门店或操作者。每个门店在真实机构宿主注册独立 Connector，凭据交给既有 Connector 凭据管理，绑定相应资源槽；Session 主体不随查询患者改变。

将以下非秘密配置保存在被忽略的 `.runtime-logs/gi08-business-config.json`，替换外部业务编码；`token_env` 指向进程环境中的独立令牌，配置文件不保存令牌值：

```json
{
  "authorizations": [
    {
      "token_env": "GI08_STORE_A_TOKEN",
      "tenant_ref": "institution-a",
      "store_ref": "store-a",
      "operator_ref": "connector-service-a",
      "patient_refs": ["patient-a"]
    }
  ],
  "confirmation_ttl_seconds": 300
}
```

在已提供该环境变量的终端启动；服务仅监听 loopback，SQLite 属于外部模拟系统，不操作宿主数据库。确认有效期默认 300 秒，显式配置范围 1–3600 秒，重启后新计划采用配置值，已有计划保留原有效期。

```powershell
python -X utf8 -B tools/integration-scenario/business_service.py --port 8099 --state tools/integration-scenario/.state/business.sqlite3 --business-config .runtime-logs/gi08-business-config.json
```

`/mcp` 使用正式 `2026-07-28` 多轮协议。授权调用可发现五个业务工具：`appointment_query` 只读已创建预约；`appointment_evaluate` 分别接收患者意向 `patient_time` 和选定 `appointment_time`，不提交或预占；`appointment_create`、`followup_create` 首轮只保存待确认计划；`business_receipt` 按原 `request_id` 查询当前门店及服务操作者名下的回执。精确参数 schema 由 `tools/list` 返回，时间为带时区的 ISO 8601 字符串。未配置业务授权时只公开既有三个模拟媒体工具，业务调用明确拒绝。

提交工具首轮返回 `input_required`，其中 `inputRequests.confirm_business` 使用 `elicitation/create` 表单展示患者、关键参数和原有效期。用户的真实准确等待回复经 Runtime 绑定原执行与等待；Runtime 续调保留原工具和 arguments，原样带回 opaque `requestState`，并提交 `inputResponses.confirm_business={"action":"accept","content":{"confirmed":true}}`。拒绝/取消不会写预约或随访；过期、对象无权、状态或参数被替换时不会提交。待确认计划属于业务草稿，不进入长期 Memory。

业务系统采用 SQLite 事务保存稳定动作与原回执，同 `request_id` 同参数读取原结果，异参数拒绝；每次续答和查询都重新核对当前患者范围及凭据。回执丢失后查询原 `business_receipt`，`not_found` 不授予重新提交权限；外部去重不构成 Runtime 重放 UNKNOWN 的证明。重试遵循本项目未知副作用契约；查询原回执和幂等参数核验不能替代派发结果核实。

```powershell
python -X utf8 -B -m unittest discover -s tools/integration-scenario -p test_business_workflow.py -v
```

该组验证真实本地 HTTP、门店/患者/服务操作者隔离、只读评估、参数与时效绑定确认、当前撤权、并发去重，以及丢弃结果后重建业务服务并查询原回执。它证明外部模拟业务能力；真实宿主、Runtime、Connector 与准确等待恢复的组合由正式集成测试另行验证。

### 群业务控制与人工确认

群业务复用同一门店 Connector 和五个结构化工具。群消息与触发使用正式 `/groups/messages`、`/groups/triggers`；业务患者不替换群 Session 主体，原消息作者、原 Run requester、当前确认者和外部固定服务操作者分别保留。确认通过 `/groups/runs/respond` 回传原 Run、等待代次和修订；其他普通群成员不能借接管状态确认该任务，当前 requester 或获准管理员仍需其当前成员、服务主体和业务对象权限。

自动业务写范围由 `/groups/controls` 的 `business_write` 管理。暂停先取得控制锁时，后续业务工具和确认续调不能取得新派发资格；已取得资格的原 Attempt 仍按真实结果收束。恢复控制状态继续原 Run 和原等待，不重新提交已完成的预约。业务已经发生但响应丢失时保留 UNKNOWN，查询原 `business_receipt`；控制恢复和后续查询不会授予重新派发资格。

[双宿主业务集成入口](../../backend-java/skillforge-institution-service/src/test/java/com/skillforge/institution/integration/BusinessWorkflowHttpIntegrationTest.java)已接入 [群业务组合消费者](../../backend-java/skillforge-institution-service/src/test/java/com/skillforge/institution/integration/GroupBusinessWorkflowScenario.java)，使用测试独占模型响应屏障及 MCP 传输代理，分别安排“暂停先胜”“派发先胜”和“外部提交后回执丢失”。代理只控制本地测试传输，不进入生产代码；群组合的通过结论以本轮真实 Gradle 结果为准，不以此入口存在视为已验收。


### 群后台用量与钱包组合

[test_wallet_http_integration.py](test_wallet_http_integration.py) 可直接消费本轮双宿主报告中的 `group_memory_usage.unknown_usage`、`usage` 和 `headquarters_usage` 三份原公开 Page。它沿原 `event_id` 和 Attempt 去重，9 项群/后台来源字段随不可变用量正文保留；实际系统 requester 与来源作者分开，钱包付款账户仍由实际执行机构的显式配置确定。

将 `GI10_ACTUAL_GROUP_USAGE_REPORT` 指向本轮 `BusinessWorkflowHttpIntegrationTest` 已完成且 `state=PASS` 的 `report.json`，再运行：

```powershell
$env:GI10_ACTUAL_GROUP_USAGE_REPORT='<本轮报告的绝对路径>'
python -X utf8 -B -m unittest discover -s tools/integration-scenario -p test_wallet_http_integration.py -v
```

该群单例先消费供应商响应前的 UNKNOWN，确认没有预造应收或钱包指令，再消费同 Attempt 的真实模型回执。测试销售价为输入 100 分/千 Token、输出 200 分/千 Token，合成 Provider 的 13/5 Token 对应客户应收 2.3 分；随后实际退出适配器进程、让独立钱包提交后丢失响应，并分别重启查询原指令。机构与总部原事件重读仍只有一笔入账；UNKNOWN 时首次冻结的售价也不会被后来的配置替换。钱包和模型供应商结果均为明确模拟，公开用量、HTTP 传输及双方持久化为真实执行。没有提供本轮原始报告时该例明确跳过，不以手工导出事件代替宿主计量验收。
