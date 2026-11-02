# 当前 SDK 正式发布消费验收

本入口在全新临时目录创建独立 Gradle 消费工程。SDK 只从指定隔离 Maven 仓库解析，第三方依赖使用 Maven Central；不使用仓库 project dependency、composite、classes 或 Maven Local。正式 BOM 固定 SDK 版本集合，所有消费配置由 STRICT `gradle.lockfile` 冻结。

工程分别验证两种组合：

- PostgreSQL：正式 Starter 自动装配、JDBC 自有 `RuntimeJdbcSchema.install` / `validate`、持久 Session 创建/激活/关闭、幂等重放、重建读取、冲突 CAS 和受限角色 RLS。初始化只安装 Runtime 自有 schema，不依赖平台迁移或预置平台角色。消费项目自己的 `ConsumerHostIdentity` 通过公开构造入口参与原事务，验证完整身份恢复、补充输入重放和当前撤权拒绝；示例身份事实保存在宿主内存中。
- SQLite：独立 `sqliteTest` 源集与类路径，只消费正式 SQLite/API/Session SPI 构件，拒绝 Spring、宿主模块与测试夹具；真实文件数据库验证 Run 受理、幂等与冲突、租约竞争、纳秒时间、组件重建和租户隔离。

所有 SDK 构件均核对来源仓库、版本和 SHA-256，源码中的 SDK 类型还核对实际 JAR 来源。PostgreSQL 的会话自动装配宿主不注册执行 Host，并检查 `ExecutionHostRegistry` 与 `ExecutionHostWorker` 均未装配；显式设置 `agent-runtime.memory.enabled=false`，检查 `JdbcMemoryPort` 与 `MemoryRuntime` 均未装配，因此无需声明六组记忆治理 bean。另有手工装配的执行 Host 场景，见下文。媒体和外部秘密能力继续显式拒绝。本入口不覆盖真实模型、MCP、进程崩溃恢复或生产环境验收。

运行器要求指定全新空 work/reports 目录、隔离 Gradle cache 和准确发布版本。PostgreSQL 地址、管理员账号和密码分别通过 `SDK_CONSUMER_JDBC_URL`、`SDK_CONSUMER_JDBC_USER`、`SDK_CONSUMER_JDBC_PASSWORD` 提供，不写入消费源、属性或结果报告。仅可使用明确授权的全新隔离 PostgreSQL 服务；数据库测试拒绝已存在 public 表的库，并新建非 superuser、`NOBYPASSRLS` 消费角色。数据库和角色由运行环境管理，运行器不删除它们。SQLite 使用测试临时文件。

从 `backend-java` 调用：

~~~bash
python3 ../tools/sdk-published-consumer/verify.py \
  --sdk-repository "$PWD/build/sdk-ci-repository" --sdk-version "$sdk_version" \
  --work-dir "$RUNNER_TEMP/sdk-published-consumer" \
  --reports-dir "$PWD/build/sdk-consumer-evidence" \
  --gradle-executable "$PWD/gradlew" \
  --gradle-user-home "$RUNNER_TEMP/sdk-consumer-gradle"
~~~

普通验收必须携带受控 `project/gradle.lockfile`。修改依赖或 BOM 后，在全新准备目录使用同一命令追加 `--write-locks`，只执行实际解析，在报告目录生成 `proposed-gradle.lockfile`；审查该生成差量后更新受控模板，再在新的 work/reports 目录执行正常验收。不得手工拼写锁。锁准备、源码编译或 JAR 来源核对均不等于数据库消费通过。

Windows 使用 `gradlew.bat` 和明确的英文 Gradle 缓存路径，例如 `--gradle-user-home .gradle-user-home`。运行器同时将子进程 `GRADLE_USER_HOME` 设为该路径，并使用 `--no-daemon --no-parallel --max-workers=1`；这只影响本次运行，不修改外部全局配置。

Gradle 8.14 的依赖锁与内置动态版本失败模式互斥；工程在保留 STRICT 锁的同时，显式拒绝动态 selector、rich version、changing dependency/component metadata 和 SNAPSHOT。BOM 管理依赖的空版本不属于动态版本。

正常结果必须同时包含 PostgreSQL `test` 和 SQLite `sqliteTest` 的非零原生测试证据、零失败/跳过及真实宿主启动标记；报告保留输入摘要、解析摘要、原生 Gradle 日志和 JUnit XML。PostgreSQL 初始化与数据库测试先完成，再启动独立自动装配宿主，避免默认 schema 校验在空库上提前失败。

## typed Host 本地任务样例

原 `PublishedHost` 继续验证仅会话 Spring 组合，禁止意外创建执行 Host/Worker。`PublishedDatabaseTest` 另外装配 [PublishedExecutionHost](project/src/test/java/example/sdk/PublishedExecutionHost.java)：真实 Worker 平台线程 claim/finish 一个无外调本地任务，关闭 Worker 与 Scheduler 后，以新 Store/Host 验证原身份和终态。样例没有调用模型、工具、Plan 或子任务，不构成这些能力的验收。完整 Agent 宿主应在 `Host.drive` 使用 `ExecutionCoordinator.drive`；入口和生命周期见 [SDK 说明](../../backend-java/agent-runtime-bom/README.md#当前执行入口与生命周期)。

该流程仍使用现有显式隔离数据库配置与正式 JAR 校验，不增加测试替身、生产库连接或自动凭据发现。

`PublishedAgentScenario` 在同一隔离 PostgreSQL 中另建 Run，使用正式 JAR 的 `AgentExecutionProgram`、`ExecutionCoordinator`、JDBC Store、模型适配器和 tokenizer，经 typed Host 推进两次 MODEL 与一次只读 TOOL，校验原身份、Action/Attempt、预算和终态。在同一进程中重建 Store 和 Coordinator 后，确认已完成的 Run 不会重新派发；这不是进程重启或中断恢复验收。模型适配器通过实际 HTTP 访问测试进程内的 loopback OpenAI-compatible 端点；端点按脚本返回模型结果与固定用量，工具返回受控本地值。模型推理、工具数据和用量均是模拟，不能据此声称真实供应商、业务账本或生产环境验收。测试只在完成所有断言后输出专用标记，运行器要求该标记存在。上述正式 JAR 来源、默认生产类路径和 SQLite 独立类路径约束继续生效。

此样例直接编译引用 Core、JSON、Codec SPI 和模型适配器：变更依赖后须按照上文使用 `--write-locks` 生成并审查 STRICT 锁，再用全新目录运行普通验收；仅编译或生成锁不代表此 Agent 纵切通过。

## 发布 testFixtures 消费

`PublishedExecutionStoreContractTest` 继承正式 test-fixtures JAR 中的 `ExecutionStoreContract`，使用真实临时 SQLite 文件运行准入重建、材料批读保序和存活租约排他三个继承测试。该依赖只进入 `testCompileClasspath` / `testRuntimeClasspath`；生产 `runtimeClasspath` 与独立 `sqliteTest` 继续拒绝测试夹具。运行器要求这三个继承测试具有非零 JUnit XML 证据，并核对实际测试夹具 JAR 来源、版本和 SHA-256。

本批新增 testkit 和测试专用 SQLite 依赖后，必须先使用原运行器的 `--write-locks` 准备新锁，审查后将 `proposed-gradle.lockfile` 更新至受控模板，再以新的空 work/reports 目录正常验收；不能直接沿用旧 STRICT 锁，也不能手工补写锁条目。模板中的 `.gradle`、`build` 缓存不会复制到消费工程。
