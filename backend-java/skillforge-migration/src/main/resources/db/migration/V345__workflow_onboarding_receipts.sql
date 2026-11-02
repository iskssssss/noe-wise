CREATE TABLE public.nw_workflow_onboarding_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    agent_id varchar(32) NOT NULL,
    workflow_id varchar(128) NOT NULL,
    status varchar(16) NOT NULL,
    response jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    PRIMARY KEY (tenant_id, request_id),
    CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CHECK ((status = 'PROCESSING' AND response IS NULL AND completed_at IS NULL) OR
        (status IN ('SUCCEEDED', 'REJECTED') AND response IS NOT NULL AND completed_at IS NOT NULL
         AND jsonb_typeof(response) = 'object' AND response->>'status' = status
         AND response->>'request_id' = request_id::text))
);
ALTER TABLE public.nw_workflow_onboarding_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_workflow_onboarding_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_onboarding_receipts_tenant ON public.nw_workflow_onboarding_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_workflow_onboarding_receipts TO skillforge;
COMMENT ON TABLE public.nw_workflow_onboarding_receipts IS '工作流组合接入原命令及回执；成功与业务共同提交，处理中不得重派';
