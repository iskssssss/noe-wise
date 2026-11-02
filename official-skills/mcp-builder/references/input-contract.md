# Python MCP 输入与验证契约

入口读取唯一明确挂载的 `mcp-server.json`，输出源码目录 `mcp-server/`。输入上限 2 MiB、最多 32 个工具、32 个 `.py` 源码、每个源码 512 KiB、64 个协议测试。自定义源码始终在隔离的 `code_execute` 环境执行，宿主机不直接运行生成服务。

| 字段 | 含义 |
|---|---|
| `name` | 2 至 64 位小写服务名称 |
| `tools` | 工具目录：唯一 snake_case 名称、description、object 类型 `input_schema` |
| `tools[].operation` | 模板模式支持 `echo`、`sum`、`lookup`，自定义源码模式不使用此字段 |
| `tools[].lookup_field`、`records` | lookup 的精确字段及明确静态数据；返回分页 items/total_count/has_more/next_offset |
| `source_files` | 可选相对 `.py` 路径到源码的映射，必须有 `server.py`；禁止绝对路径、回退和覆盖输出目录 |
| `tests` | tool、arguments、expected_json 或 `expect_error:true`；每个工具至少一项有预期 JSON 的成功测试 |
| `bundle_zip` | 默认 false；仅用户明确要求时设为 true |

模板 sum 的输入 Schema 应要求数值数组 `values`；lookup 应要求 `value` 并约束 `offset >= 0`、`1 <= limit <= 100`。Schema 采用 JSON Schema 2020-12，建议 `additionalProperties:false`。成功结果为单个 JSON 文本或 `structuredContent`，客户端实际解析后与 `expected_json` 比较。

普通数值数组求和使用 [单工具求和示例](../examples/mcp-sum.json)，保留用户提供的工具名称、约束和预期结果。`operation: "sum"` 已由生成器实现，不需要 `source_files` 或另写 FastMCP。该示例声明 `[120.5,120.5,249]` 返回 `{"sum":490}`，以及字符串数组输入返回协议错误；这里的数值是合成验证输入，不是业务事实。复制完整 JSON 到 `input_files["mcp-server.json"]` 字符串，不能只传 `tools` 或仅挂载包内示例文件。

调用固定使用 `mode: "skill"`、当前激活回执的真实 `skill_handle`、`language: "python"`、`script_path: "scripts/build_mcp_server.py"`；不传 `code`。每次原样声明 [manifest](../manifest.json) 的全部 29 项固定依赖，声明三个源码 `output_files`；类型化结果由固定 `/workspace/output/result.json` 自动回读。生成器自动完成 SDK 子进程环境与测试，不用 inline 复制它或补写独立测试器。

专业服务开发可直接提供 `source_files`。实现采用 MCP SDK 官方 Server/FastMCP，stdout 只输出协议消息；诊断日志写 stderr。工具目录必须与 `tools` 精确一致，包括每个 inputSchema。测试可添加无效类型、缺失字段、空数据、分页边界、服务异常等错误路径。生成器额外验证未知工具返回 `isError:true`。

脚本使用当前 Python 进程的解释器启动固定 `server.py`，验证最长 30 秒，退出/超时关闭 stdio 子进程。编译产物、临时文件及服务进程在 work 目录内；成功后只复制源码、使用说明和 `requirements.txt` 为交付候选。公开检查为 `result.json`，含实际协商 protocol_version、工具目录、测试结果和文件摘要；`external_service_tested:false` 表示本地协议验证没有访问真实外部服务。

源码用 `mcp==1.26.0` 启动。平台 manifest 完整列出隔离环境传递依赖；`requirements.txt` 是用户在自己环境安装 SDK 的入口，不授予在 Agent 运行时动态安装的权限。
