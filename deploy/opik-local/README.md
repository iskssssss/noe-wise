# 本机 Opik 2.2.82 PoC

本目录只提供行为评测脱敏结果的本机展示平台，不改变 灵知 Runtime 的执行、计量账本、冻结报告或发布资格判定。受控 Python 执行器、原始 trace、usage 账本和 `release-check` 仍是权威来源；Opik 只保存脱敏后的派生视图，Java 模块不引入 Opik 依赖。

## 固定范围

管理入口固定使用 Opik `2.2.82` 上游源码 commit `d5459acdc83db1bf634324634a121f028d2f9a10`，Compose project 为 `skillforge-opik-poc`。只向宿主发布 `127.0.0.1:15173`，不与 灵知 的 Compose、数据库、卷或网络共享；`down` 会停止容器但保留数据卷。

PoC 关闭后端 usage/OTel 上报、远程模型注册、Python evaluator、AI/演示数据生成和代码执行。前端 Statsig 或其他前端 telemetry 的状态不由本 PoC 文档推断。导入前必须使用 `export-opik` 生成的新独立目录，并确认内容已脱敏。

## 管理命令

从仓库根目录运行：

```powershell
python -X utf8 -B deploy/opik-local/manage.py prepare
python -X utf8 -B deploy/opik-local/manage.py up
python -X utf8 -B deploy/opik-local/manage.py status
python -X utf8 -B deploy/opik-local/manage.py down
```

`prepare` 固定并校验上游源码和 Compose 配置；`up` 只启动 PoC 所需服务并等待健康状态；`status` 读取该独立 project；`down` 保留卷，删除或清理卷必须另行按数据清理授权执行。管理脚本拒绝意外端口、特权服务和上游源码漂移。

## 导出与导入

导出必须以已冻结批次目录为输入，并将结果写入新的独立目录：

```powershell
python -X utf8 -B tools/chat_regression/behavior_main.py export-opik --root tools/chat_regression/runs/<冻结批次> --out .runtime-logs/opik-exports/<新目录>
python -X utf8 -B tools/chat_regression/behavior_main.py import-opik --root .runtime-logs/opik-exports/<新目录> --opik-url http://127.0.0.1:15173/api
```

导出不重跑 Runtime、不覆盖原报告，导出目录只包含 `bundle.json`、`trials/` 和 `public/`；UUIDv7 映射和收据写入 `.runtime-logs/opik-import/<目标地址摘要>/receipts.json`，不写入导出目录。批量便利接口不保证去重，不能重复生成同一批次的标识。未知用量只进入报告，不使 export/import 非零；只有导入错误或未完整读回才非零，平台故障不能触发 Runtime 重跑。

原始批次使用 `--root` 指定的已冻结本地目录，运行目录不随仓库检出；导出默认位于 `.runtime-logs/opik-exports/`，导入收据位于 `.runtime-logs/opik-import/<目标地址摘要>/receipts.json`。这些目录均只用于受控证据和脱敏导入，不能把服务健康或导入结果当作行为评测或 Release Gate 结论。
