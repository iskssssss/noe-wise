-- V62: Outbox 输出携带冻结的业务因果上下文，供 TraceView 从问题回放到可见结果。

ALTER TABLE public.nw_chat_session_outbox
    ADD COLUMN input_id varchar(128),
    ADD COLUMN root_input_id varchar(128),
    ADD COLUMN trace_id varchar(128),
    ADD COLUMN source_event_id varchar(128),
    ADD COLUMN caused_by_event_id varchar(128);

CREATE INDEX idx_skillforge_chat_session_outbox_root_input
    ON public.nw_chat_session_outbox (tenant_id, root_input_id, created_at)
    WHERE root_input_id IS NOT NULL;

COMMENT ON COLUMN public.nw_chat_session_outbox.root_input_id IS
    '产生该可见输出的原始用户问题；回调和恢复输出沿用同一根因';
COMMENT ON COLUMN public.nw_chat_session_outbox.source_event_id IS
    '生成输出的直接持久事件引用；不保存模型私有思维或原始第三方载荷';
