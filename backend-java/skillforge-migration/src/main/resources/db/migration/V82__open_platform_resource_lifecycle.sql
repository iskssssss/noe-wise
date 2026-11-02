-- V82: Open Platform 资源正文 revision、Deployment 与 ChannelBinding 生命周期。
CREATE TABLE public.nw_open_platform_resource_revisions (
    id               bigint NOT NULL,
    tenant_id        bigint NOT NULL,
    resource_type    varchar(64) NOT NULL,
    resource_id      varchar(128) NOT NULL,
    revision         bigint NOT NULL,
    status           varchar(32) NOT NULL,
    content          jsonb NOT NULL DEFAULT '{}'::jsonb,
    content_hash     varchar(128) NOT NULL,
    etag             varchar(128) NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    published_at     timestamptz,
    CONSTRAINT skillforge_open_platform_resource_revisions_pkey PRIMARY KEY (id),
    CONSTRAINT uq_open_platform_resource_revision UNIQUE
        (tenant_id, resource_type, resource_id, revision),
    CONSTRAINT ck_open_platform_resource_revision_no_zero CHECK (revision > 0),
    CONSTRAINT ck_open_platform_resource_revision_status CHECK
        (status IN ('draft', 'validating', 'validation_failed', 'ready', 'published', 'archived')),
    CONSTRAINT ck_open_platform_resource_revision_json CHECK (jsonb_typeof(content) = 'object')
);

CREATE INDEX idx_open_platform_resource_revision_latest
    ON public.nw_open_platform_resource_revisions
        (tenant_id, resource_type, resource_id, revision DESC);

CREATE TABLE public.nw_open_platform_deployments (
    id                  bigint NOT NULL,
    tenant_id           bigint NOT NULL,
    deployment_id       varchar(128) NOT NULL,
    resource_type       varchar(64) NOT NULL,
    resource_id         varchar(128) NOT NULL,
    resource_revision   bigint NOT NULL,
    environment         varchar(64) NOT NULL,
    status              varchar(32) NOT NULL,
    deployment_revision bigint NOT NULL,
    etag                varchar(128) NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT skillforge_open_platform_deployments_pkey PRIMARY KEY (id),
    CONSTRAINT uq_open_platform_deployment UNIQUE (tenant_id, deployment_id),
    CONSTRAINT ck_open_platform_deployment_revision CHECK
        (resource_revision > 0 AND deployment_revision > 0),
    CONSTRAINT ck_open_platform_deployment_status CHECK (status IN ('active', 'disabled'))
);

CREATE TABLE public.nw_open_platform_channel_bindings (
    id                  bigint NOT NULL,
    tenant_id           bigint NOT NULL,
    channel_id          bigint NOT NULL,
    deployment_id       varchar(128) NOT NULL,
    resource_id         varchar(128) NOT NULL,
    resource_revision   bigint NOT NULL,
    binding_revision    bigint NOT NULL,
    status              varchar(32) NOT NULL,
    etag                varchar(128) NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT skillforge_open_platform_channel_bindings_pkey PRIMARY KEY (id),
    CONSTRAINT uq_open_platform_channel_binding UNIQUE (tenant_id, channel_id),
    CONSTRAINT ck_open_platform_channel_binding_revision CHECK
        (resource_revision > 0 AND binding_revision > 0),
    CONSTRAINT ck_open_platform_channel_binding_status CHECK (status IN ('active', 'disabled'))
);

CREATE INDEX idx_skillforge_open_platform_deployments_resource
    ON public.nw_open_platform_deployments (tenant_id, resource_type, resource_id);
CREATE INDEX idx_skillforge_open_platform_channel_bindings_deployment
    ON public.nw_open_platform_channel_bindings (tenant_id, deployment_id);

ALTER TABLE public.nw_open_platform_resource_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_open_platform_resource_revisions FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_open_platform_deployments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_open_platform_deployments FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_open_platform_channel_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_open_platform_channel_bindings FORCE ROW LEVEL SECURITY;

CREATE POLICY open_platform_resource_revision_tenant ON public.nw_open_platform_resource_revisions
    USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)))
    WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));
CREATE POLICY open_platform_deployment_tenant ON public.nw_open_platform_deployments
    USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)))
    WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));
CREATE POLICY open_platform_channel_binding_tenant ON public.nw_open_platform_channel_bindings
    USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)))
    WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));

COMMENT ON TABLE public.nw_open_platform_resource_revisions IS
    'Open Platform 资源正文不可变 revision；已发布版本不得原地覆盖。';
COMMENT ON TABLE public.nw_open_platform_deployments IS
    'Open Platform Deployment 只引用已发布资源 revision，并使用 deployment revision/ETag CAS。';
COMMENT ON TABLE public.nw_open_platform_channel_bindings IS
    'ChannelBinding 只引用 active Agent Deployment，更新使用 binding revision/ETag CAS。';
