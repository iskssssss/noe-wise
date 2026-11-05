CREATE TABLE public.nw_tool_deployment_state_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    deployment_key varchar(128) NOT NULL,
    expected_revision bigint NOT NULL,
    active boolean NOT NULL,
    outcome varchar(16) NOT NULL DEFAULT 'PENDING',
    deployment_status varchar(16),
    result_revision bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tool_deployment_state_receipts_pk PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT tool_deployment_state_receipts_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT tool_deployment_state_receipts_revision CHECK (expected_revision > 0),
    CONSTRAINT tool_deployment_state_receipts_result CHECK (
        (outcome='SUCCEEDED' AND result_revision IS NOT NULL AND deployment_status IS NOT NULL AND result_revision=expected_revision+1
            AND deployment_status=CASE WHEN active THEN 'ACTIVE' ELSE 'DISABLED' END)
        OR (outcome IN ('PENDING','REJECTED') AND result_revision IS NULL AND deployment_status IS NULL))
);
ALTER TABLE public.nw_tool_deployment_state_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_tool_deployment_state_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY tool_deployment_state_receipts_tenant ON public.nw_tool_deployment_state_receipts
    USING (tenant_id::text=current_setting('app.tenant_id',true))
    WITH CHECK (tenant_id::text=current_setting('app.tenant_id',true));
GRANT SELECT,INSERT,UPDATE ON public.nw_tool_deployment_state_receipts TO skillforge;
COMMENT ON TABLE public.nw_tool_deployment_state_receipts IS '工具部署启停原命令与确定回执；成功与状态CAS同事务，未知身份不按期限清理';
COMMENT ON COLUMN public.nw_tool_deployment_state_receipts.result_revision IS '原请求成功时的部署修订，不从当前状态回填';
