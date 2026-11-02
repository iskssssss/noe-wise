-- 收口模板完整定义、实例运行预算与安装幂等账本；不迁移历史 JSON 结构。

ALTER TABLE public.nw_agent_templates
    DROP COLUMN config_schema,
    DROP COLUMN required_permissions;

ALTER TABLE public.nw_agents
    DROP COLUMN planning_rules,
    ADD COLUMN icon_url varchar(512),
    ADD COLUMN max_iterations integer,
    ADD COLUMN max_tool_calls integer,
    ADD COLUMN max_knowledge_calls integer,
    ADD COLUMN max_total_tokens bigint,
    ADD COLUMN max_wall_time_seconds bigint;

CREATE UNIQUE INDEX skillforge_agent_templates_one_published_per_family
    ON public.nw_agent_templates (template_key)
    WHERE status = 'published';

CREATE TABLE public.nw_agent_template_installations (
    tenant_id bigint NOT NULL CONSTRAINT skillforge_agent_template_installations_tenant_fk REFERENCES public.nw_tenants(id),
    request_id varchar(128) NOT NULL,
    fingerprint char(64) NOT NULL,
    template_id bigint NOT NULL CONSTRAINT skillforge_agent_template_installations_template_fk
        REFERENCES public.nw_agent_templates(id) ON DELETE RESTRICT,
    agent_id bigint CONSTRAINT skillforge_agent_template_installations_agent_fk
        REFERENCES public.nw_agents(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_agent_template_installations_pkey PRIMARY KEY (tenant_id, request_id)
);

CREATE INDEX skillforge_agent_template_installations_agent_id_idx
    ON public.nw_agent_template_installations (agent_id);
CREATE INDEX skillforge_agent_template_installations_template_id_idx
    ON public.nw_agent_template_installations (template_id);

ALTER TABLE public.nw_agent_template_installations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_agent_template_installations FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_agent_template_installations_tenant_isolation ON public.nw_agent_template_installations
    USING (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::bigint)
    WITH CHECK (tenant_id = (NULLIF(current_setting('app.tenant_id', true), ''))::bigint);

COMMENT ON INDEX public.skillforge_agent_templates_one_published_per_family IS '同一模板族最多一个已发布版本';
COMMENT ON TABLE public.nw_agent_template_installations IS '模板安装请求的租户内幂等账本；删除 Agent 后仍保留处理回执';
COMMENT ON COLUMN public.nw_agents.icon_url IS 'Agent 实例图标 URL；为空时由调用方使用默认展示';
COMMENT ON COLUMN public.nw_agents.max_iterations IS '业务模型调用次数覆盖；为空采用任务场景与角色派生额度；受真实任务根约束';
COMMENT ON COLUMN public.nw_agents.max_tool_calls IS '运行最大工具调用次数覆盖；为空采用任务场景与角色派生额度；受真实任务根约束';
COMMENT ON COLUMN public.nw_agents.max_knowledge_calls IS '运行最大知识调用次数覆盖；为空采用任务场景与角色派生额度；受真实任务根约束';
COMMENT ON COLUMN public.nw_agents.max_total_tokens IS '运行累计总 Token 上限覆盖；为空采用任务场景与角色派生额度；受真实任务根约束';
COMMENT ON COLUMN public.nw_agents.max_wall_time_seconds IS '活跃执行时长秒数覆盖；为空采用任务场景与角色派生额度；受真实任务根约束';
COMMENT ON COLUMN public.nw_agent_template_installations.tenant_id IS '所属租户；RLS 使用事务 app.tenant_id 隔离';
COMMENT ON COLUMN public.nw_agent_template_installations.request_id IS '租户内客户端安装请求幂等键';
COMMENT ON COLUMN public.nw_agent_template_installations.fingerprint IS '安装请求内容 SHA-256 指纹，用于检测同键请求漂移';
COMMENT ON COLUMN public.nw_agent_template_installations.template_id IS '被安装的模板版本；禁止删除以保留回执来源';
COMMENT ON COLUMN public.nw_agent_template_installations.agent_id IS '成功创建的 Agent；删除 Agent 时置空但保留幂等回执';
COMMENT ON COLUMN public.nw_agent_template_installations.created_at IS '首次受理安装请求的时间';
COMMENT ON CONSTRAINT skillforge_agent_template_installations_pkey ON public.nw_agent_template_installations IS '每租户 request_id 唯一，保证安装幂等';
COMMENT ON CONSTRAINT skillforge_agent_template_installations_tenant_fk ON public.nw_agent_template_installations IS '安装账本必须属于已存在的租户';
COMMENT ON CONSTRAINT skillforge_agent_template_installations_template_fk ON public.nw_agent_template_installations IS '安装回执必须引用存在的模板版本，禁止删除模板';
COMMENT ON CONSTRAINT skillforge_agent_template_installations_agent_fk ON public.nw_agent_template_installations IS '删除已安装 Agent 时保留幂等回执并清空结果引用';
