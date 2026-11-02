# B：人工检索样本

[dev.json](dev.json) 与 [heldout.json](heldout.json) 各包含 6 个查询及独立人工事实，供真实 JDBC/pgvector 检索验收消费。源样本中的 `evidenceStatus=NOT_RUN` 表示样本本身不是执行证据，执行后也不回写原样本。真实运行的结果另存报告；不能据 JSON 样本推定通过。

两集是不同案例，机制可以重合；本版非盲测，不证明语义家族隔离或泛化。标签来自用户语义而非生产排序分数；后续若使用 heldout 调参，必须记录暴露并另建未调参集合。

`body` 是所属类型正文投影的人工期望输入。`nonBodyMetadata` 仅用于检查 schema、内部身份等不会被当作正文；宿主生产投影应从正式类型中生成文本，不能将整份候选 JSON 拼入检索文本。`defaults` 和查询 `ownerKey`/`subjectKey` 是隔离夹具的符号身份，真实 runner 必须映射为正式 Tenant/Session/Group/EndUser，不能从这些名字生成授权。

`relevanceGrades` 的 0/1/2 依次表达无关、部分相关、直接相关；未列出的可见有效事实为 0。`expectedForbidden` 表达当前权限或状态必排除的身份，不能通过向量/词法高相似度重新准入。它们不参与相关事实分母，越权、旧修订或无效状态命中单独记工程失败。

样本覆盖中文改写、否定、时间不确定性、群公共事实、显式成员主体、元数据噪声、不同领域无关内容与修订。Recall@5、nDCG@5 只对有人工相关事实的查询计算；零相关查询另报错误非空结果率。预算、来源过滤、精确 revision 复核和冻结恢复由正式生产链与对应真实数据库测试验证，不能由样本文件或纯 RRF 单测推定通过。


## 复用真实 JDBC 夹具运行

[SemanticMemoryRetrievalDatabaseEvaluation](../../../backend-java/agent-runtime-adapter-jdbc/src/test/java/com/skillforge/runtime/adapter/jdbc/memory/search/SemanticMemoryRetrievalDatabaseEvaluation.java) 由既有 `JdbcMemoryPortContractTest` 的一个测试调用，复用其 Testcontainers pgvector、完整 Flyway、受限角色与已提交 SessionLog 来源。helper 不复制或新建数据库设施，只通过正式 `MemoryPort.Create/Transition/Query` 写入和读取事实，词法实际经过 Lucene 与 PostgreSQL rank，向量实际经过 pgvector 与 RRF。

实际报告固定包含 `LEXICAL_DATABASE` 和 `HYBRID_CONTROLLED_CONSTANT_VECTOR`，配置真实向量文件时另加 `REAL_PROVIDER_VECTORS_DATABASE`。受控向量刻意对全部正文和查询固定为 `[1,0,0]`，与 `relevanceGrades` 无关，不从人工答案生成模型分数。受控路径只验证真实向量几何、索引和融合链；其 Recall/nDCG 不能作为 embedding 模型的语义效果。报告保留实际候选/采用事实 ID 与 revision、两腿 rank/score、融合分、索引回执和实测查询耗时。语义目标当前仅观测，不要求采集测试已达 B/C/D 目标；forbidden、未知事实混入或正式查询失败仍是工程失败。失败原结果先写样本文件，不把失败当空命中计分。

可通过 `SEMANTIC_B_RETRIEVAL_EVIDENCE_DIR` 指定新的绝对输出目录；未指定时使用本模块 `build/reports/semantic-retrieval-<随机ID>`。每次保留原样本字节与 SHA-256，按 dev/heldout 及各条路径分别汇总，已有报告拒绝覆盖。前一集合结束时通过正式状态迁移归档其活跃事实，下一集合不会混入前一集合的活跃事实。样本中的 revision 用来建立审核后的当前版本；归档事实的实际最终 revision 来自正式 CAS 迁移，报告保存实际值，不将人工初始版本提示冒充最终版本。

该入口直接使用 SDK MemoryPort，HTTP 认证、宿主 AccessPolicy/SourcePolicy、完整 MemoryRuntime 和记忆提取语义均为 **NOT_RUN**，不能因 JDBC 测试通过宣称这些层已验收。未配置真实向量文件时，真实供应商 embedding 语义也是 **NOT_RUN**。既有正式工程套件分别提供对应层的证据。小语料的候选数量有限，Recall@5 的高值也不代表真实大库规模的排序质量；需要后续更大且未调参的真实样本验证。

