# 又拍云工作区挂载验证

此目录是独立实验，不接入 Runtime 或生产沙箱。通过 FUSE 将又拍云 REST API 映射到容器 `/workspace`，只允许 `/workspace-poc/` 下的指定目录。

## 构建与运行

在仓库根目录执行；依赖本机已有 `skillforge-sandbox-python:3.12` 镜像：

```powershell
docker build --pull=false -t skillforge-workspace-poc:local deploy/sandbox/workspace-poc
docker build --pull=false -f deploy/sandbox/workspace-poc/Dockerfile.upyun -t skillforge-upyun-workspace-poc:local deploy/sandbox/workspace-poc
```

通过本机安全方式设置 `UPYUN_BUCKET`、`UPYUN_OPERATOR`、`UPYUN_PASSWORD` 环境变量，密码不要写入脚本或仓库。启动脚本将凭据通过标准输入传入容器，控制台只显示测试目录与连接状态：

```powershell
./deploy/sandbox/workspace-poc/Start-UpyunPoc.ps1
```

另开终端执行文件操作：

```powershell
docker exec --user 10001:10001 skillforge-upyun-poc bash -lc 'mkdir -p /workspace/demo; printf "hello upyun\nTODO workspace\n" > /workspace/demo/a.txt; grep -Rn TODO /workspace; find /workspace -type f'
docker stop skillforge-upyun-poc
```

容器退出自动删除，云端文件保留。再次启动时传入此前打印的 `-Prefix`，即可验证跨容器读取。删除测试文件也应只针对该目录。

## 实验边界

- 使用 HTTPS 存储 API，不使用 CDN 域名做读写入口；服务端 429 最多退避重试三次。
- 仅支持单线程小文件实验，读写以整文件处理，单文件限制 1 MiB；不支持目录重命名、文件锁、符号链接和完整 POSIX 语义。文件重命名为复制再删除，不原子。
- 挂载容器持有凭据和 `SYS_ADMIN`，仅运行受控验证命令；不能将此容器直接交给 Agent 执行不可信代码。生产需要将可信挂载进程与执行沙箱隔离。
- 此处的目录前缀限制是适配器边界，不代表云端操作员只拥有该目录权限。
- 2026-09-24 实测通过文件创建、读取、覆盖、追加、复制、移动、删除、目录创建/删除、`grep`、`find`，以及销毁容器后重新挂载读取；未验证大文件、并发写入、故障恢复和性能。
