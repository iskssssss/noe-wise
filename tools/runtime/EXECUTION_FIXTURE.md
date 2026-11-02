# S03 typed跨进程验收专属fixture

本入口只创建新容器和新数据库，完整迁移由独立migration owner执行，process测试使用 `skillforge` 受限机构角色。它补齐[集中验收入口](EXECUTION_ACCEPTANCE.md)的外部fixture创建缺口，不读取旧业务库或私有历史runner。

先由中央在当前候选导出正式测试类路径，路径按实际替换：

```powershell
$workspace = (Get-Location).Path
$gradleCache = Join-Path $workspace '.gradle-user-home'
$classpathFile = Join-Path $workspace '.runtime-logs/current-runtime-classpath.txt'
New-Item -ItemType Directory -Path (Split-Path $classpathFile) -Force | Out-Null
cd backend-java
./gradlew.bat --gradle-user-home "$gradleCache" --no-daemon --no-parallel --max-workers=2 -I ../tools/runtime/execution-fixture.gradle "-PexecutionFixtureClasspath=$classpathFile" :skillforge-institution-service:exportExecutionFixtureClasspath
cd ..
python -X utf8 -B tools/runtime/execution_fixture.py run --root .runtime-logs/process-fixture-unique --classpath-file "$classpathFile" --gradle-user-home "$gradleCache"
```

要求 `JAVA_HOME`、PATH的java与Gradle toolchain均为项目Java21；`--java`可指定bootstrap使用的java绝对路径，但不会改变Gradle或测试子进程的Java选择。导出任务仅依赖正式 `testClasses`，不执行业务测试。Java source launcher直接复用该classpath中的Flyway和`db/migration`，不复制SQL或手工模拟迁移。类路径必须来自当前候选的编译，代码/资源变化后重新导出，不能引用其他候选的旧build目录。实际完整链迁移仍须由中央运行证实。

`run`按顺序准备、运行runner的`process` lane、在Gradle退出后finally精确清理。`--root`必须是不存在的私有目录；目录内含`fixture.private.json`敏感凭据，不提交或上传。Docker密码通过进程env和`--env POSTGRES_PASSWORD`传递；Java密码通过进程env传递；bootstrap输出在落日志前替换生成的密码/token。源码或命令行不含明文密码。

需要分阶段核验时：

```powershell
python -X utf8 -B tools/runtime/execution_fixture.py prepare --root .runtime-logs/process-fixture-unique --classpath-file "$classpathFile"
python -X utf8 -B tools/runtime/execution_fixture.py cleanup --root .runtime-logs/process-fixture-unique
```

`prepare`有意保留fixture供中央核验；`run`要求新root，不能复用已prepare目录隐式重跑。prepare失败同样尝试清理，`fixture.json`保留失败类型及cleanup结果；清理失败标记 `CLEANUP_FAILED`，中央使用同一cleanup命令接续。Docker异常不是资源不存在的证据；NOT_FOUND须成功完成容器清单查询。不要在bootstrap或Gradle尚运行时对其fixture执行cleanup。

需要区分宿主存储成本时，可在取得对应目录授权后使用 `--postgres-data-root <数据根目录>`。工具仅新建带 owner 标记的随机子目录，并把其 `data` 子目录绑定为本次 PostgreSQL 数据目录；源码、证据与 Gradle 缓存位置不变，不迁移 Docker 引擎或既有数据库，也不修改 fsync/synchronous_commit。清理先核容器 owner，再核数据目录是所记录根目录的直接子目录且标记匹配，只删除这个子目录。数据目录应由本次操作者明确指定并独立拥有；不得使用业务数据库目录或共享数据目录。

## 实际装配与边界

- PG使用 `pgvector/pgvector:pg16`，Redis使用 `redis:7-alpine`；Docker在loopback原子分配端口，工具读取真实映射端口并分别等待pg_isready/Redis ping。
- 容器有任务前缀及本次随机owner label；state在创建前落盘。cleanup复核准确label后按不可变container ID删除本次容器及其匿名数据卷，不删除镜像、共享容器、命名卷或Docker引擎。
- 新库命名 `sf_s03_runtime_restart_<随机后缀>`。migration owner为`sf_fixture_owner`，业务角色`skillforge`显式NOSUPERUSER/NOBYPASSRLS/NOCREATEDB/NOCREATEROLE。正式迁移对固定角色`skillforge`的GRANT也落在该受限角色上；平台辅助角色独立且只参与正式授权校验，不传给业务测试。
- `ExecutionFixtureBootstrap.java`执行Flyway所有classpath正式迁移，要求latest resolved=current、零pending、validate成功；随后建准确lane/token guard，核验guard仍归migration owner，核验业务角色无super/bypass身份。
- 权限复用现行[prepare_database.py](../dev/prepare_database.py)与[runtime-grants.sql](../../deploy/pg-init/roles/runtime-grants.sql)，保留对象owner/角色继承/CREATE检查、机构角色仅SELECT/INSERT/UPDATE/DELETE、pricing只读例外及禁止TRUNCATE/REFERENCES/TRIGGER校验，不手抄宽GRANT ALL给业务角色。
- 测试父基类及每个真实子JVM会再次验证库名、guard token、迁移版本与业务角色。父测试创建模型/工具loopback HTTP夹具；强杀及不同PID跨JVM恢复由已有四个process测试证明。
- `acceptance/result.json`保留每个原生testcase的PASS/FAILED/SKIPPED/NOT_RUN和独立Gradle退出状态；只有全部selector通过且Gradle成功才为PASS。`fixture.json`的CLEANED只表示自有容器已清理，不表示验收通过；真实JVM reaped事实还须查看每个测试报告。

各候选的迁移、恢复、容量与清理结果分别保留在其原始报告和任务状态记录；入口或清理通过不替代对应候选的业务验收。
