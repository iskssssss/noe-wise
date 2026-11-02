-- 协议版本属于冻结路由；新增显式握手模式，不改变已有发布绑定内容。
ALTER TABLE nw_runtime_mcp_bindings
    DROP CONSTRAINT nw_runtime_mcp_bindings_protocol_version_check;
ALTER TABLE nw_runtime_mcp_bindings
    ADD CONSTRAINT skillforge_runtime_mcp_bindings_protocol_version_check
    CHECK (protocol_version IN ('2026-07-28', '2025-11-25'));
