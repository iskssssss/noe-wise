-- Web/SSE 直接按已提交 Outbox 游标读取；网络重试仅由独立 Webhook delivery 账本负责。
DROP INDEX public.idx_skillforge_chat_session_outbox_ready;
DROP INDEX public.idx_skillforge_chat_session_outbox_publish_head;

ALTER TABLE public.nw_chat_session_outbox
    DROP CONSTRAINT chk_skillforge_chat_session_outbox_status,
    DROP COLUMN status,
    DROP COLUMN attempts,
    DROP COLUMN available_at,
    DROP COLUMN claimed_at,
    DROP COLUMN published_at,
    DROP COLUMN error_code;

COMMENT ON TABLE public.nw_chat_session_outbox IS
    '会话已提交输出事实：按session_sequence有序重放，按event_id幂等；不记录Web连接发布或已读状态';
