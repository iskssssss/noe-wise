# 灵知 记忆评测工具

[suite.json](suite.json)冻结 24 类中文机构服务场景，每类独立重复 3 次，共 72 个计划样本。实时运行通过当前正式聊天、记忆提取和治理链路采集私有证据；[reporting.py](reporting.py)只读取已冻结的批次文件，生成离线报告与语义复核模板，不访问数据库、HTTP 服务或模型。测试、判断和证据复用按[评审与验证规范](../../.agents/skills/nw-verification/references/candidate-driven-delivery.md)执行；工具产物不替代真实调用或页面验收。

A「契约与评估基线」的受控生产规则样本、dev/heldout 分集和独立运行方式见[当前语义决策基线](semantic-a/README.md)。该入口不调用真实模型，也不改动现有 24×3 真实样本矩阵。

B「记忆检索」的人工正文/查询、真实 JDBC/pgvector/RRF 三路径及可选供应商向量入口见[检索样本与运行说明](semantic-b/README.md)。真实供应商采集单独显式执行；预采集向量可以反复用于不收费的数据库评估。

## 独立验收入口

[run_acceptance.py](run_acceptance.py)从仓库根目录定位当前 Gradle Wrapper，默认使用仓库根目录下的`.gradle-user-home`缓存，可通过`--gradle-user-home`指定其他目录；环境`GRADLE_USER_HOME`与命令行`--gradle-user-home`同时设置为同一目录。缓存路径只转换为绝对路径，保留 ASCII 目录联接别名，不使用`resolve()`追到中文用户目录目标，否则 Worker 参数文件路径可能再次出现中文字符。固定`--no-parallel --max-workers=1 --no-daemon`串行执行，在需要 Daemon 时由 Gradle 创建本次专用进程，避免复用启动环境不同的已有 Daemon；保留`LANG`、`LC_ALL`和`LC_CTYPE`字符编码工具链环境，不继承任意`GRADLE_OPTS`或 Java 选项环境变量。裸命令等价于`offline`，仅选择不收费的`memoryEvaluationOfflineTest`：

```powershell
python -X utf8 -B tools/memory_evaluation/run_acceptance.py
python -X utf8 -B tools/memory_evaluation/run_acceptance.py offline
```

`browser`选择`memoryGovernanceBrowserAcceptance`，在 Testcontainers PostgreSQL/Redis、独立机构身份和临时 Vite 来源中验收真实记忆治理页面，数据库回执检查模型调用与外部调用为零。需要本地容器服务、Java 21、Node、已安装前端依赖和 Playwright 浏览器；可通过`NODE_BINARY`选择已配置的 Node。证据目录必须尚不存在：

```powershell
python -X utf8 -B tools/memory_evaluation/run_acceptance.py browser `
  --evidence-dir .agent-work-state/memory-evaluation/<批次>/browser-acceptance
```

`live`选择`liveMemoryAcceptance`，按显式`LIVE_MEMORY_SOURCE_MODEL_ID`和`LIVE_MEMORY_SOURCE_MODEL_REVISION`读取来源库当前已发布的`deepseek-flash`模型并在隔离数据库内执行冻结的 72 个样本。聊天与自动提取共同受 100 万输入输出 Tokens 和 2 小时预算约束。来源配置通过显式`--env-file`提供，启动器只解析`SPRING_DATASOURCE_URL`、`PLATFORM_DATASOURCE_USERNAME`、`PLATFORM_DATASOURCE_PASSWORD`、`SECRET_KEY`及上述两个模型选择键；不会将整份环境文件或继承的业务连接注入隔离服务。值支持`KEY=字面值`或完整单双引号，按原样传入进程，不执行、不插值、不反转义。缺字段只输出字段名；来源只读访问模型与当前提示词，不对来源库写入评测数据。

独立`liveMemoryAcceptance`源集仅依赖主源码输出与测试依赖，单独编译自包含的`AbstractIntegrationTest.java`基类，不依赖整个普通测试源集的输出或触发`compileTestJava`。真实测试使用当前仓库默认记忆提取提示词，并在批次中记录其与本地已发布提示词的摘要差异；评测夹具将每次输出上限固定为`max_output_tokens=2048`，来源模型的原值和覆盖记录写入`batch.model`，结果仅适用于这一冻结配置。

```powershell
python -X utf8 -B tools/memory_evaluation/run_acceptance.py live `
  --execute-live `
  --env-file .env `
  --evidence-dir .agent-work-state/memory-evaluation/<批次>/live
```

