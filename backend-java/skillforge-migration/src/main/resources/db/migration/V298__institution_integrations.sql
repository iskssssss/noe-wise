-- 平台管理映射不进入业务RLS；机构执行域与范围始终有固定Tenant/integration。
CREATE TABLE public.nw_external_institution_mappings (
    id bigint PRIMARY KEY,
    namespace varchar(128) NOT NULL CHECK (btrim(namespace) <> ''),
    external_institution_ref varchar(256) NOT NULL CHECK (btrim(external_institution_ref) <> ''),
    tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
    integration_id varchar(128) NOT NULL CHECK (btrim(integration_id) <> ''),
    provision_fingerprint varchar(64),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_by varchar(160) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (namespace, external_institution_ref),
    UNIQUE (tenant_id, integration_id)
);
COMMENT ON TABLE public.nw_external_institution_mappings IS '部署内唯一外部机构映射；受平台动作及目标授权保护的管理仓储';
COMMENT ON COLUMN public.nw_external_institution_mappings.id IS '稳定映射雪花主键';
COMMENT ON COLUMN public.nw_external_institution_mappings.namespace IS '获准创建或映射的外部命名空间';
COMMENT ON COLUMN public.nw_external_institution_mappings.external_institution_ref IS '命名空间内稳定机构键，不能重绑Tenant';
COMMENT ON COLUMN public.nw_external_institution_mappings.tenant_id IS '已创建或明确授权的真实Tenant';
COMMENT ON COLUMN public.nw_external_institution_mappings.integration_id IS '稳定执行域，不因凭证轮换改变';
COMMENT ON COLUMN public.nw_external_institution_mappings.provision_fingerprint IS '明确开通参数的规范摘要，关联已有机构时为空';
COMMENT ON COLUMN public.nw_external_institution_mappings.revision IS '当前映射修订，从1开始';
COMMENT ON COLUMN public.nw_external_institution_mappings.created_by IS '可信管理主体稳定身份';
COMMENT ON COLUMN public.nw_external_institution_mappings.created_at IS '数据库记录时间';

CREATE TABLE public.nw_institution_integrations (
    tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
    integration_id varchar(128) NOT NULL CHECK (btrim(integration_id) <> ''),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, integration_id)
);
COMMENT ON TABLE public.nw_institution_integrations IS '机构内稳定执行域；多个独立ServicePrincipal凭证共享，原生和手工签发不要求外部映射';
COMMENT ON COLUMN public.nw_institution_integrations.tenant_id IS '固定Tenant';
COMMENT ON COLUMN public.nw_institution_integrations.integration_id IS '固定integration';
COMMENT ON COLUMN public.nw_institution_integrations.created_at IS '执行域首次登记时间';
INSERT INTO public.nw_institution_integrations(tenant_id,integration_id)
SELECT DISTINCT tenant_id,integration_id FROM public.nw_service_principals;

CREATE FUNCTION public.register_institution_integration() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO public.nw_institution_integrations(tenant_id,integration_id)
    VALUES(NEW.tenant_id,NEW.integration_id) ON CONFLICT DO NOTHING;
    RETURN NEW;
END $$;
COMMENT ON FUNCTION public.register_institution_integration() IS 'ServicePrincipal真实创建时原子登记固定执行域，凭证轮换不创建新域';
CREATE TRIGGER service_principal_integration_registration BEFORE INSERT ON public.nw_service_principals
FOR EACH ROW EXECUTE FUNCTION public.register_institution_integration();
ALTER TABLE public.nw_service_principals ADD CONSTRAINT skillforge_service_principals_integration_fk
    FOREIGN KEY(tenant_id,integration_id) REFERENCES public.nw_institution_integrations(tenant_id,integration_id);
ALTER TABLE public.nw_agent_deployments ADD CONSTRAINT skillforge_agent_deployments_integration_fk
    FOREIGN KEY(tenant_id,integration_id) REFERENCES public.nw_institution_integrations(tenant_id,integration_id);

