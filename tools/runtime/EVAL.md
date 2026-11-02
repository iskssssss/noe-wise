# 会话效果离线评测

`conversation_eval.py` 评测已导出的 Agent Runtime 会话，不调用模型、不重放会话，也不执行任何业务工具。

真实导出可能包含用户内容、标识或工具参数，因此不要提交导出文件。

使用本地副本，并在需要时另建脱敏的 `context.json`：

```json
{
  "scenario": "plan",
  "plan": {"approved": false},
  "tool_effects": {"list_artifacts": "read", "publish_page": "write"}
}
```

运行单条记录：

```powershell
python tools/runtime/conversation_eval.py --trace .runtime-logs/private/export.json --context .runtime-logs/private/context.json --out .runtime-logs/private/eval.json
```

运行后，报告会给出可从 trace 可靠提取的模型调用数、工具调用数、首个助手正文延迟及 token 使用量。源记录没有对应字段时显示 `unavailable`，不能由相邻时间或文本内容推算。

`eval_cases.json` 是合成、脱敏的代表案例。其自动断言只采用结构化 process、状态、工具标识、显式工具副作用和人工提供的场景事实。业务意图、最终答复质量、失败复盘是否有用等语义问题由 `manual_gold` 指引人工评分。评测器从不依据关键词、JSON 字符串或文本相似度宣称语义正确。

人工评分文件示例：

```json
{
  "cases": {
    "failure-fact-review": {"score": "partial", "reviewer": "reviewer-1", "note": "遗漏恢复建议"}
  }
}
```

合并评分：

```powershell
python tools/runtime/conversation_eval.py --trace .runtime-logs/private/export.json --manual-scores .runtime-logs/private/scores.json --out .runtime-logs/private/eval-with-gold.json
```

比较 baseline 和 candidate。两者可以是原始导出或已捕获的评测报告；比较只并列可观察状态、人工分数和指标，证据缺失保留为 `unknown`。

```powershell
python tools/runtime/conversation_eval.py --baseline .runtime-logs/private/baseline.json --candidate .runtime-logs/private/candidate.json --out .runtime-logs/private/comparison.json
```

工具不提供在线回放模式。若未来增加，必须以显式参数启用，并仅允许合成或明确标注为安全的场景；默认行为不得对真实业务执行任何调用。