真实入口必须显式带`--execute-live`；漏开关时不会读取来源配置或启动 Gradle。固定套件绝对路径由启动器设置，模型选择只来自显式环境文件，不接受继承的环境替换。模型 ID 与修订必须为正整数，Java 来源查询使用参数绑定，并核验当前发布指针、配置身份、供应商和冻结模型约束。每个`live`或`browser`执行输出新目录的`launcher-start.json`、`launcher-finish.json`和私有`gradle.log`，控制台不打印配置值。Live 结束收据同时保留 Gradle 退出码、完整 72 位置的样本统计和基础设施失败数；即使 Gradle 返回零，证据缺失、不完整矩阵或基础设施失败也返回`3`。完整执行中语义`UNKNOWN`仍保持待复核，样本`FAIL`仍保留为效果失败，任务成功不代表正式验收通过。浏览器页面证据位于该目录的`browser/`；真实样本与`batch.json`位于`live`目录。旧目录拒绝重用，失败后不自动重发模型请求。`--dry-run`仅验证参数、路径、四个来源键及两个模型选择键，不创建目录或运行 Gradle；`--gradle-user-home`可以显式选择其他缓存路径。

对应 Gradle 任务可在`backend-java/`手动运行；真实入口的同名门禁变量和四个来源键必须由受控进程环境提供，优先使用启动器以避免将整个`.env`注入测试服务：

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home :skillforge-institution-service:memoryEvaluationOfflineTest
.\gradlew.bat --gradle-user-home .gradle-user-home :skillforge-institution-service:memoryGovernanceBrowserAcceptance
.\gradlew.bat --gradle-user-home .gradle-user-home :skillforge-institution-service:liveMemoryAcceptance
```

## 生成报告

从仓库根目录执行，`--batch`指向实时入口生成的批次文件，`--output`必须是尚不存在的新目录。默认套件为此目录下的`suite.json`，若使用另一套件必须显式提供`--suite`，其原始字节 SHA-256 必须与批次一致。

```powershell
python -X utf8 -B tools/memory_evaluation/reporting.py report `
  --batch .agent-work-state/memory-evaluation/<批次>/live/batch.json `
  --output .agent-work-state/memory-evaluation/<批次>/report-01
```

成功退出码为`0`；证据缺失、身份或摘要不匹配、非法结果、目录逃逸和覆盖历史请求退出`2`。输出包含`report.md`、`report.json`、`evidence-index.json`和`review-template.json`。传入复核时还生成`review-receipt.json`。原始样本、前次报告和已有复核文件不会被覆盖；离线重评创建`report-02`等新目录。

总体始终保留 72 个计划位置，分别统计`PASS`、`FAIL`、`UNKNOWN`与`NOT_RUN`，同时显示完整分母和已执行样本分母的通过率。维度统计只包含该维度适用的场景；缺少适用维度断言保持`UNKNOWN`。源断言与源样本结果保留在`sourceOutcome`，有效结果按确定性断言和已完成复核重新计算，避免词面假失败或待复核结果成为正式质量结论。

最终汇总先冻结实际`batch.json`和样本，再关联本次候选快照、`preflight.json`、精确工程 XML 与浏览器结果/回执。批次的`COMPLETED`只表示执行器结束循环，无法替代样本断言、真实供应商调用、事实落库或语义复核。报告工具直接消费批次、套件、复核、工程摘要/精确 XML 和可选价格；浏览器`playwright-results.json`、B01–B05回执、数据库资源回执及候选快照作为独立汇总证据，不能用普通 JUnit 通过替代页面结果。

报告展示提取、检索、回答使用、治理、安全与隔离、可靠性六个维度。`NATURAL_PROBE`与`DIAGNOSTIC`阶段分别保留；只有实际捕获的`retrievals`产生检索轨迹计数，回答包含事实词语不证明调用`search_memory`。提取候选、SessionLog、作业、持久事实、检索轨迹及实际模型响应通过原始文件 SHA-256 和 JSON 指针索引。

## 语义复核与离线重评

`DETERMINISTIC`断言直接计入结果；`TEXT_HEURISTIC`和`SEMANTIC_REVIEW_REQUIRED`只有经逐样本复核才能成为正式`PASS`或`FAIL`。模板默认`UNKNOWN`，审阅者、时间、评语和证据引用为空；工具不会生成虚构的人或模型评分。

```powershell
python -X utf8 -B tools/memory_evaluation/reporting.py review-template `
  --batch .agent-work-state/memory-evaluation/<批次>/live/batch.json `
  --output .agent-work-state/memory-evaluation/<批次>/review.json
```

审阅者阅读原始样本的完整回答、限定条件、实际候选及事实和检索证据，再逐条填写`reviews`：

- `sampleId`、`sampleSha256`、`dimension`、`assertionId`、`assertionIndex`和`assertionCode`保持模板绑定值；`assertionId`是断言维度、原始编码和数组位置的摘要，编码包含秘密时公开编码被脱敏，摘要仍准确绑定原检查。同编码的多阶段检查分别复核；重排后样本字节摘要变化，原收据失效。
- `outcome`填写`PASS`、`FAIL`或`UNKNOWN`；无法可靠判断仍为`UNKNOWN`。
- 形成`PASS`或`FAIL`时填写`reviewer`、包含时区的`reviewedAt`、具体`reason`和至少一个`evidenceRefs`；引用使用样本内实际存在的 JSON 指针，如`#/stages/1/answer`或`#/stages/0/facts/0`。

