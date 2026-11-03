CREATE TABLE public.nw_tool_deployment_create_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    outcome varchar(16) NOT NULL DEFAULT 'PENDING',
    deployment_id bigint,
    deployment_key varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id),
    CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CHECK (outcome IN ('PENDING','SUCCEEDED','REJECTED')),
    CHECK ((outcome = 'SUCCEEDED' AND deployment_id IS NOT NULL AND deployment_key IS NOT NULL)
        OR (outcome = 'REJECTED' AND deployment_id IS NULL AND deployment_key IS NOT NULL)
        OR (outcome = 'PENDING' AND deployment_id IS NULL AND deployment_key IS NULL))
);
ALTER TABLE public.nw_tool_deployment_create_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_tool_deployment_create_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY tool_deployment_create_receipts_tenant ON public.nw_tool_deployment_create_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_tool_deployment_create_receipts TO skillforge;
COMMENT ON TABLE public.nw_tool_deployment_create_receipts IS '工具部署创建原请求与结果，和部署在同一事务提交';
