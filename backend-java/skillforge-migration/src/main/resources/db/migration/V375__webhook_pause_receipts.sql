CREATE TABLE public.nw_webhook_pause_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(64) NOT NULL,
    subscription_id bigint NOT NULL,
    changed boolean,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id)
);
CREATE INDEX idx_webhook_pause_receipts_subscription ON public.nw_webhook_pause_receipts (tenant_id, subscription_id);
ALTER TABLE public.nw_webhook_pause_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_webhook_pause_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY webhook_pause_receipts_tenant ON public.nw_webhook_pause_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_webhook_pause_receipts TO skillforge;
COMMENT ON TABLE public.nw_webhook_pause_receipts IS 'Webhook 订阅暂停原操作回执';
COMMENT ON COLUMN public.nw_webhook_pause_receipts.tenant_id IS '所属机构';
COMMENT ON COLUMN public.nw_webhook_pause_receipts.request_id IS '原请求标识';
COMMENT ON COLUMN public.nw_webhook_pause_receipts.actor_ref IS '原操作成员';
COMMENT ON COLUMN public.nw_webhook_pause_receipts.subscription_id IS '请求暂停的订阅标识';
COMMENT ON COLUMN public.nw_webhook_pause_receipts.changed IS '原操作是否实际变更';
COMMENT ON COLUMN public.nw_webhook_pause_receipts.created_at IS '回执创建时间';
