-- V56: Session Outbox。

CREATE TABLE public.nw_chat_session_outbox (
    id                bigint NOT NULL,
    tenant_id         bigint NOT NULL,
    session_id        varchar(128) NOT NULL,
    runtime_run_id    varchar(128),
    event_id          varchar(128) NOT NULL,
    session_sequence  bigint NOT NULL,
    event_type        varchar(64) NOT NULL,
    status            varchar(16) NOT NULL DEFAULT 'queued',
    attempts          integer NOT NULL DEFAULT 0,
    available_at      timestamptz NOT NULL DEFAULT now(),
    published_at      timestamptz,
    payload           jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_chat_session_outbox_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_chat_session_outbox_event UNIQUE (tenant_id, event_id),
    CONSTRAINT uq_skillforge_chat_session_outbox_sequence UNIQUE (tenant_id, session_id, session_sequence),
    CONSTRAINT chk_skillforge_chat_session_outbox_status CHECK (status IN ('queued', 'published')),
    CONSTRAINT chk_skillforge_chat_session_outbox_sequence CHECK (session_sequence > 0)
);

CREATE INDEX idx_skillforge_chat_session_outbox_ready
    ON public.nw_chat_session_outbox (tenant_id, session_id, status, session_sequence);

CREATE INDEX idx_skillforge_chat_session_outbox_runtime_run
    ON public.nw_chat_session_outbox (tenant_id, session_id, runtime_run_id, created_at)
    WHERE runtime_run_id IS NOT NULL;

COMMENT ON COLUMN public.nw_chat_session_outbox.runtime_run_id IS '输出事件所属的Runtime Run；非Runtime输出为空';

ALTER TABLE public.nw_chat_session_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_chat_session_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_chat_session_outbox_tenant_isolation ON public.nw_chat_session_outbox
    USING ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''));

CREATE TABLE public.nw_chat_session_outbox_sequences (
    tenant_id      bigint NOT NULL,
    session_id     varchar(128) NOT NULL,
    next_sequence  bigint NOT NULL DEFAULT 0,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_chat_session_outbox_sequences_pkey PRIMARY KEY (tenant_id, session_id),
    CONSTRAINT chk_skillforge_chat_session_outbox_sequences_value CHECK (next_sequence >= 0)
);

ALTER TABLE public.nw_chat_session_outbox_sequences ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_chat_session_outbox_sequences FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_chat_session_outbox_sequences_tenant_isolation ON public.nw_chat_session_outbox_sequences
    USING ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.nw_chat_session_outbox IS
    '会话输出持久邮箱：按 sessionSequence 有序发布，消费者可按 eventId 去重';
