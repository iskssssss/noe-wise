-- V59: Session Outbox 发布租约、失败投影与恢复语义。

ALTER TABLE public.nw_chat_session_outbox
    ADD COLUMN claimed_at timestamptz,
    ADD COLUMN error_code varchar(128);

ALTER TABLE public.nw_chat_session_outbox
    DROP CONSTRAINT chk_skillforge_chat_session_outbox_status;
ALTER TABLE public.nw_chat_session_outbox
    ADD CONSTRAINT chk_skillforge_chat_session_outbox_status
    CHECK (status IN ('queued', 'publishing', 'published'));

CREATE INDEX idx_skillforge_chat_session_outbox_publish_head
    ON public.nw_chat_session_outbox (tenant_id, session_id, session_sequence)
    WHERE status IN ('queued', 'publishing');

COMMENT ON COLUMN public.nw_chat_session_outbox.claimed_at IS
    '当前发布租约的开始时间；过期 publishing 行可由后续 worker 重新领取';
COMMENT ON COLUMN public.nw_chat_session_outbox.error_code IS
    '最后一次投递失败的稳定错误码；不记录渠道响应、凭证或异常原文';
