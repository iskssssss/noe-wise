# 独立代码执行沙箱

本目录装配 `skillforge-sandbox-service`、Python 3.12 / Node.js 24 任务镜像及受控出站代理。任务容器只加入 `internal` 且 `gateway_mode_ipv4=isolated` 的网络；代理同时加入该网络与独立 uplink，任务不能经 bridge 网关访问宿主机，也没有 IPv6 或其他直连公网路径。

## 启动

需要 Docker Engine 28 或兼容实现，支持 bridge `gateway_mode_ipv4=isolated` 和 volume `volume-subpath`。首次配置时将 `deploy/sandbox/.env.example` 复制为同目录的本地 .env 文件，将 `SANDBOX_SERVICE_TOKEN` 换成至少 32 个随机字符；已有配置不要覆盖。令牌只配置到机构服务和 sandbox service，不能写入代码请求、任务输入或日志。

先在 `backend-java/` 生成服务 JAR；Docker 构建只打包该产物，不在镜像构建中运行 Gradle。服务基础镜像默认固定到已验证的多架构摘要，可通过 `SANDBOX_SERVICE_BASE_IMAGE` 替换为管理员镜像仓库中的同等 JRE 21 镜像。

从仓库根目录执行：

```powershell
Push-Location backend-java
.\gradlew.bat :skillforge-sandbox-service:bootJar --no-parallel --no-daemon
if ($LASTEXITCODE -ne 0) { throw '沙箱服务构建失败' }
Pop-Location
docker compose --env-file deploy/sandbox/.env -f deploy/sandbox/compose.yml --profile runtime-images build python-runtime-image node-runtime-image
docker compose --env-file deploy/sandbox/.env -f deploy/sandbox/compose.yml build sandbox-ca-init sandbox-egress-proxy sandbox-service
docker compose --env-file deploy/sandbox/.env -f deploy/sandbox/compose.yml up -d sandbox-egress-proxy sandbox-service
```

`runtime-images` profile 仅用于 build，不要对该 profile 执行 `up`。服务启动时把运行镜像标签解析为本地 `sha256:` image ID，后续任务使用固定 ID。服务还会检查任务网络确为 `internal + isolated gateway + IPv6 disabled`、代理健康以及任务/CA 卷存在；任一条件不满足时拒绝启动。

运行镜像的固定 Debian Bookworm 基础层使用官方 `https://deb.debian.org`，APT 传输重试最多两次；配置同时覆盖 Node 的 Playwright 系统依赖安装。网络失败直接终止构建，保留依赖哈希、模型锁与 TLS 校验，不切换全局代理或替换镜像摘要。构建本身不更新运行服务，运行镜像变化须在获授权重启服务后才对新任务生效。

CA 初始化容器在 `skillforge-sandbox-ca` 卷根目录保存代理私钥，只把 `public/ca.crt` 子目录只读挂载给任务。执行服务独占 Docker socket，机构服务和任务容器不得挂载 socket。

## 内部接口

服务默认只监听宿主 `127.0.0.1:18200`，所有 `/internal/**` 请求必须携带 `Authorization: Bearer <token>`。

- `POST /internal/executions/artifact-inputs` 使用 `application/zip` 提交任务；首个条目必须为 `request.json`，内容为 camelCase 的 `{execution, artifacts}` 清单，后续条目流式携带文件正文。首次登记返回 `202`，相同 `taskId` 与相同摘要返回 `200` 和持久事实，不再次执行；不同摘要返回 `409 TASK_ID_REUSED`。
- `GET /internal/executions/detail?taskId=...` 查询状态。
- `DELETE /internal/executions/delete` 使用 JSON 请求体 `{"taskId":"..."}` 杀停容器及子进程；确认停止返回 `cancelled`，无法确认静止返回 `unknown`。已为 `unknown` 的任务不因取消变成已知结果。
- `GET /internal/executions/files?taskId=...` 仅对已知停止结果流式返回 ZIP；`unknown` 返回 `409 FILES_UNAVAILABLE`。
- `GET /internal/executions/logs?taskId=...&stream=stdout&offset=0&limit=32768` 按页读取已停止任务的捕获日志；日志正文不自动进入公开结果。

`execution` 必须显式提供 `profile`、`deadlineAt` 和冻结的 `executionPolicy`；STANDARD inline 请求使用非空或空字符串 `code`，`dependencies` 为数组，`inputs` 仅承载文件元数据。完整字段、产物清单和调用侧流式协议见[代码执行与产物运行指南](../../docs/04-指南与运维/09-代码执行与产物运行指南.md)。提交同步完成校验、持久登记和工作区准备后异步执行，首次响应可能仍是 `queued` 快照；用 detail 和真实容器运行事实观察执行，不能把 `202` 当作执行完成。

Skill 模式传递冻结的 `inputs` 与 `scriptPath`。每个输入清单项只包含安全相对路径、字节数及 SHA-256，必须与 `artifacts` 中的传输清单匹配；文件正文通过后续 ZIP 条目传输，不在 JSON 中内嵌 UTF8 或 BASE64 内容。入口必须位于 `scripts/` 并与语言匹配。STANDARD 输入只读挂载到 `/input`，当前目录为 `/workspace`，输出写入 `/workspace/output`。输入及输出声明受统一文件清单上限 2048 项约束，实际采集上限另由冻结的 `executionPolicy.maxArtifactFiles` 控制。

STANDARD 依赖安装与代码执行是两个一次性容器，按任务独享 `/workspace/work`，共享冻结的 `executionPolicy.executionTimeoutSeconds` 执行期限，同时受请求总 `deadlineAt` 限制。安装容器不挂输入或输出，Python 只安装 wheel，Node 禁用 install scripts；解析版本与完整性报告写入 evidence 子卷。服务在安装容器停止后流式固化报告摘要，执行容器不挂 evidence。执行容器不接收仓库配置。

