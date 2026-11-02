-- 成功投影状态与错误回执分离；状态、游标、Outbox 输出在同一行锁事务内提交。
ALTER TABLE public.nw_chat_session_outbox_sequences
    ADD COLUMN projection_checkpoint_sequence bigint,
    ADD COLUMN projection_checkpoint_event_id varchar(128),
    ADD COLUMN projection_checkpoint_policy varchar(128),
    ADD COLUMN projection_checkpoint_state jsonb,
    ADD CONSTRAINT chk_session_projection_checkpoint CHECK (
        (projection_checkpoint_sequence IS NULL AND projection_checkpoint_event_id IS NULL
            AND projection_checkpoint_policy IS NULL AND projection_checkpoint_state IS NULL)
        OR (projection_checkpoint_sequence IS NOT NULL AND projection_checkpoint_sequence >= 0
            AND projection_checkpoint_event_id IS NOT NULL
            AND (projection_checkpoint_sequence = 0 OR length(projection_checkpoint_event_id) > 0)
            AND projection_checkpoint_policy IS NOT NULL AND length(projection_checkpoint_policy) > 0
            AND projection_checkpoint_state IS NOT NULL AND jsonb_typeof(projection_checkpoint_state) = 'object'));

CREATE INDEX idx_session_events_run_type_sequence ON public.agent_runtime_session_events
    (tenant_id, target_kind, target_id, session_id, run_id, event_type, session_sequence);
