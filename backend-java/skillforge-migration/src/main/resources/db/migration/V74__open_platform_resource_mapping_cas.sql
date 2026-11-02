-- V74: Open Platform externalRef 资源映射与 revision/ETag CAS。
CREATE TABLE public.nw_integration_resource_mappings (
    id               bigint NOT NULL,
    tenant_id        bigint NOT NULL,
    integration_id   varchar(128) NOT NULL,
    resource_type    varchar(64) NOT NULL,
    external_ref     varchar(256) NOT NULL,
    resource_id      varchar(128) NOT NULL,
    revision         bigint NOT NULL,
    content_hash     varchar(256) NOT NULL,
    etag             varchar(128) NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT skillforge_integration_resource_mappings_pkey PRIMARY KEY (id),
    CONSTRAINT uq_integration_resource_mapping UNIQUE
        (tenant_id, integration_id, resource_type, external_ref),
    CONSTRAINT ck_integration_resource_mapping_revision CHECK (revision > 0)
);

CREATE INDEX idx_integration_resource_mapping_tenant_resource
    ON public.nw_integration_resource_mappings (tenant_id, resource_type, resource_id);

ALTER TABLE public.nw_integration_resource_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_integration_resource_mappings FORCE ROW LEVEL SECURITY;
CREATE POLICY integration_resource_mapping_tenant_insert ON public.nw_integration_resource_mappings
    FOR INSERT WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));
CREATE POLICY integration_resource_mapping_tenant_select ON public.nw_integration_resource_mappings
    FOR SELECT USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));
CREATE POLICY integration_resource_mapping_tenant_update ON public.nw_integration_resource_mappings
    FOR UPDATE USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)))
    WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));

COMMENT ON TABLE public.nw_integration_resource_mappings IS
    'Tenant Open Platform externalRef 映射；更新必须使用当前 revision+ETag CAS，不保存资源正文';
