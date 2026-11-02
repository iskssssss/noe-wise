# A：历史语义决策基线

本套件记录 A 采集时生产规则在人工标注样本上的实际表现。它只服务 A「契约与评估基线」，实现与证据入口复用[记忆评测工具](../README.md)。

**当前版本的记忆提取已在 C 切换为来源绑定的 v2 契约。A 的旧候选输入未包含来源、时间、条件与关系，已退役；当前 runner 对记忆分支记录 `RETIRED_INPUT_SCHEMA` / `NOT_RUN`，样本结果保留 `UNKNOWN`，不补造 v2 字段，也不再声称旧 A 完整复跑。** 交付解析分支仍可独立观测，新增报告明确 `productionRulesChanged=true`。下文对 A 原始候选过滤输出的说明只适用于历史冻结采集。

当前自然语言模型提取使用 [C 样本与 runner](../semantic-c/README.md)。

[dev.json](dev.json) 和 [heldout.json](heldout.json) 各 24 个样本：12 个受控候选过滤、12 个交付要求解析。两集使用不同人工案例与 ID，预期来自原始用户语义；A 不依据结果调生产规则。`family` 只是唯一案例标签，既有文件读取、格式选择、否定等机制可以跨集重合。标签唯一性检查只检查案例元数据，本版不证明语义家族隔离，也不证明跨机制泛化。样本覆盖中文改写、否定与双重否定、引用、临时与稳定要求、中英文、数组顺序与字面值、多格式、条件、局部修订。

## 直接运行

Runner 在现有 `liveMemoryAcceptance` 源集中，独立小任务只选择 [SemanticDecisionBaseline](../../../backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/artifact/SemanticDecisionBaseline.java)。默认 offline、live 和浏览器任务均不触发它。C、D 替换生产入口后，旧记忆候选和交付正则两支均只保留退役状态与原输入，输出 `NOT_RUN` 观察和 `UNKNOWN` 断言；不能据此重算或覆盖 A 的历史生产规则得分。它不调用模型、数据库或常驻服务，原基线结果以已冻结报告为准。

在 `backend-java` 目录指定一个新的证据目录：

```powershell
$env:SEMANTIC_A_BASELINE_DIR = '.runtime-logs/semantic-a-baseline-01'
.\gradlew.bat --gradle-user-home .gradle-user-home :skillforge-institution-service:semanticDecisionBaseline
```

运行产生 `baseline.json`，以及 `dev/`、`heldout/` 各自的 `suite.json`、`batch.json` 和样本文件。记录当前 Git revision、两份生产源码、runner 和样本原始字节的 SHA-256；未提交候选以这些精确源码哈希绑定。目录中已有文件拒绝覆盖，修正后另选新目录。

在仓库根目录使用已有报告器，分别报告两个集合；报告器不会运行生产代码或模型：

```powershell
python -X utf8 -B tools/memory_evaluation/reporting.py report --batch .runtime-logs/semantic-a-baseline-01/dev/batch.json --suite .runtime-logs/semantic-a-baseline-01/dev/suite.json --output .runtime-logs/semantic-a-baseline-01/dev-report
python -X utf8 -B tools/memory_evaluation/reporting.py report --batch .runtime-logs/semantic-a-baseline-01/heldout/batch.json --suite .runtime-logs/semantic-a-baseline-01/heldout/suite.json --output .runtime-logs/semantic-a-baseline-01/heldout-report
```

复用已有 `PASS`、`FAIL`、`UNKNOWN`、`NOT_RUN` 语义和原样本哈希/证据指针。所有样本明确标记 `CONTROLLED_PRODUCTION_RULE_BASELINE`。候选过滤归现有 `EXTRACTION` 维度，交付解析归 `GOVERNANCE` 维度，具体路径由样本 `kind` 区分；它们都不能被解读为真实模型端到端效果。

