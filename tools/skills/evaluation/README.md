# Skill 触发与效果评测

本工具对灵知 真实已发布 Agent 进行 Skill 触发与效果评测，冻结测试集和 Agent 版本，按可观察断言比较基线与候选。不自动改写 Skill 描述，不修改已发布包、Agent 挂载或工作台内容。

评测输入先准备明确的已发布 Agent 变体（基线/候选，固定相同模型与工具配置），在 suite 中列出每个 Agent 的完整 Skill definition_id→version_id 集合。工具冻结 Agent 详情、挂载、输入与摘要，运行前再核对，不创建或发布 Agent。请比较冻结配置以确认差量仅含待评测 Skill；不同模型或工具权限的差异不能归因于 Skill。

`suite.example.json` 的标识均为待替换占位值，不能直接作为验收。每个 case 定义自然任务 prompt、target_skill_definition_id、should_activate 和实际可观察断言。正例覆盖适用任务，负例覆盖相邻但不适用的任务；少量样本只支持当前样本结论。重复执行可以观察方差，不能把一次成功当成总体成功率。

凭据只通过环境变量 `SKILL_EVAL_INSTITUTION_TOKEN`、可选 `SKILL_EVAL_PLATFORM_TOKEN` 传入，不写入套件或证据。机构账号须有 Agent 与 Session 查询/执行权限，平台观测凭据用于同机构会话 trace 与 usage。只有机构凭据时可评估运行和成果，Skill 触发及用量保留 `UNKNOWN`。

```powershell
python -X utf8 -B tools/skills/evaluation/run_evaluation.py freeze --suite <真实suite.json> --output .agent-work-state/skill-strengthening/evaluations/<独立批次>
python -X utf8 -B tools/skills/evaluation/run_evaluation.py submit --output <同一证据目录> --variant candidate --case positive --repeat 0
python -X utf8 -B tools/skills/evaluation/run_evaluation.py collect --output <同一证据目录> --variant candidate --case positive --repeat 0 --wait 45
python -X utf8 -B tools/skills/evaluation/run_evaluation.py report --output <同一证据目录>
```

submit 每次创建独立 Session 并提交真实 `/chat`，先落盘受理意图，写请求失败不重试。未知创建/受理必须先查询并人工核对标识，禁止删除回执重发。collect 的等待上限 60 秒，超过等待时间只保存原 Run 进度；再次 collect 不新建运行，也不自动回答 HITL。需要取消时显式执行 `cancel`，按原 session/run 调用平台取消入口并保存回执；取消不撤销外部副作用。

采集原 Run 的状态、回答、正式成果、真实下载字节摘要、平台公开激活事件和 Provider 用量。触发只认成功 `ACTIVATED/ALREADY_ACTIVE` 的精确 `skill:<mount_id>` 结果，不使用模型自述或包名文本推断。`should_activate` 对没有挂载目标 Skill 的基线不适用，避免把能力缺失算成触发失败。正式交付断言只对已 ready 且下载摘要一致的文件成立。

支持的断言为 answer_contains、answer_not_contains、deliverable_format、deliverable_text_contains。它们是具体任务的自动验收条件；版式、复杂内容与语义仍需要独立看图或人工评审。文档和 Office 的二进制内容不作为文本读取。token 未报告时保留 null/unknown_runs，不补零、不推算价格。elapsed 使用同一 Run 的 run.started 到终态事件时差；缺少完整生命周期时序时为 UNKNOWN，不能当成 Provider 首 Token 延迟。collection_elapsed_seconds 单独记录受理到采集时间，包含观测延迟。

真实工具回归还可使用 `runtime_status` 与 `tool_call`。后者使 collect 从同一机构 Session/Run 的正式轨迹接口采集实际模型请求、模型结果和 TOOL 输入输出，保存到 `trajectory.json`；报告从原始采集文件重新判定，不能仅凭 `assessment.json` 中的成功摘要通过。例如：

