-- V90: CapabilityMount 持久化基础。
-- V87-V89 已用于 Capability Catalog 生命周期与 Producer 关联；本迁移只建立
-- Agent 装配代与四类类型化 mount，不接入管理 API、Agent Runtime 或旧 binding。

-- Composite foreign keys preserve tenant ownership for resources whose global
-- Snowflake primary key alone cannot express the owning tenant.
ALTER TABLE public.nw_agents
    ADD CONSTRAINT uq_skillforge_agents_tenant_id_capability_mounts UNIQUE (tenant_id, id);
ALTER TABLE public.nw_tool_deployments
    ADD CONSTRAINT uq_skillforge_tool_deployments_tenant_id_capability_mounts UNIQUE (tenant_id, id);
ALTER TABLE public.nw_workflow_instances
    ADD CONSTRAINT uq_skillforge_workflow_instances_tenant_id_capability_mounts UNIQUE (tenant_id, id);
ALTER TABLE public.nw_knowledge_base_revisions
    ADD CONSTRAINT uq_knowledge_revisions_tenant_id_capability_mounts UNIQUE (tenant_id, id);

CREATE TABLE public.nw_agent_capability_mount_sets (
    tenant_id  bigint NOT NULL,
    agent_id   bigint NOT NULL,
    generation bigint NOT NULL DEFAULT 1,
    CONSTRAINT skillforge_agent_capability_mount_sets_pkey PRIMARY KEY (tenant_id, agent_id),
    CONSTRAINT skillforge_agent_capability_mount_sets_tenant_fkey
        FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id) ON DELETE CASCADE,
    CONSTRAINT skillforge_agent_capability_mount_sets_agent_fkey
        FOREIGN KEY (tenant_id, agent_id)
        REFERENCES public.nw_agents (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_skillforge_agent_capability_mount_sets_generation CHECK (generation > 0)
);

