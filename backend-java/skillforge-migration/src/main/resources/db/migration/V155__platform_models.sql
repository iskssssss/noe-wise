-- 平台统一模型目录：配置版本不可变，机构只持有引用，不复制连接信息。
CREATE TABLE nw_platform_models (
 id bigint PRIMARY KEY,
 current_revision bigint NOT NULL CHECK(current_revision > 0)
);
COMMENT ON TABLE nw_platform_models IS '所有机构共用的平台模型目录及当前版本';
COMMENT ON COLUMN nw_platform_models.id IS '平台模型雪花标识';
COMMENT ON COLUMN nw_platform_models.current_revision IS '当前生效的不可变配置版本';
CREATE TABLE nw_platform_model_versions (
 model_id bigint NOT NULL REFERENCES nw_platform_models(id),
 revision bigint NOT NULL CHECK(revision > 0),
 config jsonb NOT NULL CHECK(jsonb_typeof(config)='object'),
 actor_id text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(model_id,revision)
);
COMMENT ON TABLE nw_platform_model_versions IS '不可变平台模型快照与同事务变更审计；凭据仅密文';
COMMENT ON COLUMN nw_platform_model_versions.model_id IS '所属平台模型';
COMMENT ON COLUMN nw_platform_model_versions.revision IS '单调增加的模型版本';
COMMENT ON COLUMN nw_platform_model_versions.config IS '经受控模型契约校验的完整不可变快照';
COMMENT ON COLUMN nw_platform_model_versions.actor_id IS '已认证的平台操作人';
COMMENT ON COLUMN nw_platform_model_versions.created_at IS '版本保存时间';
ALTER TABLE nw_platform_models ADD CONSTRAINT platform_model_current_revision_fk FOREIGN KEY(id,current_revision) REFERENCES nw_platform_model_versions(model_id,revision) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE nw_tenant_model_selections (
 tenant_id bigint PRIMARY KEY REFERENCES nw_tenants(id) ON DELETE CASCADE,
 llm_model_config_id bigint REFERENCES nw_platform_models(id),
 embedding_model_config_id bigint REFERENCES nw_platform_models(id),
 rerank_model_config_id bigint REFERENCES nw_platform_models(id)
);
COMMENT ON TABLE nw_tenant_model_selections IS '机构默认模型选择，平台启用的模型可由任意机构选择';
COMMENT ON COLUMN nw_tenant_model_selections.tenant_id IS '机构归属及隔离标识';
COMMENT ON COLUMN nw_tenant_model_selections.llm_model_config_id IS '默认LLM平台模型引用';
COMMENT ON COLUMN nw_tenant_model_selections.embedding_model_config_id IS '新知识库使用的默认Embedding模型引用';
COMMENT ON COLUMN nw_tenant_model_selections.rerank_model_config_id IS '默认Rerank模型引用';
ALTER TABLE nw_tenant_model_selections ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_tenant_model_selections FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_tenant_model_selections_isolation ON nw_tenant_model_selections USING (tenant_id::text=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id::text=current_setting('app.tenant_id',true));
ALTER TABLE nw_agents ADD COLUMN model_config_id bigint REFERENCES nw_platform_models(id);
COMMENT ON COLUMN nw_agents.model_config_id IS 'Agent选择的平台LLM；空值继承机构默认模型';
CREATE INDEX skillforge_agents_model_config_idx ON nw_agents(model_config_id) WHERE model_config_id IS NOT NULL;
COMMENT ON INDEX skillforge_agents_model_config_idx IS '平台模型引用查询';
CREATE TABLE nw_host_run_settings (
 tenant_id text NOT NULL,
 run_id text NOT NULL,
 snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,run_id)
);
COMMENT ON TABLE nw_host_run_settings IS '新Run受理前冻结的宿主模型和运营配置版本；无Runtime外键以支持先冻结后受理';
COMMENT ON COLUMN nw_host_run_settings.tenant_id IS 'Run机构隔离标识';
COMMENT ON COLUMN nw_host_run_settings.run_id IS '原始Run身份';
COMMENT ON COLUMN nw_host_run_settings.snapshot IS '只含模型标识、模型版本和配置分组版本，不含凭据';
COMMENT ON COLUMN nw_host_run_settings.created_at IS '首次冻结时间';
ALTER TABLE nw_host_run_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_host_run_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_host_run_settings_isolation ON nw_host_run_settings USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true));
CREATE FUNCTION reject_platform_configuration_version_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'configuration_version_immutable'; END $$;
CREATE TRIGGER platform_model_version_immutable BEFORE UPDATE OR DELETE ON nw_platform_model_versions FOR EACH ROW EXECUTE FUNCTION reject_platform_configuration_version_change();
CREATE TRIGGER skillforge_host_run_settings_immutable BEFORE UPDATE OR DELETE ON nw_host_run_settings FOR EACH ROW EXECUTE FUNCTION reject_platform_configuration_version_change();
