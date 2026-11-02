-- Agent 本体保存稳定身份与工作草稿；发布历史保存不可修改的配置和挂载快照。
ALTER TABLE nw_agents DROP CONSTRAINT uq_skillforge_agents_tenant_key_revision;
ALTER TABLE nw_agents ADD CONSTRAINT uq_skillforge_agents_tenant_key UNIQUE (tenant_id,agent_key);
ALTER TABLE nw_agents DROP CONSTRAINT ck_skillforge_agents_revision_positive;
ALTER TABLE nw_agents ADD CONSTRAINT ck_skillforge_agents_revision_nonnegative CHECK (revision >= 0);
ALTER TABLE nw_agents ALTER COLUMN revision SET DEFAULT 0;
ALTER TABLE nw_agents ADD COLUMN published_revision_id BIGINT;
ALTER TABLE nw_agents ADD COLUMN has_draft_changes BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE nw_agent_publications (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    published_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id,agent_id,revision),
    UNIQUE (tenant_id,agent_id,id),
    FOREIGN KEY (tenant_id,agent_id) REFERENCES nw_agents(tenant_id,id) ON DELETE CASCADE
);
ALTER TABLE nw_agents ADD CONSTRAINT fk_skillforge_agents_current_publication
    FOREIGN KEY (tenant_id,id,published_revision_id)
    REFERENCES nw_agent_publications(tenant_id,agent_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE nw_agent_publications ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_agent_publications FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON nw_agent_publications
    USING (tenant_id::text = current_setting('app.tenant_id',true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id',true));
COMMENT ON TABLE nw_agent_publications IS '稳定 Agent 身份的不可变发布配置与挂载历史';
COMMENT ON COLUMN nw_agents.revision IS '最近发布修订号，未发布为0';
COMMENT ON COLUMN nw_agents.published_revision_id IS '下一次独立执行采用的发布快照';

CREATE FUNCTION reject_agent_publication_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Agent 发布快照不可修改';
END;
$$;
CREATE TRIGGER agent_publication_immutable BEFORE UPDATE ON nw_agent_publications
    FOR EACH ROW EXECUTE FUNCTION reject_agent_publication_update();
