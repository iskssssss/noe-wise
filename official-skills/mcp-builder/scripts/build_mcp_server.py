"""生成 Python MCP 源码，在同一受管环境编译并通过官方 stdio 客户端验证。"""
from __future__ import annotations

import asyncio
import hashlib
from importlib.metadata import version
import json
import os
from pathlib import Path, PurePosixPath
import py_compile
import re
import shutil
import sys
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile

from jsonschema import Draft202012Validator
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

INPUT_DATA = Path("/input/data")
INPUT_ARTIFACTS = Path("/input/artifacts")
OUTPUT = Path("/workspace/output")
WORK = Path("/workspace/work")
MAX_INPUT_BYTES = 2 * 1024 * 1024
MAX_SOURCE_BYTES = 512 * 1024
PROTOCOL_TIMEOUT_SECONDS = 30

SERVER_TEMPLATE = '''"""受管生成的只读 MCP 服务；stdio stdout 仅用于协议消息。"""
import asyncio
import json
from jsonschema import Draft202012Validator
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import CallToolResult, TextContent, Tool, ToolAnnotations

SPEC = json.loads(__SPEC__)
server = Server(SPEC["name"])
tools = {item["name"]: item for item in SPEC["tools"]}

@server.list_tools()
async def list_tools():
    return [Tool(name=t["name"], description=t["description"], inputSchema=t["input_schema"],
                 annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False,
                                            idempotentHint=True, openWorldHint=False))
            for t in SPEC["tools"]]

@server.call_tool()
async def call_tool(name, arguments):
    tool = tools.get(name)
    if tool is None:
        return CallToolResult(content=[TextContent(type="text", text="Unknown tool")], isError=True)
    errors = list(Draft202012Validator(tool["input_schema"]).iter_errors(arguments))
    if errors:
        return CallToolResult(content=[TextContent(type="text", text="Invalid arguments")], isError=True)
    operation = tool["operation"]
    if operation == "echo":
        result = arguments
    elif operation == "sum":
        result = {"sum": sum(arguments["values"])}
    else:
        field = tool["lookup_field"]
        rows = [row for row in tool["records"] if row.get(field) == arguments["value"]]
        limit = min(arguments.get("limit", 20), 100)
        offset = arguments.get("offset", 0)
        page = rows[offset:offset + limit]
        more = offset + limit < len(rows)
        result = {"items": page, "total_count": len(rows), "has_more": more,
                  "next_offset": offset + limit if more else None}
    return CallToolResult(content=[TextContent(type="text", text=json.dumps(result, ensure_ascii=False))],
                          structuredContent=result, isError=False)

async def main():
    async with stdio_server() as (read, write):
        await server.run(read, write, server.create_initialization_options())

if __name__ == "__main__":
    asyncio.run(main())
'''


def locate_input():
    """只读取明确 data 或单个成果挂载，禁止读取包内示例或猜测工作区。"""
    paths = [INPUT_DATA / "mcp-server.json"]
    if INPUT_ARTIFACTS.is_dir():
        paths.extend(p / "mcp-server.json" for p in INPUT_ARTIFACTS.iterdir() if p.is_dir())
    matches = [p for p in paths if p.is_file()]
    if len(matches) != 1:
        raise ValueError("需要且只能挂载一个 mcp-server.json")
    path = matches[0].resolve()
    if not any(path.is_relative_to(p.resolve()) for p in (INPUT_DATA, INPUT_ARTIFACTS)):
        raise ValueError("输入超出挂载根")
    if path.stat().st_size > MAX_INPUT_BYTES:
        raise ValueError("输入超过 2 MiB")
    return json.loads(path.read_text(encoding="utf-8"))


