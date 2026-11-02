# 运维备份、恢复与隔离演练

`backup_restore.py` 使用真实 PostgreSQL 工具执行备份和恢复。每个命令都要求显式来源或目标，不读取项目 `.env`，不自动启动应用，不执行数据库迁移、删除源库或覆盖已有目标。

需要 Python 3.11+ 和与源数据库主版本匹配的 PostgreSQL 客户端工具。认证由单独的 `--passfile` 提供；该文件按 PostgreSQL `pgpass` 格式维护，并限制为操作者可读。子进程不继承业务环境中的数据库密码；错误输出只提供命令名称和退出码。

## 备份包内容

- 逻辑备份使用 `pg_dump --format=custom`，验证 `pg_restore --list` 并保存数据库摘要。
- 物理备份使用 `pg_basebackup --wal-method=stream`，由 `pg_verifybackup` 验证整个 PostgreSQL 集群。`source-database` 是操作者核对来源的标识；物理备份覆盖整个集群。
- 文件材料只来自明确传入的 `--snapshot-root`。每个根应是已经冻结的快照，工具记录相对路径、大小和 SHA256，拒绝链接及复制过程中变化的文件。数据库和文件的联合一致性仍需调用方安排冻结时间，不能把活动工作目录的逐文件复制当成一致快照。
- `--config` 仅登记部署配置的名称、大小和摘要，不复制应用配置正文。恢复后需独立提供目标环境配置。
- 物理基线内含 PostgreSQL 自身的数据和配置，备份包应使用组织规定的访问控制与加密存储。长期 PITR 还需要基线之后完整、连续的 WAL 归档；一次基线成功不证明未来归档连续性。

只有最后写入的 `manifest.json` 存在且摘要验证通过，目录才是完整备份包。失败目录需由操作者核实后处理，工具不会自动删除。

## 命令示例

下面使用独立备份环境占位值，执行时替换为已经核对的来源、目标和工具目录。命令内不填写密码。

```powershell
python tools/operations/backup_restore.py logical-backup `
  --source-host backup-source.example.internal --source-port 5432 `
  --source-user backup_reader --source-database skillforge `
  --source-confirmation skillforge --output C:/recovery/bundles/logical-20261011 `
  --passfile C:/recovery/auth/pgpass --sslmode verify-full `
  --bin-dir 'C:/Program Files/PostgreSQL/16/bin' `
  --snapshot-root C:/recovery/frozen/session-snapshots `
  --config C:/recovery/config/deployment.conf
```

逻辑恢复只创建全新且名称以 `skillforge_restore_` 开头的数据库。目标名称必须与 `--target-confirmation` 精确相同；已存在的库、与源同名的库、已存在的文件根或与备份包重叠的目录都会拒绝。数据库恢复使用单事务，不含 `--clean`、`DROP`、来源 owner 或 ACL。原身份及权限需要在独立恢复环境按当前部署契约重新配置。

```powershell
python tools/operations/backup_restore.py logical-restore `
  --bundle C:/recovery/bundles/logical-20261011 `
  --target-host restore-only.example.internal --target-port 55432 --target-user restore_operator `
  --target-database skillforge_restore_20261011 --target-confirmation skillforge_restore_20261011 `
  --target-files C:/recovery/isolated/skillforge_restore_files_20261011 `
  --passfile C:/recovery/auth/restore-pgpass --sslmode verify-full `
  --bin-dir 'C:/Program Files/PostgreSQL/16/bin'
```

物理备份及归档追加分别执行，归档系统仍负责持续产出完整 WAL。追加命令拒绝同名但不同摘要的归档，不覆盖已经登记的证据。

```powershell
python tools/operations/backup_restore.py physical-backup `
  --source-host backup-source.example.internal --source-port 5432 `
  --source-user replication_backup --source-database skillforge `
  --source-confirmation skillforge --output C:/recovery/bundles/physical-20261011 `
  --wal-archive C:/recovery/archive --passfile C:/recovery/auth/pgpass --sslmode verify-full `
  --bin-dir 'C:/Program Files/PostgreSQL/16/bin'

python tools/operations/backup_restore.py wal-append `
  --bundle C:/recovery/bundles/physical-20261011 --wal-archive C:/recovery/archive

