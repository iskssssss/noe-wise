# AGENTS.md

灵知 是面向 B 端机构的 AI Agent 与 Workflow 平台，采用 Java 21 / Spring Boot 3、Vue 3 / TypeScript，当前主线为 MVP-0 Agent Runtime。项目定位见 [README](README.md)；修改 Runtime 查 [Harness 架构](harness-design/02-微内核插件架构实现思路.md)，修改宿主查[技术架构](docs/02-架构与设计/01-技术架构.md)。

## 最高优先原则

1. **保障系统架构正确性，不兼容历史任何实现。** 按当前已批准架构与契约实施。
2. **快速推进，按模块批次实施，关键不确定性先验证，稳定后集中验收，不过度审计。** 满足完成标准即交付。

适用范围、授权与必要验收见[开发流程](.agents/skills/nw-workflow/references/development-workflow.md#项目最高优先原则)。

实现遵循最小必要复杂度：

1. **防过度防御**：几乎不会发生的边界或并发问题，先讲清发生概率并经用户确认后再加防御，严禁为“理论上更稳”私自膨胀代码。具体要求见[范围与确认](.agents/skills/nw-workflow/references/development-workflow.md#自主执行边界)。
2. **入口单次校验**：外部数据只在边界校验一次，内部靠类型系统流转，禁止层层重复判断。具体要求见[命名与类型安全](.agents/skills/nw-engineering/references/engineering-standards.md#命名与类型安全)。
3. **拒绝提前抽象**：单次使用的逻辑就近内联，不随意提前拆分无意义的 helper。具体要求见[函数组织](.agents/skills/nw-engineering/references/engineering-standards.md#函数控制流与可变数据)。
4. **控制复杂度**：非必要不用 `try/catch`，能在单个函数中清晰完成的逻辑不硬拆。具体要求见[错误处理](.agents/skills/nw-error-handling/SKILL.md#错误与处理责任)和[函数组织](.agents/skills/nw-engineering/references/engineering-standards.md#函数控制流与可变数据)。

## 启动与任务执行

首次任务读[开发流程](.agents/skills/nw-workflow/references/development-workflow.md)，再按[技能任务表](.agents/skills/nw-workflow/SKILL.md#按任务加载)读取相关主题；恢复从[状态摘要](.agents/skills/nw-workflow/references/development-workflow.md#工作状态快照)接续，已读且未变化的规则不重读。

## 仓库结构

```text
backend-java/       Java 多模块后端，模块清单见 settings.gradle.kts
frontend-admin/     机构后台
frontend-platform/  平台后台
frontend-chat/      独立会话端
frontend-shared/    两个后台的共享能力
harness-design/    Runtime 需求、架构、契约与实施方案
docs/              产品、宿主设计与运维
.agents/skills/     按任务加载的 nw-* 技能
tools/、deploy/    通用工具与部署资料
```

## 常用命令与验证入口

- **服务启动或重启**：使用[本地启动指南](docs/04-指南与运维/01-开发与部署指南.md#53-本地启动)中的当前入口与就绪检查。
- **工作树创建或迁移**：使用[工作树创建与迁移](.agents/skills/nw-workflow/references/development-workflow.md#工作树创建与迁移)中的入口与核对要求。
- **代码验证**：按[风险与测试路由](.agents/skills/nw-verification/references/candidate-driven-delivery.md#测试路由)选择受影响模块，不默认运行全套检查。
- **文档检查**：仓库根目录运行 `python -X utf8 -B tools/docs/verify_md_links.py <文件或目录...>`；广泛导航、目录或生成来源调整用 `python -X utf8 -B tools/docs/check.py`。

## 工程与文档导航

- **工程实现**：[工程技能](.agents/skills/nw-engineering/SKILL.md)；后台共享范围见[共享层设计](docs/02-架构与设计/08-前端共享层设计.md)。
- **代码维护**：命名、类型、常量、依赖、安全和资源生命周期按[工程规范](.agents/skills/nw-engineering/SKILL.md)对应主题执行。
- **文档与记录**：[文档技能](.agents/skills/nw-docs/SKILL.md)、[文档导航](docs/README.md)。
- **数据清理**：[清理与保留规范](docs/04-指南与运维/11-数据清理与保留规范.md)。
- **界面交互**：[UI 技能](.agents/skills/nw-ui-ux/SKILL.md)按产品界面定位设计、控件规格与验收要求。
- **术语**：[术语表](docs/03-规范与术语/03-术语表.md#开发工作流术语)。

## 维护本文件

只维护概览、原则摘要和入口；具体规则修改所属正文。[CLAUDE.md](CLAUDE.md)引用本文件。
