-- 先登记唯一处理权，工具接入与成功回执在同一事务提交。
CREATE TABLE public.nw_tool_onboarding_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    owner_id uuid NOT NULL,
    connector_id varchar(64) NOT NULL,
    tool_names jsonb NOT NULL,
    status varchar(16) NOT NULL,
    response jsonb,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tool_onboarding_receipts_pk PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT tool_onboarding_receipts_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT tool_onboarding_receipts_selection CHECK (jsonb_typeof(tool_names) = 'array'
        AND jsonb_array_length(tool_names) BETWEEN 1 AND 100 AND octet_length(tool_names::text) <= 16384),
    CONSTRAINT tool_onboarding_receipts_response CHECK (
        (status = 'PROCESSING' AND response IS NULL AND completed_at IS NULL) OR
        (status IN ('SUCCEEDED', 'REJECTED') AND response IS NOT NULL AND completed_at IS NOT NULL
         AND jsonb_typeof(response) = 'object' AND response->>'status' IS NOT DISTINCT FROM status
         AND response->>'request_id' IS NOT DISTINCT FROM request_id::text AND octet_length(response::text) <= 131072))
);
ALTER TABLE public.nw_tool_onboarding_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_tool_onboarding_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY tool_onboarding_receipts_tenant ON public.nw_tool_onboarding_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_tool_onboarding_receipts TO skillforge;
COMMENT ON TABLE public.nw_tool_onboarding_receipts IS '工具接入原命令及回执；成功与业务共同提交，处理中不得重派，不按普通 TTL 清理';
COMMENT ON COLUMN public.nw_tool_onboarding_receipts.response IS '冻结的终态回执，成功仅含工具和版本等稳定身份；处理中为空';
