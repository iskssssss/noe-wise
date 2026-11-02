-- 平台系统 Agent 的工具部署与不可变版本审计；不复用租户 tool_deployments。
CREATE TABLE public.nw_platform_agent_tool_deployment_versions (
    id              bigint NOT NULL,
    revision        bigint NOT NULL,
    name            varchar(128) NOT NULL,
    tool_version_id bigint NOT NULL REFERENCES public.nw_capability_versions(id) ON DELETE RESTRICT,
    provider_type   varchar(32) NOT NULL,
    provider_ref    varchar(255) NOT NULL,
    environment     varchar(32) NOT NULL,
    active          boolean NOT NULL,
    actor           varchar(128) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_platform_agent_tool_deployment_versions_pkey PRIMARY KEY (id, revision),
    CONSTRAINT skillforge_platform_agent_tool_deployment_versions_r_ad8170eaff CHECK (revision > 0),
    CONSTRAINT skillforge_platform_agent_tool_deployment_versions_name_check CHECK (btrim(name) <> ''),
    CONSTRAINT skillforge_platform_agent_tool_deployment_versions_p_e75cfcdecc
        CHECK (provider_type IN ('PLATFORM', 'MCP', 'CONNECTOR')),
    CONSTRAINT skillforge_platform_agent_tool_deployment_versions_ref_check CHECK (btrim(provider_ref) <> ''),
    CONSTRAINT skillforge_platform_agent_tool_deployment_versions_e_5aa5c17cb8
        CHECK (environment IN ('TEST', 'PRODUCTION')),
    CONSTRAINT skillforge_platform_agent_tool_deployment_versions_actor_check CHECK (btrim(actor) <> '')
);

