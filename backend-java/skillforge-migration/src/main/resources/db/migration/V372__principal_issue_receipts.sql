CREATE TABLE public.nw_principal_issue_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(64) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    principal_id bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, principal_id) REFERENCES public.nw_service_principals (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_principal_issue_receipts_principal ON public.nw_principal_issue_receipts (tenant_id, principal_id);
ALTER TABLE public.nw_principal_issue_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_principal_issue_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY principal_issue_receipts_tenant ON public.nw_principal_issue_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_principal_issue_receipts TO skillforge;
COMMENT ON TABLE public.nw_principal_issue_receipts IS '服务身份签发原请求回执，不含凭据明文';
COMMENT ON COLUMN public.nw_principal_issue_receipts.tenant_id IS '所属机构';
COMMENT ON COLUMN public.nw_principal_issue_receipts.request_id IS '原请求标识';
COMMENT ON COLUMN public.nw_principal_issue_receipts.actor_ref IS '原操作成员';
COMMENT ON COLUMN public.nw_principal_issue_receipts.fingerprint IS '规范化签发输入摘要';
COMMENT ON COLUMN public.nw_principal_issue_receipts.principal_id IS '事务中生成的服务身份标识';
COMMENT ON COLUMN public.nw_principal_issue_receipts.created_at IS '回执创建时间';
