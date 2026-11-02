-- 永久格式拒绝只属于公开投影进度；复用既有 Outbox 序号锁和 Session 生命周期。
ALTER TABLE public.nw_chat_session_outbox_sequences
    ADD COLUMN projection_source_sequence bigint,
    ADD COLUMN projection_source_event_id varchar(128),
    ADD COLUMN projection_policy_version varchar(128),
    ADD COLUMN projection_error_code varchar(128),
    ADD CONSTRAINT chk_skillforge_chat_session_outbox_projection_source CHECK (
        (projection_source_sequence IS NULL AND projection_source_event_id IS NULL
         AND projection_policy_version IS NULL AND projection_error_code IS NULL)
        OR (projection_source_sequence IS NOT NULL AND projection_source_sequence >= 0 AND projection_source_event_id IS NOT NULL
            AND projection_policy_version IS NOT NULL AND length(projection_policy_version) > 0
            AND (projection_source_sequence = 0 OR length(projection_source_event_id) > 0))
    );

COMMENT ON COLUMN public.nw_chat_session_outbox_sequences.projection_source_sequence IS
    '公开投影处理的原 SessionLog 最高序号；后续来源变化重新判断，不作为传输游标';
COMMENT ON COLUMN public.nw_chat_session_outbox_sequences.projection_source_event_id IS
    '原 SessionLog 水位事件身份；空日志为已确认的空字符串';
COMMENT ON COLUMN public.nw_chat_session_outbox_sequences.projection_policy_version IS
    '公开投影政策版本；版本变化解除原版本格式拒绝，不尝试历史兼容';
COMMENT ON COLUMN public.nw_chat_session_outbox_sequences.projection_error_code IS
    '该来源与政策的永久格式拒绝原因；成功为空，不改变 Run、Goal 或 Outbox 投递状态';
