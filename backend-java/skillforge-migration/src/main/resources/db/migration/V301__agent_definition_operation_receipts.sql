-- 定义生命周期采用唯一原回执契约；本轮仅运行于新隔离测试库，不迁移旧toString指纹或伪造历史响应。
DROP INDEX agent_definition_create_request_idx;
ALTER TABLE nw_agent_templates DROP COLUMN create_actor_ref, DROP COLUMN create_request_key, DROP COLUMN create_fingerprint;
CREATE TABLE nw_agent_definition_operations (
    actor_ref varchar(160) NOT NULL CHECK(actor_ref ~ '^(platform_member|management_principal):[1-9][0-9]*$'),
    operation varchar(16) NOT NULL CHECK(operation IN('create','update','publish','deprecate','restore')),
    object_scope varchar(64) NOT NULL,
    request_key varchar(256) NOT NULL CHECK(btrim(request_key)<>''),
    fingerprint varchar(64) NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'),
    definition_id bigint NOT NULL REFERENCES nw_agent_templates(id) ON DELETE RESTRICT,
    schema_version integer NOT NULL DEFAULT 1 CHECK(schema_version=1),
    result_snapshot jsonb NOT NULL CHECK(jsonb_typeof(result_snapshot)='object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(actor_ref,operation,object_scope,request_key),
    CHECK((operation='create' AND object_scope='new') OR (operation<>'create' AND object_scope=definition_id::text)),
    CHECK(result_snapshot ? 'definition_id' AND result_snapshot->>'definition_id'=definition_id::text)
);
CREATE INDEX skillforge_agent_definition_operations_definition_idx ON nw_agent_definition_operations(definition_id,created_at);
ALTER TABLE nw_agent_definition_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_agent_definition_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_agent_definition_operations_platform ON nw_agent_definition_operations
    USING(coalesce(current_setting('app.tenant_id',true),'')='')
    WITH CHECK(coalesce(current_setting('app.tenant_id',true),'')='');
GRANT SELECT,INSERT ON nw_agent_definition_operations TO skillforge;
CREATE FUNCTION immutable_agent_definition_operation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'agent_definition_operation_immutable'; END $$;
CREATE TRIGGER agent_definition_operation_immutable BEFORE UPDATE OR DELETE ON nw_agent_definition_operations
    FOR EACH ROW EXECUTE FUNCTION immutable_agent_definition_operation();
COMMENT ON TABLE nw_agent_definition_operations IS '定义创建/编辑/发布/下架/恢复的已提交安全原回执；复用定义事务与锁，无独立执行状态';
COMMENT ON COLUMN nw_agent_definition_operations.actor_ref IS '稳定可信主体，不含凭据版本';
COMMENT ON COLUMN nw_agent_definition_operations.operation IS '精确生命周期操作，不隐含其他动作权限';
COMMENT ON COLUMN nw_agent_definition_operations.object_scope IS 'create为new，其他为稳定定义ID';
COMMENT ON COLUMN nw_agent_definition_operations.request_key IS '精确Idempotency-Key，不静默裁剪';
COMMENT ON COLUMN nw_agent_definition_operations.fingerprint IS '规范化命令摘要，包含预期修订，Map排序而有序数组保持顺序';
COMMENT ON COLUMN nw_agent_definition_operations.definition_id IS '原操作唯一涉及的稳定定义';
COMMENT ON COLUMN nw_agent_definition_operations.schema_version IS '原安全响应格式版本';
COMMENT ON COLUMN nw_agent_definition_operations.result_snapshot IS '首次成功后持久化的安全item响应，后续修改不重建或覆盖';
COMMENT ON COLUMN nw_agent_definition_operations.created_at IS '原回执随业务事务提交的记录时间';
