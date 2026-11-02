CREATE TABLE public.nw_platform_tenant_create_receipts (
    request_id uuid PRIMARY KEY,
    actor_ref varchar(128) NOT NULL,
    status varchar(16) NOT NULL,
    tenant_id bigint,
    tenant_name varchar(128),
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK ((status = 'PROCESSING' AND tenant_id IS NULL AND tenant_name IS NULL AND error_message IS NULL AND completed_at IS NULL)
        OR (status = 'SUCCEEDED' AND tenant_id IS NOT NULL AND tenant_name IS NOT NULL AND error_message IS NULL AND completed_at IS NOT NULL)
        OR (status = 'REJECTED' AND tenant_id IS NULL AND tenant_name IS NULL AND error_message IS NOT NULL AND completed_at IS NOT NULL))
);
ALTER TABLE public.nw_platform_tenant_create_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_platform_tenant_create_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_tenant_create_receipts_admin ON public.nw_platform_tenant_create_receipts
    USING (current_setting('app.platform_admin', true) = 'true')
    WITH CHECK (current_setting('app.platform_admin', true) = 'true');
GRANT SELECT, INSERT, UPDATE ON public.nw_platform_tenant_create_receipts TO skillforge;
COMMENT ON TABLE public.nw_platform_tenant_create_receipts IS '普通机构创建原请求回执；不保存请求正文或密码';
