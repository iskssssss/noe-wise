-- MCP discovery maps readOnlyHint to idempotent=true.  Read-only calls are
-- safe to repeat; only write actions need the stricter action-key contract.
ALTER TABLE nw_runtime_mcp_bindings
    DROP CONSTRAINT nw_runtime_mcp_bindings_idempotent_check;

ALTER TABLE nw_runtime_mcp_bindings
    ADD CONSTRAINT skillforge_runtime_mcp_bindings_idempotent_check
    CHECK (effect = 'READ_ONLY' OR NOT idempotent);
