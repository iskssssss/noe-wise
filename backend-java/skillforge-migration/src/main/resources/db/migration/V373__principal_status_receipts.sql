CREATE TABLE public.nw_principal_status_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(64) NOT NULL,
    principal_id bigint NOT NULL,
    action varchar(16) NOT NULL CHECK (action IN ('suspend', 'revoke')),
    changed boolean,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, principal_id) REFERENCES public.nw_service_principals (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_principal_status_receipts_principal ON public.nw_principal_status_receipts (tenant_id, principal_id);
ALTER TABLE public.nw_principal_status_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_principal_status_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY principal_status_receipts_tenant ON public.nw_principal_status_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_principal_status_receipts TO skillforge;
COMMENT ON TABLE public.nw_principal_status_receipts IS '服务身份暂停与吊销原操作回执';
COMMENT ON COLUMN public.nw_principal_status_receipts.tenant_id IS '所属机构';
COMMENT ON COLUMN public.nw_principal_status_receipts.request_id IS '原请求标识';
COMMENT ON COLUMN public.nw_principal_status_receipts.actor_ref IS '原操作成员';
COMMENT ON COLUMN public.nw_principal_status_receipts.principal_id IS '服务身份数据库标识';
COMMENT ON COLUMN public.nw_principal_status_receipts.action IS '原操作类型';
COMMENT ON COLUMN public.nw_principal_status_receipts.changed IS '原操作是否实际变更';
COMMENT ON COLUMN public.nw_principal_status_receipts.created_at IS '回执创建时间';
