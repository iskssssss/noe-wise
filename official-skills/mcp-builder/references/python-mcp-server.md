# Python MCP 实现方法

执行环境固定 MCP SDK 1.26.0，支持官方 Server/FastMCP；精确输入和验证边界见 [输入契约](input-contract.md)。工具使用 snake_case 名称，描述应包含用途、参数、返回结构与错误；不得把合成示例数据作为用户事实。

FastMCP 会从函数签名和类型生成 inputSchema。自定义服务的实际 tools/list 必须与输入声明完全一致；若使用 Pydantic 参数对象，核对是否产生嵌套 params 字段，不能用预期的扁平 Schema 冒充实际目录。需要精确控制 Schema 时使用官方低层 Server。

工具错误应通过协议的 isError 或 SDK 支持的工具异常表达；不要把普通 Error 字符串当成协议错误。成功结果应能由客户端解析为 expected_json。未知工具必须返回 isError:true。stdio 的 stdout 只输出协议消息，安全诊断走 stderr。

把共用的客户端、分页、响应转换和错误处理提取成小函数；采用明确类型并校验必填、范围和额外字段。读取和写入分开定义；网络超时或未知写入结果不能自动重试。认证由目标系统的真实契约管理，不在源码写凭据，也不向聊天索取密钥。

仅当前本地 stdio 验证可执行，不使用 elicitation、HTTP 服务或未挂载资源作为恢复步骤。受管生成器统一进行编译与 SDK 客户端测试；不得另外启动长服务、安装包或调用外部服务。


## 自定义服务的精确 Schema 示例

仅当模板不能满足当前专业需求时才用 source_files。下例演示字符串标准化工具，input_schema 必须与这里的 SCHEMA 完全一致，成功测试的 expected_json 为对象。普通 sum/echo/lookup 使用平台模板，无需复制此例。示例没有外部请求、凭据或真实业务操作。

```python
import asyncio
import json
from jsonschema import Draft202012Validator
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import CallToolResult, TextContent, Tool, ToolAnnotations

SCHEMA = {"type": "object", "properties": {"text": {"type": "string", "maxLength": 200}},
          "required": ["text"], "additionalProperties": False}
server = Server("text_mcp")

@server.list_tools()
async def list_tools():
    return [Tool(name="normalize_text", description="按明确要求移除文本首尾空白", inputSchema=SCHEMA,
                 annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False,
                                            idempotentHint=True, openWorldHint=False))]

@server.call_tool()
async def call_tool(name, arguments):
    if name != "normalize_text":
        return CallToolResult(content=[TextContent(type="text", text="Unknown tool")], isError=True)
    if not isinstance(arguments, dict) or list(Draft202012Validator(SCHEMA).iter_errors(arguments)):
        return CallToolResult(content=[TextContent(type="text", text="Invalid arguments")], isError=True)
    value = {"text": arguments["text"].strip()}
    return CallToolResult(content=[TextContent(type="text", text=json.dumps(value, ensure_ascii=False))],
                          structuredContent=value, isError=False)

async def main():
    async with stdio_server() as (read, write):
        await server.run(read, write, server.create_initialization_options())

if __name__ == "__main__":
    asyncio.run(main())
```

对应 tools 项：name=normalize_text、operation 省略、input_schema=SCHEMA；tests 至少含 arguments={"text":"  示例  "}、expected_json={"text":"示例"}，以及超长/缺失/额外字段的 expect_error:true。这是合成测试，不是用户资料。输入 Schema 校验不能代替业务权限检查；有写入时需另行落实真实目标服务授权与幂等。

## FastMCP 与类型化参数

FastMCP 的函数参数直接影响真实工具 Schema，例如：

```python
from mcp.server.fastmcp import FastMCP
mcp = FastMCP("text_mcp")

@mcp.tool()
def normalize_text(text: str) -> dict:
    """按明确要求移除文本首尾空白。"""
    return {"text": text.strip()}
```

此最小片段只说明签名映射，不提供上述低层例子的 maxLength/额外字段与未知工具处理保证。需要这些约束时补齐对应实现并由受管目录/错误测试核验；不能给实际没有的约束作声明。Pydantic BaseModel 适合多个关联字段和字段校验，但参数对象可能引入一层 params，必须沿真实 tools/list 设计声明。

## 分页与资源的边界

受支持 lookup 模板使用 offset>=0、1<=limit<=100；按真实数据切片后返回 items、total_count、has_more 和 next_offset，末页 next_offset=null。自定义实现复用这一明确口径，不先加载无限数据再假装分页。

SDK 的资源读取与工具调用属于不同协议能力。本包受管验证覆盖 tools/list 与 tools/call，不将 resources/read、sampling 或 elicitation 写成已接入的平台能力。需要从显式输入文件读取静态数据时，生成自包含源码或明确辅助模块；不猜宿主路径。可以用普通小函数把读取、业务转换和结果包装分开，但不能把路径参数当成文件授权。
