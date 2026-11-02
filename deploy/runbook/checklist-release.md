# 灵知 发布检查清单

> 当前为预发布阶段。涉及 Agent Runtime 的发布必须把应用、schema、测试数据与文档作为一个发布单元，并满足
> [MVP-0 实施方案](../../harness-design/06-MVP-0技术底座实施方案.md) 的当前里程碑门禁。

## 发布前

- [ ] 已按 [Note 治理规范](../../.agents/skills/nw-docs/references/notes-governance.md)核对适用 Note 的范围与证据；普通变更不因发布清单额外建 Note。
- [ ] 按[评审与验证规范](../../.agents/skills/nw-verification/references/candidate-driven-delivery.md)选择的必要验证已通过，有效证据可复用。
- [ ] 文档链接、Mermaid、结构和预算检查通过。
- [ ] 冲突旧入口、旧契约、旧 schema、双读写和兼容适配器已删除。
- [ ] 若包含迁移，目标 schema、一次性数据转换、验收查询和恢复方案已在预发验证。
- [ ] 陪伴样例或复杂 Workflow 样例中受影响的路径已回归。
- [ ] 前端构建、OpenAPI 客户端和 SSE 投影与服务端契约一致。
- [ ] 镜像使用不可变 tag 或 digest，并与验证产物一致。
- [ ] PostgreSQL、Redis及当前启用的外部依赖可达。
- [ ] Secret 来自受控密钥系统，values、日志和工单不含凭证。
- [ ] 当前部署 revision、数据库快照和恢复负责人已记录。
- [ ] 若发布范围包含 Agent 行为评测，已按[行为评测指南](../../tools/chat_regression/BEHAVIOR.md)冻结 18×3（54 个）矩阵、最多 60 秒的结算窗口和 binding；受控 Windows 环境收据绑定当前 deployment revision，且确认评测 workflow 不负责部署业务服务。
- [ ] 初始 54 样本评测没有隐式运行 Judge；若另行执行 Judge，已为该补充评测单独批准预算。36 个未标注校准任务不是已完成的人类数据集；缺少可审计人工金标准时 Judge 保持 `uncalibrated`，不作为发布资格依据。
- [ ] 已区分 `behavior_status` 和 `release_eligible`：前者可以在环境或预算阻断时保留已知通过，后者必须因聚合阻断原因而为 false；`model_version` 未知时不进行横向或成本比较。

## 发布中

- [ ] 应用与 schema 按已验证顺序部署，不单独切回旧 Runtime 路径。
- [ ] 所有工作负载完成滚动发布且无异常重启。
- [ ] live、ready 和依赖健康检查通过。
- [ ] Unified Inbound、普通对话、Workflow dispatch、挂起恢复和输出投递完成最小冒烟。
- [ ] 按 rootInputId/rootOperationId 关联持久业务链，并用 traceId 定位技术尝试；观察 Inbox/Outbox 积压、执行失败和 Connector 失败率。
- [ ] 不通过 legacy/new 开关、旧 API 或旧读路径缓解发布问题。

## 发布后

- [ ] 观察窗口内无未解释的平台级或租户级告警、错误率或队列积压。
- [ ] Registry 与 Execution Ledger 一致，Inbox/Outbox 无异常滞留。
- [ ] 重复输入、恢复和输出投递没有产生重复副作用。
- [ ] 记录部署 revision、镜像 digest、迁移结果和验收证据。
- [ ] 使用 `GH_TOKEN` 运行 `behavior_main.py release-check --root <本地批次目录> --ci-run-id <GitHub Actions run ID> --ci-repository <owner/repository> --expected-binding <冻结 binding JSON>`；该命令调用 `behavior_release.py` 的 GitHub 来源核验，已经验证成功的 `workflow_dispatch` candidate、精确 workflow 路径、run attempt、`agent-eval` 的 `required_reviewers` 规则及对应 `approved` 审批，并确认 `agent-behavior-evaluation-public` artifact 的 `summary.json` 与本地 `root/public/summary.json` 完全相同。任一事实或权限无法验证时不得作为发布证据。
- [ ] 适用 Note 按自身覆盖范围和实际证据维护状态；本次部署完成不自动代表跨阶段 Note 全部完成。
- [ ] 需要恢复时按 [运行手册](../../docs/04-指南与运维/02-运行手册.md) 执行；不得只回滚应用而忽略 schema 和数据。
