-- V68: Session Outbox 的独立 Webhook 订阅与至少一次投递账本。
-- Webhook 不复用 session_outbox.status：SSE 发布成功不等于第三方已确认，
-- 每个 subscription + eventId 各自领取、失败退避和确认。

CREATE TABLE public.nw_chat_session_event_subscriptions (
    id                       bigint NOT NULL,
    tenant_id                bigint NOT NULL,
    session_id               varchar(128),
    endpoint_url             varchar(2048) NOT NULL,
    signing_secret_encrypted text NOT NULL,
    status                   varchar(16) NOT NULL DEFAULT 'active',
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_chat_session_event_subscriptions_pkey PRIMARY KEY (id),
    CONSTRAINT ck_skillforge_chat_session_event_subscriptions_status CHECK (status IN ('active', 'paused', 'disabled'))
);

CREATE INDEX idx_skillforge_chat_session_event_subscriptions_active
    ON public.nw_chat_session_event_subscriptions (tenant_id, status, session_id);

CREATE TABLE public.nw_chat_session_outbox_webhook_deliveries (
    id                 bigint NOT NULL,
    tenant_id          bigint NOT NULL,
    subscription_id    bigint NOT NULL,
    outbox_event_id    bigint NOT NULL,
    event_id           varchar(128) NOT NULL,
    session_id         varchar(128) NOT NULL,
    session_sequence   bigint NOT NULL,
    status             varchar(16) NOT NULL DEFAULT 'queued',
    attempts           integer NOT NULL DEFAULT 0,
    available_at       timestamptz NOT NULL DEFAULT now(),
    claimed_at         timestamptz,
    delivered_at       timestamptz,
    error_code         varchar(128),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_chat_session_outbox_webhook_deliveries_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_chat_session_outbox_webhook_delivery UNIQUE (subscription_id, outbox_event_id),
    CONSTRAINT ck_skillforge_chat_session_outbox_webhook_delivery_status
        CHECK (status IN ('queued', 'publishing', 'delivered', 'unresolved')),
    CONSTRAINT fk_skillforge_chat_session_outbox_webhook_delivery_subscription
        FOREIGN KEY (subscription_id) REFERENCES public.nw_chat_session_event_subscriptions(id) ON DELETE CASCADE,
    CONSTRAINT fk_skillforge_chat_session_outbox_webhook_delivery_outbox
        FOREIGN KEY (outbox_event_id) REFERENCES public.nw_chat_session_outbox(id) ON DELETE CASCADE
);

CREATE INDEX idx_skillforge_chat_session_outbox_webhook_delivery_ready
    ON public.nw_chat_session_outbox_webhook_deliveries (tenant_id, subscription_id, status, available_at, session_sequence);

ALTER TABLE public.nw_chat_session_event_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_chat_session_event_subscriptions FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_chat_session_event_subscriptions_tenant_isolation ON public.nw_chat_session_event_subscriptions
    USING ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.nw_chat_session_outbox_webhook_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_chat_session_outbox_webhook_deliveries FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_chat_session_outbox_webhook_deliveries_te_e89737203e ON public.nw_chat_session_outbox_webhook_deliveries
    USING ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.nw_chat_session_event_subscriptions IS
    'Session Outbox 出站 Webhook 订阅；秘密仅保存 AES-GCM 密文，入站回调不复用本表';
COMMENT ON TABLE public.nw_chat_session_outbox_webhook_deliveries IS
    '每个订阅对每个 Session Outbox eventId 的至少一次投递账本；网络尝试另写 ExternalInvocation';