在 `backend-java/` 运行下列定向入口；`SEMANTIC_B_RETRIEVAL_EVIDENCE_DIR` 需指定新的绝对目录。未设置真实向量文件时只执行词法与常量受控路径，不产生供应商调用：

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home :agent-runtime-adapter-jdbc:test --tests com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPortContractTest.humanLabeledSemanticEvaluationUsesFormalMemoryConsumersAndControlledVectors
```

## 可选的真实 Provider 向量

`SEMANTIC_B_LIVE_EMBEDDINGS_FILE` 指向预先由真实 Provider 采集的向量 JSON 文件。采集前冻结仓库根目录下 `.runtime-logs/semantic-b-live-vector-input.json`：按 dev facts、dev queries、heldout facts、heldout queries 顺序列出所有 `facts[].body` 与 `queries[].text`，不 trim、不归一化，文本 ID 为 `<split>:fact:<id>` 或 `<split>:query:<id>`。manifest 保存两个语料原始文件的 SHA-256，以及每个精确 JSON 字符串值按 UTF-8 编码后的 SHA-256；真实 Provider 调用只使用这些原始文本，不能消费相关标签或用标签生成向量。

向量输出格式如下；数组为格式示意，实际条目必须覆盖 manifest 的全部 26 个唯一文本 ID，维度必须与模型相符，`modelConfigId` 与 `modelRevision` 固定真实采集所用配置版本。`usage` 和 `evidence` 保存采集回执且必须非空；用量未知时可明确写 `{"status":"UNKNOWN"}`，不推定为零。凭据不进入这些文件。

```json
{
  "schemaVersion": 1,
  "inputManifestSha256": "<原始 manifest 字节的 SHA-256>",
  "model": {
    "provider": "<实际供应商>",
    "modelId": "<实际模型>",
    "modelConfigId": "<采集所用配置 ID>",
    "modelRevision": 1,
    "dimensions": 2
  },
  "usage": {"status": "UNKNOWN"},
  "evidence": {"requestId": "<采集回执>"},
  "vectors": [
    {
      "textId": "dev:fact:D-CHANNEL",
      "textSha256": "<精确文本的 SHA-256>",
      "vector": [0.25, 0.75]
    }
  ]
}
```

[SemanticMemoryLiveVectors](../../../backend-java/agent-runtime-adapter-jdbc/src/test/java/com/skillforge/runtime/adapter/jdbc/memory/search/SemanticMemoryLiveVectors.java) 在测试开始时校验原始 manifest、当前语料摘要、逐文本摘要、模型修订、维度及完整 ID 集合。未知、重复或缺失条目、非有限或全零向量、变更的查询文本或模型配置会使对应评估失败，不回退成人工向量。查询仍通过正式 MemoryPort，索引仍通过正式 claim/complete 回执，实际 pgvector 排序与 RRF 结果单独计入第三条路径。报告复制已验证的 manifest 和向量原始字节并保存模型、用量、回执及 SHA-256，不覆盖词法与常量受控路径。

此路径的 JDBC JVM 本身不调用 Provider；`providerCallsInThisJvm=0` 和 `realVectorQueryLookups` 分别记录该边界与文件向量读取次数。真实采集文件加上数据库结果仅证明指定模型在这份已暴露的小型人工语料上的 embedding 检索效果；不证明提取语义、端到端 HTTP/宿主装配或大规模泛化。[协议测试](../../../backend-java/agent-runtime-adapter-jdbc/src/test/java/com/skillforge/runtime/adapter/jdbc/memory/search/SemanticMemoryLiveVectorsTest.java) 使用人工协议夹具，不属于真实 Provider 效果证据。

## 显式供应商采集

[SemanticEmbeddingProviderCapture](../../../backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/integration/SemanticEmbeddingProviderCapture.java) 使用批准的百炼 `qwen3.7-text-embedding-flash` 和既有平台模型 Probe/共享 EmbeddingClient。仅此独立 Gradle 任务会收费，普通 test、offline、liveMemoryAcceptance 和 browser 入口均不选择它。执行前按上文冻结完整 manifest，其字段为 `schemaVersion=1`、`suites.{dev,heldout}.sha256`、`texts[{textId,kind,text,textSha256}]`；kind 为 DOCUMENT/QUERY，顺序和全部精确文本固定，不传入相关标签。凭据由调用进程设置 `SEMANTIC_B_EMBEDDING_API_KEY`，不要写入脚本、命令历史或证据。

在 `backend-java/` 将 `SEMANTIC_B_EMBEDDING_INPUT` 指向上述 manifest 的绝对路径，`SEMANTIC_B_EMBEDDING_OUTPUT` 指向本批专用输出目录，再显式运行：

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home :skillforge-institution-service:memoryEmbeddingProviderCapture
```

每批派发前先保存原 invocation 与 STARTED 回执，成功才保存 READY 向量；已有 STARTED/UNKNOWN/REJECTED 不自动重新派发，READY 批次读取原结果。产物 `vectors.json` 保存精确模型修订、探测维度、26 条向量、用量与证明范围。真实 HTTP 和生产解码已执行，预算/账本接线为 `CONTROLLED_NOT_DATABASE`，不能宣称生产费用结算已验收。Probe 未暴露的 usage 保持 UNKNOWN，不把向量批次的已知 Tokens 当作全调用总量。采集失败应先核查原回执，不改输出目录自动重试同一未知操作。

将得到的 `vectors.json` 绝对路径设置为 `SEMANTIC_B_LIVE_EMBEDDINGS_FILE` 后重用前述 JDBC 入口，即可获得独立第三路径报告，无需重复供应商调用。真实运行的结果与限制记录在本次生成的独立报告中；本指南不附带私有运行证据。
