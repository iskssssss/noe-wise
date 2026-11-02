-- V61: UsageEvent 显式关联外部调用的逻辑 invocation 与真实 attempt。
-- 每次尝试的 UsageEvent 使用 attemptId:usage 作为稳定幂等键，避免重试被折叠为一条流水。

ALTER TABLE public.nw_usage_events
    ALTER COLUMN event_id TYPE varchar(192),
    ADD COLUMN invocation_id varchar(128),
    ADD COLUMN attempt_id varchar(160);

CREATE INDEX idx_skillforge_usage_events_invocation
    ON public.nw_usage_events (tenant_id, invocation_id, occurred_at)
    WHERE invocation_id IS NOT NULL;
CREATE INDEX idx_skillforge_usage_events_attempt
    ON public.nw_usage_events (tenant_id, attempt_id)
    WHERE attempt_id IS NOT NULL;

COMMENT ON COLUMN public.nw_usage_events.invocation_id IS
    '关联 external_invocations 的逻辑调用 ID；一个 invocation 可有多个 attempt';
COMMENT ON COLUMN public.nw_usage_events.attempt_id IS
    '关联 external_invocations 的实际调用尝试 ID；重试不与前次 attempt 合并';
