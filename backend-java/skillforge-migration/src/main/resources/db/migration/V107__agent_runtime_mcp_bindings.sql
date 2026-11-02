-- 不可变路由只记录原部署/Connector 冻结事实；不宣称输入 capability snapshot membership。
CREATE TABLE nw_runtime_mcp_bindings (
    tenant_id text NOT NULL CHECK (length(tenant_id)>0),
    handle text NOT NULL CHECK (handle LIKE 'tool:_%'),
    deployment_id text NOT NULL CHECK (length(deployment_id)>0),
    deployment_revision bigint NOT NULL CHECK (deployment_revision>0),
    capability_id text NOT NULL CHECK (length(capability_id)>0),
    version_id text NOT NULL CHECK (length(version_id)>0),
    input_schema_id text NOT NULL CHECK (length(input_schema_id)>0),
    input_schema text NOT NULL CHECK (jsonb_typeof(input_schema::jsonb)='object'),
    output_schema_id text NOT NULL CHECK (length(output_schema_id)>0),
    output_schema text NOT NULL CHECK (jsonb_typeof(output_schema::jsonb)='object'),
    effect text NOT NULL CHECK (effect IN ('READ_ONLY','IDEMPOTENT_WRITE','NON_IDEMPOTENT_WRITE','DESTRUCTIVE')),
    idempotent boolean NOT NULL CHECK (NOT idempotent),
    timeout text NOT NULL CHECK (length(timeout)>0),
    connector_id text NOT NULL CHECK (length(connector_id)>0),
    endpoint text NOT NULL CHECK (length(endpoint)>0),
    auth_type text NOT NULL CHECK (auth_type IN ('none','bearer')),
    encrypted_credential text,
    descriptor text NOT NULL CHECK (jsonb_typeof(descriptor::jsonb)='object'),
    descriptor_hash text NOT NULL CHECK (length(descriptor_hash)>0),
    tool_name text NOT NULL CHECK (length(tool_name)>0),
    protocol_version text NOT NULL CHECK (protocol_version = '2026-07-28'),
    content_digest text NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_runtime_mcp_bindings_pkey PRIMARY KEY(tenant_id,handle,deployment_id,deployment_revision,capability_id,version_id),
    CONSTRAINT mcp_binding_credential CHECK
        ((auth_type='none' AND encrypted_credential IS NULL)
         OR (auth_type='bearer' AND encrypted_credential IS NOT NULL AND length(encrypted_credential)>0)),
    CONSTRAINT mcp_binding_idempotent_effect CHECK (effect <> 'IDEMPOTENT_WRITE' OR idempotent)
);
ALTER TABLE nw_runtime_mcp_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_runtime_mcp_bindings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON nw_runtime_mcp_bindings
    USING (tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK (tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION agent_runtime_mcp_binding_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'mcp_binding_immutable';
END;
$$;
CREATE TRIGGER mcp_binding_immutable BEFORE UPDATE OR DELETE ON nw_runtime_mcp_bindings
    FOR EACH ROW EXECUTE FUNCTION agent_runtime_mcp_binding_immutable();
COMMENT ON TABLE nw_runtime_mcp_bindings IS 'MCP挂载目标与原部署/Connector的不可变精确路由，不替代Session能力快照授权';
COMMENT ON COLUMN nw_runtime_mcp_bindings.tenant_id IS '原部署及Connector所属租户';
COMMENT ON COLUMN nw_runtime_mcp_bindings.handle IS '冻结tool:<mountId>句柄，同部署允许多个不同挂载';
COMMENT ON COLUMN nw_runtime_mcp_bindings.deployment_id IS '原tool_deployments.deployment_key';
COMMENT ON COLUMN nw_runtime_mcp_bindings.deployment_revision IS '冻结时原部署精确revision';
COMMENT ON COLUMN nw_runtime_mcp_bindings.capability_id IS '原TOOL能力definition_id';
COMMENT ON COLUMN nw_runtime_mcp_bindings.version_id IS '原不可变TOOL版本id';
COMMENT ON COLUMN nw_runtime_mcp_bindings.input_schema_id IS '原FrozenToolTarget输入schema身份，不猜测或重写';
COMMENT ON COLUMN nw_runtime_mcp_bindings.input_schema IS '原FrozenToolTarget输入schema完整规范文本';
COMMENT ON COLUMN nw_runtime_mcp_bindings.output_schema_id IS '原FrozenToolTarget输出schema身份';
COMMENT ON COLUMN nw_runtime_mcp_bindings.output_schema IS '原FrozenToolTarget输出schema完整规范文本';
COMMENT ON COLUMN nw_runtime_mcp_bindings.effect IS '与原descriptor一致的副作用分类';
COMMENT ON COLUMN nw_runtime_mcp_bindings.idempotent IS '必须与原descriptor一致且为false；当前MCP适配器没有稳定动作键协议，不授权崩溃后自动幂等重放';
COMMENT ON COLUMN nw_runtime_mcp_bindings.timeout IS '原descriptor.timeout_ms无损转换的JDK Duration';
COMMENT ON COLUMN nw_runtime_mcp_bindings.connector_id IS '原同租户MCP Connector业务instance_id';
COMMENT ON COLUMN nw_runtime_mcp_bindings.endpoint IS '原Connector base_url，调用期间不重查当前端点';
COMMENT ON COLUMN nw_runtime_mcp_bindings.auth_type IS '只支持无认证none或bearer';
COMMENT ON COLUMN nw_runtime_mcp_bindings.encrypted_credential IS '原Connector冻结AES-GCM密文，不存明文';
COMMENT ON COLUMN nw_runtime_mcp_bindings.descriptor IS '冻结发布版本完整descriptor，保留来源可核验事实';
COMMENT ON COLUMN nw_runtime_mcp_bindings.descriptor_hash IS '原发布版本content_hash';
COMMENT ON COLUMN nw_runtime_mcp_bindings.tool_name IS '受信发布者明确提供且存在于原Connector capabilities的MCP名称';
COMMENT ON COLUMN nw_runtime_mcp_bindings.protocol_version IS '冻结无状态 MCP Streamable HTTP 协议版本 2026-07-28';
COMMENT ON COLUMN nw_runtime_mcp_bindings.content_digest IS '完整目标、原配置与发布Profile的SHA256';
COMMENT ON COLUMN nw_runtime_mcp_bindings.created_at IS '数据库首次冻结路由时间';
COMMENT ON CONSTRAINT mcp_binding_credential ON nw_runtime_mcp_bindings IS '认证类型与密文必须一致，禁止无版本Vault引用';
COMMENT ON CONSTRAINT mcp_binding_idempotent_effect ON nw_runtime_mcp_bindings IS '幂等写副作用必须有原幂等声明';
COMMENT ON INDEX skillforge_runtime_mcp_bindings_pkey IS '租户、完整挂载、部署版本和能力版本精确定位';
COMMENT ON FUNCTION agent_runtime_mcp_binding_immutable() IS '拒绝覆写或删除历史MCP路由';