JUnit 成功只表示基线已完整采集；候选数量相同本身不能取得完整内容通过。所有 Memory 样本的人工 `expected.expectedCandidates` 明确列出预期保留的候选全字段；预期丢弃填空数组，精确去重样本明确保留输入第 1 项。比较生产返回的完整候选数组，覆盖 object 内嵌值、数组次序、文字大小写、空格、emoji、scope、signal 和 sensitive 等字段；JSON 对象键顺序不作为差异。标签来自原始人工样本与逐例语义判断，没有读取运行结果生成标签。语义偏离仍写 `FAIL`；不存在的来源支持、选择组合、条件或局部修订表达记录 `SEMANTIC_CONTRACT_UNSUPPORTED` 和 `UNSUPPORTED` 实际状态，不伪造 PASS。普通当前文本解析不能证明完整 Goal/DAG/版本修订链的行为，相关实际链保持未评；这类样本只记录当前解析输出和公开表达能力缺口。`excludedFormats`、选择组合、未解除条件与局部修订字段均声明对应 `unsupportedFeatures`；MD11 的后续 USER 共享确认治理声明 `notEvaluated`，产生 `NOT_RUN` 观察及 `UNKNOWN` 样本结果。标注理由和关键负向分类放在 expected 之外，只作案例注释；runner 检查未比较的 expected 字段必须有明确 `UNSUPPORTED` 或 `NOT_RUN` 声明。

## 证据界限

候选 JSON 由人工提供，`sourceText` 只是人工标注的上下文，当前候选过滤入口并不读取它。观察到的错误保留/丢弃只能称为**受控候选过滤差距**，不能算自然语言实际提取漏失或来源支持率。两个字面不同候选被保留，也不证明生产存储没有在后续检索/治理中合并；那些路径使用各自真实依赖验收。

模型语义与实际 JDBC 检索需要独立运行当前入口，不能由受控候选样本推断通过。

heldout 是与 dev 分开的人工样本集，机制与 dev 可以重合，规模有限且不代表真实用户总体分布。它的用途是在不依据该集调参的前提下记录独立案例结果，不构成语义家族隔离或泛化证明。若后续用 heldout 结果调整规则或提示词，应永久记录该版已被用于调参，并换用新版本未调参样本；不能将反复改同批样本后通过称为泛化。

## 预设后续指标

A 的完成条件是建立可追溯当前基线和差距。下列是后续能力目标，不要求当前规则已经通过，不用未运行或 UNKNOWN 当作零失败。

| 指标 | 后续门槛 | 必要证据 |
|---|---|---|
| 范围越权、错误自动合并/修订、关键稳定事实错误丢弃、错误强制交付 | heldout 关键负向样本零失败 | 对应真实链和人工预期；候选过滤不能代完整治理 |
| Recall@5 / nDCG@5 | 至少 0.90 / 0.85 | 人工相关事实 ID 与 0/1/2 相关度标签 |
| 无关记忆注入率 | 不超过 0.05 | 实际注入记录；同时报告召回，空注入不代表效果合格 |
| 临时要求长期误晋升率 | 关键样本零失败，其余不超过 0.02 | 实际模型提取及持久事实 |
| 稳定偏好漏提取率 | 不超过 0.10 | 内容、scope、适用条件均计入 |
| 来源支持率 | 1.00 | 实际 source ID/片段支持，缺证据 UNKNOWN |
| 疑似矛盾检出率 | 至少 0.90 | 主体、关系、条件、时间重叠的人工标签 |
| 明确交付漏识别率 | 不超过 0.05 | 明确要求样本为分母 |
| 多格式组合/局部修订准确率 | 至少 0.95 | 完整结构与基版本；flat 格式集合不等于选择组合 |
| 成本与延迟 | B/C/D 正式比较前按实际配置冻结上限 | 调用数、输入输出 Tokens、首轮/检索/索引延迟；A 没有真实调用，不填假值 |

这些阈值先于后续实现固定。若产品范围或预算需变更，应写明变更及原因，再执行新的比较，不在同一结果上改门槛。
