# C：来源绑定的模型记忆提取

[dev.json](dev.json) 与 [heldout.json](heldout.json) 各 16 个已知人工案例，每例最多调用一次模型。来源与标签先于采集固定，不从模型结果倒推预期；两集机制可以重合，不是盲测，也不证明语义家族或真实用户分布的泛化。若根据 heldout 改提示词或实现，该版本即记为已用于调参，后续比较另建未调参集合。

样本覆盖稳定偏好、单次要求、否定和历史引文、第三方话语、未来计划与未知时间、同条件疑似矛盾、不同条件并存、明确更正、同义复述、跨助手范围、敏感内容与空提取。`input` 直接采用正式 schema v2，来源 `occurredAt` 均为绝对时间；明确区间的人工标签另外固定 UTC 两端。`existingFacts` 只是可读事实别名，不含写入权限。模型不能通过 `relation` 获得改写历史的权利。

首次真实观测前，`existingFacts[].object` 已与宿主 `MemoryExtractionWorker.freeze` 的 `MemoryContent` v2 正文形态对齐：原始业务值位于 `value`，适用条件位于 `conditions`，这些已有稳定偏好的 `temporal` 为 `{"kind":"stable","from":null,"to":null}`，不向模型暴露 `revisionProposal`。仅调整输入字段包装，全部来源文本/时间、expected 标签、谓词、事实有效期和修订保持全等；这次对齐发生在任何模型调用前，不属于依据模型结果调参。修正前原始字节与逐项核对回执保留在私有路径 `.runtime-logs/semantic-c-input-alignment-before/`。最终冻结 SHA-256 为 dev `7c5e4e0794f34beb3b289bb11aec2e423625808d20604f1115cd040047ac330a`，heldout `6eaea619e750fcd2a5eea4edc5b4eb0e323972e983e5a2064d073e397d363879`。

`expected.requiredFacts` 按原始含义列出主体、适用条件、范围、时间、敏感属性与关系，不要求模型逐字复述标签或使用人工命名的谓词。`temporaryConstraints` 的 `PASS` 表示没有被错误当成长期稳定事实；允许输出保留 task/interval/unknown 的候选，也允许没有必要记忆的临时内容不提取。`allowedOmission` 明确标注尚未决定的计划可以省略，若提取则必须保留不确定性。`forbiddenClaims` 与 `possibleConflictTargets` 由人工阅读来源及已有事实标注。明确更正仅允许提出 `correction` 关联，同义仅允许提出 `similar` 关联；候选输出本身不能证明治理已自动合并、修订或丢弃。

## 实际执行入口与冻结

[SemanticMemoryExtractionEvaluation](../../../backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/integration/SemanticMemoryExtractionEvaluation.java) 位于既有 `liveMemoryAcceptance` 源集，公开 `evaluate` 方法由宿主隔离夹具传入正式 `SystemAgentExecution`、模型与提示词已发布的配置引用、显式预算和输出目录。它调用现有 `executePurposeControlled("memory-extraction", ...)`，输入通过正式 `encodeInput` 冻结，并调用 `MemoryExtractionCandidates.parse(output, typedInput)` 得到正式候选实体；不另建模型执行器，不复制 parser、自然语言筛选或治理逻辑，也不修改 `MemoryLiveAcceptance` 的原有 24×3 矩阵。

固定 Suite/Scenario、批次、样本、阶段、尝试、用量、预算、配置与数据库回执由本域 [SemanticMemoryExtractionReports](../../../backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/integration/SemanticMemoryExtractionReports.java) 的 DTO/record 持有字段。模型输入直接使用生产 `ExtractionInput`，已有事实正文复用 `MemoryContent.Content`；只有开放的人工断言 expected/observed 和业务 value 保留 JSON 值。序列化限定在模型与报告文件边界，报告仍使用原 camelCase 字段，数据库投影回执保留原 snake_case 列名，不随宿主 HTTP 全局命名设置变化。Python 指标的固定输出也由本域 dataclass 定义，报告 schema、指标和门槛保持原样。

真实调用前由根协调者冻结模型 ID/修订、系统 Agent 配置版本、提示词 SHA、调用数、每次输出上限、Token 预留、总预算、超时与实际价格来源。源样本里的 `budget.approval=PENDING_ROOT_CONFIGURATION_FREEZE` 和 `evidenceStatus=NOT_RUN` 永远只描述计划；不会在采集后回写成证据。实际批准值由 runner 的 `Limits` 与 `configurationEvidence` 固定到 `batch.json`，不把未批准的示例配置当成可付费授权。

