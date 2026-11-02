-- 消息版本只描述同一公开消息的语义更新；与 Run revision 和事件 schema 版本分离。
ALTER TABLE public.nw_chat_messages
    ADD COLUMN message_version bigint NOT NULL DEFAULT 1 CHECK (message_version > 0),
    ADD COLUMN input_references jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(input_references)='array');