python tools/operations/backup_restore.py physical-stage `
  --bundle C:/recovery/bundles/physical-20261011 `
  --target-data C:/recovery/isolated/skillforge_restore_pitr_20261011 `
  --target-confirmation C:/recovery/isolated/skillforge_restore_pitr_20261011 `
  --target-port 55433 --recovery-target-time '2026-10-11T10:30:00+08:00' `
  --bin-dir 'C:/Program Files/PostgreSQL/16/bin'
```

`physical-stage` 只准备离线目录，目标必须全新且末级目录以 `skillforge_restore_` 开头。它验证物理基线、清除主库连接、覆盖生产 include 配置，使用包内 WAL 恢复到明确时间并暂停。默认仅监听 loopback 且拒绝全部连接。操作者须在隔离环境配置恢复认证并手动启动目标 PostgreSQL，确认目标时间、事务事实、文件摘要和权限，再决定后续处理。脚本不切流、不启动业务应用。

`wal-fetch` 是 PostgreSQL `restore_command` 回调，只读取严格命名的包内 WAL；缺片时失败，不从业务主库拉取。不要修改已登记 WAL 或对缺片恢复猜测结果。

## 真实隔离演练

下面两项演练都创建全新的容器内临时集群，容器不发布端口，运行时网络为 `none`，仓库仅以只读方式挂载。结束时只停止演练创建的 PostgreSQL 进程。

```powershell
docker build -f tools/operations/Dockerfile.drill -t skillforge-recovery-drill:foundation tools/operations

docker run --rm --network none --name skillforge-foundation-recovery-drill `
  --mount 'type=bind,source=C:/workspace/noe-wise,target=/repo,readonly' `
  skillforge-recovery-drill:foundation

docker run --rm --network none --name skillforge-foundation-operations-sql-drill `
  --entrypoint python3 `
  --mount 'type=bind,source=C:/workspace/noe-wise,target=/repo,readonly' `
  skillforge-recovery-drill:foundation /repo/tools/operations/operations_sql_drill.py
```

`recovery_drill.py` 验证逻辑恢复、文件摘要、应用配置正文排除、既有目标拒绝、连续 WAL 时间点恢复、目标之后的事务排除及恢复暂停。`operations_sql_drill.py` 在最小已有审计表基线上真正执行仓库 V279，用非 `BYPASSRLS` 账号验证租户隔离、不可改写事实、行锁、引用保护、30 天硬底线、解除墓碑、删除回滚和不可变终态回执；候选选择直接读取生产 Mapper SQL，验证永久保护不会堵住后续可清理事实。

这两项演练证明工具与 V279 新约束在独立 PostgreSQL 16 环境中的行为。完整迁移链、应用组合授权、真实部署的备份凭据、归档连续性、外部存储恢复、RPO/RTO 和恢复后的业务验收仍属于对应环境验收，不能从隔离演练推导其已完成。

## 监控规则验证

```powershell
docker run --rm --network none --entrypoint /bin/promtool `
  --mount 'type=bind,source=C:/workspace/noe-wise/deploy,target=/config,readonly' `
  prom/prometheus:v3.5.0 check rules /config/alerts.yml

docker run --rm --network none --entrypoint /bin/promtool `
  --mount 'type=bind,source=C:/workspace/noe-wise/deploy,target=/config,readonly' `
  prom/prometheus:v3.5.0 test rules /config/monitoring-tests.yml
```

规则使用实际 Micrometer HTTP、Hikari 和 JVM 指标；HTTP p95 需要宿主启用 histogram。`promtool` 验证规则和输入场景，不能证明部署环境已经抓取到 `/actuator/prometheus` 或告警接收器已经发送通知。
