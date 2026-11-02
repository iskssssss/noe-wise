-- Agent 的行 id 是精确版本身份，业务 key 在租户内归组。
ALTER TABLE nw_agents ADD COLUMN revision integer NOT NULL DEFAULT 1;
ALTER TABLE nw_agents DROP CONSTRAINT skillforge_agents_agent_key_key;
ALTER TABLE nw_agents ADD CONSTRAINT uq_skillforge_agents_tenant_key_revision UNIQUE (tenant_id, agent_key, revision);
ALTER TABLE nw_agents ADD CONSTRAINT ck_skillforge_agents_revision_positive CHECK (revision > 0);
COMMENT ON COLUMN nw_agents.revision IS '同一租户业务 key 下的版本号，新修订按现存最大值递增，渠道和会话引用精确 id';
COMMENT ON CONSTRAINT uq_skillforge_agents_tenant_key_revision ON nw_agents IS '一个租户的同一 Agent 版本只允许一行';
COMMENT ON CONSTRAINT ck_skillforge_agents_revision_positive ON nw_agents IS 'Agent 版本从 1 开始';
