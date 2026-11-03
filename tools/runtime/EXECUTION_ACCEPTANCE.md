# 当前 typed Runtime 集中验收

`run_execution_acceptance.py` 复用正式 `:skillforge-institution-service:runtimeLatencyE2E`，不使用临时 `.runtime-logs/Run-*`。默认仅输出当前源码中的声明方法和命令；加 `--execute` 才执行。不要并行运行这些 lane；Gradle test 输出目录及跨 JVM classpath 文件由同一模块拥有。

```powershell
python -X utf8 -B tools/runtime/run_execution_acceptance.py http --gradle-user-home .gradle-user-home
python -X utf8 -B tools/runtime/run_execution_acceptance.py http --gradle-user-home .gradle-user-home --execute --output .runtime-logs/runtime-http-unique-batch
python -X utf8 -B tools/runtime/run_execution_acceptance.py process --gradle-user-home .gradle-user-home --execute --output .runtime-logs/runtime-process-unique-batch
```

输出目录必须不存在。日志可能含测试数据库地址等私有材料，应保留在本次拥有的私有证据目录。runner 保存 Gradle 日志、原生 JUnit 和结果摘要。Gradle 成功后仍要求每个准确 selector 的 testcase 为 PASS；缺失、skipped 均失败。Gradle 状态单列，失败时仍保留本次 XML 中的实际用例结果。runner 不声称外部资源已回收。运行期间不要强杀外层 runner；若必须中断，由中央 owner 核收其 Gradle/JVM 树及外部 fixture。

## 当前实际矩阵

| lane | 当前声明方法数 | 范围 |
| --- | ---: | --- |
| http | 14 | 首链1、KEYWORD知识1、原生并行工具1、HUMAN/MCP/取消/UNKNOWN等待及拒绝7、Workflow与委派组合2、原Run预算及UNKNOWN预算2 |
| process | 4 | 保存结果/未知派发恢复、HUMAN等待、Browser等待、委派子HUMAN等待 |
| program | 1 | 真实sandbox RPC、独立typed工具child及共享预算 |
| capacity | 3 | ordinary、multi-step、long-history，各50并发/50预热/500测量；测试内的本地延迟参考值不代表真实模型或生产环境验收 |

具体方法名由 plan 打印。只选择当前类声明的方法，不隐式继承基类旧链用例；七个热样本的比较诊断不列入功能矩阵。上述数量是当前源码下的选择范围，不代表浏览器产品、媒体、独立 SDK 和其他宿主消费者的验收；这些范围有各自门禁。

## 依赖与资源归属

所有 lane 使用项目 Java 21、Wrapper 和既有依赖。HTTP链路通过真实 Spring HTTP/SSE、PostgreSQL、Redis及生产模型适配器；Provider端为确定性的 loopback HTTP夹具，不代表真实供应商质量验收。普通 `http` 未设置任何 `SKILLFORGE_S03_EXTERNAL_*` 时，由现有 `AbstractIntegrationTest` 创建 pgvector/pg16 与 Redis7 Testcontainers；其 Spring listener 先关闭Host，再释放所属容器，中央仍需核收退出事实。

`process` 强制要求以下**完整外部fixture环境变量**，防止JUnit条件跳过被误报通过：

```text
SKILLFORGE_RUNTIME_PROCESS_RESTART=1
SKILLFORGE_S03_EXTERNAL_JDBC_URL
SKILLFORGE_S03_EXTERNAL_DB_USER
SKILLFORGE_S03_EXTERNAL_DB_PASSWORD
SKILLFORGE_S03_EXTERNAL_REDIS_HOST
SKILLFORGE_S03_EXTERNAL_REDIS_PORT
SKILLFORGE_S03_EXTERNAL_FIXTURE_LANE
SKILLFORGE_S03_EXTERNAL_FIXTURE_TOKEN
SKILLFORGE_S03_EXTERNAL_SCHEMA_VERSION
```

中央owner必须创建专属新库 `sf_s03_<lane>_<suffix>`（lane匹配 `[a-z][a-z0-9_]{0,31}`），用migration owner执行**当前全部正式Flyway迁移**；业务角色不得 superuser/bypassrls，具备正式运行所需授权。库中 `public.s03_host_fixture_guard` 需有准确lane/token单行，`public.flyway_schema_history` 须有指定版本成功记录，供父fixture与每个子JVM再次核验。Redis同样是专属实例。不要用现有业务库、只建部分表或关闭原校验。`AbstractIntegrationTest.verifyExternalFixture` 与 `RuntimeExecutionRestartChild.requireIsolatedParentFixture` 是实际校验事实源。

**已有替代入口**：新增 [S03 专属fixture](EXECUTION_FIXTURE.md)复用正式Flyway及受限角色授权准备新库并运行process；旧工具本身仍不适用：已入库 `tools/full_project_testing/isolated_environment.py` 面向旧全项目bootJar环境，未创建上述S03受限角色/guard；旧 `process_recovery.py` 读取 `agent_runtime_runs` 等旧graph事实，不适用typed恢复。本runner接受中央owner准备的fixture；专属入口可完成准备、process运行和精确清理。未实际执行时 process 为未运行，不能由http通过替代。

process 测试关闭父Host worker，以两个不同OS子JVM运行正式应用，使用 `destroyForcibly` 并等待退出；恢复数据跨真实进程边界，原始typed Run/Action/Attempt/等待/预算及外调去重都有原断言。子进程边界故障注入使用测试Bean spy控制准确提交点，未以mock对象重建冒充重启。每个测试记录自有PID、ready/边界文件、前后持久事实和 `allOwnedChildrenReaped`；数据库/角色/Redis由中央owner在所有所属JVM退出后精确回收。runner只等待Gradle自然退出，不自行删除数据库或停止共享服务。

`program` 另需 `SKILLFORGE_RUNTIME_PROGRAM_E2E=1`、`CODE_EXECUTION_SMOKE_ENDPOINT`、至少32字符的 `CODE_EXECUTION_SMOKE_TOKEN` 以及中央拥有的真实Runner。`capacity` 不应与其他lane并行，也不由功能通过推导通过；禁止开启 `SKILLFORGE_PERF_SQL=1`、`SKILLFORGE_PERF_SQL_CALLERS=1` 或 `SKILLFORGE_PERF_SQL_ROLES=1`。

容量验收需在运行前固定适用的延迟、吞吐、并发与资源预算，并在报告中记录环境及测量方法。功能通过不证明性能达标；真实模型延迟、平台处理延迟与业务渠道体验分别测量，未执行的范围不得宣称通过。