def text(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(label + " 必须是非空字符串")
    return value


def validate(data):
    if not isinstance(data, dict):
        raise ValueError("输入必须是对象")
    name = text(data.get("name"), "name")
    if not re.fullmatch(r"[a-z][a-z0-9_-]{1,63}", name):
        raise ValueError("name 必须是 2 至 64 位小写服务名称")
    tools = data.get("tools")
    if not isinstance(tools, list) or not 1 <= len(tools) <= 32:
        raise ValueError("tools 必须含 1 至 32 项")
    custom = data.get("source_files") is not None
    seen = set()
    for tool in tools:
        if not isinstance(tool, dict):
            raise ValueError("tool 必须是对象")
        key = text(tool.get("name"), "tool.name")
        if not re.fullmatch(r"[a-z][a-z0-9_]{1,63}", key) or key in seen:
            raise ValueError("tool.name 必须唯一且为 snake_case")
        seen.add(key)
        text(tool.get("description"), "description")
        schema = tool.get("input_schema")
        Draft202012Validator.check_schema(schema)
        if not isinstance(schema, dict) or schema.get("type") != "object":
            raise ValueError("input_schema 必须声明 object")
        if not custom:
            operation = tool.get("operation")
            if operation not in ("echo", "sum", "lookup"):
                raise ValueError("受管模板仅支持 echo、sum、lookup；专业实现提供 source_files")
            if operation == "lookup":
                text(tool.get("lookup_field"), "lookup_field")
                if not isinstance(tool.get("records"), list) or len(tool["records"]) > 10000 or any(not isinstance(r, dict) for r in tool["records"]):
                    raise ValueError("records 必须是最多 10000 项对象数组")
    tests = data.get("tests")
    if not isinstance(tests, list) or not 1 <= len(tests) <= 64:
        raise ValueError("必须提供 1 至 64 项真实协议测试")
    positive = set()
    for test in tests:
        if not isinstance(test, dict) or test.get("tool") not in seen or not isinstance(test.get("arguments"), dict):
            raise ValueError("测试必须引用已声明工具及对象 arguments")
        error = test.get("expect_error", False)
        if not isinstance(error, bool):
            raise ValueError("expect_error 必须是布尔值")
        if not error:
            if "expected_json" not in test:
                raise ValueError("成功测试必须填写 expected_json，不能只检查模型宣称成功")
            positive.add(test["tool"])
    if positive != seen:
        raise ValueError("每个工具至少需要一个带预期结果的成功测试")
    if not isinstance(data.get("bundle_zip", False), bool):
        raise ValueError("bundle_zip 必须是布尔值")
    return data


def sources(data):
    if data.get("source_files") is None:
        encoded = json.dumps({"name": data["name"], "tools": data["tools"]}, ensure_ascii=False)
        return {"server.py": SERVER_TEMPLATE.replace("__SPEC__", repr(encoded))}
    files = data["source_files"]
    if not isinstance(files, dict) or not 1 <= len(files) <= 32 or "server.py" not in files:
        raise ValueError("source_files 必须含 server.py，最多 32 项")
    result = {}
    for filename, content in files.items():
        path = PurePosixPath(filename)
        if path.is_absolute() or ".." in path.parts or "\\" in filename or ":" in filename or "\x00" in filename or path.suffix != ".py":
            raise ValueError("源码路径必须是无回退的相对 .py 路径")
        text(content, "源码")
        if len(content.encode("utf-8")) > MAX_SOURCE_BYTES:
            raise ValueError("单个源码超过 512 KiB")
        result[filename] = content
    return result


def result_json(result):
    if result.structuredContent is not None:
        return result.structuredContent
    chunks = [p.text for p in result.content if p.type == "text"]
    if len(chunks) != 1:
        raise ValueError("成功结果须为单个 JSON 文本或 structuredContent")
    return json.loads(chunks[0])


async def protocol_check(project, data):
    """只启动明确生成的 Python 服务；客户端退出或超时会关闭 stdio 子进程。"""
    # SDK 的默认环境白名单没有 PYTHONPATH；只传受管包目录，不传播业务凭据。
    parameters = StdioServerParameters(command=sys.executable, args=[str(project / "server.py")], cwd=str(project),
                                       env={"PYTHONPATH": os.environ.get("PYTHONPATH", "")})
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write) as client:
            initialized = await client.initialize()
            listed = await client.list_tools()
            actual = {t.name: t for t in listed.tools}
            expected = {t["name"]: t for t in data["tools"]}
            if set(actual) != set(expected) or listed.nextCursor is not None:
                raise ValueError("tools/list 名称或分页与声明不一致")
            for name, tool in expected.items():
                if actual[name].inputSchema != tool["input_schema"]:
                    raise ValueError("tools/list inputSchema 与输入契约不一致")
            checks = []
            for index, test in enumerate(data["tests"]):
                value = await client.call_tool(test["tool"], test["arguments"])
                wanted_error = test.get("expect_error", False)
                if value.isError != wanted_error:
                    raise ValueError(f"测试 {index} isError 不符合预期")
                if not wanted_error and result_json(value) != test["expected_json"]:
                    raise ValueError(f"测试 {index} 真实结果不符合预期")
                checks.append({"index": index, "tool": test["tool"], "passed": True, "expected_error": wanted_error})
            missing = await client.call_tool("skillforge_nonexistent_tool", {})
            if not missing.isError:
                raise ValueError("未声明工具未返回协议错误")
            return {"transport": "stdio", "protocol_version": initialized.protocolVersion,
                    "server_name": initialized.serverInfo.name, "sdk": "mcp==1.26.0",
                    "listed_tools": sorted(actual), "checks": checks, "unknown_tool_error": True}


