-- V72: Usage Ledger 对账/补偿追加事件；原始事件永不更新。
ALTER TABLE public.nw_usage_events
    ADD COLUMN usage_event_kind varchar(32) NOT NULL DEFAULT 'measured',
    ADD COLUMN amount_status varchar(32) NOT NULL DEFAULT 'unpriced',
    ADD COLUMN adjustment_of_event_id bigint;

ALTER TABLE public.nw_usage_events
    ADD CONSTRAINT ck_skillforge_usage_events_kind
        CHECK (usage_event_kind IN ('measured', 'reconciliation', 'adjustment')),
    ADD CONSTRAINT ck_skillforge_usage_events_amount_status
        CHECK (amount_status IN ('priced', 'unpriced', 'unresolved'));

CREATE INDEX idx_skillforge_usage_events_adjustment_of
    ON public.nw_usage_events (tenant_id, adjustment_of_event_id)
    WHERE adjustment_of_event_id IS NOT NULL;

COMMENT ON COLUMN public.nw_usage_events.usage_event_kind IS
    '追加事实类型：measured 原始计量、reconciliation 对账、adjustment 补偿；只 INSERT 不 UPDATE';
COMMENT ON COLUMN public.nw_usage_events.amount_status IS
    '金额事实状态：priced / unpriced / unresolved；未知金额不得按零聚合';
COMMENT ON COLUMN public.nw_usage_events.adjustment_of_event_id IS
    '对账或补偿事件引用的同租户原始用量事件 ID';
