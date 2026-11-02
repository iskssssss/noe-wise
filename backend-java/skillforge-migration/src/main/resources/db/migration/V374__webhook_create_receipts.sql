ALTER TABLE public.nw_chat_session_event_subscriptions
    ADD CONSTRAINT uq_chat_webhook_subscriptions_tenant_id UNIQUE (tenant_id, id);
CREATE TABLE public.nw_webhook_create_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(64) NOT NULL,
    subscription_id bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, subscription_id) REFERENCES public.nw_chat_session_event_subscriptions (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_webhook_create_receipts_subscription ON public.nw_webhook_create_receipts (tenant_id, subscription_id);
ALTER TABLE public.nw_webhook_create_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_webhook_create_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY webhook_create_receipts_tenant ON public.nw_webhook_create_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_webhook_create_receipts TO skillforge;
COMMENT ON TABLE public.nw_webhook_create_receipts IS 'Webhook 订阅创建原操作回执，不含签名密钥';
COMMENT ON COLUMN public.nw_webhook_create_receipts.tenant_id IS '所属机构';
COMMENT ON COLUMN public.nw_webhook_create_receipts.request_id IS '原请求标识';
COMMENT ON COLUMN public.nw_webhook_create_receipts.actor_ref IS '原操作成员';
COMMENT ON COLUMN public.nw_webhook_create_receipts.subscription_id IS '创建的订阅标识';
COMMENT ON COLUMN public.nw_webhook_create_receipts.created_at IS '回执创建时间';