输出目录必须为空。runner 在调用前先保存原始 `suite.json`、套件 SHA、源码 SHA、完整 16 个计划位置，以及当前样本 `STARTED_NO_AUTOMATIC_REPLAY`。每个完成回执保留系统 Agent/run/version、配置与调用引用、原始输出和 SHA、精确冻结输入、正式解析候选、状态、错误码、用量和耗时。未知、缺用量、超时、预算不足或配置漂移不转换成空候选、不重新发送，后继保持 `NOT_RUN`。已确定的 `FAILED` 仅在原输出与完整用量齐全，且同一正式 parser 重验得到的协议错误码与 Run 错误码一致时，保留样本 `FAIL` 并继续后续独立样本；generic 异常仍停止。已有目录拒绝覆盖；失败后另建目录只能用于经协调者判断的新批次，不能把新成功冒充旧回执已确定。

每次调用的输出上限、超时和取消通过正式 `ExecutionLimits` 进入请求。调用数、Token 预留与总量计数属于本 runner 的受控上限，不是生产数据库预算/外部调用账本验收；完整已知用量可替换该次预留，未知保留预留并停止后继，超出预留明确停止。实际供应商配置是否使用真实账本必须在 `configurationEvidence` 说明。当前只从正式 System Agent `RunView` 保存用量到 `systemAgentCalls`，不伪造 Runtime attempt 或 wire 观测；`runtimeCalls`、`wireCalls` 为空不代表模型调用零次，模型调用与已知 Token 汇总由本目录的指标器单列。

## 独立真实采集夹具

[SemanticMemoryExtractionLiveAcceptance](../../../backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/integration/SemanticMemoryExtractionLiveAcceptance.java) 继承既有 `AbstractIntegrationTest` 的临时 PG、Redis、完整 Flyway 和资源清理，不加载来源业务库或 `.env`，不替换正式模型调用、预算与计费 Bean。它只在本类临时 PG 中保存加密后的临时凭据、发布模型修订 1 及当前 `PromptDefaults` 的 `memory-extraction` 正文，通过生产 `SystemAgentExecution` → `LlmService` → Provider 调用。平台表沿现有测试平台访问方式写入，不向业务连接追加授权。

该类只由根协调者配置的独立显式 Gradle 任务选择，默认 offline、原 live 和 browser 不触发。运行参数由受控进程环境提供，密钥不写脚本或文档：`SKILLFORGE_SEMANTIC_C_LIVE_ACCEPTANCE=1`、`SEMANTIC_C_CHAT_API_KEY`、`SEMANTIC_C_CHAT_MODEL_ID`、`SEMANTIC_C_SUITE_ROOT` 和 `SEMANTIC_C_EVIDENCE_DIR`。两个目录参数必须为绝对路径；输入根包含本目录冻结的 dev/heldout，输出根必须为空。模型须先由协调者在官方发现入口确认，endpoint 固定为 `https://api.deepseek.com`，不接受来源业务库地址或继承其它任务的外部数据库 lane。

本轮固定 dev/heldout 共 32 例、每例最多一次，输出上限 2048 Tokens，每次预留 10000 Tokens，总预留 320000 Tokens，调用超时 45 秒，完整批次超时 20 分钟。heldout 继承 dev 后剩余的次数、Token 与时间，不重新发放完整总预算。配置发布后冻结系统 Agent 版本、model ID/revision、prompt SHA 与去凭据配置 SHA。价格未获批准时为 `UNKNOWN`。

此夹具使用正式外部调用守卫、辅助模型维护根预算和 CostRecorder；批次总上限仍由 runner 计数，不声称它是跨样本生产预算根。`capture.json` 在收尾读取本隔离租户的系统任务、外部调用尝试、用量事件和预算状态原始回执；读取失败明确为 `UNKNOWN`。供应商调用与本地计量不等于真实客户钱包结算，外部钱包保持 `NOT_RUN`。已有 `capture.json` 只读原回执；未确定完成时拒绝再次派发，不用新 key 或新配置重发未知调用。

## 人工复核与指标

已有真实采集可用 [SemanticMemoryExtractionReplay](../../../backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/integration/SemanticMemoryExtractionReplay.java) 薄入口重评：在受控进程设置绝对路径 `SEMANTIC_C_REPLAY_SOURCE` 为原 02 根目录、`SEMANTIC_C_REPLAY_OUTPUT` 为尚不存在的新目录，执行独立 `semanticMemoryExtractionReplay` JavaExec 任务。它只读取原始 suite/sample/capture，以当前正式 parser 解析原输出；原始文件完全只读，32 个位置与原 input/output 摘要保持绑定。`sourceCapture` 和 `sourceSample` 保留原 capture/batch/sample SHA，以及原 Run ID、状态、错误码、用量与调用引用；旧 `FAILED` 永久保留在原文件及来源引用中。新 `OFFLINE_CAPTURED_OUTPUT_REPLAY` 阶段的 `PARSED`/`REJECTED` 只表示当前离线解析结果，沿用原 Run/调用身份，不复制旧 `C_SYSTEM_AGENT_COMPLETED` 断言，也不把旧 Run 改为成功。

