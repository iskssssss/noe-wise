# MCP 工具设计原则

工具名用 snake_case，描述精确说明任务与边界；按需要加服务前缀，避免同名歧义。不要为了通用规范改写用户已指定的名称或增加不需要的接口。

输入 Schema、真实 tools/list 与调用行为保持一致。列表按约定分页，限制单次结果数量，并提供 has_more、next_offset 与 total_count 等真实元数据。返回结构以当前输入契约与 expected_json 为准，不强制给每个工具加 response_format 参数或额外输出格式。

明确 readOnlyHint、destructiveHint、idempotentHint、openWorldHint；这些注解只是提示，不能替代服务端授权。身份、租户、Scope 与凭据不得仅信任模型参数。校验路径、URL、大小和范围，避免命令注入与内部错误泄露。

成功返回可解析 JSON，工具失败按 isError 表达，保留有助于修正且不含秘密的说明。清理子进程与连接，明确取消、幂等和未知结果，禁止把超时写成成功或零值。

当前执行范围仅受管 Python stdio；HTTP/OAuth/外部集成设计不能视为已实现或已验收。验证与源码交付遵循 [输入契约](input-contract.md) 和根入口，不另加负载、真实服务或模型评测要求。
