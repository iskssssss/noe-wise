-- V78: 按租户第三方账单 Provider 凭据与受限响应 schema。
ALTER TABLE public.nw_tenants
    ADD COLUMN usage_reconciliation_provider varchar(64),
    ADD COLUMN usage_reconciliation_endpoint varchar(512),
    ADD COLUMN usage_reconciliation_api_key_encrypted text,
    ADD COLUMN usage_reconciliation_api_key_header varchar(128),
    ADD COLUMN usage_reconciliation_response_schema jsonb;

ALTER TABLE public.nw_tenants
    ADD CONSTRAINT ck_skillforge_tenants_usage_reconciliation_provider
    CHECK (usage_reconciliation_provider IS NULL
        OR usage_reconciliation_provider IN ('http'));

COMMENT ON COLUMN public.nw_tenants.usage_reconciliation_provider IS
    '租户第三方账单 provider；当前只允许受控 http 适配器';
COMMENT ON COLUMN public.nw_tenants.usage_reconciliation_endpoint IS
    '租户账单 endpoint；支持 tenantId/from/to/cursor URI 变量';
COMMENT ON COLUMN public.nw_tenants.usage_reconciliation_api_key_encrypted IS
    '租户账单 API key 的 AES-GCM 密文，禁止返回客户端';
COMMENT ON COLUMN public.nw_tenants.usage_reconciliation_api_key_header IS
    '租户账单 API key 请求头名称';
COMMENT ON COLUMN public.nw_tenants.usage_reconciliation_response_schema IS
    '受限 JSON path/字段别名映射；禁止脚本和表达式';