def main():
    if version("mcp") != "1.26.0":
        raise ValueError("执行环境 MCP SDK 必须与受管版本 mcp==1.26.0 一致")
    data = validate(locate_input())
    files = sources(data)
    WORK.mkdir(parents=True, exist_ok=True)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="mcp-build-", dir=WORK) as directory:
        project = Path(directory)
        compiled = []
        for filename, source in files.items():
            destination = project / filename
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(source, encoding="utf-8")
            py_compile.compile(str(destination), cfile=str(project / (hashlib.sha256(filename.encode()).hexdigest() + ".pyc")), doraise=True)
            compiled.append(filename)
        report = asyncio.run(asyncio.wait_for(protocol_check(project, data), PROTOCOL_TIMEOUT_SECONDS))
        report.update(compiled_sources=sorted(compiled), external_service_tested=False)
        for filename, source in files.items():
            path = project / filename
            if path.is_symlink() or not path.is_file() or path.read_text(encoding="utf-8") != source:
                raise ValueError("协议执行修改了冻结源码，不能交付未经编译验证的字节")
        # 只有真实编译和协议测试通过后才复制为交付候选，证据不混入源码包。
        output = OUTPUT / "mcp-server"
        if output.exists():
            raise ValueError("输出目录已存在，禁止覆盖既有候选")
        output.mkdir()
        delivered = []
        for filename in files:
            destination = output / filename
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(project / filename, destination)
            delivered.append("mcp-server/" + filename)
        (output / "requirements.txt").write_text("mcp==1.26.0\n", encoding="utf-8")
        (output / "README.md").write_text(
            f"# {data['name']}\n\nPython 3.12，依赖见 requirements.txt。\n\n"
            "启动命令：`python server.py`。stdio stdout 仅输出 MCP 协议。\n\n"
            "源码已通过同环境 Python 编译、官方 MCP 客户端初始化、工具目录、输入 Schema、声明测试和未知工具错误验证。"
            "外部服务凭据、真实服务访问、HTTP 部署和生产运行尚需单独配置与验收。\n", encoding="utf-8")
        delivered += ["mcp-server/requirements.txt", "mcp-server/README.md"]
        if data.get("bundle_zip", False):
            with ZipFile(OUTPUT / "mcp-server.zip", "w", ZIP_DEFLATED) as archive:
                for filename in sorted(delivered):
                    archive.write(OUTPUT / filename, filename)
            delivered.append("mcp-server.zip")
        report["output_files"] = delivered
        report["sources"] = [{"path": p, "sha256": hashlib.sha256((OUTPUT / p).read_bytes()).hexdigest()} for p in delivered]
        public = {"results": [{"kind": "text", "format": "plain", "text": json.dumps(report, ensure_ascii=False)}]}
        (OUTPUT / "result.json").write_text(json.dumps(public, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
