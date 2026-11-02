# 系统提示词导航

这里维护平台宿主自有的系统正文。资源内容就是模型可见正文；包外的业务配置、Tool Schema 和 Runtime 消息各归其所有者。独立桌面宿主正文不归本模块，物理归属不由 `SessionPrompt.Type` 分类决定，见 [Harness 提示词模块](../../harness-design/03-上下文视图与执行优化.md#11-具名提示词模块)。
固定正文按真实消费者与用途组织；聊天专项协议按冻结能力与当前工具目录装配。业务 Agent 的角色、模板及发布正文由数据库管理；新增内置业务 Agent 走业务配置导入与发布入口，不在本模块添加角色正文或启动种子回填。

系统运行机制、业务上下文、Skill 方法和当前任务的内容边界统一遵循 [Harness 提示词模块](../../harness-design/03-上下文视图与执行优化.md#11-具名提示词模块)；跨行业适用的任务方法也不因此成为系统规则。

## 正文与加载入口

| 内容 | 资源入口 | 真实消费者 |
| --- | --- | --- |
| 宿主优先级和来源边界 | [chat/core.md](src/main/resources/com/skillforge/prompts/chat/core.md) | [ChatPrompts](src/main/java/com/skillforge/prompts/ChatPrompts.java) |
| 证据、回复、自检和事实状态规则 | [chat/runtime-policy.md](src/main/resources/com/skillforge/prompts/chat/runtime-policy.md) | ChatPrompts；执行与最终答复阶段复用 |
| 执行交互、动作与预算收敛规则 | [chat/runtime-actions.md](src/main/resources/com/skillforge/prompts/chat/runtime-actions.md) | ChatPrompts；仅执行阶段发送 |
| 工具、工作流与 Skill 专项协议 | [runtime-tools.md](src/main/resources/com/skillforge/prompts/chat/runtime-tools.md)、[runtime-workflow.md](src/main/resources/com/skillforge/prompts/chat/runtime-workflow.md)、[runtime-skill.md](src/main/resources/com/skillforge/prompts/chat/runtime-skill.md) | 仅对应能力存在时发送 |
| 记忆、知识、任务与 Goal 协议 | [runtime-memory.md](src/main/resources/com/skillforge/prompts/chat/runtime-memory.md)、[runtime-knowledge.md](src/main/resources/com/skillforge/prompts/chat/runtime-knowledge.md)、[runtime-task.md](src/main/resources/com/skillforge/prompts/chat/runtime-task.md)、[runtime-goal.md](src/main/resources/com/skillforge/prompts/chat/runtime-goal.md) | 按挂载或平台工具选择 |
| 发现、程序调用与代码协议 | [runtime-discovery.md](src/main/resources/com/skillforge/prompts/chat/runtime-discovery.md)、[runtime-program.md](src/main/resources/com/skillforge/prompts/chat/runtime-program.md)、[runtime-code.md](src/main/resources/com/skillforge/prompts/chat/runtime-code.md) | 仅对应平台工具可见时发送 |
| 计划模式规则 | [chat/plan-policy.md](src/main/resources/com/skillforge/prompts/chat/plan-policy.md) | ChatPrompts；仅计划模式发送 |
| 显式 Goal 续轮 | [chat/goal-round.md](src/main/resources/com/skillforge/prompts/chat/goal-round.md) | ChatPrompts；Goal 续行宿主 |
| 可发布的任务推进默认策略 | [defaults/agent-decision-policy.md](src/main/resources/com/skillforge/prompts/defaults/agent-decision-policy.md) | [PromptDefaults](src/main/java/com/skillforge/prompts/PromptDefaults.java)；运营策略配置 |
| 会话画像摘要与画像合并 | [defaults/chat-summary.md](src/main/resources/com/skillforge/prompts/defaults/chat-summary.md)、[defaults/profile-merge.md](src/main/resources/com/skillforge/prompts/defaults/profile-merge.md) | PromptDefaults；系统用途配置 |
| 历史压缩、记忆提取与任务纠正 | [defaults/context-summary.md](src/main/resources/com/skillforge/prompts/defaults/context-summary.md)、[defaults/memory-extraction.md](src/main/resources/com/skillforge/prompts/defaults/memory-extraction.md)、[defaults/task-context.md](src/main/resources/com/skillforge/prompts/defaults/task-context.md) | 独立 MODEL_TASK 用途 |
| 通用系统 Agent 原生动作协议 | [system-agent/action-protocol.md](src/main/resources/com/skillforge/prompts/system-agent/action-protocol.md) | [SystemAgentPrompts](src/main/java/com/skillforge/prompts/SystemAgentPrompts.java)；独立于数据库用途正文 |
| 主/子成员协作政策 | [delegation/primary.md](src/main/resources/com/skillforge/prompts/delegation/primary.md)、[delegation/child.md](src/main/resources/com/skillforge/prompts/delegation/child.md) | [DelegationPrompts](src/main/java/com/skillforge/prompts/DelegationPrompts.java)；按角色与能力启用 |
| 成果准备、冻结、发布及 Goal 结算指引 | [output/preparation.md](src/main/resources/com/skillforge/prompts/output/preparation.md) | [OutputPrompts](src/main/java/com/skillforge/prompts/OutputPrompts.java)；机构成果门禁 |
| 成果答复、发布展示职责与公开边界 | [output/presentation.md](src/main/resources/com/skillforge/prompts/output/presentation.md) | OutputPrompts；执行与最终答复阶段复用 |

## 所有权与编辑方式

- 数据库业务角色、模板和系统用途配置绑定其真实发布版本；源码默认正文不覆盖运营维护内容。来源与优先级以 Harness 契约为准。
- 成果执行规则由 `OutputPrompts.preparationHint()` 经宿主 `SessionOutputContract` 注入；最终阶段改用 `finalAnswerHint()`，两者复用唯一成果呈现正文。聊天能力模块不再复制。代码模块负责业务观察回传，Goal 续轮只携带目标推进要求，冻结回执引导最终业务正文。本轮计划状态由 `DeliverableService.currentOutputPlanState` 读取，再经 `OutputPrompts.runContext` 与历史正式成果分开投影；不从上一轮计划或发布版本推断。
- 工具参数、版本修订、等待及完成字段只由当前原生工具定义说明；计划、委派与 Goal 正文保留任务选择、授权和真实终态约束。成果准备只说明正式交付、版本身份、冻结和发布职责，不复制文件字段教程。独立系统用途链保留自身必要通用边界，不为文本去重引入聊天专项能力。
- [PlatformToolCatalog](../skillforge-core-capability/src/main/java/com/skillforge/core/capability/connector/PlatformToolCatalog.java) 及其分类定义维护工具说明、字段说明及 Schema；短描述不再转换为提示词资源 key。
- [Session](../agent-runtime-session/) 通过实际装配入口维护运行上下文，不打包无生产消费者的情境正文。原生动作、字段、标签、短回执及串并行约束归所属 Runtime 代码。
- 固定正文通过 [PromptResources](src/main/java/com/skillforge/prompts/PromptResources.java) 读取 UTF-8、归一化 CRLF 后保留有效空白；摘要取最终内容字节。动态配置和数据按字面组合，模板参数只插入一次，不二次解释表达式。
- 标准 Gradle 资源处理直接打包；不维护生成器、具名块格式、旧 key 别名或兼容入口。正文变化同步更新实际请求验收和不可变发布来源。
- 用户可见交互默认先给业务结果，再说明实际验证、限制和剩余工作。普通技术标识可用于授权排错；凭据、访问令牌和宿主私有路径不公开，代码、业务 API 字段和业务编号按任务保留。内部工具参数、证据绑定及主子成员核验保留精确引用，不能用统一清洗破坏内部协议。
- 工具 owner 通过 [ProcessPresentation](../skillforge-core-chat/src/main/java/com/skillforge/core/chat/contract/ProcessPresentation.java) 提供明确的业务结果与结构化引用；宿主投影只公开这些字段。[PublicOutputGuard](../skillforge-core-chat/src/main/java/com/skillforge/core/chat/integration/publicread/PublicOutputGuard.java) 按明确敏感信息规则检查用户正文，[ArtifactPublicInspection](../skillforge-institution-service/src/main/java/com/skillforge/institution/artifact/ArtifactPublicInspection.java) 在文件首次公开前检查有界文本与元数据并登记凭据，冻结前再复核。媒体不做 OCR。提示词声明公开边界，程序负责检查和拒绝终止；不在提示词重复教授拒绝后的终止流程。

## 验证入口

[PromptCompositionTest](src/test/java/com/skillforge/prompts/PromptCompositionTest.java) 验证来源分层、发布身份、动态正文的字面组合和计划条件。[机构完整请求快照](../skillforge-institution-service/src/test/resources/prompts/agent-runtime.expected.txt) 与宿主装配测试验证真实组装；Tool Catalog 测试验证实际 Tool/Schema 描述，Session 独立消费者验证最终 JAR。

[请求意图场景集](src/test/resources/evaluation/request-intent-cases.json) 覆盖查询、执行、补充、澄清、权限、记忆、格式继承和成果阶段。静态测试不证明模型遵守规则；真实模型评估必须记录实际配置、调用、结果和终态，未执行明确记为 NOT_RUN。
