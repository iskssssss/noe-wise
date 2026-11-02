# Core 最终公开回复格式补充 oracle

此工具离线检查已经冻结的 Core `chat-tools-7` 证据中的最终公开回复。它是独立的机械格式维度，没有修改 Core 原自动断言、原 `manual_review` 结果、运行器、Provider、提示词或生产代码，也不发起 API 或模型调用。

适用范围由每个 lane 的冻结 `plan.json` 决定：suite 必须为 `chat-tools-7`，profile 必须为 `behavior` 或 `readonly`，其已冻结 prompt 必须以前置公共 `PROTOCOL` 字符串精确开头。工具用 Python AST 从 `tools/chat_regression/behavior_suite.py` 读取该常量，不导入 Core 模块。报告保存常量 SHA、冻结 plan SHA 与 profile prompt SHA，只保存无隐私的格式摘要，不输出完整 profile、canary 或隐藏模型输入。

只有同时具有冻结 evidence、原 evaluation、完整的 trace/audit/terminal/context/quiescence、持久化终态、没有 blocking reason 的用例才进入检查。实际回复只取 `observable_output.visible_events` 中 `message.delta`、`phase=final`、`state=completed`、`durability=persistent` 的内容。无最终回复时记录 `NOT_REVIEWED`；不会从 `answer`、隐藏模型输出或已取消 Run 推断一份回复。重复投递的相同消息合并，每份不同的最终回复都检查。

当前 consumer 接受整个消息为一个 JSON 对象，或整个消息仅为一个 JSON 代码围栏。对象必须恰有 `status`、`value`、`url`、`message` 四个字段；`status` 为 `success`、`unavailable` 或 `denied`；`url` 为字符串或 null；`message` 为字符串。`value` 可以是任何合法 JSON 类型。前后叙述、多个对象或围栏、重复键、非标准或非有限数字、缺失或额外字段、工具回执形状均失败。

格式 PASS 只表示该机械契约满足。原值忠实度、工具调用次数、写入安全、最终解释准确性和实际任务完成仍由各自 oracle 与独立语义评审负责。例如 B09 可以格式 PASS，同时因三次永久 503 查询违反该用例 max1 而过程 FAIL。取消用例没有最终回复时，格式结果仍为 NOT_REVIEWED，不能抹去已证明的取消过程。

必须显式指定 `--ledger` 或重复 `--lane`，两者互斥。ledger 模式读取其中每个 Core reservation 的 `active_evidence_root`（缺少时使用 `evidence_root`）；相对路径按 `--root` 解析，重复 lane 合并。ledger 可能指向尚未完成的新 lane 或旧的残缺 lane；报告中的三 lane 不等于三次完整有效 live 运行。报告是追加式文件，拒绝覆盖既有输出。报告绑定评审时源码和证据 SHA；当前源码 SHA 与冻结 prompt 前缀只说明评审适用条件，不能追认运行开始源码或部署二进制身份。

PowerShell 使用方法：

```powershell
python -X utf8 -B -m unittest discover -s 'tools/full_project_testing' -p 'test_core_final_format_oracle.py'

python -X utf8 -B 'tools/full_project_testing/core_final_format_oracle.py' --root '.' --ledger '<ignored-ledger-path>' --output '<new-ignored-report-path>'

python -X utf8 -B 'tools/full_project_testing/core_final_format_oracle.py' --root '.' --lane '<frozen-core-lane>' --output '<another-new-ignored-report-path>'
```

测试覆盖整体 JSON 消费格式，以及错误 suite/profile/protocol、未冻结或不完整证据、原 evaluation 缺失、瞬态消息、无最终回复和重复事件等 eligibility 负控。所有文件夹具使用临时目录，保留原 evidence 与 oracle 不变。
