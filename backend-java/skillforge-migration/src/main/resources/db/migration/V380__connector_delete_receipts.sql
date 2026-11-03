-- 删除与成功回执同事务提交；回执独立于被删除的连接器。
CREATE TABLE public.nw_connector_delete_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(64) NOT NULL,
    instance_id varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id)
);
ALTER TABLE public.nw_connector_delete_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_connector_delete_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY connector_delete_tenant ON public.nw_connector_delete_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT ON public.nw_connector_delete_receipts TO skillforge;
COMMENT ON TABLE public.nw_connector_delete_receipts IS '连接器删除请求的成功回执';
COMMENT ON COLUMN public.nw_connector_delete_receipts.tenant_id IS '所属租户';
COMMENT ON COLUMN public.nw_connector_delete_receipts.request_id IS '客户端稳定请求标识';
COMMENT ON COLUMN public.nw_connector_delete_receipts.actor_ref IS '原操作的机构成员身份';
COMMENT ON COLUMN public.nw_connector_delete_receipts.instance_id IS '原操作的连接器实例标识';
COMMENT ON COLUMN public.nw_connector_delete_receipts.created_at IS '原操作成功时间';
