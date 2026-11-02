---
name: mcp-builder
description: 为明确的接口或工具需求开发 Python MCP stdio 服务，实际编译并验证初始化、工具目录、输入契约和调用结果后交付源码；适用于创建 MCP 服务，不用于直接操作已有 SaaS 或替换平台 MCP Runtime。
license: Apache-2.0
compatibility: code_execute Python 3.12; mcp 1.26.0
metadata:
  version: 1.0.5
  runtime: python-mcp-sdk
allowed-tools: code_execute finalize_task_outputs
---

# MCP 服务开发

先确定服务用途、工具名称、输入结构、授权边界、分页与错误语义。只使用用户已提供或实际查询到的接口资料和字段，不虚构服务能力、凭据或真实服务验证。开发方法参考 `references/upstream-mcp-builder.md`、`references/mcp-best-practices.md`；Python SDK 写法参考 `references/python-mcp-server.md`。这些资料已裁剪为平台适用的方法；执行仍只走本包受管入口，不增加安装、外部服务或模型评测流程。

当前可执行路径为 Python 3.12 + 固定 MCP SDK 1.26.0 的 stdio 服务。执行前用 `read_skill_resource` 读取 [输入契约](references/input-contract.md) 和 [manifest](manifest.json)。普通数值数组求和直接采用已支持的 `operation: "sum"` 模板，完整输入参考 [单工具求和示例](examples/mcp-sum.json)；echo、静态数据 lookup 也使用已有模板。没有模板不支持的功能时不填写 `source_files`，不自写 FastMCP 服务。专业需求才提供 `source_files`（`server.py` 及需要的 Python 模块），并仍走同一受管验证入口。HTTP 部署、真实第三方 API、凭据绑定及生产连接器接入需要各自验收，生成源码和本地协议通过不代表已经接通 SaaS。

先生成并核验 mcp-server/server.py、必要辅助模块、mcp-server/requirements.txt 和 mcp-server/README.md。用户明确要求 ZIP 时才打包；修改已有成果时使用真实 base_revision_id。

调用 `code_execute` 必须设置 `mode: "skill"`、`language: "python"`、本 Skill 激活回执中的真实 `skill_handle` 和 `script_path: "scripts/build_mcp_server.py"`，不传 `code`。`input_files["mcp-server.json"]` 为完整需求 JSON 字符串，平台将其挂载为 `/input/data/mcp-server.json`；已有输入只能通过 `artifact_inputs` 的真实 `artifact_id`、`path` 显式挂载单个 `/input/artifacts/<artifact_id>/mcp-server.json`。`dependencies` 原样复制 [manifest](manifest.json) 中完整的 29 项 `{name, version}` 精确坐标，不能只传 mcp，也不能假设依赖或文件继承。通过固定 `/workspace/output/result.json` 自动回读类型化结果，模板默认 `output_files: ["mcp-server/server.py", "mcp-server/requirements.txt", "mcp-server/README.md"]`，只有明确要求 ZIP 才追加 `mcp-server.zip`；自定义模块同时显式登记相应源码路径。

禁止使用 `mode: "inline"` 复制生成器、自写独立检测程序或直接写“检查通过”的交付文件来替代本包受管入口。每次执行工作区独立，输入和完整依赖每次重新传入；失败时根据真实诊断修正需求 JSON 或自定义服务，不绕过编译、Schema 或结果门禁。stdout/stderr 是内部诊断，模型读取验证事实使用返回的 `public_presentation.results`，不能通过空的 stdout 上下文引用推定成功。

脚本只读取这一明确输入，拒绝缺失或多个同名输入，不扫描包内示例。它在 `/workspace/work` 生成源码，通过真实 Python 编译、官方 SDK 客户端的 initialize、tools/list 和 tools/call，逐项核对 Schema、预期 JSON、声明的错误测试及未知工具错误；任一失败或 30 秒协议超时均不产出可交付源码。服务仅用当前 Python 启动，不调用 Shell，不安装依赖，不部署外部服务。

自定义源码依赖只能使用已受管安装的 manifest 依赖和 Python 标准库。禁止在源码中下载/安装包、生成任意 Shell 命令、写入凭据或绕过平台授权。写操作工具必须按照目标服务真实契约定义幂等、取消和未知结果；stdio 本地验证不证明外部写操作的正确性。示例数据为合成数据，不能当成用户业务事实。

验证通过后 `output_files` 只登记 `result.json` 报告中的源码文件与明确要求的 ZIP；类型化结果由固定 `/workspace/output/result.json` 自动回读，验证报告不作为正式源码交付。不能将“文件已生成”“模型宣称成功”代替协议结果。最后通过 `finalize_task_outputs` 交付实际文件，表示填写 source/bundle、format、真实 artifact_id/path、entry_path；证据留在 `evidence` 或公开验证摘要，不能混入源码表示。没有可交付成果时使用 `abandon_reason` 如实说明失败。
