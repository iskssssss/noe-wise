# 桌面原型演示与组合检查

桌面客户端的安装、模型配置与启动入口见 [desktop-client](../../desktop-client/README.md)。

## 创建可修复的示例

在仓库根目录运行，目标目录必须不存在，且父目录已存在：

~~~powershell
node tools/desktop-prototype/create-demo.mjs C:\workspace\NoeWise-Demo
~~~

在桌面窗口中选择生成的目录，输入“修复 sum 的实现，运行 node --test 验证，并说明修改”。该示例没有第三方依赖，使用电脑已安装的 Node.js。Agent 需要先读取文件，再修改实现；运行测试前会出现命令确认。真实模型请求由用户配置的供应商计费。

## 真实 Host stdio 组合检查

先构建 Java 宿主的 installDist，再在仓库根目录运行：

~~~powershell
node tools/desktop-prototype/stdio-smoke.mjs
~~~

脚本在临时目录启动真实 Java 进程、SQLite、本机文件和命令工具，并通过正式 Provider 连接本地受控 HTTP 服务。它验证读取、修改、精确命令确认、最终回复，以及重启查询历史不重发模型请求。服务使用操作系统分配端口和固定测试凭据，不读取项目秘密，不调用付费云端模型。

可用 --java 和 --host-lib 指向打包后的运行时及宿主 lib 目录，验证随包依赖能否完成同一链路。该检查不替代真实供应商或 macOS 真机验收。
