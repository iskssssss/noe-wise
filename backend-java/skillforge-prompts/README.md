# 灵知 系统提示词

本模块维护平台宿主自有的系统规则和系统用途默认正文，按标准 classpath 资源直接加载；不是所有宿主的全局提示词仓库。独立桌面宿主的行为正文随其自身模块打包，共享 Runtime 语义由 Runtime 维护，归属规则见 [Harness 提示词模块](../../harness-design/03-上下文视图与执行优化.md#11-具名提示词模块)。非系统 Agent 和业务模板正文只存在数据库，通过平台模板管理或机构 Agent 管理创建、修改和发布；源码、启动流程和迁移不注入业务角色默认正文。

编辑入口见[提示词导航](NAVIGATION.md)。平台宿主核心、通用聊天行为、计划与 Goal、独立系统用途、协作和成果契约等正文，位于 [src/main/resources/com/skillforge/prompts](src/main/resources/com/skillforge/prompts)。每个模块整篇进入所属调用链；聊天专项模块按冻结能力与当前工具目录选择，不解析具名块，不生成 txt 副本。

工具和 Schema 说明由所属 Tool 定义维护；Runtime 情境正文由 Session 模块维护并随其 JAR 打包。宿主 Java 仅依赖 `agent-runtime-session-spi`，Runtime SDK 不读取宿主源码、构建目录或提示词 JAR。配置来源、条件启用和冻结版本见 [Harness 上下文设计](../../harness-design/03-上下文视图与执行优化.md)。

通用规则按真实调用链保持单一归属：宿主核心负责指令与公开边界，聊天通用正文负责证据与回复，能力正文只补专项行为；工具字段和参数教程留给工具定义。独立系统用途不装配整套聊天能力，摘要用途保留各自的数据归属、来源与续接语义。
