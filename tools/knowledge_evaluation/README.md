# 固定知识评估集 Q06

[suite.json](suite.json)拥有固定合成文档、8类任务、独立事实判据和工程用例映射。它供真实 PostgreSQL / Runtime 契约测试直接读取；[run_evaluation.py](run_evaluation.py)负责输入冻结与离线报告，不连接服务、不提交聊天、不调用供应商，也不改写已发布 Agent。真实模型运行沿用已有授权执行入口；已导出会话的指标复用[会话效果评测](../runtime/EVAL.md)，不再实现第二套 HTTP、提交或恢复框架。

## 固定输入与判据

所有 tenant、source、document、version、handle 都是合成测试身份，仅供独立测试库装配，不可当成现有环境资源。`documents[].mounted`表示是否属于当前固定目录；真实测试必须通过正式冻结绑定和当前授权入口装配，不能由该布尔值直接代替生产权限判断。真实测试若将合成标识映射到其专用数据库标识，应保留映射与原套件 SHA-256。

|案例|场景|判据|
|---|---|---|
|K01|目录分页|当前挂载24项；默认20项必须续页；无重复、无全文、无未挂载或另一tenant元数据|
|K02|标题筛选|连续子串“差旅”恰匹配生效、历史、草案3项；保留状态，不将筛选拆成多词拼接|
|K03|指定长文续读|25,600个Unicode code points；offset3999为🚀、7999为𠮷；末尾包含验收尾码与最终签核角色|
|K04|跨文档|住宿480元与餐补60元分属两文；一天一晚合计540元并分别引用|
|K05|无答案|资料未规定高管海外家属差旅额度；不得把境内普通员工规则套用为该答案|
|K06|相似干扰|2026生效版480元适用；2025历史版360元、2027草案620元均不适用|
|K07|跨tenant拒绝|当前tenant不能通过已知文档ID读取另一tenant资料，目录与错误不泄露目标内容|
|K08|未挂载拒绝|同tenant身份不扩大当前Run冻结来源；已知未挂载文档ID不产生读取授权|

`oracles`维护目录与过滤结果、长文字符数、非BMP位置和拒绝目标；正文与`expected_answer_facts`维护业务真值。目录按实际契约稳定排序，游标原样回传，read使用半开区间与Unicode code points；精确行为归 [Knowledge API](../../backend-java/agent-runtime-api/src/main/java/com/skillforge/runtime/api/port/KnowledgePortal.java) 和对应测试。目录不是引用证据，全文片段继续沿既有 EvidenceBundle 引用链。

## 本地资产检查与冻结

从仓库根目录执行；自检只证明语料及报告器本身，不证明数据库、授权或模型效果。

```powershell
python -X utf8 -B tools/knowledge_evaluation/run_evaluation.py validate
python -X utf8 -B -m unittest discover -s tools/knowledge_evaluation -p 'test_*.py' -v
python -X utf8 -B tools/knowledge_evaluation/run_evaluation.py prepare --output .agent-work-state/knowledge-evaluation/q06-batch-01
```

`prepare`要求新目录，按原字节复制套件，并冻结正文摘要、套件摘要和配置身份；同时输出`report-initial/report.json`与`report.md`。没有真实模型配置时，身份为`NOT_RUN`，所有模型、预算、工具和来源字段均为`null`，两个评估维度的8个计划位置保持`NOT_RUN`。不得为了填满报告而生成模型、预算或来源身份。

真实运行前可用`--identity <私有identity.json>`冻结`FROZEN`配置，字段定义归脚本的`FrozenIdentity`：tenant与Agent、模型ID/修订、provider/wire_model/推理配置、模型调用/总tokens/总秒数上限、工具schema摘要与真实来源快照摘要必须完整。摘要所指原件由执行方保存在同批私有证据中；冻结配置本身不产生调用授权。不要将令牌、API key或原环境文件写入身份。

## 导入真实契约证据

`cases[].contract_tests`使用精确的`JUnit classname#testcase-name`，不接受类名前缀或“某模块通过”的泛称。Gradle由主线程按模块串行执行，本工具不启动Gradle。先准备批次，再运行所属真实测试，最后用实际XML生成新报告目录：

```powershell
python -X utf8 -B tools/knowledge_evaluation/run_evaluation.py report `
  --batch .agent-work-state/knowledge-evaluation/q06-batch-01 `
  --junit <本批精确TEST-类名.xml> `
  --not-before <本批测试开始时间，含时区> `
  --output .agent-work-state/knowledge-evaluation/q06-report-01
```

时间下界不得早于`frozen.json.prepared_at`，XML修改时间不得早于该下界。只有全部映射用例实际通过才将对应契约计为`PASS`；缺少全部证据为`NOT_RUN`，部分缺少或跳过为`UNKNOWN`，真实failure/error为`FAIL`。重复selector拒绝导入。契约XML不会改变真实模型语义列，K04–K06的语义必须独立复核。

## 真实模型观察与语义复核

本工具只消费已完成的真实运行，不执行付费调用、重放或自动续答。`report --runs <私有runs.json>`导入结构化观察；顶层为`suite_sha256`与`runs`数组，每项固定字段归`ModelRun`：

- `case_id`、`execution_kind="REAL_MODEL"`、原`run_id`与`runtime_status`；
- 与冻结配置匹配的`identity_sha256`；
- 同目录树内`trace_path/trace_sha256`、`answer_path/answer_sha256`、`provider_receipt_path/provider_receipt_sha256`。

trace使用既有导出的`processes/messages`结构，必须能观察到真实模型调用；provider receipt保留实际供应商调用或计量原件，执行者负责核对其真实来源。脚本校验身份与字节绑定，不把文件存在宣称为供应商真实性认证。Mock、合成trace或自检XML只能测试报告器，禁止包装成`REAL_MODEL`证据。缺失用量保持既有指标的`unavailable`，不补零、不推算费用。报告不复制原回答和供应商载荷。

没有人工复核的真实模型样本语义为`UNKNOWN`；没有真实模型运行始终为`NOT_RUN`。`--reviews <私有reviews.json>`可导入`reviews`数组，字段归`SemanticReview`：case、原run、套件摘要、身份摘要、原回答摘要、`outcome`、`reviewer`、含时区`reviewed_at`、具体`reason`和`evidence_refs`。引用只能指向该样本已绑定的回答、trace或provider receipt相对路径。只有真实`completed`运行、绑定完全匹配并具有具名审阅、理由与证据引用时才能记录人工`PASS/FAIL`；回答或配置字节变化后旧复核失效。

人工按每个case的`expected_answer_facts`与`semantic_rubric`核对完整回答、适用范围与真实来源。关键词包含、字符串相似、模型自述或工程测试通过都不能代替语义复核。报告分别统计确定性契约与真实模型语义的`PASS/FAIL/UNKNOWN/NOT_RUN`，两者不合并为总体“知识质量通过”。原批次与报告目录均拒绝覆盖，重评使用新报告目录。
