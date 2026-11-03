# Agent Runtime SDK 1.0.0

`com.skillforge:agent-runtime-bom:1.0.0` 固定首版 Agent Runtime Java SDK 的兼容集合。模块边界与 Runtime 不变量分别见[微内核插件架构](../../harness-design/02-微内核插件架构实现思路.md#14-agent-runtime-sdk-交付边界)和[核心对象模型](../../harness-design/03-核心对象模型与状态机.md)；本文件只说明构件消费、支持面和升级规则。

## 发布与解析

从 `backend-java` 执行发布 Gate，`sdkReleaseRepository` 指向隔离 Maven 仓库：

~~~powershell
.\gradlew.bat sdkReleaseGate -PagentRuntimeVersion=1.0.0 -PsdkReleaseRepository=../build/sdk-repository --rerun-tasks --no-daemon --no-parallel --max-workers=1 --console=plain
~~~

消费工程只配置该发布仓库和 Maven Central，不配置 灵知 project dependency、classes 目录或 Maven Local：

~~~groovy
repositories {
    maven { url = uri('../build/sdk-repository') }
    mavenCentral()
}

dependencies {
    implementation platform('com.skillforge:agent-runtime-bom:1.0.0')
    implementation 'com.skillforge:agent-runtime-spring-boot-starter'
}
~~~

BOM 还约束 API、Codec SPI、Session SPI、Core、Session、Memory、JDBC、SQLite、Model 与 MCP。使用手工装配时按实际需要声明这些模块，不手写版本。

`agent-runtime-adapter-testkit` 由 BOM 固定版本，内存参考实现位于该模块的 `src/test`，共享契约套件位于 `src/testFixtures`。Gradle 消费方使用 `testImplementation(testFixtures("com.skillforge:agent-runtime-adapter-testkit"))`；Maven 消费方使用 `test-fixtures` classifier 和 test scope，并显式声明 API、Session SPI 与 JUnit 测试依赖。测试支持不进入 production runtime classpath，主 JAR 不包含参考实现或契约测试类。

发布任务为 main、sources、javadoc JAR 关闭文件时间戳并固定条目顺序，POM、Gradle metadata 与所有构件由 `verifySdkReleaseRepository` 计算 SHA-256。当前最小正式发布消费入口见[SDK 独立消费验收](../../tools/sdk-published-consumer/README.md)，在临时独立工程验证发布来源/版本、Starter 启动、独立 Runtime schema 安装、PostgreSQL 与 SQLite 消费，并以严格依赖锁固定解析结果。

## 版本与兼容策略

所有 `com.skillforge` Runtime 构件必须使用同一个 BOM 版本，不支持混合版本、动态版本或 SNAPSHOT。

- patch 版本只交付不破坏已支持契约的缺陷和安全修复。

- minor 版本可以增加兼容 API、Port 或扩展点；现有消费者不需要修改源码。

- major 版本才允许破坏正式 API、Core/manual wiring 或 Starter 扩展点的签名和语义。

升级任何 Runtime 构件时整体升级 BOM，并执行消费者、ABI、依赖、迁移、持久化与恢复检查。schema、checkpoint、journal、receipt、permit、Action 结果和 Outbox ACK 只允许同版本重启或向前迁移；数据库快照不能回滚已经发生的外部副作用账本。

## 支持范围

| 构件面 | 支持承诺 |
| --- | --- |
| `agent-runtime-api` | 值对象、sealed 结果与 Ports 是正式公共契约。当前执行入口为下节的 typed Host / `ExecutionCoordinator` 组合；不能将单独的 API façade 类型解释为已经装配的完整业务执行入口。Port 实现不能绕过身份、Policy、permit、账本与 Outbox。 |
| `agent-runtime-core` | 支持公开 ABI baseline 中供 Session、Starter、经独立验收的 Delegation 或完整 manual wiring 使用的组件。manual wiring 必须采用同一 BOM、实现所有必选端口并通过 JAR consumer、契约、持久化和恢复测试；任意 `public` 类型不自动成为业务调用入口。 |
| `agent-runtime-session-spi` 与 `agent-runtime-codec-spi` | 是发布集合内的 lockstep 协作面。第三方实现必须针对目标 BOM 的精确版本重新编译并重跑 contract、迁移与恢复测试；不承诺跨 BOM 直接混装或让 SPI 独立升级。 |
| 生产 Adapters | JDBC、SQLite、Model 与 MCP 是 1.0.0 的受支持生产实现；具体 Provider 必须满足各 Port 的冻结目标、协议、幂等和可信结果要求。 |
| Spring Boot Starter | 支持下节列出的必选宿主 bean、公开配置/信任/生命周期入口和明确的替换点。覆盖其余内部 JDBC、Session、worker 或 projector bean 不属于支持扩展面。 |

Core 与 SPI 的 committed ABI/依赖 Gate 是精确 Java 事实源；本说明不把模块内部实现细节扩张为兼容承诺。

批次路由结果按原请求槽位保留实际来源：`BatchRouted.items` 包含新调用的 `Fresh` 候选或原完整 `Reuse`/`Converge` 所属的 `Existing` 项。历史项不新建 Action、派发或能力计数；Core 结果只表示全批纯预检通过，不表示 Session 已登记或授予调用权。精确 Java 签名见[Core ABI 基线](../agent-runtime-core/api-baseline/public-api.javap)，批次治理见[上下文执行专题](../../harness-design/03-上下文视图与执行优化.md#16-只读多工具批次)。

## Starter 宿主契约

Starter 提供通用基础组件，不自动提供具体宿主的身份存储、业务能力绑定或完整执行 Host。基础组合要求宿主提供 `DataSource` 以及以下 bean；精确签名见 [AgentRuntimeAutoConfiguration](../agent-runtime-spring-boot-starter/src/main/java/com/skillforge/runtime/starter/AgentRuntimeAutoConfiguration.java)：

- `JdbcIdentityTransactions`：宿主保存原验证完整事实，并在 Runtime 借用的租户 `SqlSession` 中核验当前权限；不得另开连接、切换租户或提交。独立宿主实现自己的身份边界，无需依赖平台身份实现。
- `AgentRuntimeDecrypt` 与 `MediaPort`：宿主拥有秘密解析及媒体授权；未启用的能力应显式拒绝。

记忆装配由 `agent-runtime.memory.enabled` 控制，默认 `true`：机构与系统宿主均消费记忆能力，默认继续要求完整治理。只消费会话等基础能力的独立宿主应显式设为 `false`；关闭后 Starter 不创建 `JdbcMemoryPort` / `MemoryRuntime`，也不要求以下记忆专用 bean。启用时缺少任一必选端口即启动失败，不补默认允许或 no-op 实现。该开关只控制 Starter 记忆装配，不禁用宿主自行声明的业务记忆服务。

- `MemoryTransitionGuard` 与 `AgentRuntimeMemorySearchBindings`：宿主提供记忆附加迁移约束、类型正文投影、完整检索策略和实际 embedding 边界。
- `MemoryAccessPolicy`、`MemoryTypeRegistry`、`MemorySourcePolicy` 与 `MemoryRuntime.CurrentFactSource`：宿主明确记忆读写权限、类型和来源事实。

宿主显式调用 `RuntimeJdbcSchema.install` / `validate` 管理 Runtime 自有 PostgreSQL schema；SDK 不携带平台 Flyway 迁移或平台角色。安装与连接角色、受限权限及租户上下文均由宿主拥有。独立消费示例见 [ConsumerHostIdentity](../../tools/sdk-published-consumer/project/src/main/java/example/sdk/ConsumerHostIdentity.java) 和 [PublishedHost](../../tools/sdk-published-consumer/project/src/test/java/example/sdk/PublishedHost.java)。

当前命名统一维护的是目标初始化链，仅用于新建或另行授权重建的环境，不能将改写过的 V1 当作既有库的升级迁移。变更 schema 后运行 `:agent-runtime-adapter-jdbc:exportRuntimeSchemaManifest`：任务仅创建并销毁自身的临时 PostgreSQL，按正式 Mapper 导出到该模块 `build/schema/runtime-schema-v1.tsv`；审查后更新受控清单，再运行 schema 生命周期和存储测试。不得通过手改编码定义绕过结构漂移检查。

只有注册了 `ExecutionHostRegistry.Host` 时，Starter 才创建 `ExecutionHostRegistry` 和 `ExecutionHostWorker`；执行宿主还需提供 `ExecutionTenantDiscovery` 与 `AgentRuntimeTenantBinder`。Worker 自动启动字段是 `agent-runtime.workers.auto-startup`，默认 `true`；若来源、身份或恢复屏障尚未准备好，宿主必须在启动前显式设为 `false`，准备完成后按宿主生命周期控制启动。通用限额、保留期和租约配置见 [AgentRuntimeProperties](../agent-runtime-spring-boot-starter/src/main/java/com/skillforge/runtime/starter/AgentRuntimeProperties.java)，不从平台配置或旧扁平别名推断。

SDK 的 Spring 配置只绑定 `agent-runtime.*`；平台宿主另有 `skillforge.agent-runtime.*`，承载其身份与回调配置，不属于 SDK。配置在启动时生效；标准环境变量遵循 Spring Boot 的点转下划线、连字符删除和大写规则，例如 `agent-runtime.model-maximum-response-bytes` 对应 `AGENTRUNTIME_MODELMAXIMUMRESPONSEBYTES`。

当前自动装配的 `ConditionalOnMissingBean` 替换点为 `Clock`、`HttpClient`、`PayloadValidator`、`SessionStorePort`、`ExecutionBudgetPort`、`ModelProviderCatalog` 与 `ModelProviderRuntime`；宿主也可以通过 `AgentRuntimePayloadSchema` 扩展精确 payload schema，重复注册会拒绝启动。缺少必选 bean 或有效配置时失败关闭；仅让 Spring 容器成功启动不构成完整业务或生产环境验收。

## 当前执行入口与生命周期

会话存储组合只装配 `SessionStorePort` / `SessionLogPort`，不注册 `ExecutionHostRegistry.Host`，因此不创建执行 Worker。它的启动、会话 CAS 与重启读取验收保留在 [PublishedHost](../../tools/sdk-published-consumer/project/src/test/java/example/sdk/PublishedHost.java)。

Starter 将 `agent-runtime-session` 作为 API 依赖导出，因为公开 `Host.drive` 和 `Worker.driveProgramChild` 返回 `ExecutionCoordinator.Outcome`；仅声明 Starter 的消费工程即可编译这些公开宿主签名，不需要自行补齐隐藏的实现依赖。

执行组合由宿主显式注册 `ExecutionHostRegistry.Host`，按已持久 Run 的来源选择唯一 Host；`ExecutionHostWorker` 拥有有界平台线程槽位。完整 Agent 宿主在 `Host.drive` 调用 `ExecutionCoordinator.drive`，以 typed `ExecutionStore` 提交 claim、dispatch、result 和 finish。程序只计算下一步与上下文候选，实际能力适配器只消费已登记的派发许可。读取持久状态不能产生新的发送权限，重建宿主也不能重新执行原外部请求。

[PublishedExecutionHost](../../tools/sdk-published-consumer/project/src/test/java/example/sdk/PublishedExecutionHost.java) 是独立发布 JAR 的最小本地任务示例：真实注册 Host、由 Worker 平台线程 claim 并 finish，关闭后用新 Host 和 Store 读取同一终态。它没有模型、工具、Plan 或子任务能力，不代表完整 Agent 执行验收。[PublishedDatabaseTest](../../tools/sdk-published-consumer/project/src/test/java/example/sdk/PublishedDatabaseTest.java) 在仅会话组合之外调用该示例，使用同一受限 PostgreSQL 连接与宿主身份边界。

Spring 拥有它创建的 Worker、控制 Scheduler 和 `ModelProviderRuntime`。后者实现 `AutoCloseable` 时，现有 `@Bean` 的默认销毁推断调用 `close`，宿主不应再次手动关闭同一 Bean。手工装配者按 Worker、Scheduler、Provider、连接池的顺序关闭自己拥有的资源；借用宿主资源的组件不取得额外关闭权。示例只拥有 Worker 和 Scheduler，连接池仍由调用方拥有。

当前 `ExecutionStore` 共享契约位于 testkit 的 `ExecutionStoreContract`，覆盖准入幂等与重建读取、未过期 claim 排他；JDBC 与 SQLite 的生产存储测试均调用它。旧 `ExecutionLedgerPortContract` 只证明对应旧端口，不能替代当前 typed store 的证据。

## 委派与 Session 派生边界

当前 Core 可根据已装配的目标视图路由委派动作，Session 执行程序也包含委派请求与等待结果的处理；缺少委派装配时不会据此推断目标或默认允许。这些运行时组件仍需要宿主提供实际委派能力，不能仅凭类型或路由结果认为子任务已执行。BOM 未发布独立的 `agent-runtime-delegation` 构件，当前公开构件也不提供 `forkSession`；SDK 消费方需按实际宿主装配和验收范围判断能力，不能把接口类型当成完整委派或 Session 派生支持。
