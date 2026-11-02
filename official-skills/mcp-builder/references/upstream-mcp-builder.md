# MCP 服务开发方法参考

先确定目标服务的真实 API、输入输出、权限与副作用，再设计名称清晰、边界小的工具。优先完成当前任务需要的能力，不为覆盖所有 API 扩大范围。资料来源须真实可追溯；未知 API、字段或权限保留缺口。

平台只支持根入口规定的 Python stdio、固定 SDK 与受管构建测试。普通 echo/sum/lookup 使用已有模板；自定义逻辑才提供 source_files。不要因上游方法另建 TypeScript、HTTP 服务、Inspector 或模型评测流程。

实现时对齐声明的 input_schema、返回 JSON 与错误语义；明确分页、边界、取消、幂等和未知结果处理。用受管验证器检查实际初始化、工具目录和调用结果，预期来自明确契约。测试通过不代表已连接真实第三方服务。

按 [输入契约](input-contract.md)、[Python 方法](python-mcp-server.md) 与 [设计原则](mcp-best-practices.md) 完成当前源码交付。额外外部集成、部署或模型质量评测属于独立任务。