```powershell
python -X utf8 -B tools/memory_evaluation/reporting.py report `
  --batch .agent-work-state/memory-evaluation/<批次>/live/batch.json `
  --review .agent-work-state/memory-evaluation/<批次>/review.json `
  --output .agent-work-state/memory-evaluation/<批次>/report-02
```

批次、套件或样本字节变化后原复核收据失效，应针对新冻结输入创建模板。复核不能改写确定性断言；执行和设施失败须有确定性断言。既有失败证据保留，修正测试设施或断言后另建实时批次。

## 收集本次工程验证

优先使用本次验证后精确复制到批次目录的`summary.json`及其同目录`<module>/TEST-<class>.xml`。摘要为数组，每项包含`module`、`class`、`tests`、`failures`、`errors`与`skipped`；工具必须核对真实 XML 的`testcase`计数和结果。`--junit-not-before`填写本次冻结验证开始时间，必须包含时区；新鲜度依据 XML 文件 mtime，不猜测无时区的 JUnit `timestamp`。

```powershell
python -X utf8 -B tools/memory_evaluation/reporting.py report `
  --batch .agent-work-state/memory-evaluation/<批次>/live/batch.json `
  --engineering-summary .agent-work-state/memory-evaluation/<批次>/engineering/contract-runtime-inmemory/summary.json `
  --engineering-summary .agent-work-state/memory-evaluation/<批次>/engineering/jdbc-starter/summary.json `
  --junit-not-before 2026-10-11T15:47:44.110570+00:00 `
  --output .agent-work-state/memory-evaluation/<批次>/report-03
```

也可以显式提供一个或多个`--junit-dir`及精确`--junit-suite`全限定类名。无`TEST-`前缀的前端报告通过`--junit-file <精确文件>`及`--junit-suite src/api/memories.test.ts`收集。仅白名单 suite 被收集；不递归扫描构建目录，不匹配宽泛类名前缀。目标 suite 缺失或零测试记`UNKNOWN`；报告早于时间界、相同 suite 重复来源或摘要与 XML 不符会拒绝生成。没有提供本次工程证据时记`NOT_RUN`，不能借其他批次报告宣称通过。

## 用量、费用与公开材料

Runtime 调用中的`usage.inputTokens`和`usage.outputTokens`是累计依据；优先采用完整批次调用账本，包含停止时尚未关联样本的在途调用，缺少批次账本时回退到样本证据并标注`callScope`。同一`attemptId`重复捕获只累计一次，冲突计量拒绝生成。wire 调用次数和耗时另外统计，避免对同一次调用重复计费。缺少任何调用输入或输出计量时，对应总量为`null`，Markdown 显示`UNKNOWN`，已知小计和缺失数量另列。默认费用为`UNKNOWN`；需要估算时通过`--prices`提供 JSON 对象，例如`{"currency":"CNY","inputPerMillion":2,"outputPerMillion":4}`。示例仅说明单位，不声明供应商价格；实际价格由执行者提供。未完成样本不会生成虚构 Tokens。

公开 Markdown、JSON、复核收据和模板不复制原始供应商正文、事件或作业载荷，只保留受控概要与证据索引。认证字段、Header、Cookie、私钥、秘密样本标记以及输入、回答和评语中的常见秘密表达被脱敏。原始`sample-*.json`可能包含用于 M21 的合成秘密与内部链路载荷，仅保留在私有批次证据目录，不直接公开。

## 结果格式与本地验证

批次 JSON 的`schemaVersion=1`，包含`suiteId`、`batchId`、`startedAt`、`model`、`prompt`、`suiteHash`、`plannedSamples`、`stopCode`、`budget`以及`samples[{sampleId,path,outcome}]`。样本路径相对于批次文件所在目录，禁止绝对路径和目录逃逸；已执行样本缺文件会拒绝生成。原始样本包含`schemaVersion`、`sampleId`、`scenario`、`repeat`、身份、`stages`、`assertions`、`outcome`、`failures`、`runtimeCalls`与`wireCalls`。

断言维度为`EXTRACTION`、`RETRIEVAL`、`ANSWER`、`GOVERNANCE`、`SAFETY`或`RELIABILITY`，结果为`PASS`、`FAIL`或`UNKNOWN`，方法为`DETERMINISTIC`、`TEXT_HEURISTIC`或`SEMANTIC_REVIEW_REQUIRED`。同一样本的多阶段检查允许编码重复，各自按位置与样本摘要绑定。六个维度适用矩阵归报告工具，新增场景可在套件场景对象中显式提供`dimensions`。

报告边界单测覆盖完整分母、语义复核绑定与覆盖限制、真实证据索引、秘密脱敏、未知用量、精确 JUnit 新鲜度与零测试、摘要计数和禁止覆盖历史。使用 Python 标准库，无额外依赖：

```powershell
python -X utf8 -B -m unittest discover -s tools/memory_evaluation -p test_reporting.py -v
python -X utf8 -B -m unittest discover -s tools/memory_evaluation -p test_launcher.py -v
python -X utf8 -B tools/docs/verify_md_links.py tools/memory_evaluation/README.md
```
