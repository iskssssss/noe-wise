CREATE TABLE nw_system_agent_runtime_capability_sources (
 tenant_id text NOT NULL,
 session_id text NOT NULL,
 agent_id text NOT NULL,
 end_user_id bigint NOT NULL CHECK(end_user_id>0),
 input_id text NOT NULL,
 generation bigint NOT NULL CHECK(generation>0),
 source_data jsonb NOT NULL CHECK(jsonb_typeof(source_data)='object'),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,session_id,input_id),
 FOREIGN KEY(tenant_id,agent_id,session_id) REFERENCES nw_runtime_host_owners(tenant_id,agent_id,session_id)
);
ALTER TABLE nw_system_agent_runtime_capability_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_system_agent_runtime_capability_sources FORCE ROW LEVEL SECURITY;
CREATE POLICY system_agent_sources_tenant ON nw_system_agent_runtime_capability_sources
 USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
COMMENT ON TABLE nw_system_agent_runtime_capability_sources IS '平台通用Runtime每次输入冻结的完整模型与能力来源';
COMMENT ON COLUMN nw_system_agent_runtime_capability_sources.tenant_id IS '执行隔离空间';
COMMENT ON COLUMN nw_system_agent_runtime_capability_sources.session_id IS '系统运行对应Session';
COMMENT ON COLUMN nw_system_agent_runtime_capability_sources.agent_id IS '平台智能体身份';
COMMENT ON COLUMN nw_system_agent_runtime_capability_sources.end_user_id IS '与owner全值匹配的内部执行主体';
COMMENT ON COLUMN nw_system_agent_runtime_capability_sources.input_id IS 'Runtime输入幂等身份';
COMMENT ON COLUMN nw_system_agent_runtime_capability_sources.generation IS '本次冻结目录代次';
COMMENT ON COLUMN nw_system_agent_runtime_capability_sources.source_data IS '不含明文凭据的执行配置与能力快照';
COMMENT ON COLUMN nw_system_agent_runtime_capability_sources.created_at IS '冻结时间';
CREATE FUNCTION reject_system_agent_immutable_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'system_agent_immutable_record'; END; $$;
CREATE TRIGGER system_agent_source_immutable BEFORE UPDATE OR DELETE ON nw_system_agent_runtime_capability_sources
 FOR EACH ROW EXECUTE FUNCTION reject_system_agent_immutable_change();
CREATE TRIGGER system_agent_version_immutable BEFORE UPDATE OR DELETE ON nw_platform_system_agent_versions
 FOR EACH ROW EXECUTE FUNCTION reject_system_agent_immutable_change();