CREATE TABLE public.nw_agent_capability_mounts (
    id                         bigint NOT NULL,
    tenant_id                  bigint NOT NULL,
    agent_id                   bigint NOT NULL,
    capability_type            varchar(32) NOT NULL,
    capability_definition_id   bigint NOT NULL,
    capability_version_id     bigint NOT NULL,
    priority                   integer NOT NULL DEFAULT 0,
    status                     varchar(16) NOT NULL DEFAULT 'ACTIVE',
    revision                   bigint NOT NULL DEFAULT 1,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    updated_at                 timestamptz NOT NULL DEFAULT now(),
    activation_mode           varchar(16),
    token_budget              bigint,
    tool_deployment_id        bigint,
    tool_deployment_revision  bigint,
    workflow_instance_id      bigint,
    workflow_instance_revision bigint,
    knowledge_binding_mode    varchar(32),
    knowledge_pinned_revision_id bigint,
    CONSTRAINT skillforge_agent_capability_mounts_pkey PRIMARY KEY (id),
    CONSTRAINT skillforge_agent_capability_mounts_set_fkey
        FOREIGN KEY (tenant_id, agent_id)
        REFERENCES public.nw_agent_capability_mount_sets (tenant_id, agent_id)
        ON DELETE CASCADE,
    CONSTRAINT skillforge_agent_capability_mounts_tenant_fkey
        FOREIGN KEY (tenant_id) REFERENCES public.nw_tenants(id) ON DELETE CASCADE,
    CONSTRAINT skillforge_agent_capability_mounts_definition_fkey
        FOREIGN KEY (capability_definition_id)
        REFERENCES public.nw_capability_definitions(id) ON DELETE RESTRICT,
    CONSTRAINT skillforge_agent_capability_mounts_version_fkey
        FOREIGN KEY (capability_version_id)
        REFERENCES public.nw_capability_versions(id) ON DELETE RESTRICT,
    CONSTRAINT skillforge_agent_capability_mounts_tool_fkey
        FOREIGN KEY (tool_deployment_id)
        REFERENCES public.nw_tool_deployments(id) ON DELETE RESTRICT,
    CONSTRAINT skillforge_agent_capability_mounts_tool_tenant_fkey
        FOREIGN KEY (tenant_id, tool_deployment_id)
        REFERENCES public.nw_tool_deployments (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT skillforge_agent_capability_mounts_workflow_fkey
        FOREIGN KEY (workflow_instance_id)
        REFERENCES public.nw_workflow_instances(id) ON DELETE RESTRICT,
    CONSTRAINT skillforge_agent_capability_mounts_workflow_tenant_fkey
        FOREIGN KEY (tenant_id, workflow_instance_id)
        REFERENCES public.nw_workflow_instances (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT skillforge_agent_capability_mounts_knowledge_revision_fkey
        FOREIGN KEY (knowledge_pinned_revision_id)
        REFERENCES public.nw_knowledge_base_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT skillforge_agent_capability_mounts_knowledge_tenant_fkey
        FOREIGN KEY (tenant_id, knowledge_pinned_revision_id)
        REFERENCES public.nw_knowledge_base_revisions (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_agent_capability_mount_definition
        UNIQUE (tenant_id, agent_id, capability_definition_id),
    CONSTRAINT ck_skillforge_agent_capability_mounts_id CHECK (id > 0),
    CONSTRAINT ck_skillforge_agent_capability_mounts_type CHECK
        (capability_type IN ('SKILL', 'TOOL', 'WORKFLOW', 'KNOWLEDGE_SOURCE')),
    CONSTRAINT ck_skillforge_agent_capability_mounts_status CHECK
        (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_skillforge_agent_capability_mounts_revision CHECK (revision > 0),
    CONSTRAINT ck_skillforge_agent_capability_mounts_skill_activation CHECK
        (activation_mode IS NULL OR activation_mode IN ('ALWAYS', 'ON_DEMAND')),
    CONSTRAINT ck_skillforge_agent_capability_mounts_skill_budget CHECK
        (token_budget IS NULL OR token_budget > 0),
    CONSTRAINT ck_skillforge_agent_capability_mounts_tool_revision CHECK
        (tool_deployment_revision IS NULL OR tool_deployment_revision > 0),
    CONSTRAINT ck_skillforge_agent_capability_mounts_workflow_revision CHECK
        (workflow_instance_revision IS NULL OR workflow_instance_revision > 0),
    CONSTRAINT ck_skillforge_agent_capability_mounts_knowledge_mode CHECK
        (knowledge_binding_mode IS NULL
            OR knowledge_binding_mode IN ('FOLLOW_ACTIVE', 'PINNED_REVISION')),
    CONSTRAINT ck_skillforge_agent_capability_mounts_knowledge_revision CHECK
        (knowledge_pinned_revision_id IS NULL OR knowledge_pinned_revision_id > 0),
    CONSTRAINT ck_skillforge_agent_capability_mounts_typed_config CHECK (
        (
            activation_mode IS NOT NULL
            AND token_budget IS NOT NULL
            AND tool_deployment_id IS NULL
            AND tool_deployment_revision IS NULL
            AND workflow_instance_id IS NULL
            AND workflow_instance_revision IS NULL
            AND knowledge_binding_mode IS NULL
            AND knowledge_pinned_revision_id IS NULL
        ) OR (
            activation_mode IS NULL
            AND token_budget IS NULL
            AND tool_deployment_id IS NOT NULL
            AND tool_deployment_revision IS NOT NULL
            AND workflow_instance_id IS NULL
            AND workflow_instance_revision IS NULL
            AND knowledge_binding_mode IS NULL
            AND knowledge_pinned_revision_id IS NULL
        ) OR (
            activation_mode IS NULL
            AND token_budget IS NULL
            AND tool_deployment_id IS NULL
            AND tool_deployment_revision IS NULL
            AND workflow_instance_id IS NOT NULL
            AND workflow_instance_revision IS NOT NULL
            AND knowledge_binding_mode IS NULL
            AND knowledge_pinned_revision_id IS NULL
        ) OR (
            activation_mode IS NULL
            AND token_budget IS NULL
            AND tool_deployment_id IS NULL
            AND tool_deployment_revision IS NULL
            AND workflow_instance_id IS NULL
            AND workflow_instance_revision IS NULL
            AND knowledge_binding_mode = 'FOLLOW_ACTIVE'
            AND knowledge_pinned_revision_id IS NULL
        ) OR (
            activation_mode IS NULL
            AND token_budget IS NULL
            AND tool_deployment_id IS NULL
            AND tool_deployment_revision IS NULL
            AND workflow_instance_id IS NULL
            AND workflow_instance_revision IS NULL
            AND knowledge_binding_mode = 'PINNED_REVISION'
            AND knowledge_pinned_revision_id IS NOT NULL
        )
    )
);

CREATE INDEX idx_skillforge_agent_capability_mount_sets_agent
    ON public.nw_agent_capability_mount_sets (tenant_id, agent_id);
CREATE INDEX idx_skillforge_agent_capability_mounts_agent_status
    ON public.nw_agent_capability_mounts (tenant_id, agent_id, status, priority DESC, id);
CREATE INDEX idx_skillforge_agent_capability_mounts_version
    ON public.nw_agent_capability_mounts (tenant_id, capability_definition_id, capability_version_id);

CREATE FUNCTION public.validate_agent_capability_mount_reference()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    definition_tenant bigint;
    definition_type varchar(32);
    definition_status varchar(32);
    version_tenant bigint;
    version_definition bigint;
    version_type varchar(32);
    version_status varchar(32);
    deployment_version bigint;
BEGIN
    SELECT tenant_id, capability_type, status
      INTO definition_tenant, definition_type, definition_status
      FROM public.nw_capability_definitions
     WHERE id = NEW.capability_definition_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'capability mount definition does not exist';
    END IF;

    SELECT tenant_id, definition_id, capability_type, status
      INTO version_tenant, version_definition, version_type, version_status
      FROM public.nw_capability_versions
     WHERE id = NEW.capability_version_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'capability mount version does not exist';
    END IF;

    IF definition_type <> NEW.capability_type
        OR version_type <> NEW.capability_type
        OR version_definition <> NEW.capability_definition_id
        OR definition_tenant IS DISTINCT FROM version_tenant THEN
        RAISE EXCEPTION 'capability mount definition/version/type identity mismatch';
    END IF;
    IF (definition_tenant IS NOT NULL AND definition_tenant <> NEW.tenant_id)
        OR (version_tenant IS NOT NULL AND version_tenant <> NEW.tenant_id) THEN
        RAISE EXCEPTION 'capability mount capability is not visible to tenant';
    END IF;
    IF version_status <> 'PUBLISHED' THEN
        RAISE EXCEPTION 'capability mount requires a published version';
    END IF;
    IF definition_status <> 'PUBLISHED' THEN
        RAISE EXCEPTION 'capability mount requires a published definition';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
            OR NEW.agent_id IS DISTINCT FROM OLD.agent_id
            OR NEW.capability_type IS DISTINCT FROM OLD.capability_type
            OR NEW.capability_definition_id IS DISTINCT FROM OLD.capability_definition_id
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'capability mount stable identity is immutable';
        END IF;
        IF NEW.revision <> OLD.revision + 1 THEN
            RAISE EXCEPTION 'capability mount revision must advance by one';
        END IF;
    END IF;

    IF NEW.capability_type = 'TOOL' THEN
        SELECT tool_version_id
          INTO deployment_version
          FROM public.nw_tool_deployments
         WHERE id = NEW.tool_deployment_id
           AND tenant_id = NEW.tenant_id;
        IF NOT FOUND OR deployment_version <> NEW.capability_version_id THEN
            RAISE EXCEPTION 'tool mount deployment does not match capability version';
        END IF;
    ELSIF NEW.capability_type = 'WORKFLOW'
        AND NEW.workflow_instance_id IS NULL THEN
        RAISE EXCEPTION 'workflow mount requires a workflow instance';
    ELSIF NEW.capability_type = 'KNOWLEDGE_SOURCE'
        AND NEW.knowledge_binding_mode IS NULL THEN
        RAISE EXCEPTION 'knowledge source mount requires a binding mode';
    ELSIF NEW.capability_type = 'SKILL'
        AND (NEW.activation_mode IS NULL OR NEW.token_budget IS NULL) THEN
        RAISE EXCEPTION 'skill mount requires activation mode and token budget';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_capability_mount_reference_guard
    BEFORE INSERT OR UPDATE ON public.nw_agent_capability_mounts
    FOR EACH ROW EXECUTE FUNCTION public.validate_agent_capability_mount_reference();

CREATE FUNCTION public.guard_agent_capability_mount_set_generation()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.generation <> 1 THEN
            RAISE EXCEPTION 'new capability mount set generation must be one';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.agent_id IS DISTINCT FROM OLD.agent_id THEN
        RAISE EXCEPTION 'capability mount set ownership is immutable';
    END IF;
    IF NEW.generation <> OLD.generation + 1 THEN
        RAISE EXCEPTION 'capability mount set generation must advance by one';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_capability_mount_set_generation_guard
    BEFORE INSERT OR UPDATE ON public.nw_agent_capability_mount_sets
    FOR EACH ROW EXECUTE FUNCTION public.guard_agent_capability_mount_set_generation();

ALTER TABLE public.nw_agent_capability_mount_sets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_agent_capability_mount_sets FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_agent_capability_mount_sets_tenant_isolation
    ON public.nw_agent_capability_mount_sets
    USING (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.nw_agent_capability_mounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_agent_capability_mounts FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_agent_capability_mounts_tenant_isolation
    ON public.nw_agent_capability_mounts
    USING (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.nw_agent_capability_mount_sets IS
    '每个租户 Agent 的当前 CapabilityMount 装配代；generation 由成功 mutation 单调推进';
COMMENT ON COLUMN public.nw_agent_capability_mount_sets.tenant_id IS
    '装配代所属租户；与 agent_id 组成所有权键';
COMMENT ON COLUMN public.nw_agent_capability_mount_sets.agent_id IS
    '装配代所属 Agent；Agent 删除时级联删除装配代及 mount';
COMMENT ON COLUMN public.nw_agent_capability_mount_sets.generation IS
    '当前装配代，正数；首次 ensure/read 或首次成功 create 为 1，后续成功 mutation 每次加 1';

COMMENT ON TABLE public.nw_agent_capability_mounts IS
    '四类 CapabilityMount 的类型化持久记录；不保存 JSON/Map config 或 input 快照';
COMMENT ON COLUMN public.nw_agent_capability_mounts.id IS
    'Snowflake mount identity，同时作为稳定 mountId/handle 的数字部分';
COMMENT ON COLUMN public.nw_agent_capability_mounts.tenant_id IS
    'mount 所属租户；所有读写与 Agent 装配代按租户隔离';
COMMENT ON COLUMN public.nw_agent_capability_mounts.agent_id IS
    'mount 所属 Agent；由装配代复合外键保证 Agent/tenant 一致';
COMMENT ON COLUMN public.nw_agent_capability_mounts.capability_type IS
    '封闭 Capability 类型冗余列；由数据库触发器与 definition/version 类型一致性校验';
COMMENT ON COLUMN public.nw_agent_capability_mounts.capability_definition_id IS
    '挂载能力的稳定 definition 外键；同一 Agent 对同一 definition 只能有一个 mount';
COMMENT ON COLUMN public.nw_agent_capability_mounts.capability_version_id IS
    '挂载的不可变 published Capability version 外键';
COMMENT ON COLUMN public.nw_agent_capability_mounts.priority IS
    '同一 Agent 装配内的调度优先级；数值越大越优先';
COMMENT ON COLUMN public.nw_agent_capability_mounts.status IS
    'mount 状态：ACTIVE 或 DISABLED';
COMMENT ON COLUMN public.nw_agent_capability_mounts.revision IS
    'mount CAS 版本；正数，成功 Update 才递增';
COMMENT ON COLUMN public.nw_agent_capability_mounts.created_at IS
    'mount 创建时间';
COMMENT ON COLUMN public.nw_agent_capability_mounts.updated_at IS
    'mount 最近更新时间';
COMMENT ON COLUMN public.nw_agent_capability_mounts.activation_mode IS
    'SKILL 类型配置：ALWAYS 或 ON_DEMAND；其它类型必须为空';
COMMENT ON COLUMN public.nw_agent_capability_mounts.token_budget IS
    'SKILL 类型正数 token 预算；其它类型必须为空';
COMMENT ON COLUMN public.nw_agent_capability_mounts.tool_deployment_id IS
    'TOOL 类型 ToolDeployment Snowflake 外键；运行时 deploymentKey 由租户限定查询重建';
COMMENT ON COLUMN public.nw_agent_capability_mounts.tool_deployment_revision IS
    'TOOL 类型绑定时保存的正数 Deployment revision 快照';
COMMENT ON COLUMN public.nw_agent_capability_mounts.workflow_instance_id IS
    'WORKFLOW 类型 Workflow instance 外键';
COMMENT ON COLUMN public.nw_agent_capability_mounts.workflow_instance_revision IS
    'WORKFLOW 类型绑定时保存的正数实例 revision 输入快照，不是新的实例事实源';
COMMENT ON COLUMN public.nw_agent_capability_mounts.knowledge_binding_mode IS
    'KNOWLEDGE_SOURCE 类型绑定模式：FOLLOW_ACTIVE 或 PINNED_REVISION';
COMMENT ON COLUMN public.nw_agent_capability_mounts.knowledge_pinned_revision_id IS
    'PINNED_REVISION 模式的同租户 Knowledge revision 外键；FOLLOW_ACTIVE 必须为空';
