-- V86: 四类 Capability 的统一身份、不可变版本与 ToolDeployment 权威记录。
CREATE TABLE public.nw_capability_definitions (
    id               bigint NOT NULL,
    tenant_id        bigint,
    capability_type  varchar(32) NOT NULL,
    capability_key   varchar(128) NOT NULL,
    name             varchar(255) NOT NULL,
    description      text NOT NULL DEFAULT '',
    trust_level      varchar(32) NOT NULL,
    status           varchar(32) NOT NULL,
    revision         bigint NOT NULL DEFAULT 1,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT skillforge_capability_definitions_pkey PRIMARY KEY (id),
    CONSTRAINT ck_capability_definition_type CHECK
        (capability_type IN ('SKILL', 'TOOL', 'WORKFLOW', 'KNOWLEDGE_SOURCE')),
    CONSTRAINT ck_capability_definition_trust CHECK
        ((trust_level = 'PLATFORM' AND tenant_id IS NULL)
            OR (trust_level = 'TENANT' AND tenant_id IS NOT NULL)),
    CONSTRAINT ck_capability_definition_status CHECK
        (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED', 'ARCHIVED')),
    CONSTRAINT ck_capability_definition_revision CHECK (revision > 0),
    CONSTRAINT ck_capability_definition_key CHECK (btrim(capability_key) <> ''),
    CONSTRAINT ck_capability_definition_name CHECK (btrim(name) <> '')
);

CREATE UNIQUE INDEX uq_capability_definition_platform_key
    ON public.nw_capability_definitions (capability_type, capability_key)
    WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX uq_capability_definition_tenant_key
    ON public.nw_capability_definitions (tenant_id, capability_type, capability_key)
    WHERE tenant_id IS NOT NULL;

CREATE TABLE public.nw_capability_versions (
    id               bigint NOT NULL,
    definition_id    bigint NOT NULL,
    tenant_id        bigint,
    capability_type  varchar(32) NOT NULL,
    version_key      varchar(128) NOT NULL,
    name             varchar(255) NOT NULL,
    description      text NOT NULL DEFAULT '',
    trust_level      varchar(32) NOT NULL,
    descriptor       jsonb NOT NULL,
    content_hash     varchar(128) NOT NULL,
    status           varchar(32) NOT NULL,
    created_at       timestamptz NOT NULL,
    published_at     timestamptz,
    CONSTRAINT skillforge_capability_versions_pkey PRIMARY KEY (id),
    CONSTRAINT skillforge_capability_versions_definition_fkey FOREIGN KEY (definition_id)
        REFERENCES public.nw_capability_definitions(id) ON DELETE RESTRICT,
    CONSTRAINT uq_capability_version_key UNIQUE (definition_id, version_key),
    CONSTRAINT uq_capability_version_hash UNIQUE (definition_id, content_hash),
    CONSTRAINT ck_capability_version_type CHECK
        (capability_type IN ('SKILL', 'TOOL', 'WORKFLOW', 'KNOWLEDGE_SOURCE')),
    CONSTRAINT ck_capability_version_trust CHECK
        ((trust_level = 'PLATFORM' AND tenant_id IS NULL)
            OR (trust_level = 'TENANT' AND tenant_id IS NOT NULL)),
    CONSTRAINT ck_capability_version_status CHECK
        (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED', 'ARCHIVED')),
    CONSTRAINT ck_capability_version_descriptor CHECK (jsonb_typeof(descriptor) = 'object'),
    CONSTRAINT ck_capability_version_key CHECK (btrim(version_key) <> ''),
    CONSTRAINT ck_capability_version_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_capability_version_hash CHECK (btrim(content_hash) <> ''),
    CONSTRAINT ck_capability_version_published_at CHECK
        ((status = 'PUBLISHED' AND published_at IS NOT NULL) OR status <> 'PUBLISHED')
);

CREATE INDEX idx_capability_version_catalog
    ON public.nw_capability_versions (tenant_id, capability_type, status, definition_id);

CREATE TABLE public.nw_tool_deployments (
    id                  bigint NOT NULL,
    tenant_id           bigint NOT NULL,
    deployment_key      varchar(128) NOT NULL,
    tool_version_id     bigint NOT NULL,
    provider_type       varchar(32) NOT NULL,
    provider_ref        varchar(255) NOT NULL,
    connector_instance_ref varchar(255) GENERATED ALWAYS AS
        (CASE WHEN provider_type IN ('MCP', 'CONNECTOR') THEN provider_ref ELSE NULL END) STORED,
    environment         varchar(32) NOT NULL,
    status              varchar(32) NOT NULL,
    revision            bigint NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT skillforge_tool_deployments_pkey PRIMARY KEY (id),
    CONSTRAINT skillforge_tool_deployments_version_fkey FOREIGN KEY (tool_version_id)
        REFERENCES public.nw_capability_versions(id) ON DELETE RESTRICT,
    CONSTRAINT skillforge_tool_deployments_connector_fkey FOREIGN KEY (tenant_id, connector_instance_ref)
        REFERENCES public.nw_connector_instances(tenant_id, instance_id) ON DELETE RESTRICT,
    CONSTRAINT uq_tool_deployment_key UNIQUE (tenant_id, deployment_key),
    CONSTRAINT ck_tool_deployment_provider CHECK
        (provider_type IN ('PLATFORM', 'CONNECTOR', 'MCP', 'TEST_MOCK')),
    CONSTRAINT ck_tool_deployment_environment CHECK
        (environment IN ('DEVELOPMENT', 'TEST', 'PRODUCTION')),
    CONSTRAINT ck_tool_deployment_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_tool_deployment_revision CHECK (revision > 0),
    CONSTRAINT ck_tool_deployment_no_prod_mock CHECK
        (NOT (environment = 'PRODUCTION' AND provider_type = 'TEST_MOCK')),
    CONSTRAINT ck_tool_deployment_identity CHECK
        (btrim(deployment_key) <> '' AND btrim(provider_ref) <> '')
);

CREATE INDEX idx_tool_deployment_version
    ON public.nw_tool_deployments (tenant_id, tool_version_id, status);

CREATE INDEX idx_tool_deployment_connector
    ON public.nw_tool_deployments (tenant_id, connector_instance_ref);

COMMENT ON COLUMN public.nw_tool_deployments.provider_ref IS
    'MCP/CONNECTOR 引用本租户 connector_instances.instance_id；PLATFORM/TEST_MOCK 保留各自 Provider 引用。';
COMMENT ON COLUMN public.nw_tool_deployments.connector_instance_ref IS
    '由 MCP/CONNECTOR provider_ref 生成的连接器实例业务标识；其他 Provider 为空，不拥有连接器引用。';
COMMENT ON CONSTRAINT skillforge_tool_deployments_connector_fkey ON public.nw_tool_deployments IS
    'MCP/CONNECTOR 部署必须引用同租户连接器；任何状态的部署仍引用时禁止删除连接器。';
COMMENT ON INDEX public.idx_tool_deployment_connector IS
    '按租户与连接器实例标识定位 ToolDeployment 引用，供连接器删除约束检查。';

CREATE FUNCTION public.validate_capability_version_identity() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    owner_tenant bigint;
    owner_type varchar(32);
    owner_trust varchar(32);
BEGIN
    SELECT tenant_id, capability_type, trust_level INTO owner_tenant, owner_type, owner_trust
      FROM public.nw_capability_definitions WHERE id = NEW.definition_id;
    IF NOT FOUND
        OR owner_tenant IS DISTINCT FROM NEW.tenant_id
        OR owner_type IS DISTINCT FROM NEW.capability_type
        OR owner_trust IS DISTINCT FROM NEW.trust_level THEN
        RAISE EXCEPTION 'capability version identity does not match definition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER capability_version_identity
    BEFORE INSERT OR UPDATE ON public.nw_capability_versions
    FOR EACH ROW EXECUTE FUNCTION public.validate_capability_version_identity();

CREATE FUNCTION public.validate_tool_deployment_version() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    version_tenant bigint;
    version_type varchar(32);
    version_status varchar(32);
BEGIN
    SELECT tenant_id, capability_type, status
      INTO version_tenant, version_type, version_status
      FROM public.nw_capability_versions WHERE id = NEW.tool_version_id;
    IF NOT FOUND
        OR version_type <> 'TOOL'
        OR version_status <> 'PUBLISHED'
        OR (version_tenant IS NOT NULL AND version_tenant IS DISTINCT FROM NEW.tenant_id) THEN
        RAISE EXCEPTION 'tool deployment requires an accessible published TOOL version';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tool_deployment_version
    BEFORE INSERT OR UPDATE ON public.nw_tool_deployments
    FOR EACH ROW EXECUTE FUNCTION public.validate_tool_deployment_version();

-- 发布制品的身份、正文和哈希不可原地修改；生命周期只允许更新 status/published_at。
CREATE FUNCTION public.guard_published_capability_version() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' AND (
        NEW.definition_id IS DISTINCT FROM OLD.definition_id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.capability_type IS DISTINCT FROM OLD.capability_type
        OR NEW.version_key IS DISTINCT FROM OLD.version_key
        OR NEW.name IS DISTINCT FROM OLD.name
        OR NEW.description IS DISTINCT FROM OLD.description
        OR NEW.trust_level IS DISTINCT FROM OLD.trust_level
        OR NEW.descriptor IS DISTINCT FROM OLD.descriptor
        OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
    ) THEN
        RAISE EXCEPTION 'published capability version content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER capability_version_immutable
    BEFORE UPDATE ON public.nw_capability_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_published_capability_version();

CREATE FUNCTION public.guard_capability_version_delete() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('PUBLISHED', 'DEPRECATED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'released capability version cannot be deleted';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER capability_version_delete_guard
    BEFORE DELETE ON public.nw_capability_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_capability_version_delete();

ALTER TABLE public.nw_capability_definitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_capability_definitions FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_capability_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_capability_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_tool_deployments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_tool_deployments FORCE ROW LEVEL SECURITY;

CREATE POLICY capability_definition_read ON public.nw_capability_definitions
    FOR SELECT USING (
        tenant_id IS NULL
        OR tenant_id::text = COALESCE(current_setting('app.tenant_id', true), '')
    );
CREATE POLICY capability_definition_tenant_write ON public.nw_capability_definitions
    FOR ALL USING (
        tenant_id::text = COALESCE(current_setting('app.tenant_id', true), '')
    ) WITH CHECK (
        tenant_id::text = COALESCE(current_setting('app.tenant_id', true), '')
    );
CREATE POLICY capability_definition_platform_write ON public.nw_capability_definitions
    FOR ALL USING (
        tenant_id IS NULL AND current_setting('app.platform_admin', true) = 'true'
    ) WITH CHECK (
        tenant_id IS NULL AND current_setting('app.platform_admin', true) = 'true'
    );
CREATE POLICY capability_version_read ON public.nw_capability_versions
    FOR SELECT USING (
        tenant_id IS NULL
        OR tenant_id::text = COALESCE(current_setting('app.tenant_id', true), '')
    );
CREATE POLICY capability_version_tenant_write ON public.nw_capability_versions
    FOR ALL USING (
        tenant_id::text = COALESCE(current_setting('app.tenant_id', true), '')
    ) WITH CHECK (
        tenant_id::text = COALESCE(current_setting('app.tenant_id', true), '')
    );
CREATE POLICY capability_version_platform_write ON public.nw_capability_versions
    FOR ALL USING (
        tenant_id IS NULL AND current_setting('app.platform_admin', true) = 'true'
    ) WITH CHECK (
        tenant_id IS NULL AND current_setting('app.platform_admin', true) = 'true'
    );
CREATE POLICY tool_deployment_tenant ON public.nw_tool_deployments
    USING (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id::text = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.nw_capability_definitions IS
    'SKILL/TOOL/WORKFLOW/KNOWLEDGE_SOURCE 的统一稳定身份；不保存类型专属运行字段。';
COMMENT ON TABLE public.nw_capability_versions IS
    '四类 Capability 的不可变类型化 Descriptor 版本；运行时只引用已发布版本。';
COMMENT ON TABLE public.nw_tool_deployments IS
    'ToolVersion 到 Platform/Connector/MCP Provider 的租户部署；凭证不得写入本表。';
