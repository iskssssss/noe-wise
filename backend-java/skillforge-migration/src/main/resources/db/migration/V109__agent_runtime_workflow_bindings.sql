-- 实例 revision 的权威身份是 definition_version_id；配置与图在可信发布时共同冻结。
CREATE TABLE nw_runtime_workflow_bindings (
    tenant_id text NOT NULL CHECK(length(tenant_id)>0),
    handle text NOT NULL CHECK(handle LIKE 'workflow:_%'),
    instance_id text NOT NULL CHECK(instance_id ~ '^[1-9][0-9]*$'),
    instance_revision bigint NOT NULL CHECK(instance_revision>0),
    installation_revision bigint NOT NULL CHECK(installation_revision>0),
    capability_id text NOT NULL CHECK(capability_id ~ '^[1-9][0-9]*$'),
    version_id text NOT NULL CHECK(version_id ~ '^[1-9][0-9]*$'),
    timeout text NOT NULL CHECK(length(timeout)>0),
    workflow_key text NOT NULL CHECK(length(workflow_key)>0),
    definition_json text NOT NULL CHECK(jsonb_typeof(definition_json::jsonb)='object'),
    config_json text NOT NULL CHECK(jsonb_typeof(config_json::jsonb)='object'),
    bindings_json text NOT NULL CHECK(jsonb_typeof(bindings_json::jsonb)='object'),
    resolved_bindings_json text NOT NULL CHECK(jsonb_typeof(resolved_bindings_json::jsonb)='object'),
    custom_prompt text,
    descriptor text NOT NULL CHECK(jsonb_typeof(descriptor::jsonb)='object'),
    digest text NOT NULL CHECK(digest ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT skillforge_runtime_workflow_bindings_pkey PRIMARY KEY(tenant_id,handle,instance_id,instance_revision,installation_revision,capability_id,version_id)
);
ALTER TABLE nw_runtime_workflow_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_runtime_workflow_bindings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON nw_runtime_workflow_bindings
    USING(tenant_id=current_setting('app.tenant_id',true))
    WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION agent_runtime_workflow_binding_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'workflow_binding_immutable';
END;
$$;
CREATE TRIGGER workflow_binding_immutable BEFORE UPDATE OR DELETE ON nw_runtime_workflow_bindings
    FOR EACH ROW EXECUTE FUNCTION agent_runtime_workflow_binding_immutable();
COMMENT ON TABLE nw_runtime_workflow_bindings IS 'Workflow原挂载目标、实例配置与不可变定义的完整冻结事实，不替代Input成员资格';
COMMENT ON COLUMN nw_runtime_workflow_bindings.installation_revision IS '精确不可变安装修订，隔离同一定义的不同参数和槽绑定';
COMMENT ON COLUMN nw_runtime_workflow_bindings.bindings_json IS '安装时显式选择的能力、子图和 Agent 槽绑定完整快照';
COMMENT ON COLUMN nw_runtime_workflow_bindings.resolved_bindings_json IS '准入时解析的精确 Tool、Knowledge、子 Workflow 与 Agent 目标闭包';
COMMENT ON COLUMN nw_runtime_workflow_bindings.tenant_id IS '原实例及定义所属租户';
COMMENT ON COLUMN nw_runtime_workflow_bindings.handle IS '冻结workflow:<mountId>，同实例可多挂载';
COMMENT ON COLUMN nw_runtime_workflow_bindings.instance_id IS 'workflow_instances.id主键文本，不是业务instance_id';
COMMENT ON COLUMN nw_runtime_workflow_bindings.instance_revision IS '原instance.definition_version_id即不可变定义主键';
COMMENT ON COLUMN nw_runtime_workflow_bindings.capability_id IS '原WORKFLOW capability definition主键';
COMMENT ON COLUMN nw_runtime_workflow_bindings.version_id IS '与原DefinitionVersion关联的capability version主键';
COMMENT ON COLUMN nw_runtime_workflow_bindings.timeout IS '完整冻结JDK Duration，不从当前配置重算';
COMMENT ON COLUMN nw_runtime_workflow_bindings.workflow_key IS '原workflow container的workflow_id';
COMMENT ON COLUMN nw_runtime_workflow_bindings.definition_json IS '原不可变版本完整图JSON对象';
COMMENT ON COLUMN nw_runtime_workflow_bindings.config_json IS '同次读取的实例配置JSON对象';
COMMENT ON COLUMN nw_runtime_workflow_bindings.custom_prompt IS '同次读取的可空实例Prompt，空字符串与NULL区分';
COMMENT ON COLUMN nw_runtime_workflow_bindings.descriptor IS '原发布WORKFLOW capability descriptor完整对象';
COMMENT ON COLUMN nw_runtime_workflow_bindings.digest IS '完整目标和来源字段长度分帧SHA256';
COMMENT ON COLUMN nw_runtime_workflow_bindings.created_at IS '数据库首次冻结时刻';
COMMENT ON INDEX skillforge_runtime_workflow_bindings_pkey IS '精确租户/挂载/实例/定义/能力版本定位';
COMMENT ON FUNCTION agent_runtime_workflow_binding_immutable() IS '拒绝覆盖或删除历史Workflow绑定';
