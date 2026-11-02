-- 连接器更新的原请求回执与更新同事务提交；凭据不写入回执。
CREATE TABLE public.nw_connector_update_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(64) NOT NULL,
    instance_id varchar(64) NOT NULL,
    fingerprint text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES public.nw_connector_instances(tenant_id, instance_id) ON DELETE CASCADE
);
CREATE INDEX idx_connector_update_receipts_instance ON public.nw_connector_update_receipts (tenant_id, instance_id);
ALTER TABLE public.nw_connector_update_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_connector_update_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY connector_update_tenant ON public.nw_connector_update_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT ON public.nw_connector_update_receipts TO skillforge;
COMMENT ON TABLE public.nw_connector_update_receipts IS '连接器更新请求的成功回执';
COMMENT ON COLUMN public.nw_connector_update_receipts.tenant_id IS '所属租户';
COMMENT ON COLUMN public.nw_connector_update_receipts.request_id IS '客户端稳定请求标识';
COMMENT ON COLUMN public.nw_connector_update_receipts.actor_ref IS '原操作的机构成员身份';
COMMENT ON COLUMN public.nw_connector_update_receipts.instance_id IS '原操作的连接器实例标识';
COMMENT ON COLUMN public.nw_connector_update_receipts.fingerprint IS '包含凭据的更新请求摘要的认证密文';
COMMENT ON COLUMN public.nw_connector_update_receipts.created_at IS '原操作成功时间';
