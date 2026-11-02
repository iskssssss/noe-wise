ALTER TABLE public.nw_service_principals
    ADD CONSTRAINT uq_service_principals_tenant_id UNIQUE (tenant_id, id);
CREATE TABLE public.nw_principal_rotation_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(64) NOT NULL,
    principal_id bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, principal_id) REFERENCES public.nw_service_principals (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_principal_rotation_receipts_principal ON public.nw_principal_rotation_receipts (tenant_id, principal_id);
ALTER TABLE public.nw_principal_rotation_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_principal_rotation_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY principal_rotation_receipts_tenant ON public.nw_principal_rotation_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT ON public.nw_principal_rotation_receipts TO skillforge;
COMMENT ON TABLE public.nw_principal_rotation_receipts IS '服务身份凭据轮换的成功回执，不含明文凭据';
COMMENT ON COLUMN public.nw_principal_rotation_receipts.tenant_id IS '所属机构';
COMMENT ON COLUMN public.nw_principal_rotation_receipts.request_id IS '原请求标识';
COMMENT ON COLUMN public.nw_principal_rotation_receipts.actor_ref IS '原操作成员';
COMMENT ON COLUMN public.nw_principal_rotation_receipts.principal_id IS '服务身份数据库标识';
COMMENT ON COLUMN public.nw_principal_rotation_receipts.created_at IS '轮换成功时间';
