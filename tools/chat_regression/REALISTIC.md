# 真实场景验收矩阵

本文只描述 `tools/chat_regression` 可直接执行的真实场景验收范围和复用入口，不定义 Runtime 契约。当前候选、部署和报告必须按[行为评测指南](./BEHAVIOR.md)及项目[测试与验收策略](../../docs/04-指南与运维/03-测试与验收策略.md)绑定后再判定。

默认产品 Agent、当前已发布的默认提示和真实中文用户输入是本矩阵的对象。用户可以自然地问候、补充条件、改口、要求查询或要求写入；最终回复可以是自然中文。`behavior_suite.py` 中固定的 JSON 协议只用于机器核验工具行为，不能替代默认产品 Agent 的自然中文聊天验收，也不能把 JSON 用例通过解释为产品聊天通过。

已有报告均标为历史参考，不能直接证明当前候选通过。复用历史部署、本地行为批次和修复快照时，必须核实候选、配置及变更范围，明确哪些是复用证据、哪些在新候选上重新执行；不能把不同候选的样本拼接为完整发布矩阵。

| 真实流程 | 故障条件或变化 | 机器可核验事实 | 可复用脚本与历史参考 | 优先级 |
|---|---|---|---|---|
| 默认产品 Agent 从聊天入口接收“你好”“帮我查一下……”等自然中文，创建 Session 并完成一轮回答 | 默认提示、Native 对话投影、模型流或工具过程版本发生变化 | 请求使用目标 Agent 和当前提示绑定；SSE 与持久 trace 的 `run_id` 一致；Run 有 `run.completed` 或明确终态；过程项、工具结果、最终自然中文回复一致 | 脚本入口：[main.py](./main.py)、[runner.py](./runner.py) | P0 |
| 用户先说“请查一条记录”，下一轮补充编号，随后又改成另一个编号 | 澄清跨轮复用旧输入、助手事实或已排队 Action；用户改口发生在恢复或重启后 | 第一轮不调用查询；后续只使用最新用户输入；工具参数、消息归属、来源事件和最终回复均指向同一当前范围；不产生旧编号副作用 | [behavior_main.py](./behavior_main.py) 的 B03 计划/评估；[behavior_capture.py](./behavior_capture.py) | P0 |
| 用户明确要求实际只读查询，模型调用 `query_record` 并用中文报告数值或来源 | 缺少参数、跨轮引用、空值、畸形结果、分页或来源字段变化 | 查询次数、冻结参数、响应 JSON 类型、分页游标和最终自然语言中的数值/来源可从 trace/audit 核对；JSON 专用套件另核验 `status/value/url`；空值为 unavailable，不能编造数值或来源 | [mcp_fixture.py](./mcp_fixture.py)、[behavior_suite.py](./behavior_suite.py) 的 B02/B04/B05/B06/B07/B11/B12；离线语义补充仅可用 [conversation_eval.py](../runtime/conversation_eval.py)，不能替代真实调用 | P0 |
| 用户明确授权写入，实际调用 `write_record`；供应商或夹具在写入后丢失响应 | 非幂等 Action 的结果未知；重试、重启或恢复可能重复写入 | 只有一个发送和一个副作用；未知结果落为 `OUTCOME_UNKNOWN`/等价事实；不伪造成功、不再次外调；取消后无新派发，最终 Action/Run 静止 | [mcp_fixture.py](./mcp_fixture.py)、[behavior_capture.py](./behavior_capture.py) 的 B14 | P0 |
| 用户或工具请求触发 401/403，或另一租户读取 Session、成果票据和工具数据 | 认证过期、权限不足、auditor 只读、跨租户 ID 猜测或错误重试 | 未认证为 401；无权为 403；跨租户读取、票据签发和写副作用被拒；永久拒绝不自动重试；明确的新核验请求单独授权；审计事实保留租户和主体归属 | [contract_probe.py](./contract_probe.py)、[mcp_fixture.py](./mcp_fixture.py) | P0 |
| 客户端关闭 SSE、网络断开或模型流只有部分正文后恢复原请求 | 流 reader 阻塞、缺少终态、重复 POST、供应商 5xx/超时或取消竞争 | 恢复只 GET 原 `client_message_id`/Run 的事件和 trace；不重新 POST；模型、工具、Usage 和 Outbox 不重复；最终状态与已提交正文/错误事实一致 | [runner.py](./runner.py)、[contract_probe.py](./contract_probe.py) | P0 |
| 用户在模型或工具执行中点击取消；服务随后重启，再查询原 Run | 取消屏障、UNKNOWN Action、迟到回调或执行线程未静止 | 取消请求持久化；屏障释放后无新 dispatch/decision；Action attempt、Run、Execution 终态一致；重启后只读同一事实，不恢复为成功或重复外调 | [behavior_capture.py](./behavior_capture.py) 的 B15、[runner.py](./runner.py) | P0 |
| 同一 `client_message_id` 重复提交，以及同键不同 payload 冲突 | 浏览器重试、SSE 超时后的误重发、服务重启后的重复恢复 | 同键同 payload 返回原 Run/原终态且模型、工具、Usage、Outbox 不增加；同键异 payload 明确 4xx/冲突；未知请求不猜测创建结果 | [client.py](./client.py)、[contract_probe.py](./contract_probe.py)、[runner.py](./runner.py) | P0 |
| 有限预算下执行自然中文聊天、查询、写入和取消，并在多个 Session 上并发运行 | token、输入次数或执行秒数达到上限；在途请求可能尚未结算；并发可能放大连接池和流回调问题 | 发起下一次收费调用前检查 `max_tokens/max_turns/max_seconds`；超限停止新收费并记录 `budget_exceeded`；在途请求只结算不重发；Session/租户不串线，取消后静止 | [behavior_main.py](./behavior_main.py)、[behavior_runner.py](./behavior_runner.py)、[BEHAVIOR.md](./BEHAVIOR.md) | P0 |

## 预算与停止条件

本节只规定逐批运行时的熔断：每个批次在冻结计划中显式设置 `max_tokens`、`max_turns`、`max_seconds` 和结算窗口；发起下一次收费请求前重新检查剩余额度，任一上限达到或供应商返回明确额度不足时停止新请求。

停止后只允许等待、结算、读取原请求终态和写入脱敏报告；在途调用不能重发，未知请求不能借重跑、扩大并发或更换候选掩盖。本次执行的预算与停止条件在运行前明确，历史结果不替代当前候选验证。

## 正式发布前置

正式发布评测必须使用完整 core `B01`–`B18`、每类 3 次的 54 样本矩阵；stress `S01`–`S06`、每类 3 次的 18 样本是独立压力补充，不能与 core 或其他候选拼接。影响标签选择和本地小批次只能用于定位问题，不能替代相应冻结批次。

正式运行前必须完成受控环境收据、真实服务就绪、隔离租户、受控 MCP 夹具、显式模型 ID和预算绑定；凭据只能由环境变量提供。正式 runner 入口是 [behavior_main.py](./behavior_main.py) 的 `plan/prepare/run/evaluate/report/release-check`。

`release-check` 还必须核对保护 CI workflow [agent-behavior-eval.yml](../../.github/workflows/agent-behavior-eval.yml)、受保护环境审批、唯一公开报告 artifact、候选完整 SHA、部署 revision、suite/evaluator/config digest、model/model version、结算窗口和 plan fingerprint。当前没有这些完整绑定证据，不能宣称已具备上线资格。
