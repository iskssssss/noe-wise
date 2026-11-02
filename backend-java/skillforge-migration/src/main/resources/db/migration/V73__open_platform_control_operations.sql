-- V73: Open Platform 非会话 ControlOperation 受理账本。
CREATE TABLE public.nw_control_operations (
    id                   bigint NOT NULL,
    tenant_id            bigint NOT NULL,
    operation_id         varchar(128) NOT NULL,
    root_operation_id    varchar(128) NOT NULL,
    integration_id       varchar(128) NOT NULL,
    operation_type       varchar(64) NOT NULL,
    status               varchar(32) NOT NULL,
    idempotency_key      varchar(256) NOT NULL,
    request_fingerprint  varchar(128) NOT NULL,
    items                jsonb NOT NULL DEFAULT '[]'::jsonb,
    accepted_at          timestamptz NOT NULL,
    completed_at         timestamptz,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT skillforge_control_operations_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_control_operations_operation UNIQUE (tenant_id, operation_id),
    CONSTRAINT uq_skillforge_control_operations_idempotency UNIQUE (tenant_id, integration_id, idempotency_key),
    CONSTRAINT ck_skillforge_control_operations_status CHECK (
        status IN ('accepted', 'processing', 'review_required', 'succeeded',
                   'partially_succeeded', 'failed', 'cancelled')),
    CONSTRAINT ck_skillforge_control_operations_items_array CHECK (jsonb_typeof(items) = 'array')
);

CREATE INDEX idx_skillforge_control_operations_tenant_updated
    ON public.nw_control_operations (tenant_id, updated_at DESC);
CREATE INDEX idx_skillforge_control_operations_root
    ON public.nw_control_operations (tenant_id, root_operation_id);

ALTER TABLE public.nw_control_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_control_operations FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_control_operations_tenant_insert ON public.nw_control_operations
    FOR INSERT WITH CHECK (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));
CREATE POLICY skillforge_control_operations_tenant_select ON public.nw_control_operations
    FOR SELECT USING (((tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text)));

COMMENT ON TABLE public.nw_control_operations IS
    'Open Platform 非会话控制操作受理账本；同租户 integration+幂等键同指纹只返回原事实，异指纹拒绝';