CREATE TABLE public.nw_platform_agent_tool_deployments (
    id               bigint NOT NULL,
    current_revision bigint NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_platform_agent_tool_deployments_pkey PRIMARY KEY (id),
    CONSTRAINT skillforge_platform_agent_tool_deployments_revision_check CHECK (current_revision > 0),
    CONSTRAINT skillforge_platform_agent_tool_deployments_current_version_fkey
        FOREIGN KEY (id, current_revision)
        REFERENCES public.nw_platform_agent_tool_deployment_versions(id, revision)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE UNIQUE INDEX uq_platform_agent_tool_deployment_current_name
    ON public.nw_platform_agent_tool_deployment_versions(name)
    WHERE revision = 1;
CREATE INDEX idx_skillforge_platform_agent_tool_deployment_versions_tool
    ON public.nw_platform_agent_tool_deployment_versions(tool_version_id, id, revision DESC);

CREATE TABLE public.nw_platform_agent_tool_deployment_audits (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deployment_id bigint NOT NULL,
    revision      bigint NOT NULL,
    operation     varchar(16) NOT NULL,
    actor         varchar(128) NOT NULL,
    payload       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_platform_agent_tool_deployment_audits_ope_301159f585
        CHECK (operation IN ('CREATE', 'UPDATE')),
    CONSTRAINT skillforge_platform_agent_tool_deployment_audits_actor_check CHECK (btrim(actor) <> ''),
    CONSTRAINT skillforge_platform_agent_tool_deployment_audits_payload_check CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT skillforge_platform_agent_tool_deployment_audits_version_fkey
        FOREIGN KEY (deployment_id, revision)
        REFERENCES public.nw_platform_agent_tool_deployment_versions(id, revision) ON DELETE RESTRICT
);

CREATE INDEX idx_skillforge_platform_agent_tool_deployment_audits_1649ebf59f
    ON public.nw_platform_agent_tool_deployment_audits(deployment_id, created_at DESC);

-- 只允许平台公共、已发布的 TOOL 版本进入平台工具部署版本。
CREATE FUNCTION public.validate_platform_agent_tool_deployment_version() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    version_tenant bigint;
    version_type varchar(32);
    version_trust varchar(32);
    version_status varchar(32);
    definition_tenant bigint;
    definition_type varchar(32);
    definition_trust varchar(32);
    definition_status varchar(32);
BEGIN
    SELECT v.tenant_id, v.capability_type, v.trust_level, v.status,
           d.tenant_id, d.capability_type, d.trust_level, d.status
      INTO version_tenant, version_type, version_trust, version_status,
           definition_tenant, definition_type, definition_trust, definition_status
      FROM public.nw_capability_versions v
      INNER JOIN public.nw_capability_definitions d ON d.id = v.definition_id
     WHERE v.id = NEW.tool_version_id;
    IF NOT FOUND
        OR version_tenant IS NOT NULL OR definition_tenant IS NOT NULL
        OR version_type <> 'TOOL' OR definition_type <> 'TOOL'
        OR version_trust <> 'PLATFORM' OR definition_trust <> 'PLATFORM'
        OR version_status <> 'PUBLISHED' OR definition_status <> 'PUBLISHED' THEN
        RAISE EXCEPTION 'platform agent tool deployment requires a published platform TOOL version';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER platform_agent_tool_deployment_version_guard
    BEFORE INSERT ON public.nw_platform_agent_tool_deployment_versions
    FOR EACH ROW EXECUTE FUNCTION public.validate_platform_agent_tool_deployment_version();

CREATE FUNCTION public.reject_platform_agent_tool_deployment_history_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER skillforge_platform_agent_tool_deployment_versions_immutable
    BEFORE UPDATE OR DELETE ON public.nw_platform_agent_tool_deployment_versions
    FOR EACH ROW EXECUTE FUNCTION public.reject_platform_agent_tool_deployment_history_mutation();
CREATE TRIGGER skillforge_platform_agent_tool_deployment_audits_immutable
    BEFORE UPDATE OR DELETE ON public.nw_platform_agent_tool_deployment_audits
    FOR EACH ROW EXECUTE FUNCTION public.reject_platform_agent_tool_deployment_history_mutation();

ALTER TABLE public.nw_platform_agent_tool_deployment_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_platform_agent_tool_deployment_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_platform_agent_tool_deployments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_platform_agent_tool_deployments FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_platform_agent_tool_deployment_audits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_platform_agent_tool_deployment_audits FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_platform_agent_tool_deployment_versions_admin
    ON public.nw_platform_agent_tool_deployment_versions
    FOR ALL USING (current_setting('app.platform_admin', true) = 'true')
    WITH CHECK (current_setting('app.platform_admin', true) = 'true');
CREATE POLICY skillforge_platform_agent_tool_deployments_admin
    ON public.nw_platform_agent_tool_deployments
    FOR ALL USING (current_setting('app.platform_admin', true) = 'true')
    WITH CHECK (current_setting('app.platform_admin', true) = 'true');
CREATE POLICY skillforge_platform_agent_tool_deployment_versions_runtime_read
    ON public.nw_platform_agent_tool_deployment_versions
    FOR SELECT USING (current_setting('app.system_agent_runtime', true) = 'true');
CREATE POLICY skillforge_platform_agent_tool_deployments_runtime_read
    ON public.nw_platform_agent_tool_deployments
    FOR SELECT USING (current_setting('app.system_agent_runtime', true) = 'true');
CREATE POLICY skillforge_platform_agent_tool_deployment_audits_admin
    ON public.nw_platform_agent_tool_deployment_audits
    FOR ALL USING (current_setting('app.platform_admin', true) = 'true')
    WITH CHECK (current_setting('app.platform_admin', true) = 'true');

COMMENT ON TABLE public.nw_platform_agent_tool_deployments IS
    '平台系统 Agent 工具部署当前版本指针；无 tenant_id，不能由请求体伪造租户范围。';
COMMENT ON TABLE public.nw_platform_agent_tool_deployment_versions IS
    '平台系统 Agent 工具部署不可变版本；运行冻结 revision 后按该表恢复 Provider 绑定。';
COMMENT ON TABLE public.nw_platform_agent_tool_deployment_audits IS
    '平台系统 Agent 工具部署创建与更新的持久审计账本。';