隔离生命周期验收入口为 [verify_lifecycle.py](../../tools/sandbox/verify_lifecycle.py)。通过进程环境传入 `SANDBOX_VERIFY_ENDPOINT`、`SANDBOX_VERIFY_TOKEN`、`SANDBOX_VERIFY_CONTAINER`、`SANDBOX_VERIFY_PREFIX`、`SANDBOX_VERIFY_OWNER`，执行 `python -X utf8 -B tools/sandbox/verify_lifecycle.py --output <独立证据目录>`。脚本只接受匹配本批 Compose 项目、精确服务名及任务卷的实例；验收真实在途取消、强杀后恢复、UNKNOWN 拒绝下载、持久幂等与新任务执行。门控标记、执行次数和真实容器状态构成观察证据，重启后重新读取 loopback 端口并写入 `result.json`；脚本不打印或保存令牌。

## 出网和运维

代理只接受 HTTP 与 HTTPS，关闭 raw TCP、TCP/UDP host passthrough 和 WebSocket。CONNECT、每个 HTTP 请求以及最终 server connect 都重新检查 DNS 与 IPv4/IPv6 地址，并把批准地址固定到连接；loopback、私网、链路本地、保留地址、宿主和元数据地址均拒绝。重定向后的新请求会再次检查。

任务事实与工作目录保存在 `SANDBOX_TASK_VOLUME` 指定的任务卷，默认 `skillforge-sandbox-tasks`。每个任务卷仅由一个运行中的服务实例使用；并行部署须分别配置任务卷及其数据根挂载。容器的 `skillforge.sandbox.owner` 标签由任务卷名的 SHA-256 派生，重启保持稳定，名称也包含该 owner。服务重启只枚举同时匹配 `skillforge.sandbox.managed=true` 和当前 owner 的遗留容器；清理及取消均重新核验 owner，取得真实容器 ID 后按 ID 删除，不能因名称被替换而操作其他容器。其他 owner、无 owner 或归属无法确认的容器不被删除；无法确认本次清理或取消时按失败或 `unknown` 收敛。服务把未完成记录置为 `unknown`，不会重新运行代码。普通文件数量受当前请求清单上限 2048 项与冻结的 `executionPolicy.maxArtifactFiles` 共同限制，拒绝链接、特殊文件和路径逃逸；不设置单文件或总字节上限，磁盘容量和调用 deadline 仍是物理边界。

备份时保存任务卷及调用方已接收的状态事实。关闭功能先停止新请求，再优雅停止 sandbox service；关闭不会自动删除任务卷。磁盘耗尽、代理不健康、运行镜像缺失、隔离网络失效或容器无法确认清理都按失败关闭处理。

## 会话项目卷与云端快照

`SANDBOX_PROJECT_VOLUME` 默认 `skillforge-sandbox-projects`，在可信服务中挂载到 `/var/lib/skillforge-projects`，任务只挂载当前授权的项目子目录到 `/workspace/project`。项目卷独立于临时任务卷；容器回收不会删除项目文件。首版单服务实例独占任务卷和项目卷，不能以同一项目卷并行启动多个写入服务。

Node 任务镜像同时提供 Bash、coreutils、grep、findutils、ripgrep 和 Git。`shell-execute` 入口接受只读脚本路径和项目内工作目录，直接启动 Bash；不进行依赖安装，不承接上一调用的进程或 Shell 变量。更新该入口后需重新构建 Node 运行镜像，服务启动时解析其固定镜像 ID。

后台快照使用又拍云 HTTPS 存储 API，文件操作直接读写本地项目卷。通过环境或秘密配置一起提供 `SANDBOX_WORKSPACE_CLOUD_BUCKET`、`SANDBOX_WORKSPACE_CLOUD_OPERATOR`、`SANDBOX_WORKSPACE_CLOUD_PASSWORD`；三个值可同时为空，此时云端未配置。密码不得写入仓库或镜像。默认接入点为 `https://v0.api.upyun.com`，禁止携带凭据、query 或 fragment，也不会跟随重定向。云端凭据只由可信服务持有，不进入任务容器或工具参数。

使用临时受控进程环境注入秘密时，普通 Docker restart 保留容器已登记的环境，Compose 重建则需要在创建进程中重新注入完整的三个值。不要把已有容器可备份等同于下次重建自动继承秘密；重建后重新核验云端配置与快照状态，缺失时不宣称云端备份完成。现有项目卷、操作状态和删除墓碑仍须保留，不通过删除卷解决配置缺失。

所有快照上传、恢复和删除共用 `SANDBOX_WORKSPACE_CLOUD_REQUESTS_PER_SECOND`，默认每秒一个请求；这是客户端策略，不代表又拍云额度。每次请求最长两分钟，429 在同轮最多尝试五次，优先遵守 `Retry-After`，否则进行带随机扰动的指数退避。服务要求等待超过 60 秒时结束本轮，交由持久备份队列后续重试，避免提前重试。非 429 错误立即反馈给队列，不自动重跑用户命令。

快照上传使用唯一版本对象键，流式发送文件并在成功后通过 HEAD 校验大小和 MD5；未校验成功不推进云端备份版本。下载先写入独立临时文件，传输成功后才原子替换目标，工作区恢复层进一步验证归档清单和 SHA-256。清理按持久登记的精确对象键删除，不枚举其他用户目录。项目卷、任务卷和云端快照应按工作区生命周期联合保留；本地保存与云端备份状态分别展示。
