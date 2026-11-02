-- V60: 外部调用尝试账本。UsageEvent 仍是用量/费用事实，本表只记录调用边界与状态。

CREATE TABLE public.nw_external_invocations (
    id                   bigint NOT NULL,
    tenant_id            bigint NOT NULL,
    invocation_id        varchar(128) NOT NULL,
    attempt_id           varchar(160) NOT NULL,
    operation_type       varchar(64) NOT NULL,
    provider             varchar(128) NOT NULL,
    service              varchar(256) NOT NULL,
    idempotency_key      varchar(160),
    status               varchar(16) NOT NULL,
    input_id             varchar(128),
    root_input_id        varchar(128),
    operation_id         varchar(128),
    root_operation_id    varchar(128),
    trace_id             varchar(128) NOT NULL,
    span_id              varchar(128) NOT NULL,
    source_event_id      varchar(128) NOT NULL,
    caused_by_event_id   varchar(128),
    provider_request_id  varchar(256),
    error_code           varchar(128),
    started_at           timestamptz NOT NULL,
    ended_at             timestamptz,
    metadata             jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_external_invocations_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_external_invocations_attempt UNIQUE (tenant_id, attempt_id),
    CONSTRAINT ck_external_invocation_status
        CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'UNRESOLVED')),
    CONSTRAINT ck_external_invocation_context
        CHECK ((input_id IS NOT NULL AND root_input_id IS NOT NULL
                    AND operation_id IS NULL AND root_operation_id IS NULL)
               OR (operation_id IS NOT NULL AND root_operation_id IS NOT NULL
                    AND input_id IS NULL AND root_input_id IS NULL))
);

CREATE INDEX idx_skillforge_external_invocations_root_input
    ON public.nw_external_invocations (tenant_id, root_input_id, started_at)
    WHERE root_input_id IS NOT NULL;
CREATE INDEX idx_skillforge_external_invocations_root_operation
    ON public.nw_external_invocations (tenant_id, root_operation_id, started_at)
    WHERE root_operation_id IS NOT NULL;
CREATE INDEX idx_skillforge_external_invocations_unresolved
    ON public.nw_external_invocations (tenant_id, status, started_at)
    WHERE status IN ('STARTED', 'UNRESOLVED');

ALTER TABLE public.nw_external_invocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_external_invocations FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_external_invocations_tenant_isolation ON public.nw_external_invocations
    USING ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.nw_external_invocations IS
    '外部调用尝试账本：每个 attempt 先持久登记再调用 Provider；UsageEvent 另存用量、价格和费用';
