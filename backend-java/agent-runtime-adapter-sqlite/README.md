# 桌面 SQLite 适配器

本模块为独立桌面 Host 提供文件 SQLite 存储。执行状态使用当前 `ExecutionStore`，保留 Session 程序实际消费的 Run、Action、Attempt、材料、预算、等待和事件契约；桌面会话元数据由 `SqliteDesktopSessions` 保存。宿主装配见[桌面 Host](../agent-runtime-desktop-host/README.md)。

## 数据与提交

初始化和业务 SQL 均通过 MyBatis Core Mapper XML 执行。数据库启用 WAL、外键、STRICT 表和 IMMEDIATE 短事务；所有模型和工具调用都发生在事务外。状态、预算和事件在同一业务提交中生效或回滚；状态使用显式列，材料正文通过不可变引用保存，不把整个 Run 编码成 checkpoint。

领取和派发检查 revision、单调 fence 与精确租约身份；外部发送还要消耗一次持久发送授权。幂等重放返回原事实，不再次签发调用权。取消或失去所有权后的结果可以保存，但不能重新打开或推进原任务。无结果和 UNKNOWN 保留未知，恢复不会盲目重发。Instant 保存完整秒与纳秒分量。

首版桌面受理限定个人会话、独立主 Run 与实际装配的 MODEL/TOOL 能力，不借本地数据库声称拥有平台 IAM、机构资源、Workflow 或子 Agent 能力。

## 验证

在 backend-java 目录运行：

~~~powershell
.\gradlew.bat :agent-runtime-adapter-sqlite:test
.\gradlew.bat verifyMybatisAccess
~~~

测试使用独立临时数据库文件，覆盖幂等、重开、双实例竞争、单次发送、预算回滚、取消迟到、未知结果、输入确认与工具批次；不依赖原项目数据库或 Docker。具体通过与失败以对应构建报告为准。
