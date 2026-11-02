-- Runtime EventId 是不透明身份；取消与恢复事件的组合身份可超过旧宿主 128 字符限制。
-- 保留原始身份与唯一约束，不截断、散列替换或重写事件事实。
ALTER TABLE public.nw_chat_session_outbox
    ALTER COLUMN event_id TYPE text,
    ALTER COLUMN source_event_id TYPE text,
    ALTER COLUMN caused_by_event_id TYPE text;

ALTER TABLE public.nw_chat_session_outbox_sequences
    ALTER COLUMN projection_source_event_id TYPE text,
    ALTER COLUMN projection_checkpoint_event_id TYPE text;

ALTER TABLE public.nw_chat_session_outbox_webhook_deliveries
    ALTER COLUMN event_id TYPE text;