离线重评的 `systemAgentCalls` 为空，调用数与预留均为零，模型与提示词只引用原冻结来源；原 Provider 用量保存在来源元数据，不计为本次新费用。新协议 gate 只评当前 parser，语义断言继续复用已有逐样本复核与指标器。来源是真实模型采集，当前步骤是已知人工案例的离线重放；后续 Agent 逐例复核须明确标注 Agent reviewer、非人工盲评，不能冒充新的真实模型调用或盲测。

运行结果固定兼容[已有报告与逐样本复核工具](../README.md#语义复核与离线重评)。`C_FROZEN_CONFIGURATION`、`C_SYSTEM_AGENT_COMPLETED` 是工程断言；语义断言初始均为 `UNKNOWN`，`SEMANTIC_REVIEW_REQUIRED`。先生成模板，审阅者阅读完整来源、原始模型输出、正式候选、条件与已有事实，填写实际证据指针、理由和复核时间。字面 quote 存在由正式 parser 校验，quote 在原上下文中是否支持候选全部含义仍需复核，不把摘取了“英文”一词当成默认英文偏好的支持。

```powershell
python -X utf8 -B tools/memory_evaluation/reporting.py report --batch <本次dev目录>/batch.json --suite <本次dev目录>/suite.json --output <新的初始报告目录>
python -X utf8 -B tools/memory_evaluation/reporting.py review-template --batch <本次dev目录>/batch.json --suite <本次dev目录>/suite.json --output <新的review.json>
python -X utf8 -B tools/memory_evaluation/semantic-c/metrics.py --batch <本次dev目录>/batch.json --suite <本次dev目录>/suite.json --review <已填写review.json> --output <新的metrics.json>
```

heldout 单独使用同样流程，不能混入 dev 调参结果。指标器复用已有报告器的批次、样本 SHA、复核身份、证据指针与覆盖校验，不自行解释自然语言。固定分母从源套件的人工标签建立，执行或解析失败不能缩小分母；未运行、未复核保持 `UNKNOWN`，空候选不能取得完美来源支持率。源码中 `C_FACT` 复核候选的完整语义，`C_STABLE` 另供稳定事实漏提取指标，关键项再列 `C_CRITICAL_STABLE`，分别保留用途而不重复累计同一指标分母。

| 指标 | 固定门槛 | 本入口的证据范围 |
|---|---|---|
| 关键临时长期误晋升 | 零失败 | 人工标签与实际候选的时间语义；持久状态由工程治理链另验 |
| 其它临时长期误晋升 | 不超过 0.02 | 同上，按适用标签计分 |
| 稳定偏好漏提取 | 不超过 0.10 | 主体、内容、条件、时间与 scope 都计入；关键稳定事实零错误丢弃 |
| 来源支持 | 1.00 | 逐个实际候选完整含义及 source/quote 上下文人工复核 |
| 疑似矛盾检出 | 至少 0.90 | 同主体、关系、条件与时间重叠的目标别名 |
| 错误自动合并或修订 | 零失败 | 此 runner **NOT_RUN**，必须关联另行真实 PG/HTTP 治理工程证据 |

这些门槛沿用 [A 已预设的后续指标](../semantic-a/README.md#预设后续指标)，不根据本轮结果改阈值。样本很小，比例只能描述这份人工语料，不能提供总体置信或上线质量保证。批次与根回执以 `protocolFailures` / `protocolOutcome` 单列协议结果，完整矩阵的 `COMPLETED` 不抹去其中的 `FAIL`；完整保存原始矩阵和账本后，协议 gate 仍可失败。runner 的 `COMPLETED` 只证明采集完成；未复核语义、未验治理或缺失用量不升级为 C 全部验收通过。

[SemanticMemoryExtractionEvaluationTest](../../../backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/integration/SemanticMemoryExtractionEvaluationTest.java) 使用受控系统任务回执验证文件协议、完整矩阵、原时间、未知不重发和失败不变空提取，不调用供应商。指标器的离线边界测试为：

```powershell
python -X utf8 -B -m unittest discover -s tools/memory_evaluation/semantic-c -p test_metrics.py -v
```
