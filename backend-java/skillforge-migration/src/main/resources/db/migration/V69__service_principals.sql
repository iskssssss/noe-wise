-- V69: Tenant Open Platform 的第三方服务身份、scope 与签名凭证。

CREATE TABLE public.nw_service_principals (
    id                       bigint NOT NULL,
    tenant_id                bigint NOT NULL,
    principal_id             varchar(128) NOT NULL,
    integration_id           varchar(128) NOT NULL,
    api_key_hash             varchar(64) NOT NULL,
    api_key_prefix           varchar(16) NOT NULL,
    signing_secret_encrypted text NOT NULL,
    scopes                   jsonb NOT NULL DEFAULT '[]'::jsonb,
    status                   varchar(16) NOT NULL DEFAULT 'active',
    credential_version       bigint NOT NULL DEFAULT 1,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_service_principals_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_service_principals_principal_id UNIQUE (principal_id),
    CONSTRAINT uq_skillforge_service_principals_api_key_hash UNIQUE (api_key_hash),
    CONSTRAINT ck_skillforge_service_principals_status CHECK (status IN ('active', 'suspended', 'revoked')),
    CONSTRAINT ck_skillforge_service_principals_scopes_array CHECK (jsonb_typeof(scopes) = 'array')
);

CREATE INDEX idx_skillforge_service_principals_tenant_integration
    ON public.nw_service_principals (tenant_id, integration_id, status);

ALTER TABLE public.nw_service_principals ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_service_principals FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_service_principals_tenant_isolation ON public.nw_service_principals
    USING ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''));

CREATE OR REPLACE FUNCTION public.resolve_service_principal_by_api_key_hash(p_api_key_hash text)
RETURNS SETOF public.nw_service_principals
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT *
    FROM public.nw_service_principals
    WHERE api_key_hash = p_api_key_hash
      AND status = 'active'
    LIMIT 1;
$$;

REVOKE ALL ON FUNCTION public.resolve_service_principal_by_api_key_hash(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.resolve_service_principal_by_api_key_hash(text) TO skillforge;

COMMENT ON TABLE public.nw_service_principals IS
    'Tenant Open Platform 第三方服务身份；API key 仅保存哈希，签名 secret 仅保存 AES-GCM 密文';
COMMENT ON FUNCTION public.resolve_service_principal_by_api_key_hash(text) IS
    '外部 Open Platform 认证用：按 API key 哈希查 active ServicePrincipal，SECURITY DEFINER 仅用于认证前租户解析';
