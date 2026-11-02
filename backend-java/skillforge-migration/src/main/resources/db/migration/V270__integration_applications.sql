-- 应用是跨机构治理身份；所有治理SQL由应用服务明确应用谓词，Runtime租户RLS保持独立。
CREATE TABLE public.nw_integration_applications (
    application_id varchar(64) PRIMARY KEY,
    name varchar(200) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'active' CHECK(status IN ('active','suspended','revoked')),
    default_plan_code varchar(64) NOT NULL REFERENCES public.nw_billing_plans(plan_code),
    max_tenants integer NOT NULL CHECK(max_tenants>0),
    allow_provision boolean NOT NULL DEFAULT false,
    quota_api_calls_per_month integer CHECK(quota_api_calls_per_month>=0),
    quota_max_knowledge_bases integer CHECK(quota_max_knowledge_bases>=0),
    quota_max_members integer CHECK(quota_max_members>=0),
    quota_max_channels integer CHECK(quota_max_channels>=0),
    rate_limit_per_minute integer CHECK(rate_limit_per_minute>=0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.nw_integration_applications IS '平台批准的业务应用与机构开通策略；应用治理身份不具有任意机构执行权限';
COMMENT ON COLUMN public.nw_integration_applications.application_id IS '稳定应用ID，采用项目雪花数字字符串';
COMMENT ON COLUMN public.nw_integration_applications.name IS '应用显示名称';
COMMENT ON COLUMN public.nw_integration_applications.status IS '应用授权状态，撤销为终态';
COMMENT ON COLUMN public.nw_integration_applications.default_plan_code IS '机构开通时启用套餐的唯一编码';
COMMENT ON COLUMN public.nw_integration_applications.max_tenants IS '应用允许关联的机构总数，暂停或撤销仍占用名额';
COMMENT ON COLUMN public.nw_integration_applications.allow_provision IS '是否允许应用自助开通新机构';
COMMENT ON COLUMN public.nw_integration_applications.quota_api_calls_per_month IS '开通机构的每月调用覆盖配额，空值继承套餐';
COMMENT ON COLUMN public.nw_integration_applications.quota_max_knowledge_bases IS '开通机构的知识库覆盖配额，空值继承套餐';
COMMENT ON COLUMN public.nw_integration_applications.quota_max_members IS '开通机构的成员覆盖配额，空值继承套餐';
COMMENT ON COLUMN public.nw_integration_applications.quota_max_channels IS '开通机构的渠道覆盖配额，空值继承套餐';
COMMENT ON COLUMN public.nw_integration_applications.rate_limit_per_minute IS '开通机构的每分钟速率覆盖，空值继承套餐';
COMMENT ON COLUMN public.nw_integration_applications.created_at IS '应用创建时刻';
COMMENT ON COLUMN public.nw_integration_applications.updated_at IS '策略与状态最后更新时间';

CREATE TABLE public.nw_integration_application_tenants (
    application_id varchar(64) NOT NULL REFERENCES public.nw_integration_applications(application_id),
    tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),
    namespace varchar(128) NOT NULL,
    external_institution_ref varchar(256) NOT NULL,
    integration_id varchar(128) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'active' CHECK(status IN ('active','suspended','revoked')),
    provision_fingerprint varchar(64),
    idempotency_key varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(application_id,tenant_id),
    UNIQUE(application_id,namespace,external_institution_ref),
    UNIQUE(tenant_id,integration_id),
    UNIQUE(application_id,idempotency_key)
);
COMMENT ON TABLE public.nw_integration_application_tenants IS '应用与机构授权关联；外部命名空间和机构标识严格按应用隔离';
COMMENT ON COLUMN public.nw_integration_application_tenants.application_id IS '平台批准的应用ID';
COMMENT ON COLUMN public.nw_integration_application_tenants.tenant_id IS '真实机构ID，执行和资源隔离边界';
COMMENT ON COLUMN public.nw_integration_application_tenants.namespace IS '外部业务命名空间，例如外部平台的全局命名空间';
COMMENT ON COLUMN public.nw_integration_application_tenants.external_institution_ref IS '外部机构稳定标识，例如外部平台的机构编号';
COMMENT ON COLUMN public.nw_integration_application_tenants.integration_id IS '本关联的稳定执行integration，不随凭证轮换变化';
COMMENT ON COLUMN public.nw_integration_application_tenants.status IS '机构关联的授权状态';
COMMENT ON COLUMN public.nw_integration_application_tenants.provision_fingerprint IS '开通请求规范指纹，拒绝同键异参';
COMMENT ON COLUMN public.nw_integration_application_tenants.idempotency_key IS '应用范围的开通幂等键，平台关联时为空';
COMMENT ON COLUMN public.nw_integration_application_tenants.created_at IS '机构授权关联创建时间';
CREATE INDEX idx_integration_tenant_application ON public.nw_integration_application_tenants(tenant_id,application_id);
COMMENT ON INDEX public.idx_integration_tenant_application IS '机构后台应用关联目录查询';

CREATE TABLE public.nw_integration_provision_requests (
    application_id varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    tenant_id bigint NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(application_id,idempotency_key),
    FOREIGN KEY(application_id,tenant_id) REFERENCES public.nw_integration_application_tenants(application_id,tenant_id)
);
COMMENT ON TABLE public.nw_integration_provision_requests IS '成功开通请求的幂等结果；同外部机构使用新幂等键也固定记录结果';
COMMENT ON COLUMN public.nw_integration_provision_requests.application_id IS '幂等键所属应用';
COMMENT ON COLUMN public.nw_integration_provision_requests.idempotency_key IS '调用方稳定请求键';
COMMENT ON COLUMN public.nw_integration_provision_requests.tenant_id IS '同事务产生或复用的机构结果';
COMMENT ON COLUMN public.nw_integration_provision_requests.request_fingerprint IS '规范化开通内容摘要';
COMMENT ON COLUMN public.nw_integration_provision_requests.created_at IS '成功处理时刻';

CREATE TABLE public.nw_integration_application_credentials (
    credential_id varchar(64) PRIMARY KEY,
    application_id varchar(64) NOT NULL REFERENCES public.nw_integration_applications(application_id),
    api_key_hash varchar(64) NOT NULL UNIQUE,
    api_key_prefix varchar(16) NOT NULL,
    signing_secret_encrypted text NOT NULL,
    scopes jsonb NOT NULL CHECK(jsonb_typeof(scopes)='array'),
    status varchar(16) NOT NULL CHECK(status IN ('active','revoked')),
    credential_version bigint NOT NULL CHECK(credential_version>0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.nw_integration_application_credentials IS '应用治理专用HMAC凭证；API哈希及AES-GCM密文不对外回显';
COMMENT ON COLUMN public.nw_integration_application_credentials.credential_id IS '治理凭证稳定ID';
COMMENT ON COLUMN public.nw_integration_application_credentials.application_id IS '固定绑定的应用ID';
COMMENT ON COLUMN public.nw_integration_application_credentials.api_key_hash IS 'API key SHA256哈希';
COMMENT ON COLUMN public.nw_integration_application_credentials.api_key_prefix IS '安全展示前缀';
COMMENT ON COLUMN public.nw_integration_application_credentials.signing_secret_encrypted IS 'HMAC签名密钥的AES-GCM密文';
COMMENT ON COLUMN public.nw_integration_application_credentials.scopes IS '动态治理权限字符串数组，签发时校验固定权限目录';
COMMENT ON COLUMN public.nw_integration_application_credentials.status IS '治理凭证状态';
COMMENT ON COLUMN public.nw_integration_application_credentials.credential_version IS '轮换单调版本';
COMMENT ON COLUMN public.nw_integration_application_credentials.created_at IS '签发时刻';
COMMENT ON COLUMN public.nw_integration_application_credentials.updated_at IS '轮换或撤销时刻';
CREATE INDEX idx_integration_credentials_application ON public.nw_integration_application_credentials(application_id,created_at);
COMMENT ON INDEX public.idx_integration_credentials_application IS '应用治理凭证目录查询';

CREATE TABLE public.nw_integration_business_scopes (
    scope_id varchar(64) CONSTRAINT skillforge_integration_business_scopes_pkey PRIMARY KEY,
    application_id varchar(64) NOT NULL,
    tenant_id bigint NOT NULL,
    scope_type varchar(64) NOT NULL,
    external_ref varchar(256) NOT NULL,
    display_name varchar(200) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'active' CHECK(status IN ('active','suspended','revoked')),
    UNIQUE(application_id,tenant_id,scope_type,external_ref),
    UNIQUE(application_id,tenant_id,scope_id),
    FOREIGN KEY(application_id,tenant_id) REFERENCES public.nw_integration_application_tenants(application_id,tenant_id)
);
COMMENT ON TABLE public.nw_integration_business_scopes IS '应用声明授权的机构内业务范围映射；不能作为跨机构资源权限';
COMMENT ON COLUMN public.nw_integration_business_scopes.scope_id IS '可信业务范围稳定ID';
COMMENT ON COLUMN public.nw_integration_business_scopes.application_id IS '声明应用';
COMMENT ON COLUMN public.nw_integration_business_scopes.tenant_id IS '所属真实机构';
COMMENT ON COLUMN public.nw_integration_business_scopes.scope_type IS '业务范围类型，例如store';
COMMENT ON COLUMN public.nw_integration_business_scopes.external_ref IS '应用提供的业务范围稳定标识';
COMMENT ON COLUMN public.nw_integration_business_scopes.display_name IS '业务范围展示名称';
COMMENT ON COLUMN public.nw_integration_business_scopes.status IS '明确业务范围授权状态';

CREATE TABLE public.nw_integration_application_audit (
    id bigint PRIMARY KEY,
    application_id varchar(64) NOT NULL REFERENCES public.nw_integration_applications(application_id),
    tenant_id bigint REFERENCES public.nw_tenants(id),
    actor_id varchar(128) NOT NULL,
    action varchar(128) NOT NULL,
    target_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.nw_integration_application_audit IS '应用治理同事务追加审计；不含外部个人标识、密钥或请求正文';
COMMENT ON COLUMN public.nw_integration_application_audit.id IS '审计雪花主键';
COMMENT ON COLUMN public.nw_integration_application_audit.application_id IS '审计应用范围';
COMMENT ON COLUMN public.nw_integration_application_audit.tenant_id IS '相关机构，应用级操作为空';
COMMENT ON COLUMN public.nw_integration_application_audit.actor_id IS '已认证平台操作者或应用身份';
COMMENT ON COLUMN public.nw_integration_application_audit.action IS '治理操作类型';
COMMENT ON COLUMN public.nw_integration_application_audit.target_id IS '内部目标ID';
COMMENT ON COLUMN public.nw_integration_application_audit.created_at IS '操作提交时刻';
CREATE INDEX idx_integration_audit_application ON public.nw_integration_application_audit(application_id,created_at,id);
COMMENT ON INDEX public.idx_integration_audit_application IS '应用治理审计顺序读取';
GRANT SELECT,INSERT,UPDATE ON public.nw_integration_applications,public.nw_integration_application_tenants,
    public.nw_integration_application_credentials,public.nw_integration_business_scopes,public.nw_integration_provision_requests TO skillforge;
GRANT SELECT,INSERT ON public.nw_integration_application_audit TO skillforge;