CREATE TABLE public.nw_institution_provision_requests (
    actor_ref varchar(160) NOT NULL,
    operation_kind varchar(256) NOT NULL,
    idempotency_key varchar(256) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
    result_ref varchar(64) NOT NULL,
    result_revision bigint NOT NULL CHECK (result_revision > 0),
    result_status varchar(16),
    integration_id varchar(128),
    principal_ref varchar(128),
    api_key_prefix varchar(32),
    scopes text[],
    principal_created_at timestamptz,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(actor_ref,operation_kind,idempotency_key),
    CHECK ((principal_ref IS NULL AND api_key_prefix IS NULL AND scopes IS NULL AND principal_created_at IS NULL)
        OR (principal_ref IS NOT NULL AND integration_id IS NOT NULL AND api_key_prefix IS NOT NULL
            AND scopes IS NOT NULL AND cardinality(scopes)>0 AND principal_created_at IS NOT NULL AND result_status IS NOT NULL))
);
COMMENT ON TABLE public.nw_institution_provision_requests IS '机构开通、映射、执行凭证和范围写入的同事务安全回执；不存储或重发秘密';
COMMENT ON COLUMN public.nw_institution_provision_requests.actor_ref IS '主体稳定身份，不包含凭据版本';
COMMENT ON COLUMN public.nw_institution_provision_requests.operation_kind IS '操作及对象范围';
COMMENT ON COLUMN public.nw_institution_provision_requests.idempotency_key IS '调用者幂等键';
COMMENT ON COLUMN public.nw_institution_provision_requests.fingerprint IS '类型化请求参数规范摘要';
COMMENT ON COLUMN public.nw_institution_provision_requests.tenant_id IS '结果所属Tenant';
COMMENT ON COLUMN public.nw_institution_provision_requests.result_ref IS '结果对象主键';
COMMENT ON COLUMN public.nw_institution_provision_requests.result_revision IS '原已提交结果修订';
COMMENT ON COLUMN public.nw_institution_provision_requests.result_status IS '原已提交状态；映射操作为空';
COMMENT ON COLUMN public.nw_institution_provision_requests.integration_id IS '凭证原固定执行域';
COMMENT ON COLUMN public.nw_institution_provision_requests.principal_ref IS '原凭证稳定主体引用';
COMMENT ON COLUMN public.nw_institution_provision_requests.api_key_prefix IS '原凭证安全前缀，不是API key';
COMMENT ON COLUMN public.nw_institution_provision_requests.scopes IS '原已签发执行scope集合';
COMMENT ON COLUMN public.nw_institution_provision_requests.principal_created_at IS '原执行主体创建时间';
COMMENT ON COLUMN public.nw_institution_provision_requests.recorded_at IS '数据库回执记录时间';

