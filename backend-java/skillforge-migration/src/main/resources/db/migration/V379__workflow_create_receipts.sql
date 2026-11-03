CREATE TABLE public.nw_workflow_create_receipts (
    request_id uuid CONSTRAINT nw_workflow_create_receipts_pkey PRIMARY KEY,
    tenant_id bigint NOT NULL,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL CONSTRAINT nw_workflow_create_receipts_fingerprint_check CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    workflow_id varchar(128),
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.nw_workflow_create_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_workflow_create_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_create_receipts_tenant ON public.nw_workflow_create_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_workflow_create_receipts TO skillforge;
COMMENT ON TABLE public.nw_workflow_create_receipts IS '工作流创建原请求身份及结果，与草稿同事务提交';
