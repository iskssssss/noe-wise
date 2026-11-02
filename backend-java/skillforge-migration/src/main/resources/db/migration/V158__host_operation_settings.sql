CREATE TABLE nw_host_operation_settings (
 tenant_id text NOT NULL,
 run_id text NOT NULL,
 operation_key text NOT NULL,
 snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,run_id,operation_key)
);
COMMENT ON TABLE nw_host_operation_settings IS '单次模型或工具动作的不可变配置快照；新动作使用当前配置，恢复使用原版本';
COMMENT ON COLUMN nw_host_operation_settings.tenant_id IS '动作所属机构';
COMMENT ON COLUMN nw_host_operation_settings.run_id IS '动作所属Run';
COMMENT ON COLUMN nw_host_operation_settings.operation_key IS '可信宿主生成的模型迭代或工具调用身份';
COMMENT ON COLUMN nw_host_operation_settings.snapshot IS '模型与运营配置版本引用及本次角色指令，不含凭据';
COMMENT ON COLUMN nw_host_operation_settings.created_at IS '动作配置首次捕获时间';
ALTER TABLE nw_host_operation_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_host_operation_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_host_operation_settings_isolation ON nw_host_operation_settings USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE TRIGGER skillforge_host_operation_settings_immutable BEFORE UPDATE OR DELETE ON nw_host_operation_settings FOR EACH ROW EXECUTE FUNCTION reject_platform_configuration_version_change();