-- 旧应用范围不推断为新授权；保留到最终退役迁移，旧FK由后续来源迁移明确替换。
ALTER TABLE public.nw_integration_business_scopes RENAME TO nw_legacy_integration_business_scopes;
ALTER INDEX public.skillforge_integration_business_scopes_pkey RENAME TO skillforge_legacy_integration_business_scopes_pkey;
CREATE TABLE public.nw_integration_business_scopes (
    scope_id varchar(64) CONSTRAINT skillforge_integration_business_scopes_pkey PRIMARY KEY,
    tenant_id bigint NOT NULL,
    integration_id varchar(128) NOT NULL,
    scope_type varchar(64) NOT NULL CHECK (btrim(scope_type) <> ''),
    external_ref varchar(256) NOT NULL CHECK (btrim(external_ref) <> ''),
    display_name varchar(200) NOT NULL CHECK (btrim(display_name) <> ''),
    status varchar(16) NOT NULL CHECK(status IN ('active','suspended','revoked')),
    revision bigint NOT NULL CHECK(revision > 0),
    actor_ref varchar(160) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,integration_id,scope_type,external_ref),
    UNIQUE(tenant_id,integration_id,scope_id),
    FOREIGN KEY(tenant_id,integration_id) REFERENCES public.nw_institution_integrations(tenant_id,integration_id)
);
COMMENT ON TABLE public.nw_integration_business_scopes IS '可信机构执行域内显式声明的业务范围，不能扩大Tenant或integration授权';
COMMENT ON COLUMN public.nw_integration_business_scopes.scope_id IS '稳定业务范围身份，对外business_scope_id';
COMMENT ON COLUMN public.nw_integration_business_scopes.tenant_id IS '固定Tenant';
COMMENT ON COLUMN public.nw_integration_business_scopes.integration_id IS '固定执行域';
COMMENT ON COLUMN public.nw_integration_business_scopes.scope_type IS '范围类型';
COMMENT ON COLUMN public.nw_integration_business_scopes.external_ref IS '当前执行域内外部范围键';
COMMENT ON COLUMN public.nw_integration_business_scopes.display_name IS '创建时固定展示名称';
COMMENT ON COLUMN public.nw_integration_business_scopes.status IS 'active/suspended/revoked，revoked终态';
COMMENT ON COLUMN public.nw_integration_business_scopes.revision IS '状态CAS修订';
COMMENT ON COLUMN public.nw_integration_business_scopes.actor_ref IS '可信创建或最后修改执行主体';
COMMENT ON COLUMN public.nw_integration_business_scopes.created_at IS '数据库创建时间';
COMMENT ON COLUMN public.nw_integration_business_scopes.updated_at IS '数据库最后更新时间';
CREATE TABLE public.nw_institution_business_scope_requests (
    tenant_id bigint NOT NULL,
    integration_id varchar(128) NOT NULL,
    actor_ref varchar(160) NOT NULL,
    operation_kind varchar(256) NOT NULL,
    idempotency_key varchar(256) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    result_ref varchar(64) NOT NULL,
    result_revision bigint NOT NULL CHECK(result_revision>0),
    result_status varchar(16) NOT NULL CHECK(result_status IN ('active','suspended','revoked')),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,integration_id,actor_ref,operation_kind,idempotency_key),
    FOREIGN KEY(tenant_id,integration_id,result_ref) REFERENCES public.nw_integration_business_scopes(tenant_id,integration_id,scope_id)
);
COMMENT ON TABLE public.nw_institution_business_scope_requests IS '机构执行域中BusinessScope写入的原始安全回执，与平台管理事实分属不同RLS边界';
COMMENT ON COLUMN public.nw_institution_business_scope_requests.tenant_id IS '固定机构';
COMMENT ON COLUMN public.nw_institution_business_scope_requests.integration_id IS '固定执行域';
COMMENT ON COLUMN public.nw_institution_business_scope_requests.actor_ref IS '可信执行主体稳定身份';
COMMENT ON COLUMN public.nw_institution_business_scope_requests.operation_kind IS '范围ensure或status及精确对象范围';
COMMENT ON COLUMN public.nw_institution_business_scope_requests.idempotency_key IS '调用者幂等键';
COMMENT ON COLUMN public.nw_institution_business_scope_requests.fingerprint IS '类型化请求参数规范摘要';
COMMENT ON COLUMN public.nw_institution_business_scope_requests.result_ref IS '原业务范围主键';
COMMENT ON COLUMN public.nw_institution_business_scope_requests.result_revision IS '原已提交范围修订';
COMMENT ON COLUMN public.nw_institution_business_scope_requests.result_status IS '原已提交范围状态';
COMMENT ON COLUMN public.nw_institution_business_scope_requests.recorded_at IS '数据库回执记录时间';
ALTER TABLE public.nw_institution_business_scope_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_institution_business_scope_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_institution_business_scope_requests_tenant ON public.nw_institution_business_scope_requests
    USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::bigint);
ALTER TABLE public.nw_institution_integrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_institution_integrations FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_institution_integrations_tenant ON public.nw_institution_integrations
    USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::bigint);
ALTER TABLE public.nw_integration_business_scopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_integration_business_scopes FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_integration_business_scopes_tenant ON public.nw_integration_business_scopes
    USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::bigint);
GRANT SELECT,INSERT ON public.nw_external_institution_mappings,public.nw_institution_provision_requests TO skillforge;
GRANT SELECT,INSERT ON public.nw_institution_integrations TO skillforge;
GRANT SELECT,INSERT,UPDATE ON public.nw_integration_business_scopes TO skillforge;
GRANT SELECT,INSERT ON public.nw_institution_business_scope_requests TO skillforge;
