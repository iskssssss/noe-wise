ALTER TABLE nw_workflow_instances ADD COLUMN installation_revision bigint NOT NULL DEFAULT 0 CHECK (installation_revision >= 0);
COMMENT ON COLUMN nw_workflow_instances.installation_revision IS '当前不可变安装修订；0 表示尚未安装，不能执行';
ALTER TABLE nw_workflow_definition_versions ADD CONSTRAINT workflow_definition_tenant_identity UNIQUE(tenant_id,id);

CREATE TABLE nw_workflow_installation_revisions (
  tenant_id bigint NOT NULL,
  instance_id bigint NOT NULL,
  installation_revision bigint NOT NULL CHECK(installation_revision > 0),
  definition_version_id bigint NOT NULL,
  config jsonb NOT NULL CHECK(jsonb_typeof(config)='object'),
  custom_prompt text NOT NULL,
  bindings jsonb NOT NULL CHECK(jsonb_typeof(bindings)='object'),
  created_at timestamptz NOT NULL,
  PRIMARY KEY(tenant_id,instance_id,installation_revision),
  FOREIGN KEY(tenant_id,instance_id) REFERENCES nw_workflow_instances(tenant_id,id),
  FOREIGN KEY(tenant_id,definition_version_id) REFERENCES nw_workflow_definition_versions(tenant_id,id)
);
COMMENT ON TABLE nw_workflow_installation_revisions IS 'Workflow 不可变安装事实，冻结业务配置和显式能力、子图、Agent 槽映射';
COMMENT ON COLUMN nw_workflow_installation_revisions.tenant_id IS '安装所属租户';
COMMENT ON COLUMN nw_workflow_installation_revisions.instance_id IS 'Workflow 实例主键';
COMMENT ON COLUMN nw_workflow_installation_revisions.installation_revision IS '实例内单调递增修订，不等同定义版本';
COMMENT ON COLUMN nw_workflow_installation_revisions.definition_version_id IS '精确不可变 DefinitionVersion';
COMMENT ON COLUMN nw_workflow_installation_revisions.config IS '动态业务参数完整快照，不含凭证';
COMMENT ON COLUMN nw_workflow_installation_revisions.custom_prompt IS '实例提示词完整快照';
COMMENT ON COLUMN nw_workflow_installation_revisions.bindings IS '类型化槽绑定序列化快照，禁止运行时按名称查找';
COMMENT ON COLUMN nw_workflow_installation_revisions.created_at IS '修订创建时间';
ALTER TABLE nw_workflow_installation_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_workflow_installation_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON nw_workflow_installation_revisions
 USING(tenant_id::text=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
CREATE FUNCTION reject_workflow_installation_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'workflow installation revisions are immutable'; END $$;
CREATE TRIGGER workflow_installation_immutable BEFORE UPDATE OR DELETE ON nw_workflow_installation_revisions
 FOR EACH ROW EXECUTE FUNCTION reject_workflow_installation_mutation();

CREATE FUNCTION require_workflow_installation_pointer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.installation_revision = 0 THEN
    IF NEW.status = 'published' THEN RAISE EXCEPTION 'published workflow requires installation revision'; END IF;
  ELSIF NOT EXISTS (
    SELECT 1 FROM nw_workflow_installation_revisions r
    WHERE r.tenant_id=NEW.tenant_id AND r.instance_id=NEW.id
      AND r.installation_revision=NEW.installation_revision
      AND r.definition_version_id=NEW.definition_version_id
  ) THEN
    RAISE EXCEPTION 'workflow installation pointer does not match immutable revision';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER workflow_installation_pointer BEFORE INSERT OR UPDATE OF installation_revision,definition_version_id,status
 ON nw_workflow_instances FOR EACH ROW EXECUTE FUNCTION require_workflow_installation_pointer();

DROP TABLE IF EXISTS nw_workflow_tool_connector_bindings;
