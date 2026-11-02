-- 受理投影只按完整 owner、Run 与 input 定位首个不可变来源，不重扫历史受理集合。
CREATE INDEX idx_session_events_input_first_source ON public.agent_runtime_session_events
    (tenant_id, target_kind, target_id, session_id, end_user_id, run_id, input_id, session_sequence);

COMMENT ON INDEX public.idx_session_events_input_first_source IS
    '按完整 SessionOwner、Run 和 input 定位首个不可变来源，保留受理输出的原始身份与 trace';