```json
{"type":"runtime_status","value":"completed"}
{"type":"tool_call","tool_name":"business_data_query","arguments":{"aggregates":[{"function":"count","alias":"total"}]},"arguments_absent_paths":["aggregates.0.field"],"result":{"rows":[{"total":17}]},"min_calls":1,"max_calls":1,"max_executions":1}
```

`tool_name` 来自实际模型请求中工具定义的显式 `logical_name`，并映射到该定义的真实 `wire_name`，不从哈希名称猜测。`arguments` 和 `result` 分别匹配真实调用参数与实际工具 `structuredContent`：对象按子集匹配，数组按位置且长度相同；`arguments_absent_paths` 用点分路径检查字段不存在，数组索引使用数字。`result_contains` 是可选字符串数组，用于在实际工具文本或结构化正文中查找资源标记，不能用单个数字代替业务结果核验。业务聚合应同时固定 function、field、where 与 alias，并把同一 alias 对应的真值写入 `result`；模型使用其他合法 alias 的结果仍需人工审阅，自动断言不替代完整语义评判。

`min_calls/max_calls` 默认均为 1，表示符合参数条件的模型请求数量；`max_executions` 可约束实际 TOOL 节点数量，复用不新增执行。结果按真实 `call_id` 配对，并由模型结果的调用身份、轮次、参数和实际 TOOL action 核对；实际节点须 SUCCEEDED、MCP `isError=false`、`resultType=complete`，结构化结果含 `truncated=true` 时不能通过。缺少完整请求、配对反馈或实际结果时为 UNKNOWN 且不成功。仅完成 Run 不等于断言通过。

结果优先读取 MCP `structuredContent`；未提供时，仅在 `content` 恰好包含一个 text 且其正文为合法 JSON 对象时读取该对象，并记录 `result_source=single_text_json`。多文本、非 JSON 或数组不猜测为结构化对象，要求 `result` 断言时保持 UNKNOWN。同一工具调用在后续压缩请求中重复出现只按 call_id 计一次，各冻结请求共同保留已闭合组；同 call_id 的函数或参数冲突明确失败。

工具场景同时保存机构 `/billing/traces/detail` 的原始 `billing_trace.json` 与 `institution_usage_summary`。无平台用量时，报告使用该摘要明确报告的 prompt/completion tokens；缓存 token、缺失费用及未报告字段继续为 UNKNOWN，不按总调用数推断模型次数，也不把无 token 的 MCP 用量报告自动解释为业务执行未知。

ALWAYS 挂载不要求显式 ACTIVATED 事件，触发准确率对此不评分。`tool_call` 仅覆盖有真实目录声明和外部 TOOL 节点的业务工具；`read_skill_resource` 属于原生 AGENT_ACTION，资源加载须人工核对其 LOADED 回执，不能伪装成外部 TOOL 执行。冻结和提交前同时核对无草稿变更、已发布 revision 详情与当前 Agent/挂载一致，避免冻结草稿却运行旧发布版本。

报告生成 JSON 与 Markdown，包含断言成功率、触发准确率、假阳性/假阴性、时间及用量样本数量。未执行或未终态的样本明确保持不完整。证据只能写入仓库 `.agent-work-state/` 的独立子目录，不能污染 Skill 包、版本目录或源码评测集。

报告的 `success_scope` 固定为 `automatic_assertions_only`，`manual_review_required_runs` 统计冻结场景明确要求独立语义审阅的样本数。自动断言通过不代表业务正确；例如查到了正确结果但最终回答混淆日期口径、取消条件或人数与次数，仍可能不通过业务验收。按场景真值独立进行 semantic-review，不解析人工 PASS 文本替代自动事实。实际 TOOL 已记录 FAILED 或 MCP isError 时属于 FULL 失败证据；重复请求复用失败仍为失败，不归为缺证据 UNKNOWN，也不因 Run completed 改成成功。
